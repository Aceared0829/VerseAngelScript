'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { readyDirectoryWatch } = require('./watchReadiness');
const { RerunSaveEvidence } = require('./rerunSaveEvidence');

const root = path.resolve('watch-readiness-fixture'), target = path.join(root, 'source.vas');
function setup(writeProbe, extra = {}) {
  const watcher = new EventEmitter(), writes = [], callbacks = [];
  let receive;
  watcher.close = () => { watcher.closed = true; };
  const promise = readyDirectoryWatch(root, (...event) => callbacks.push(event), {
    createWatcher(directory, callback) { assert.equal(directory, root); receive = callback; return watcher; },
    async writeProbe(file, text, options) {
      assert.notEqual(file, target, 'readiness must never mutate the selected source');
      writes.push(file); await writeProbe({ file, text, options, receive, watcher, count: writes.length });
    }, timeoutMs: 100, probeIntervalMs: 1, ...extra
  });
  return { promise, watcher, writes, callbacks, emit: (...event) => receive(...event) };
}

test('directory readiness loses an initial probe but opens only on a matching sentinel receipt', async () => {
  const state = setup(async ({ file, receive, count }) => {
    receive('change', null); receive('change', 'unrelated.txt');
    if (count === 2) receive('change', path.basename(file));
  });
  const ready = await state.promise;
  try {
    assert.equal(state.writes.length, 2);
    assert.equal(ready.audit.ready.name, path.basename(state.writes[1]));
    assert.equal(ready.audit.ready.phase, 'readiness');
    assert.equal(ready.audit.events[0].name, null);
    assert.equal(ready.audit.events[1].name, 'unrelated.txt');
  } finally { ready.close(); }
  assert.equal(state.watcher.closed, true);
});

test('readiness callback during a probe write cannot advance until that write finishes', async () => {
  let release, completed = false;
  const state = setup(({ file, receive }) => {
    receive('change', path.basename(file));
    return new Promise(resolve => { release = resolve; });
  });
  const result = state.promise.then(ready => { completed = true; return ready; });
  await Promise.resolve(); assert.equal(completed, false);
  release(); const ready = await result;
  assert.equal(completed, true); ready.close();
});

test('elapsed time, unknown names and no callbacks cannot establish readiness', async () => {
  for (const mode of ['missing', 'wrong-name']) {
    const state = setup(({ receive }) => {
      if (mode === 'wrong-name') { receive('change', null); receive('rename', 'other-sentinel'); }
    }, { timeoutMs: 10 });
    await assert.rejects(state.promise, /Timed out: independent directory watcher readiness/);
    assert.equal(state.watcher.closed, true);
    assert.ok(state.writes.length > 0);
    const writes = state.writes.length;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(state.writes.length, writes, 'failure leaves no continuing probe loop');
  }
});

test('watcher errors and cancellation abort then drain owned probe writes before rejecting', async () => {
  for (const mode of ['watch-error', 'cancel']) {
    const controller = new AbortController();
    let release, observedAbort = false;
    const state = setup(({ watcher, options }) => {
      options.signal.addEventListener('abort', () => { observedAbort = true; });
      if (mode === 'watch-error') watcher.emit('error', new Error('watcher failed'));
      else controller.abort();
      return new Promise(resolve => { release = resolve; });
    }, { signal: controller.signal });
    let rejected = false;
    const failure = assert.rejects(state.promise, mode === 'watch-error' ? /watcher failed/ : /cancelled/).then(() => { rejected = true; });
    await Promise.resolve(); assert.equal(observedAbort, true); assert.equal(rejected, false);
    release(); await failure;
    assert.equal(state.watcher.closed, true);
    assert.equal(state.writes.length, 1);
  }
});

test('sentinel readiness alone never satisfies a selected-source saved notification', async () => {
  const evidence = new RerunSaveEvidence({ files: [target], version: 2, text: 'void main() {}\n// new\n',
    before: { kind: 'readable', digest: 'old' }, read() { throw new Error('sentinel cannot initiate a source read'); } });
  const state = setup(({ file, receive }) => receive('change', path.basename(file)));
  const ready = await state.promise;
  try {
    evidence.willSave({ order: 1, version: 2, dirty: true, digest: evidence.expected.documentDigest });
    evidence.didSave({ order: 2, version: 2, dirty: false, digest: evidence.expected.documentDigest });
    // Even a delayed duplicate readiness callback after willSave is unrelated.
    evidence.observed({ order: 3, kind: 'change', path: state.writes[0] });
    assert.equal(evidence.events.length, 0);
    assert.throws(() => evidence.verify(undefined, undefined), /actual scoped notification/);
    assert.equal(ready.audit.phase, 'ready');
  } finally { ready.close(); await evidence.dispose(); }
});

test('readiness deadline aborts and drains its outstanding write', async () => {
  let writeFinished = false;
  const state = setup(({ options }) => new Promise((_, reject) => {
    options.signal.addEventListener('abort', () => { writeFinished = true; reject(new Error('write aborted')); });
  }), { timeoutMs: 10 });
  await assert.rejects(state.promise, /Timed out: independent directory watcher readiness/);
  assert.equal(writeFinished, true);
  assert.equal(state.watcher.closed, true);
  assert.equal(state.writes.length, 1);
});

test('raw readiness evidence is bounded and late watcher errors stay visible until close', async () => {
  const state = setup(({ file, receive }) => {
    for (let index = 0; index < 70; index++) receive('change', 'x'.repeat(2048));
    receive('change', path.basename(file));
  });
  const ready = await state.promise;
  try {
    assert.equal(ready.audit.events.length, 64);
    assert.equal(ready.audit.events[0].name.length, 1024);
    assert.equal(ready.audit.dropped, 7);
    state.watcher.emit('error', new Error('late watch failure'));
    await assert.rejects(ready.wait(new Promise(() => {})), /late watch failure/);
    await assert.rejects(ready.wait(Promise.resolve('already complete')), /late watch failure/);
  } finally { ready.close(); }
});
