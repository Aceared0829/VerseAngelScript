'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { inspectRuntime, configuredRuntime, prepareRuntime, runtimeForIntegration, subscribeRuntimeParcel } = require('./vscodeRuntime');

async function looseParcelFixture(parcel) {
  await fs.mkdir(path.join(parcel, 'build/Release'), { recursive: true });
  await fs.writeFile(path.join(parcel, 'build/Release/watcher.node'), 'native fixture, never executed');
  await fs.writeFile(path.join(parcel, 'index.js'), 'entry point fixture, never executed');
  await fs.writeFile(path.join(parcel, 'package.json'), JSON.stringify({ name: '@parcel/watcher', version: '2.1.0', main: 'index.js' }));
}

async function fixture(run, platform = process.platform) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-code-runtime-'));
  const executable = platform === 'darwin' ? path.join(root, 'Visual Studio Code.app/Contents/MacOS/Code') :
    path.join(root, platform === 'win32' ? 'Code.exe' : 'code');
  const app = platform === 'darwin' ? path.join(root, 'Visual Studio Code.app/Contents/Resources/app') : path.join(root, 'resources/app');
  const parcel = path.join(app, 'node_modules/@parcel/watcher'), version = '1.96.4', commit = 'c'.repeat(40);
  try {
    await fs.mkdir(path.dirname(executable), { recursive: true }); await fs.writeFile(executable, 'layout fixture, never executed');
    await looseParcelFixture(parcel);
    await fs.writeFile(path.join(app, 'package.json'), JSON.stringify({ version }));
    await fs.writeFile(path.join(app, 'product.json'), JSON.stringify({ version, commit }));
    await run({ root, executable, app, parcel, version, commit });
  } finally { await fs.rm(root, { recursive: true, force: true }); }
}

for (const [platform, backend] of [['linux', 'inotify'], ['darwin', 'fs-events'], ['win32', 'windows']]) {
  test(`official ${platform} runtime layout binds version, commit, bundled Parcel and explicit backend`, async () => fixture(async state => {
    const actual = await inspectRuntime(state.executable, platform);
    assert.equal(actual.app, state.app); assert.equal(actual.parcel, state.parcel);
    assert.equal(actual.parcelBinding, path.join(state.parcel, 'build/Release/watcher.node'));
    assert.equal(actual.parcelLayout, 'loose');
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

async function archiveFixture(state, { platform = process.platform, versioned = platform === 'win32', mutateHeader, packageJson } = {}) {
  await fs.rm(path.join(state.app, 'node_modules'), { recursive: true });
  if (versioned) {
    const app = path.join(state.root, state.commit.slice(0, 10), 'resources/app');
    await fs.mkdir(path.dirname(app), { recursive: true });
    await fs.rename(state.app, app);
    await fs.rmdir(path.join(state.root, 'resources'));
    state.app = app;
    state.parcel = path.join(app, 'node_modules/@parcel/watcher');
  }
  const bytes = Buffer.from('native fixture, never executed');
  const parcelBinding = path.join(state.app, 'node_modules.asar.unpacked/@parcel/watcher/build/Release/watcher.node');
  await fs.mkdir(path.dirname(parcelBinding), { recursive: true }); await fs.writeFile(parcelBinding, bytes);
  const packageBytes = Buffer.from(JSON.stringify(packageJson || { name: '@parcel/watcher', version: '2.5.6', main: 'index.js' }));
  const indexBytes = Buffer.from('entry point fixture, never executed');
  const packageEntry = { offset: '0', size: packageBytes.length };
  const indexEntry = { offset: String(packageBytes.length), size: indexBytes.length };
  const nativeEntry = { size: bytes.length, unpacked: true };
  const watcher = { files: { 'package.json': packageEntry, 'index.js': indexEntry,
    build: { files: { Release: { files: { 'watcher.node': nativeEntry } } } } } };
  const header = { files: { '@parcel': { files: { watcher } } } };
  mutateHeader?.({ header, packageEntry, indexEntry, nativeEntry, watcher, packageBytes, indexBytes, nativeBytes: bytes });
  const json = Buffer.from(JSON.stringify(header)), padded = Math.ceil(json.length / 4) * 4;
  const prefix = Buffer.alloc(16); prefix.writeUInt32LE(4, 0); prefix.writeUInt32LE(padded + 8, 4);
  prefix.writeUInt32LE(padded + 4, 8); prefix.writeUInt32LE(json.length, 12);
  const archive = path.join(state.app, 'node_modules.asar');
  await fs.writeFile(archive, Buffer.concat([prefix, json, Buffer.alloc(padded - json.length), packageBytes, indexBytes]));
  return { archive, parcelBinding };
}

for (const platform of ['linux', 'darwin', 'win32']) {
  test(`official ${platform} ASAR layout preserves exact runtime and bundled native identity`, async () => fixture(async state => {
    const { archive, parcelBinding } = await archiveFixture(state, { platform });
    const actual = await inspectRuntime(state.executable, platform);
    assert.equal(actual.app, state.app); assert.equal(actual.version, state.version); assert.equal(actual.commit, state.commit);
    assert.equal(actual.parcel, path.join(archive, '@parcel/watcher')); assert.equal(actual.parcelBinding, parcelBinding);
    assert.equal(actual.parcelLayout, 'asar'); assert.equal(actual.parcelVersion, '2.5.6');
  }, platform));
}

test('ASAR runtime preparation also resolves stable once and reuses its exact product identity', async () => fixture(async state => {
  await archiveFixture(state);
  let downloads = 0;
  const envFile = path.join(state.root, 'github-env');
  const prepared = await prepareRuntime({ version: 'stable', envFile, download: async version => {
    assert.equal(version, 'stable'); downloads++; return state.executable;
  } });
  const env = { GITHUB_ACTIONS: 'true', ...Object.fromEntries((await fs.readFile(envFile, 'utf8')).trimEnd().split('\n')
    .map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; })) };
  assert.deepEqual(await runtimeForIntegration(env, () => { throw new Error('unexpected second resolution'); }), prepared);
  assert.equal(downloads, 1);
}));

test('Windows versioned runtime rejects ambiguous and commit-mismatched app roots', async () => {
  for (const mode of ['mismatch', 'two-versioned', 'legacy-and-versioned']) await fixture(async state => {
    await archiveFixture(state, { platform: 'win32' });
    if (mode === 'mismatch') {
      await fs.writeFile(path.join(state.app, 'product.json'), JSON.stringify({ version: state.version, commit: 'd'.repeat(40) }));
    } else {
      const other = path.join(state.root, mode === 'two-versioned' ? 'dddddddddd/resources/app' : 'resources/app');
      await fs.mkdir(other, { recursive: true });
    }
    await assert.rejects(inspectRuntime(state.executable, 'win32'), /exactly one|exact product commit/);
  }, 'win32');
});

test('ASAR rejects missing, linked, malformed, out-of-bounds or packed watcher members without fallback', async () => {
  const mutations = [
    ({ header }) => { delete header.files['@parcel']; },
    ({ watcher }) => { watcher.link = '../other'; },
    ({ packageEntry }) => { packageEntry.link = '../other/package.json'; },
    ({ packageEntry }) => { packageEntry.offset = '-1'; },
    ({ packageEntry }) => { packageEntry.offset = '9007199254740993'; },
    ({ packageEntry }) => { packageEntry.offset = '500000'; },
    ({ packageEntry }) => { packageEntry.size = 1024 * 1024 + 1; },
    ({ packageEntry }) => { packageEntry.unpacked = true; },
    ({ nativeEntry }) => { nativeEntry.unpacked = false; },
    ({ nativeEntry }) => { nativeEntry.link = '../../../other.node'; },
    ({ nativeEntry }) => { nativeEntry.size++; },
    ({ indexEntry }) => { indexEntry.offset = '-1'; },
    ({ indexEntry }) => { indexEntry.link = '../other.js'; },
    ({ header }) => { header.files['@parcel'].files['watcher-linux-x64-glibc'] = { files: {} }; }
  ];
  for (const mutateHeader of mutations) await fixture(async state => {
    await archiveFixture(state, { mutateHeader });
    await assert.rejects(inspectRuntime(state.executable), /ASAR|Parcel/);
  });
});

test('ASAR rejects invalid headers, truncated payloads and wrong package metadata', async () => {
  for (const mode of ['header-size', 'pickle-size', 'json-size', 'truncated', 'wrong-name', 'wrong-version', 'wrong-main']) await fixture(async state => {
    const packageJson = { name: mode === 'wrong-name' ? 'other' : '@parcel/watcher',
      version: mode === 'wrong-version' ? '' : '2.5.6', main: mode === 'wrong-main' ? '../other.js' : 'index.js' };
    const { archive } = await archiveFixture(state, { packageJson });
    if (['header-size', 'pickle-size', 'json-size'].includes(mode)) {
      const file = await fs.open(archive, 'r+');
      try {
        const bytes = Buffer.alloc(4); bytes.writeUInt32LE(mode === 'header-size' ? 2 * 1024 * 1024 : 0);
        await file.write(bytes, 0, 4, { 'header-size': 4, 'pickle-size': 0, 'json-size': 12 }[mode]);
      } finally { await file.close(); }
    } else if (mode === 'truncated') await fs.truncate(archive, (await fs.stat(archive)).size - 1);
    await assert.rejects(inspectRuntime(state.executable));
  });
});

test('runtime native member cannot be absent, a link, outside its app or larger than the bound', async () => {
  for (const mode of ['missing', ...(process.platform === 'win32' ? [] : ['link']), 'escape', 'internal-alias', 'oversized']) await fixture(async state => {
    const { parcelBinding } = await archiveFixture(state);
    if (mode === 'missing') await fs.rm(parcelBinding);
    if (mode === 'link') {
      await fs.rm(parcelBinding); await fs.symlink(state.executable, parcelBinding);
    }
    if (mode === 'escape' || mode === 'internal-alias') {
      const directory = path.dirname(parcelBinding), outside = path.join(mode === 'escape' ? state.root : state.app, 'other-binding');
      await fs.rename(directory, outside); await fs.symlink(outside, directory, 'junction');
    }
    if (mode === 'oversized') await fs.truncate(parcelBinding, 32 * 1024 * 1024 + 1);
    await assert.rejects(inspectRuntime(state.executable), /ENOENT|regular file|belong|size limit|exact package path/);
  });
});

test('native subscription preserves Parcel callback, options, readiness, unsubscribe and native failures', async () => fixture(async state => {
  const runtime = await inspectRuntime(state.executable);
  const events = [{ type: 'update', path: path.join(state.root, 'included.vas') }];
  const nativeError = new Error('native watcher error'); let observed, args, ready, unsubscribed = 0;
  const started = Promise.withResolvers();
  const callback = (...values) => { observed = values; };
  const binding = { writeSnapshot() {}, getEventsSince() {},
    subscribe(...values) { args = values; return new Promise(resolve => { ready = resolve; started.resolve(); }); },
    unsubscribe(...values) { assert.deepEqual(values, args); assert.equal(values[2], args[2]); unsubscribed++; return Promise.resolve('closed'); } };
  let complete = false;
  const pending = subscribeRuntimeParcel(runtime, state.root, callback, filename => {
    assert.equal(filename, runtime.parcelBinding); return binding;
  }).then(value => { complete = true; return value; });
  await Promise.race([started.promise, pending.then(() => assert.fail('subscription completed before native readiness'))]);
  assert.equal(complete, false); assert.equal(args[0], path.resolve(state.root)); assert.equal(args[1], callback);
  assert.deepEqual(args[2], { backend: runtime.backend });
  args[1](nativeError, events); assert.equal(observed[0], nativeError); assert.equal(observed[1], events);
  ready(); const subscription = await pending;
  assert.equal(await subscription.unsubscribe(), 'closed'); assert.equal(unsubscribed, 1);
  binding.subscribe = async () => { throw nativeError; };
  await assert.rejects(subscribeRuntimeParcel(runtime, state.root, callback, () => binding), error => error === nativeError);
  delete binding.getEventsSince;
  await assert.rejects(subscribeRuntimeParcel(runtime, state.root, callback, () => binding), /native export is missing/);
  await assert.rejects(subscribeRuntimeParcel({ ...runtime, parcelBinding: state.executable }, state.root, callback,
    () => { throw new Error('unverified load'); }), /parcelBinding changed/);
}));

for (const platform of ['linux', 'darwin', 'win32']) {
  test(`loose ${platform} runtime rejects a competing archive or preferred platform binding`, async () => {
    for (const mode of ['archive', 'preferred', 'nested-preferred']) await fixture(async state => {
      const initial = await inspectRuntime(state.executable, platform);
      assert.equal(initial.parcelLayout, 'loose'); assert.equal(initial.app, state.app);
      if (mode === 'archive') {
        await archiveFixture(state, { platform });
        const archived = await inspectRuntime(state.executable, platform);
        assert.equal(archived.parcelLayout, 'asar'); assert.equal(archived.app, state.app);
        // Compete inside the one verified app. Recreating the old Windows app
        // directory would test application discovery ambiguity instead.
        assert.equal(state.parcel, path.join(archived.app, 'node_modules/@parcel/watcher'));
        await looseParcelFixture(state.parcel);
      } else await fs.mkdir(path.join(mode === 'preferred' ? path.dirname(state.parcel) : path.join(state.parcel, 'node_modules/@parcel'),
        `watcher-${platform}-${process.arch}`), { recursive: true });
      await assert.rejects(inspectRuntime(state.executable, platform), mode === 'archive' ?
        /Ambiguous loose and archived Parcel layouts/ : /Unsupported preferred Parcel platform binding/);
    }, platform);
  });
}

test('legacy loose watcher remains supported beside an unrelated ASAR', async () => fixture(async state => {
  const header = Buffer.from(JSON.stringify({ files: { unrelated: { size: 1, offset: '0' } } }));
  const padded = Math.ceil(header.length / 4) * 4, prefix = Buffer.alloc(16);
  prefix.writeUInt32LE(4, 0); prefix.writeUInt32LE(padded + 8, 4);
  prefix.writeUInt32LE(padded + 4, 8); prefix.writeUInt32LE(header.length, 12);
  await fs.writeFile(path.join(state.app, 'node_modules.asar'), Buffer.concat([prefix, header, Buffer.alloc(padded - header.length), Buffer.from('x')]));
  assert.equal((await inspectRuntime(state.executable)).parcelLayout, 'loose');
}));


test('ASAR validates optional whole-file and block integrity and rejects inconsistent metadata', async () => {
  function integrity(bytes) {
    const hash = bytes => createHash('sha256').update(bytes).digest('hex'), blockSize = 16;
    const blocks = [];
    for (let offset = 0; offset < bytes.length; offset += blockSize) blocks.push(hash(bytes.subarray(offset, offset + blockSize)));
    return { algorithm: 'SHA256', hash: hash(bytes), blockSize, blocks };
  }
  for (const mode of ['valid', 'native-hash', 'package-hash', 'index-hash', 'block-hash', 'block-count', 'algorithm', 'block-size', 'null']) {
    await fixture(async state => {
      await archiveFixture(state, { mutateHeader({ packageEntry, indexEntry, nativeEntry, packageBytes, indexBytes, nativeBytes }) {
        packageEntry.integrity = integrity(packageBytes); indexEntry.integrity = integrity(indexBytes); nativeEntry.integrity = integrity(nativeBytes);
        if (mode === 'native-hash') nativeEntry.integrity.hash = '0'.repeat(64);
        if (mode === 'package-hash') packageEntry.integrity.hash = '0'.repeat(64);
        if (mode === 'index-hash') indexEntry.integrity.hash = '0'.repeat(64);
        if (mode === 'block-hash') nativeEntry.integrity.blocks[0] = '0'.repeat(64);
        if (mode === 'block-count') nativeEntry.integrity.blocks.pop();
        if (mode === 'algorithm') nativeEntry.integrity.algorithm = 'SHA1';
        if (mode === 'block-size') nativeEntry.integrity.blockSize = 0;
        if (mode === 'null') nativeEntry.integrity = null;
      } });
      if (mode === 'valid') assert.equal((await inspectRuntime(state.executable)).parcelLayout, 'asar');
      else await assert.rejects(inspectRuntime(state.executable), /integrity/);
    });
  }
});


test('signed macOS and Windows native files may grow after ASAR construction but remain bounded and cannot shrink', async () => {
  for (const platform of ['darwin', 'win32']) for (const mode of ['signing-growth', 'truncated', 'declared-zero', 'declared-oversized']) {
    await fixture(async state => {
      const { parcelBinding } = await archiveFixture(state, { platform, mutateHeader({ nativeEntry }) {
        if (mode === 'declared-zero') nativeEntry.size = 0;
        if (mode === 'declared-oversized') nativeEntry.size = 32 * 1024 * 1024 + 1;
      } });
      if (mode === 'signing-growth') await fs.appendFile(parcelBinding, 'signature fixture');
      if (mode === 'truncated') await fs.truncate(parcelBinding, 1);
      if (mode === 'signing-growth') assert.equal((await inspectRuntime(state.executable, platform)).parcelBinding, parcelBinding);
      else await assert.rejects(inspectRuntime(state.executable, platform), /ASAR|limit|smaller/);
    }, platform);
  }
});
