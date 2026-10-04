'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { StringDecoder } = require('node:string_decoder');
const { spawn } = require('node:child_process');
const { EventEmitter } = require('node:events');
const { projectPlan, projectRequest } = require('../src/project');
const { ProjectBuildProcess, ProjectDependencies } = require('../src/projectReport');
const { createProjectTerminal } = require('../src/projectTask');
const { ProjectInputObservations } = require('../src/projectObservations');
const { createProjectWatchers } = require('../src/projectWatch');
const { BuildDiagnostics } = require('../src/diagnostics');
const { documentDigest } = require('../src/projectVersions');
const { configuredRuntime } = require('./vscodeRuntime');
const { readyDirectoryWatch } = require('./watchReadiness');
const { sameFileName } = require('../src/toolchain');

// These are native-process/component tests, not extension-host or native Windows
// UI tests. An old compiler is covered by the other compatibility tests. CI's
// proof-required mode must fail, never silently fall back, if proofs are absent.
const nativeProofOptions = {
  skip: process.env.VAS_TEST_REQUIRE_SOURCE_PROOFS !== '1' &&
    'Native source-proof cases require VAS_TEST_REQUIRE_SOURCE_PROOFS=1; old-compiler compatibility is tested separately.',
  timeout: 20000
};
const samePath = (left, right) => path.resolve(left) === path.resolve(right);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
async function bounded(promise, label, milliseconds = 10000) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}
class Emitter {
  constructor() {
    this.listeners = new Set();
    this.event = callback => { this.listeners.add(callback); return { dispose: () => this.listeners.delete(callback) }; };
  }
  fire(value) { for (const callback of this.listeners) callback(value); }
  dispose() { this.listeners.clear(); }
}

async function fixture(run, { canonicalAlias = false } = {}) {
  assert.ok(process.env.VAS_TEST_COMPILER, 'Proof-required tests require an actual VAS_TEST_COMPILER executable');
  const storage = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-native-proof-'));
  let root = storage;
  if (canonicalAlias) {
    try {
      const physical = path.join(storage, 'physical'); await fs.mkdir(physical);
      root = path.join(storage, 'lexical');
      // A directory junction does not need Windows file-symlink privileges.
      await fs.symlink(physical, root, process.platform === 'win32' ? 'junction' : 'dir');
    } catch (error) { await fs.rm(storage, { recursive: true, force: true }); throw error; }
  }
  const project = path.join(root, 'vas-project.json'), include = path.join(root, 'shared 文😀.vas');
  const bytes = Buffer.from('\ufeff/* 文😀 */\r\nint Shared() { int value; return value; }\r\n');
  const folder = { uri: { scheme: 'file', fsPath: root } }, collections = [], fileEvents = new Emitter(), watchPatterns = [];
  const vscode = {
    EventEmitter: Emitter, Uri: { file: fsPath => ({ scheme: 'file', fsPath }) },
    Range: class { constructor(line, character) { this.start = { line, character }; } },
    Diagnostic: class { constructor(range, message, severity) { Object.assign(this, { range, message, severity }); } },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2 },
    RelativePattern: class { constructor(base, pattern) { Object.assign(this, { base, pattern }); } },
    workspace: {
      isTrusted: true, textDocuments: [], workspaceFolders: [folder],
      getConfiguration: () => ({ inspect: () => ({ globalValue: process.env.VAS_TEST_COMPILER }) }),
      createFileSystemWatcher(pattern) {
        watchPatterns.push(pattern);
        return { onDidChange: fileEvents.event, onDidCreate: () => ({ dispose() {} }),
          onDidDelete: () => ({ dispose() {} }), dispose() {} };
      }
    }
  };
  const diagnostics = new BuildDiagnostics(() => {
    const collection = { entries: [], publications: [], clear() { this.entries = []; },
      set(entries) { this.entries = entries; this.publications.push(entries); }, dispose() {} };
    collections.push(collection); return collection;
  });
  const dependencies = new ProjectDependencies(), observations = new ProjectInputObservations(diagnostics, dependencies);
  const watchers = createProjectWatchers(vscode, dependencies, () => observations.invalidate(), observations);
  const request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, process.env.VAS_TEST_COMPILER);
  const builds = [], cleanup = [];
  function start({ onProof } = {}) {
    const build = { output: [], wire: [], proofs: [], closeCodes: [], closed: deferred(), nativeClosed: deferred(), completed: deferred() };
    builds.push(build);
    build.terminal = createProjectTerminal({ vscode, diagnostics, dependencies, observations, done() {},
      prepare: async cancel => {
        const plan = await projectPlan(request, undefined, { vscode, folder, cancel, dependencies });
        build.plan = plan;
        assert.equal(plan.inputVersions.match(include), undefined, 'this native invocation must start with a genuinely cold include');
        const observeInput = plan.observeInput;
        plan.observeInput = (file, proof) => {
          if (proof) {
            build.proofs.push({ file, proof });
            onProof?.(file, proof, build);
          }
          return observeInput(file, proof);
        };
        return plan;
      },
      createProcess(plan, callbacks) {
        build.native = new ProjectBuildProcess(plan, { ...callbacks,
          complete: async (code, report) => {
            build.processCode = code;
            build.report = report;
            try { await callbacks.complete(code, report); }
            finally { build.admissionCompleted = true; build.completed.resolve(); }
          }
        }, (executable, args, options) => {
          // Always launch the configured real executable. The audit is read-only:
          // no fake process, successful status, report bytes, or compiler proof.
          assert.equal(executable, process.env.VAS_TEST_COMPILER);
          assert.deepEqual(args, ['--report=jsonl', '--project', project, '--unit', 'main']);
          assert.equal(options.shell, false);
          const child = spawn(executable, args, options);
          build.child = child;
          const decoder = new StringDecoder('utf8');
          let pending = '', total = 0;
          child.stdout.on('data', chunk => {
            total += chunk.length;
            if (total > 128 * 1024) { build.auditError = 'native report audit limit exceeded'; return; }
            const lines = (pending + decoder.write(chunk)).split('\n');
            pending = lines.pop();
            for (const line of lines) {
              try { build.wire.push(JSON.parse(line)); }
              catch (error) { build.auditError = error.message; }
            }
          });
          child.once('close', (code, signal) => { build.nativeClose = { code, signal }; build.nativeClosed.resolve(build.nativeClose); });
          return child;
        });
        return build.native;
      }
    });
    build.terminal.onDidWrite(text => build.output.push(text));
    build.terminal.onDidClose(code => { build.closeCodes.push(code); build.closed.resolve(code); });
    build.terminal.open();
    return build;
  }
  const state = { root, project, include, bytes, vscode, collections, diagnostics, dependencies, observations, start, cleanup, watchPatterns,
    fsChanged: file => fileEvents.fire(vscode.Uri.file(file)) };
  try {
    await fs.writeFile(project, JSON.stringify({ schemaVersion: 1, compilationUnits: [
      { id: 'main', entry: 'main.vas', hostApi: { config: 'api.txt' }, output: 'out/main.vasbc' }
    ] }));
    await fs.writeFile(path.join(root, 'api.txt'), '');
    await fs.writeFile(path.join(root, 'main.vas'), '#include "shared 文😀.vas"\nvoid main() {}\n');
    await fs.writeFile(include, bytes);
    await run(state);
  } finally {
    // Release filesystem barriers even on assertion failure, then reap real
    // children before deleting their files or letting the test process exit.
    for (const dispose of cleanup.reverse()) dispose();
    for (const build of builds) build.terminal.dispose();
    let quiescent = true;
    try {
      for (const build of builds) {
        await bounded(build.closed.promise, 'terminal cleanup', 3000);
        if (build.native) {
          if (build.child) await bounded(build.nativeClosed.promise, 'native process cleanup', 5000);
          await bounded(build.completed.promise, 'proof admission cleanup', 5000);
        }
      }
    } finally {
      for (const build of builds) {
        if (build.child && !build.nativeClose) {
          build.child.kill('SIGKILL');
          try { await bounded(build.nativeClosed.promise, 'forced native process cleanup', 3000); }
          catch { quiescent = false; }
        }
        if (build.native && !build.admissionCompleted) {
          try { await bounded(build.completed.promise, 'forced proof admission cleanup', 3000); }
          catch { quiescent = false; }
        }
      }
      watchers.dispose(); observations.dispose(); diagnostics.dispose(); fileEvents.dispose();
      if (quiescent) await fs.rm(storage, { recursive: true, force: true });
      else console.error(`Preserving source-proof fixture because cleanup was not confirmed: ${storage}`);
    }
  }
}

function assertActualProof(state, build) {
  assert.equal(build.auditError, undefined);
  const record = build.wire.find(item => item.type === 'section_loaded' && samePath(item.section, state.include));
  assert.ok(record, 'the actual native stdout must contain the cold include section_loaded event');
  assert.equal(record.sourceDigestVersion, 1, 'proof-required mode cannot use an older no-proof compiler');
  assert.equal(record.sourceDigestAlgorithm, 'sha256');
  assert.equal(record.sourceByteLength, state.bytes.length);
  assert.equal(record.sourceDigest, digest(state.bytes), 'native evidence must describe the exact BOM/CRLF/Unicode bytes');
  const delivered = build.proofs.find(item => item.file === record.section);
  assert.deepEqual(delivered?.proof, { version: 1, algorithm: 'sha256', byteLength: state.bytes.length, digest: digest(state.bytes) },
    'the production report consumer must deliver the native proof to the actual project plan');
  return record;
}
function assertNativeSuccess(build) {
  assert.equal(build.nativeClose?.code, 0, build.output.join(''));
  assert.equal(build.nativeClose.signal, null);
  assert.equal(build.wire.find(item => item.type === 'result')?.success, true);
  assert.ok(build.wire.some(item => item.type === 'diagnostic' && item.severity === 'warning'), 'the compiler must produce a real warning');
}
function warningEntries(state) {
  return state.collections.flatMap(collection => collection.entries).filter(([uri, items]) =>
    samePath(uri.fsPath, state.include) && items.some(item => item.severity === state.vscode.DiagnosticSeverity.Warning));
}
function assertNeverPublished(state) {
  assert.ok(state.collections.every(collection => collection.entries.length === 0 && collection.publications.length === 0),
    'no native diagnostics may be published from an unverified or cancelled generation');
}

function holdColdAdmission(state) {
  const originalOpen = fs.open, entered = deferred(), release = deferred();
  const gate = { entered, released: false, returned: false, proofReceived: false };
  // Gate before opening/reading the local path. Native reads are in a separate
  // process and unaffected; preparation never knows this include yet.
  fs.open = async function(file, ...args) {
    if (typeof file === 'string' && samePath(file, state.include) && gate.proofReceived && !gate.file) {
      gate.file = file;
      entered.resolve();
      await release.promise;
      const handle = await originalOpen.call(this, file, ...args);
      gate.returned = true;
      return handle;
    }
    return originalOpen.call(this, file, ...args);
  };
  gate.onProof = (file, proof, build) => {
    if (samePath(file, state.include)) {
      // This proof came through ProjectReport, and also has an independently
      // observed wire record from the real child before the gate can activate.
      assertActualProof(state, build);
      gate.proofReceived = true;
    }
  };
  gate.release = () => { gate.released = true; release.resolve(); };
  state.cleanup.push(() => { fs.open = originalOpen; gate.release(); });
  return gate;
}

async function awaitHeldNative(state, build, gate) {
  await bounded(Promise.race([gate.entered.promise, build.closed.promise.then(code => {
    throw new Error(`Task closed ${code} before source-proof admission (native started: ${Boolean(build.child)}, proofs: ${build.proofs.length}):\n${build.output.join('').slice(0, 8192)}`);
  })]), 'actual loaded-source proof to reach the local admission read');
  assertActualProof(state, build);
  assert.equal(build.plan.inputVersions.match(gate.file), undefined);
  assert.equal(gate.returned, false, 'the local admission read must still be pending');
  await bounded(build.nativeClosed.promise, 'real compiler to exit while local admission is held');
  assertNativeSuccess(build);
  assert.deepEqual(build.closeCodes, [], 'native exit 0 cannot finish the task before proof admission');
}

test('real native proof rejects changed cold-include bytes before local admission even after native exit 0', nativeProofOptions,
  async () => fixture(async state => {
    const gate = holdColdAdmission(state), build = state.start({ onProof: gate.onProof });
    await awaitHeldNative(state, build, gate);
    await fs.writeFile(state.include, Buffer.concat([state.bytes, Buffer.from('// saved after the compiler loaded this section\n')]));
    gate.release();
    assert.equal(await bounded(build.closed.promise, 'mismatched source-proof task failure'), 1);
    assert.equal(build.processCode, 1);
    assert.match(build.report.error?.message || '', /source no longer matches the bytes loaded by the compiler/);
    assert.equal(build.plan.inputVersions.match(gate.file), undefined, 'new disk bytes must never become this build baseline');
    assertNeverPublished(state);
    assert.doesNotMatch(build.output.join(''), /VAS: Built project unit/);
    assert.ok((await fs.stat(build.plan.output)).size > 0, 'a real native successful artifact does not authorize stale diagnostics');
  }));

test('cancelling a real native proof admission closes 130 synchronously and a late local read cannot admit or publish', nativeProofOptions,
  async () => fixture(async state => {
    const gate = holdColdAdmission(state), build = state.start({ onProof: gate.onProof });
    await awaitHeldNative(state, build, gate);
    build.terminal.close();
    assert.deepEqual(build.closeCodes, [130], 'cancellation must close immediately without waiting for the local read');
    assert.equal(gate.returned, false);
    const closedOutput = build.output.join('');
    gate.release();
    await bounded(build.completed.promise, 'cancelled native admission to settle after the read is released');
    assert.equal(gate.returned, true, 'the filesystem operation really completed after cancellation');
    await assert.rejects(build.plan.waitForSourceProofs(), /cancelled/);
    assert.equal(build.processCode, 130);
    assert.equal(build.plan.inputVersions.match(gate.file), undefined);
    assert.deepEqual(build.closeCodes, [130], 'late native completion must not emit a second close');
    assert.equal(build.output.join(''), closedOutput, 'late completion must not emit success or diagnostics output');
    assertNeverPublished(state);
  }));

test('a first real native include proof preserves warnings across pending and late same-byte FS events; later changes clear them', nativeProofOptions,
  async () => {
    for (const mode of ['changed-bytes', 'same-byte-inode-replacement']) await fixture(async state => {
      const gate = holdColdAdmission(state), build = state.start({ onProof: gate.onProof });
      await awaitHeldNative(state, build, gate);
      state.fsChanged(state.include);
      assertNeverPublished(state);
      gate.release();
      assert.equal(await bounded(build.closed.promise, `${mode}: first native warning publication`), 0, build.output.join(''));
      const nativeSection = assertActualProof(state, build).section;
      const baseline = build.plan.inputVersions.match(nativeSection);
      assert.ok(baseline, 'the first-seen include needs the actual compiler proof before it becomes a baseline');
      assert.equal(baseline.file, nativeSection, 'admission preserves the exact compiler-owned section identity');
      assert.equal(baseline.digest, digest(state.bytes));
      assert.equal(warningEntries(state).length, 1);
      const publication = warningEntries(state)[0];
      state.fsChanged(state.include);
      await bounded(state.observations.settle(), 'late same-byte FS event');
      assert.equal(warningEntries(state)[0], publication, 'a late saved-byte event must retain the existing warning publication');
      if (mode === 'changed-bytes') await fs.appendFile(state.include, '// actual saved change\n');
      else {
        const replacement = path.join(state.root, 'replacement.vas');
        await fs.writeFile(replacement, state.bytes);
        const replacementStat = await fs.stat(replacement, { bigint: true });
        assert.notEqual(replacementStat.ino.toString(), baseline.ino, 'the same-byte replacement must have a distinct inode');
        await fs.rename(replacement, state.include);
        assert.equal(digest(await fs.readFile(state.include)), baseline.digest);
      }
      state.fsChanged(state.include);
      await bounded(state.observations.settle(), `${mode}: invalidation`);
      assert.equal(warningEntries(state).length, 0, mode);
      await assert.rejects(build.plan.checkFresh(), /input changed/, mode);
    });
  });

test('real native publication survives a single metadata-only change delivered by the selected VS Code Parcel backend', nativeProofOptions,
  async () => fixture(async state => {
    const runtime = await configuredRuntime();
    const parcel = require(runtime.parcel);
    const root = await fs.realpath(state.root), included = await fs.realpath(state.include);
    const audit = { version: runtime.version, commit: runtime.commit, parcelVersion: runtime.parcelVersion, backend: runtime.backend,
      parcel: runtime.parcel, phase: 'subscribe', events: [], dropped: 0 };
    let armed = false, watcherError, subscription, readiness, observeReadiness, bridge;
    const received = deferred();
    try {
      // Use the product's actual native module/backend. Node fs.watch's macOS
      // rename/change classification is not Parcel's stat-based update mapping.
      // Parcel exposes subscription completion; the GUI fixture still proves
      // delivery through the extension's own existing FileSystemWatcher.
      subscription = await parcel.subscribe(root, (error, events) => {
        if (error) { watcherError = error; audit.error = error.message; bridge?.emit('error', error); received.resolve(); return; }
        for (const event of events) {
          if (audit.events.length < 64) audit.events.push({ type: event.type, path: event.path, phase: audit.phase }); else audit.dropped++;
          if (sameFileName(path.dirname(event.path), root)) observeReadiness?.(event.type, path.basename(event.path));
          if (armed && event.type === 'update' && sameFileName(event.path, included)) {
            state.fsChanged(state.include); received.resolve();
          }
        }
      }, { backend: runtime.backend });
      assert.ifError(watcherError);
      audit.phase = 'readiness';
      readiness = await readyDirectoryWatch(root, () => {}, { createWatcher(_directory, receive) {
        // Forward this same Parcel subscription, never create a Node watcher.
        observeReadiness = receive; bridge = new EventEmitter();
        bridge.close = () => { observeReadiness = undefined; };
        return bridge;
      } });
      audit.readiness = readiness.audit;
      audit.phase = 'compile';
      const build = state.start();
      assert.equal(await bounded(build.closed.promise, 'single native build'), 0, build.output.join(''));
      assertActualProof(state, build); assertNativeSuccess(build);
      const publication = warningEntries(state)[0]; assert.ok(publication);
      const before = await fs.stat(state.include, { bigint: true });
      assert.ifError(watcherError);
      armed = true;
      audit.phase = 'post-publication-touch';
      await fs.utimes(state.include, Number(before.atimeNs) / 1e9, Number(before.mtimeNs) / 1e9 + 2);
      await bounded(received.promise, 'post-publication Parcel metadata notification');
      assert.ifError(watcherError);
      await bounded(state.observations.settle(), 'classify the actual metadata notification');
      const after = await fs.stat(state.include, { bigint: true });
      audit.mtime = { before: before.mtimeNs.toString(), after: after.mtimeNs.toString() };
      assert.notEqual(after.mtimeNs, before.mtimeNs);
      assert.equal(after.dev, before.dev); assert.equal(after.ino, before.ino); assert.equal(after.size, before.size);
      assert.deepEqual(await fs.readFile(state.include), state.bytes);
      assert.equal(warningEntries(state)[0], publication);
      assert.deepEqual(build.closeCodes, [0]);
      assert.equal(build.wire.filter(record => record.type === 'start').length, 1);
    } finally {
      readiness?.close();
      try { await subscription?.unsubscribe(); assert.ifError(watcherError); }
      finally { console.log('VAS Parcel metadata evidence:', JSON.stringify(audit)); }
    }
  }));

test('real cold-include proofs retain mandatory dirty and clean physical-alias checks', nativeProofOptions, async () => {
  for (const mode of ['dirty-alias', 'clean-alias-mismatch', 'clean-alias-match', 'unrelated-equal-bytes']) await fixture(async state => {
    const alias = path.join(state.root, 'editor alias.txt'), unrelated = path.join(state.root, 'same bytes.txt');
    await fs.link(state.include, alias);
    await fs.writeFile(unrelated, state.bytes);
    const editorText = state.bytes.toString('utf8').slice(1); // VS Code strips the UTF-8 BOM.
    const file = mode === 'unrelated-equal-bytes' ? unrelated : alias;
    const document = { uri: state.vscode.Uri.file(file), version: 1, isClosed: false,
      isDirty: mode === 'dirty-alias' || mode === 'unrelated-equal-bytes',
      getText: () => mode === 'clean-alias-mismatch' ? 'different clean editor contents' : editorText };
    state.vscode.workspace.textDocuments.push(document);
    const build = state.start();
    const code = await bounded(build.closed.promise, `${mode}: native task completion`);
    assert.ok(build.child, `${mode}: native compiler never started:\n${build.output.join('').slice(0, 8192)}`);
    await bounded(build.nativeClosed.promise, `${mode}: native exit`);
    const record = assertActualProof(state, build);
    assertNativeSuccess(build);
    assert.ok(build.plan.inputVersions.match(record.section));
    assert.equal(await build.plan.inputVersions.related(alias), true, 'the editor alias must be proven by actual device/inode identity');
    assert.equal(await build.plan.inputVersions.related(unrelated), false, 'equal content alone never creates an input alias');
    if (mode === 'dirty-alias' || mode === 'clean-alias-mismatch') {
      assert.equal(code, 1, mode);
      assert.match(build.output.join(''), mode === 'dirty-alias' ? /Save the VAS project input.*editor alias\.txt/ : /editor content does not match.*editor alias\.txt/);
      assertNeverPublished(state);
      assert.doesNotMatch(build.output.join(''), /VAS: Built project unit/);
    } else {
      assert.equal(code, 0, build.output.join(''));
      assert.equal(warningEntries(state).length, 1);
      state.observations.changed(alias, { documentDigest: documentDigest(editorText) });
      await bounded(state.observations.settle(), 'matching clean physical alias event');
      assert.equal(warningEntries(state).length, 1);
      state.observations.changed(alias, { documentDigest: documentDigest('mismatched clean editor contents') });
      await bounded(state.observations.settle(), 'mismatched clean physical alias event');
      assert.equal(warningEntries(state).length, 0, 'a clean flag and matching disk bytes cannot excuse different editor text');
    }
  });
});

test('native proof fixture models canonical-path external watchers before starting its real compiler', nativeProofOptions,
  async () => fixture(async state => {
    const canonical = await fs.realpath(state.root);
    assert.notEqual(state.root, canonical, 'the fixture must force a real lexical/canonical workspace difference');
    const gate = holdColdAdmission(state), build = state.start({ onProof: gate.onProof });
    await awaitHeldNative(state, build, gate);
    assert.ok(state.watchPatterns.some(pattern => pattern instanceof state.vscode.RelativePattern &&
      pattern.base === canonical && pattern.pattern === '*'), 'actual dependency registration must construct the canonical-directory watcher');
    gate.release();
    assert.equal(await bounded(build.closed.promise, 'canonical-path native proof completion'), 0, build.output.join(''));
    assert.equal(warningEntries(state).length, 1);
  }, { canonicalAlias: true }));

test('native proof fixture reports an early watcher setup failure instead of waiting for a compiler that never started', nativeProofOptions,
  async () => fixture(async state => {
    delete state.vscode.RelativePattern;
    const gate = holdColdAdmission(state), build = state.start({ onProof: gate.onProof });
    await assert.rejects(awaitHeldNative(state, build, gate), /Task closed 1 before source-proof admission[\s\S]*vscode\.RelativePattern is not a constructor/);
    assert.equal(build.child, undefined);
    assert.equal(build.native, undefined);
    assert.deepEqual(build.closeCodes, [1]);
    assert.equal(build.proofs.length, 0);
  }, { canonicalAlias: true }));
