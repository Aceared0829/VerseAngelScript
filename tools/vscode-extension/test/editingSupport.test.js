'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { inspectSyntax, callAt, signature } = require('../src/editingSupport');

test('structural diagnostics protect literals, comments, number separators and inactive branches', () => {
  assert.deepEqual(inspectSyntax('void Main() { string S = "} )"; int Value = 0xAB\'CD; /* ] */ }'), []);
  assert.deepEqual(inspectSyntax('string S = """} ] (\n""";'), []);
  assert(inspectSyntax('void Main(] {}').some(p => p.message === "Unexpected ']'"));
  assert(inspectSyntax('void Main() {').some(p => p.message === "Expected '}'"));
  assert(inspectSyntax('string S = "escaped\\"').some(p => p.message.includes('string')));
  assert(inspectSyntax('/* unterminated').some(p => p.message.includes('comment')));
  assert.deepEqual(inspectSyntax('#if OTHER\nvoid Different() {\n#else\nvoid Main() {}\n#endif'), []);
});
test('parameter help tracks innermost argument, nested expressions and default parameter commas', () => {
  const text = 'Outer(Inner(1, 2), {3,4}, "comma,", ';
  assert.equal(callAt(text, text.length).name, 'Outer');
  assert.equal(callAt(text, text.length).parameter, 3);
  assert.equal(callAt('Outer(Inner(1, ', 15).name, 'Inner');
  const info = signature('Add(array<int>@ Values, int Count = Other(1,2), string S = "a,b")');
  assert.equal(info.parameters.length, 3);
  assert.equal(info.label.slice(...info.parameters[2]), 'string S = "a,b"');
});
