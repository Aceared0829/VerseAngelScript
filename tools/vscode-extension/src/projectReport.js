'use strict';

const path = require('node:path');
const fs = require('node:fs/promises');
const { TextDecoder } = require('node:util');
const { spawn } = require('node:child_process');
const { sameFileName } = require('./toolchain');

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const string = value => typeof value === 'string' && !value.includes('\0') &&
  !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value);
const nullablePath = value => value === null || string(value);
const integer = Number.isSafeInteger;
function requireValue(condition, message) { if (!condition) throw new Error(`Invalid VAS report: ${message}`); }

// Keep exact compiler identities. A lossy display string is never a filesystem path.
function identity(record, field) {
  if (record[field] === null) return undefined;
  return record.invalidUtf8Fields.includes(field) ? { key: JSON.stringify(['bytes', record.rawBytes[field]]), file: undefined } :
    { key: JSON.stringify(['text', record[field]]), file: record[field] };
}

/** Strict bounded UTF-8 JSONL transport, independent of terminal rendering. */
class ProjectReport {
  constructor(plan, event = () => {}) {
    this.plan = plan;
    this.event = event;
    this.decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
    this.pending = '';
    this.bytes = 0;
    this.sequence = 0;
    this.attempts = new Set();
    this.observed = new Map();
    this.invalidSources = new Set();
  }
  write(chunk) {
    if (this.error) return;
    try {
      this.bytes += chunk.length;
      requireValue(this.bytes <= 64 * 1024 * 1024, 'total report limit exceeded');
      this.accept(this.decoder.decode(chunk, { stream: true }));
    }
    catch (error) { this.error = error; }
  }
  accept(text) {
    this.pending += text;
    let newline;
    while ((newline = this.pending.indexOf('\n')) !== -1) {
      requireValue(newline <= 1024 * 1024, 'oversized record');
      const line = this.pending.slice(0, newline);
      this.pending = this.pending.slice(newline + 1);
      requireValue(line.length > 0 && !line.includes('\r') && !line.startsWith('\uFEFF'), 'invalid line framing');
      this.record(JSON.parse(line));
    }
    requireValue(this.pending.length <= 1024 * 1024, 'oversized record');
  }
  record(record) {
    requireValue(this.sequence < 100000, 'event limit exceeded');
    requireValue(object(record) && record.protocol === 'vasbuild' && record.version === 1, 'unsupported protocol/version');
    requireValue(!this.result && integer(record.seq) && record.seq === this.sequence + 1, 'sequence or terminal record');
    requireValue(Array.isArray(record.invalidUtf8Fields) && object(record.rawBytes), 'missing encoding metadata');
    const fields = record.invalidUtf8Fields;
    requireValue(new Set(fields).size === fields.length && fields.every(field => string(field) &&
      string(record[field]) && typeof record.rawBytes[field] === 'string' && /^(?:[0-9a-f]{2})+$/.test(record.rawBytes[field])) &&
      Object.keys(record.rawBytes).length === fields.length, 'invalid raw-byte metadata');
    requireValue(this.sequence !== 0 || record.type === 'start', 'missing start');
    switch (record.type) {
      case 'start': {
        requireValue(this.sequence === 0 && record.compiler === 'vasbuild' && string(record.compilerVersion) &&
          record.positionEncoding === 'utf-8-bytes' && record.positionBase === 1 && nullablePath(record.cwd), 'invalid start');
        // This process is tied to a validated descriptor, never an active editor.
        for (const [field, expected] of Object.entries({ project: this.plan.project, unit: this.plan.unit,
          entry: this.plan.source, config: this.plan.config, output: this.plan.output,
          projectSchemaVersion: this.plan.projectSchemaVersion, legacyProject: this.plan.legacyProject })) {
          requireValue(record[field] === expected && !fields.includes(field), `changed ${field} identity`);
        }
        requireValue(!fields.includes('cwd') && record.cwd !== null && path.isAbsolute(record.cwd), 'unusable cwd');
        this.start = record;
        break;
      }
      case 'diagnostic':
        requireValue(['error', 'warning', 'information'].includes(record.severity) && string(record.message) &&
          string(record.section) && integer(record.row) && integer(record.column), 'invalid diagnostic');
        break;
      case 'section_loaded': {
        requireValue(string(record.section) && typeof record.utf8Valid === 'boolean', 'invalid loaded section');
        const item = identity(record, 'section');
        this.observed.set(item.key, item);
        if (!record.utf8Valid) this.invalidSources.add(item.key);
        break;
      }
      case 'include_attempt': {
        requireValue(string(record.from) && string(record.requested) && nullablePath(record.resolved), 'invalid include attempt');
        this.attempts.add(record.seq);
        const item = identity(record, 'resolved');
        if (item) this.observed.set(item.key, item);
        break;
      }
      case 'include_result':
        requireValue(integer(record.attemptSeq) && this.attempts.delete(record.attemptSeq) &&
          ['loaded', 'skipped', 'failed', 'rejected'].includes(record.status), 'invalid include result');
        break;
      case 'result':
        requireValue(typeof record.success === 'boolean' && typeof record.dependenciesComplete === 'boolean' &&
          ['arguments', 'engine', 'config', 'load', 'compile', 'output'].includes(record.phase) &&
          this.attempts.size === 0 && (!record.success || (record.phase === 'output' && record.dependenciesComplete)), 'invalid result');
        this.result = record;
        break;
      default: throw new Error('Invalid VAS report: unknown record type');
    }
    this.sequence = record.seq;
    this.event(record);
  }
  end(code) {
    if (!this.error) {
      try {
        this.accept(this.decoder.decode());
        requireValue(this.pending.length === 0 && this.start && this.result, 'truncated report');
        requireValue(integer(code) && code >= 0 && (code === 0) === this.result.success, 'exit status disagrees with result');
      } catch (error) { this.error = error; }
    }
    return this.error ? 1 : code;
  }
}

function diagnosticRecord(record, report) {
  if (record.type !== 'diagnostic' || !record.section || record.invalidUtf8Fields.includes('section')) return undefined;
  const file = path.isAbsolute(record.section) ? record.section : path.resolve(report.start.cwd, record.section);
  // Empty/engine sections are not attached to a convenient active editor.
  const known = [report.plan.source, report.plan.config, report.plan.output, report.plan.project];
  if (!path.isAbsolute(record.section) && !known.some(item => path.resolve(item) === file) &&
    ![...report.observed.values()].some(item => item.file && path.resolve(item.file) === file)) return undefined;
  const invalid = report.invalidSources.has(identity(record, 'section').key);
  return { file, row: invalid ? 0 : record.row, column: invalid ? 0 : record.column,
    severity: record.severity === 'error' ? 'ERR' : record.severity === 'warning' ? 'WARN' : 'INFO', message: record.message };
}

class ProjectBuildProcess {
  constructor(plan, callbacks, spawnProcess = spawn) {
    Object.assign(this, { plan, callbacks, spawnProcess });
    this.observationWork = Promise.resolve();
    this.report = new ProjectReport(plan, record => {
      if (this.cancelled || this.finished) return;
      if (record.type === 'section_loaded' || record.type === 'include_attempt') {
        const item = identity(record, record.type === 'section_loaded' ? 'section' : 'resolved');
        if (item) this.observationWork = this.observationWork.then(async () => {
          if (!this.cancelled && !this.report.error) await callbacks.observe?.(item);
        }).catch(error => {
          this.report.error ||= error;
          this.failed = true;
          this.child?.kill();
          this.killTimer ||= setTimeout(() => { if (!this.finished) this.child?.kill('SIGKILL'); }, 2000);
          this.killTimer.unref();
        });
      }
      if (record.type === 'diagnostic') {
        callbacks.output(`${record.section || 'vasbuild'} (${record.row}, ${record.column}) : ${record.severity}: ${record.message}\n`);
        const diagnostic = diagnosticRecord(record, this.report);
        if (diagnostic) callbacks.diagnostic(diagnostic);
      }
    });
  }
  start() {
    if (this.started || this.finished) return;
    this.started = true;
    if (this.cancelled) return this.finish(130);
    try {
      this.child = this.spawnProcess(this.plan.executable, this.plan.args, {
        cwd: this.plan.cwd, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
      });
      const stderr = new TextDecoder('utf-8');
      this.child.stdout.on('data', chunk => {
        if (this.cancelled) return;
        this.report.write(chunk);
        if (this.report.error && !this.failed) {
          this.failed = true;
          this.child.kill();
          this.killTimer = setTimeout(() => { if (!this.finished) this.child.kill('SIGKILL'); }, 2000);
          this.killTimer.unref();
        }
      });
      this.child.stderr.on('data', chunk => this.callbacks.output(stderr.decode(chunk, { stream: true })));
      this.child.stderr.once('end', () => this.callbacks.output(stderr.decode()));
      for (const pipe of [this.child.stdout, this.child.stderr]) pipe.on('error', error => { this.failed = true; this.report.error ||= error; this.callbacks.output(`VAS output error: ${error.message}\n`); });
      this.child.once('error', error => { this.failed = true; this.report.error ||= error; this.callbacks.output(`VAS could not run compiler: ${error.message}\n`); });
      this.child.once('close', async code => {
        await this.observationWork;
        const checked = this.report.end(code);
        if (this.report.error && !this.cancelled) this.callbacks.output(`VAS: ${this.report.error.message}\n`);
        this.finish(this.cancelled ? 130 : this.failed ? 1 : checked);
      });
    } catch (error) { this.callbacks.output(`VAS could not run compiler: ${error.message}\n`); this.finish(1); }
  }
  finish(code) {
    if (this.finished) return;
    this.finished = true;
    clearTimeout(this.killTimer);
    this.callbacks.complete(code, this.report);
  }
  terminate() {
    if (this.cancelled || this.finished) return;
    this.cancelled = true;
    if (!this.child) return this.finish(130);
    this.child.kill();
    this.killTimer = setTimeout(() => { if (!this.finished) this.child.kill('SIGKILL'); }, 2000);
    this.killTimer.unref();
  }
}

/** Observations are context, not semantic bindings. Partial refreshes only union. */
class ProjectDependencies {
  constructor(onFiles = () => {}) { this.entries = new Map(); this.onFiles = onFiles; }
  async observe(plan, item) {
    if (!this.entries.has(plan.key)) this.update(plan, { observed: new Map() }, false);
    this.entries.get(plan.key).observed.set(item.key, item);
    if (item.file) {
      this.onFiles([item.file]);
      const physical = await fs.realpath(item.file).catch(() => undefined);
      if (physical) {
        // Watch aliases separately; never replace the compiler's section key.
        this.entries.get(plan.key).aliases.add(physical);
        this.onFiles([physical]);
      }
    }
  }
  update(plan, report, complete) {
    const previous = this.entries.get(plan.key);
    const observed = new Map(complete ? [] : previous?.observed);
    for (const [key, item] of report.observed) observed.set(key, item);
    const inputs = [...new Set([...(complete ? [] : previous?.inputs || []), plan.project, plan.source, plan.config, ...(plan.watchInputs || [])])];
    const outputs = [...new Set([...(previous?.outputs || []), plan.output].filter(Boolean))];
    const next = { inputs, observed, outputs, aliases: previous?.aliases || new Set() };
    try { this.onFiles([...inputs, ...[...observed.values()].flatMap(item => item.file ? [item.file] : [])]); }
    catch (error) {
      // Failure to establish watches makes this refresh incomplete too.
      next.inputs = [...new Set([...(previous?.inputs || []), ...inputs])];
      next.observed = new Map([...(previous?.observed || []), ...observed]);
      this.entries.set(plan.key, next);
      throw error;
    }
    this.entries.set(plan.key, next);
  }
  outputOnly(file) {
    return !this.relevant(file) && [...this.entries.values()].some(entry => entry.outputs.some(output => sameFileName(output, file)));
  }
  relevant(file) {
    return [...this.entries.values()].some(entry => entry.inputs.some(input => sameFileName(input, file)) || [...entry.aliases].some(alias => sameFileName(alias, file)) ||
      [...entry.observed.values()].some(item => item.file && sameFileName(item.file, file)));
  }
}

module.exports = { ProjectReport, ProjectBuildProcess, ProjectDependencies, diagnosticRecord, identity };
