'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { createHash } = require('node:crypto');
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
const proofFields = (bytes = Buffer.from('void main() {}')) => ({ sourceDigestVersion: 1, sourceDigestAlgorithm: 'sha256',
  sourceByteLength: bytes.length, sourceDigest: createHash('sha256').update(bytes).digest('hex') });
const proofValue = fields => ({ version: fields.sourceDigestVersion, algorithm: fields.sourceDigestAlgorithm,
  byteLength: fields.sourceByteLength, digest: fields.sourceDigest });
const loaded = (seq, fields = {}, section = plan.source) => base('section_loaded', seq, { section, utf8Valid: true, ...fields });
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

test('loaded source proofs retain exact byte metadata separately from path observations', () => {
  for (const bytes of [Buffer.alloc(0), Buffer.from('void main() {}'),
    Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('one\r\ntwo\0'), Buffer.from([0xff, 0xfe])])]) {
    const fields = proofFields(bytes);
    const { report, code } = feed([start(), loaded(2, { ...fields, utf8Valid: false }), result(3)]);
    const key = JSON.stringify(['text', plan.source]);
    assert.equal(code, 0);
    assert.deepEqual(report.sourceProofs.get(key), proofValue(fields));
    assert.deepEqual(report.observed.get(key), { key, file: plan.source });
    assert.ok(report.invalidSources.has(key), 'source encoding validity stays independent of byte proof');
  }
  assert.equal(feed([start(), loaded(2, { ...proofFields(), sourceByteLength: Number.MAX_SAFE_INTEGER }), result(3)]).code, 0);
});

test('old and future source digest formats remain usable without source proof evidence', () => {
  for (const fields of [{}, { sourceDigestVersion: 2 }, { ...proofFields(), sourceDigestVersion: 100 },
    { sourceDigestVersion: 2, sourceDigestAlgorithm: 'future', sourceByteLength: 'unknown', sourceDigest: {} }]) {
    const { report, code } = feed([start(), loaded(2, fields), result(3)]);
    assert.equal(code, 0);
    assert.equal(report.sourceProofs.size, 0);
    assert.equal(report.observed.size, 1);
  }
  const first = feed([start(), loaded(2, proofFields()), result(3)]).report;
  const second = feed([start(), loaded(2), result(3)]).report;
  assert.equal(first.sourceProofs.size, 1);
  assert.equal(second.sourceProofs.size, 0, 'proofs cannot carry over to another invocation');
});

test('known source proof fields must be complete, exact and well typed', () => {
  const valid = proofFields();
  const malformed = [];
  for (const field of Object.keys(valid)) {
    const partial = { ...valid }; delete partial[field]; malformed.push(partial);
    malformed.push({ [field]: valid[field] });
  }
  for (const sourceDigestVersion of [null, 0, -1, 1.5, 2.5, '1', Number.MAX_SAFE_INTEGER + 1]) malformed.push({ ...valid, sourceDigestVersion });
  for (const sourceDigestAlgorithm of [null, 1, 'SHA256', 'sha-256', 'sha256\0']) malformed.push({ ...valid, sourceDigestAlgorithm });
  for (const sourceByteLength of [null, -1, 1.5, '14', Number.MAX_SAFE_INTEGER + 1, NaN, Infinity]) malformed.push({ ...valid, sourceByteLength });
  for (const sourceDigest of [null, 1, '', 'a'.repeat(63), 'a'.repeat(65), 'A'.repeat(64), 'g'.repeat(64), 'a'.repeat(64) + '\n']) malformed.push({ ...valid, sourceDigest });
  malformed.push({ ...valid, invalidUtf8Fields: ['sourceDigest'], rawBytes: { sourceDigest: 'ff' } });
  malformed.push({ ...valid, invalidUtf8Fields: ['sourceDigestAlgorithm'], rawBytes: { sourceDigestAlgorithm: 'ff' } });
  for (const fields of malformed) {
    const { report, code } = feed([start(), loaded(2, fields), result(3)]);
    assert.equal(code, 1, JSON.stringify(fields));
    assert.match(report.error.message, /source digest/);
  }
});

test('repeated exact loaded identities cannot replace source proof or change coverage', () => {
  const known = proofFields(), future = { sourceDigestVersion: 2 };
  const changes = [[known, { ...known, sourceDigest: 'a'.repeat(64) }], [known, { ...known, sourceByteLength: known.sourceByteLength + 1 }],
    [known, {}], [{}, known], [known, future], [future, known]];
  for (const [first, second] of changes) {
    const { report, code } = feed([start(), loaded(2, first), loaded(3, second), result(4)]);
    assert.equal(code, 1);
    assert.match(report.error.message, /source proof/);
    assert.deepEqual(report.sourceProofs.get(JSON.stringify(['text', plan.source])), first === known ? proofValue(known) : undefined);
  }
  for (const [first, second] of [[known, known], [{}, {}], [future, {}], [{}, future]]) {
    assert.equal(feed([start(), loaded(2, first), loaded(3, second), result(4)]).code, 0);
  }
  const { report, code } = feed([start(), loaded(2, known, '/Case.vas'),
    loaded(3, proofFields(Buffer.from('different')), '/case.vas'), result(4)]);
  assert.equal(code, 0);
  assert.equal(report.sourceProofs.size, 2, 'case-distinct compiler identities remain independent');
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

function processFixture(callbacks = {}, invocation = plan) {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  child.kill = () => { child.kills = (child.kills || 0) + 1; return true; };
  const output = [], diagnostics = [];
  let controller, spawnOptions;
  const ended = new Promise(resolve => {
    controller = new ProjectBuildProcess(invocation, { output: text => output.push(text), diagnostic: value => diagnostics.push(value), ...callbacks, complete: resolve },
      (executable, args, options) => { assert.equal(executable, plan.executable); spawnOptions = options; return child; });
    controller.start();
  });
  return { child, controller, output, diagnostics, ended, spawnOptions };
}

test('only loaded known proofs reach observation callbacks and dependency graphs keep path-only context', async () => {
  const fields = proofFields(), observations = [], registrations = [], deps = new ProjectDependencies();
  const oldFile = path.join(root, 'old.vas'), futureFile = path.join(root, 'future.vas');
  const f = processFixture({ observe: async item => { observations.push(item); await deps.observe(plan, item); } },
    { ...plan, observeInput: (file, proof) => registrations.push({ file, proof }) });
  f.child.stdout.end(Buffer.concat([start(), loaded(2, fields),
    base('include_attempt', 3, { from: plan.source, requested: 'main.vas', resolved: plan.source, ...fields }),
    base('include_result', 4, { attemptSeq: 3, status: 'skipped' }), loaded(5, {}, oldFile),
    loaded(6, { ...fields, sourceDigestVersion: 2 }, futureFile), result(7)].map(line)));
  f.child.stderr.end(); f.child.emit('close', 0);
  assert.equal(await f.ended, 0);
  assert.deepEqual(observations.map(item => item.sourceProof), [proofValue(fields), undefined, undefined, undefined]);
  assert.deepEqual(registrations, [{ file: plan.source, proof: proofValue(fields) }, { file: plan.source, proof: undefined },
    { file: oldFile, proof: undefined }, { file: futureFile, proof: undefined }]);
  for (const observed of [f.controller.report.observed, deps.entries.get(plan.key).observed]) {
    assert.equal(observed.size, 3);
    for (const item of observed.values()) assert.deepEqual(Object.keys(item).sort(), ['file', 'key']);
  }
});

test('source proof admission is registered synchronously and awaited before observation and completion', async () => {
  const fields = proofFields(), phases = [];
  let releaseAdmission, releaseObservation, completed = false;
  const f = processFixture({ observe: async item => {
    assert.deepEqual(item.sourceProof, proofValue(fields));
    phases.push('observing'); await new Promise(resolve => { releaseObservation = resolve; });
  } }, { ...plan, observeInput: (file, proof) => {
    assert.equal(file, plan.source); assert.deepEqual(proof, proofValue(fields)); phases.push('admitting');
    return new Promise(resolve => { releaseAdmission = resolve; });
  } });
  f.ended.then(() => { completed = true; });
  f.child.stdout.end(Buffer.concat([start(), loaded(2, fields), result(3)].map(line)));
  assert.deepEqual(phases, ['admitting']);
  f.child.stderr.end(); f.child.emit('close', 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(phases, ['admitting']); assert.equal(completed, false);
  releaseAdmission(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(phases, ['admitting', 'observing']); assert.equal(completed, false);
  releaseObservation(); assert.equal(await f.ended, 0);
});

test('pending source proof admission cannot bypass cancellation, rejected evidence or complete-report validation', async () => {
  for (const mode of ['cancel', 'reject', 'truncated']) {
    let release, reject, completed = false;
    const observations = [];
    const f = processFixture({ observe: item => observations.push(item) }, { ...plan,
      observeInput: () => new Promise((resolve, fail) => { release = resolve; reject = fail; }) });
    f.ended.then(() => { completed = true; });
    f.child.stdout.end(Buffer.concat([start(), loaded(2, proofFields()), ...(mode === 'truncated' ? [] : [result(3)])].map(line)));
    if (mode === 'cancel') f.controller.terminate();
    f.child.stderr.end(); f.child.emit('close', 0);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(completed, false); assert.equal(observations.length, 0);
    if (mode === 'reject') reject(new Error('Loaded source bytes do not match compiler proof'));
    else release();
    assert.equal(await f.ended, mode === 'cancel' ? 130 : 1);
    if (mode === 'reject') { assert.equal(observations.length, 0); assert.match(f.controller.report.error.message, /bytes do not match/); }
    if (mode === 'truncated') assert.match(f.controller.report.error.message, /truncated report/);
  }
});

test('later source proof rejection stays handled while earlier observation is pending', async () => {
  let release, calls = 0;
  const f = processFixture({ observe: () => new Promise(resolve => { release = resolve; }) }, { ...plan,
    observeInput: () => ++calls === 1 ? undefined : Promise.reject(new Error('Later proof failed')) });
  f.child.stdout.end(Buffer.concat([start(), loaded(2, proofFields()),
    loaded(3, proofFields(), path.join(root, 'another.vas')), result(4)].map(line)));
  f.child.stderr.end(); f.child.emit('close', 0);
  await new Promise(resolve => setImmediate(resolve));
  release(); assert.equal(await f.ended, 1);
  assert.match(f.controller.report.error.message, /Later proof failed/);
});

test('asynchronous observation rejection still invalidates proof-bearing report', async () => {
  const f = processFixture({ observe: async () => { throw new Error('Watch verification failed'); } });
  f.child.stdout.end(Buffer.concat([start(), loaded(2, proofFields()), result(3)].map(line)));
  f.child.stderr.end(); f.child.emit('close', 0);
  assert.equal(await f.ended, 1);
  assert.match(f.controller.report.error.message, /Watch verification failed/);
});

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
    const resolvedPhysical = await fs.realpath(physical);
    assert.ok(watched.includes(resolvedPhysical)); assert.ok(deps.relevant(resolvedPhysical));
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
  const files = [], phases = [], observations = [], fields = proofFields();
  let release, completed = false;
  const invocation = { ...plan, observeInput: file => files.push(file) };
  const ended = new Promise(resolve => {
    const process = new ProjectBuildProcess(invocation, { output() {}, diagnostic() {},
      observe: async item => { observations.push(item); phases.push('observing'); await new Promise(done => { release = done; }); },
      complete: code => { completed = true; resolve(code); } }, () => child);
    process.start();
  });
  const included = path.join(root, 'shared.vas');
  child.stdout.write(Buffer.concat([line(start()), line(loaded(2, fields, included)), line(result(3))]));
  assert.deepEqual(files, [included], 'input membership is synchronous with validated report parsing');
  child.stdout.end(); child.stderr.end(); child.emit('close', 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(phases, ['observing']); assert.equal(completed, false);
  assert.deepEqual(observations, [{ key: JSON.stringify(['text', included]), file: included, sourceProof: proofValue(fields) }]);
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
  child.stdout.write(Buffer.concat([line(start()), line(loaded(2, proofFields(), included)), line(result(3))]));
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

test('valid source proof never gives a raw-byte loaded path URI authority', () => {
  const { report, code } = feed([start(), loaded(2, { ...proofFields(), invalidUtf8Fields: ['section'],
    rawBytes: { section: '2f726177ff2e766173' } }, '/display�.vas'), result(3)]);
  assert.equal(code, 1);
  assert.match(report.error.message, /saved input aliases cannot be verified/);
  assert.ok(report.observed.has(JSON.stringify(['bytes', '2f726177ff2e766173'])));
  assert.equal(report.sourceProofs.size, 0);
});
