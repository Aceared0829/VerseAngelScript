'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const manifest = require('../package.json');
const { contains, createPlan } = require('../src/toolchain');

const defaults = { trusted: true, operation: 'build', root: '/project', file: 'src/main.vas',
  settings: { compilerPath: '/sdk/vasbuild', runnerPath: '/sdk/vasrun', configFile: '.vas/api.txt', outputDirectory: '.vas/build' } };

test('build uses separate arguments and keeps the source hierarchy', () => {
  const plan = createPlan({ ...defaults, root: '/my project', file: "src/a;$(echo danger).vas" }, path.posix);
  assert.deepEqual(plan.args, ['/my project/.vas/api.txt', '/my project/src/a;$(echo danger).vas', '/my project/.vas/build/src/a;$(echo danger).vasbc']);
  assert.equal(plan.cwd, '/my project/src');
  assert.equal(plan.executable, '/sdk/vasbuild');
});

test('same-basename files produce distinct bytecode', () => {
  assert.notEqual(createPlan(defaults, path.posix).output,
    createPlan({ ...defaults, file: 'other/main.vas' }, path.posix).output);
});

test('run compiles the original saved source with its working directory', () => {
  assert.deepEqual(createPlan({ ...defaults, operation: 'run' }, path.posix), {
    executable: '/sdk/vasrun', args: ['/project/src/main.vas'], cwd: '/project/src', source: '/project/src/main.vas'
  });
});

test('workspace variables resolve against the selected root', () => {
  const plan = createPlan({ ...defaults, file: '${workspaceFolder}/main.vas', settings: {
    ...defaults.settings, configFile: '${workspaceFolder}/api.txt', outputDirectory: '${workspaceFolder}/out'
  } }, path.posix);
  assert.deepEqual(plan.args, ['/project/api.txt', '/project/main.vas', '/project/out/main.vasbc']);
});

test('Windows paths, spaces and drives are handled without shell quoting', () => {
  const plan = createPlan({ trusted: true, operation: 'build', root: 'C:\\VAS Game', file: 'src\\main.vas', settings: {
    compilerPath: 'C:\\Program Files\\VAS\\vasbuild.exe', configFile: '.vas\\api.txt', outputDirectory: '.vas\\build'
  } }, path.win32);
  assert.equal(plan.executable, 'C:\\Program Files\\VAS\\vasbuild.exe');
  assert.deepEqual(plan.args, ['C:\\VAS Game\\.vas\\api.txt', 'C:\\VAS Game\\src\\main.vas', 'C:\\VAS Game\\.vas\\build\\src\\main.vasbc']);
  assert.equal(contains('C:\\VAS Game', 'D:\\other.vas', path.win32), false);
});

test('invalid or unsafe plans fail before any execution', () => {
  for (const override of [
    { trusted: false }, { operation: 'debug' }, { file: '../escape.vas' }, { file: '/project-sibling/main.vas' },
    { file: 'old.as' }, { file: 'upper.VAS' }, { root: '' }, { file: '${env:HOME}/main.vas' },
    { settings: { ...defaults.settings, compilerPath: 'vasbuild' } },
    { settings: { ...defaults.settings, compilerPath: '${workspaceFolder}/vasbuild' } },
    { settings: { ...defaults.settings, outputDirectory: '../outside' } },
    { settings: { ...defaults.settings, configFile: '' } }, { file: 'bad\0.vas' }
  ]) assert.throws(() => createPlan({ ...defaults, ...override }, path.posix), Error);
  assert.throws(() => createPlan({ ...defaults, root: 'C:\\project', settings: {
    ...defaults.settings, compilerPath: 'C:\\sdk\\vasbuild.cmd'
  } }, path.win32), /native .exe/);
});

test('problem matchers distinguish compiler errors and warnings including Windows paths', () => {
  const [errors, warnings] = manifest.contributes.problemMatchers;
  const line = 'C:\\game (demo)\\src\\main.vas (12, 9) : ERR  : Expected expression value';
  const match = new RegExp(errors.pattern.regexp).exec(line);
  assert.equal(match[errors.pattern.file], 'C:\\game (demo)\\src\\main.vas');
  assert.equal(match[errors.pattern.line], '12');
  assert.equal(match[errors.pattern.message], 'Expected expression value');
  // Compiler columns count UTF-8 bytes, not VS Code UTF-16 units. Until a
  // structured bridge converts them, highlight the exact line, not a wrong column.
  assert.equal(errors.pattern.column, undefined);
  assert.equal(warnings.pattern.column, undefined);
  assert.equal(errors.severity, 'error');
  assert.equal(warnings.severity, 'warning');
  assert.equal(new RegExp(warnings.pattern.regexp).test(line), false);
  assert.equal(new RegExp(warnings.pattern.regexp).test('/game/main.vas (2, 1) : WARN : Unreachable code'), true);
  assert.equal(new RegExp(errors.pattern.regexp).test('/game/main.vas (0, 0) : INFO : Compilation failed'), false);
});

test('manifest gates execution and keeps trusted executables in machine settings', () => {
  assert.equal(manifest.capabilities.untrustedWorkspaces.supported, 'limited');
  assert.equal(manifest.contributes.configuration.properties['vas.compilerPath'].scope, 'machine');
  assert.equal(manifest.contributes.configuration.properties['vas.runnerPath'].scope, 'machine');
  for (const command of manifest.contributes.commands) assert.match(command.enablement, /isWorkspaceTrusted/);
  assert.deepEqual(manifest.extensionKind, ['workspace']);
});
