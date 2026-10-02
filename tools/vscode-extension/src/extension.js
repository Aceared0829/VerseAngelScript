'use strict';

const vscode = require('vscode');
const fs = require('node:fs/promises');
const path = require('node:path');
const { contains, createPlan } = require('./toolchain');
const { BuildDiagnostics } = require('./diagnostics');
const { createBuildTerminal } = require('./buildTask');

async function requireFile(file, label) {
  try {
    if ((await fs.stat(file)).isFile()) return;
  } catch { /* Give the actionable path below rather than a raw filesystem error. */ }
  throw new Error(`${label} is not a file: ${file}`);
}

function requireReady(folder) {
  if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before building or running VAS.');
  const dirty = vscode.workspace.textDocuments.find(document => document.isDirty && document.uri.scheme === 'file' &&
    document.uri.fsPath.endsWith('.vas') && contains(folder.uri.fsPath, document.uri.fsPath));
  if (dirty) throw new Error('Save all VAS files in this workspace folder before building or running.');
}

async function createTask(definition, folder, name, builds) {
  if (!folder || typeof folder !== 'object' || folder.uri.scheme !== 'file') {
    throw new Error('Open the VAS project as a filesystem workspace folder.');
  }
  const configuration = vscode.workspace.getConfiguration('vas', folder.uri);
  const plan = createPlan({
    trusted: vscode.workspace.isTrusted,
    operation: definition.operation,
    root: folder.uri.fsPath,
    file: definition.file,
    settings: {
      compilerPath: configuration.get('compilerPath'), runnerPath: configuration.get('runnerPath'),
      configFile: configuration.get('configFile'), outputDirectory: configuration.get('outputDirectory')
    }
  });
  requireReady(folder);
  await preparePlan(plan, folder);
  const execution = definition.operation === 'build' ? new vscode.CustomExecution(async () => {
    const terminal = createBuildTerminal({ vscode, plan, diagnostics: builds.diagnostics,
      prepare: async () => { requireReady(folder); await preparePlan(plan, folder); requireReady(folder); },
      done: () => builds.active.delete(terminal)
    });
    builds.active.add(terminal);
    return terminal;
  }) : new vscode.ProcessExecution(plan.executable, plan.args, { cwd: plan.cwd });
  const task = new vscode.Task(definition, folder, name, 'VAS',
    execution, definition.operation === 'build' ? [] : ['$vas-errors', '$vas-warnings']);
  task.group = definition.operation === 'build' ? vscode.TaskGroup.Build : undefined;
  task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Dedicated, clear: true };
  return task;
}

async function preparePlan(plan, folder) {
  await requireFile(plan.executable, 'VAS executable');
  await requireFile(plan.source, 'VAS source');
  if (plan.config) await requireFile(plan.config, 'VAS application interface configuration');
  if (plan.output) {
    // A project may contain a symlink/junction in its configured output path.
    // Validate the nearest existing ancestor BEFORE creating any directories.
    const realRoot = await fs.realpath(folder.uri.fsPath);
    let ancestor = path.dirname(plan.output);
    while (true) {
      try {
        if (!contains(realRoot, await fs.realpath(ancestor))) {
          throw new Error('The VAS output directory resolves outside the workspace.');
        }
        break;
      } catch (error) {
        if (error.code !== 'ENOENT' || ancestor === path.dirname(ancestor)) throw error;
        ancestor = path.dirname(ancestor);
      }
    }
    await fs.mkdir(path.dirname(plan.output), { recursive: true });
    const realDirectory = await fs.realpath(path.dirname(plan.output));
    if (!contains(realRoot, realDirectory)) throw new Error('The VAS output directory resolves outside the workspace.');
    try {
      if ((await fs.lstat(plan.output)).isSymbolicLink()) throw new Error('The VAS output file must not be a symbolic link.');
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}

async function runCurrent(operation, builds) {
  try {
    if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before building or running VAS.');
    const document = vscode.window.activeTextEditor?.document;
    if (!document || document.uri.scheme !== 'file' || !document.uri.fsPath.endsWith('.vas')) {
      throw new Error('Open a saved .vas file to build or run.');
    }
    if (document.isDirty && !(await document.save())) throw new Error('Save the VAS file before building or running.');
    const folder = vscode.workspace.getWorkspaceFolder(document.uri);
    const task = await createTask({ type: 'vas', operation, file: document.uri.fsPath }, folder,
      `${operation === 'build' ? 'Build' : 'Run'} ${path.basename(document.uri.fsPath)}`, builds);
    return await vscode.tasks.executeTask(task);
  } catch (error) {
    void vscode.window.showErrorMessage(`VAS: ${error.message}`);
    return undefined;
  }
}

function activate(context) {
  let collectionId = 0;
  const builds = {
    diagnostics: new BuildDiagnostics(() => vscode.languages.createDiagnosticCollection(`vas-build-${++collectionId}`)),
    active: new Set()
  };
  context.subscriptions.push(
    { dispose() {
      for (const terminal of builds.active) terminal.dispose();
      builds.active.clear();
      builds.diagnostics.dispose();
    } },
    // Until dependency tracking exists, a file edit conservatively invalidates
    // all build results, including pending reads, even if the user saves again.
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme === 'file' && event.contentChanges.length) builds.diagnostics.invalidate();
    }),
    vscode.commands.registerCommand('vas.buildCurrentFile', () => runCurrent('build', builds)),
    vscode.commands.registerCommand('vas.runCurrentFile', () => runCurrent('run', builds)),
    vscode.tasks.registerTaskProvider('vas', {
      provideTasks: () => [],
      async resolveTask(task) {
        try { return await createTask(task.definition, task.scope, task.name, builds); }
        catch (error) { void vscode.window.showErrorMessage(`VAS: ${error.message}`); return undefined; }
      }
    })
  );
}

module.exports = { activate };
