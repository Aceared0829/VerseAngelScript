'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function host({ trusted = true, file = '/project/src/main.vas', dirty = [], failedSave = false, settings = {}, realDirectory } = {}) {
  const commands = new Map();
  const executed = [], errors = [], created = [];
  let provider;
  const folder = { uri: { scheme: 'file', fsPath: '/project' } };
  const document = { uri: { scheme: 'file', fsPath: file }, isDirty: failedSave, save: async () => false };
  const vscode = {
    workspace: { isTrusted: trusted, textDocuments: dirty, getWorkspaceFolder: () => folder,
      getConfiguration: () => ({ get: key => ({ compilerPath: '/sdk/vasbuild', runnerPath: '/sdk/vasrun', configFile: '.vas/api.txt', outputDirectory: '.vas/build', ...settings })[key] }) },
    window: { activeTextEditor: { document }, showErrorMessage: message => errors.push(message) },
    commands: { registerCommand: (name, callback) => { commands.set(name, callback); return { dispose() {} }; } },
    tasks: { registerTaskProvider: (_, value) => { provider = value; return { dispose() {} }; }, executeTask: async task => { executed.push(task); return { task }; } },
    Task: class { constructor(definition, scope, name, source, execution, problemMatchers) { Object.assign(this, { definition, scope, name, source, execution, problemMatchers }); } },
    ProcessExecution: class { constructor(executable, args, options) { Object.assign(this, { executable, args, options }); } },
    TaskGroup: { Build: 'build' }, TaskRevealKind: { Always: 1 }, TaskPanelKind: { Dedicated: 2 }
  };
  const files = {
    stat: async () => ({ isFile: () => true }), mkdir: async directory => created.push(directory),
    realpath: async target => target === '/project' ? '/project' : (realDirectory || target),
    lstat: async () => { const error = new Error('missing'); error.code = 'ENOENT'; throw error; }
  };
  // Keep mock fixture paths platform-independent; the pure path suite separately
  // tests win32 rules, and native integration tests use the real host filesystem.
  const pure = require('../src/toolchain');
  const sandbox = { module: { exports: {} }, require: name => name === 'vscode' ? vscode : name === 'node:fs/promises' ? files :
    name === 'node:path' ? path.posix : name === './toolchain' ? {
      contains: (root, file) => pure.contains(root, file, path.posix),
      createPlan: options => pure.createPlan(options, path.posix)
    } : require(name) };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/extension.js'), 'utf8'), sandbox);
  sandbox.module.exports.activate({ subscriptions: [] });
  return { commands, executed, errors, created, provider, folder };
}

test('activation does not create output or execute anything', () => {
  const h = host();
  assert.equal(h.executed.length, 0);
  assert.equal(h.created.length, 0);
  assert.deepEqual(Array.from(h.provider.provideTasks()), []);
});

test('explicit build creates a native process task with compiler diagnostics', async () => {
  const h = host();
  await h.commands.get('vas.buildCurrentFile')();
  assert.equal(h.errors.length, 0);
  assert.equal(h.executed.length, 1);
  assert.equal(h.executed[0].execution.executable, '/sdk/vasbuild');
  assert.deepEqual(Array.from(h.executed[0].execution.args), ['/project/.vas/api.txt', '/project/src/main.vas', '/project/.vas/build/src/main.vasbc']);
  assert.deepEqual(Array.from(h.executed[0].problemMatchers), ['$vas-errors', '$vas-warnings']);
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
