'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { writeFileSync } = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { RerunSaveEvidence } = require('./rerunSaveEvidence');
const { readVersion } = require('../src/projectVersions');
const { sameFileName } = require('../src/toolchain');
const { readyDirectoryWatch } = require('./watchReadiness');

const file = path.resolve('rerun-save-fixture.vas'), text = 'void main() {}\n// saved 文😀\n';
function fixture(read) {
  const evidence = new RerunSaveEvidence({ files: [file], version: 2, text,
    before: { kind: 'readable', digest: 'previous saved bytes' }, read });
  const snapshot = { file, kind: 'readable', dev: '1', ino: '2', digest: evidence.expected.digest,
    byteLength: evidence.expected.byteLength };
  const proof = { sourceDigestVersion: 1, sourceDigestAlgorithm: 'sha256', sourceDigest: snapshot.digest,
    sourceByteLength: snapshot.byteLength };
  return { evidence, snapshot, proof,
    will(order = 10) { evidence.willSave({ order, version: 2, dirty: true, digest: evidence.expected.documentDigest }); },
    saved(order = 20) { evidence.didSave({ order, version: 2, dirty: false, digest: evidence.expected.documentDigest }); },
    event(order = 15, kind = 'change', name = file) { evidence.observed({ order, kind, path: name }); } };
}

for (const timing of ['before didSave', 'after didSave', 'after native close']) {
  test(`rerun receipt ${timing} observes the saved version without relying on callback order`, async () => {
    let reads = 0;
    const state = fixture(async () => { reads++; return state.snapshot; });
    try {
      state.will();
      if (timing === 'before didSave') {
        state.event();
        assert.equal(reads, 0, 'an in-progress save must not start a possibly transient read');
      }
      state.saved();
      if (timing !== 'before didSave') state.event(timing === 'after didSave' ? 25 : 40);
      await state.evidence.settle();
      assert.equal(reads, 1, 'one stable read per real receipt, without retry');
      assert.equal(state.evidence.verify(state.proof, state.snapshot).length, 1);
    } finally { await state.evidence.dispose(); }
  });
}

for (const scenario of ['pre-window', 'unrelated', 'missing', 'deleted', 'wrong-content', 'unreadable']) {
  test(`rerun evidence rejects ${scenario} notification evidence`, async () => {
    let reads = 0;
    const state = fixture(async () => {
      reads++;
      return scenario === 'unreadable' ? { kind: 'unreadable', state: 'ENOENT' } : { ...state.snapshot, digest: 'wrong bytes' };
    });
    try {
      if (scenario === 'pre-window') state.event(5);
      state.will(); state.saved();
      if (scenario === 'unrelated') state.event(25, 'change', path.resolve('unrelated.vas'));
      if (scenario === 'deleted') state.event(25, 'delete');
      if (['wrong-content', 'unreadable'].includes(scenario)) state.event(25);
      await state.evidence.settle();
      assert.equal(reads, ['wrong-content', 'unreadable'].includes(scenario) ? 1 : 0);
      assert.throws(() => state.evidence.verify(state.proof, state.snapshot),
        ['wrong-content', 'unreadable'].includes(scenario) ? /expected saved bytes/ : /actual scoped notification/);
    } finally { await state.evidence.dispose(); }
  });
}

test('rerun proof rejects wrong saved version, unchanged input and divergent native bytes or physical identity', async () => {
  const state = fixture(async () => state.snapshot);
  try {
    state.will(); state.saved(); state.event(25); await state.evidence.settle();
    for (const proof of [{ ...state.proof, sourceDigest: 'other' }, { ...state.proof, sourceByteLength: 0 },
      { ...state.proof, sourceDigestVersion: 2 }, undefined]) {
      assert.throws(() => state.evidence.verify(proof, state.snapshot));
    }
    assert.throws(() => state.evidence.verify(state.proof, { ...state.snapshot, ino: 'replacement' }), /physical file/);
    assert.throws(() => new RerunSaveEvidence({ files: [file], version: 2, text, before: state.snapshot }), /no-op/);
  } finally { await state.evidence.dispose(); }
  for (const phase of ['will', 'saved']) {
    const invalid = fixture(async () => state.snapshot);
    try {
      if (phase === 'will') invalid.evidence.willSave({ order: 10, version: 3, dirty: true, digest: invalid.evidence.expected.documentDigest });
      else { invalid.will(); invalid.evidence.didSave({ order: 20, version: 3, dirty: false, digest: invalid.evidence.expected.documentDigest }); }
      assert.throws(() => invalid.evidence.currentNotifications(), /document version/);
    } finally { await invalid.evidence.dispose(); }
  }
});

test('rerun audit cancellation drains pending reads, discards late evidence and does not wait for a missing didSave', async () => {
  let release, settled = false;
  const state = fixture(() => new Promise(resolve => { release = resolve; }));
  state.will(); state.event(); state.saved();
  const disposed = state.evidence.dispose().then(() => { settled = true; });
  await Promise.resolve(); assert.equal(settled, false);
  release(state.snapshot); await disposed;
  assert.equal(state.evidence.pending.size, 0);
  assert.equal(state.evidence.events[0].snapshot, undefined);
  assert.equal(state.evidence.currentNotifications().length, 0);
  state.event(30); assert.equal(state.evidence.events.length, 1);
  const noSave = fixture(() => { throw new Error('must not read before didSave'); });
  noSave.will(); noSave.event(); await noSave.evidence.dispose();
  assert.equal(noSave.evidence.pending.size, 0);
});

test('rerun audit bounds receipts and reports failed stable reads without dangling rejection', async () => {
  const state = fixture(async () => { throw new Error('stable read failed'); });
  try {
    state.will(); state.saved(); state.event(25); await state.evidence.settle();
    assert.throws(() => state.evidence.currentNotifications(), /stable read failed/);
    assert.equal(state.evidence.pending.size, 0);
  } finally { await state.evidence.dispose(); }
  const bounded = fixture(async () => state.snapshot);
  try {
    bounded.will();
    for (let index = 0; index < 65; index++) bounded.event(11 + index);
    assert.equal(bounded.evidence.events.length, 64);
    assert.throws(() => bounded.evidence.currentNotifications(), /audit limit/);
  } finally { await bounded.evidence.dispose(); }
});

test('an admitted receipt unlocks notification waiting but cannot bypass wrong saved bytes', async () => {
  let notified;
  const state = fixture(async () => ({ ...state.snapshot, digest: 'wrong saved content' }));
  state.evidence.onReceipt = event => { notified = event; };
  try {
    state.will(); state.event();
    assert.equal(notified, state.evidence.events[0]);
    assert.equal(notified.snapshot, undefined, 'notification admission is distinct from completed content proof');
    state.saved(); await state.evidence.settle();
    assert.throws(() => state.evidence.verify(state.proof, state.snapshot), /expected saved bytes/);
  } finally { await state.evidence.dispose(); }
});

async function deadline(promise, label) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), 10000);
  })]); } finally { clearTimeout(timer); }
}

for (const mode of ['early entry', 'early include alias', 'late entry']) {
  test(`real filesystem ${mode} receipt is bound to actual compiler saved-source proof`, {
    skip: process.env.VAS_TEST_REQUIRE_SOURCE_PROOFS !== '1' && 'Requires the actual source-proof compiler', timeout: 20000
  }, async () => {
    assert.ok(process.env.VAS_TEST_COMPILER);
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-rerun-save-'));
    const entry = path.join(root, 'entry 文😀.vas'), include = path.join(root, 'shared 文😀.vas');
    const alias = path.join(root, 'editor 文😀.txt'), project = path.join(root, 'vas-project.json');
    let evidence, watcher;
    try {
      await fs.writeFile(entry, '#include "shared 文😀.vas"\nvoid main() { int value = Shared(); }\n');
      await fs.writeFile(include, 'int Shared() { int value; return value; }\n');
      await fs.link(include, alias);
      await fs.writeFile(path.join(root, 'api.txt'), '');
      await fs.writeFile(project, JSON.stringify({ schemaVersion: 1, name: 'Rerun source proof', compilationUnits: [
        { id: 'main', entry: path.basename(entry), hostApi: { config: 'api.txt' }, output: 'out/main.vasbc' }
      ] }));
      const input = mode.includes('alias') ? alias : entry, section = mode.includes('alias') ? include : entry;
      const nextText = (await fs.readFile(input, 'utf8')) + `// this saved version ${mode}\n`;
      let order = 0, notify;
      const received = new Promise(resolve => { notify = resolve; });
      evidence = new RerunSaveEvidence({ files: [input, await fs.realpath(input)], version: 2, text: nextText,
        before: await readVersion(input), onReceipt: notify });
      watcher = await readyDirectoryWatch(root, (kind, name) => {
        if (!name || !sameFileName(path.join(root, name), input)) return;
        const event = { order: ++order, kind: kind === 'rename' ? 'create' : 'change', path: input };
        evidence.observed(event);
      });
      assert.equal(evidence.events.length, 0, 'independent readiness receipts cannot count as source notifications');
      assert.equal(evidence.will, undefined, 'the readiness gate must precede this save window');
      watcher.audit.phase = 'target-save';
      evidence.willSave({ order: ++order, version: 2, dirty: true, digest: evidence.expected.documentDigest });
      if (mode.startsWith('early')) {
        await fs.writeFile(input, nextText);
        await deadline(watcher.wait(received), 'actual early filesystem notification');
        assert.equal(evidence.pending.size, 0, 'early notification cannot read until save completion');
      } else writeFileSync(input, nextText); // Complete the write before permitting callbacks to run.
      evidence.didSave({ order: ++order, version: 2, dirty: false, digest: evidence.expected.documentDigest });
      const event = await deadline(watcher.wait(received), 'actual filesystem notification');
      assert.equal(event.order < evidence.saved.order, mode.startsWith('early'));
      await deadline(evidence.settle(), 'actual stable saved-input observation');
      const { stdout, stderr } = await promisify(execFile)(process.env.VAS_TEST_COMPILER,
        ['--report=jsonl', '--project', project, '--unit', 'main'], { shell: false, timeout: 10000, maxBuffer: 128 * 1024 });
      assert.equal(stderr, '');
      const records = stdout.trimEnd().split('\n').map(line => JSON.parse(line));
      assert.equal(records.at(-1).success, true);
      assert.ok(records.some(record => record.type === 'diagnostic' && record.severity === 'warning'));
      const proof = records.find(record => record.type === 'section_loaded' && sameFileName(record.section, section));
      assert.ok(proof, 'actual compiler must report the selected entry/include section');
      const current = await readVersion(section);
      await watcher.wait(evidence.settle());
      evidence.verify(proof, current);
    } catch (error) {
      console.error('VAS native watch evidence:', JSON.stringify({ mode, watch: watcher?.audit, save: evidence?.snapshot() }));
      throw error;
    } finally {
      watcher?.close();
      await evidence?.dispose();
      await fs.rm(root, { recursive: true, force: true });
    }
  });
}
