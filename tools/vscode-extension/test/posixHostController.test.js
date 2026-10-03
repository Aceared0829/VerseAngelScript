'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');

const group = 12345;
const denied = () => Object.assign(new Error('kill EPERM'), { code: 'EPERM' });

async function controller({ kill = () => { throw denied(); }, output = () => `${group + 1} ${group} Z\n`,
  psExit = 0, now = Date.now } = {}) {
  // Deterministic controller coverage on every OS. Actual Darwin/Linux process
  // behavior remains covered by hostRunner.test.js, not simulated by this VM.
  const signals = [], inspections = [];
  const context = {
    module: { exports: {} }, __dirname,
    process: { kill(pid, signal) { signals.push({ pid, signal }); return kill(pid, signal); } },
    Date: { now }, setTimeout, clearTimeout,
    require(name) {
      if (name !== 'node:child_process') return require(name);
      return { spawn(command, args, options) {
        inspections.push({ command, args: Array.from(args), shell: options.shell });
        const reader = new EventEmitter();
        reader.stdout = new PassThrough();
        reader.stderr = new PassThrough();
        reader.kill = () => true;
        queueMicrotask(() => {
          reader.stdout.end(output());
          reader.stderr.end(psExit ? 'inspection failed' : '');
          reader.emit('close', psExit);
        });
        return reader;
      } };
    }
  };
  vm.runInNewContext(await fs.readFile(path.join(__dirname, 'runIntegration.js'), 'utf8'), context,
    { filename: 'runIntegration.js' });
  return { ...context.module.exports, signals, inspections };
}

test('POSIX group probe EPERM requires positive zombie/dead-only membership evidence', async () => {
  const harness = await controller({ output: () => `${group + 1} ${group} Z+\n${group + 2} ${group} X\n` });
  assert.equal((await harness.activeGroupMembers(group)).length, 0);
  assert.deepEqual(harness.inspections, [{ command: 'ps', args: ['-o', 'pid=,pgid=,stat=', '-g', String(group)], shell: false }]);
  assert.deepEqual(harness.signals, [{ pid: -group, signal: 0 }]);
});

test('POSIX TERM EPERM succeeds only after exact group quiescence is established', async () => {
  const harness = await controller();
  await harness.terminateHost({ pid: group }, Promise.resolve());
  assert.deepEqual(harness.signals.map(call => call.signal), ['SIGTERM', 0, 0]);
  assert.ok(harness.signals.every(call => call.pid === -group), 'never broaden cleanup beyond the owned group');
});

test('POSIX post-KILL EPERM probe still inspects zombie-only members', async () => {
  let killed = false, clock = 0;
  const harness = await controller({
    kill(pid, signal) {
      if (signal === 'SIGKILL') killed = true;
      else if (signal === 0 && killed) throw denied();
    },
    output: () => `${group + 1} ${group} ${killed ? 'Z' : 'S'}\n`,
    now: () => { clock += 6000; return clock; }
  });
  await harness.terminateHost({ pid: group }, Promise.resolve());
  assert.deepEqual(harness.signals.map(call => call.signal), ['SIGTERM', 0, 'SIGKILL', 0]);
  assert.equal(harness.inspections.length, 2);
});

test('POSIX KILL EPERM requires fresh proof even after the TERM grace expired', async () => {
  let killed = false, clock = 0;
  const harness = await controller({
    kill(pid, signal) {
      if (signal === 'SIGKILL') killed = true;
      if (killed) throw denied();
    },
    output: () => `${group + 1} ${group} ${killed ? 'Z' : 'S'}\n`,
    now: () => { clock += 6000; return clock; }
  });
  await harness.terminateHost({ pid: group }, Promise.resolve());
  assert.deepEqual(harness.signals.map(call => call.signal), ['SIGTERM', 0, 'SIGKILL', 0, 0]);
  assert.equal(harness.inspections.length, 3);
});

test('POSIX empty inspection accepts disappearance only when a fresh probe returns ESRCH', async () => {
  let probes = 0;
  const harness = await controller({ output: () => '', kill(pid, signal) {
    assert.equal(signal, 0);
    if (++probes === 1) throw denied();
    throw Object.assign(new Error('gone'), { code: 'ESRCH' });
  } });
  assert.equal((await harness.activeGroupMembers(group)).length, 0);
  assert.equal(probes, 2);
});

test('POSIX signal EPERM remains a failure when any owned member is live', async () => {
  const harness = await controller({ output: () => `${group + 1} ${group} Z\n${group + 2} ${group} S\n` });
  await assert.rejects(harness.terminateHost({ pid: group }, Promise.resolve()), { code: 'EPERM' });
  assert.deepEqual(harness.signals.map(call => call.signal), ['SIGTERM', 0]);
});

test('POSIX EPERM cannot treat empty, foreign, malformed or failed inspection as quiescence', async () => {
  for (const options of [
    { output: () => '' },
    { output: () => `${group + 1} ${group + 1} Z\n` },
    { output: () => 'unparseable process state\n' },
    { psExit: 2 }
  ]) {
    const harness = await controller(options);
    await assert.rejects(harness.terminateHost({ pid: group }, Promise.resolve()), /Cannot (establish|parse|inspect)/);
  }
});

test('POSIX ESRCH confirms disappearance while other signal/probe errors remain failures', async () => {
  const gone = await controller({ kill: () => { throw Object.assign(new Error('gone'), { code: 'ESRCH' }); } });
  await gone.terminateHost({ pid: group }, Promise.resolve());
  assert.equal(gone.inspections.length, 0);
  const broken = await controller({ kill: () => { throw Object.assign(new Error('bad signal'), { code: 'EINVAL' }); } });
  await assert.rejects(broken.activeGroupMembers(group), { code: 'EINVAL' });
  await assert.rejects(broken.terminateHost({ pid: group }, Promise.resolve()), { code: 'EINVAL' });
  assert.equal(broken.inspections.length, 0);
});
