'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { BuildDiagnostics } = require('../src/diagnostics');
const { ProjectDependencies } = require('../src/projectReport');
const { ProjectInputObservations } = require('../src/projectObservations');
const { captureVersions, documentDigest, readVersion } = require('../src/projectVersions');

async function fixture(run) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-observations-'));
  const source = path.join(root, 'source 文😀.vas');
  await fs.writeFile(source, 'void main() {}\n');
  const diagnostics = new BuildDiagnostics(() => ({ clear() {}, set() {}, dispose() {} }));
  const dependencies = new ProjectDependencies();
  const observations = new ProjectInputObservations(diagnostics, dependencies);
  async function register(key = 'unit') {
    const plan = { key, source, project: path.join(root, 'vas-project.json'), config: path.join(root, 'api.txt'),
      inputVersions: await captureVersions([source]) };
    dependencies.update(plan, { observed: new Map() }, true);
    const token = diagnostics.begin(key);
    observations.register(plan, token);
    return { plan, token };
  }
  try { await run({ root, source, diagnostics, dependencies, observations, register }); }
  finally { observations.dispose(); diagnostics.dispose(); await fs.rm(root, { recursive: true, force: true }); }
}

test('late same-saved-byte FS and clean-document events preserve their original generation', async () => fixture(async state => {
  const { token } = await state.register();
  state.observations.changed(state.source);
  await state.observations.settle();
  assert.equal(state.diagnostics.current(token), true);
  state.observations.changed(state.source, { documentDigest: documentDigest('void main() {}\n') });
  await state.observations.settle();
  assert.equal(state.diagnostics.current(token), true);
  state.observations.changed(state.source, { documentDigest: documentDigest('different clean editor contents') });
  await state.observations.settle();
  assert.equal(state.diagnostics.current(token), false, 'a clean flag cannot excuse different text');
}));

test('proven physical hardlink event preserves compiler identity and changed bytes invalidate', async () => fixture(async state => {
  const alias = path.join(state.root, 'editor alias.txt');
  await fs.link(state.source, alias);
  const { token } = await state.register();
  state.observations.changed(alias, { documentDigest: documentDigest('void main() {}\n') });
  await state.observations.settle();
  assert.equal(state.diagnostics.current(token), true);
  await fs.appendFile(alias, '// changed\n');
  state.observations.changed(alias);
  await state.observations.settle();
  assert.equal(state.diagnostics.current(token), false);
}));

test('unknown newly discovered input, deletion, identity replacement and failed reads invalidate', async () => {
  for (const mode of ['unknown', 'delete', 'replace', 'read-error']) await fixture(async state => {
    const { plan, token } = await state.register();
    let file = state.source, options;
    if (mode === 'unknown') {
      file = path.join(state.root, 'first-seen.vas');
      await fs.writeFile(file, 'void main() {}\n');
      await state.dependencies.observe(plan, { key: 'exact-new-section', file });
    } else if (mode === 'delete') options = { kind: 'delete' };
    else if (mode === 'replace') {
      const replacement = path.join(state.root, 'replacement');
      await fs.writeFile(replacement, 'void main() {}\n');
      await fs.rename(replacement, file);
    } else state.observations.read = async () => { throw new Error('unreadable'); };
    state.observations.changed(file, options);
    await state.observations.settle();
    assert.equal(state.diagnostics.current(token), false, mode);
  });
});

test('same entry in another unit cannot overwrite an older generation baseline', async () => fixture(async state => {
  const old = await state.register('old-config');
  await fs.appendFile(state.source, '// external change before another unit\n');
  const next = await state.register('new-config');
  state.observations.changed(state.source);
  await state.observations.settle();
  assert.equal(state.diagnostics.current(old.token), false);
  assert.equal(state.diagnostics.current(next.token), false, 'global conservative invalidation is retained');
}));

test('late old checks re-read for a replacement generation instead of clearing its correct diagnostics', async () => fixture(async state => {
  await state.register();
  let release, reads = 0;
  state.observations.read = async (...args) => {
    const version = await readVersion(...args);
    if (++reads === 1) await new Promise(resolve => { release = resolve; });
    return version;
  };
  await fs.appendFile(state.source, '// new saved version\n');
  state.observations.changed(state.source);
  while (!release) await new Promise(resolve => setImmediate(resolve));
  state.observations.invalidate();
  const latest = await state.register();
  release();
  await state.observations.settle();
  assert.equal(reads, 2);
  assert.equal(state.diagnostics.current(latest.token), true);
}));

test('settlement waits for queued changes and disposal fences outstanding classification', async () => fixture(async state => {
  const { token } = await state.register();
  let release;
  state.observations.read = async (...args) => {
    await new Promise(resolve => { release = resolve; });
    return readVersion(...args);
  };
  state.observations.changed(state.source);
  let settled = false;
  const waiting = state.observations.settle().then(() => { settled = true; });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(settled, false);
  state.observations.dispose();
  release(); await waiting;
  assert.equal(state.diagnostics.current(token), true, 'disposed callbacks cannot touch diagnostics');
}));

test('bounded event queue overflow conservatively invalidates without retaining an unbounded backlog', async () => fixture(async state => {
  const { token } = await state.register();
  let release;
  state.observations.read = async (...args) => {
    await new Promise(resolve => { release = resolve; });
    return readVersion(...args);
  };
  state.observations.changed(state.source);
  for (let index = 0; index < 66; index++) state.observations.changed(path.join(state.root, `${index}.vas`));
  assert.equal(state.diagnostics.current(token), false);
  assert.ok(state.observations.queue.size <= 64);
  release(); await state.observations.settle();
}));

test('settlement drains an event queued between worker resolution and pending-finally cleanup', async () => fixture(async state => {
  const { token } = await state.register();
  const original = state.observations.process.bind(state.observations);
  let workers = 0;
  state.observations.process = async () => {
    await original();
    if (++workers === 1) queueMicrotask(() => state.observations.changed(state.source,
      { documentDigest: documentDigest('late different clean document') }));
  };
  state.observations.changed(state.source);
  await state.observations.settle();
  assert.equal(workers, 2);
  assert.equal(state.observations.queue.size, 0);
  assert.equal(state.diagnostics.current(token), false, 'the interleaved event must be classified before settlement');
}));

test('coalesced filesystem notifications cannot erase a clean-editor text mismatch', async () => fixture(async state => {
  const { plan, token } = await state.register();
  const second = path.join(state.root, 'second.vas');
  await fs.writeFile(second, 'void second() {}\n');
  plan.inputVersions = await captureVersions([state.source, second]);
  state.observations.register(plan, token);
  let release, first = true;
  state.observations.read = async (...args) => {
    if (first) { first = false; await new Promise(resolve => { release = resolve; }); }
    return readVersion(...args);
  };
  state.observations.changed(state.source);
  state.observations.changed(second, { documentDigest: documentDigest('wrong clean editor text') });
  state.observations.changed(second);
  release(); await state.observations.settle();
  assert.equal(state.diagnostics.current(token), false);
}));

test('conflicting coalesced clean-editor versions fail conservatively', async () => fixture(async state => {
  const { token } = await state.register();
  let release;
  state.observations.read = async (...args) => {
    await new Promise(resolve => { release = resolve; });
    return readVersion(...args);
  };
  state.observations.changed(state.source);
  state.observations.changed(state.source, { documentDigest: 'first' });
  state.observations.changed(state.source, { documentDigest: 'second' });
  assert.equal(state.diagnostics.current(token), false);
  release(); await state.observations.settle();
}));

test('a rejected old-generation read rechecks the newest generation instead of clearing it', async () => fixture(async state => {
  await state.register();
  let reject, reads = 0;
  state.observations.read = async (...args) => {
    if (++reads === 1) await new Promise((_, fail) => { reject = fail; });
    return readVersion(...args);
  };
  state.observations.changed(state.source);
  state.observations.invalidate();
  const latest = await state.register();
  reject(new Error('old read could not open the saved path'));
  await state.observations.settle();
  assert.equal(reads, 2);
  assert.equal(state.diagnostics.current(latest.token), true);
}));

test('freshness is rechecked after pending notifications rather than publishing a pre-settlement version', async () => fixture(async state => {
  const { plan, token } = await state.register();
  const silent = path.join(state.root, 'silent.vas');
  await fs.writeFile(silent, 'void silent() {}\n');
  plan.inputVersions = await captureVersions([state.source, silent]);
  state.observations.register(plan, token);
  let release, reads = 0;
  state.observations.read = async (...args) => {
    if (++reads === 1) await new Promise(resolve => { release = resolve; });
    return readVersion(...args);
  };
  let checks = 0;
  const validation = state.observations.validate(async () => {
    await plan.inputVersions.checkAll();
    if (++checks === 1) state.observations.changed(state.source);
  });
  while (!release) await new Promise(resolve => setImmediate(resolve));
  await fs.appendFile(silent, '// silent modification during event settlement\n');
  release();
  await assert.rejects(validation, /input changed/);
  assert.equal(checks, 1, 'second check must fail before it can authorize publication');
}));

test('continuous events during freshness rechecks stop at the explicit stabilization bound', async () => fixture(async state => {
  await state.register();
  let checks = 0;
  await assert.rejects(state.observations.validate(async () => {
    checks++;
    state.observations.changed(state.source);
  }), /kept changing/);
  assert.equal(checks, 8);
  await state.observations.settle();
}));

test('filesystem classification waits for already-received compiler proof admission before treating a loaded include as unknown', async () => fixture(async state => {
  const { createHash } = require('node:crypto');
  const { plan, token } = await state.register();
  const include = path.join(state.root, 'first-loaded.vas'), bytes = Buffer.from('int Shared() { return 1; }\n');
  await fs.writeFile(include, bytes);
  await state.dependencies.observe(plan, { key: 'exact-include-identity', file: include });
  let release;
  plan.sourceProofRevision = 1;
  const pending = new Promise(resolve => { release = resolve; }).then(() => plan.inputVersions.admitLoaded(include,
    { version: 1, algorithm: 'sha256', byteLength: bytes.length, digest: createHash('sha256').update(bytes).digest('hex') }));
  plan.waitForSourceProofs = () => pending;
  state.observations.changed(include);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(state.diagnostics.current(token), true);
  release(); await state.observations.settle();
  assert.equal(state.diagnostics.current(token), true);
}));
