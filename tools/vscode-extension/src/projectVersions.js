'use strict';

const fs = require('node:fs/promises');
const { constants } = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { TextDecoder } = require('node:util');

const MAX_FILES = 4096;
const MAX_FILE_BYTES = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES = 64 * 1024 * 1024;
const unreadableCodes = new Set(['ENOENT', 'EACCES', 'EPERM', 'ENOTDIR', 'EISDIR', 'ENXIO', 'ELOOP']);
const hash = () => createHash('sha256');
// These are lookup names, not normalized compiler identities. A differently
// spelled path must prove physical identity before it can match a baseline.
const key = file => file;
const physicalKey = value => value.ino !== '0' ? `${value.dev}:${value.ino}` : undefined;
const version = stat => [stat.dev, stat.ino, stat.size, stat.mtimeNs, stat.ctimeNs].join(':');
function ready(cancel) { if (cancel?.cancelled) throw new Error('Project selection cancelled.'); }
function changed(file) { return new Error(`VAS input changed during build validation. Build Project again: ${file}`); }
function validPath(file) { return typeof file === 'string' && path.isAbsolute(file) && !file.includes('\0'); }

/** Hash the exact text returned by VS Code; do not normalize its line endings. */
function documentDigest(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text, 'utf8') > MAX_FILE_BYTES ||
    /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(text)) return undefined;
  return hash().update(text, 'utf8').digest('hex');
}

/** A stable read of this pathname, never a learned baseline for a completed build. */
async function readVersion(file, cancel, budget = { bytes: 0 }) {
  let handle;
  try {
    ready(cancel);
    handle = await fs.open(file, constants.O_RDONLY | (constants.O_NONBLOCK || 0));
    ready(cancel);
    const before = await handle.stat({ bigint: true });
    ready(cancel);
    if (!before.isFile()) return Object.freeze({ file, kind: 'unreadable', state: 'nonregular' });
    if (before.size > BigInt(MAX_FILE_BYTES)) throw new Error(`VAS input exceeds the ${MAX_FILE_BYTES} byte validation limit: ${file}`);
    if (before.size > BigInt(MAX_TOTAL_BYTES - budget.bytes)) throw new Error(`VAS inputs exceed the ${MAX_TOTAL_BYTES} byte aggregate validation limit.`);
    const realpath = await fs.realpath(file);
    ready(cancel);
    const pathnameBefore = await fs.stat(file, { bigint: true });
    ready(cancel);
    if (version(before) !== version(pathnameBefore)) throw changed(file);
    const binary = hash(), document = hash(), decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
    const buffer = Buffer.alloc(64 * 1024);
    let bytes = 0, utf8Valid = true, firstText = true;
    function textDigest(text) {
      if (!text.length) return;
      if (firstText) { firstText = false; if (text.startsWith('\uFEFF')) text = text.slice(1); }
      document.update(text, 'utf8');
    }
    while (true) {
      ready(cancel);
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      ready(cancel);
      if (!bytesRead) break;
      bytes += bytesRead; budget.bytes += bytesRead;
      if (bytes > MAX_FILE_BYTES) throw new Error(`VAS input exceeds the ${MAX_FILE_BYTES} byte validation limit: ${file}`);
      if (budget.bytes > MAX_TOTAL_BYTES) throw new Error(`VAS inputs exceed the ${MAX_TOTAL_BYTES} byte aggregate validation limit.`);
      const chunk = buffer.subarray(0, bytesRead);
      binary.update(chunk);
      if (utf8Valid) { try { textDigest(decoder.decode(chunk, { stream: true })); } catch { utf8Valid = false; } }
    }
    if (utf8Valid) { try { textDigest(decoder.decode()); } catch { utf8Valid = false; } }
    const after = await handle.stat({ bigint: true });
    ready(cancel);
    const pathnameAfter = await fs.stat(file, { bigint: true });
    ready(cancel);
    const realpathAfter = await fs.realpath(file);
    ready(cancel);
    if (version(before) !== version(after) || version(before) !== version(pathnameAfter) ||
      key(realpath) !== key(realpathAfter) || BigInt(bytes) !== before.size) throw changed(file);
    return Object.freeze({ file, kind: 'readable', realpath, dev: before.dev.toString(), ino: before.ino.toString(),
      digest: binary.digest('hex'), documentDigest: utf8Valid ? document.digest('hex') : undefined });
  } catch (error) {
    ready(cancel);
    if (unreadableCodes.has(error.code)) return Object.freeze({ file, kind: 'unreadable', state: error.code });
    throw error;
  } finally { await handle?.close(); }
}

function sameVersion(expected, actual, alias = false) {
  return actual?.kind === 'readable' && expected?.kind === 'readable' && expected.dev === actual.dev &&
    expected.ino === actual.ino && expected.digest === actual.digest &&
    (alias || key(expected.realpath) === key(actual.realpath));
}

/** Only call before the native compiler starts. The returned baselines never change. */
async function captureVersions(files, cancel) {
  const unique = new Map();
  for (const file of files) {
    ready(cancel);
    if (!validPath(file)) throw new Error('VAS input validation requires an absolute filesystem path.');
    unique.set(key(file), file);
    if (unique.size > MAX_FILES) throw new Error(`VAS inputs exceed the ${MAX_FILES} file validation limit.`);
  }
  const records = [], names = new Map(), physical = new Map(), budget = { bytes: 0 };
  function add(map, name, record) {
    const previous = map.get(name) || [];
    if (previous.some(item => !sameVersion(item, record, true) &&
      !(item.kind === 'unreadable' && record.kind === 'unreadable' && item.state === record.state))) throw changed(record.file);
    if (!previous.includes(record)) previous.push(record);
    map.set(name, previous);
  }
  for (const file of unique.values()) {
    const record = await readVersion(file, cancel, budget);
    records.push(record);
    for (const name of record.kind === 'readable' ? [file, record.realpath] : [file]) {
      add(names, key(name), record);
    }
    if (record.kind === 'readable') {
      const identity = physicalKey(record);
      if (identity) add(physical, identity, record);
    }
  }
  ready(cancel);
  const match = file => validPath(file) ? names.get(key(file))?.[0] : undefined;
  const lookup = (file, actual) => {
    if (!validPath(file)) return Object.freeze([]);
    return Object.freeze([...new Set([...(names.get(key(file)) || []),
      ...(actual?.kind === 'readable' ? physical.get(physicalKey(actual)) || [] : [])])]);
  };
  const matches = (file, actual, expectedDocumentDigest) => {
    if (actual?.file !== file) return false;
    const candidates = lookup(file, actual), named = validPath(file) ? names.get(key(file)) || [] : [];
    return candidates.length > 0 && candidates.every(expected =>
      (expectedDocumentDigest === undefined || (expected.documentDigest !== undefined && expected.documentDigest === expectedDocumentDigest)) &&
      sameVersion(expected, actual, !named.includes(expected)));
  };
  return Object.freeze({
    match, lookup, matches,
    async check(file, cancel, expectedDocumentDigest) {
      ready(cancel);
      if (!validPath(file)) return false;
      let expected = match(file);
      if (!expected) {
        // Equal bytes alone never authorize an unrelated path. Hardlink aliases
        // require a nonzero device/inode identity, checked again by readVersion.
        try {
          const stat = await fs.stat(file, { bigint: true });
          ready(cancel);
          if (!stat.isFile()) return false;
          expected = physical.get(physicalKey({ dev: stat.dev.toString(), ino: stat.ino.toString() }))?.[0];
        } catch (error) { ready(cancel); if (unreadableCodes.has(error.code)) return false; throw error; }
      }
      if (!expected || expected.kind !== 'readable' ||
        (expectedDocumentDigest !== undefined && expected.documentDigest !== expectedDocumentDigest)) return false;
      const actual = await readVersion(file, cancel);
      ready(cancel);
      return matches(file, actual, expectedDocumentDigest);
    },
    async checkAll(cancel) {
      const budget = { bytes: 0 };
      for (const expected of records) {
        ready(cancel);
        const actual = await readVersion(expected.file, cancel, budget);
        if (expected.kind === 'unreadable') {
          if (actual.kind !== 'unreadable' || actual.state !== expected.state) throw changed(expected.file);
        } else if (!sameVersion(expected, actual)) throw changed(expected.file);
      }
      ready(cancel);
    }
  });
}

module.exports = { captureVersions, documentDigest, readVersion, MAX_FILES, MAX_FILE_BYTES, MAX_TOTAL_BYTES };
