'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');

class Emitter {
  constructor() { this.listeners = new Set(); this.event = listener => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; }; }
  fire(value) { for (const listener of this.listeners) listener(value); }
  dispose() { this.listeners.clear(); }
}
const tick = () => new Promise(resolve => setImmediate(resolve));

function host({ trusted = true, file = '/project/src/main.vas', dirty = [], failedSave = false, settings = {}, realDirectory } = {}) {
  const commands = new Map();
  const executed = [], errors = [], created = [];
  let provider;
  const changes = new Emitter(), spawns = [], collections = [], subscriptions = [];
  const folder = { uri: { scheme: 'file', fsPath: '/project' } };
  const document = { uri: { scheme: 'file', fsPath: file }, isDirty: failedSave, save: async () => false };
  const vscode = {
    workspace: { isTrusted: trusted, textDocuments: dirty, onDidChangeTextDocument: changes.event, getWorkspaceFolder: () => folder,
      getConfiguration: () => ({ get: key => ({ compilerPath: '/sdk/vasbuild', runnerPath: '/sdk/vasrun', configFile: '.vas/api.txt', outputDirectory: '.vas/build', ...settings })[key] }) },
    window: { activeTextEditor: { document }, showErrorMessage: message => errors.push(message) },
    commands: { registerCommand: (name, callback) => { commands.set(name, callback); return { dispose() {} }; } },
    tasks: { registerTaskProvider: (_, value) => { provider = value; return { dispose() {} }; }, executeTask: async task => { executed.push(task); return { task }; } },
    Task: class { constructor(definition, scope, name, source, execution, problemMatchers) { Object.assign(this, { definition, scope, name, source, execution, problemMatchers }); } },
    CustomExecution: class { constructor(callback) { this.callback = callback; } },
    EventEmitter: Emitter,
    Uri: { file: file => ({ fsPath: file, toString: () => 'file://' + file }) },
    Range: class { constructor(line, character) { this.start = { line, character }; } },
    Diagnostic: class { constructor(range, message, severity) { Object.assign(this, { range, message, severity }); } },
    DiagnosticSeverity: { Error: 0, Warning: 1 },
    languages: { createDiagnosticCollection: name => {
      const collection = { name, entries: [], clear() { this.entries = []; }, set(entries) { this.entries = entries; }, dispose() { this.disposed = true; } };
      collections.push(collection); return collection;
    } },
    ProcessExecution: class { constructor(executable, args, options) { Object.assign(this, { executable, args, options }); } },
    TaskGroup: { Build: 'build' }, TaskRevealKind: { Always: 1 }, TaskPanelKind: { Dedicated: 2 }
  };
  const files = {
    readFile: async () => '/* 文😀 */ int Broken() { return ; }',
    stat: async () => ({ isFile: () => true }), mkdir: async directory => created.push(directory),
    realpath: async target => target === '/project' ? '/project' : (realDirectory || target),
    lstat: async () => { const error = new Error('missing'); error.code = 'ENOENT'; throw error; }
  };
  // Keep mock fixture paths platform-independent; the pure path suite separately
  // tests win32 rules, and native integration tests use the real host filesystem.
  const pure = require('../src/toolchain');
  function load(name) {
    const sandbox = { module: { exports: {} }, Buffer, Set, Map, setTimeout, clearTimeout, require: requested =>
      requested === 'vscode' ? vscode : requested === 'node:fs/promises' ? files :
      requested === 'node:path' ? path.posix : requested === './toolchain' ? {
        contains: (root, file) => pure.contains(root, file, path.posix),
        createPlan: options => pure.createPlan(options, path.posix)
      } : requested === 'node:child_process' ? { spawn: (executable, args, options) => {
        const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
        child.kill = () => { child.killCalls = (child.killCalls || 0) + 1; setImmediate(() => child.emit('close', null)); return true; };
        spawns.push({ executable, args, options, child }); return child;
      } } : requested.startsWith('./') ? load(requested.slice(2)) : require(requested) };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src', name + '.js'), 'utf8'), sandbox);
    return sandbox.module.exports;
  }
  load('extension').activate({ subscriptions });
  async function openBuild(task = executed.at(-1)) {
    const terminal = await task.execution.callback();
    const output = [];
    terminal.onDidWrite(text => output.push(text));
    const closed = new Promise(resolve => terminal.onDidClose(resolve));
    terminal.open();
    await tick();
    return { terminal, closed, output };
  }
  return { commands, executed, errors, created, provider, folder, vscode, files, spawns, collections, changes, subscriptions, openBuild };

}

test('activation does not create output or execute anything', () => {
  const h = host();
  assert.equal(h.executed.length, 0);
  assert.equal(h.created.length, 0);
  assert.deepEqual(Array.from(h.provider.provideTasks()), []);
});

test('explicit build creates a pipe-backed native task, with no terminal problem matcher', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  assert.equal(h.errors.length, 0);
  assert.equal(h.executed.length, 1);
  assert.equal(h.spawns.length, 0, 'resolving a task must not start the process');
  assert.deepEqual(Array.from(h.executed[0].problemMatchers), []);
  const build = await h.openBuild();
  assert.equal(h.spawns[0].executable, '/sdk/vasbuild');
  assert.deepEqual(Array.from(h.spawns[0].args), ['/project/.vas/api.txt', '/project/src/main.vas', '/project/.vas/build/src/main.vasbc']);
  h.spawns[0].child.emit('close', 0);
  assert.equal(await build.closed, 0);
});

test('run launches the runner without compiling bytecode first', async () => {
  const h = host();
  await h.commands.get('vas.runCurrentFile')();
  assert.equal(h.executed.length, 1);
  assert.equal(h.executed[0].execution.executable, '/sdk/vasrun');
  assert.equal(h.created.length, 0);
});

test('untrusted workspaces cannot execute commands or resolve custom tasks', async () => {
  const h = host({ trusted: false });
  await h.commands.get('vas.buildCurrentFile')();
  await h.commands.get('vas.runCurrentFile')();
  assert.equal(await h.provider.resolveTask({ definition: { type: 'vas', operation: 'build', file: 'main.vas' }, scope: h.folder }), undefined);
  assert.equal(h.executed.length, 0);
  assert.equal(h.created.length, 0);
  assert.equal(h.errors.length, 3);
});

test('failed saves, legacy extensions and unsaved included documents block execution', async () => {
  for (const options of [{ failedSave: true }, { file: '/project/main.as' },
    { dirty: [{ isDirty: true, uri: { scheme: 'file', fsPath: '/project/src/include.vas' } }] }]) {
    const h = host(options);
    await h.commands.get('vas.buildCurrentFile')();
    assert.equal(h.executed.length, 0);
    assert.equal(h.errors.length, 1);
  }
});

test('a resolved task keeps the original task definition', async () => {
  const h = host();
  const definition = { type: 'vas', operation: 'build', file: 'src/main.vas' };
  const resolved = await h.provider.resolveTask({ definition, scope: h.folder, name: 'Build entry' });
  assert.equal(resolved.definition, definition);
  assert.equal(resolved.name, 'Build entry');
  assert.equal(h.executed.length, 0);
});

test('an escaping output link blocks the process', async () => {
  const h = host({ realDirectory: '/outside' });
  await h.commands.get('vas.buildCurrentFile')();
  assert.equal(h.executed.length, 0);
  assert.equal(h.created.length, 0);
  assert.match(h.errors[0], /outside the workspace/);
});


test('trust and dirty sources are rechecked when a previously resolved task actually starts', async () => {
  for (const change of [h => { h.vscode.workspace.isTrusted = false; }, h => {
    h.vscode.workspace.textDocuments.push({ isDirty: true, uri: { scheme: 'file', fsPath: '/project/include.vas' } });
  }]) {
    const h = host();
    await h.commands.get('vas.buildCurrentFile')();
    change(h);
    const build = await h.openBuild();
    assert.equal(await build.closed, 1);
    assert.equal(h.spawns.length, 0);
    assert.match(build.output.join(''), /Trust this workspace|Save all VAS/);
  }
});

test('pipe diagnostics publish exact URI/UTF-16 columns and edits during compile discard results', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  const first = await h.openBuild();
  const child = h.spawns[0].child;
  child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : Must return a value\r\n'));
  await tick(); child.emit('close', 1);
  assert.equal(await first.closed, 1);
  const [uri, values] = h.collections[0].entries[0];
  assert.equal(uri.fsPath, '/project/shared 文😀.vas');
  assert.equal(values[0].range.start.character, '/* 文😀 */ int Broken() { '.length);
  await h.commands.get('vas.buildCurrentFile')();
  const second = await h.openBuild();
  h.changes.fire({ document: { uri: { scheme: 'file' } }, contentChanges: [{}] });
  h.spawns[1].child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : stale\n'));
  await tick(); h.spawns[1].child.emit('close', 1);
  assert.equal(await second.closed, 1);
  assert.equal(h.collections[0].entries.length, 0);
});

test('closing or disposing an active task terminates the child and suppresses diagnostics', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  const build = await h.openBuild();
  build.terminal.close();
  assert.equal(await build.closed, 130);
  assert.equal(h.collections[0].entries.length, 0);
  for (const item of h.subscriptions) item.dispose();
  assert.ok(h.collections.every(collection => collection.disposed));
});

test('newer build wins even when an older build finishes after it', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  const old = await h.openBuild();
  const newer = await h.openBuild();
  h.spawns[1].child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : newer\n'));
  await tick(); h.spawns[1].child.emit('close', 1);
  assert.equal(await newer.closed, 1);
  h.spawns[0].child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : older\n'));
  await tick(); h.spawns[0].child.emit('close', 1);
  assert.equal(await old.closed, 1);
  assert.equal(h.collections.length, 1);
  assert.equal(h.collections[0].entries[0][1][0].message, 'newer');
});

test('edits while source-position reads are pending cannot publish saved-source positions', async () => {
  const h = host();
  let release;
  h.files.readFile = () => new Promise(resolve => { release = resolve; });
  await h.commands.get('vas.buildCurrentFile')();
  const build = await h.openBuild();
  h.spawns[0].child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : old position\n'));
  await tick(); h.spawns[0].child.emit('close', 1);
  await tick();
  assert.equal(typeof release, 'function');
  h.changes.fire({ document: { uri: { scheme: 'file' } }, contentChanges: [{}] });
  release('new source');
  assert.equal(await build.closed, 1);
  assert.equal(h.collections[0].entries.length, 0);
});

test('close during async launch preparation prevents later spawning', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  let release;
  h.files.stat = () => new Promise(resolve => { release = resolve; });
  const build = await h.openBuild();
  build.terminal.close();
  assert.equal(await build.closed, 130);
  // The remaining preparation can finish; cancelled work still cannot spawn.
  h.files.stat = async () => ({ isFile: () => true });
  release({ isFile: () => true });
  await tick();
  assert.equal(h.spawns.length, 0);
});

test('filesystem safety is rechecked at actual task execution', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  h.files.realpath = async target => target === '/project' ? '/project' : '/outside';
  const build = await h.openBuild();
  assert.equal(await build.closed, 1);
  assert.equal(h.spawns.length, 0);
  assert.match(build.output.join(''), /outside the workspace/);
});

test('terminal output preserves split CRLF without doubling carriage returns', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  const build = await h.openBuild();
  h.spawns[0].child.stdout.write(Buffer.from('one\r'));
  h.spawns[0].child.stdout.write(Buffer.from('\ntwo\n'));
  h.spawns[0].child.stdout.end();
  await tick(); h.spawns[0].child.emit('close', 0);
  assert.equal(await build.closed, 0);
  assert.equal(build.output.join(''), 'one\r\ntwo\r\n');
});


test('extension disposal kills a live compiler and disposes native collections', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  await h.openBuild();
  for (const item of h.subscriptions) item.dispose();
  await tick();
  assert.equal(h.spawns[0].child.killCalls, 1);
  assert.ok(h.collections.every(collection => collection.disposed));
});


test('cancellation closes the task immediately while diagnostic source reads are pending', async () => {
  const h = host();
  let release;
  h.files.readFile = () => new Promise(resolve => { release = resolve; });
  await h.commands.get('vas.buildCurrentFile')();
  const build = await h.openBuild();
  let code;
  build.terminal.onDidClose(value => { code = value; });
  h.spawns[0].child.stderr.end(Buffer.from('/project/shared 文😀.vas (1, 30) : ERR  : pending read\n'));
  await tick(); h.spawns[0].child.emit('close', 1);
  await tick();
  assert.equal(typeof release, 'function');
  build.terminal.close();
  await tick();
  assert.equal(code, 130, 'closing must not wait for the source read');
  release('new source');
  await tick();
  assert.equal(await build.closed, 130);
  assert.equal(h.collections[0].entries.length, 0);
});
