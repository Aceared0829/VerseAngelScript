'use strict';

const fs = require('node:fs/promises');
const { constants } = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { TextDecoder } = require('node:util');
const { spawn } = require('node:child_process');
const { resolvePath, contains, sameFileName } = require('./toolchain');

function compilerPath(configuration, paths = path) {
  // machine-scoped values must not gain authority from workspace settings.
  const inspected = configuration.inspect?.('compilerPath');
  const value = inspected ? inspected.globalValue : configuration.get('compilerPath');
  if (typeof value !== 'string' || !paths.isAbsolute(value) || value.includes('\0') || value.includes('${') ||
    (paths === path.win32 && paths.extname(value).toLowerCase() !== '.exe')) {
    throw new Error('Set vas.compilerPath to an absolute trusted native compiler in User or Remote settings.');
  }
  return value;
}

function projectRequest(definition, folder, executable) {
  if (!folder || typeof folder !== 'object' || folder.uri.scheme !== 'file') throw new Error('Open a filesystem workspace folder for Build Project.');
  const root = folder.uri.fsPath;
  const project = resolvePath(definition.project, root, 'VAS project');
  if (project !== path.join(root, 'vas-project.json')) throw new Error('VAS project tasks currently require the workspace root vas-project.json.');
  if (typeof definition.unit !== 'string' || !/^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}$/.test(definition.unit)) throw new Error('Select an explicit VAS compilation unit.');
  return { project, root, executable, unit: definition.unit };
}

function validateDescriptor(value, code) {
  const object = item => item && typeof item === 'object' && !Array.isArray(item);
  const text = item => typeof item === 'string' && !item.includes('\0') &&
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(item);
  const absolute = item => text(item) && path.isAbsolute(item);
  if (!object(value) || value.protocol !== 'vas-project' || value.version !== 1 || typeof value.success !== 'boolean' ||
    !Array.isArray(value.compilationUnits) || !Array.isArray(value.errors) || !Array.isArray(value.warnings) ||
    !Number.isInteger(code) || code < 0 || (code === 0) !== value.success) throw new Error('Invalid or unsupported VAS project descriptor.');
  if (!value.success) {
    if (value.compilationUnits.length || !value.errors.length || value.errors.some(error => !object(error) || !text(error.message) || !text(error.field))) throw new Error('Invalid failed VAS project descriptor.');
    throw new Error(value.errors.map(error => `${error.field || 'project'}: ${error.message || 'invalid manifest'}`).join('\n'));
  }
  if (!absolute(value.project) || !absolute(value.projectRoot) || typeof value.legacyProject !== 'boolean' ||
    (value.legacyProject ? value.projectSchemaVersion !== null : value.projectSchemaVersion !== 1) ||
    !(value.name === null || text(value.name)) || value.errors.length || value.compilationUnits.length < 1 || value.compilationUnits.length > 256 ||
    value.warnings.some(warning => !object(warning) || !text(warning.code) || !text(warning.message))) throw new Error('Invalid VAS project descriptor metadata.');
  const ids = new Set();
  for (const unit of value.compilationUnits) {
    if (!object(unit) || !text(unit.id) || !/^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}$/.test(unit.id) || ids.has(unit.id) ||
      !absolute(unit.entry) || !object(unit.hostApi) || !absolute(unit.hostApi.config) || !absolute(unit.output)) throw new Error('Invalid VAS compilation unit descriptor.');
    ids.add(unit.id);
  }
  return value;
}

function cancellation() {
  const callbacks = new Set();
  return { cancelled: false, listen(callback) { callbacks.add(callback); return () => callbacks.delete(callback); },
    cancel() { this.cancelled = true; for (const callback of callbacks) callback(); callbacks.clear(); } };
}

function describeProject(executable, project, cancel = cancellation(), spawnProcess = spawn) {
  return new Promise((resolve, reject) => {
    let child, timer, killTimer, detach, settled = false, failed, size = 0;
    const chunks = [];
    const stop = error => {
      failed ||= error;
      if (child) { child.kill(); killTimer ||= setTimeout(() => child.kill('SIGKILL'), 2000); killTimer.unref(); }
    };
    const finish = code => {
      if (settled) return;
      settled = true;
      clearTimeout(timer); clearTimeout(killTimer); detach?.();
      try {
        if (cancel.cancelled) throw new Error('Project selection cancelled.');
        if (failed) throw failed;
        const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(Buffer.concat(chunks));
        if (!text.endsWith('\n') || text.startsWith('\uFEFF') || text.slice(0, -1).includes('\n')) throw new Error('Truncated or malformed VAS project descriptor.');
        resolve(validateDescriptor(JSON.parse(text), code));
      } catch (error) { reject(error); }
    };
    if (cancel.cancelled) return finish(130);
    try {
      child = spawnProcess(executable, ['--describe-project=json', project], {
        cwd: path.dirname(project), shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
      });
      detach = cancel.listen(() => stop(new Error('Project selection cancelled.')));
      child.stdout.on('data', chunk => {
        size += chunk.length;
        if (size > 16 * 1024 * 1024) stop(new Error('VAS project descriptor exceeded the response limit.'));
        else if (!failed) chunks.push(chunk);
      });
      // Drain but do not treat stderr as a descriptor protocol channel.
      child.stderr.on('data', () => {});
      for (const pipe of [child.stdout, child.stderr]) pipe.on('error', error => stop(error));
      child.once('error', error => { failed = error; });
      child.once('close', finish);
      timer = setTimeout(() => stop(new Error('VAS project descriptor timed out.')), 30000);
      timer.unref();
    } catch (error) { failed = error; finish(1); }
  });
}

async function fileVersion(file) {
  try {
    const stat = await fs.stat(file, { bigint: true });
    if (!stat.isFile()) return 'unreadable:nonregular';
    return [stat.dev, stat.ino, stat.size, stat.mtimeNs, stat.ctimeNs].join(':');
  } catch (error) { if (['ENOENT', 'EACCES', 'EPERM', 'ENOTDIR'].includes(error.code)) return `unreadable:${error.code}`; throw error; }
}

async function fingerprint(file, limit = 16 * 1024 * 1024, cancel) {
  let handle;
  try {
    if (cancel?.cancelled) throw new Error('Project selection cancelled.');
    // O_NONBLOCK prevents a swapped-in FIFO from hanging before fstat. The
    // actual opened handle must be regular, not just a preceding path probe.
    handle = await fs.open(file, constants.O_RDONLY | (constants.O_NONBLOCK || 0));
    const stat = await handle.stat();
    if (!stat.isFile()) return 'unreadable:nonregular';
    if (stat.size > limit) throw new Error(`VAS input exceeds the ${limit} byte client validation limit: ${file}`);
    const hash = createHash('sha256'), buffer = Buffer.alloc(64 * 1024);
    let size = 0;
    while (true) {
      if (cancel?.cancelled) throw new Error('Project selection cancelled.');
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      if (!bytesRead) break;
      size += bytesRead;
      if (size > limit) throw new Error(`VAS input exceeds the ${limit} byte client validation limit: ${file}`);
      hash.update(buffer.subarray(0, bytesRead));
    }
    return hash.digest('hex');
  } catch (error) {
    if (['ENOENT', 'EISDIR', 'EACCES', 'EPERM', 'ENXIO', 'ENOTDIR'].includes(error.code)) return `unreadable:${error.code}`;
    throw error;
  } finally { await handle?.close(); }
}

async function readSourceText(file, cancel, limit = 16 * 1024 * 1024) {
  let handle;
  try {
    handle = await fs.open(file, constants.O_RDONLY | (constants.O_NONBLOCK || 0));
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > limit) return undefined;
    const chunks = [], buffer = Buffer.alloc(64 * 1024);
    let size = 0;
    while (!cancel?.cancelled) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      if (!bytesRead) return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(Buffer.concat(chunks));
      size += bytesRead;
      if (size > limit) return undefined;
      chunks.push(Buffer.from(buffer.subarray(0, bytesRead)));
    }
  } catch { /* Missing/nonregular/invalid UTF-8 sources cannot provide positions. */ }
  finally { await handle?.close(); }
  return undefined;
}

async function requireProjectReady(vscode, folder, project, configs = [], cancel) {
  function ready() {
    if (cancel?.cancelled) throw new Error('Project selection cancelled.');
    if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before building a VAS project.');
    if (folder.uri.scheme !== 'file' || !contains(folder.uri.fsPath, project)) throw new Error('Open a filesystem workspace folder for Build Project.');
  }
  ready();
  const dirty = vscode.workspace.textDocuments.filter(document => document.isDirty && document.uri.scheme === 'file');
  // Most builds have no dirty editors. Do not stat/realpath an entire include
  // graph when there is no unsaved document to compare it with.
  if (!dirty.length) return;
  const files = [...new Set([project, ...configs])];
  for (const document of dirty) {
    if (document.uri.fsPath.toLowerCase().endsWith('.vas') || files.some(file => sameFileName(document.uri.fsPath, file))) {
      throw new Error(`Save the VAS project input before building: ${document.uri.fsPath}`);
    }
  }
  async function physical(file) {
    ready();
    try {
      const stat = await fs.stat(file, { bigint: true });
      ready();
      const real = await fs.realpath(file);
      return { dev: stat.dev, ino: stat.ino, real };
    } catch (error) { ready(); return undefined; }
  }
  // A dirty editor may name a symlink or hardlink target. Only these dirty
  // documents require physical matching. Sequential probes bound concurrency
  // and cancellation is checked between every filesystem operation.
  const identities = [];
  for (const document of dirty) {
    const identity = await physical(document.uri.fsPath);
    if (identity) identities.push({ ...identity, document });
  }
  if (identities.length) for (const file of files) {
    const input = await physical(file);
    if (!input) continue;
    const alias = identities.find(item => sameFileName(input.real, item.real) ||
      (item.ino !== 0n && input.dev === item.dev && input.ino === item.ino));
    if (alias) throw new Error(`Save the VAS project input before building: ${alias.document.uri.fsPath}`);
  }
  ready();
}

async function snapshotDescriptor({ vscode, folder, project, executable, cancel }) {
  await requireProjectReady(vscode, folder, project, [], cancel);
  const before = await fingerprint(project, 1024 * 1024, cancel);
  if (before.startsWith('unreadable:')) throw new Error(`VAS project manifest is not a file: ${project}`);
  if (!(await fs.stat(executable)).isFile()) throw new Error(`VAS compiler is not a file: ${executable}`);
  await requireProjectReady(vscode, folder, project, [], cancel);
  const descriptor = await describeProject(executable, project, cancel);
  if (!sameFileName(descriptor.project, project) || !sameFileName(descriptor.projectRoot, folder.uri.fsPath)) {
    throw new Error('The compiler described a different VAS project.');
  }
  await requireProjectReady(vscode, folder, project, descriptor.compilationUnits.flatMap(unit => [unit.hostApi.config, unit.entry]), cancel);
  if (before !== await fingerprint(project, 1024 * 1024, cancel)) throw new Error('VAS manifest changed during selection. Build Project again.');
  const configVersions = new Map();
  for (const unit of descriptor.compilationUnits) {
    if (!configVersions.has(unit.hostApi.config)) configVersions.set(unit.hostApi.config, await fileVersion(unit.hostApi.config));
  }
  return { descriptor, manifestFingerprint: before, configVersions };
}

async function projectPlan(request, selected, { vscode, folder, cancel, dependencies }) {
  if (compilerPath(vscode.workspace.getConfiguration('vas', folder.uri)) !== request.executable) throw new Error('VAS compiler setting changed. Build Project again.');
  const snapshot = await snapshotDescriptor({ vscode, folder, project: request.project, executable: request.executable, cancel });
  const { descriptor } = snapshot;
  const unit = descriptor.compilationUnits.find(unit => unit.id === request.unit);
  if (!unit) throw new Error(`Unknown VAS compilation unit: ${request.unit}`);
  if (selected && (selected.manifestFingerprint !== snapshot.manifestFingerprint || JSON.stringify(selected.descriptor) !== JSON.stringify(descriptor))) {
    throw new Error('VAS project changed after selection. Build Project again.');
  }
  if (selected && selected.configVersions.get(unit.hostApi.config) !== await fileVersion(unit.hostApi.config)) throw new Error('VAS host configuration changed after selection. Build Project again.');
  const configFingerprint = await fingerprint(unit.hostApi.config, 16 * 1024 * 1024, cancel);
  await requireProjectReady(vscode, folder, request.project, [unit.hostApi.config, unit.entry], cancel);
  const plan = { executable: request.executable, project: descriptor.project, source: unit.entry, config: unit.hostApi.config,
    output: unit.output, unit: unit.id, cwd: descriptor.projectRoot, projectSchemaVersion: descriptor.projectSchemaVersion,
    legacyProject: descriptor.legacyProject, key: JSON.stringify([descriptor.project, unit.id]),
    args: ['--report=jsonl', '--project', request.project, '--unit', unit.id] };
  plan.watchInputs = await Promise.all([plan.project, plan.source, plan.config].map(file => fs.realpath(file).catch(() => file)));
  // Keep exact compiler paths here; physical identity is checked only when
  // comparing these participating inputs with dirty editor documents.
  const observedInputs = new Set();
  plan.observeInput = file => observedInputs.add(file);
  const inputs = () => [...new Set([plan.config, plan.source,
    ...(dependencies?.inputFiles(plan.key) || []), ...observedInputs])];
  plan.checkFresh = async () => {
    await requireProjectReady(vscode, folder, request.project, inputs(), cancel);
    if (compilerPath(vscode.workspace.getConfiguration('vas', folder.uri)) !== plan.executable ||
      await fingerprint(request.project, 1024 * 1024, cancel) !== snapshot.manifestFingerprint || await fingerprint(plan.config, 16 * 1024 * 1024, cancel) !== configFingerprint) {
      throw new Error('VAS manifest, host configuration or compiler setting changed. Build Project again.');
    }
    await requireProjectReady(vscode, folder, request.project, inputs(), cancel);
  };
  await plan.checkFresh();
  return plan;
}

module.exports = { compilerPath, projectRequest, validateDescriptor, describeProject, fingerprint, fileVersion, readSourceText, cancellation,
  requireProjectReady, snapshotDescriptor, projectPlan };
