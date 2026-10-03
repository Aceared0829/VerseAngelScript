'use strict';

const path = require('node:path');
const { contains } = require('./toolchain');

function createProjectWatchers(vscode, dependencies, invalidate) {
  let disposed = false;
  const subscriptions = [], external = new Map();
  const changed = uri => {
    if (!disposed && uri.scheme === 'file' && !dependencies.outputOnly(uri.fsPath) &&
      (uri.fsPath.toLowerCase().endsWith('.vas') || path.basename(uri.fsPath) === 'vas-project.json' || dependencies.relevant(uri.fsPath))) invalidate();
  };
  function watch(pattern) {
    const watcher = vscode.workspace.createFileSystemWatcher(pattern);
    subscriptions.push(watcher, watcher.onDidCreate(changed), watcher.onDidChange(changed), watcher.onDidDelete(changed));
  }
  watch('**/*');
  dependencies.onFiles = files => {
    if (disposed) return;
    for (const file of files) {
      if (!path.isAbsolute(file) || (vscode.workspace.workspaceFolders || []).some(folder =>
        folder.uri.scheme === 'file' && contains(folder.uri.fsPath, file))) continue;
      const directory = path.dirname(file);
      if (external.has(directory)) continue;
      if (external.size >= 512) throw new Error('VAS external dependency watch limit reached; build results cannot be validated.');
      watch(new vscode.RelativePattern(directory, '*'));
      external.set(directory, true);
    }
  };
  return { dispose() {
    // An outstanding realpath observation may resolve after terminal shutdown.
    // It must not recreate watchers after this registry has been disposed.
    disposed = true;
    dependencies.onFiles = () => {};
    for (const subscription of subscriptions) subscription.dispose();
    subscriptions.length = 0; external.clear();
  } };
}

module.exports = { createProjectWatchers };
