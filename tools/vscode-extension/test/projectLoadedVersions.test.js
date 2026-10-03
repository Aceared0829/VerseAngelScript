'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { captureVersions, readVersion, MAX_FILE_BYTES, MAX_TOTAL_BYTES, MAX_FILES } = require('../src/projectVersions');

const proof = bytes => ({ version: 1, algorithm: 'sha256', byteLength: bytes.length, digest: createHash('sha256').update(bytes).digest('hex') });
async function fixture(run) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-loaded-versions-'));
  try { await run(root); } finally { await fs.rm(root, { recursive: true, force: true }); }
}

test('compiler proof admits exact BOM/CRLF/Unicode/NUL and invalid UTF-8 bytes without rewriting source identity', async () => fixture(async root => {
  for (const bytes of [Buffer.from('\ufeff/* 文😀 */\r\n\0'), Buffer.from([0xff, 0, 0xfe]), Buffer.alloc(0)]) {
    const file = path.join(root, `input-${bytes.length}.vas`); await fs.writeFile(file, bytes);
    const versions = await captureVersions([]);
    await versions.admitLoaded(file, proof(bytes));
    const actual = await readVersion(file);
    assert.equal(versions.matches(file, actual), true);
    assert.equal(versions.match(file).file, file);
    assert.equal(versions.match(file).byteLength, bytes.length);
    assert.ok(Object.isFrozen(versions.match(file)));
    await versions.checkAll();
    if (bytes[0] === 0xff) assert.equal(versions.match(file).documentDigest, undefined);
  }
}));

test('admission rejects changed, missing and nonregular sources and never manufactures a post-hoc proof', async () => fixture(async root => {
  const bytes = Buffer.from('compiler actually read these bytes'), file = path.join(root, 'main.vas');
  const versions = await captureVersions([]);
  await fs.writeFile(file, 'new bytes');
  await assert.rejects(versions.admitLoaded(file, proof(bytes)), /no longer matches/);
  assert.equal(versions.match(file), undefined);
  await fs.unlink(file);
  await assert.rejects(versions.admitLoaded(file, proof(bytes)), /no longer matches/);
  await fs.mkdir(file);
  await assert.rejects(versions.admitLoaded(file, proof(bytes)), /no longer matches/);
  assert.equal(versions.match(file), undefined);
  for (const invalid of [{}, { ...proof(bytes), version: 2 }, { ...proof(bytes), digest: 'A'.repeat(64) }, { ...proof(bytes), byteLength: -1 }]) {
    await assert.rejects(versions.admitLoaded(file, invalid), /Invalid compiler-loaded/);
  }
}));

test('compiler proof cannot overwrite a pre-compile baseline, and later same-byte replacement still invalidates admission', async () => fixture(async root => {
  const file = path.join(root, 'source.vas'), before = Buffer.from('before'), after = Buffer.from('after');
  await fs.writeFile(file, before);
  const original = await captureVersions([file]);
  await fs.writeFile(file, after);
  await assert.rejects(original.admitLoaded(file, proof(after)), /input changed/);
  assert.equal(original.match(file).digest, proof(before).digest);
  const admitted = await captureVersions([]);
  await admitted.admitLoaded(file, proof(after));
  const replacement = path.join(root, 'replacement.vas'); await fs.writeFile(replacement, after); await fs.rename(replacement, file);
  await assert.rejects(admitted.checkAll(), /input changed/);
}));

test('admitted source aliases require physical identity, never just equal content', async () => fixture(async root => {
  const file = path.join(root, 'source.vas'), alias = path.join(root, 'editor.txt'), unrelated = path.join(root, 'same-bytes.txt');
  const bytes = Buffer.from('same'); await fs.writeFile(file, bytes); await fs.link(file, alias); await fs.writeFile(unrelated, bytes);
  const versions = await captureVersions([]); await versions.admitLoaded(file, proof(bytes));
  assert.equal(await versions.related(alias), true);
  assert.equal(await versions.check(alias), true);
  assert.equal(await versions.related(unrelated), false);
  assert.equal(await versions.check(unrelated), false);
}));

test('loaded-source proof admission is bounded by byte count, total bytes, file count and cancellation', async () => fixture(async root => {
  const versions = await captureVersions([]), file = path.join(root, 'large.vas');
  await assert.rejects(versions.admitLoaded(file, { ...proof(Buffer.alloc(0)), byteLength: MAX_FILE_BYTES + 1 }), /byte validation limit/);
  const bytes = Buffer.alloc(MAX_FILE_BYTES), digest = proof(bytes);
  for (let index = 0; index < MAX_TOTAL_BYTES / MAX_FILE_BYTES; index++) {
    const input = path.join(root, `${index}.vas`); await fs.writeFile(input, bytes); await versions.admitLoaded(input, digest);
  }
  await fs.writeFile(file, 'x');
  await assert.rejects(versions.admitLoaded(file, proof(Buffer.from('x'))), /aggregate validation limit/);
  const full = await captureVersions(Array.from({ length: MAX_FILES }, (_, index) => path.join(root, `missing-${index}.vas`)));
  await assert.rejects(full.admitLoaded(file, proof(Buffer.from('x'))), /file validation limit/);
  await assert.rejects(versions.admitLoaded(file, proof(Buffer.from('x')), { cancelled: true }), /cancelled/);
}));
