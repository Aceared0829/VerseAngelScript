'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');

async function audit() {
  const compiler = path.resolve('unit-audit-compiler');
  const children = [];
  const childProcess = { spawn() {
    const child = new EventEmitter(); child.stdout = new PassThrough(); children.push(child); return child;
  } };
  childProcess.execFile = childProcess.spawn;
  const watchers = [];
  const vscode = { CustomExecution: class {}, workspace: { createFileSystemWatcher(pattern) {
    const watcher = new EventEmitter(); watcher.pattern = pattern;
    for (const [name, event] of [['onDidCreate', 'create'], ['onDidChange', 'change'], ['onDidDelete', 'delete']]) {
      watcher[name] = listener => { watcher.on(event, listener); return { dispose: () => watcher.off(event, listener) }; };
    }
    watcher.dispose = () => { watcher.disposed = true; };
    watchers.push(watcher); return watcher;
  } } };
  class Observations { register() {} changed() {} invalidate() {} }
  class Diagnostics { publish() { return true; } }
  const context = { module: { exports: {} }, process: { env: { VAS_TEST_COMPILER: compiler } }, console,
    require(name) {
      if (name === 'vscode') return vscode;
      if (name === 'node:child_process') return childProcess;
      if (name === '../src/projectObservations') return { ProjectInputObservations: Observations };
      if (name === '../src/diagnostics') return { BuildDiagnostics: Diagnostics };
      return require(name);
    }
  };
  const source = await fs.readFile(path.join(__dirname, 'integration.js'), 'utf8');
  vm.runInNewContext(source + '\nmodule.exports.audit = { processCalls, readyExtensionDirectory, postPublicationNotification, actualWatchListeners };',
    context, { filename: 'integration.js' });
  return { compiler, childProcess, children, vscode, watchers, ...context.module.exports.audit, calls: context.module.exports.audit.processCalls };
}

test('integration wire audit preserves child, exact chunk bytes and split Unicode while recording native close order', async () => {
  const state = await audit();
  const child = state.childProcess.spawn(state.compiler, ['--report=jsonl'], { shell: false });
  assert.equal(child, state.children[0]);
  const received = [];
  child.stdout.on('data', bytes => received.push(Buffer.from(bytes)));
  const bytes = Buffer.from(JSON.stringify({ type: 'diagnostic', message: 'warning 文😀', section: 'exact/文😀.vas' }) + '\n');
  const cut = bytes.indexOf(Buffer.from('😀')) + 1;
  child.stdout.write(bytes.subarray(0, cut)); child.stdout.write(bytes.subarray(cut)); child.stdout.end();
  child.emit('close', 7, null);
  assert.deepEqual(Buffer.concat(received), bytes, 'audit must not substitute the production pipe contents');
  const call = state.calls[0];
  assert.equal(call.report[0].message, 'warning 文😀');
  assert.equal(call.report[0].section, 'exact/文😀.vas');
  assert.equal(call.nativeClose.code, 7);
  assert.ok(call.order < call.report[0].order && call.report[0].order < call.nativeClose.order);
});

test('integration wire audit has byte/record bounds without truncating the actual process stream', async () => {
  for (const bytes of [Buffer.alloc(128 * 1024 + 1, 'x'), Buffer.from(Array.from({ length: 60 }, () => '{"type":"unit-audit"}\n').join(''))]) {
    const state = await audit(), child = state.childProcess.spawn(state.compiler, ['--report=jsonl']);
    const received = []; child.stdout.on('data', chunk => received.push(Buffer.from(chunk)));
    child.stdout.end(bytes);
    assert.deepEqual(Buffer.concat(received), bytes);
    assert.equal(state.calls[0].reportTruncated, true);
    assert.ok(state.calls[0].report.length <= 48);
  }
});

test('integration wire audit does not subscribe to unrelated processes or descriptors', async () => {
  const state = await audit();
  for (const [executable, args] of [[state.compiler, ['--describe-project=json']], [path.resolve('other-tool'), ['--report=jsonl']]]) {
    const child = state.childProcess.spawn(executable, args);
    assert.equal(child.stdout.listenerCount('data'), 0);
    assert.equal(state.calls.at(-1).report, undefined);
    child.stdout.end();
  }
});

test('extension readiness bridges the same existing watcher and cleanup never disposes that production watcher', async () => {
  const state = await audit(), root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-extension-watch-'));
  const watcher = state.vscode.workspace.createFileSystemWatcher('**/*');
  let productionCalls = 0;
  watcher.onDidCreate(() => { productionCalls++; });
  try {
    const readiness = await state.readyExtensionDirectory(root, { writeProbe: async file => {
      watcher.emit('create', { scheme: 'file', fsPath: file });
    } });
    assert.equal(state.watchers.length, 1, 'readiness must not create a second VS Code or Node watcher');
    assert.equal(readiness.audit.ready.watcherId, 1);
    assert.equal(readiness.audit.pattern, '**/*');
    assert.equal(productionCalls, 1, 'the actual watcher listener must still receive its unchanged callback');
    readiness.close();
    assert.equal(state.actualWatchListeners.size, 0);
    assert.equal(watcher.disposed, undefined);
    watcher.emit('create', { scheme: 'file', fsPath: path.join(root, 'after-cleanup.txt') });
    assert.equal(productionCalls, 2);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('missing same-directory extension readiness fails without widening an exact-file watcher or leaking its bridge', async () => {
  const state = await audit(), root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-extension-watch-missing-'));
  const watcher = state.vscode.workspace.createFileSystemWatcher('**/exact.vas');
  try {
    await assert.rejects(state.readyExtensionDirectory(root, { timeoutMs: 10, probeIntervalMs: 1, writeProbe: async file => {
      // Other directories and non-file schemes cannot establish this scope.
      watcher.emit('change', { scheme: 'file', fsPath: path.join(root, 'elsewhere', path.basename(file)) });
      watcher.emit('change', { scheme: 'untitled', fsPath: file });
    } }), /independent directory watcher readiness/);
    assert.equal(state.watchers.length, 1); assert.equal(watcher.pattern, '**/exact.vas');
    assert.equal(state.actualWatchListeners.size, 0); assert.equal(watcher.disposed, undefined);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('post-publication wait rejects sentinel, old, missing, wrong-kind and different-watcher callbacks', async () => {
  const state = await audit(), include = path.resolve('include.vas');
  const select = events => state.postPublicationNotification(events, 20, 7, [include]);
  for (const events of [[], [{ order: 19, kind: 'change', watcherId: 7, path: include }],
    [{ order: 21, kind: 'create', watcherId: 7, path: include }],
    [{ order: 21, kind: 'change', watcherId: 8, path: include }],
    [{ order: 21, kind: 'change', watcherId: 7, path: path.resolve('.readiness-sentinel') }]]) assert.equal(select(events), undefined);
  const event = { order: 21, kind: 'change', watcherId: 7, path: include };
  assert.equal(select([event]), event);
});
