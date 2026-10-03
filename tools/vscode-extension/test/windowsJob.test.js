'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { spawn } = require('node:child_process');
const { launchWindowsJob } = require('./windowsJob');

const windows = { skip: process.platform !== 'win32' ? 'Requires real Windows Job Object APIs' : false, timeout: 30000 };

function alive(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}

async function recordedPids(directory) {
  const pids = [];
  for (const name of ['leader', 'middle', 'grandchild']) {
    const value = await fs.readFile(path.join(directory, `${name}.pid`), 'utf8').catch(error => {
      if (error.code === 'ENOENT') return ''; throw error;
    });
    if (value) pids.push(Number(value));
  }
  return pids;
}

function familyScript(directory, exitCode, inheritedOutput) {
  const record = name => `setTimeout(() => process.exit(0), 20000).unref();
    require('node:fs').writeFileSync(${JSON.stringify(path.join(directory, `${name}.pid`))}, String(process.pid));`;
  const grandchild = `${record('grandchild')} setInterval(() => {}, 1000);`;
  const stdio = inheritedOutput ? ['ignore', 'inherit', 'inherit'] : 'ignore';
  const finish = exitCode === null ? '' : `process.exit(${exitCode});`;
  // Windows libuv kills non-detached children when their Node parent exits.
  // Detach both generations so this fixture really leaves live orphans for
  // our outer, non-breakaway Job to clean up (detached is not Job breakaway).
  const spawnOptions = JSON.stringify({ stdio, detached: true, windowsHide: true });
  const middle = `${record('middle')}
    const child = require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(grandchild)}], ${spawnOptions});
    child.unref();
    setInterval(() => {}, 1000);
  `;
  return `${record('leader')}
    const child = require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(middle)}], ${spawnOptions});
    child.unref();
    let ready = false;
    setInterval(() => {
      if (!ready && require('node:fs').existsSync(${JSON.stringify(path.join(directory, 'grandchild.pid'))})) {
        ready = true;
        console.log('VAS_TEST_STAGE:windows:tree-ready');
        ${finish}
      }
    }, 10);
  `;
}

test('Windows family fixture keeps detached descendants alive until outer Job cleanup', () => {
  // Exercise the generated fixture itself, not Windows Job APIs, on every OS.
  // In particular, prevent an unref-only middle from exiting before its child
  // starts, or libuv's own parent-exit cleanup from masquerading as Job cleanup.
  for (const exitCode of [0, 7, null]) for (const inheritedOutput of [false, true]) {
    const directory = path.join(os.tmpdir(), 'virtual-windows-family');
    const files = new Map();
    const stages = [];
    function evaluate(script, pid) {
      const state = { intervals: [], timeouts: [], children: [], exits: [] };
      vm.runInNewContext(script, {
        process: { execPath: process.execPath, pid, exit(code) { state.exits.push(code); } },
        console: { log(stage) { stages.push(stage); } },
        setTimeout(callback, milliseconds) {
          const timer = { callback, milliseconds, unrefed: false,
            unref() { timer.unrefed = true; } };
          state.timeouts.push(timer);
          return timer;
        },
        setInterval(callback, milliseconds) { state.intervals.push({ callback, milliseconds }); },
        require(name) {
          if (name === 'node:fs') return {
            writeFileSync(file, value) { files.set(file, value); },
            existsSync(file) { return files.has(file); }
          };
          assert.equal(name, 'node:child_process');
          return { spawn(executable, args, options) {
            const child = { executable, args, options, unrefed: false,
              unref() { child.unrefed = true; } };
            state.children.push(child);
            return child;
          } };
        }
      });
      assert.equal(state.timeouts.length, 1);
      assert.equal(state.timeouts[0].milliseconds, 20000, 'each descendant still self-expires after a containment failure');
      assert.equal(state.timeouts[0].unrefed, true);
      return state;
    }
    function descendant(parent, pid) {
      assert.equal(parent.children.length, 1);
      const child = parent.children[0];
      assert.equal(child.executable, process.execPath);
      assert.equal(child.args[0], '-e');
      assert.equal(child.options.detached, true, 'libuv must not kill descendants on parent exit');
      assert.equal(child.options.windowsHide, true);
      assert.equal(JSON.stringify(child.options.stdio), JSON.stringify(inheritedOutput ? ['ignore', 'inherit', 'inherit'] : 'ignore'));
      assert.equal(child.unrefed, true, 'a descendant must not hold its leader open');
      return evaluate(child.args[1], pid);
    }
    const leader = evaluate(familyScript(directory, exitCode, inheritedOutput), 100);
    assert.equal(leader.intervals.length, 1);
    leader.intervals[0].callback();
    assert.deepEqual(leader.exits, [], 'leader cannot finish before the entire tree starts');
    const middle = descendant(leader, 101);
    assert.equal(middle.intervals.length, 1, 'middle must stay live until the outer Job stops it');
    leader.intervals[0].callback();
    assert.deepEqual(leader.exits, [], 'spawning a grandchild does not mean it has started');
    const grandchild = descendant(middle, 102);
    assert.equal(grandchild.intervals.length, 1, 'grandchild must stay live until the outer Job stops it');
    assert.equal(files.size, 3);
    leader.intervals[0].callback();
    leader.intervals[0].callback();
    assert.deepEqual(stages, ['VAS_TEST_STAGE:windows:tree-ready']);
    assert.deepEqual(leader.exits, exitCode === null ? [] : [exitCode], 'preserve the exact leader result');
    assert.deepEqual(middle.exits, []);
    assert.deepEqual(grandchild.exits, []);
  }
});

async function withFamily(run) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-windows-family-'));
  const sibling = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  const siblingClosed = new Promise(resolve => sibling.once('close', resolve));
  let quiescent = false;
  try { quiescent = await run(directory, sibling) === true; }
  finally {
    // Recorded PIDs are read-only evidence, never destructive cleanup targets.
    // Fixtures self-expire if containment fails, and their directories remain.
    sibling.kill();
    await siblingClosed;
    if (quiescent) await fs.rm(directory, { recursive: true, force: true });
    else console.error(`Preserving unconfirmed Windows family fixture: ${directory}`);
  }
}

async function assertFamilyStopped(directory, sibling) {
  const pids = await recordedPids(directory);
  assert.equal(pids.length, 3, 'the test must really create a leader, child, and grandchild');
  for (const pid of pids) assert.equal(alive(pid), false, `owned process ${pid} must be stopped before resolution`);
  assert.equal(sibling.exitCode, null, 'an independently spawned sibling must survive');
  assert.equal(sibling.signalCode, null);
}

test('Windows Job cleans orphaned grandchildren after leader exit 0, including inherited output pipes', windows, async () => {
  for (const inheritedOutput of [false, true]) await withFamily(async (directory, sibling) => {
    await launchWindowsJob(process.execPath, ['-e', familyScript(directory, 0, inheritedOutput)], 'windows-orphan', { timeoutMs: 10000 });
    await assertFamilyStopped(directory, sibling);
    return true;
  });
});

test('Windows Job retains a real nonzero leader exit while cleaning descendants', windows, async () => {
  await withFamily(async (directory, sibling) => {
    await assert.rejects(launchWindowsJob(process.execPath, ['-e', familyScript(directory, 7, false)],
      'windows-nonzero', { timeoutMs: 10000 }), error => {
      assert.match(error.message, /exited 7; last stage: windows:tree-ready/);
      assert.notEqual(error.hostCleanupFailed, true, error.message);
      return true;
    });
    await assertFamilyStopped(directory, sibling);
    return true;
  });
});

test('Windows Job deadline stops the entire live tree and leaves an independent sibling alive', windows, async () => {
  await withFamily(async (directory, sibling) => {
    await assert.rejects(launchWindowsJob(process.execPath, ['-e', familyScript(directory, null, false)],
      'windows-timeout', { timeoutMs: 3000 }), error => {
      assert.match(error.message, /timed out after 3000ms; last stage: windows:tree-ready/);
      assert.notEqual(error.hostCleanupFailed, true, error.message);
      return true;
    });
    await assertFamilyStopped(directory, sibling);
    return true;
  });
});

test('Windows Job preserves exact Unicode, empty, quoted, trailing-backslash and shell-metacharacter arguments', windows, async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-windows-arguments-'));
  let quiescent = false;
  try {
    const output = path.join(directory, 'arguments.json');
    const args = ['', '文😀 $&;', 'a"b', 'one\\', 'two\\\\', 'space \\"quote"\\', '$(not a shell)'];
    const script = `require('node:fs').writeFileSync(${JSON.stringify(output)}, JSON.stringify({ args: process.argv.slice(1), request: process.env.VAS_WINDOWS_JOB_REQUEST }));`;
    await launchWindowsJob(process.execPath, ['-e', script, ...args], 'windows-arguments', { timeoutMs: 5000 });
    quiescent = true;
    const actual = JSON.parse(await fs.readFile(output, 'utf8'));
    assert.deepEqual(actual.args, args);
    assert.equal(actual.request, undefined, 'the launched program must not inherit the private result-file request');
  } finally {
    if (quiescent) await fs.rm(directory, { recursive: true, force: true });
    else console.error(`Preserving unconfirmed Windows argument fixture: ${directory}`);
  }
});

test('Windows Job does not mistake legitimate exit code 259 for a running process', windows, async () => {
  await assert.rejects(launchWindowsJob(process.execPath, ['-e', 'process.exit(259)'], 'windows-259', { timeoutMs: 5000 }),
    /exited 259/);
});

test('Windows Job startup failure is explicit and its empty owned job is verified quiescent', windows, async () => {
  await assert.rejects(launchWindowsJob(path.join(os.tmpdir(), 'vas-no-such-native-host.exe'), [],
    'windows-create-failure', { timeoutMs: 5000 }), error => {
    assert.match(error.message, /failed to launch: .*CreateProcessW/);
    assert.notEqual(error.hostCleanupFailed, true, error.message);
    return true;
  });
});

test('Windows Job supervisor crash fails closed without a quiescence certificate', windows, async () => {
  await withFamily(async (directory, sibling) => {
    await assert.rejects(launchWindowsJob(process.execPath, ['-e', familyScript(directory, null, false)],
      'windows-supervisor-crash', { timeoutMs: 10000, onSupervisorSpawn(supervisor) {
        let output = '', killed = false;
        supervisor.stdout.on('data', chunk => {
          output += chunk;
          if (!killed && output.includes('VAS_TEST_STAGE:windows:tree-ready')) {
            killed = true;
            supervisor.kill();
          }
        });
      } }), error => {
      assert.match(error.message, /without a valid cleanup certificate/);
      assert.equal(error.hostCleanupFailed, true, 'the caller must preserve fixtures when cleanup cannot be proven');
      return true;
    });
    const deadline = Date.now() + 5000;
    while ((await recordedPids(directory)).some(alive) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    await assertFamilyStopped(directory, sibling);
    // Deliberately do not return true: native cleanup has no certificate even
    // though the test independently observes KILL_ON_JOB_CLOSE taking effect.
    assert.equal(sibling.exitCode, null);
    assert.equal(sibling.signalCode, null);
  });
});
