'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vscode = require('vscode');

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
  const document = await vscode.workspace.openTextDocument(uri('main 文😀.vas'));
  await vscode.window.showTextDocument(document);
  assert.equal(document.languageId, 'vas');
  const extension = vscode.extensions.getExtension('VerseAngelScript.verseangelscript-vscode');
  assert.ok(extension, 'extension must be registered');
  await extension.activate();
  const commands = await vscode.commands.getCommands(true);
  assert.ok(commands.includes('vas.buildCurrentFile'));
  assert.ok(commands.includes('vas.runCurrentFile'));

  if (process.env.VAS_TEST_MODE === 'untrusted') {
    assert.equal(vscode.workspace.isTrusted, false, 'Restricted Mode must really be active');
    let started = 0;
    const listener = vscode.tasks.onDidStartTask(() => started++);
    try {
      assert.equal(await vscode.commands.executeCommand('vas.buildCurrentFile'), undefined);
      assert.equal(await vscode.commands.executeCommand('vas.runCurrentFile'), undefined);
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
}

module.exports = { run };
