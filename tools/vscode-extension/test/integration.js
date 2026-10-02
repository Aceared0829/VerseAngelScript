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
  throw new Error(`Timed out waiting for ${description}`);
}

async function commandExit(command) {
  const ended = new Map();
  const listener = vscode.tasks.onDidEndTaskProcess(event => ended.set(event.execution, event.exitCode));
  try {
    const execution = await vscode.commands.executeCommand(command);
    assert.ok(execution, `${command} must start a task`);
    await eventually(() => ended.has(execution), `${command} process exit`);
    return ended.get(execution);
  } finally { listener.dispose(); }
}

async function run() {
  const folder = vscode.workspace.workspaceFolders[0];
  const root = folder.uri.fsPath;
  const uri = name => vscode.Uri.file(path.join(root, 'src', name));
  const document = await vscode.workspace.openTextDocument(uri('main.vas'));
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
      await assert.rejects(fs.stat(path.join(root, '.vas', 'build')));
    } finally { listener.dispose(); }
    console.log('PASS: .vas recognition and execution blocking in Restricted Mode');
    return;
  }

  assert.equal(vscode.workspace.isTrusted, true);
  assert.ok(process.env.VAS_TEST_COMPILER && process.env.VAS_TEST_RUNNER, 'Set VAS_TEST_COMPILER and VAS_TEST_RUNNER to freshly built tools.');
  const config = vscode.workspace.getConfiguration('vas', folder.uri);
  await config.update('compilerPath', process.env.VAS_TEST_COMPILER, vscode.ConfigurationTarget.Global);
  await config.update('runnerPath', process.env.VAS_TEST_RUNNER, vscode.ConfigurationTarget.Global);
  await config.update('configFile', 'api.txt', vscode.ConfigurationTarget.WorkspaceFolder);
  await config.update('outputDirectory', '.vas/build', vscode.ConfigurationTarget.WorkspaceFolder);

  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  assert.ok((await fs.stat(path.join(root, '.vas/build/src/main.vasbc'))).size > 0);
  assert.equal(await commandExit('vas.runCurrentFile'), 0);

  const broken = await vscode.workspace.openTextDocument(uri('broken.vas'));
  await vscode.window.showTextDocument(broken);
  assert.notEqual(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文.vas')).some(diagnostic =>
    diagnostic.severity === vscode.DiagnosticSeverity.Error && diagnostic.range.start.line === 0),
  'compiler error on the included file in the Problems view');
  await fs.writeFile(uri('shared 文.vas').fsPath, 'int Broken() { return 1; }\n');
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文.vas')).length === 0, 'stale diagnostics to clear after a clean build');
  await fs.rm(path.join(root, '.vas'), { recursive: true, force: true });
  console.log('PASS: native build/run tasks, bytecode, include-file diagnostics, and diagnostic clearing');
}

module.exports = { run };
