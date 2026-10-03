'use strict';

const { sourcePosition } = require('./diagnostics');
const { cancellation, readSourceText } = require('./project');
const { ProjectBuildProcess } = require('./projectReport');

function createProjectTerminal({ vscode, prepare, diagnostics, dependencies, done,
  createProcess = (plan, callbacks) => new ProjectBuildProcess(plan, callbacks) }) {
  const writes = new vscode.EventEmitter(), closes = new vscode.EventEmitter(), cancel = cancellation();
  let process, plan, token, opened = false, finished = false, completing = false;
  const output = text => { if (!finished && text) writes.fire(text.replace(/\r?\n/g, '\r\n')); };

  async function publish() {
    if (!diagnostics.current(token)) return false;
    const files = new Map();
    for (const record of token.records) {
      if (!files.has(record.file)) files.set(record.file, []);
      files.get(record.file).push(record);
    }
    const entries = [];
    for (const [file, records] of files) {
      // File-level records (including output errors/success) never read the file.
      const source = records.some(record => record.row > 0 && record.column > 0) ? await readSourceText(file, cancel) : undefined;
      if (!diagnostics.current(token)) return false;
      entries.push([vscode.Uri.file(file), records.map(record => {
        const position = source === undefined ? { line: Math.max(0, record.row - 1), character: 0 } : sourcePosition(source, record.row, record.column);
        const severity = record.severity === 'ERR' ? vscode.DiagnosticSeverity.Error : record.severity === 'WARN' ?
          vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Information;
        const diagnostic = new vscode.Diagnostic(new vscode.Range(position.line, position.character, position.line, position.character), record.message, severity);
        diagnostic.source = 'vasbuild';
        return diagnostic;
      })]);
    }
    await plan.checkFresh();
    return diagnostics.publish(token, entries);
  }

  function close(code) {
    if (finished) return;
    finished = true;
    done(code);
    closes.fire(code);
    writes.dispose(); closes.dispose();
  }

  async function complete(code, report) {
    if (finished || completing) return;
    completing = true;
    try {
      let current = token && diagnostics.current(token) && !cancel.cancelled;
      if (plan && current) {
        try { await plan.checkFresh(); }
        catch (error) { current = false; diagnostics.cancel(token); output(`VAS: ${error.message}\n`); }
      }
      if (token && current && !report?.error) {
        if (!(await publish())) { current = false; output('VAS: Build diagnostics discarded because inputs changed or a newer build started.\n'); }
        if (token.truncated) output('VAS: Problems limit reached; remaining diagnostics are in the terminal.\n');
      } else if (token && diagnostics.current(token)) diagnostics.cancel(token);
      current = current && diagnostics.current(token) && !cancel.cancelled && !finished;
      // Commit complete observations only after all publication/freshness awaits.
      if (plan && report) dependencies.update(plan, report,
        Boolean(current && !report.error && report.result?.dependenciesComplete && code !== 130));
      if (code === 0 && !current) code = 1;
      if (code === 0) output(`VAS: Built project unit ${plan.unit}.\n`);
    } catch (error) {
      if (token) diagnostics.cancel(token);
      if (plan && report) { try { dependencies.update(plan, report, false); } catch { /* Preserve failure and still close the task. */ } }
      output(`VAS: ${error.message}\n`);
      code = 1;
    }
    close(cancel.cancelled ? 130 : code);
  }

  return {
    onDidWrite: writes.event, onDidClose: closes.event,
    open() {
      if (opened || finished) return;
      opened = true;
      const revision = diagnostics.revision;
      void (async () => {
        plan = await prepare(cancel);
        if (cancel.cancelled || finished) return;
        if (diagnostics.revision !== revision) throw new Error('Project inputs changed during preparation. Build Project again.');
        token = diagnostics.begin(plan.key);
        dependencies.update(plan, { observed: new Map() }, false);
        await plan.checkFresh();
        if (cancel.cancelled || finished) return;
        if (!diagnostics.current(token)) throw new Error('Project inputs changed before compiler launch. Build Project again.');
        process = createProcess(plan, { output, diagnostic: record => diagnostics.add(token, record),
          observe: item => dependencies.observe(plan, item), complete });
        process.start();
      })().catch(error => { output(`VAS: ${error.message}\n`); void complete(1); });
    },
    close() {
      if (finished || cancel.cancelled) return;
      cancel.cancel();
      if (token) diagnostics.cancel(token);
      if (process) {
        process.terminate();
        try { dependencies.update(plan, process.report, false); } catch (error) { output(`VAS: ${error.message}\n`); }
      }
      // Do not wait on an in-flight descriptor or source-position read to close UI.
      close(130);
    },
    handleInput(data) { if (data.includes('\x03')) this.close(); },
    dispose() { this.close(); writes.dispose(); closes.dispose(); }
  };
}

module.exports = { createProjectTerminal };
