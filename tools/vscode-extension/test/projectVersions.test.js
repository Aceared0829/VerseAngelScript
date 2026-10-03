'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { captureVersions, documentDigest, readVersion, MAX_FILES, MAX_FILE_BYTES, MAX_TOTAL_BYTES } = require('../src/projectVersions');

async function fixture(run) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-versions-'));
  try { await run(root); } finally { await fs.rm(root, { recursive: true, force: true }); }
}

test('saved bytes survive delayed notifications and touches without refreshing the immutable baseline', () => fixture(async root => {
  const file = path.join(root, 'main.vas');
  await fs.writeFile(file, 'void main() {}\n');
  const snapshot = await captureVersions([file, file]);
  assert.ok(Object.isFrozen(snapshot)); assert.ok(Object.isFrozen(snapshot.match(file)));
  assert.equal(await snapshot.check(file), true);
  await fs.utimes(file, new Date(), new Date(Date.now() + 1000));
  assert.equal(await snapshot.check(file), true, 'same bytes and identity tolerate metadata-only changes');
  await snapshot.checkAll();
  await fs.writeFile(file, 'void main() { }\n');
  assert.equal(await snapshot.check(file), false);
  await assert.rejects(snapshot.checkAll(), /changed/);
  assert.equal(await snapshot.check(file), false, 'a failed check must not learn the changed bytes');
}));

test('atomic replacement with identical bytes invalidates the captured physical identity', () => fixture(async root => {
  const file = path.join(root, 'main.vas'), replacement = path.join(root, 'new.vas');
  await fs.writeFile(file, 'same');
  const snapshot = await captureVersions([file]);
  await fs.writeFile(replacement, 'same'); await fs.rename(replacement, file);
  assert.equal(await snapshot.check(file), false);
  await assert.rejects(snapshot.checkAll(), /changed/);
}));

test('physical hardlink aliases match, equal-content unrelated files never do, and shared reads can be reused', () => fixture(async root => {
  const file = path.join(root, 'main.vas'), alias = path.join(root, 'editor.txt'), unrelated = path.join(root, 'other.vas');
  await fs.writeFile(file, 'same'); await fs.link(file, alias); await fs.writeFile(unrelated, 'same');
  const snapshot = await captureVersions([file]);
  assert.equal(snapshot.match(alias), undefined, 'a hardlink has no lexically inferred alias');
  const actual = await readVersion(alias);
  assert.equal(snapshot.lookup(alias, actual).length, 1);
  assert.ok(Object.isFrozen(snapshot.lookup(alias, actual)));
  assert.equal(snapshot.matches(alias, actual), true); assert.equal(await snapshot.check(alias), true);
  assert.equal(await snapshot.check(unrelated), false);
  assert.equal(snapshot.lookup(unrelated, await readVersion(unrelated)).length, 0);
  await fs.writeFile(alias, 'changed');
  assert.equal(await snapshot.check(alias), false); await assert.rejects(snapshot.checkAll(), /changed/);
}));

test('symlink aliases are proven at capture and a retarget fails even with the same bytes and inode',
  { skip: process.platform === 'win32' }, () => fixture(async root => {
    const target = path.join(root, 'target.txt'), sameInode = path.join(root, 'same-inode.txt'), link = path.join(root, 'main.vas');
    await fs.writeFile(target, 'same'); await fs.link(target, sameInode); await fs.symlink(target, link);
    const snapshot = await captureVersions([link]);
    assert.equal(snapshot.match(await fs.realpath(target)), snapshot.match(link), 'only the proven canonical target is indexed as a name alias');
    assert.equal(snapshot.match(link).file, link, 'canonical aliases must not rewrite the original input identity');
    assert.equal(await snapshot.check(target), true);
    await fs.unlink(link); await fs.symlink(sameInode, link);
    assert.equal(await snapshot.check(link), false); await assert.rejects(snapshot.checkAll(), /changed/);
  }));

test('missing and nonregular inputs remain honestly uncomparable while allowing native file-level errors', () => fixture(async root => {
  const missing = path.join(root, 'missing.vas'), directory = path.join(root, 'directory');
  await fs.mkdir(directory);
  const snapshot = await captureVersions([missing, missing, directory]);
  assert.equal(await snapshot.check(missing), false); assert.equal(await snapshot.check(directory), false);
  await snapshot.checkAll();
  await fs.writeFile(missing, 'now readable');
  assert.equal(await snapshot.check(missing), false); await assert.rejects(snapshot.checkAll(), /changed/);
}));

test('FIFOs and device inputs do not hang or establish comparable versions',
  { skip: process.platform === 'win32' }, () => fixture(async root => {
    const fifo = path.join(root, 'pipe.vas'); require('node:child_process').execFileSync('mkfifo', [fifo]);
    const snapshot = await captureVersions([fifo, '/dev/zero']);
    assert.equal(await snapshot.check(fifo), false); assert.equal(await snapshot.check('/dev/zero'), false);
    await snapshot.checkAll();
  }));

test('document comparison strips only the disk BOM, preserves Unicode and line endings, and rejects invalid UTF-8', () => fixture(async root => {
  const file = path.join(root, 'main.vas'), invalid = path.join(root, 'invalid.vas');
  const text = '/* 文😀 */\r\nvoid main() {}\r\n';
  await fs.writeFile(file, '\ufeff' + text); await fs.writeFile(invalid, Buffer.from([0xff, 0xfe]));
  const snapshot = await captureVersions([file, invalid]);
  assert.equal(snapshot.match(file).documentDigest, documentDigest(text));
  assert.equal(await snapshot.check(file, undefined, documentDigest(text)), true);
  assert.equal(await snapshot.check(file, undefined, documentDigest(text.replaceAll('\r\n', '\n'))), false);
  assert.notEqual(documentDigest('\ufeff' + text), documentDigest(text), 'getText is exact, without additional normalization');
  assert.equal(snapshot.match(invalid).documentDigest, undefined);
  assert.equal(await snapshot.check(invalid), true, 'binary freshness still works');
  assert.equal(await snapshot.check(invalid, undefined, documentDigest('\ufffd\ufffd')), false);
  assert.equal(documentDigest('\ud800'), undefined);
  assert.equal(documentDigest('a'.repeat(MAX_FILE_BYTES + 1)), undefined);
}));

test('capture and checkAll enforce bounded file, per-input and total-byte resources', () => fixture(async root => {
  await assert.rejects(captureVersions(Array.from({ length: MAX_FILES + 1 }, (_, i) => path.join(root, `${i}.vas`))), /file validation limit/);
  const tooLarge = path.join(root, 'large.vas');
  const largeHandle = await fs.open(tooLarge, 'w'); await largeHandle.truncate(MAX_FILE_BYTES + 1); await largeHandle.close();
  await assert.rejects(captureVersions([tooLarge]), /byte validation limit/);
  const files = [];
  for (let i = 0; i < MAX_TOTAL_BYTES / MAX_FILE_BYTES + 1; i++) {
    const file = path.join(root, `${i}.vas`); const handle = await fs.open(file, 'w'); await handle.truncate(MAX_FILE_BYTES); await handle.close(); files.push(file);
  }
  await assert.rejects(captureVersions(files), /aggregate validation limit/);
  const snapshot = await captureVersions([files[0]]);
  // Windows append access permits appending but not SetEndOfFile/ftruncate.
  const handle = await fs.open(files[0], 'r+');
  try { await handle.truncate(MAX_FILE_BYTES + 1); } finally { await handle.close(); }
  await assert.rejects(snapshot.checkAll(), /byte validation limit/);
  await assert.rejects(readVersion(files[1], undefined, { bytes: MAX_TOTAL_BYTES - 1 }), /aggregate validation limit/);
}));

test('cancellation is honored before work and between filesystem operations', () => fixture(async root => {
  const file = path.join(root, 'main.vas'); await fs.writeFile(file, 'source');
  await assert.rejects(captureVersions([file], { cancelled: true }), /cancelled/);
  const snapshot = await captureVersions([file]);
  await assert.rejects(snapshot.check(file, { cancelled: true }), /cancelled/);
  await assert.rejects(snapshot.checkAll({ cancelled: true }), /cancelled/);
  const original = fs.realpath, cancel = { cancelled: false };
  try {
    fs.realpath = async (...args) => { const result = await original(...args); cancel.cancelled = true; return result; };
    await assert.rejects(readVersion(file, cancel), /cancelled/);
  } finally { fs.realpath = original; }
}));

test('a pathname replacement during the opened-handle read cannot become a trusted baseline', () => fixture(async root => {
  const file = path.join(root, 'main.vas'), replacement = path.join(root, 'new.vas'), displaced = path.join(root, 'displaced.vas');
  await fs.writeFile(file, 'same'); await fs.writeFile(replacement, 'same');
  const identity = async name => { const stat = await fs.stat(name, { bigint: true }); return `${stat.dev}:${stat.ino}`; };
  const oldIdentity = await identity(file), newIdentity = await identity(replacement);
  assert.notEqual(oldIdentity, newIdentity, 'the fixture needs two genuinely distinct files');
  const original = fs.realpath; let calls = 0, replaced = false;
  try {
    fs.realpath = async (...args) => {
      const result = await original(...args);
      if (++calls === 1) {
        try {
          // Move the open old file aside first. Windows permits renaming a
          // FILE_SHARE_DELETE handle, but replacing an open target via
          // MoveFileEx can fail. Both renames operate on absent destinations.
          await fs.rename(file, displaced);
          await fs.rename(replacement, file);
          assert.equal(await identity(displaced), oldIdentity);
          assert.equal(await identity(file), newIdentity);
          replaced = true;
        } catch (error) {
          // An intervention failure is a fixture failure, not an input I/O
          // error which readVersion may honestly represent as unreadable.
          throw new Error('Could not perform the opened-file replacement fixture', { cause: error });
        }
      }
      return result;
    };
    await assert.rejects(captureVersions([file]), /changed/);
    assert.equal(replaced, true, 'the regression must actually replace the pathname while its original handle is open');
  } finally { fs.realpath = original; }
}));

test('an availability error after opening cannot establish a readable baseline or authorize a later readable input', () => fixture(async root => {
  const file = path.join(root, 'main.vas'); await fs.writeFile(file, 'source');
  const original = fs.realpath;
  let snapshot;
  try {
    fs.realpath = async () => { throw Object.assign(new Error('input became unavailable'), { code: 'EPERM' }); };
    snapshot = await captureVersions([file]);
    assert.equal(snapshot.match(file).kind, 'unreadable');
    assert.equal(await snapshot.check(file), false, 'an unreadable state is never a comparable saved version');
  } finally { fs.realpath = original; }
  assert.equal(snapshot.matches(file, await readVersion(file)), false);
  await assert.rejects(snapshot.checkAll(), /changed/, 'project freshness must reject the availability transition before launch');
}));

test('a same-inode write during a read is rejected rather than captured as a newer baseline', () => fixture(async root => {
  const file = path.join(root, 'main.vas'); await fs.writeFile(file, 'before');
  const originalOpen = fs.open; let wrote = false;
  try {
    fs.open = async (...args) => {
      const handle = await originalOpen(...args), originalRead = handle.read.bind(handle);
      handle.read = async (...readArgs) => {
        if (!wrote) {
          wrote = true;
          await fs.writeFile(file, 'edited');
          await fs.utimes(file, new Date(), new Date(Date.now() + 2000));
        }
        return originalRead(...readArgs);
      };
      return handle;
    };
    await assert.rejects(captureVersions([file]), /changed/);
  } finally { fs.open = originalOpen; }
}));

test('a shared observation cannot be substituted for the event pathname being checked', () => fixture(async root => {
  const file = path.join(root, 'main.vas'), other = path.join(root, 'other.vas');
  await fs.writeFile(file, 'same'); await fs.writeFile(other, 'same');
  const snapshot = await captureVersions([file]), actual = await readVersion(file);
  assert.equal(snapshot.matches(other, actual), false);
  assert.equal(snapshot.matches(file, undefined), false);
}));
