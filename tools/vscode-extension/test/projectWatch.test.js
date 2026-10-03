'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { ProjectDependencies } = require('../src/projectReport');
const { createProjectWatchers } = require('../src/projectWatch');

function host(root) {
  const watchers = [];
  const vscode = { RelativePattern: class { constructor(base, pattern) { Object.assign(this, { base, pattern }); } }, workspace: {
    workspaceFolders: [{ uri: { scheme: 'file', fsPath: root } }],
    createFileSystemWatcher(pattern) {
      const watcher = { pattern, onDidCreate(callback) { this.changed = callback; return { dispose() {} }; },
        onDidChange() { return { dispose() {} }; }, onDidDelete() { return { dispose() {} }; }, dispose() { this.disposed = true; } };
      watchers.push(watcher); return watcher;
    }
  } };
  return { vscode, watchers };
}

test('watchers invalidate observed inputs, ignore output-only .vas artifacts, and stop after disposal', () => {
  const root = path.resolve('/project'), { vscode, watchers } = host(root), deps = new ProjectDependencies();
  let invalidations = 0;
  const registrar = createProjectWatchers(vscode, deps, () => invalidations++);
  const plan = { key: 'main', project: path.join(root, 'vas-project.json'), source: path.join(root, 'main.vas'),
    config: path.join(root, 'host.txt'), output: path.join(root, 'out', 'artifact.vas') };
  deps.update(plan, { observed: new Map() }, true);
  watchers[0].changed({ scheme: 'file', fsPath: plan.config }); assert.equal(invalidations, 1);
  watchers[0].changed({ scheme: 'file', fsPath: plan.output }); assert.equal(invalidations, 1);
  deps.onFiles([path.resolve('/outside/a.vas'), path.resolve('/outside/b.vas')]);
  assert.equal(watchers.length, 2, 'one subscription per outside directory');
  registrar.dispose(); deps.onFiles([path.resolve('/different/c.vas')]);
  assert.equal(watchers.length, 2); assert.ok(watchers.every(watcher => watcher.disposed));
  watchers[0].changed({ scheme: 'file', fsPath: plan.source }); assert.equal(invalidations, 1);
});

test('pending observed alias resolution cannot create watchers after extension disposal', { skip: process.platform === 'win32' }, async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-watch-dispose-'));
  try {
    const workspace = path.join(root, 'workspace'), outside = path.join(root, 'outside');
    await fs.mkdir(workspace); await fs.mkdir(outside);
    const physical = path.join(outside, 'shared.vas'), lexical = path.join(workspace, 'link.vas');
    await fs.writeFile(physical, 'void main() {}'); await fs.symlink(physical, lexical);
    const { vscode, watchers } = host(workspace), deps = new ProjectDependencies();
    const registrar = createProjectWatchers(vscode, deps, () => {});
    const plan = { key: 'unit', project: path.join(workspace, 'vas-project.json'), config: path.join(workspace, 'host.txt'), source: lexical };
    const pending = deps.observe(plan, { key: lexical, file: lexical });
    registrar.dispose(); await pending;
    assert.equal(watchers.length, 1); assert.equal(watchers[0].disposed, true);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
