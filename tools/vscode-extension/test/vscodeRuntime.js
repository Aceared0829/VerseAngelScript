'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

async function inspectRuntime(executable, platform = process.platform) {
  assert.ok(typeof executable === 'string' && path.isAbsolute(executable) && !/[\r\n]/.test(executable),
    'Set VAS_TEST_VSCODE_EXECUTABLE to the official downloaded test runtime executable');
  assert.equal((await fs.stat(executable)).isFile(), true, 'VS Code runtime executable must exist');
  const app = platform === 'darwin' ? path.resolve(path.dirname(executable), '../Resources/app') :
    path.join(path.dirname(executable), 'resources/app');
  const parcel = path.join(app, 'node_modules/@parcel/watcher');
  const [codePackage, product, parcelPackage] = await Promise.all([
    fs.readFile(path.join(app, 'package.json'), 'utf8').then(JSON.parse),
    fs.readFile(path.join(app, 'product.json'), 'utf8').then(JSON.parse),
    fs.readFile(path.join(parcel, 'package.json'), 'utf8').then(JSON.parse)
  ]);
  assert.match(codePackage.version, /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/);
  assert.equal(product.version, codePackage.version, 'VS Code product/package versions must agree');
  assert.match(product.commit, /^[0-9a-f]{40}$/, 'VS Code runtime must identify its exact commit');
  assert.equal(parcelPackage.name, '@parcel/watcher');
  assert.equal(typeof parcelPackage.version, 'string');
  const backend = { darwin: 'fs-events', win32: 'windows', linux: 'inotify' }[platform];
  assert.ok(backend, `Unsupported VS Code watcher test platform: ${platform}`);
  return { executable, app, version: codePackage.version, commit: product.commit, parcel, parcelVersion: parcelPackage.version, backend };
}

async function configuredRuntime(env = process.env) {
  const runtime = await inspectRuntime(env.VAS_TEST_VSCODE_EXECUTABLE);
  if (env.GITHUB_ACTIONS === 'true') assert.ok(env.VAS_TEST_VSCODE_VERSION && env.VAS_TEST_VSCODE_COMMIT,
    'CI must prepare one verified VS Code runtime before component or GUI tests');
  if (env.VAS_TEST_VSCODE_VERSION) assert.equal(runtime.version, env.VAS_TEST_VSCODE_VERSION);
  if (env.VAS_TEST_VSCODE_COMMIT) assert.equal(runtime.commit, env.VAS_TEST_VSCODE_COMMIT);
  if (env.VSCODE_TEST_VERSION && !['stable', 'insiders'].includes(env.VSCODE_TEST_VERSION)) {
    assert.equal(runtime.version, env.VSCODE_TEST_VERSION, 'prepared runtime must match the requested matrix version');
  }
  return runtime;
}

async function prepareRuntime({ version = process.env.VSCODE_TEST_VERSION || '1.96.4', envFile = process.env.GITHUB_ENV,
  download = requested => require('@vscode/test-electron').downloadAndUnzipVSCode(requested) } = {}) {
  const runtime = await inspectRuntime(await download(version));
  if (version !== 'stable' && version !== 'insiders') assert.equal(runtime.version, version);
  if (envFile) await fs.appendFile(envFile, `VAS_TEST_VSCODE_EXECUTABLE=${runtime.executable}\n` +
    `VAS_TEST_VSCODE_VERSION=${runtime.version}\nVAS_TEST_VSCODE_COMMIT=${runtime.commit}\n`);
  return runtime;
}

async function runtimeForIntegration(env = process.env, prepare = prepareRuntime) {
  if (env.VAS_TEST_VSCODE_EXECUTABLE) return configuredRuntime(env);
  assert.notEqual(env.GITHUB_ACTIONS, 'true', 'CI runtime preparation is required; do not resolve stable again');
  return prepare({ version: env.VSCODE_TEST_VERSION || '1.96.4' });
}

if (require.main === module) prepareRuntime().then(runtime => console.log('VAS test runtime:', JSON.stringify(runtime)))
  .catch(error => { console.error(error); process.exitCode = 1; });

module.exports = { inspectRuntime, configuredRuntime, prepareRuntime, runtimeForIntegration };
