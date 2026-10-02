'use strict';

const { spawn } = require('node:child_process');
const { DiagnosticStream, parseDiagnostic } = require('./diagnostics');

/** Non-interactive compiler process; no shell and no pseudo-console. */
class BuildProcess {
  constructor(plan, { output, diagnostic, complete }, spawnProcess = spawn) {
    Object.assign(this, { plan, output, diagnostic, complete, spawnProcess });
    this.started = false;
    this.finished = false;
    this.cancelled = false;
  }
  start() {
    if (this.started || this.finished) return;
    this.started = true;
    if (this.cancelled) return this.finish(130);
    try {
      this.child = this.spawnProcess(this.plan.executable, this.plan.args, {
        cwd: this.plan.cwd, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
      });
      for (const pipe of [this.child.stdout, this.child.stderr]) {
        const decoder = new DiagnosticStream(line => {
          const record = parseDiagnostic(line, this.plan.cwd);
          if (record && !this.cancelled) this.diagnostic(record);
        });
        pipe.on('data', chunk => this.output(decoder.write(chunk)));
        pipe.once('end', () => {
          this.output(decoder.end());
          if (decoder.truncated) this.output('\nVAS: Oversized diagnostic line omitted from Problems; see terminal output.\n');
        });
        pipe.on('error', error => { this.failed = true; this.output(`\nVAS output error: ${error.message}\n`); });
      }
      // close, unlike exit, waits for both pipes to drain. Failed spawn also closes.
      this.child.once('error', error => { this.failed = true; this.output(`\nVAS could not run compiler: ${error.message}\n`); });
      this.child.once('close', code => this.finish(this.cancelled ? 130 :
        (Number.isInteger(code) && code >= 0 && !(code === 0 && this.failed) ? code : 1)));
    } catch (error) {
      this.output(`VAS could not run compiler: ${error.message}\n`);
      this.finish(1);
    }
  }
  finish(code) {
    if (this.finished) return;
    this.finished = true;
    clearTimeout(this.killTimer);
    this.complete(code);
  }
  terminate() {
    if (this.finished || this.cancelled) return;
    this.cancelled = true;
    if (!this.child) return this.finish(130);
    this.child.kill();
    // vasbuild has no child processes. Escalate if a compiler ignores SIGTERM.
    this.killTimer = setTimeout(() => { if (!this.finished) this.child.kill('SIGKILL'); }, 2000);
    this.killTimer.unref();
  }
}

module.exports = { BuildProcess };
