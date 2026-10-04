'use strict';
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { compilerPath } = require('./project');
const { parseDiagnostic, sourcePosition } = require('./diagnostics');
const { inspectSyntax } = require('./editingSupport');

function registerLiveDiagnostics(vscode, context, workspace) {
  const collection = vscode.languages.createDiagnosticCollection('vas-live');
  let generation = 0, timer, child, disposed = false;
  const structural = document => inspectSyntax(document.getText()).map(problem => new vscode.Diagnostic(
    new vscode.Range(document.positionAt(problem.start), document.positionAt(problem.end)), problem.message, vscode.DiagnosticSeverity.Error));
  const opened = () => vscode.workspace.textDocuments.filter(d => d.languageId === 'vas' && d.uri.scheme === 'file' && !d.isClosed);
  const publishStructure = document => collection.set(document.uri, structural(document));
  const run = async (document, revision) => {
    if (!vscode.workspace.isTrusted || !vscode.workspace.getConfiguration('vas', document.uri).get('liveDiagnostics', true)) return;
    const folder = vscode.workspace.getWorkspaceFolder(document.uri);
    if (!folder) return;
    const root = path.resolve(folder.uri.fsPath), config = vscode.workspace.getConfiguration('vas', document.uri);
    let compiler;
    try { compiler = compilerPath(config); } catch { return; }
    if (!compiler) return;
    const text = document.getText(), version = document.version;
    const revisions = opened().map(d => [d, d.version]);
    let interfaceFile = config.get('configFile', '${workspaceFolder}/.vas/vasbuild.config.txt').replaceAll('${workspaceFolder}', root);
    if (!path.isAbsolute(interfaceFile)) interfaceFile = path.resolve(root, interfaceFile);
    try { if (!(await fs.stat(interfaceFile)).isFile()) return; } catch { return; }
    const graph = await workspace.graph(document, { get isCancellationRequested() { return revision !== generation; } });
    if (!graph.length || revision !== generation) return;
    // A graph outside this workspace needs a selected compilation root. Do not
    // silently compile a partial mirror and manufacture missing-module errors.
    if (graph.some(source => { const relative = path.relative(root, source.file); return relative.startsWith('..') || path.isAbsolute(relative); })) return;
    let temporary;
    try {
      temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-vscode-diagnostics-'));
      for (const source of graph) {
        const target = path.join(temporary, path.relative(root, source.file));
        await fs.mkdir(path.dirname(target), { recursive: true }); await fs.writeFile(target, source.text, 'utf8');
      }
      const source = path.join(temporary, path.relative(root, document.uri.fsPath));
      const roots = [];
      for (let parent = path.dirname(source); parent.startsWith(temporary); parent = path.dirname(parent)) {
        roots.push(parent); if (parent === temporary) break;
      }
      if (revision !== generation || disposed) return;
      const output = await new Promise(resolve => {
        const compilerProcess = execFile(compiler, [interfaceFile, source, path.join(temporary, 'diagnostics.vasbc')], {
          cwd: temporary, windowsHide: true, timeout: 10000, maxBuffer: 2 * 1024 * 1024, encoding: 'utf8',
          env: { ...process.env, VAS_INCLUDE_PATH: roots.join(path.delimiter) }
        }, (error, stdout, stderr) => { if (child === compilerProcess) child = undefined; resolve(error?.killed ? '' : stdout + stderr); });
        child = compilerProcess;
      });
      if (disposed || revision !== generation || document.isClosed || document.version !== version || revisions.some(([d, v]) => d.isClosed || d.version !== v)) return;
      const diagnostics = structural(document);
      for (const line of output.split(/\r?\n/)) {
        const issue = parseDiagnostic(line, temporary);
        if (!issue || path.resolve(issue.file).toLowerCase() !== path.resolve(source).toLowerCase()) continue;
        const start = sourcePosition(text, issue.row, issue.column);
        const position = new vscode.Position(start.line, start.character);
        const range = document.getWordRangeAtPosition(position) || new vscode.Range(position, position.translate(0, Math.min(1, document.lineAt(position.line).text.length - position.character)));
        if (!diagnostics.some(d => d.message === issue.message && d.range.start.isEqual(position))) {
          const diagnostic = new vscode.Diagnostic(range, issue.message, issue.severity === 'ERR' ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning);
          diagnostic.source = 'vasbuild'; diagnostics.push(diagnostic);
        }
      }
      collection.set(document.uri, diagnostics);
    } catch (error) { if (!['ENOENT', 'EACCES', 'EPERM'].includes(error.code)) console.error('VAS live diagnostics:', error.message); }
    finally {
      if (temporary && path.dirname(path.resolve(temporary)) === path.resolve(os.tmpdir()) && path.basename(temporary).startsWith('vas-vscode-diagnostics-')) await fs.rm(temporary, { recursive: true, force: true });
    }
  };
  function schedule() {
    const revision = ++generation; clearTimeout(timer); child?.kill();
    for (const document of opened()) publishStructure(document);
    timer = setTimeout(async () => {
      const active = vscode.window.activeTextEditor?.document, documents = opened().sort((a, b) => Number(b === active) - Number(a === active));
      for (const document of documents) { if (revision !== generation || disposed) break; await run(document, revision); }
    }, 450);
  }
  context.subscriptions.push(collection,
    vscode.workspace.onDidOpenTextDocument(schedule), vscode.workspace.onDidCloseTextDocument(document => { collection.delete(document.uri); schedule(); }),
    vscode.workspace.onDidChangeTextDocument(event => { if (event.document.languageId === 'vas' && event.contentChanges.length) schedule(); }),
    vscode.workspace.onDidChangeConfiguration(event => { if (event.affectsConfiguration('vas')) schedule(); }),
    { dispose() { disposed = true; generation++; clearTimeout(timer); child?.kill(); } });
  schedule();
}
module.exports = { registerLiveDiagnostics };
