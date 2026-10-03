'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { projectPlan, projectRequest } = require('../src/project');
const { ProjectBuildProcess, ProjectDependencies } = require('../src/projectReport');
const { ProjectInputObservations } = require('../src/projectObservations');
const { BuildDiagnostics } = require('../src/diagnostics');
const { createProjectTerminal } = require('../src/projectTask');

class Emitter {
  constructor() { this.listeners = new Set(); this.event = callback => { this.listeners.add(callback); return { dispose: () => this.listeners.delete(callback) }; }; }
  fire(value) { for (const callback of this.listeners) callback(value); }
  dispose() { this.listeners.clear(); }
}

async function fixture(run) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-preparation-'));
  const project = path.join(root, 'vas-project.json'), source = path.join(root, 'main.vas'), include = path.join(root, 'shared.vas'), config = path.join(root, 'api.txt');
  const folder = { uri: { scheme: 'file', fsPath: root } };
  const settings = { compiler: process.env.VAS_TEST_COMPILER }, collections = [];
  const vscode = { EventEmitter: Emitter, Uri: { file: fsPath => ({ fsPath }) },
    Range: class { constructor(line, character) { this.start = { line, character }; } },
    Diagnostic: class { constructor(range, message, severity) { Object.assign(this, { range, message, severity }); } },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2 },
    workspace: { isTrusted: true, textDocuments: [], getConfiguration: () => ({ inspect: () => ({ globalValue: settings.compiler }) }) } };
  const diagnostics = new BuildDiagnostics(() => {
    const collection = { entries: [], clear() { this.entries = []; }, set(entries) { this.entries = entries; }, dispose() {} };
    collections.push(collection); return collection;
  });
  const dependencies = new ProjectDependencies(), observations = new ProjectInputObservations(diagnostics, dependencies);
  const request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, settings.compiler);
  let starts = 0;
  async function execute({ beforeCapture, afterCapture, afterLaunch } = {}) {
    let mutation = Promise.resolve();
    const output = [];
    const terminal = createProjectTerminal({ vscode, diagnostics, dependencies, observations, done() {},
      prepare: async cancel => {
        await beforeCapture?.(cancel);
        const plan = await projectPlan(request, undefined, { vscode, folder, cancel, dependencies });
        await afterCapture?.(plan, cancel, () => terminal.close());
        return plan;
      },
      createProcess(plan, callbacks) {
        const native = new ProjectBuildProcess(plan, { ...callbacks, complete: async (...args) => { await mutation; return callbacks.complete(...args); } });
        const start = native.start.bind(native);
        native.start = () => { starts++; start(); mutation = Promise.resolve(afterLaunch?.(plan)); };
        return native;
      }
    });
    terminal.onDidWrite(text => output.push(text));
    const closed = new Promise(resolve => terminal.onDidClose(resolve));
    terminal.open();
    return { code: await closed, output: output.join('') };
  }
  try {
    await fs.writeFile(project, JSON.stringify({ schemaVersion: 1, compilationUnits: [
      { id: 'main', entry: 'main.vas', hostApi: { config: 'api.txt' }, output: 'out/main.vasbc' }
    ] }));
    await fs.writeFile(config, '');
    await fs.writeFile(source, '#include "shared.vas"\nvoid main() {}\n');
    await fs.writeFile(include, 'int Shared() { int value; return value; }\n');
    await run({ root, project, source, include, config, vscode, settings, diagnostics, dependencies, observations, collections, execute, get starts() { return starts; } });
  } finally { observations.dispose(); diagnostics.dispose(); await fs.rm(root, { recursive: true, force: true }); }
}

test('preparation adopts saved inputs before capture and validates late same-version events against its own plan',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => {
    for (const mode of ['before-source', 'before-config', 'before-manifest', 'after-same-event']) await fixture(async state => {
      assert.equal((await state.execute()).code, 0);
      state.observations.invalidate(); // Saved edit already invalidated the preceding result; keep its graph.
      const before = state.starts;
      const hooks = mode === 'after-same-event' ? { afterCapture: async () => {
        state.observations.changed(state.include); // No live old token: old global-revision guard rejected this.
        await state.observations.settle();
      } } : { beforeCapture: async () => {
        await fs.appendFile(mode === 'before-source' ? state.include : mode === 'before-config' ? state.config : state.project,
          mode === 'before-manifest' ? ' ' : '\n// newly saved before this build snapshot\n');
        state.observations.changed(mode === 'before-source' ? state.include : mode === 'before-config' ? state.config : state.project);
        await state.observations.settle();
      } };
      const result = await state.execute(hooks);
      assert.equal(result.code, 0, `${mode}: ${result.output}`);
      assert.equal(state.starts, before + 1);
      assert.ok(state.collections.some(collection => collection.entries.some(([uri, items]) =>
        uri.fsPath.endsWith('shared.vas') && items.some(item => item.severity === 1))), mode);
    });
  });

test('preparation rejects post-capture bytes, identity/context changes, dirty or mismatched clean buffers, trust loss and cancellation',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => {
    for (const mode of ['source', 'config', 'manifest', 'identity', 'delete', 'tool', 'dirty', 'clean', 'clean-alias', 'trust', 'cancel']) await fixture(async state => {
      assert.equal((await state.execute()).code, 0);
      state.observations.invalidate();
      const before = state.starts;
      const result = await state.execute({ afterCapture: async (plan, cancel, close) => {
        if (['source', 'config', 'manifest'].includes(mode)) await fs.appendFile(mode === 'source' ? state.include : mode === 'config' ? state.config : state.project, ' ');
        else if (mode === 'identity') {
          const replacement = path.join(state.root, 'replacement.vas');
          await fs.copyFile(state.include, replacement); await fs.rename(replacement, state.include);
        }
        else if (mode === 'delete') await fs.unlink(state.include);
        else if (mode === 'tool') state.settings.compiler = process.execPath;
        else if (mode === 'dirty') state.vscode.workspace.textDocuments.push({ isDirty: true, uri: { scheme: 'file', fsPath: state.include } });
        else if (mode === 'clean' || mode === 'clean-alias') {
          const file = mode === 'clean' ? state.include : path.join(state.root, 'editor-alias.txt');
          if (mode === 'clean-alias') await fs.link(state.include, file);
          const text = 'int DifferentCleanText() { return 1; }\n';
          state.vscode.workspace.textDocuments.push({ isDirty: false, version: 2, getText: () => text, uri: { scheme: 'file', fsPath: file } });
          state.observations.changed(file, { documentDigest: require('../src/projectVersions').documentDigest(text) });
          await state.observations.settle();
        }
        else if (mode === 'trust') state.vscode.workspace.isTrusted = false;
        else close();
      } });
      assert.notEqual(result.code, 0, mode);
      assert.equal(state.starts, before, `${mode}: no stale native build may start`);
      assert.ok(state.collections.every(collection => collection.entries.length === 0), mode);
    });
  });

test('preparation checks matching clean aliases without reading unrelated editor contents or coupling unknown file creation',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => fixture(async state => {
    assert.equal((await state.execute()).code, 0);
    state.observations.invalidate();
    const alias = path.join(state.root, 'clean-editor.txt'), unrelated = path.join(state.root, 'unrelated.txt');
    await fs.link(state.include, alias); await fs.writeFile(unrelated, 'unrelated');
    const text = await fs.readFile(alias, 'utf8');
    state.vscode.workspace.textDocuments.push(
      { isDirty: false, version: 1, getText: () => text, uri: { scheme: 'file', fsPath: alias } },
      { isDirty: false, version: 1, getText() { throw new Error('Unrelated buffer contents must not be read'); }, uri: { scheme: 'file', fsPath: unrelated } });
    const result = await state.execute({ afterCapture: async () => {
      const unknown = path.join(state.root, 'not-an-input.vas');
      await fs.writeFile(unknown, 'void Unrelated() {}');
      state.observations.changed(unknown, { kind: 'create' });
    } });
    assert.equal(result.code, 0, result.output);
  }));

test('a silent known-input change during a clean-editor identity probe is checked again before native launch',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => fixture(async state => {
    assert.equal((await state.execute()).code, 0);
    const unrelated = path.join(state.root, 'unrelated.txt'); await fs.writeFile(unrelated, 'notes');
    state.vscode.workspace.textDocuments.push({ isDirty: false, version: 1, getText() { throw new Error('Unrelated contents'); },
      uri: { scheme: 'file', fsPath: unrelated } });
    const original = fs.stat, before = state.starts;
    let wrote = false;
    try {
      const result = await state.execute({ afterCapture: async () => {
        fs.stat = async (...args) => {
          if (args[0] === unrelated && !wrote) { wrote = true; await fs.appendFile(state.include, '\n// changed during alias probe\n'); }
          return original(...args);
        };
      } });
      assert.equal(wrote, true);
      assert.equal(result.code, 1, result.output);
      assert.equal(state.starts, before);
    } finally { fs.stat = original; }
  }));

test('after native launch a real known-input write still discards diagnostics, while first-seen late include events remain conservative',
  { skip: !process.env.VAS_TEST_COMPILER }, async () => {
    await fixture(async state => {
      assert.equal((await state.execute()).code, 0);
      const before = state.starts;
      const result = await state.execute({ afterLaunch: async () => { await fs.appendFile(state.include, '\n// changed during native build\n'); } });
      assert.equal(state.starts, before + 1);
      assert.equal(result.code, 1, result.output);
      assert.ok(state.collections.every(collection => collection.entries.length === 0));
    });
    await fixture(async state => {
      const result = await state.execute();
      assert.equal(result.code, 0, result.output);
      assert.ok(state.collections.some(collection => collection.entries.length));
      state.observations.changed(state.include);
      await state.observations.settle();
      assert.ok(state.collections.every(collection => collection.entries.length === 0),
        'an unchanged first-seen include still has no pre-compile version; a late event cannot be blessed post hoc');
    });
  });
