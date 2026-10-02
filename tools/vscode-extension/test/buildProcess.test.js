'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { BuildProcess } = require('../src/buildProcess');
const { sourcePosition } = require('../src/diagnostics');

function run(plan, spawn) {
  const records = [], output = [];
  let controller;
  const ended = new Promise(resolve => {
    controller = new BuildProcess(plan, { diagnostic: record => records.push(record), output: text => output.push(text), complete: resolve }, spawn);
    controller.start();
  });
  return { controller, records, output, ended };
}

test('real child pipes preserve separate UTF-8 streams, arguments and non-zero status', async () => {
  const file = path.join(os.tmpdir(), 'VAS project 工程 文😀 with spaces', 'shared 文😀.vas');
  const line = `${file} (1, 30) : ERR  : error 文😀`;
  const script = `const b = Buffer.from(${JSON.stringify(line + '\r\n')}); for (const byte of b) process.stderr.write(Buffer.from([byte])); process.stdout.write('stdout without newline'); process.exitCode = 7;`;
  const result = run({ executable: process.execPath, args: ['-e', script, 'literal;$(echo nope)'], cwd: os.tmpdir() });
  assert.equal(await result.ended, 7);
  assert.equal(result.records.length, 1);
  assert.equal(result.records[0].file, file);
  assert.equal(result.records[0].message, 'error 文😀');
  assert.match(result.output.join(''), /stdout without newline/);
});

test('spawn errors and signals fail instead of reporting success', async () => {
  const absent = run({ executable: path.join(os.tmpdir(), 'vas-compiler-does-not-exist'), args: [], cwd: os.tmpdir() });
  assert.equal(await absent.ended, 1);
  assert.match(absent.output.join(''), /could not run compiler/);
  const throwing = run({}, () => { throw new Error('spawn denied'); });
  assert.equal(await throwing.ended, 1);
});

test('spawn stays shell-free; exit waits for both pipes and cancellation kills once', async () => {
  const child = new EventEmitter();
  child.stdout = new PassThrough(); child.stderr = new PassThrough();
  let kills = 0, options;
  child.kill = () => { kills++; return true; };
  const plan = { executable: '/sdk/vasbuild', args: ['a;$(evil)', 'b'], cwd: '/project' };
  const result = run(plan, (executable, args, opts) => {
    assert.equal(executable, plan.executable); assert.equal(args, plan.args); options = opts; return child;
  });
  assert.deepEqual(options, { cwd: '/project', shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.emit('exit', 0);
  assert.equal(result.controller.finished, false);
  result.controller.terminate(); result.controller.terminate();
  assert.equal(kills, 1);
  child.stdout.end(); child.stderr.end(); child.emit('close', null, 'SIGTERM');
  assert.equal(await result.ended, 130);
});

test('terminate a live native child and support cancellation before launch', async () => {
  const running = run({ executable: process.execPath, args: ['-e', 'setInterval(() => {}, 1000)'], cwd: os.tmpdir() });
  running.controller.terminate();
  assert.equal(await running.ended, 130);
  let spawned = 0, completed = 0;
  const controller = new BuildProcess({}, { output() {}, diagnostic() {}, complete: code => { assert.equal(code, 130); completed++; } }, () => spawned++);
  controller.terminate(); controller.start(); controller.terminate();
  assert.equal(spawned, 0); assert.equal(completed, 1);
});

test('real vasbuild reports exact Unicode include path and mapped saved-source column', { skip: !process.env.VAS_TEST_COMPILER }, async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-pipe-test-'));
  try {
    const root = path.join(temp, 'VAS project 工程 文😀 with spaces');
    await fs.mkdir(root);
    const source = path.join(root, 'main 文😀.vas'), included = path.join(root, 'shared 文😀.vas');
    const line = '\t/* 文😀 */ int Broken() { return ; }';
    await fs.writeFile(source, '#include "shared 文😀.vas"\nvoid main() {}\n');
    await fs.writeFile(included, line + '\r\n');
    const config = path.join(root, 'config 接口😀.txt');
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'), config);
    const result = run({ executable: process.env.VAS_TEST_COMPILER, args: [config, source, path.join(root, 'out 出力😀.vasbc')], cwd: root });
    assert.notEqual(await result.ended, 0);
    const error = result.records.find(record => record.file === included && record.message === 'Must return a value');
    assert.ok(error, JSON.stringify(result.records));
    assert.deepEqual(sourcePosition(line, error.row, error.column), { line: 0, character: line.indexOf('return') });
    await fs.writeFile(included, 'int Broken() { return 1; }\n');
    const output = path.join(root, 'out 出力😀.vasbc');
    const fixed = run({ executable: process.env.VAS_TEST_COMPILER, args: [config, source, output], cwd: root });
    assert.equal(await fixed.ended, 0);
    assert.equal(fixed.records.length, 0);
    assert.ok((await fs.stat(output)).size > 0);
  } finally { await fs.rm(temp, { recursive: true, force: true }); }
});

test('compiler signal termination and output errors cannot report success', async () => {
  for (const mode of ['signal', 'pipe-error']) {
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
    child.kill = () => true;
    const result = run({ cwd: os.tmpdir() }, () => child);
    if (mode === 'pipe-error') child.stderr.emit('error', new Error('broken pipe'));
    child.stdout.end(); child.stderr.end();
    child.emit('close', mode === 'signal' ? null : 0, mode === 'signal' ? 'SIGTERM' : undefined);
    assert.equal(await result.ended, 1);
  }
});

test('oversized pipe lines warn while following diagnostics still parse', async () => {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  const result = run({ cwd: os.tmpdir() }, () => child);
  child.stderr.end(Buffer.from('x'.repeat(70000) + '\n/file.vas (1, 1) : ERR : after long line\n'));
  await new Promise(resolve => setImmediate(resolve));
  child.stdout.end(); child.emit('close', 1);
  assert.equal(await result.ended, 1);
  assert.match(result.output.join(''), /Oversized diagnostic line omitted/);
  assert.equal(result.records.length, 1);
});

test('compiler ignoring SIGTERM is forcibly stopped', { skip: process.platform === 'win32', timeout: 10000 }, async () => {
  let ready;
  const started = new Promise(resolve => { ready = resolve; });
  let controller;
  const ended = new Promise(resolve => {
    controller = new BuildProcess({ executable: process.execPath,
      args: ['-e', "process.on('SIGTERM', () => {}); console.log('ready'); setInterval(() => {}, 1000)"], cwd: os.tmpdir() },
    { output: text => { if (text.includes('ready')) ready(); }, diagnostic() {}, complete: resolve });
  });
  controller.start();
  await started;
  controller.terminate();
  assert.equal(await ended, 130);
});
