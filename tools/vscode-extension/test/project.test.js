'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { compilerPath, projectRequest, validateDescriptor, describeProject, cancellation, snapshotDescriptor, projectPlan } = require('../src/project');
const { ProjectBuildProcess } = require('../src/projectReport');
const root = path.join(os.tmpdir(), 'VAS 文😀;$(trap)');
const folder = { uri: { scheme: 'file', fsPath: root } };
const executable = process.execPath;
const descriptor = () => ({ protocol: 'vas-project', version: 1, success: true, project: path.join(root, 'vas-project.json'),
  projectRoot: root, projectSchemaVersion: 1, legacyProject: false, name: 'Project 文😀',
  compilationUnits: [{ id: 'main', entry: path.join(root, 'main.vas'), hostApi: { config: path.join(root, 'api.txt') }, output: path.join(root, 'out/main.vasbc') }], warnings: [], errors: [] });

test('machine compiler is independent from workspace traps; project tasks require explicit root and unit', () => {
  const configuration = { inspect: () => ({ globalValue: executable, workspaceValue: '/project/trap' }), get: () => '/project/trap' };
  assert.equal(compilerPath(configuration), executable);
  assert.throws(() => compilerPath({ inspect: () => ({ workspaceValue: '/project/trap' }) }), /User or Remote/);
  assert.throws(() => compilerPath({ get: () => 'C:\\tools\\vasbuild.cmd' }, path.win32), /native compiler/);
  const request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, executable);
  assert.equal(request.project, path.join(root, 'vas-project.json'));
  for (const definition of [{ project: 'nested/vas-project.json', unit: 'main' }, { project: 'vas-project.json' },
    { project: 'vas-project.json', unit: '../bad' }, { project: '${env:TRAP}', unit: 'main' }]) assert.throws(() => projectRequest(definition, folder, executable));
});

test('descriptor validates supported schema, unique unit identity and success/exit agreement only', () => {
  assert.equal(validateDescriptor(descriptor(), 0).compilationUnits[0].id, 'main');
  for (const change of [value => value.version = 2, value => value.project = 'relative', value => value.compilationUnits.push(value.compilationUnits[0]),
    value => value.compilationUnits[0].entry = '/bad\ud800.vas', value => value.errors.push({ message: 'bad' }),
    value => value.projectSchemaVersion = 2, value => value.legacyProject = true]) {
    const value = descriptor(); change(value); assert.throws(() => validateDescriptor(value, 0));
  }
  assert.throws(() => validateDescriptor(descriptor(), 7));
  assert.throws(() => validateDescriptor({ ...descriptor(), success: false, compilationUnits: [], errors: [{ field: 'schemaVersion', message: 'unsupported' }] }, 255), /schemaVersion: unsupported/);
});

function descriptorPipe(cancel = cancellation()) {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  child.kill = () => { child.kills = (child.kills || 0) + 1; };
  const result = describeProject(executable, path.join(root, 'vas-project.json'), cancel, (tool, args, options) => {
    assert.equal(tool, executable); assert.deepEqual(args, ['--describe-project=json', path.join(root, 'vas-project.json')]);
    assert.equal(options.shell, false); return child;
  });
  return { child, result, cancel };
}

test('descriptor transport requires single JSON, LF, valid UTF-8 and cancellation never resolves', async () => {
  for (const content of ['{}', '\ufeff' + JSON.stringify(descriptor()) + '\n', JSON.stringify(descriptor()) + '\n{}\n', Buffer.from([255])]) {
    const fixture = descriptorPipe(); fixture.child.stdout.end(content); fixture.child.stderr.end(); fixture.child.emit('close', 0);
    await assert.rejects(fixture.result);
  }
  const valid = descriptorPipe(); valid.child.stdout.end(JSON.stringify(descriptor()) + '\n'); valid.child.stderr.end(); valid.child.emit('close', 0);
  assert.equal((await valid.result).name, 'Project 文😀');
  const cancelled = descriptorPipe(); cancelled.cancel.cancel(); cancelled.child.emit('close', null);
  await assert.rejects(cancelled.result, /cancelled/); assert.equal(cancelled.child.kills, 1);
});

function host(folder) {
  const settings = { compilerPath: process.env.VAS_TEST_COMPILER };
  const vscode = { workspace: { isTrusted: true, textDocuments: [], getConfiguration: () => ({ inspect: key => ({ globalValue: settings[key] }) }) } };
  return { vscode, settings, folder };
}
function run(plan) {
  const diagnostics = [], output = [];
  return new Promise(resolve => {
    const process = new ProjectBuildProcess(plan, { diagnostic: record => diagnostics.push(record), output: text => output.push(text),
      complete: (code, report) => resolve({ code, report, diagnostics, output }) });
    process.start();
  });
}

test('real native descriptor and project builds preserve unit/config, Unicode/BOM/include diagnostics and freshness', { skip: !process.env.VAS_TEST_COMPILER }, async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'VAS project 文😀 ; '));
  const folder = { uri: { scheme: 'file', fsPath: root } }, context = host(folder);
  const project = path.join(root, 'vas-project.json'), config = path.join(root, 'api 文😀.txt');
  const manifest = { schemaVersion: 1, name: 'native', compilationUnits: [
    { id: 'first', entry: 'main 文😀.vas', hostApi: { config: 'api 文😀.txt' }, output: 'out ;/one 文😀.vasbc' },
    { id: 'second', entry: 'main 文😀.vas', hostApi: { config: 'missing.txt' }, output: 'out ;/two 文😀.vasbc' }
  ] };
  try {
    await fs.writeFile(project, JSON.stringify(manifest));
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'), config);
    await fs.writeFile(path.join(root, 'main 文😀.vas'), '#include "shared 文😀.vas"\nvoid main() {}\n');
    await fs.writeFile(path.join(root, 'shared 文😀.vas'), '\ufeff/* 文😀 */ int Broken() { return; }\r\n');
    const request = projectRequest({ project: 'vas-project.json', unit: 'first' }, folder, process.env.VAS_TEST_COMPILER);
    const selected = await snapshotDescriptor({ ...context, project, executable: request.executable });
    await assert.rejects(fs.stat(path.join(root, 'out ;')));
    const plan = await projectPlan(request, selected, context);
    const failed = await run(plan);
    assert.notEqual(failed.code, 0); assert.equal(failed.report.error, undefined);
    assert.equal(failed.report.result.dependenciesComplete, true);
    assert.ok(failed.diagnostics.some(record => record.file.endsWith('shared 文😀.vas') && record.message === 'Must return a value'));
    await assert.rejects(fs.stat(path.join(root, 'out ;')));
    const second = await projectPlan({ ...request, unit: 'second' }, undefined, context);
    const missing = await run(second);
    assert.notEqual(missing.code, 0); assert.equal(missing.report.error, undefined);
    assert.ok(missing.diagnostics.some(record => record.file.endsWith('missing.txt') && record.column === 0));
    assert.notEqual(plan.key, second.key);
    await fs.writeFile(path.join(root, 'shared 文😀.vas'), 'int Broken() { return 1; }\n');
    const success = await run(plan);
    assert.equal(success.code, 0, success.output.join(''));
    assert.ok((await fs.stat(plan.output)).size > 0);
    await fs.appendFile(config, '\n// changed\n');
    await assert.rejects(projectPlan(request, selected, context), /configuration changed/);
    await assert.rejects(plan.checkFresh(), /configuration/);
    const fresh = await snapshotDescriptor({ ...context, project, executable: request.executable });
    await fs.appendFile(project, ' ');
    await assert.rejects(projectPlan(request, fresh, context), /changed after selection/);
    context.vscode.workspace.isTrusted = false;
    await assert.rejects(projectPlan(request, undefined, context), /Trust/);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('real native dirty inputs and invalid manifests block without output; legacy descriptor ignores trap tools', { skip: !process.env.VAS_TEST_COMPILER }, async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'VAS legacy 文😀 '));
  const folder = { uri: { scheme: 'file', fsPath: root } }, context = host(folder), project = path.join(root, 'vas-project.json');
  const request = projectRequest({ project: 'vas-project.json', unit: 'main' }, folder, process.env.VAS_TEST_COMPILER);
  try {
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'), path.join(root, 'api.txt'));
    await fs.writeFile(path.join(root, 'main.vas'), 'void main() {}\n');
    await fs.writeFile(project, JSON.stringify({ name: 'legacy', entry: 'main.vas', builderConfig: 'api.txt', bytecodeOutput: 'out/main.vasbc', builder: 'trap.exe', runner: 'trap.exe' }));
    const snapshot = await snapshotDescriptor({ ...context, project, executable: request.executable });
    assert.equal(snapshot.descriptor.legacyProject, true); assert.equal(snapshot.descriptor.compilationUnits[0].id, 'main');
    await assert.rejects(fs.stat(path.join(root, 'out')));
    for (const file of [project, path.join(root, 'api.txt'), path.join(root, 'main.vas')]) {
      context.vscode.workspace.textDocuments = [{ isDirty: true, uri: { scheme: 'file', fsPath: file } }];
      await assert.rejects(projectPlan(request, undefined, context), /Save/);
    }
    context.vscode.workspace.textDocuments = [];
    const plan = await projectPlan(request, snapshot, context);
    const built = await run(plan); assert.equal(built.code, 0, built.output.join(''));
    assert.ok(built.diagnostics.some(record => record.severity === 'WARN'));
    await fs.rm(path.join(root, 'out'), { recursive: true });
    await fs.writeFile(project, '{"schemaVersion":999}');
    await assert.rejects(projectPlan(request, undefined, context));
    await assert.rejects(fs.stat(path.join(root, 'out')));
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('dirty config aliases are rejected without changing compiler identity keys', async () => {
  const { requireProjectReady } = require('../src/project');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-config-alias-'));
  const target = path.join(root, 'Api.txt'), alias = path.join(root, 'config-link.txt');
  const project = path.join(root, 'vas-project.json');
  const context = host({ uri: { scheme: 'file', fsPath: root } });
  try {
    await fs.writeFile(target, 'api'); await fs.writeFile(project, '{}');
    if (process.platform === 'win32') await fs.link(target, alias); else await fs.symlink(target, alias);
    context.vscode.workspace.textDocuments = [{ isDirty: true, uri: { scheme: 'file', fsPath: target } }];
    await assert.rejects(requireProjectReady(context.vscode, context.folder, project, [alias]), /Save/);
    if (process.platform === 'win32') {
      context.vscode.workspace.textDocuments[0].uri.fsPath = path.join(root, 'api.TXT');
      await assert.rejects(requireProjectReady(context.vscode, context.folder, project, [target]), /Save/);
    }
    const { sameFileName } = require('../src/toolchain');
    assert.equal(sameFileName('C:\\Project\\Host\\API.txt', 'c:/project/host/api.TXT', path.win32), true);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('regular-file fingerprints and source reads are bounded and do not block on special files', async () => {
  const { fingerprint, readSourceText } = require('../src/project');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-bounded-'));
  try {
    const large = path.join(root, 'large.txt'); await fs.writeFile(large, 'too large');
    await assert.rejects(fingerprint(large, 2), /validation limit/);
    assert.equal(await readSourceText(large, undefined, 2), undefined);
    assert.match(await fingerprint(root), /^unreadable:/);
    if (process.platform !== 'win32') {
      const fifo = path.join(root, 'input-fifo');
      require('node:child_process').execFileSync('mkfifo', [fifo]);
      assert.equal(await fingerprint(fifo), 'unreadable:nonregular');
      assert.equal(await readSourceText(fifo), undefined);
      assert.equal(await readSourceText('/dev/zero'), undefined);
    }
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
