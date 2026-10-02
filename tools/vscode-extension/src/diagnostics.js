'use strict';

const path = require('node:path');
const { StringDecoder } = require('node:string_decoder');

// Paths come directly from the compiler pipe, never from terminal screen text.
function parseDiagnostic(line, cwd, paths = path) {
  const match = /^(.*) \((\d+),\s*(\d+)\)\s*:\s*(ERR|WARN)\s*:\s*(.+)$/.exec(line);
  if (!match || !match[1] || match[1].includes('\0')) return undefined;
  const row = Number(match[2]), column = Number(match[3]);
  if (!Number.isSafeInteger(row) || !Number.isSafeInteger(column)) return undefined;
  return { file: paths.resolve(cwd, match[1]), row, column, severity: match[4], message: match[5] };
}

/** One UTF-8 decoder per pipe. Discard oversized lines, not an unbounded tail. */
class DiagnosticStream {
  constructor(onLine, maxLineLength = 64 * 1024) {
    this.decoder = new StringDecoder('utf8');
    this.onLine = onLine;
    this.maxLineLength = maxLineLength;
    this.pending = '';
    this.discarding = false;
  }
  accept(text) {
    let start = 0;
    for (let end = 0; end <= text.length; end++) {
      if (end !== text.length && text[end] !== '\n') continue;
      const part = text.slice(start, end);
      if (!this.discarding) {
        if (this.pending.length + part.length > this.maxLineLength) {
          this.pending = '';
          this.discarding = true;
          this.truncated = true;
        } else this.pending += part;
      }
      if (end < text.length) {
        if (!this.discarding) this.onLine(this.pending.replace(/\r$/, ''));
        this.pending = '';
        this.discarding = false;
      }
      start = end + 1;
    }
    return text;
  }
  write(chunk) { return this.accept(this.decoder.write(chunk)); }
  end() {
    const tail = this.accept(this.decoder.end());
    if (!this.discarding && this.pending) this.onLine(this.pending.replace(/\r$/, ''));
    this.pending = '';
    return tail;
  }
}

// AngelScript asCScriptCode::ConvertPosToRowCol counts one-based UTF-8 bytes,
// including tabs as one byte. Never split a UTF-8 sequence or UTF-16 surrogate pair.
function utf16Column(line, byteColumn) {
  const target = Math.max(0, byteColumn - 1);
  let bytes = 0, units = 0;
  for (const character of line) {
    const width = Buffer.byteLength(character, 'utf8');
    if (bytes + width > target) break;
    bytes += width;
    units += character.length;
  }
  return units;
}

function sourcePosition(source, row, column) {
  const lines = Array.isArray(source) ? source : source.split('\n');
  const line = Math.min(Math.max(0, row - 1), lines.length - 1);
  const text = lines[line].replace(/\r$/, '');
  let character = row > 0 && column > 0 ? utf16Column(text, column) : 0;
  // VS Code strips a UTF-8 BOM from the document, but the compiler counts it.
  if (line === 0 && text.startsWith('\uFEFF')) character = Math.max(0, character - 1);
  return { line, character };
}

/** Small generation store, independent of VS Code and filesystem timing. */
class BuildDiagnostics {
  constructor(createCollection) {
    this.createCollection = createCollection;
    this.entries = new Map();
    this.revision = 0;
    this.disposed = false;
  }
  begin(key) {
    if (this.disposed) throw new Error('VAS diagnostics have been disposed.');
    let entry = this.entries.get(key);
    if (!entry) {
      entry = { collection: this.createCollection() };
      this.entries.set(key, entry);
    }
    entry.collection.clear();
    const token = { key, revision: this.revision, records: [], size: 0, truncated: false };
    entry.token = token;
    return token;
  }
  current(token) {
    return !this.disposed && token.revision === this.revision && this.entries.get(token.key)?.token === token;
  }
  add(token, record) {
    if (!this.current(token)) return;
    const size = record.file.length + record.message.length;
    if (token.records.length >= 2000 || token.size + size > 1024 * 1024) {
      token.truncated = true;
      return;
    }
    token.size += size;
    token.records.push(record);
  }
  publish(token, entries) {
    if (!this.current(token)) return false;
    this.entries.get(token.key).collection.set(entries);
    token.records = [];
    return true;
  }
  cancel(token) {
    if (!this.current(token)) return;
    this.entries.get(token.key).collection.clear();
    this.entries.get(token.key).token = undefined;
    token.records = [];
  }
  invalidate() {
    this.revision++;
    for (const entry of this.entries.values()) {
      entry.collection.clear();
      if (entry.token) entry.token.records = [];
      entry.token = undefined;
    }
  }
  dispose() {
    this.invalidate();
    this.disposed = true;
    for (const entry of this.entries.values()) entry.collection.dispose();
    this.entries.clear();
  }
}

module.exports = { parseDiagnostic, DiagnosticStream, utf16Column, sourcePosition, BuildDiagnostics };
