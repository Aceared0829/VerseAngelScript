'use strict';

const fs = require('node:fs/promises');
const assert = require('node:assert/strict');

const modes = Object.freeze(['current', 'project', 'alias-dirty', 'alias-saved', 'user-rerun-alias',
  'user-rerun', 'single-root', 'legacy', 'untrusted']);

// The native host writes its own API version only after its assertions pass.
async function recordHost(file, version, mode) {
  if (file) await fs.writeFile(file, JSON.stringify({ version, platform: process.platform, arch: process.arch, mode }) + '\n');
}

async function readHost(file, mode, previous) {
  const current = JSON.parse(await fs.readFile(file, 'utf8'));
  assert.match(current.version, /^\d+\.\d+\.\d+$/);
  assert.equal(current.mode, mode);
  assert.equal(current.platform, process.platform);
  assert.equal(current.arch, process.arch);
  const identity = { version: current.version, platform: current.platform, arch: current.arch };
  if (previous) assert.deepEqual(identity, previous);
  return identity;
}

async function finish(file, identity, requestedVersion, passedModes) {
  assert.deepEqual(passedModes, modes);
  assert.ok(identity);
  if (requestedVersion !== 'stable') assert.equal(identity.version, requestedVersion);
  await fs.writeFile(file, JSON.stringify({ ...identity, requestedVersion, passedModes }) + '\n');
}

module.exports = { modes, recordHost, readHost, finish };
