'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { ReadmeProcessor } = require('@vscode/vsce/out/package');
const manifest = require('../package.json');

test('installed README retains exact source bytes under the pinned VSCE processor', async () => {
  const source = await fs.readFile(path.join(__dirname, '../README.md'));
  const packaged = await new ReadmeProcessor(manifest, {}).onFile({
    path: 'extension/readme.md', contents: source
  });
  // Relative links are rewritten by VSCE. Use absolute repository URLs in the
  // installed README so links work and delivery can require exact byte parity.
  assert.deepEqual(packaged.contents, source);
});
