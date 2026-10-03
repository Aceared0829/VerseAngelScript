'use strict';

const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

// The helper owns the native-host deadline. This independent watchdog also
// bounds PowerShell startup/compilation and cleanup, without claiming that a
// killed supervisor proves that all its descendants have exited.
const wrapperAllowanceMs = 40000;
const wrapperCloseTimeoutMs = 5000;

async function launchWindowsJob(executable, args, mode, { timeoutMs = 180000, onSupervisorSpawn } = {}) {
  if (process.platform !== 'win32') throw new Error('Windows Job Object launcher requires Windows');
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 0x7fffffff - wrapperAllowanceMs) throw new Error('Invalid Windows host timeout');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-windows-job-'));
  const resultFile = path.join(directory, 'result.json');
  let preserve = false;
  try {
    await new Promise((resolve, reject) => {
      // Use the normal script-file policy. Restricted/AllSigned hosts must fail
      // visibly rather than bypass their policy with command text or flags.
      const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', path.join(__dirname, 'windowsJob.ps1')], {
        shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, VAS_TEST_MODE: mode,
          VAS_WINDOWS_JOB_REQUEST: JSON.stringify({ executable, args, timeoutMs, resultFile }) }
      });
      let finished = false, exited = false, lastStage = '(no test stage received)', closeTimer, watchdogError;
      const failure = (reason, quiescent = false) => {
        const error = new Error(`VS Code ${mode} tests ${reason}; last stage: ${lastStage}`);
        if (!quiescent) { error.hostCleanupFailed = true; preserve = true; }
        return error;
      };
      const complete = error => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        clearTimeout(closeTimer);
        if (watchdogError || error) reject(watchdogError || error); else resolve();
      };
      for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
        stream.setEncoding('utf8');
        let pending = '';
        const record = line => {
          const marker = line.indexOf('VAS_TEST_STAGE:');
          if (marker !== -1) lastStage = line.slice(marker + 'VAS_TEST_STAGE:'.length).trim();
        };
        stream.on('data', chunk => {
          destination.write(chunk);
          const lines = (pending + chunk).split(/\r?\n/);
          pending = lines.pop();
          for (const line of lines) record(line);
          record(pending);
          pending = pending.slice(-4096);
        });
      }
      const stopUnconfirmed = error => {
        watchdogError = error;
        clearTimeout(timer);
        // libuv terminates this exact ChildProcess through its retained handle.
        // The private, non-inherited KILL_ON_JOB_CLOSE handle contains its tree.
        // A missing result certificate still fails closed and retains fixtures.
        if (!exited) child.kill();
        closeTimer = setTimeout(() => {
          // A missing close event must not keep Node alive past the watchdog.
          // Detach only this supervisor's owned handles; cleanup stays unproven.
          child.stdout.destroy();
          child.stderr.destroy();
          child.unref();
          complete(watchdogError);
        }, wrapperCloseTimeoutMs);
      };
      const timer = setTimeout(() => {
        stopUnconfirmed(failure(`Windows Job supervisor exceeded ${timeoutMs + wrapperAllowanceMs}ms`));
      }, timeoutMs + wrapperAllowanceMs);
      child.once('error', error => complete(failure(`failed to launch Windows Job supervisor: ${error.message}`, !child.pid)));
      child.once('exit', () => { exited = true; });
      child.once('close', async (code, signal) => {
        if (finished) return;
        if (watchdogError) { complete(watchdogError); return; }
        try {
          const result = JSON.parse(await fs.readFile(resultFile, 'utf8'));
          if (result.Quiescent !== true) throw failure(`Windows Job cleanup is unconfirmed: ${result.Error || 'missing ActiveProcesses==0 proof'}`);
          if (result.TimedOut === true) throw failure(`timed out after ${timeoutMs}ms`, true);
          if (result.Error) throw failure(`${result.Launched ? 'failed' : 'failed to launch'}: ${result.Error}`, true);
          if (result.LeaderExited !== true || !Number.isInteger(result.LeaderExitCode) ||
              !Number.isInteger(result.ExitCode) || (result.ExitCode >>> 0) !== (result.LeaderExitCode >>> 0)) {
            throw failure('Windows Job supervisor did not report a real leader exit', true);
          }
          if (code !== 0 || result.LeaderExitCode !== 0) throw failure(`exited ${result.LeaderExitCode || code || signal}`, true);
          complete();
        } catch (error) {
          complete(error.message?.startsWith(`VS Code ${mode} tests `) ? error :
            failure(`Windows Job supervisor exited ${code ?? signal} without a valid cleanup certificate: ${error.message}`));
        }
      });
      // Test-only crash injection uses the retained ChildProcess handle, never
      // a recorded numeric PID that may have been reused after leader exit.
      if (onSupervisorSpawn) {
        try { onSupervisorSpawn(child); }
        catch (error) { stopUnconfirmed(failure(`supervisor observer failed: ${error.message}`)); }
      }
    });
  } finally {
    if (!preserve) await fs.rm(directory, { recursive: true, force: true });
  }
}

module.exports = { launchWindowsJob };
