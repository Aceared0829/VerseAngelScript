'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');

test('Windows controller watchdog rejects, preserves fixtures, and releases owned handles when close is withheld', async () => {
  // Exercise only the JavaScript controller on every OS. This does not emulate
  // or claim coverage of native Windows Job Object APIs.
  const child = new EventEmitter();
  child.pid = 12345;
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  let kills = 0, unrefs = 0, removals = 0;
  child.kill = () => { kills++; return true; };
  child.unref = () => { unrefs++; };
  const fakeFs = {
    mkdtemp: async () => '/virtual/owned-job-result',
    readFile: async () => { throw new Error('No result or close event is supplied'); },
    rm: async () => { removals++; }
  };
  const context = {
    module: { exports: {} }, __dirname,
    process: { platform: 'win32', env: {}, stdout: { write() {} }, stderr: { write() {} } },
    require(name) {
      if (name === 'node:child_process') return { spawn: () => child };
      if (name === 'node:fs/promises') return fakeFs;
      return require(name);
    },
    // Accelerate the controller's two explicit timers, without real processes.
    setTimeout: (callback, milliseconds) => setTimeout(callback, Math.min(milliseconds, 10)),
    clearTimeout
  };
  vm.runInNewContext(await fs.readFile(path.join(__dirname, 'windowsJob.js'), 'utf8'), context,
    { filename: 'windowsJob.js' });
  await assert.rejects(context.module.exports.launchWindowsJob('test-only.exe', [], 'withheld-close', { timeoutMs: 1000 }), error => {
    assert.match(error.message, /supervisor exceeded 41000ms/);
    assert.equal(error.hostCleanupFailed, true);
    return true;
  });
  assert.equal(kills, 1, 'only the retained supervisor handle is terminated');
  assert.equal(unrefs, 1, 'a missing close event cannot retain the supervisor handle');
  assert.equal(child.stdout.destroyed, true);
  assert.equal(child.stderr.destroyed, true);
  assert.equal(removals, 0, 'unconfirmed cleanup must preserve the result/fixture directory');
});
