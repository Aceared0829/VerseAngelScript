'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { ProjectReport, ProjectBuildProcess, ProjectDependencies, diagnosticRecord, identity } = require('../src/projectReport');
const root = path.join(os.tmpdir(), 'Project 文😀;$(trap)');
const plan = { executable: '/sdk/vasbuild', cwd: root, project: path.join(root, 'vas-project.json'), unit: 'main',
  source: path.join(root, 'main.vas'), config: path.join(root, 'config.txt'), output: path.join(root, 'out/main.vasbc'),
  projectSchemaVersion: 1, legacyProject: false, key: JSON.stringify([root, 'main']), args: [] };
const base = (type, seq, value) => ({ protocol: 'vasbuild', version: 1, type, seq, invalidUtf8Fields: [], rawBytes: {}, ...value });
const start = () => base('start', 1, { compiler: 'vasbuild', compilerVersion: 'test', positionEncoding: 'utf-8-bytes', positionBase: 1,
  cwd: plan.cwd, project: plan.project, unit: plan.unit, entry: plan.source, config: plan.config, output: plan.output,
  projectSchemaVersion: 1, legacyProject: false });
const result = (seq, success = true, dependenciesComplete = true) => base('result', seq, { success, phase: 'output', dependenciesComplete });
const line = record => Buffer.from(JSON.stringify(record) + '\n');
function feed(records, code = 0) { const report = new ProjectReport(plan); for (const record of records) report.write(line(record)); return { report, code: report.end(code) }; }

test('strict report accepts split UTF-8 framing, actual include context and exit agreement', () => {
  const observed = [];
  const report = new ProjectReport(plan, event => observed.push(event.type));
  const data = Buffer.concat([start(), base('section_loaded', 2, { section: plan.source, utf8Valid: true }),
    base('include_attempt', 3, { from: plan.source, requested: 'shared 文😀.vas', resolved: path.join(root, 'shared 文😀.vas') }),
    base('include_result', 4, { attemptSeq: 3, status: 'loaded' }), result(5)].map(line));
  for (const byte of data) report.write(Buffer.from([byte]));
  assert.equal(report.end(0), 0);
  assert.equal(report.observed.size, 2);
  assert.deepEqual(observed, ['start', 'section_loaded', 'include_attempt', 'include_result', 'result']);
  assert.equal(feed([start(), result(2, false)], 7).code, 7);
});

test('unsupported versions, malformed/framing/truncation, wrong identities and exit mismatches fail closed', () => {
  const variants = [
    [start(), { ...result(2), version: 2 }], [start(), result(3)], [start()], [result(1)],
    [start(), result(2), result(3)], [start(), start(), result(3)], [{ ...start(), unit: 'other' }, result(2)],
    [start(), base('include_result', 2, { attemptSeq: 1, status: 'loaded' }), result(3)],
    [start(), base('include_attempt', 2, { from: plan.source, requested: 'x', resolved: null }), result(3)],
    [start(), { ...result(2), success: 'true' }], [start(), { ...result(2), dependenciesComplete: false }],
    [start(), { ...result(2), invalidUtf8Fields: ['unit'], rawBytes: {} }],
    [{ ...start(), project: plan.project.toUpperCase() }, result(2)],
    [start(), base('unknown', 2, {}), result(3)]
  ];
  for (const records of variants) assert.equal(feed(records).code, 1, JSON.stringify(records));
  assert.equal(feed([start(), result(2)], 3).code, 1);
  assert.equal(feed([start(), result(2, false)], 0).code, 1);
  assert.equal(feed([start(), result(2)], null).code, 1);
  const valid = Buffer.concat([line(start()), line(result(2))]);
  for (const data of [valid.subarray(0, -1), Buffer.concat([Buffer.from('\ufeff'), valid]), Buffer.from('\n'),
    Buffer.concat([valid, Buffer.from('x')]), Buffer.from('{nope}\n'), Buffer.from([0xff]), Buffer.from('x'.repeat(1024 * 1024 + 1))]) {
    const report = new ProjectReport(plan); report.write(data); assert.equal(report.end(0), 1);
  }
});

test('raw byte identities remain distinct/non-bindable, section case and literal replacement remain exact', () => {
  const report = new ProjectReport(plan); report.write(line(start()));
  const loaded = (seq, raw) => base('section_loaded', seq, { section: '/display�.vas', utf8Valid: true,
    invalidUtf8Fields: ['section'], rawBytes: { section: raw } });
  assert.notEqual(identity(loaded(2, 'ff'), 'section').key, identity(loaded(3, 'fe'), 'section').key);
  report.write(line(base('section_loaded', 2, { section: '/display�.vas', utf8Valid: true })));
  report.write(line(base('section_loaded', 3, { section: '/Display�.vas', utf8Valid: true })));
  assert.equal(report.observed.size, 2);
  assert.equal(identity(loaded(2, 'ff'), 'section').file, undefined);
  const diagnostic = base('diagnostic', 6, { severity: 'error', section: '/display�.vas', row: 1, column: 1, message: 'bad',
    invalidUtf8Fields: ['section'], rawBytes: { section: 'ff' } });
  assert.equal(diagnosticRecord(diagnostic, report), undefined);
});

test('config/file level, UTF-8 source validity and empty engine sections retain honest positions', () => {
  const report = new ProjectReport(plan); report.write(line(start()));
  const diagnostic = (section, row, column) => base('diagnostic', 2, { severity: 'warning', section, row, column, message: 'message' });
  assert.equal(diagnosticRecord(diagnostic('', 0, 0), report), undefined);
  assert.equal(diagnosticRecord(diagnostic('engine section', 0, 0), report), undefined);
  assert.equal(diagnosticRecord(diagnostic('config.txt', 4, 0), report).column, 0);
  report.write(line(base('section_loaded', 2, { section: plan.source, utf8Valid: false })));
  const invalid = diagnosticRecord(diagnostic(plan.source, 4, 30), report);
  assert.equal(invalid.row, 0); assert.equal(invalid.column, 0);
  // U+FEFF inside a valid JSON string is data, not a framing BOM.
  report.write(line(base('diagnostic', 3, { severity: 'warning', section: '', row: 0, column: 0, message: 'keep\ufeffdata' })));
  report.write(line(result(4)));
  assert.equal(report.end(0), 0);
});

test('complete observations replace; partial/load failure unions and keys separate unit/root/config contexts', () => {
  const deps = new ProjectDependencies();
  const report = files => ({ observed: new Map(files.map(file => [JSON.stringify(['text', file]), { file }])) });
  deps.update(plan, report(['/one.vas', '/two.vas']), true);
  deps.update(plan, report(['/three.vas']), false);
  assert.equal(deps.entries.get(plan.key).observed.size, 3);
  deps.update(plan, report([]), false);
  assert.equal(deps.entries.get(plan.key).observed.size, 3);
  deps.update(plan, report(['/three.vas']), true);
  assert.equal(deps.entries.get(plan.key).observed.size, 1);
  for (const key of [JSON.stringify([plan.project, 'second']), JSON.stringify(['/root2/vas-project.json', 'main'])]) {
    deps.update({ ...plan, key }, report(['/different.vas']), true);
  }
  assert.equal(deps.entries.size, 3);
  assert.ok(deps.relevant(plan.config)); assert.ok(deps.relevant('/three.vas')); assert.equal(deps.relevant('/two.vas'), false);
});

function processFixture() {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  child.kill = () => { child.kills = (child.kills || 0) + 1; return true; };
  const output = [], diagnostics = [];
  let controller, spawnOptions;
  const ended = new Promise(resolve => {
    controller = new ProjectBuildProcess(plan, { output: text => output.push(text), diagnostic: value => diagnostics.push(value), complete: resolve },
      (executable, args, options) => { assert.equal(executable, plan.executable); spawnOptions = options; return child; });
    controller.start();
  });
  return { child, controller, output, diagnostics, ended, spawnOptions };
}

test('pipe project process never uses shell and does not infer success from valid-looking stderr', async () => {
  const f = processFixture();
  assert.equal(f.spawnOptions.shell, false); assert.deepEqual(f.spawnOptions.stdio, ['ignore', 'pipe', 'pipe']);
  f.child.stderr.end(Buffer.concat([line(start()), line(result(2))])); f.child.stdout.end(); f.child.emit('close', 0);
  assert.equal(await f.ended, 1);
});

test('late data, cancellation, signal, transport errors and nonzero after success cannot be green', async () => {
  for (const mode of ['cancel', 'signal', 'pipe', 'exit', 'late']) {
    const f = processFixture();
    f.child.stdout.write(Buffer.concat([line(start()), line(result(2))]));
    if (mode === 'cancel') { f.controller.terminate(); f.controller.terminate(); assert.equal(f.child.kills, 1); }
    if (mode === 'pipe') f.child.stdout.emit('error', new Error('broken'));
    if (mode === 'late') f.child.stdout.write(line(base('diagnostic', 3, { severity: 'error', message: 'late', section: plan.source, row: 1, column: 1 })));
    f.child.stdout.end(); f.child.stderr.end(); f.child.emit('close', mode === 'signal' ? null : mode === 'exit' ? 7 : 0);
    assert.notEqual(await f.ended, 0, mode);
    if (mode === 'late') assert.equal(f.diagnostics.length, 0);
  }
});

test('escaped unpaired surrogates and bounded total/event budgets fail explicitly', () => {
  assert.equal(feed([start(), base('section_loaded', 2, { section: '/bad\ud800.vas', utf8Valid: true }), result(3)]).code, 1);
  const bytes = new ProjectReport(plan); bytes.bytes = 64 * 1024 * 1024; bytes.write(line(start()));
  assert.equal(bytes.end(0), 1); assert.match(bytes.error.message, /total report limit/);
  const events = new ProjectReport(plan); events.sequence = 100000; events.write(line(result(100001)));
  assert.equal(events.end(0), 1); assert.match(events.error.message, /event limit/);
});

test('stale partial run preserves newer configuration inputs and external observations subscribe immediately', () => {
  const watched = [];
  const deps = new ProjectDependencies(files => watched.push(...files));
  const newer = { ...plan, config: '/new/hostB.txt' };
  deps.update(newer, { observed: new Map() }, true);
  deps.update({ ...plan, config: '/old/hostA.txt' }, { observed: new Map() }, false);
  assert.ok(deps.relevant('/new/hostB.txt')); assert.ok(deps.relevant('/old/hostA.txt'));
  deps.observe(plan, { key: '["text","/external/candidate.vas"]', file: '/external/candidate.vas' });
  assert.ok(watched.includes('/external/candidate.vas'));
  assert.ok(deps.relevant('/external/candidate.vas'));
  deps.update(newer, { observed: new Map() }, true);
  assert.equal(deps.relevant('/old/hostA.txt'), false);
});

test('observed include symlink watches physical alias without changing exact compiler section identity', { skip: process.platform === 'win32' }, async () => {
  const fs = require('node:fs/promises');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-observed-alias-'));
  try {
    await fs.mkdir(path.join(root, 'workspace')); await fs.mkdir(path.join(root, 'outside'));
    const lexical = path.join(root, 'workspace', 'link.vas'), physical = path.join(root, 'outside', 'shared.vas');
    await fs.writeFile(physical, 'void main() {}'); await fs.symlink(physical, lexical);
    const watched = [], deps = new ProjectDependencies(files => watched.push(...files));
    const key = JSON.stringify(['text', lexical]);
    await deps.observe(plan, { key, file: lexical });
    assert.ok(watched.includes(physical)); assert.ok(deps.relevant(physical));
    assert.equal(deps.entries.get(plan.key).observed.get(key).file, lexical);
    assert.equal(deps.entries.get(plan.key).observed.size, 1);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('valid-looking complete report followed by transport error is marked invalid for dependency publication', async () => {
  const f = processFixture();
  f.child.stdout.write(Buffer.concat([line(start()), line(result(2))]));
  f.child.stdout.emit('error', new Error('transport broken'));
  f.child.stdout.end(); f.child.stderr.end(); f.child.emit('close', 0);
  assert.equal(await f.ended, 1);
  assert.match(f.controller.report.error.message, /transport broken/);
});

test('participating input registration precedes asynchronous observation and completion waits for it', async () => {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => true;
  const files = [], phases = [];
  let release, completed = false;
  const invocation = { ...plan, observeInput: file => files.push(file) };
  const ended = new Promise(resolve => {
    const process = new ProjectBuildProcess(invocation, { output() {}, diagnostic() {},
      observe: async () => { phases.push('observing'); await new Promise(done => { release = done; }); },
      complete: code => { completed = true; resolve(code); } }, () => child);
    process.start();
  });
  const included = path.join(root, 'shared.vas');
  child.stdout.write(Buffer.concat([line(start()), line(base('section_loaded', 2, { section: included, utf8Valid: true })), line(result(3))]));
  assert.deepEqual(files, [included], 'input membership is synchronous with validated report parsing');
  child.stdout.end(); child.stderr.end(); child.emit('close', 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(phases, ['observing']); assert.equal(completed, false);
  release(); assert.equal(await ended, 0);
});

test('cancellation during asynchronous input observation cannot complete successfully', async () => {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => true;
  const files = [];
  let release, process;
  const ended = new Promise(resolve => {
    process = new ProjectBuildProcess({ ...plan, observeInput: file => files.push(file) }, { output() {}, diagnostic() {},
      observe: () => new Promise(done => { release = done; }), complete: resolve }, () => child);
    process.start();
  });
  const included = path.join(root, 'shared.vas');
  child.stdout.write(Buffer.concat([line(start()), line(base('section_loaded', 2, { section: included, utf8Valid: true })), line(result(3))]));
  await new Promise(resolve => setImmediate(resolve));
  process.terminate(); child.stdout.end(); child.stderr.end(); child.emit('close', 0);
  release(); assert.equal(await ended, 130); assert.deepEqual(files, [included]);
});

test('saved-input validation follows current dependency graph, not retained watch-only aliases', () => {
  const deps = new ProjectDependencies();
  deps.update(plan, { observed: new Map([['old', { file: '/old/include.vas' }]]) }, true);
  deps.entries.get(plan.key).aliases.add('/old/include.txt');
  assert.ok(deps.inputFiles(plan.key).includes('/old/include.vas'));
  deps.update(plan, { observed: new Map([['new', { file: '/new/include.vas' }]]) }, true);
  assert.equal(deps.inputFiles(plan.key).includes('/old/include.vas'), false);
  assert.equal(deps.inputFiles(plan.key).includes('/old/include.txt'), false);
  assert.ok(deps.inputFiles(plan.key).includes('/new/include.vas'));
});


test('an actually loaded raw-byte path fails saved-input verification and retains its lossless partial identity', () => {
  const report = new ProjectReport(plan);
  report.write(line(start()));
  report.write(line(base('section_loaded', 2, { section: '/display�.vas', utf8Valid: true,
    invalidUtf8Fields: ['section'], rawBytes: { section: '2f726177ff2e766173' } })));
  report.write(line(result(3)));
  assert.equal(report.end(0), 1);
  assert.match(report.error.message, /saved input aliases cannot be verified/);
  assert.ok(report.observed.has(JSON.stringify(['bytes', '2f726177ff2e766173'])));
});
