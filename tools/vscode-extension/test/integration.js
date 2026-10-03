'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { StringDecoder } = require('node:string_decoder');
const vscode = require('vscode');

// Observe the real child-process boundary before activating the extension. These
// wrappers never replace compiler output, exit status, tasks, or VS Code APIs.
const childProcess = require('node:child_process');
const processCalls = [];
let auditSequence = 0, processAudit, fileAudit, inputAudit, lastInputObservations;
// Pass-through audit of the actual version checks. It also provides a barrier
// so the saved-notification assertion cannot race an asynchronous decision.
const { ProjectInputObservations } = require('../src/projectObservations');
const { BuildDiagnostics } = require('../src/diagnostics');
const originalPublish = BuildDiagnostics.prototype.publish;
BuildDiagnostics.prototype.publish = function(token, entries) {
  const published = originalPublish.call(this, token, entries);
  if (this === lastInputObservations?.diagnostics) inputAudit?.({ order: ++auditSequence, action: 'publish', key: token.key, published,
    entries: entries.slice(0, 8).map(([uri, items]) => ({ uri: uri.toString(), items: items.slice(0, 8).map(item =>
      ({ severity: item.severity, message: item.message.slice(0, 2048) })) })) });
  return published;
};
const originalInputChanged = ProjectInputObservations.prototype.changed;
const originalInputRegister = ProjectInputObservations.prototype.register;
const originalInputInvalidate = ProjectInputObservations.prototype.invalidate;
ProjectInputObservations.prototype.register = function(...args) {
  lastInputObservations = this;
  inputAudit?.({ order: ++auditSequence, action: 'register', key: args[0].key });
  return originalInputRegister.apply(this, args);
};
ProjectInputObservations.prototype.invalidate = function(...args) {
  inputAudit?.({ order: ++auditSequence, action: 'invalidate',
    callers: new Error().stack.split('\n').slice(2, 6).map(line => line.trim()) });
  return originalInputInvalidate.apply(this, args);
};
ProjectInputObservations.prototype.changed = function(file, options) {
  lastInputObservations = this;
  inputAudit?.({ order: ++auditSequence, action: 'changed', file, kind: options?.kind || 'change', cleanDocument: options?.documentDigest !== undefined,
    baselines: [...this.registrations.values()].filter(item => this.diagnostics.current(item.token)).slice(0, 8).map(item => ({ key: item.plan.key, exactName: Boolean(item.versions?.match(file)),
      observedInput: this.dependencies.affects(item.plan.key, file) })) });
  return originalInputChanged.call(this, file, options);
};
for (const method of ['spawn', 'execFile']) {
  const original = childProcess[method];
  childProcess[method] = function(executable, args, options, ...rest) {
    const call = { order: ++auditSequence, method, executable, args: Array.isArray(args) ? [...args] : [], options };
    if (processAudit) call.editorState = processAudit();
    processCalls.push(call);
    const child = original.call(this, executable, args, options, ...rest);
    if (executable === process.env.VAS_TEST_COMPILER && call.args.includes('--report=jsonl')) {
      // Read-only, bounded native wire audit. Never substitute or transform
      // bytes delivered to the production report consumer.
      const decoder = new StringDecoder('utf8');
      let pending = '', bytes = 0;
      call.report = [];
      child.once('close', (code, signal) => { call.nativeClose = { order: ++auditSequence, code, signal }; });
      child.stdout.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > 128 * 1024) { call.reportTruncated = true; pending = ''; return; }
        const lines = (pending + decoder.write(chunk)).split('\n');
        pending = lines.pop();
        for (const line of lines) {
          if (call.report.length >= 48) { call.reportTruncated = true; break; }
          try { call.report.push({ order: ++auditSequence, ...JSON.parse(line) }); }
          catch { call.report.push({ auditError: 'non-JSON native report line' }); }
        }
      });
    }
    return child;
  };
}

// Audit the real CustomExecution lifecycle without replacing its PTY, output,
// callback result or close code. Task reuse failures need both sides of the API.
const customExecutions = [];
const OriginalCustomExecution = vscode.CustomExecution;
vscode.CustomExecution = class extends OriginalCustomExecution {
  constructor(callback) {
    super(async (...args) => {
      const record = { id: customExecutions.length + 1, definition: args[0], opened: 0, closeRequested: 0, closeCodes: [], closeEvents: [], messages: [] };
      customExecutions.push(record);
      const terminal = await callback(...args);
      terminal.onDidClose(code => { record.closeCodes.push(code); record.closeEvents.push({ order: ++auditSequence, code }); });
      terminal.onDidWrite(text => {
        if (record.messages.length < 24 && (text.includes('VAS:') || text.includes(' : '))) record.messages.push(text.slice(0, 2048));
      });
      const open = terminal.open, close = terminal.close;
      terminal.open = function(...parameters) { record.opened++; return open.apply(this, parameters); };
      terminal.close = function(...parameters) { record.closeRequested++; return close.apply(this, parameters); };
      return terminal;
    });
  }
};

// Observe notifications on the extension's actual watchers. This neither
// substitutes a separate watcher nor changes the production event callbacks.
const originalWatcher = vscode.workspace.createFileSystemWatcher;
vscode.workspace.createFileSystemWatcher = function(...args) {
  const watcher = originalWatcher.apply(this, args);
  watcher.onDidCreate(uri => fileAudit?.('create', uri));
  watcher.onDidChange(uri => fileAudit?.('change', uri));
  watcher.onDidDelete(uri => fileAudit?.('delete', uri));
  return watcher;
};

let lastStage = 'initializing';
function stage(value) { lastStage = value; console.log(`VAS_TEST_STAGE:${process.env.VAS_TEST_MODE}:${value}`); }
async function bounded(promise, label, milliseconds = 15000) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out: ${label}; last stage: ${lastStage}`)), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}
function execute(command, ...args) {
  stage(`command: ${command}`);
  return bounded(vscode.commands.executeCommand(command, ...args), command);
}

const manifestUri = folder => vscode.Uri.joinPath(folder.uri, 'vas-project.json');
const nativeCalls = from => processCalls.slice(from).filter(call => call.executable === process.env.VAS_TEST_COMPILER);
const buildCalls = from => nativeCalls(from).filter(call => call.args.includes('--project'));

async function fixtureFor(folder) {
  return JSON.parse(await fs.readFile(path.join(folder.uri.fsPath, 'integration-project-fixture.json'), 'utf8'));
}

async function missing(file, description = file) {
  await assert.rejects(fs.stat(file), error => error.code === 'ENOENT', `${description} must not exist`);
}

async function assertNoTrap(folder) {
  const fixture = await fixtureFor(folder);
  assert.ok(!processCalls.some(call => path.resolve(call.executable) === path.resolve(folder.uri.fsPath, fixture.trap)),
    'manifest builder/runner must never even be passed to a process API');
  await missing(path.join(folder.uri.fsPath, 'tool-trap-executed'), 'trap execution marker');
}

async function withPickers(actions, start) {
  // The actual Quick Pick is displayed in the real workbench. Observe its items
  // for synchronization, then navigate/accept/cancel with built-in UI commands.
  // No synthetic selected unit, descriptor, or production test hook is used.
  const original = vscode.window.showQuickPick;
  const originalError = vscode.window.showErrorMessage;
  const pending = [...actions];
  const observed = [], interactions = [], errors = [];
  let pickerError;
  // Preserve the actual workbench notification and return value. If project
  // selection exits before its next picker, report why rather than only the
  // number of unconsumed UI actions (which hides freshness/trust failures).
  vscode.window.showErrorMessage = function(message, ...args) {
    errors.push(message);
    return originalError.call(this, message, ...args);
  };
  vscode.window.showQuickPick = function(items, options, token) {
    let focused;
    const shown = original.call(this, items, { ...options, onDidSelectItem(item) {
      focused = item;
      options?.onDidSelectItem?.(item);
    } }, token);
    interactions.push((async () => {
      const values = await items;
      const action = pending.shift();
      assert.ok(action, 'an unexpected additional Quick Pick was displayed');
      observed.push(values);
      const label = item => typeof item === 'string' ? item : item.label;
      const index = action.cancel ? -1 : values.findIndex(item => label(item) === action.label);
      if (!action.cancel) assert.notEqual(index, -1, `Quick Pick must offer ${action.label}`);
      // onDidSelectItem is the public focus notification from the real picker.
      // Wait on that RPC rather than guessing how quickly the workbench paints.
      await eventually(() => focused !== undefined, 'the real project picker to gain focus');
      assert.equal(label(focused), label(values[0]), 'picker must initially focus the first offered item');
      if (action.cancel) await execute('workbench.action.closeQuickOpen');
      else {
        for (let count = 0; count < index; count++) {
          await execute('workbench.action.quickOpenSelectNext');
          await eventually(() => label(focused) === label(values[count + 1]), 'picker navigation to move focus');
        }
        await execute('workbench.action.acceptSelectedQuickOpenItem');
      }
      const selected = await shown;
      if (action.cancel) assert.equal(selected, undefined);
      else assert.equal(label(selected), action.label, 'the real picker must return the requested choice');
    })().catch(async error => {
      pickerError = error;
      await execute('workbench.action.closeQuickOpen');
    }));
    return shown;
  };
  let timer;
  try {
    const result = await Promise.race([start(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Timed out driving the project Quick Pick UI')), 15000);
    })]);
    await bounded(Promise.all(interactions), 'complete picker interactions');
    if (pickerError) throw pickerError;
    assert.equal(pending.length, 0,
      `the command must display every required root/unit picker; remaining actions: ${JSON.stringify(pending)}; errors shown: ${JSON.stringify(errors)}`);
    return { result, observed };
  } catch (error) {
    console.error('Project picker failure:', JSON.stringify({ pending, errors }));
    throw error;
  } finally {
    clearTimeout(timer);
    vscode.window.showQuickPick = original;
    vscode.window.showErrorMessage = originalError;
    await execute('workbench.action.closeQuickOpen');
  }
}

async function projectCommand(actions) {
  return (await withPickers(actions, () => execute('vas.buildProject'))).result;
}

async function projectTask(folder, id) {
  const tasks = await bounded(vscode.tasks.fetchTasks({ type: 'vas' }), 'fetch VAS tasks');
  const task = tasks.find(item => item.definition.operation === 'buildProject' && item.definition.unit === id &&
    item.scope?.uri?.toString() === folder.uri.toString());
  assert.ok(task, `tasks.json must resolve project unit ${id} in its own workspace root`);
  return task;
}

async function projectTaskExit(folder, id) {
  const task = await projectTask(folder, id);
  const from = processCalls.length;
  const code = await taskExit(() => vscode.tasks.executeTask(task), `project task ${id}`);
  const calls = buildCalls(from);
  assert.equal(calls.length, 1, 'the selected project task must invoke the compiler exactly once');
  assert.deepEqual(calls[0].args, ['--report=jsonl', '--project', manifestUri(folder).fsPath, '--unit', id],
    'the native project command must preserve manifest and unit argument boundaries');
  assert.equal(calls[0].options.shell, false, 'project compilation must never use a shell');
  return code;
}

async function replaceText(document, text) {
  const edit = new vscode.WorkspaceEdit();
  edit.replace(document.uri, new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length)), text);
  assert.equal(await vscode.workspace.applyEdit(edit), true);
  assert.equal(document.isDirty, true);
}

async function blockedProject(actions, description, { descriptorAllowed = false } = {}) {
  const from = processCalls.length;
  let started = 0;
  const listener = vscode.tasks.onDidStartTask(() => started++);
  try {
    assert.equal(await projectCommand(actions), undefined, description);
    assert.equal(started, 0, `${description}: no task may start`);
    assert.equal(buildCalls(from).length, 0, `${description}: no native build may run`);
    if (!descriptorAllowed) assert.equal(nativeCalls(from).length, 0, `${description}: no descriptor may run`);
  } finally { listener.dispose(); }
}

async function singleRootProjectTests(folder) {
  const fixture = await fixtureFor(folder);
  const unit = fixture.manifest.compilationUnits[0];
  const output = path.join(folder.uri.fsPath, unit.output);
  await missing(output);
  await blockedProject([{ cancel: true }], 'cancelling the mandatory one-unit picker', { descriptorAllowed: true });
  await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  // Even with an include active and exactly one unit, the unit picker is required.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(
    vscode.Uri.joinPath(folder.uri, fixture.projectPaths.source, 'library 文😀.vas')));
  assert.equal(await taskExit(() => projectCommand([{ label: unit.id }]), 'single-root project command'), 0);
  assert.ok((await fs.readFile(output)).length > 0);
  await assertNoTrap(folder);
  console.log('PASS: real single-root/one-unit picker, cancellation without artifacts, active include and versioned bytecode');
}

async function legacyProjectTests(folder) {
  const fixture = await fixtureFor(folder), unit = fixture.manifest.compilationUnits[0];
  const output = path.join(folder.uri.fsPath, unit.output);
  const reference = await fs.readFile(path.join(folder.uri.fsPath, 'versioned-bytecode-reference.bin'));
  assert.ok(reference.length > 0, 'prior isolated v1 session must have produced real bytecode');
  await missing(output);
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(
    vscode.Uri.joinPath(folder.uri, fixture.projectPaths.source, 'library 文😀.vas')));
  assert.equal(await taskExit(() => projectCommand([{ label: 'main' }]), 'legacy project command'), 0);
  assert.deepEqual(await fs.readFile(output), reference, 'same-source legacy and v1 builds must be byte-for-byte equivalent');
  await assertNoTrap(folder);
  console.log('PASS: isolated legacy conversion, same-source/config native v1 bytecode equivalence and manifest tool traps');
}

async function dirtyIncludeAliasTests(folder, fixture) {
  const alias = fixture.includeAlias;
  assert.ok(alias, 'the multi-unit fixture must include a dedicated physical-alias unit');
  const uri = relative => vscode.Uri.joinPath(folder.uri, relative);
  const unit = fixture.manifest.compilationUnits.find(item => item.id === alias.unit);
  const [includedStat, aliasStat] = await Promise.all([alias.include, alias.alias].map(relative =>
    fs.stat(uri(relative).fsPath, { bigint: true })));
  assert.notEqual(includedStat.ino, 0n, 'the fixture must expose a physical file identity');
  assert.equal(includedStat.dev, aliasStat.dev);
  assert.equal(includedStat.ino, aliasStat.ino, 'the included .vas and editor .txt must be hardlinks');
  await missing(uri(unit.output).fsPath, 'the never-built alias unit output');

  const document = await vscode.workspace.openTextDocument(uri(alias.alias));
  assert.equal(document.languageId, 'plaintext', 'the alias must not be caught by the .vas editor guard');
  assert.equal(vscode.workspace.getConfiguration('task', folder.uri).get('saveBeforeRun'), 'never');
  assert.equal(vscode.workspace.getConfiguration('files', folder.uri).get('autoSave'), 'off');
  // Do not depend on the workbench's delayed opening of dirty hidden documents.
  await vscode.window.showTextDocument(document);
  await replaceText(document, document.getText() + '// dirty physical include alias\n');
  const task = await projectTask(folder, alias.unit);
  const diagnosticUris = [alias.entry, alias.include, alias.alias, unit.output].map(uri);
  const published = [];
  const recordDiagnostics = () => {
    for (const file of diagnosticUris) {
      const diagnostics = vscode.languages.getDiagnostics(file);
      if (diagnostics.length) published.push({ file: file.toString(), messages: diagnostics.map(item => item.message) });
    }
  };
  const listener = vscode.languages.onDidChangeDiagnostics(recordDiagnostics);
  const saved = [], willSave = [], taskEvents = [];
  const saveRequests = vscode.workspace.onWillSaveTextDocument(event => { if (event.document.uri.toString() === document.uri.toString()) willSave.push({ reason: event.reason, version: event.document.version }); });
  const saves = vscode.workspace.onDidSaveTextDocument(item => { if (item.uri.toString() === document.uri.toString()) saved.push({ version: item.version, dirty: item.isDirty }); });
  const taskListeners = [
    vscode.tasks.onDidStartTask(event => taskEvents.push({ event: 'start', name: event.execution.task.name, definition: event.execution.task.definition })),
    vscode.tasks.onDidEndTaskProcess(event => taskEvents.push({ event: 'processEnd', name: event.execution.task.name, exitCode: event.exitCode })),
    vscode.tasks.onDidEndTask(event => taskEvents.push({ event: 'end', name: event.execution.task.name }))
  ];
  const evidence = (stage, code, from, customFrom, eventsFrom) => {
    const value = { stage, code, dirty: document.isDirty, closed: document.isClosed, version: document.version,
      saveBeforeRun: vscode.workspace.getConfiguration('task', folder.uri).get('saveBeforeRun'), saved, willSave,
      editorTabs: vscode.window.tabGroups.all.flatMap(group => group.tabs).filter(tab => tab.input?.uri?.toString() === document.uri.toString()).length,
      processes: nativeCalls(from).map(call => ({ args: call.args, shell: call.options?.shell })),
      executions: customExecutions.slice(customFrom), taskEvents: taskEvents.slice(eventsFrom) };
    console.log('VAS dirty-alias task evidence:', JSON.stringify(value));
    return JSON.stringify(value);
  };
  try {
    // This unit has no previous observations. The real native compiler can read
    // the saved include and even write bytecode before its report identifies the
    // dirty editor alias. The task must still reject success and publication.
    const firstStart = processCalls.length, firstCustom = customExecutions.length, firstEvents = taskEvents.length;
    const firstCode = await taskExit(() => vscode.tasks.executeTask(task), 'first discovery of a dirty include alias');
    assert.notEqual(firstCode, 0, evidence('first-discovery', firstCode, firstStart, firstCustom, firstEvents));
    assert.equal(buildCalls(firstStart).length, 1, 'the first build must discover the include through the real compiler');
    assert.equal(document.isDirty, true, 'include discovery must not save the alias editor');
    assert.equal(customExecutions.length - firstCustom, 1, 'first execution must create one fresh PTY');
    assert.equal(customExecutions[firstCustom].opened, 1);
    assert.deepEqual(customExecutions[firstCustom].closeCodes, [firstCode], 'PTY and task process exit codes must agree');
    recordDiagnostics();
    assert.deepEqual(published, [], 'the rejected first discovery must not publish warning or success diagnostics');

    const repeatStart = processCalls.length, repeatCustom = customExecutions.length, repeatEvents = taskEvents.length;
    const repeatCode = await taskExit(() => vscode.tasks.executeTask(task), 'previously observed dirty include alias');
    assert.notEqual(repeatCode, 0, evidence('repeat-cached-task', repeatCode, repeatStart, repeatCustom, repeatEvents));
    assert.equal(buildCalls(repeatStart).length, 0, 'retained include observations must block the next native --project launch');
    assert.equal(document.isDirty, true, 'the repeated API task must retain the unsaved test condition');
    assert.deepEqual(saved, [], 'saveBeforeRun=never must keep the dirty-input check meaningful');
    assert.deepEqual(willSave, []);
    assert.equal(customExecutions.length - repeatCustom, 1, 'the same resolved Task must invoke a fresh CustomExecution callback');
    assert.equal(customExecutions[repeatCustom].opened, 1);
    assert.deepEqual(customExecutions[repeatCustom].closeCodes, [repeatCode]);
    recordDiagnostics();
    assert.deepEqual(published, [], 'the rejected repeat must not publish diagnostics');

    // Observations belong to their compilation unit. This dirty alias is not an
    // input to main-unit, so it cannot become a workspace-wide .txt build ban.
    assert.equal(await projectTaskExit(folder, 'main-unit'), 0);
    assert.equal(document.isDirty, true, 'an unrelated unit must leave the alias editor dirty');
    recordDiagnostics();
    assert.deepEqual(published, [], 'an unrelated build must not publish the rejected alias unit diagnostics');
  } finally { listener.dispose(); saves.dispose(); saveRequests.dispose(); for (const item of taskListeners) item.dispose(); }

  assert.equal(await document.save(), true);
  assert.equal(document.isDirty, false);
  console.log('PASS: hardlink first discovery, same-Task repeat rejection, per-unit isolation and explicit alias save');
}

async function savedIncludeAliasTests(folder, fixture) {
  const alias = fixture.includeAlias, unit = fixture.manifest.compilationUnits.find(item => item.id === alias.unit);
  const uri = relative => vscode.Uri.joinPath(folder.uri, relative);
  async function inputEvidence() {
    return Promise.all([alias.entry, alias.include, alias.alias, alias.rerunAlias].map(async relative => {
      const file = uri(relative).fsPath;
      const [bytes, stat, realpath] = await Promise.all([fs.readFile(file), fs.stat(file, { bigint: true }), fs.realpath(file)]);
      return { relative, file, realpath, dev: stat.dev.toString(), ino: stat.ino.toString(), bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex'), text: bytes.subarray(0, 2048).toString('utf8') };
    }));
  }
  const before = await inputEvidence(), fileEvents = [], diagnosticEvents = [], inputEvents = [], textEvents = [];
  const from = processCalls.length, customFrom = customExecutions.length;
  fileAudit = (kind, file) => { if (fileEvents.length < 64) fileEvents.push({ order: ++auditSequence, kind, path: file.fsPath }); };
  inputAudit = event => { if (inputEvents.length < 64) inputEvents.push(event); };
  const diagnosticListener = vscode.languages.onDidChangeDiagnostics(event => {
    for (const file of event.uris) if (diagnosticEvents.length < 64) diagnosticEvents.push({ order: ++auditSequence, uri: file.toString(),
      items: vscode.languages.getDiagnostics(file).slice(0, 8).map(item => ({ severity: item.severity, message: item.message.slice(0, 2048) })) });
  });
  const textListener = vscode.workspace.onDidChangeTextDocument(event => {
    if (event.contentChanges.length && textEvents.length < 64) textEvents.push({ order: ++auditSequence,
      file: event.document.uri.fsPath, dirty: event.document.isDirty, version: event.document.version });
  });
  const configurationListener = vscode.workspace.onDidChangeConfiguration(event => {
    if (event.affectsConfiguration('vas') && inputEvents.length < 64) inputEvents.push({ order: ++auditSequence, action: 'configuration' });
  });
  try {
    const document = await vscode.workspace.openTextDocument(uri(alias.alias));
    assert.equal(document.isDirty, false);
    assert.match(document.getText(), /dirty physical include alias/, 'the preceding session must have explicitly saved this same alias');
    await missing(uri(unit.output).fsPath);
    const notes = await vscode.workspace.openTextDocument(uri(alias.unrelated));
    await replaceText(notes, notes.getText() + 'Still unrelated and unsaved\n');
    assert.equal(await projectTaskExit(folder, alias.unit), 0, 'saving the alias must allow a genuine successful build');
    assert.ok((await fs.stat(uri(unit.output).fsPath)).size > 0);
    await eventually(() => vscode.languages.getDiagnostics(uri(alias.include)).some(item =>
      item.severity === vscode.DiagnosticSeverity.Warning), 'the saved include warning under its compiler-owned .vas identity');
    assert.equal(vscode.languages.getDiagnostics(document.uri).length, 0,
      'physical safety checks must not rewrite compiler diagnostic paths to the .txt editor alias');
    assert.equal(notes.isDirty, true, 'a dirty unrelated .txt must not block or be silently saved by the build');
    assert.equal(await notes.save(), true);

    console.log('PASS: saved alias builds and publishes its compiler-owned warning while unrelated text remains dirty');
  } finally {
    try {
      let after;
      try { after = await inputEvidence(); } catch (error) { after = { evidenceError: error.message }; }
      console.log('VAS saved-alias evidence:', JSON.stringify({ before, after, fileEvents, diagnosticEvents, inputEvents, textEvents,
        processes: nativeCalls(from).map(call => ({ order: call.order, args: call.args, report: call.report, reportTruncated: call.reportTruncated, nativeClose: call.nativeClose })),
        executions: customExecutions.slice(customFrom) }));
    } finally {
      fileAudit = undefined; inputAudit = undefined; diagnosticListener.dispose(); textListener.dispose(); configurationListener.dispose();
    }
  }
}

async function userRerunTests(folder, fixture, inputKind = 'entry') {
  const alias = fixture.includeAlias, uri = relative => vscode.Uri.joinPath(folder.uri, relative);
  assert.equal(await projectTaskExit(folder, alias.unit), 0, 'establish the real last task in this fresh host');
  // Exercise the real user rerun command, not a refetched API Task. VS Code
  // 1.96.4 Rerun Last Task saves editors itself even with saveBeforeRun=never.
  // Other hosts must still satisfy the invariant: a dirty participating input
  // cannot finish green; a host save is an observed transition, not a retry.
  const inputFile = inputKind === 'include-alias' ? alias.rerunAlias : alias.entry;
  assert.ok(inputFile, 'the rerun input must be explicitly present in the fixture');
  const entry = await vscode.workspace.openTextDocument(uri(inputFile));
  let cachedTask;
  if (inputKind === 'include-alias') {
    assert.equal(entry.languageId, 'plaintext');
    const [included, editor] = await Promise.all([alias.include, inputFile].map(file => fs.stat(uri(file).fsPath, { bigint: true })));
    assert.notEqual(included.ino, 0n);
    assert.equal(included.dev, editor.dev); assert.equal(included.ino, editor.ino, 'hot-cache recovery must exercise a real include hardlink');
    cachedTask = await projectTask(folder, alias.unit);
  }
  const entryPaths = [entry.uri.fsPath, await fs.realpath(entry.uri.fsPath)];
  const fileEvents = [], diagnosticEvents = [], inputEvents = [], textEvents = [];
  inputAudit = event => { if (inputEvents.length < 64) inputEvents.push(event); };
  const textListener = vscode.workspace.onDidChangeTextDocument(event => {
    if (event.contentChanges.length && textEvents.length < 64) textEvents.push({ order: ++auditSequence,
      path: event.document.uri.fsPath, dirty: event.document.isDirty, version: event.document.version });
  });
  const { sameFileName } = require('../src/toolchain');
  fileAudit = (kind, file) => {
    if (fileEvents.length < 64 && entryPaths.some(input => sameFileName(input, file.fsPath))) {
      fileEvents.push({ order: ++auditSequence, kind, path: file.fsPath });
    }
  };
  const diagnosticListener = vscode.languages.onDidChangeDiagnostics(event => {
    if (diagnosticEvents.length < 64 && event.uris.some(file => file.toString() === uri(alias.include).toString())) {
      diagnosticEvents.push({ order: ++auditSequence,
        warnings: vscode.languages.getDiagnostics(uri(alias.include)).filter(item => item.severity === vscode.DiagnosticSeverity.Warning).length });
    }
  });
  await vscode.window.showTextDocument(entry);
  await replaceText(entry, entry.getText() + '// user rerun input edit\n');
  if (inputKind === 'include-alias') {
    const from = processCalls.length;
    assert.notEqual(await taskExit(() => vscode.tasks.executeTask(cachedTask), 'retained include alias before user recovery'), 0);
    assert.equal(buildCalls(from).length, 0, 'the retained unit graph must reject the dirty alias before compilation');
    assert.equal(entry.isDirty, true);
    assert.equal(vscode.languages.getDiagnostics(uri(alias.include)).length, 0);
  }
  const rerunSaved = [];
  const rerunSaveListener = vscode.workspace.onDidSaveTextDocument(item => {
    if (item.uri.toString() === entry.uri.toString()) rerunSaved.push({ order: ++auditSequence, version: item.version, dirty: item.isDirty });
  });
  const rerunStart = processCalls.length, rerunCustom = customExecutions.length;
  processAudit = () => ({ dirty: entry.isDirty, saves: rerunSaved.length, version: entry.version });
  let rerunExecution;
  const rerunListener = vscode.tasks.onDidStartTask(event => {
    if (event.execution.task.definition.operation === 'buildProject' && event.execution.task.definition.unit === alias.unit &&
      event.execution.task.scope?.uri?.toString() === folder.uri.toString()) rerunExecution = event.execution;
  });
  try {
    const code = await taskExit(async () => {
      await execute('workbench.action.tasks.reRunTask');
      await eventually(() => rerunExecution !== undefined, 'the real Rerun Last Task to start the selected unit');
      return rerunExecution;
    }, 'user Rerun Last Task');
    const audit = { inputKind, code, dirty: entry.isDirty, saved: rerunSaved, fileEvents, diagnosticEvents, inputEvents, textEvents,
      processes: nativeCalls(rerunStart).map(call => ({ order: call.order, args: call.args, shell: call.options?.shell, editorState: call.editorState })),
      executions: customExecutions.slice(rerunCustom) };
    console.log('VAS user-rerun evidence:', JSON.stringify(audit));
    assert.equal(customExecutions.length - rerunCustom, 1, 'real user rerun must call a fresh CustomExecution');
    assert.deepEqual(customExecutions[rerunCustom].closeCodes, [code], JSON.stringify(audit));
    if (entry.isDirty) {
      assert.notEqual(code, 0, JSON.stringify(audit));
      assert.equal(buildCalls(rerunStart).length, 0, 'an unsaved rerun must fail before native build');
    } else {
      assert.ok(rerunSaved.length, 'clean rerun requires an observed workbench save');
      assert.equal(code, 0, JSON.stringify(audit));
      const builds = buildCalls(rerunStart);
      assert.equal(builds.length, 1, 'a saved user rerun must invoke the actual compiler');
      assert.equal(builds[0].editorState.dirty, false, 'the entry must already be clean when native compilation starts');
      assert.ok(builds[0].editorState.saves > 0 && rerunSaved.some(event => event.order < builds[0].order),
        'the observed workbench save must precede the native --project spawn');
      assert.ok(builds[0].order < customExecutions[rerunCustom].closeEvents[0].order, 'native spawn must precede successful PTY close');
      try {
        const savedOrder = Math.min(...rerunSaved.map(event => event.order));
        await eventually(() => fileEvents.some(event => event.order > savedOrder), 'this rerun saved input notification on the extension actual watcher');
        assert.ok(lastInputObservations, 'the actual task must register its production input observation barrier');
        await bounded(lastInputObservations.settle(), 'classify the saved input notification against its pre-compile baseline');
        audit.notificationAfterNativeClose = fileEvents.some(event => event.order > customExecutions[rerunCustom].closeEvents[0].order);
        assert.ok(vscode.languages.getDiagnostics(uri(alias.include)).some(item => item.severity === vscode.DiagnosticSeverity.Warning),
          'the saved native build must retain current included-file diagnostics after its actual notification is classified');
      } catch (error) {
        console.error('VAS saved-rerun diagnostic evidence:', JSON.stringify(audit));
        throw error;
      }
      if (inputKind === 'include-alias') assert.equal(vscode.languages.getDiagnostics(entry.uri).length, 0, 'physical alias recovery must not rebind compiler diagnostics');
      console.log('VAS saved-rerun diagnostic evidence:', JSON.stringify(audit));
    }
  } finally { processAudit = undefined; fileAudit = undefined; inputAudit = undefined; textListener.dispose(); diagnosticListener.dispose(); rerunListener.dispose(); rerunSaveListener.dispose(); }
  if (entry.isDirty) assert.equal(await entry.save(), true);
  console.log('PASS: real user Rerun Last Task preserves saved-input and event-order invariants');
}

async function projectTests(folder, second) {
  const fixture = await fixtureFor(folder), other = await fixtureFor(second);
  const units = fixture.manifest.compilationUnits;
  const rootPick = { label: folder.name };
  const taskDiscoveryStart = processCalls.length;
  for (const workspace of [folder, second]) await missing(path.join(workspace.uri.fsPath, '.vas'));
  const mixedTasks = await bounded(vscode.tasks.fetchTasks({ type: 'vas' }), 'enumerate mixed Current/Project tasks');
  assert.ok(mixedTasks.some(task => task.definition.operation === 'build'), 'Current task must coexist with project tasks');
  assert.ok(mixedTasks.some(task => task.definition.operation === 'buildProject'), 'Project task must coexist with Current tasks');
  const unknownUnitTask = await projectTask(folder, 'unknown-unit');
  assert.equal(nativeCalls(taskDiscoveryStart).length, 0, 'mixed task enumeration/resolution must not launch any compiler or descriptor');
  for (const workspace of [folder, second]) await missing(path.join(workspace.uri.fsPath, '.vas'), 'Current-task output directory during mixed enumeration');
  const uri = name => vscode.Uri.joinPath(folder.uri, fixture.projectPaths.source, name);
  await blockedProject([{ cancel: true }], 'cancelling the workspace picker');
  await blockedProject([rootPick, { cancel: true }], 'cancelling the multi-unit picker', { descriptorAllowed: true });
  for (const workspace of [folder, second]) {
    await missing(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output));
  }

  // The active document belongs to another root and is an include, not an entry.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(
    vscode.Uri.joinPath(second.uri, other.projectPaths.source, 'library 文😀.vas')));
  const from = processCalls.length;
  assert.equal(await taskExit(() => projectCommand([rootPick, { label: units[1].id }]), 'explicit multi-root/unit selection'), 0);
  assert.deepEqual(buildCalls(from).map(call => call.args), [
    ['--report=jsonl', '--project', manifestUri(folder).fsPath, '--unit', units[1].id]
  ]);
  await eventually(() => vscode.languages.getDiagnostics(uri('warning 文😀.vas')).some(item =>
    item.severity === vscode.DiagnosticSeverity.Warning), 'project compiler warning');
  assert.deepEqual(await fs.readdir(path.join(folder.uri.fsPath, fixture.projectPaths.output)), [path.basename(units[1].output)],
    'only the explicitly selected compilation unit may create bytecode');
  await missing(path.join(second.uri.fsPath, other.projectPaths.output));
  assert.equal(await taskExit(() => projectCommand([{ label: second.name }, { label: other.manifest.compilationUnits[0].id }]),
    'second workspace project command'), 0);
  assert.ok((await fs.stat(path.join(second.uri.fsPath, other.manifest.compilationUnits[0].output))).size > 0);

  assert.notEqual(await projectTaskExit(folder, 'broken-include'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'broken-include').output));
  const brokenInclude = await vscode.workspace.openTextDocument(uri('broken include 文😀.vas'));
  await eventually(() => vscode.languages.getDiagnostics(brokenInclude.uri).some(item => item.message === 'Must return a value' &&
    item.range.start.line === 0 && item.range.start.character === brokenInclude.lineAt(0).text.indexOf('return')),
  'project included-file UTF-16 diagnostic location');
  assert.notEqual(await projectTaskExit(folder, 'host-missing'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'host-missing').output));
  await eventually(() => vscode.languages.getDiagnostics(uri('host 文😀.vas')).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'missing host API diagnostic');
  assert.equal(await projectTaskExit(folder, 'host-present'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('host 文😀.vas')).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'successful unit with the same entry and a different host config must preserve the failing unit diagnostics');
  assert.ok(vscode.languages.getDiagnostics(brokenInclude.uri).some(item => item.message === 'Must return a value'),
    'another project unit must not clear include diagnostics');

  assert.notEqual(await projectTaskExit(folder, 'invalid-config'), 0);
  await missing(path.join(folder.uri.fsPath, units.find(unit => unit.id === 'invalid-config').output));
  const configUri = vscode.Uri.joinPath(folder.uri, fixture.projectPaths.config, 'invalid 文😀.txt');
  await eventually(() => vscode.languages.getDiagnostics(configUri).some(item => item.severity === vscode.DiagnosticSeverity.Error),
    'project host-config file diagnostic');
  const config = await vscode.workspace.openTextDocument(configUri);
  const configText = config.getText();
  const cachedTask = await projectTask(folder, 'invalid-config');
  await replaceText(config, configText + '// unsaved config\n');
  await eventually(() => vscode.languages.getDiagnostics(configUri).length === 0, 'dirty configuration invalidation');
  await blockedProject([rootPick], 'dirty selected host configuration', { descriptorAllowed: true });
  const dirtyTaskStart = processCalls.length;
  assert.notEqual(await taskExit(() => vscode.tasks.executeTask(cachedTask), 'cached task with dirty configuration'), 0);
  assert.equal(buildCalls(dirtyTaskStart).length, 0, 'a previously resolved task must recheck dirty configuration before compilation');
  await replaceText(config, configText);
  assert.equal(await config.save(), true);

  const includeText = brokenInclude.getText();
  await replaceText(brokenInclude, includeText + '// unsaved included source\n');
  await blockedProject([rootPick], 'dirty included project source');
  assert.equal(brokenInclude.isDirty, true, 'project selection must not silently save included sources');
  await replaceText(brokenInclude, includeText);
  assert.equal(await brokenInclude.save(), true);

  // Unlike Build Current File, a project command must never save a guessed entry.
  const source = await vscode.workspace.openTextDocument(uri('main 文😀.vas'));
  const sourceText = source.getText();
  await replaceText(source, sourceText + '// unsaved project source\n');
  await vscode.window.showTextDocument(source);
  await blockedProject([rootPick], 'dirty project source');
  assert.equal(source.isDirty, true);
  await replaceText(source, sourceText);
  assert.equal(await source.save(), true);

  const manifest = await vscode.workspace.openTextDocument(manifestUri(folder));
  const manifestText = manifest.getText();
  await replaceText(manifest, manifestText + ' ');
  await blockedProject([rootPick], 'dirty project manifest');
  await replaceText(manifest, manifestText);
  assert.equal(await manifest.save(), true);

  // Persisted malformed JSON and unknown schema versions are rejected by the
  // authoritative native descriptor; no build or output directory is created.
  for (const workspace of [folder, second]) {
    await fs.rm(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output), { recursive: true, force: true });
  }
  const unknownStart = processCalls.length;
  assert.notEqual(await taskExit(() => vscode.tasks.executeTask(unknownUnitTask), 'unknown compilation unit'), 0);
  assert.equal(buildCalls(unknownStart).length, 0, 'unknown units must be rejected before native compilation');
  await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  for (const invalid of ['{"schemaVersion":', JSON.stringify({ ...fixture.manifest, schemaVersion: 999 })]) {
    await replaceText(manifest, invalid);
    assert.equal(await manifest.save(), true);
    await blockedProject([rootPick], 'invalid project manifest', { descriptorAllowed: true });
    await missing(path.join(folder.uri.fsPath, fixture.projectPaths.output));
  }
  await replaceText(manifest, manifestText);
  assert.equal(await manifest.save(), true);
  await assertNoTrap(folder);
  await assertNoTrap(second);
  console.log('PASS: explicit real multi-root/multi-unit picks, Unicode/metacharacter argv, configured project tasks, native warnings/include/config diagnostics, same-entry config isolation, dirty source/config/manifest rejection, invalid/cancel no-artifact behavior');
}

async function eventually(check, description) {
  stage(`waiting: ${description}`);
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  console.error('Diagnostics at timeout:', JSON.stringify(vscode.languages.getDiagnostics().map(([uri, diagnostics]) => ({
    uri: uri.toString(), diagnostics: diagnostics.map(item => ({ message: item.message, severity: item.severity, line: item.range.start.line }))
  }))));
  throw new Error(`Timed out waiting for ${description}`);
}

async function taskExit(start, label) {
  stage(`task start: ${label}`);
  const ended = new Map(), finished = new Set(), started = new Set();
  const startListener = vscode.tasks.onDidStartTask(event => started.add(event.execution));
  // VS Code 1.96.4 terminalTaskSystem forwards CustomExecution PTY close codes
  // as ProcessEnded, then End. Require both and a real numeric exit code.
  const taskListener = vscode.tasks.onDidEndTask(event => finished.add(event.execution));
  const listener = vscode.tasks.onDidEndTaskProcess(event => ended.set(event.execution, event.exitCode));
  try {
    const execution = await bounded(Promise.resolve().then(start), `${label} task start`);
    assert.ok(execution, `${label} must start a task`);
    await eventually(() => ended.has(execution) && finished.has(execution), `${label} process and task exit`);
    const code = ended.get(execution);
    assert.ok(Number.isInteger(code), `${label} must report the real numeric exit code, got ${code}`);
    return code;
  } catch (error) {
    for (const execution of started) {
      if (execution.task.definition.type === 'vas') { try { execution.terminate(); } catch { /* The failed test still propagates. */ } }
    }
    throw error;
  } finally { listener.dispose(); taskListener.dispose(); startListener.dispose(); }
}

const commandExit = command => taskExit(() => execute(command), command);

async function run() {
  const folder = vscode.workspace.workspaceFolders[0];
  const root = folder.uri.fsPath;
  const uri = name => vscode.Uri.file(path.join(root, 'src', name));
  const mode = process.env.VAS_TEST_MODE;
  stage(`session ${mode}: activate`);
  const initialUri = ['single-root', 'legacy'].includes(mode) ?
    vscode.Uri.joinPath(folder.uri, (await fixtureFor(folder)).projectPaths.source, 'main 文😀.vas') : uri('main 文😀.vas');
  const document = await vscode.workspace.openTextDocument(initialUri);
  await vscode.window.showTextDocument(document);
  assert.equal(document.languageId, 'vas');
  const extension = vscode.extensions.getExtension('VerseAngelScript.verseangelscript-vscode');
  assert.ok(extension, 'extension must be registered');
  const activationStart = processCalls.length;
  await extension.activate();
  assert.equal(nativeCalls(activationStart).length, 0, 'extension activation must never describe or compile a project');
  const commands = await bounded(vscode.commands.getCommands(true), 'list registered commands');
  assert.ok(commands.includes('vas.buildCurrentFile'));
  assert.ok(commands.includes('vas.runCurrentFile'));
  assert.ok(commands.includes('vas.buildProject'));
  for (const workspace of vscode.workspace.workspaceFolders) await assertNoTrap(workspace);

  if (process.env.VAS_TEST_MODE === 'untrusted') {
    assert.equal(vscode.workspace.isTrusted, false, 'Restricted Mode must really be active');
    let started = 0;
    const listener = vscode.tasks.onDidStartTask(() => started++);
    try {
      assert.equal(await execute('vas.buildCurrentFile'), undefined);
      assert.equal(await execute('vas.runCurrentFile'), undefined);
      const from = processCalls.length;
      assert.equal(await execute('vas.buildProject'), undefined);
      // fetchTasks itself requests Workspace Trust in VS Code. Do not grant or
      // bypass it in a Restricted Mode test; observe actual executions instead.
      assert.equal(vscode.tasks.taskExecutions.length, 0, 'Restricted Mode must have no executable VAS task running');
      assert.equal(nativeCalls(from).length, 0, 'Restricted Mode must not invoke even project discovery');
      for (const workspace of vscode.workspace.workspaceFolders) {
        await assertNoTrap(workspace);
        await missing(path.join(workspace.uri.fsPath, (await fixtureFor(workspace)).projectPaths.output));
      }
      assert.equal(started, 0);
      await assert.rejects(fs.stat(path.join(root, '.vas')));
    } finally { listener.dispose(); }
    console.log('PASS: .vas recognition and execution blocking in Restricted Mode');
    return;
  }

  assert.equal(vscode.workspace.isTrusted, true);
  assert.ok(process.env.VAS_TEST_COMPILER && process.env.VAS_TEST_RUNNER, 'Set VAS_TEST_COMPILER and VAS_TEST_RUNNER to freshly built tools.');
  const config = vscode.workspace.getConfiguration('vas', folder.uri);
  assert.equal(config.inspect('compilerPath').globalValue, process.env.VAS_TEST_COMPILER);
  assert.equal(config.inspect('runnerPath').globalValue, process.env.VAS_TEST_RUNNER);
  const second = vscode.workspace.workspaceFolders[1];
  if (mode === 'single-root') return singleRootProjectTests(folder);
  if (mode === 'legacy') return legacyProjectTests(folder);
  if (mode === 'project') return projectTests(folder, second);
  if (mode === 'alias-dirty') return dirtyIncludeAliasTests(folder, await fixtureFor(folder));
  if (mode === 'alias-saved') return savedIncludeAliasTests(folder, await fixtureFor(folder));
  if (mode === 'user-rerun-alias') return userRerunTests(folder, await fixtureFor(folder), 'include-alias');
  if (mode === 'user-rerun') return userRerunTests(folder, await fixtureFor(folder));
  assert.equal(mode, 'current', 'every trusted mode must select a concrete isolated test scenario');
  await config.update('configFile', 'config 文😀/api 接口😀.txt', vscode.ConfigurationTarget.WorkspaceFolder);
  await config.update('outputDirectory', '.vas/build 出力😀', vscode.ConfigurationTarget.WorkspaceFolder);
  const secondConfig = vscode.workspace.getConfiguration('vas', second.uri);
  await secondConfig.update('configFile', 'config 二😀/second api 二😀.txt', vscode.ConfigurationTarget.WorkspaceFolder);
  await secondConfig.update('outputDirectory', '.vas/second 二😀', vscode.ConfigurationTarget.WorkspaceFolder);

  const edit = new vscode.WorkspaceEdit();
  edit.insert(document.uri, new vscode.Position(0, 0), '// saved by explicit build\n');
  assert.equal(await vscode.workspace.applyEdit(edit), true);
  assert.equal(document.isDirty, true);
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  assert.equal(document.isDirty, false);
  assert.match(await fs.readFile(document.uri.fsPath, 'utf8'), /saved by explicit build/);
  const outputDirectory = path.join(root, '.vas', 'build 出力😀', 'src');
  assert.deepEqual(await fs.readdir(outputDirectory), ['main 文😀.vasbc'], 'bytecode filename must preserve Unicode exactly');
  assert.ok((await fs.stat(path.join(outputDirectory, 'main 文😀.vasbc'))).size > 0);
  assert.equal(await commandExit('vas.runCurrentFile'), 0);

  const configured = (await bounded(vscode.tasks.fetchTasks({ type: 'vas' }), 'fetch VAS tasks')).find(task => task.name === 'Fixture build');
  assert.ok(configured, 'tasks.json provider must resolve the configured build');
  assert.equal(await taskExit(() => vscode.tasks.executeTask(configured), 'configured VAS task'), 0);

  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri('warning.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('warning.vas')).some(diagnostic =>
    diagnostic.severity === vscode.DiagnosticSeverity.Warning), 'compiler warning in Problems');

  const broken = await vscode.workspace.openTextDocument(uri('broken.vas'));
  await vscode.window.showTextDocument(broken);
  const included = await vscode.workspace.openTextDocument(uri('shared 文😀.vas'));
  const includeEdit = new vscode.WorkspaceEdit();
  includeEdit.insert(included.uri, new vscode.Position(0, 0), '/* unsaved */ ');
  assert.equal(await vscode.workspace.applyEdit(includeEdit), true);
  assert.equal(included.isDirty, true);
  let started = 0;
  const listener = vscode.tasks.onDidStartTask(() => started++);
  try {
    assert.equal(await execute('vas.buildCurrentFile'), undefined);
    assert.equal(started, 0, 'dirty include must block compiler execution');
  } finally { listener.dispose(); }
  assert.equal(await included.save(), true);
  assert.notEqual(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic =>
    diagnostic.severity === vscode.DiagnosticSeverity.Error && diagnostic.message === 'Must return a value' &&
    diagnostic.range.start.line === 0 && diagnostic.range.start.character === included.lineAt(0).text.indexOf('return')),
  'compiler error on the included file in the Problems view');

  // Per-entry native collections survive unrelated successful builds and runs.
  // Configuration still follows the selected workspace root.
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.joinPath(second.uri, 'main 二😀.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  const secondOutputDirectory = path.join(second.uri.fsPath, '.vas', 'second 二😀');
  assert.deepEqual(await fs.readdir(secondOutputDirectory), ['main 二😀.vasbc']);
  assert.ok((await fs.stat(path.join(secondOutputDirectory, 'main 二😀.vasbc'))).size > 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'successful builds in another workspace must preserve existing entry diagnostics');
  assert.equal(await commandExit('vas.runCurrentFile'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'run problem matchers must not clear pipe-backed build diagnostics');
  await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri('main 文😀.vas')));
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  assert.ok(vscode.languages.getDiagnostics(uri('shared 文😀.vas')).some(diagnostic => diagnostic.message === 'Must return a value'),
    'successful builds of another entry in the same root must preserve diagnostics');

  await vscode.window.showTextDocument(broken);
  assert.notEqual(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length > 0, 'rebuilt entry diagnostics');
  const fix = new vscode.WorkspaceEdit();
  fix.replace(included.uri, new vscode.Range(included.positionAt(0), included.positionAt(included.getText().length)), 'int Broken() { return 1; }\n');
  assert.equal(await vscode.workspace.applyEdit(fix), true);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length === 0, 'edited-source diagnostics to be invalidated before rebuilding');
  assert.equal(await included.save(), true);
  assert.equal(await commandExit('vas.buildCurrentFile'), 0);
  await eventually(() => vscode.languages.getDiagnostics(uri('shared 文😀.vas')).length === 0, 'stale diagnostics to clear after a clean build');
  console.log('PASS: native Unicode/space build/run paths, exact bytecode names, errors/warnings, UTF-16 include positions, multi-root configuration, per-entry isolation and edit invalidation');
}

module.exports = { run: async () => {
  try { await run(); stage('complete'); }
  catch (error) { stage(`failed: ${error.message}`); throw error; }
} };
