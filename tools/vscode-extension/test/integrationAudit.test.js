'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');

async function audit() {
  const compiler = path.resolve('unit-audit-compiler');
  const children = [];
  const childProcess = { spawn() {
    const child = new EventEmitter(); child.stdout = new PassThrough(); children.push(child); return child;
  } };
  childProcess.execFile = childProcess.spawn;
  const vscode = { CustomExecution: class {}, workspace: { createFileSystemWatcher() {} } };
  class Observations { register() {} changed() {} invalidate() {} }
  class Diagnostics { publish() { return true; } }
  const context = { module: { exports: {} }, process: { env: { VAS_TEST_COMPILER: compiler } }, console,
    require(name) {
      if (name === 'vscode') return vscode;
      if (name === 'node:child_process') return childProcess;
      if (name === '../src/projectObservations') return { ProjectInputObservations: Observations };
      if (name === '../src/diagnostics') return { BuildDiagnostics: Diagnostics };
      return require(name);
    }
  };
  const source = await fs.readFile(path.join(__dirname, 'integration.js'), 'utf8');
  vm.runInNewContext(source + '\nmodule.exports.calls = processCalls;', context, { filename: 'integration.js' });
  return { compiler, childProcess, children, calls: context.module.exports.calls };
}

test('integration wire audit preserves child, exact chunk bytes and split Unicode while recording native close order', async () => {
  const state = await audit();
  const child = state.childProcess.spawn(state.compiler, ['--report=jsonl'], { shell: false });
  assert.equal(child, state.children[0]);
  const received = [];
  child.stdout.on('data', bytes => received.push(Buffer.from(bytes)));
  const bytes = Buffer.from(JSON.stringify({ type: 'diagnostic', message: 'warning 文😀', section: 'exact/文😀.vas' }) + '\n');
  const cut = bytes.indexOf(Buffer.from('😀')) + 1;
  child.stdout.write(bytes.subarray(0, cut)); child.stdout.write(bytes.subarray(cut)); child.stdout.end();
  child.emit('close', 7, null);
  assert.deepEqual(Buffer.concat(received), bytes, 'audit must not substitute the production pipe contents');
  const call = state.calls[0];
  assert.equal(call.report[0].message, 'warning 文😀');
  assert.equal(call.report[0].section, 'exact/文😀.vas');
  assert.equal(call.nativeClose.code, 7);
  assert.ok(call.order < call.report[0].order && call.report[0].order < call.nativeClose.order);
});

test('integration wire audit has byte/record bounds without truncating the actual process stream', async () => {
  for (const bytes of [Buffer.alloc(128 * 1024 + 1, 'x'), Buffer.from(Array.from({ length: 60 }, () => '{"type":"unit-audit"}\n').join(''))]) {
    const state = await audit(), child = state.childProcess.spawn(state.compiler, ['--report=jsonl']);
    const received = []; child.stdout.on('data', chunk => received.push(Buffer.from(chunk)));
    child.stdout.end(bytes);
    assert.deepEqual(Buffer.concat(received), bytes);
    assert.equal(state.calls[0].reportTruncated, true);
    assert.ok(state.calls[0].report.length <= 48);
  }
});

test('integration wire audit does not subscribe to unrelated processes or descriptors', async () => {
  const state = await audit();
  for (const [executable, args] of [[state.compiler, ['--describe-project=json']], [path.resolve('other-tool'), ['--report=jsonl']]]) {
    const child = state.childProcess.spawn(executable, args);
    assert.equal(child.stdout.listenerCount('data'), 0);
    assert.equal(state.calls.at(-1).report, undefined);
    child.stdout.end();
  }
});
