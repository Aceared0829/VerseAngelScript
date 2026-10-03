'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createProjectTerminal } = require('../src/projectTask');
const { BuildDiagnostics } = require('../src/diagnostics');
const { ProjectDependencies } = require('../src/projectReport');

class Emitter {
  constructor() { this.listeners = new Set(); this.event = callback => { this.listeners.add(callback); return { dispose: () => this.listeners.delete(callback) }; }; }
  fire(value) { for (const callback of this.listeners) callback(value); }
  dispose() { this.listeners.clear(); }
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const vscode = { EventEmitter: Emitter, Uri: { file: file => ({ fsPath: file }) },
  Range: class { constructor(line, character) { this.start = { line, character }; } },
  Diagnostic: class { constructor(range, message, severity) { Object.assign(this, { range, message, severity }); } },
  DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2 } };
function store() {
  const collections = [];
  const diagnostics = new BuildDiagnostics(() => {
    const collection = { entries: [], clear() { this.entries = []; }, set(entries) { this.entries = entries; }, dispose() {} };
    collections.push(collection); return collection;
  });
  return { diagnostics, dependencies: new ProjectDependencies(), collections };
}
function build(state, plan, prepare = async () => plan) {
  let callbacks, killed = 0, started = 0;
  const report = { observed: new Map(), result: { dependenciesComplete: true } };
  const terminal = createProjectTerminal({ vscode, ...state, prepare, done() {}, createProcess: (_, value) => {
    callbacks = value; return { report, start() { started++; }, terminate() { killed++; } };
  } });
  const output = [];
  terminal.onDidWrite(text => output.push(text));
  const closed = new Promise(resolve => terminal.onDidClose(resolve));
  terminal.open();
  return { terminal, closed, output, report, get callbacks() { return callbacks; }, get started() { return started; }, get killed() { return killed; } };
}
const plan = { key: '["/project/vas-project.json","main"]', project: '/project/vas-project.json', config: '/project/api.txt',
  source: '/project/main.vas', unit: 'main', checkFresh: async () => {} };

test('task cancellation during preparation emits 130 immediately and no later compiler starts', async () => {
  const state = store(); let release, cancel;
  const fixture = build(state, plan, token => { cancel = token; return new Promise(resolve => { release = resolve; }); });
  fixture.terminal.close();
  assert.equal(await fixture.closed, 130); assert.equal(cancel.cancelled, true);
  release(plan); await tick(); assert.equal(fixture.started, 0);
});

test('in-flight edit, newer completion, trust/freshness failure and malformed report cannot be green', async () => {
  for (const mode of ['edit', 'newer', 'freshness', 'protocol']) {
    const state = store(), invocation = { ...plan };
    const fixture = build(state, invocation); await tick();
    assert.equal(fixture.started, 1);
    if (mode === 'edit') state.diagnostics.invalidate();
    if (mode === 'newer') state.diagnostics.begin(plan.key);
    if (mode === 'freshness') invocation.checkFresh = async () => { throw new Error('config changed'); };
    if (mode === 'protocol') fixture.report.error = new Error('malformed report');
    await fixture.callbacks.complete(mode === 'protocol' ? 1 : 0, fixture.report);
    assert.notEqual(await fixture.closed, 0, mode);
    assert.ok(state.collections.every(collection => collection.entries.length === 0));
  }
});

test('UTF-8/BOM diagnostics publish exact UTF-16 and unit identity isolates same-entry collections', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-terminal-'));
  const source = path.join(root, 'shared 文😀.vas'), text = '\ufeff/* 文😀 */ int Broken() { return; }\r\n';
  try {
    await fs.writeFile(source, text);
    const state = store();
    for (const unit of ['first', 'second']) {
      const fixture = build(state, { ...plan, unit, key: JSON.stringify([plan.project, unit]) }); await tick();
      fixture.callbacks.diagnostic({ file: source, row: 1, column: Buffer.byteLength(text.slice(0, text.indexOf('return'))) + 1, severity: 'ERR', message: unit });
      await fixture.callbacks.complete(1, fixture.report);
      assert.equal(await fixture.closed, 1);
    }
    assert.equal(state.collections.length, 2);
    assert.equal(state.collections[0].entries[0][1][0].range.start.character, text.indexOf('return') - 1);
    assert.equal(state.collections[0].entries[0][1][0].message, 'first');
    assert.equal(state.collections[1].entries[0][1][0].message, 'second');
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('late old completion never replaces new diagnostics or a newer configuration dependency', async () => {
  const state = store(), older = build(state, { ...plan, config: '/old/host.txt' }); await tick();
  const newer = build(state, { ...plan, config: '/new/host.txt' }); await tick();
  newer.callbacks.diagnostic({ file: '/missing/new.vas', row: 0, column: 0, severity: 'ERR', message: 'newer' });
  await newer.callbacks.complete(1, newer.report); assert.equal(await newer.closed, 1);
  older.callbacks.diagnostic({ file: '/missing/old.vas', row: 0, column: 0, severity: 'ERR', message: 'older' });
  await older.callbacks.complete(0, older.report); assert.equal(await older.closed, 1);
  assert.equal(state.collections[0].entries[0][1][0].message, 'newer');
  assert.ok(state.dependencies.relevant('/new/host.txt'));
});

test('cancel during asynchronous completion retains prior dependencies and closes without waiting', async () => {
  const state = store();
  state.dependencies.update(plan, { observed: new Map([['previous', { file: '/previous.vas' }]]) }, true);
  let calls = 0, release;
  const pending = { ...plan, checkFresh: async () => { if (++calls === 3) await new Promise(resolve => { release = resolve; }); } };
  const fixture = build(state, pending); await tick();
  const complete = fixture.callbacks.complete(0, fixture.report); await tick();
  assert.equal(typeof release, 'function');
  fixture.terminal.close(); assert.equal(await fixture.closed, 130);
  release(); await complete;
  assert.ok(state.dependencies.relevant('/previous.vas'));
});

test('transport failure and dependency watcher errors retain partial observations and always close', async () => {
  for (const mode of ['transport', 'watcher']) {
    const state = store();
    state.dependencies.update(plan, { observed: new Map([['previous', { file: '/previous.vas' }]]) }, true);
    const fixture = build(state, plan); await tick();
    if (mode === 'transport') fixture.report.error = new Error('broken pipe');
    else state.dependencies.onFiles = () => { throw new Error('watch limit exceeded'); };
    await fixture.callbacks.complete(1, fixture.report);
    assert.equal(await fixture.closed, 1);
    assert.ok(state.dependencies.relevant('/previous.vas'), mode);
  }
});

test('file-level nonregular output diagnostics never wait for file contents', { skip: process.platform === 'win32' }, async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-file-level-'));
  try {
    const fifo = path.join(root, 'output-fifo');
    require('node:child_process').execFileSync('mkfifo', [fifo]);
    const state = store(), fixture = build(state, { ...plan, output: fifo }); await tick();
    fixture.callbacks.diagnostic({ file: fifo, row: 0, column: 0, severity: 'ERR', message: 'not a regular output' });
    await fixture.callbacks.complete(1, fixture.report); assert.equal(await fixture.closed, 1);
    assert.equal(state.collections[0].entries[0][1][0].range.start.character, 0);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('real project rejects newly discovered dirty include aliases, remembers inputs, and permits unrelated dirty documents',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => {
    const { projectPlan, projectRequest } = require('../src/project');
    const { ProjectBuildProcess } = require('../src/projectReport');
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-dirty-include-alias-'));
    const folder = { uri: { scheme: 'file', fsPath: root } }, project = path.join(root, 'vas-project.json');
    try {
      await fs.writeFile(path.join(root, 'api.txt'), '// empty interface\n');
      await fs.writeFile(path.join(root, 'main.vas'), '#include "shared.vas"\nvoid main() { Shared(); }\n');
      await fs.writeFile(path.join(root, 'shared.txt'), 'int Shared() { return 1; }\n');
      await fs.link(path.join(root, 'shared.txt'), path.join(root, 'shared.vas'));
      await fs.writeFile(path.join(root, 'unrelated.txt'), 'unrelated notes');
      await fs.writeFile(project, JSON.stringify({ schemaVersion: 1, compilationUnits: [
        { id: 'main', entry: 'main.vas', hostApi: { config: 'api.txt' }, output: 'out/main.vasbc' }
      ] }));
      const dirty = { isDirty: true, uri: { scheme: 'file', fsPath: path.join(root, 'shared.txt') } };
      const host = { ...vscode, workspace: { isTrusted: true, textDocuments: [dirty],
        getConfiguration: () => ({ inspect: () => ({ globalValue: process.env.VAS_TEST_COMPILER }) }) } };
      const state = store(), request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, process.env.VAS_TEST_COMPILER);
      let starts = 0;
      async function execute() {
        const output = [];
        const terminal = createProjectTerminal({ vscode: host, ...state, done() {},
          prepare: cancel => projectPlan(request, undefined, { vscode: host, folder, cancel, dependencies: state.dependencies }),
          createProcess: (plan, callbacks) => { starts++; return new ProjectBuildProcess(plan, callbacks); } });
        terminal.onDidWrite(text => output.push(text));
        const closed = new Promise(resolve => terminal.onDidClose(resolve));
        terminal.open();
        return { code: await closed, output: output.join('') };
      }
      const first = await execute();
      assert.equal(starts, 1, 'first native build discovers the previously unknown include');
      assert.equal(first.code, 1, first.output);
      assert.match(first.output, /Save the VAS project input.*shared\.txt/);
      assert.doesNotMatch(first.output, /VAS: Built project unit/);
      assert.ok(state.collections.every(collection => collection.entries.length === 0), 'dirty-alias result must not publish diagnostics');
      assert.ok(state.dependencies.inputFiles(JSON.stringify([project.replaceAll('\\', '/'), 'main'])).some(file => file.endsWith('shared.vas')));
      const second = await execute();
      assert.equal(second.code, 1); assert.equal(starts, 1, 'previous observation blocks compilation while alias remains dirty');
      dirty.isDirty = false;
      host.workspace.textDocuments.push({ isDirty: true, uri: { scheme: 'file', fsPath: path.join(root, 'unrelated.txt') } });
      const saved = await execute();
      assert.equal(saved.code, 0, saved.output); assert.equal(starts, 2);
      assert.match(saved.output, /VAS: Built project unit main/);
    } finally { await fs.rm(root, { recursive: true, force: true }); }
  });

test('actual POSIX invalid-byte include path fails closed without replacing prior dependencies',
  { skip: !process.env.VAS_TEST_COMPILER || process.platform === 'win32' }, async () => {
    const { projectPlan, projectRequest } = require('../src/project');
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-raw-include-'));
    const folder = { uri: { scheme: 'file', fsPath: root } }, project = path.join(root, 'vas-project.json');
    try {
      await fs.writeFile(path.join(root, 'api.txt'), '// empty interface\n');
      const include = Buffer.concat([Buffer.from('raw-'), Buffer.from([0xff]), Buffer.from('.vas')]);
      await fs.writeFile(path.join(root, 'main.vas'), Buffer.concat([Buffer.from('#include "'), include, Buffer.from('"\nvoid main() { Shared(); }\n')]));
      await fs.writeFile(path.join(root, 'shared.txt'), 'int Shared() { return 1; }\n');
      await fs.link(path.join(root, 'shared.txt'), Buffer.concat([Buffer.from(root + '/'), include]));
      await fs.writeFile(project, JSON.stringify({ schemaVersion: 1, compilationUnits: [
        { id: 'main', entry: 'main.vas', hostApi: { config: 'api.txt' }, output: 'out/main.vasbc' }
      ] }));
      const host = { ...vscode, workspace: { isTrusted: true, textDocuments: [],
        getConfiguration: () => ({ inspect: () => ({ globalValue: process.env.VAS_TEST_COMPILER }) }) } };
      const state = store(), request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, process.env.VAS_TEST_COMPILER);
      const plan = await projectPlan(request, undefined, { vscode: host, folder, dependencies: state.dependencies });
      state.dependencies.update(plan, { observed: new Map([['previous', { file: path.join(root, 'previous.vas') }]]) }, true);
      const output = [];
      const terminal = createProjectTerminal({ vscode: host, ...state, done() {}, prepare: async () => plan });
      terminal.onDidWrite(text => output.push(text));
      const closed = new Promise(resolve => terminal.onDidClose(resolve));
      terminal.open();
      assert.equal(await closed, 1);
      assert.match(output.join(''), /saved input aliases cannot be verified/);
      assert.doesNotMatch(output.join(''), /VAS: Built project unit/);
      assert.ok(state.dependencies.entries.get(plan.key).observed.has('previous'));
      assert.ok([...state.dependencies.entries.get(plan.key).observed.keys()].some(key => key.startsWith('["bytes"')));
      assert.ok(state.collections.every(collection => collection.entries.length === 0));
    } finally { await fs.rm(root, { recursive: true, force: true }); }
  });
