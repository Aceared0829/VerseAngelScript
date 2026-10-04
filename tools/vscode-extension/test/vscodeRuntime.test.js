'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { inspectRuntime, configuredRuntime, prepareRuntime, runtimeForIntegration } = require('./vscodeRuntime');

async function fixture(run, platform = process.platform) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-code-runtime-'));
  const executable = platform === 'darwin' ? path.join(root, 'Visual Studio Code.app/Contents/MacOS/Code') :
    path.join(root, platform === 'win32' ? 'Code.exe' : 'code');
  const app = platform === 'darwin' ? path.join(root, 'Visual Studio Code.app/Contents/Resources/app') : path.join(root, 'resources/app');
  const parcel = path.join(app, 'node_modules/@parcel/watcher'), version = '1.96.4', commit = 'c'.repeat(40);
  try {
    await fs.mkdir(path.dirname(executable), { recursive: true }); await fs.writeFile(executable, 'layout fixture, never executed');
    await fs.mkdir(parcel, { recursive: true });
    await fs.writeFile(path.join(app, 'package.json'), JSON.stringify({ version }));
    await fs.writeFile(path.join(app, 'product.json'), JSON.stringify({ version, commit }));
    await fs.writeFile(path.join(parcel, 'package.json'), JSON.stringify({ name: '@parcel/watcher', version: '2.1.0' }));
    await run({ root, executable, app, parcel, version, commit });
  } finally { await fs.rm(root, { recursive: true, force: true }); }
}

for (const [platform, backend] of [['linux', 'inotify'], ['darwin', 'fs-events'], ['win32', 'windows']]) {
  test(`official ${platform} runtime layout binds version, commit, bundled Parcel and explicit backend`, async () => fixture(async state => {
    const actual = await inspectRuntime(state.executable, platform);
    assert.equal(actual.app, state.app); assert.equal(actual.parcel, state.parcel);
    assert.equal(actual.version, state.version); assert.equal(actual.commit, state.commit);
    assert.equal(actual.backend, backend); assert.equal(actual.parcelVersion, '2.1.0');
  }, platform));
}

test('CI preparation resolves stable once and GUI reuses that verified executable without another download', async () => fixture(async state => {
  const envFile = path.join(state.root, 'github-env'); let downloads = 0;
  const prepared = await prepareRuntime({ version: 'stable', envFile, download: async requested => {
    assert.equal(requested, 'stable'); downloads++; return state.executable;
  } });
  const env = { GITHUB_ACTIONS: 'true', ...Object.fromEntries((await fs.readFile(envFile, 'utf8')).trimEnd().split('\n')
    .map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; })) };
  const reused = await runtimeForIntegration(env, () => { throw new Error('must never download again'); });
  assert.deepEqual(reused, prepared); assert.equal(downloads, 1);
  assert.equal(env.VAS_TEST_VSCODE_COMMIT, state.commit);
}));

test('missing or mismatched prepared CI identity fails instead of resolving another runtime', async () => fixture(async state => {
  const env = { GITHUB_ACTIONS: 'true', VAS_TEST_VSCODE_EXECUTABLE: state.executable,
    VAS_TEST_VSCODE_VERSION: state.version, VAS_TEST_VSCODE_COMMIT: state.commit };
  for (const changed of [{ ...env, VAS_TEST_VSCODE_EXECUTABLE: undefined },
    { ...env, VAS_TEST_VSCODE_VERSION: undefined }, { ...env, VAS_TEST_VSCODE_COMMIT: undefined },
    { ...env, VAS_TEST_VSCODE_VERSION: '1.97.0' }, { ...env, VAS_TEST_VSCODE_COMMIT: 'd'.repeat(40) },
    { ...env, VSCODE_TEST_VERSION: '1.97.0' }]) {
    await assert.rejects(runtimeForIntegration(changed, () => { throw new Error('unexpected download fallback'); }),
      error => !error.message.includes('unexpected download fallback'));
  }
  await assert.rejects(configuredRuntime({ VAS_TEST_VSCODE_EXECUTABLE: 'relative/code' }), /absolute|official downloaded/);
  await assert.rejects(inspectRuntime(state.executable + '\nBAD=value'), /official downloaded/);
}));

test('runtime preparation fails on absent Parcel, invalid product commit, mismatched package or requested version', async () => fixture(async state => {
  const envFile = path.join(state.root, 'github-env'), download = async () => state.executable;
  await assert.rejects(prepareRuntime({ version: '1.97.0', envFile, download }));
  await assert.rejects(fs.stat(envFile), { code: 'ENOENT' });
  const productFile = path.join(state.app, 'product.json');
  await fs.writeFile(productFile, JSON.stringify({ version: state.version, commit: 'invalid' }));
  await assert.rejects(inspectRuntime(state.executable), /exact commit/);
  await fs.writeFile(productFile, JSON.stringify({ version: state.version, commit: state.commit + '\n' }));
  await assert.rejects(inspectRuntime(state.executable), /exact commit/);
  await fs.writeFile(productFile, JSON.stringify({ version: '1.97.0', commit: state.commit }));
  await assert.rejects(inspectRuntime(state.executable), /versions must agree/);
  await fs.writeFile(productFile, JSON.stringify({ version: state.version, commit: state.commit }));
  await fs.writeFile(path.join(state.parcel, 'package.json'), JSON.stringify({ name: 'wrong-watcher', version: '2.1.0' }));
  await assert.rejects(inspectRuntime(state.executable));
  await fs.rm(path.join(state.parcel, 'package.json'));
  await assert.rejects(inspectRuntime(state.executable), { code: 'ENOENT' });
}));
