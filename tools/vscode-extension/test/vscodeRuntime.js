'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');

const VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const COMMIT = /^[0-9a-f]{40}$/;
const MAX_JSON = 1024 * 1024;
const MAX_ASAR_HEADER = 1024 * 1024;
const MAX_NATIVE = 32 * 1024 * 1024;
const MAX_ENTRYPOINT = 64 * 1024;

async function regularFile(filename, owner, maxBytes) {
  const stat = await fs.lstat(filename);
  assert.ok(stat.isFile(), `Runtime member must be a regular file: ${filename}`);
  const canonicalOwner = await fs.realpath(owner), canonicalFile = await fs.realpath(filename);
  const relative = path.relative(canonicalOwner, canonicalFile);
  assert.ok(relative && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative),
    `Runtime member must belong to the selected runtime: ${filename}`);
  assert.equal(canonicalFile, path.resolve(canonicalOwner, path.relative(owner, filename)),
    `Runtime member must retain its exact package path: ${filename}`);
  if (maxBytes !== undefined) assert.ok(stat.size > 0 && stat.size <= maxBytes, `Runtime member exceeds size limit: ${filename}`);
  return stat;
}

async function readJson(filename, owner) {
  await regularFile(filename, owner, MAX_JSON);
  return JSON.parse(await fs.readFile(filename, 'utf8'));
}

async function exists(filename) {
  try { await fs.lstat(filename); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

async function applicationPath(executable, platform) {
  const root = path.dirname(executable);
  if (platform === 'darwin') return path.resolve(root, '../Resources/app');
  const legacy = path.join(root, 'resources/app');
  if (platform !== 'win32') return legacy;
  // Official Windows archives can put resources under the build commit's first
  // ten hex digits while Code.exe stays at the archive root. Never search beyond
  // those immediate directories or pick the first of several possible builds.
  const entries = await fs.readdir(root, { withFileTypes: true });
  assert.ok(entries.length <= 256, 'Too many entries in the VS Code runtime root');
  const candidates = [];
  if (await exists(legacy)) candidates.push(legacy);
  for (const entry of entries) {
    if (/^[0-9a-f]{10}$/.test(entry.name) && await exists(path.join(root, entry.name, 'resources/app'))) {
      assert.ok(entry.isDirectory(), 'Versioned VS Code runtime directory must not be a symlink');
      candidates.push(path.join(root, entry.name, 'resources/app'));
    }
  }
  assert.equal(candidates.length, 1, 'Expected exactly one VS Code application directory');
  return candidates[0];
}

function verifyIntegrity(entry, bytes) {
  if (!Object.hasOwn(entry, 'integrity')) return;
  const value = entry.integrity, hash = bytes => createHash('sha256').update(bytes).digest('hex');
  assert.ok(value && value.algorithm === 'SHA256' && typeof value.hash === 'string' && /^[0-9a-f]{64}$/.test(value.hash) &&
    Number.isSafeInteger(value.blockSize) && value.blockSize > 0 && value.blockSize <= MAX_NATIVE &&
    Array.isArray(value.blocks) && value.blocks.length <= 8192 && value.blocks.length === Math.ceil(bytes.length / value.blockSize),
  'Unsupported or inconsistent ASAR integrity metadata');
  assert.equal(hash(bytes), value.hash, 'ASAR file integrity mismatch');
  for (let i = 0; i < value.blocks.length; i++) {
    assert.equal(typeof value.blocks[i], 'string', 'Invalid ASAR block integrity');
    assert.match(value.blocks[i], /^[0-9a-f]{64}$/);
    assert.equal(hash(bytes.subarray(i * value.blockSize, (i + 1) * value.blockSize)), value.blocks[i],
      'ASAR block integrity mismatch');
  }
}

async function archivedParcel(app, platform, allowAbsentWatcher = false) {
  // Metadata-only ASAR reader. VS Code's build/lib/asar.ts writes two Chromium
  // pickles followed by file bytes. Read only a bounded JSON header and the exact
  // watcher package entry; never unpack a tree or execute archived JavaScript.
  const archive = path.join(app, 'node_modules.asar');
  const stat = await regularFile(archive, app);
  const handle = await fs.open(archive, 'r');
  try {
    async function read(size, position) {
      assert.ok(Number.isSafeInteger(size) && size > 0 && Number.isSafeInteger(position) && position >= 0 &&
        size <= stat.size - position, 'ASAR member is outside the archive');
      const bytes = Buffer.alloc(size);
      const result = await handle.read(bytes, 0, size, position);
      assert.equal(result.bytesRead, size, 'Truncated ASAR member');
      return bytes;
    }
    const prefix = await read(16, 0);
    const headerSize = prefix.readUInt32LE(4), jsonSize = prefix.readUInt32LE(12);
    assert.ok(prefix.readUInt32LE(0) === 4 && headerSize >= 8 && headerSize <= MAX_ASAR_HEADER &&
      headerSize % 4 === 0 && prefix.readUInt32LE(8) === headerSize - 4 &&
      jsonSize > 0 && jsonSize <= headerSize - 8 && headerSize - 8 - jsonSize < 4,
    'Invalid or oversized ASAR header');
    const dataOffset = 8 + headerSize;
    assert.ok(dataOffset <= stat.size, 'Truncated ASAR header');
    const header = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(await read(jsonSize, 16)));
    function member(relative) {
      let entry = header;
      for (const part of relative.split('/')) {
        assert.ok(entry && typeof entry === 'object' && !Object.hasOwn(entry, 'link') &&
          entry.files && Object.hasOwn(entry.files, part), `Missing or linked ASAR member: ${relative}`);
        entry = entry.files[part];
      }
      assert.ok(entry && typeof entry === 'object' && !Object.hasOwn(entry, 'link') && !entry.files &&
        Number.isSafeInteger(entry.size) && entry.size > 0, `Invalid ASAR file: ${relative}`);
      return entry;
    }
    const parcelScope = header.files?.['@parcel'];
    const nestedScope = parcelScope?.files?.watcher?.files?.node_modules?.files?.['@parcel'];
    for (const scope of [parcelScope, nestedScope]) {
      assert.ok(!Object.keys(scope?.files || {}).some(name => name.startsWith('watcher-')),
        'Unsupported preferred Parcel platform binding in ASAR');
    }
    if (allowAbsentWatcher && !Object.hasOwn(parcelScope?.files || {}, 'watcher')) return undefined;
    const indexEntry = member('@parcel/watcher/index.js');
    assert.ok(!indexEntry.unpacked && indexEntry.size <= MAX_ENTRYPOINT &&
      typeof indexEntry.offset === 'string' && /^(0|[1-9][0-9]*)$/.test(indexEntry.offset) &&
      Number.isSafeInteger(Number(indexEntry.offset)) && Number(indexEntry.offset) <= stat.size - dataOffset - indexEntry.size,
    'Invalid archived Parcel entry point');
    if (Object.hasOwn(indexEntry, 'integrity')) verifyIntegrity(indexEntry, await read(indexEntry.size, dataOffset + Number(indexEntry.offset)));
    const packageEntry = member('@parcel/watcher/package.json');
    assert.ok(!packageEntry.unpacked && packageEntry.size <= MAX_JSON &&
      typeof packageEntry.offset === 'string' && /^(0|[1-9][0-9]*)$/.test(packageEntry.offset),
    'Invalid archived Parcel package metadata');
    const offset = Number(packageEntry.offset);
    assert.ok(Number.isSafeInteger(offset) && Number.isSafeInteger(dataOffset + offset), 'Invalid ASAR package offset');
    const packageBytes = await read(packageEntry.size, dataOffset + offset);
    verifyIntegrity(packageEntry, packageBytes);
    const parcelPackage = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(packageBytes));
    const nativeEntry = member('@parcel/watcher/build/Release/watcher.node');
    assert.equal(nativeEntry.unpacked, true, 'Bundled Parcel native binding must be unpacked');
    const parcelBinding = path.join(app, 'node_modules.asar.unpacked/@parcel/watcher/build/Release/watcher.node');
    const nativeStat = await regularFile(parcelBinding, app, MAX_NATIVE);
    assert.ok(nativeEntry.size <= MAX_NATIVE, 'Bundled Parcel ASAR size exceeds limit');
    // The shipped 1.140 macOS/Windows addons grow after ASAR construction:
    // https://github.com/microsoft/vscode/blob/1.140.0/build/azure-pipelines/darwin/codesign.ts
    // https://github.com/microsoft/vscode/blob/1.140.0/build/gulpfile.vscode.ts (patchWin32DependenciesTask)
    // ASAR size describes pre-signing bytes there, not the final signed file.
    // Bound both sizes and reject truncation; supplied integrity still applies
    // to the final bytes. This is not cryptographic signature verification.
    if (platform === 'darwin' || platform === 'win32') {
      assert.ok(nativeStat.size >= nativeEntry.size, 'Bundled Parcel native file is smaller than its ASAR entry');
    } else {
      assert.equal(nativeStat.size, nativeEntry.size, 'Bundled Parcel native size must match its ASAR entry');
    }
    if (Object.hasOwn(nativeEntry, 'integrity')) verifyIntegrity(nativeEntry, await fs.readFile(parcelBinding));
    return { parcel: path.join(archive, '@parcel/watcher'), parcelBinding, parcelPackage, parcelLayout: 'asar' };
  } finally { await handle.close(); }
}

async function inspectRuntime(executable, platform = process.platform) {
  assert.ok(typeof executable === 'string' && path.isAbsolute(executable) && !/[\r\n]/.test(executable),
    'Set VAS_TEST_VSCODE_EXECUTABLE to the official downloaded test runtime executable');
  const backend = { darwin: 'fs-events', win32: 'windows', linux: 'inotify' }[platform];
  assert.ok(backend, `Unsupported VS Code watcher test platform: ${platform}`);
  await regularFile(executable, path.dirname(executable));
  const app = await applicationPath(executable, platform);
  const owner = platform === 'darwin' ? path.resolve(path.dirname(executable), '..') : path.dirname(executable);
  const [codePackage, product] = await Promise.all([
    readJson(path.join(app, 'package.json'), owner), readJson(path.join(app, 'product.json'), owner)
  ]);
  assert.ok(typeof codePackage.version === 'string' && VERSION.test(codePackage.version) && !/[\r\n]/.test(codePackage.version),
    'VS Code runtime must identify its exact version');
  assert.equal(product.version, codePackage.version, 'VS Code product/package versions must agree');
  assert.ok(typeof product.commit === 'string' && COMMIT.test(product.commit) && !/[\r\n]/.test(product.commit),
    'VS Code runtime must identify its exact commit');
  if (platform === 'win32' && app !== path.join(path.dirname(executable), 'resources/app')) {
    assert.equal(path.basename(path.dirname(path.dirname(app))), product.commit.slice(0, 10),
      'Versioned VS Code directory must match its exact product commit');
  }
  const looseParcel = path.join(app, 'node_modules/@parcel/watcher');
  let bundled;
  if (await exists(path.join(looseParcel, 'package.json'))) {
    if (await exists(path.join(app, 'node_modules.asar'))) {
      // 1.96 ships an unrelated, small ASAR alongside its loose watcher. Reject
      // only an actual competing watcher package, not the unrelated archive.
      assert.equal(await archivedParcel(app, platform, true), undefined, 'Ambiguous loose and archived Parcel layouts');
    }
    for (const scope of [path.dirname(looseParcel), path.join(looseParcel, 'node_modules/@parcel')]) {
      if (await exists(scope)) {
        const entries = await fs.readdir(scope);
        assert.ok(entries.length <= 256 && !entries.some(name => name.startsWith('watcher-')),
          'Unsupported preferred Parcel platform binding');
      }
    }
    await regularFile(path.join(looseParcel, 'index.js'), app, MAX_ENTRYPOINT);
    const parcelBinding = path.join(looseParcel, 'build/Release/watcher.node');
    await regularFile(parcelBinding, app, MAX_NATIVE);
    bundled = { parcel: looseParcel, parcelBinding, parcelPackage: await readJson(path.join(looseParcel, 'package.json'), app),
      parcelLayout: 'loose' };
  } else {
    bundled = await archivedParcel(app, platform);
  }
  assert.equal(bundled.parcelPackage.name, '@parcel/watcher');
  assert.ok(typeof bundled.parcelPackage.version === 'string' && VERSION.test(bundled.parcelPackage.version) &&
    !/[\r\n]/.test(bundled.parcelPackage.version), 'Bundled Parcel must identify its exact version');
  assert.equal(bundled.parcelPackage.main, 'index.js', 'Unsupported bundled Parcel package entry point');
  const { parcel, parcelBinding, parcelLayout } = bundled;
  return { executable, app, version: codePackage.version, commit: product.commit, platform,
    parcel, parcelBinding, parcelLayout, parcelVersion: bundled.parcelPackage.version, backend };
}

async function subscribeRuntimeParcel(runtime, directory, callback, loadBinding = require) {
  const checked = await inspectRuntime(runtime.executable);
  for (const key of ['app', 'version', 'commit', 'platform', 'parcel', 'parcelBinding', 'parcelLayout', 'parcelVersion', 'backend']) {
    assert.equal(checked[key], runtime[key], `Prepared runtime ${key} changed before Parcel subscription`);
  }
  assert.equal(typeof directory, 'string');
  assert.ok(path.isAbsolute(directory), 'Parcel subscription directory must be absolute');
  assert.equal(typeof callback, 'function');
  const binding = loadBinding(checked.parcelBinding);
  for (const name of ['subscribe', 'unsubscribe', 'writeSnapshot', 'getEventsSince']) {
    assert.equal(typeof binding[name], 'function', `Bundled Parcel native export is missing: ${name}`);
  }
  // Exactly the no-ignore path of Parcel 2.1 index.js / 2.5 wrapper.js. This
  // fixture only passes an explicit backend, so no glob normalization applies.
  // Preserve the native callback, options, async readiness and rejection behavior.
  const root = path.resolve(directory), options = { backend: checked.backend };
  await binding.subscribe(root, callback, options);
  return { unsubscribe() { return binding.unsubscribe(root, callback, options); } };
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

module.exports = { inspectRuntime, configuredRuntime, prepareRuntime, runtimeForIntegration, subscribeRuntimeParcel };
