'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs/promises');
const { promisify } = require('node:util');
const { spawn, execFile } = require('node:child_process');
const { launch } = require('./runIntegration');

const execute = promisify(execFile);

async function processIsActive(pid) {
  try {
    const { stdout } = await execute('ps', ['-o', 'stat=', '-p', String(pid)], { timeout: 5000 });
    return stdout.trim().split('\n').some(state => state.trim() && !/^[ZX]/.test(state.trim()));
  } catch (error) {
    if (error.code === 1 && !error.stdout.trim()) return false;
    throw error;
  }
}

test('host launcher accepts only a real zero exit and reports nonzero with the last stage', async () => {
  await launch(process.execPath, ['-e', 'console.log("VAS_TEST_STAGE:unit:normal-close")'], 'unit-success', { timeoutMs: 5000 });
  await assert.rejects(launch(process.execPath, ['-e', 'console.log("VAS_TEST_STAGE:unit:explicit-failure"); process.exitCode = 7;'],
    'unit-failure', { timeoutMs: 5000 }), /exited 7; last stage: unit:explicit-failure/);
});

test('host launcher fails a deadline, cleans its own child, and leaves a sibling process alive', { timeout: 20000 }, async () => {
  const sibling = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  const closed = new Promise(resolve => sibling.once('close', resolve));
  try {
    const start = Date.now();
    await assert.rejects(launch(process.execPath, ['-e', 'console.log("VAS_TEST_STAGE:unit:deadline-ready"); setInterval(() => {}, 1000)'],
      'unit-deadline', { timeoutMs: 1000 }), error => {
      assert.match(error.message, /timed out after 1000ms; last stage: unit:deadline-ready/);
      assert.notEqual(error.hostCleanupFailed, true, error.message);
      return true;
    });
    assert.ok(Date.now() - start < 15000, 'deadline plus owned-child cleanup must be bounded');
    assert.equal(sibling.exitCode, null, 'cleanup must not target sibling or process-name matches');
    assert.equal(sibling.signalCode, null);
  } finally {
    sibling.kill();
    await closed;
  }
});

test('host launcher reports executable startup failure without waiting for its deadline', async () => {
  await assert.rejects(launch(path.join(os.tmpdir(), 'vas-no-such-host-executable'), [], 'unit-spawn-failure', { timeoutMs: 5000 }),
    /failed to launch/);
});

test('successful host exit cleans an ignored-stdio unref descendant before resolving', {
  timeout: 20000,
  skip: process.platform === 'win32' ? 'POSIX owned-process-group regression; Windows needs verified process-tree containment' : false
}, async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-host-descendant-'));
  const ready = path.join(directory, 'descendant-ready.json');
  const descendantCode = `
    const fs = require('node:fs');
    process.on('SIGTERM', () => {});
    setInterval(() => {}, 1000);
    setTimeout(() => process.exit(0), 15000);
    fs.writeFileSync(${JSON.stringify(ready)}, JSON.stringify({ pid: process.pid }));
  `;
  const leaderCode = `
    const fs = require('node:fs');
    const { spawn } = require('node:child_process');
    const descendant = spawn(process.execPath, ['-e', ${JSON.stringify(descendantCode)}], { stdio: 'ignore' });
    descendant.unref();
    setInterval(() => {
      if (fs.existsSync(${JSON.stringify(ready)})) {
        console.log('VAS_TEST_STAGE:unit:leader-exit-with-live-descendant');
        process.exit(0);
      }
    }, 10);
  `;
  const sibling = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  const siblingClosed = new Promise(resolve => sibling.once('close', resolve));
  let quiescent = false;
  try {
    const start = Date.now();
    await launch(process.execPath, ['-e', leaderCode], 'unit-owned-descendant', { timeoutMs: 5000 });
    const descendant = JSON.parse(await fs.readFile(ready, 'utf8')).pid;
    assert.equal(await processIsActive(descendant), false, 'exit 0 must not leave a live descendant behind');
    quiescent = true;
    assert.ok(Date.now() - start < 15000, 'SIGTERM-resistant descendants must be killed within the bounded grace period');
    assert.equal(sibling.exitCode, null, 'success cleanup must leave unrelated sibling processes alone');
    assert.equal(sibling.signalCode, null);
  } finally {
    // Never kill a recorded numeric PID after its original owner has exited;
    // it could have been reused. The fixture self-expires if cleanup regresses.
    sibling.kill();
    await siblingClosed;
    if (quiescent) await fs.rm(directory, { recursive: true, force: true });
    else console.error(`Preserving unconfirmed descendant fixture: ${directory}`);
  }
});
