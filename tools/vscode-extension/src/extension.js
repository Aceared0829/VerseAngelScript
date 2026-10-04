'use strict';

const vscode = require('vscode');
const fs = require('node:fs/promises');
const path = require('node:path');
const { contains, createPlan } = require('./toolchain');
const { BuildDiagnostics } = require('./diagnostics');
const { createBuildTerminal } = require('./buildTask');
const { createProjectTerminal } = require('./projectTask');
const { ProjectDependencies } = require('./projectReport');
const { createProjectWatchers } = require('./projectWatch');
const { ProjectInputObservations } = require('./projectObservations');
const { documentDigest } = require('./projectVersions');
const { compilerPath, projectRequest, snapshotDescriptor, projectPlan } = require('./project');

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
  if (definition.operation === 'buildProject') return createProjectTask(definition, folder, name, builds);
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
      prepare: async () => { requireReady(folder); await preparePlan(plan, folder, true); requireReady(folder); },
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

async function preparePlan(plan, folder, createOutput = false) {
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
    // Task enumeration/resolution is read-only. Create directories only for
    // an actually opened build task, after repeating the same safety checks.
    if (createOutput) {
      await fs.mkdir(path.dirname(plan.output), { recursive: true });
      const realDirectory = await fs.realpath(path.dirname(plan.output));
      if (!contains(realRoot, realDirectory)) throw new Error('The VAS output directory resolves outside the workspace.');
    }
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

function createProjectTask(definition, folder, name, builds, selected) {
  if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before building a VAS project.');
  if (!folder || typeof folder !== 'object' || folder.uri.scheme !== 'file') throw new Error('Open a filesystem workspace folder for Build Project.');
  const executable = compilerPath(vscode.workspace.getConfiguration('vas', folder.uri));
  const request = projectRequest(definition, folder, executable);
  const execution = new vscode.CustomExecution(async () => {
    const terminal = createProjectTerminal({ vscode, diagnostics: builds.projectDiagnostics, dependencies: builds.dependencies, observations: builds.observations,
      prepare: cancel => projectPlan(request, selected, { vscode, folder, cancel, dependencies: builds.dependencies }),
      done: () => builds.active.delete(terminal) });
    builds.active.add(terminal);
    return terminal;
  });
  const task = new vscode.Task(definition, folder, name, 'VAS', execution, []);
  task.group = vscode.TaskGroup.Build;
  task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Dedicated, clear: true };
  return task;
}

async function buildProject(builds) {
  try {
    if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before building a VAS project.');
    const folders = (vscode.workspace.workspaceFolders || []).filter(folder => folder.uri.scheme === 'file');
    if (!folders.length) throw new Error('Open a filesystem workspace folder for Build Project.');
    const picked = folders.length === 1 ? { folder: folders[0] } : await vscode.window.showQuickPick(
      folders.map(folder => ({ label: folder.name, description: folder.uri.fsPath, folder })),
      { title: 'VAS: Select Project Workspace', placeHolder: 'Choose the workspace root containing vas-project.json' });
    if (!picked) return undefined;
    const folder = picked.folder, project = path.join(folder.uri.fsPath, 'vas-project.json');
    await builds.observations.settle();
    const revision = builds.projectDiagnostics.revision;
    const executable = compilerPath(vscode.workspace.getConfiguration('vas', folder.uri));
    const selected = await snapshotDescriptor({ vscode, folder, project, executable });
    if (revision !== builds.projectDiagnostics.revision) throw new Error('VAS inputs changed during project selection. Build Project again.');
    const unit = await vscode.window.showQuickPick(selected.descriptor.compilationUnits.map(unit => ({
      label: unit.id, description: unit.entry, detail: `Host API: ${unit.hostApi.config}   Output: ${unit.output}`, unit
    })), { title: 'VAS: Select Compilation Unit', placeHolder: 'Choose an entry, host API configuration and bytecode output' });
    if (!unit) return undefined;
    if (revision !== builds.projectDiagnostics.revision) throw new Error('VAS inputs changed during project selection. Build Project again.');
    const task = createProjectTask({ type: 'vas', operation: 'buildProject', project: 'vas-project.json', unit: unit.unit.id },
      folder, `Build Project ${unit.unit.id}`, builds, selected);
    return await vscode.tasks.executeTask(task);
  } catch (error) { void vscode.window.showErrorMessage(`VAS: ${error.message}`); return undefined; }
}

function activate(context) {
  if (vscode.languages.registerDefinitionProvider) require('./languageFeatures').registerLanguageFeatures(vscode, context);
  let collectionId = 0;
  const builds = {
    diagnostics: new BuildDiagnostics(() => vscode.languages.createDiagnosticCollection(`vas-build-${++collectionId}`)),
    projectDiagnostics: new BuildDiagnostics(() => vscode.languages.createDiagnosticCollection(`vas-project-${++collectionId}`)),
    active: new Set(),
    dependencies: new ProjectDependencies()
  };
  builds.observations = new ProjectInputObservations(builds.projectDiagnostics, builds.dependencies);
  context.subscriptions.push(
    { dispose() {
      for (const terminal of builds.active) terminal.dispose();
      builds.active.clear();
      builds.observations.dispose();
      builds.diagnostics.dispose();
      builds.projectDiagnostics.dispose();
    } },
    // Dirty edits invalidate immediately. Clean reloads can preserve Project
    // diagnostics only when text and disk match that generation's baseline.
    // Current keeps its existing invalidation behavior. No automatic rebuild.
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme === 'file' && event.contentChanges.length) {
        builds.diagnostics.invalidate();
        if (event.document.isDirty !== false) builds.observations.invalidate();
        else {
          // A saved hardlink can reload another clean editor after compilation.
          // Its actual text and disk identity must both match a pre-launch
          // baseline; isDirty=false by itself is never sufficient evidence.
          const digest = documentDigest(event.document.getText());
          if (digest === undefined) builds.observations.invalidate();
          else builds.observations.changed(event.document.uri.fsPath, { documentDigest: digest });
        }
      }
    }),
    vscode.commands.registerCommand('vas.buildProject', () => buildProject(builds)),
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
  if (vscode.workspace.onDidChangeConfiguration) context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(event => {
    if (event.affectsConfiguration('vas')) builds.observations.invalidate();
  }));
  if (vscode.workspace.createFileSystemWatcher) context.subscriptions.push(
    createProjectWatchers(vscode, builds.dependencies, () => builds.observations.invalidate(), builds.observations));
}

module.exports = { activate };
