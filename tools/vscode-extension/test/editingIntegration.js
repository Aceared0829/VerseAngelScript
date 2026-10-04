'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vscode = require('vscode');

async function run() {
  const folder = vscode.workspace.workspaceFolders[0].uri.fsPath, file = path.join(folder, 'editor.vas');
  await fs.writeFile(file, '');
  await vscode.extensions.getExtension('VerseAngelScript.verseangelscript-vscode').activate();
  const document = await vscode.workspace.openTextDocument(file), editor = await vscode.window.showTextDocument(document);
  const replace = async text => {
    const edit = new vscode.WorkspaceEdit(); edit.replace(document.uri, new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length)), text);
    assert(await vscode.workspace.applyEdit(edit)); const end = document.positionAt(document.getText().length); editor.selection = new vscode.Selection(end, end);
  };
  for (const [open, close] of [['{', '}'], ['(', ')'], ['[', ']'], ['"', '"'], ["'", "'"]]) {
    await replace(''); await vscode.commands.executeCommand('type', { text: open }); assert.equal(document.getText(), open + close);
    assert.equal(document.offsetAt(editor.selection.active), 1);
    await vscode.commands.executeCommand('deleteLeft'); assert.equal(document.getText(), '');
    await vscode.commands.executeCommand('type', { text: open }); await vscode.commands.executeCommand('type', { text: close });
    assert.equal(document.getText(), open + close); assert.equal(document.offsetAt(editor.selection.active), 2);
  }
  await replace('// comment '); await vscode.commands.executeCommand('type', { text: '{' }); assert.equal(document.getText(), '// comment {');
  const prefix = 'int CalculateScore(int Left, int Right) { return Left + Right; }\nvoid Main() { Calcu';
  await replace(prefix);
  const items = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', document.uri, editor.selection.active);
  const functionItem = items.items.find(item => item.label === 'CalculateScore'); assert(functionItem); assert.equal(functionItem.insertText.value, 'CalculateScore($0)');
  await vscode.commands.executeCommand('editor.action.triggerSuggest'); await new Promise(r => setTimeout(r, 300));
  await vscode.commands.executeCommand('acceptSelectedSuggestion');
  assert(document.getText().endsWith('CalculateScore()'), document.getText());
  assert.equal(document.getText()[document.offsetAt(editor.selection.active)], ')');
  const help = await vscode.commands.executeCommand('vscode.executeSignatureHelpProvider', document.uri, editor.selection.active, '(');
  assert(help.signatures[0].label.includes('CalculateScore')); assert.equal(help.signatures[0].parameters.length, 2);
  const waitFor = async predicate => {
    for (let i = 0; i < 100; i++) { const values = vscode.languages.getDiagnostics(document.uri); if (predicate(values)) return values; await new Promise(r => setTimeout(r, 100)); }
    throw new Error('Diagnostic wait failed: ' + JSON.stringify(vscode.languages.getDiagnostics(document.uri)));
  };
  await replace('void Main() {'); await waitFor(values => values.some(d => d.message === "Expected '}'"));
  await replace('void Main() { int Value = MissingEditorName; }');
  await waitFor(values => values.some(d => d.source === 'vasbuild' && d.message.includes('MissingEditorName')));
  await replace('void Main() { int Value = 1 return; }'); await waitFor(values => values.some(d => d.source === 'vasbuild'));
  await replace('void Main() { int Value = 1; }'); await waitFor(values => values.length === 0);
  const libraryFile = path.join(folder, 'Library.vas'); await fs.writeFile(libraryFile, 'namespace Library { int Value() { return 1; } }');
  const library = await vscode.workspace.openTextDocument(libraryFile);
  const change = new vscode.WorkspaceEdit(); change.replace(library.uri, new vscode.Range(library.positionAt(0), library.positionAt(library.getText().length)), 'namespace Library { int NewValue() { return 2; } }'); assert(await vscode.workspace.applyEdit(change));
  await replace('import Library;\nvoid Main() { int Value = Library::NewValue(); }'); await waitFor(values => values.length === 0);
  await new Promise(r => setTimeout(r, 800)); assert.equal(vscode.languages.getDiagnostics(document.uri).length, 0);
  if (process.platform === 'linux') {
    await fs.writeFile(path.join(folder, 'Editor.vas'), 'void BrokenCase() { UnknownCaseName(); }');
    await replace('import Editor;\nvoid Main() {}');
    await new Promise(r => setTimeout(r, 2000));
    assert.equal(vscode.languages.getDiagnostics(document.uri).length, 0, 'case-distinct imported errors must not be published on editor.vas');
  }
  const evidence = { host: vscode.version, nativeTyping: true, pairs: 5, closingSkip: true, pairedBackspace: true, commentProtection: true,
    nativeFunctionCompletion: true, parameterHelp: true, structuralErrors: true, nativeCompilerErrors: true, missingSemicolon: true, clearedErrorsAfterFix: true, unsavedImport: true };
  await fs.writeFile(process.env.VAS_EDITING_EVIDENCE, JSON.stringify(evidence, null, 2));
  console.log('VAS editing integration PASS ' + JSON.stringify(evidence));
}
module.exports = { run };
