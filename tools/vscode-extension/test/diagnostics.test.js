'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { parseDiagnostic, DiagnosticStream, utf16Column, sourcePosition, BuildDiagnostics } = require('../src/diagnostics');

test('parses exact Unicode/space/parenthesis paths, severity, zero positions and relative files', () => {
  assert.deepEqual(parseDiagnostic('C:\\VAS project 工程 文😀 with spaces\\src (demo)\\shared 文😀.vas (12, 30) : ERR  : Must return a value', 'C:\\project', path.win32), {
    file: 'C:\\VAS project 工程 文😀 with spaces\\src (demo)\\shared 文😀.vas', row: 12, column: 30, severity: 'ERR', message: 'Must return a value'
  });
  assert.deepEqual(parseDiagnostic('relative.vas (0, 0) : WARN : text : more text', '/project', path.posix), {
    file: '/project/relative.vas', row: 0, column: 0, severity: 'WARN', message: 'text : more text'
  });
  for (const value of ['build complete', '/file (1, 1) : INFO : compiling', '/file (-1, 2) : ERR : bad', '/file (999999999999999999999, 1) : ERR : bad']) {
    assert.equal(parseDiagnostic(value, '/project', path.posix), undefined);
  }
});

test('pipe decoder preserves split UTF-8, CRLF and final lines without mixing streams', () => {
  const lines = [];
  const decoder = new DiagnosticStream(line => lines.push(line));
  const input = Buffer.from('工程 文😀.vas (1, 3) : ERR : 错误😀\r\nsecond\nlast');
  let output = '';
  for (const byte of input) output += decoder.write(Buffer.from([byte]));
  output += decoder.end();
  assert.equal(output, input.toString());
  assert.deepEqual(lines, ['工程 文😀.vas (1, 3) : ERR : 错误😀', 'second', 'last']);
});

test('oversized output lines are discarded through newline with bounded memory', () => {
  const lines = [];
  const decoder = new DiagnosticStream(line => lines.push(line), 16);
  for (let i = 0; i < 1000; i++) decoder.write(Buffer.from('x'.repeat(1000)));
  assert.equal(decoder.pending.length, 0);
  decoder.write(Buffer.from('\nvalid\n'));
  decoder.end();
  assert.deepEqual(lines, ['valid']);
});

test('byte columns convert CJK, emoji, combining marks, tabs, BOM and CRLF to UTF-16', () => {
  const line = '\t/* 文😀 e\u0301 */ return ;';
  const prefix = line.slice(0, line.indexOf('return'));
  assert.equal(utf16Column(line, Buffer.byteLength(prefix) + 1), prefix.length);
  assert.equal(utf16Column('文😀x', 2), 0, 'inside CJK clamps to codepoint start');
  assert.equal(utf16Column('文😀x', 6), 1, 'inside emoji does not split a surrogate');
  assert.equal(utf16Column('文😀x', 8), 3);
  assert.equal(utf16Column('abc', 999), 3);
  assert.deepEqual(sourcePosition('first\r\n' + line + '\r\n', 2, Buffer.byteLength(prefix) + 1), { line: 1, character: prefix.length });
  assert.deepEqual(sourcePosition('\uFEFFreturn;', 1, 4), { line: 0, character: 0 });
  assert.deepEqual(sourcePosition('text', 0, 0), { line: 0, character: 0 });
});

function store() {
  const collections = [];
  const diagnostics = new BuildDiagnostics(() => {
    const collection = { values: [], clear() { this.values = []; }, set(values) { this.values = values; }, dispose() { this.disposed = true; } };
    collections.push(collection);
    return collection;
  });
  return { diagnostics, collections };
}

test('per-entry results survive unrelated builds and late older completion cannot replace newer results', () => {
  const { diagnostics, collections } = store();
  const first = diagnostics.begin('/one/main.vas');
  diagnostics.publish(first, ['first error']);
  const second = diagnostics.begin('/two/main.vas');
  diagnostics.publish(second, ['second error']);
  assert.deepEqual(collections[0].values, ['first error']);
  const newer = diagnostics.begin('/one/main.vas');
  diagnostics.publish(newer, ['new error']);
  assert.equal(diagnostics.publish(first, ['old error']), false);
  assert.deepEqual(collections[0].values, ['new error']);
  assert.deepEqual(collections[1].values, ['second error']);
  diagnostics.publish(diagnostics.begin('/one/main.vas'), []);
  assert.deepEqual(collections[0].values, []);
  assert.deepEqual(collections[1].values, ['second error']);
});

test('source edits, cancellation and disposal prevent stale publication, including pending source reads', async () => {
  const { diagnostics, collections } = store();
  const token = diagnostics.begin('entry');
  diagnostics.add(token, { file: '/one.vas', message: 'old' });
  diagnostics.invalidate();
  assert.equal(diagnostics.current(token), false);
  assert.equal(diagnostics.publish(token, ['stale']), false);
  assert.equal(token.records.length, 0);
  const next = diagnostics.begin('entry');
  diagnostics.cancel(next);
  assert.equal(diagnostics.publish(next, ['cancelled']), false);
  const final = diagnostics.begin('entry');
  diagnostics.dispose();
  assert.equal(diagnostics.publish(final, ['disposed']), false);
  assert.ok(collections.every(collection => collection.disposed));
});

test('retained diagnostics have bounded count and size', () => {
  const { diagnostics } = store();
  const token = diagnostics.begin('entry');
  for (let i = 0; i < 10000; i++) diagnostics.add(token, { file: '/one.vas', message: 'bad'.repeat(1000) });
  assert.ok(token.size <= 1024 * 1024);
  assert.ok(token.records.length <= 2000);
  assert.equal(token.truncated, true);
});
