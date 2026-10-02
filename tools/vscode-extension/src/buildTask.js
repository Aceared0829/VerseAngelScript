'use strict';

const fs = require('node:fs/promises');
const { BuildProcess } = require('./buildProcess');
const { sourcePosition } = require('./diagnostics');

function createBuildTerminal({ vscode, plan, prepare, diagnostics, done, createProcess = (plan, callbacks) => new BuildProcess(plan, callbacks) }) {
  const writes = new vscode.EventEmitter();
  const closes = new vscode.EventEmitter();
  let process, token, opened = false, cancelled = false, finished = false, finishing = false, previousCR = false;

  function output(text) {
    if (!text || finished) return;
    const formatted = text.replace(/\n/g, (_, offset) =>
      (offset ? text[offset - 1] === '\r' : previousCR) ? '\n' : '\r\n');
    previousCR = text.endsWith('\r');
    writes.fire(formatted);
  }

  async function publish() {
    if (!diagnostics.current(token)) return;
    const files = new Map();
    for (const record of token.records) {
      if (!files.has(record.file)) files.set(record.file, []);
      files.get(record.file).push(record);
    }
    const entries = [];
    for (const [file, records] of files) {
      let source;
      try { source = (await fs.readFile(file, 'utf8')).split('\n'); } catch { /* Missing sources retain a line-only diagnostic. */ }
      if (!diagnostics.current(token)) return;
      const values = records.map(record => {
        const position = source === undefined ? { line: Math.max(0, record.row - 1), character: 0 } :
          sourcePosition(source, record.row, record.column);
        const value = new vscode.Diagnostic(new vscode.Range(position.line, position.character, position.line, position.character),
          record.message, record.severity === 'ERR' ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning);
        value.source = 'vasbuild';
        return value;
      });
      entries.push([vscode.Uri.file(file), values]);
    }
    return diagnostics.publish(token, entries);
  }

  async function complete(code) {
    if (finished) return;
    finishing = true;
    try {
      if (token && !cancelled && !(await publish())) {
        output('\nVAS: Build diagnostics discarded because files changed or a newer build started. Build again for current positions.\n');
      }
      if (token?.truncated) output('\nVAS: Problems limit reached; further diagnostics remain in terminal output.\n');
    } catch (error) {
      if (token) diagnostics.cancel(token);
      output(`\nVAS could not publish diagnostics: ${error.message}\n`);
    }
    if (finished) return;
    finished = true;
    const exitCode = cancelled ? 130 : code;
    done(exitCode);
    closes.fire(exitCode);
    writes.dispose();
    closes.dispose();
  }

  return {
    onDidWrite: writes.event,
    onDidClose: closes.event,
    open() {
      if (opened || finished) return;
      opened = true;
      void (async () => {
        if (cancelled) return complete(130);
        token = diagnostics.begin(vscode.Uri.file(plan.source).toString());
        // Trust, unsaved sources and filesystem safety are checked again here,
        // not just when tasks.json was resolved or the command was invoked.
        await prepare();
        if (cancelled) return complete(130);
        if (!diagnostics.current(token)) throw new Error('Sources changed before the build started. Run the build again.');
        process = createProcess(plan, { output, diagnostic: record => diagnostics.add(token, record), complete });
        process.start();
      })().catch(error => { output(`VAS: ${error.message}\n`); void complete(1); });
    },
    close() {
      if (finished || cancelled) return;
      cancelled = true;
      if (token) diagnostics.cancel(token);
      if (process) process.terminate();
      // A compiler may already have exited while diagnostic file reads are still
      // pending (e.g. a slow network include). Cancellation must not await them.
      if (opened && (!process || finishing)) void complete(130);
    },
    handleInput(data) { if (data.includes('\x03')) this.close(); },
    dispose() {
      this.close();
      writes.dispose();
      closes.dispose();
    }
  };
}

module.exports = { createBuildTerminal };
