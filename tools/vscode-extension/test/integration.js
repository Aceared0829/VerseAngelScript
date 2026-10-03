'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vscode = require('vscode');

// Observe the real child-process boundary before activating the extension. These
// wrappers never replace compiler output, exit status, tasks, or VS Code APIs.
const childProcess = require('node:child_process');
const processCalls = [];
for (const method of ['spawn', 'execFile']) {
  const original = childProcess[method];
  childProcess[method] = function(executable, args, options, ...rest) {
    processCalls.push({ method, executable, args: Array.isArray(args) ? [...args] : [], options });
    return original.call(this, executable, args, options, ...rest);
  };
}

const manifestUri = folder => vscode.Uri.joinPath(folder.uri, 'vas-project.json');
const nativeCalls = from => processCalls.slice(from).filter(call => call.executable === process.env.VAS_TEST_COMPILER);
const buildCalls = from => nativeCalls(from).filter(call => call.args.includes('--project'));

async function fixtureFor(folder) {
  return JSON.parse(await fs.readFile(path.join(folder.uri.fsPath, 'integration-project-fixture.json'), 'utf8'));
}

async function missing(file, description = file) {
  await assert.rejects(fs.stat(file), error => error.code === 'ENOENT', `${description} must not exist`);
}

async function assertNoTrap(folder) {
  const fixture = await fixtureFor(folder);
  assert.ok(!processCalls.some(call => path.resolve(call.executable) === path.resolve(folder.uri.fsPath, fixture.trap)),
    'manifest builder/runner must never even be passed to a process API');
  await missing(path.join(folder.uri.fsPath, 'tool-trap-executed'), 'trap execution marker');
}

async function withPickers(actions, start) {
  // The actual Quick Pick is displayed in the real workbench. Observe its items
  // for synchronization, then navigate/accept/cancel with built-in UI commands.
  // No synthetic selected unit, descriptor, or production test hook is used.
  const original = vscode.window.showQuickPick;
  const pending = [...actions];
  const observed = [], interactions = [];
  let pickerError;
  vscode.window.showQuickPick = function(items, options, token) {
    let focused;
    const shown = original.call(this, items, { ...options, onDidSelectItem(item) {
      focused = item;
      options?.onDidSelectItem?.(item);
    } }, token);
    interactions.push((async () => {
      const values = await items;
      const action = pending.shift();
      assert.ok(action, 'an unexpected additional Quick Pick was displayed');
      observed.push(values);
      const label = item => typeof item === 'string' ? item : item.label;
      const index = action.cancel ? -1 : values.findIndex(item => label(item) === action.label);
      if (!action.cancel) assert.notEqual(index, -1, `Quick Pick must offer ${action.label}`);
      // onDidSelectItem is the public focus notification from the real picker.
      // Wait on that RPC rather than guessing how quickly the workbench paints.
      await eventually(() => focused !== undefined, 'the real project picker to gain focus');
      assert.equal(label(focused), label(values[0]), 'picker must initially focus the first offered item');
      if (action.cancel) await vscode.commands.executeCommand('workbench.action.closeQuickOpen');
      else {
        for (let count = 0; count < index; count++) {
          await vscode.commands.executeCommand('workbench.action.quickOpenSelectNext');
          await eventually(() => label(focused) === label(values[count + 1]), 'picker navigation to move focus');
        }
        await vscode.commands.executeCommand('workbench.action.acceptSelectedQuickOpenItem');
      }
      const selected = await shown;
      if (action.cancel) assert.equal(selected, undefined);
      else assert.equal(label(selected), action.label, 'the real picker must return the requested choice');
    })().catch(async error => {
      pickerError = error;
      await vscode.commands.executeCommand('workbench.action.closeQuickOpen');
    }));
    return shown;
  };
  let timer;
  try {
    const result = await Promise.race([start(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Timed out driving the project Quick Pick UI')), 15000);
    })]);
    await Promise.all(interactions);
    if (pickerError) throw pickerError;
    assert.equal(pending.length, 0, 'the command must display every required root/unit picker');
    return { result, observed };
  } finally {
    clearTimeout(timer);
    vscode.window.showQuickPick = original;
    await vscode.commands.executeCommand('workbench.action.closeQuickOpen');
  }
}

async function projectCommand(actions) {
  return (await withPickers(actions, () => vscode.commands.executeCommand('vas.buildProject'))).result;
}

async function projectTask(folder, id) {
  const tasks = await vscode.tasks.fetchTasks({ type: 'vas' });
  const task = tasks.find(item => item.definition.operation === 'buildProject' && item.definition.unit === id &&
    item.scope?.uri?.toString() === folder.uri.toString());
  assert.ok(task, `tasks.json must resolve project unit ${id} in its own workspace root`);
  return task;
}

async function projectTaskExit(folder, id) {
  const task = await projectTask(folder, id);
  const from = processCalls.length;
  const code = await taskExit(() => vscode.tasks.executeTask(task), `project task ${id}`);
  const calls = buildCalls(from);
  assert.equal(calls.length, 1, 'the selected project task must invoke the compiler exactly once');
  assert.deepEqual(calls[0].args, ['--report=jsonl', '--project', manifestUri(folder).fsPath, '--unit', id],
    'the native project command must preserve manifest and unit argument boundaries');
  assert.equal(calls[0].options.shell, false, 'project compilation must never use a shell');
  return code;
}

async function replaceText(document, text) {
  const edit = new vscode.WorkspaceEdit();
  edit.replace(document.uri, new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length)), text);
  assert.equal(await vscode.workspace.applyEdit(edit), true);
  assert.equal(document.isDirty, true);
}

async function blockedProject(actions, description, { descriptorAllowed = false } = {}) {
  const from = processCalls.length;
  let started = 0;
  const listener = vscode.tasks.onDidStartTask(() => started++);
  try {
    assert.equal(await projectCommand(actions), undefined, description);
    assert.equal(started, 0, `${description}: no task may start`);
    assert.equal(buildCalls(from).length, 0, `${description}: no native build may run`);
    if (!descriptorAllowed) assert.equal(nativeCalls(from).length, 0, `${description}: no descriptor may run`);
  } finally { listener.dispose(); }
}

async function singleRootProjectTests(folder) {
  const fixture = await fixtureFor(folder);
  const unit = fixture.manifest.compilationUnits[0];
  const output = path.join(folder.uri.fsPath, unit.output);
  await missing(output);
  await blockedProject([{ cancel: true }], 'cancelling the mandatory one-unit picker', { descriptorAllowed: true });
  await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  // Even with an include active and exactly one unit, the unit picker is required.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(
    vscode.Uri.joinPath(folder.uri, fixture.projectPaths.source, 'library 文😀.vas')));
  assert.equal(await taskExit(() => projectCommand([{ label: unit.id }]), 'single-root project command'), 0);
  const current = await fs.readFile(output);
  assert.ok(current.length > 0);
  await fs.rm(path.join(folder.uri.fsPath, fixture.projectPaths.output), { recursive: true, force: true });

  const legacy = { name: 'Legacy 工程😀', entry: unit.entry, builderConfig: unit.hostApi.config,
    bytecodeOutput: unit.output, builder: fixture.trap, runner: fixture.trap };
  // Observe the real watcher notification before starting another selection.
  // Otherwise a late disk-edit event can correctly invalidate that selection.
  const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(folder, 'vas-project.json'));
  let changed = false;
  const listener = watcher.onDidChange(() => { changed = true; });
  try {
    await fs.writeFile(manifestUri(folder).fsPath, JSON.stringify(legacy));
    await eventually(() => changed, 'legacy manifest filesystem notification');
  } finally { listener.dispose(); watcher.dispose(); }
  assert.equal(await taskExit(() => projectCommand([{ label: 'main' }]), 'legacy project command'), 0);
  assert.deepEqual(await fs.readFile(output), current, 'legacy normalization and versioned project builds must be byte-for-byte equivalent');
  await assertNoTrap(folder);
  await fs.rm(path.join(folder.uri.fsPath, fixture.projectPaths.output), { recursive: true, force: true });
  console.log('PASS: real single-root/one-unit picker, cancellation without artifacts, active include, legacy/versioned native bytecode equivalence and manifest tool traps');
}

async function projectTests(folder, second) {
  const fixture = await fixtureFor(folder), other = await fixtureFor(second);
  const units = fixture.manifest.compilationUnits;
  const rootPick = { label: folder.name };
  const taskDiscoveryStart = processCalls.length;
  const unknownUnitTask = await projectTask(folder, 'unknown-unit');
  assert.equal(nativeCalls(taskDiscoveryStart).length, 0, 'task enumeration and resolution must not launch project discovery');
  const uri = name => vscode.Uri.joinPath(folder.uri, fixture.projectPaths.source, name);
  await blockedProject([{ cancel: true }], 'cancelling the workspace picker');
  await blockedProject([rootPick, { cancel: true }], 'cancelling the multi-unit picker', { descriptorAllowed: true });
  for (const workspace of [folder, second]) {
    await missing(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output));
  }

  // The active document belongs to another root and is an include, not an entry.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(
    vscode.Uri.joinPath(second.uri, other.projectPaths.source, 'library 文😀.vas')));
  const from = processCalls.length;
  assert.equal(await taskExit(() => projectCommand([rootPick, { label: units[1].id }]), 'explicit multi-root/unit selection'), 0);
  assert.deepEqual(buildCalls(from).map(call => call.args), [
    ['--report=jsonl', '--project', manifestUri(folder).fsPath, '--unit', units[1].id]
  ]);
  await eventually(() => vscode.languages.getDiagnostics(uri('warning 文😀.vas')).some(item =>
    item.severity === vscode.DiagnosticSeverity.Warning), 'project compiler warning');
  assert.deepEqual(await fs.readdir(path.join(folder.uri.fsPath, fixture.projectPaths.output)), [path.basename(units[1].output)],
    'only the explicitly selected compilation unit may create bytecode');
  await missing(path.join(second.uri.fsPath, other.projectPaths.output));
  assert.equal(await taskExit(() => projectCommand([{ label: second.name }, { label: other.manifest.compilationUnits[0].id }]),
    'second workspace project command'), 0);
  assert.ok((await fs.stat(path.join(second.uri.fsPath, other.manifest.compilationUnits[0].output))).size > 0);

  assert.notEqual(await projectTaskExit(folder, 'broken-include'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'broken-include').output));
  const brokenInclude = await vscode.workspace.openTextDocument(uri('broken include 文😀.vas'));
  await eventually(() => vscode.languages.getDiagnostics(brokenInclude.uri).some(item => item.message === 'Must return a value' &&
    item.range.start.line === 0 && item.range.start.character === brokenInclude.lineAt(0).text.indexOf('return')),
  'project included-file UTF-16 diagnostic location');
  assert.notEqual(await projectTaskExit(folder, 'host-missing'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'host-missing').output));
  await eventually(() => vscode.languages.getDiagnostics(uri('host 文😀.vas')).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'missing host API diagnostic');
  assert.equal(await projectTaskExit(folder, 'host-present'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('host 文😀.vas')).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'successful unit with the same entry and a different host config must preserve the failing unit diagnostics');
  assert.ok(vscode.languages.getDiagnostics(brokenInclude.uri).some(item => item.message === 'Must return a value'),
    'another project unit must not clear include diagnostics');

  assert.notEqual(await projectTaskExit(folder, 'invalid-config'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'invalid-config').output));
  const configUri = vscode.Uri.joinPath(folder.uri, fixture.projectPaths.config, 'invalid 文😀.txt');
  await eventually(() => vscode.languages.getDiagnostics(configUri).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'project host-config file diagnostic');
  const config = await vscode.workspace.openTextDocument(configUri);
  const configText = config.getText();
  const cachedTask = await projectTask(folder, 'invalid-config');
  await replaceText(config, configText + '// unsaved config\n');
  await eventually(() => vscode.languages.getDiagnostics(configUri).length === 0, 'dirty configuration invalidation');
  await blockedProject([rootPick], 'dirty selected host configuration', { descriptorAllowed: true });
  const dirtyTaskStart = processCalls.length;
  assert.notEqual(await taskExit(() => vscode.tasks.executeTask(cachedTask), 'cached task with dirty configuration'), 0);
  assert.equal(buildCalls(dirtyTaskStart).length, 0, 'a previously resolved task must recheck dirty configuration before compilation');
  await replaceText(config, configText);
  assert.equal(await config.save(), true);

  const includeText = brokenInclude.getText();
  await replaceText(brokenInclude, includeText + '// unsaved included source\n');
  await blockedProject([rootPick], 'dirty included project source');
  assert.equal(brokenInclude.isDirty, true, 'project selection must not silently save included sources');
  await replaceText(brokenInclude, includeText);
  assert.equal(await brokenInclude.save(), true);

  // Unlike Build Current File, a project command must never save a guessed entry.
  const source = await vscode.workspace.openTextDocument(uri('main 文😀.vas'));
  const sourceText = source.getText();
  await replaceText(source, sourceText + '// unsaved project source\n');
  await vscode.window.showTextDocument(source);
  await blockedProject([rootPick], 'dirty project source');
  assert.equal(source.isDirty, true);
  await replaceText(source, sourceText);
  assert.equal(await source.save(), true);

  const manifest = await vscode.workspace.openTextDocument(manifestUri(folder));
  const manifestText = manifest.getText();
  await replaceText(manifest, manifestText + ' ');
  await blockedProject([rootPick], 'dirty project manifest');
  await replaceText(manifest, manifestText);
  assert.equal(await manifest.save(), true);

  // Persisted malformed JSON and unknown schema versions are rejected by the
  // authoritative native descriptor; no build or output directory is created.
  for (const workspace of [folder, second]) {
    await fs.rm(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output), { recursive: true, force: true });
  }
  const unknownStart = processCalls.length;
  assert.notEqual(await taskExit(() => vscode.tasks.executeTask(unknownUnitTask), 'unknown compilation unit'), 0);
  assert.equal(buildCalls(unknownStart).length, 0, 'unknown units must be rejected before native compilation');
  await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  for (const invalid of ['{"schemaVersion":', JSON.stringify({ ...fixture.manifest, schemaVersion: 999 })]) {
    await replaceText(manifest, invalid);
    assert.equal(await manifest.save(), true);
    await blockedProject([rootPick], 'invalid project manifest', { descriptorAllowed: true });
    await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  }
  await replaceText(manifest, manifestText);
  assert.equal(await manifest.save(), true);
  await assertNoTrap(folder);
  await assertNoTrap(second);
  // Resolving the preserved current-file tasks can prepare their .vas folders.
  for (const workspace of [folder, second]) await fs.rm(path.join(workspace.uri.fsPath, '.vas'), { recursive: true, force: true });
  console.log('PASS: explicit real multi-root/multi-unit picks, Unicode/metacharacter argv, configured project tasks, native warnings/include/config diagnostics, same-entry config isolation, dirty source/config/manifest rejection, invalid/cancel no-artifact behavior');
}

async function eventually(check, description) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  console.error('Diagnostics at timeout:', JSON.stringify(vscode.languages.getDiagnostics().map(([uri, diagnostics]) => ({
    uri: uri.toString(), diagnostics: diagnostics.map(item => ({ message: item.message, severity: item.severity, line: item.range.start.line }))
  }))));
  throw new Error(`Timed out waiting for ${description}`);
}

async function taskExit(start, label) {
  const ended = new Map(), finished = new Set();
  // VS Code 1.96.4 terminalTaskSystem forwards CustomExecution PTY close codes
  // as ProcessEnded, then End. Require both and a real numeric exit code.
  const taskListener = vscode.tasks.onDidEndTask(event => finished.add(event.execution));
  const listener = vscode.tasks.onDidEndTaskProcess(event => ended.set(event.execution, event.exitCode));
  try {
    const execution = await start();
    assert.ok(execution, `${label} must start a task`);
    await eventually(() => ended.has(execution) && finished.has(execution), `${label} process and task exit`);
    const code = ended.get(execution);
    assert.ok(Number.isInteger(code), `${label} must report the real numeric exit code, got ${code}`);
    return code;
  } finally { listener.dispose(); taskListener.dispose(); }
}

const commandExit = command => taskExit(() => vscode.commands.executeCommand(command), command);

async function run() {
  const folder = vscode.workspace.workspaceFolders[0];
  const root = folder.uri.fsPath;
  const uri = name => vscode.Uri.file(path.join(root, 'src', name));
  const initialUri = process.env.VAS_TEST_MODE === 'single-root' ?
    vscode.Uri.joinPath(folder.uri, (await fixtureFor(folder)).projectPaths.source, 'main 文😀.vas') : uri('main 文😀.vas');
  const document = await vscode.workspace.openTextDocument(initialUri);
  await vscode.window.showTextDocument(document);
  assert.equal(document.languageId, 'vas');
  const extension = vscode.extensions.getExtension('VerseAngelScript.verseangelscript-vscode');
  assert.ok(extension, 'extension must be registered');
  const activationStart = processCalls.length;
  await extension.activate();
  assert.equal(nativeCalls(activationStart).length, 0, 'extension activation must never describe or compile a project');
  const commands = await vscode.commands.getCommands(true);
  assert.ok(commands.includes('vas.buildCurrentFile'));
  assert.ok(commands.includes('vas.runCurrentFile'));
  assert.ok(commands.includes('vas.buildProject'));
  for (const workspace of vscode.workspace.workspaceFolders) await assertNoTrap(workspace);

  if (process.env.VAS_TEST_MODE === 'untrusted') {
    assert.equal(vscode.workspace.isTrusted, false, 'Restricted Mode must really be active');
    let started = 0;
    const listener = vscode.tasks.onDidStartTask(() => started++);
    try {
      assert.equal(await vscode.commands.executeCommand('vas.buildCurrentFile'), undefined);
      assert.equal(await vscode.commands.executeCommand('vas.runCurrentFile'), undefined);
      const from = processCalls.length;
      assert.equal(await vscode.commands.executeCommand('vas.buildProject'), undefined);
      const tasks = await vscode.tasks.fetchTasks({ type: 'vas' });
      assert.ok(!tasks.some(task => task.definition.operation === 'buildProject' && task.execution),
        'Restricted Mode must not resolve an executable project task');
      assert.equal(nativeCalls(from).length, 0, 'Restricted Mode must not invoke even project discovery');
      for (const workspace of vscode.workspace.workspaceFolders) {
        await assertNoTrap(workspace);
        await missing(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output));
      }
      assert.equal(started, 0);
      await assert.rejects(fs.stat(path.join(root, '.vas')));
    } finally { listener.dispose(); }
    console.log('PASS: .vas recognition and execution blocking in Restricted Mode');
    return;
  }

  assert.equal(vscode.workspace.isTrusted, true);
  assert.ok(process.env.VAS_TEST_COMPILER && process.env.VAS_TEST_RUNNER, 'Set VAS_TEST_COMPILER and VAS_TEST_RUNNER to freshly built tools.');
  const config = vscode.workspace.getConfiguration('vas', folder.uri);
  await config.update('compilerPath', process.env.VAS_TEST_COMPILER, vscode.ConfigurationTarget.Global);
  await config.update('runnerPath', process.env.VAS_TEST_RUNNER, vscode.ConfigurationTarget.Global);
  if (process.env.VAS_TEST_MODE === 'single-root') {
    await singleRootProjectTests(folder);
    return;
  }
  await config.update('configFile', 'config 文😀/api 接口😀.txt', vscode.ConfigurationTarget.WorkspaceFolder);
  await config.update('outputDirectory', '.vas/build 出力😀', vscode.ConfigurationTarget.WorkspaceFolder);
  const second = vscode.workspace.workspaceFolders[1];
  const secondConfig = vscode.workspace.getConfiguration('vas', second.uri);
  await secondConfig.update('configFile', 'config 二😀/second api 二😀.txt', vscode.ConfigurationTarget.WorkspaceFolder);
  await secondConfig.update('outputDirectory', '.vas/second 二😀', vscode.ConfigurationTarget.WorkspaceFolder);

  const edit = new vscode.WorkspaceEdit();
  edit.insert(document.uri, new vscode.Position(0, 0), '// saved by explicit build\n');
  assert.equal(await vscode.workspace.applyEdit(edit), true);
  assert.equal(document.isDirty, true);
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  assert.equal(document.isDirty, false);
  assert.match(await fs.readFile(document.uri.fsPath, 'utf8'), /saved by explicit build/);
  const outputDirectory = path.join(root, '.vas', 'build 出力😀', 'src');
  assert.deepEqual(await fs.readdir(outputDirectory), ['main 文😀.vasbc'], 'bytecode filename must preserve Unicode exactly');
  assert.ok((await fs.stat(path.join(outputDirectory, 'main 文😀.vasbc'))).size > 0);
  assert.equal(await commandExit('vas.runCurrentFile'), 0);

  const configured = (await vscode.tasks.fetchTasks({ type: 'vas' })).find(task => task.name === 'Fixture build');
  assert.ok(configured, 'tasks.json provider must resolve the configured build');
  assert.equal(await taskExit(() => vscode.tasks.executeTask(configured), 'configured VAS task'), 0);

  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri('warning.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('warning.vas')).some(diagnostic =>
    diagnostic.severity === vscode.DiagnosticSeverity.Warning), 'compiler warning in Problems');

  const broken = await vscode.workspace.openTextDocument(uri('broken.vas'));
  await vscode.window.showTextDocument(broken);
  const included = await vscode.workspace.openTextDocument(uri('shared 文😀.vas'));
  const includeEdit = new vscode.WorkspaceEdit();
  includeEdit.insert(included.uri, new vscode.Position(0, 0), '/* unsaved */ ');
  assert.equal(await vscode.workspace.applyEdit(includeEdit), true);
  assert.equal(included.isDirty, true);
  let started = 0;
  const listener = vscode.tasks.onDidStartTask(() => started++);
  try {
    assert.equal(await vscode.commands.executeCommand('vas.buildCurrentFile'), undefined);
    assert.equal(started, 0, 'dirty include must block compiler execution');
  } finally { listener.dispose(); }
  assert.equal(await included.save(), true);
  assert.notEqual(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic =>
    diagnostic.severity === vscode.DiagnosticSeverity.Error && diagnostic.message === 'Must return a value' &&
    diagnostic.range.start.line === 0 && diagnostic.range.start.character === included.lineAt(0).text.indexOf('return')),
  'compiler error on the included file in the Problems view');

  // Per-entry native collections survive unrelated successful builds and runs.
  // Configuration still follows the selected workspace root.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.joinPath(second.uri, 'main 二😀.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  const secondOutputDirectory = path.join(second.uri.fsPath, '.vas', 'second 二😀');
  assert.deepEqual(await fs.readdir(secondOutputDirectory), ['main 二😀.vasbc']);
  assert.ok((await fs.stat(path.join(secondOutputDirectory, 'main 二😀.vasbc'))).size > 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'successful builds in another workspace must preserve existing entry diagnostics');
  assert.equal(await commandExit('vas.runCurrentFile'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'run problem matchers must not clear pipe-backed build diagnostics');
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri('main 文😀.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'successful builds of another entry in the same root must preserve diagnostics');

  await vscode.window.showTextDocument(broken);
  assert.notEqual(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length > 0, 'rebuilt entry diagnostics');
  const fix = new vscode.WorkspaceEdit();
  fix.replace(included.uri, new vscode.Range(included.positionAt(0), included.positionAt(included.getText().length)), 'int Broken() { return 1; }\n');
  assert.equal(await vscode.workspace.applyEdit(fix), true);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length === 0, 'edited-source diagnostics to be invalidated before rebuilding');
  assert.equal(await included.save(), true);
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length === 0, 'stale diagnostics to clear after a clean build');
  await fs.rm(path.join(root, '.vas'), { recursive: true, force: true });
  await fs.rm(path.join(second.uri.fsPath, '.vas'), { recursive: true, force: true });
  console.log('PASS: native Unicode/space build/run paths, exact bytecode names, errors/warnings, UTF-16 include positions, multi-root configuration, per-entry isolation and edit invalidation');
  await projectTests(folder, second);
}

module.exports = { run };
