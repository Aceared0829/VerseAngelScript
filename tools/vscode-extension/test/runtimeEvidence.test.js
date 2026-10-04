'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { modes, recordHost, readHost, finish } = require('./runtimeEvidence');

test('native runtime evidence requires every mode and consistent actual version', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-runtime-evidence-'));
  const file = path.join(root, 'runtime.json');
  try {
    await assert.rejects(readHost(file, 'current'));
    await recordHost(file, '1.96.4', 'current');
    const identity = await readHost(file, 'current');
    await assert.rejects(readHost(file, 'project', identity));
    await recordHost(file, '1.97.0', 'project');
    await assert.rejects(readHost(file, 'project', identity));
    await recordHost(file, 'stable', 'current');
    await assert.rejects(readHost(file, 'current'));
    await assert.rejects(finish(file, identity, 'stable', modes.slice(1)));
    await assert.rejects(finish(file, identity, '1.97.0', modes));
    await finish(file, identity, 'stable', [...modes]);
    const result = JSON.parse(await fs.readFile(file, 'utf8'));
    assert.equal(result.version, '1.96.4');
    assert.equal(result.requestedVersion, 'stable');
    assert.deepEqual(result.passedModes, modes);
    assert.equal(result.mode, undefined);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
