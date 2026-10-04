'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { before, after, test } = require('node:test');
const textmate = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

const extensionRoot = path.resolve(__dirname, '..');
const readJson = relative => JSON.parse(fs.readFileSync(path.join(extensionRoot, relative), 'utf8'));
const rawGrammar = readJson('syntaxes/vas.tmLanguage.json');
const configuration = readJson('language-configuration.json');
const snippets = readJson('snippets/vas.code-snippets');
let registry;
let grammar;

before(async () => {
  const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  registry = new textmate.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: patterns => new oniguruma.OnigScanner(patterns),
      createOnigString: content => new oniguruma.OnigString(content)
    }),
    loadGrammar: async scopeName => scopeName === 'source.vas' ? rawGrammar : null
  });
  grammar = await registry.loadGrammar('source.vas');
  assert.ok(grammar, 'VAS TextMate grammar loads');
});

after(() => registry?.dispose());

function tokenize(source) {
  let state = textmate.INITIAL;
  return source.split('\n').map(line => {
    const result = grammar.tokenizeLine(line, state);
    state = result.ruleStack;
    return result.tokens.map(token => ({
      text: line.slice(token.startIndex, token.endIndex),
      start: token.startIndex,
      end: token.endIndex,
      scopes: token.scopes
    }));
  });
}

function tokenAt(tokens, line, fragment, occurrence = 0) {
  let start = -1;
  for (let index = 0; index <= occurrence; index++) start = line.indexOf(fragment, start + 1);
  assert.notEqual(start, -1, `Fixture contains ${JSON.stringify(fragment)}`);
  const token = tokens.find(candidate => candidate.start <= start && candidate.end > start);
  assert.ok(token, `Token exists at ${JSON.stringify(fragment)} in ${JSON.stringify(line)}`);
  return token;
}

function assertScope(tokens, line, fragment, scope, occurrence = 0) {
  const token = tokenAt(tokens, line, fragment, occurrence);
  assert.ok(token.scopes.includes(scope), `${JSON.stringify(fragment)} should have ${scope}; got ${token.scopes.join(', ')}`);
  return token;
}

function assertNoScope(tokens, line, fragment, scopePrefix) {
  const token = tokenAt(tokens, line, fragment);
  assert.ok(!token.scopes.some(scope => scope.startsWith(scopePrefix)), `${JSON.stringify(fragment)} must not have ${scopePrefix}; got ${token.scopes.join(', ')}`);
}

test('assets preserve VAS language identity and all repository references resolve', () => {
  assert.equal(rawGrammar.scopeName, 'source.vas');
  assert.equal(rawGrammar.name, 'VAS');
  assert.deepEqual(rawGrammar.fileTypes, ['vas']);
  assert.ok(rawGrammar.patterns.length > 0);
  function visit(value) {
    if (Array.isArray(value)) return value.forEach(visit);
    if (!value || typeof value !== 'object') return;
    if (value.include) {
      assert.ok(value.include.startsWith('#'), 'Grammar does not depend on another extension');
      assert.ok(rawGrammar.repository[value.include.slice(1)], `Missing repository rule ${value.include}`);
    }
    for (const key of ['match', 'begin', 'end']) {
      if (typeof value[key] === 'string') {
        const scanner = new oniguruma.OnigScanner([value[key]]);
        scanner.dispose();
      }
    }
    Object.values(value).forEach(visit);
  }
  visit(rawGrammar);
});

test('line and block comments suppress keyword, string, and numeric highlighting', () => {
  const lines = ['// class Fake { 42 "text" }', '/* comment starts', 'int hidden = 0xFF;', '*/ int visible = 2;'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[0], lines[0], 'class', 'comment.line.double-slash.vas');
  assertNoScope(tokens[0], lines[0], '42', 'constant.numeric');
  assertScope(tokens[2], lines[2], 'int', 'comment.block.vas');
  assertNoScope(tokens[2], lines[2], '0xFF', 'constant.numeric');
  assertScope(tokens[3], lines[3], 'int', 'storage.type.vas');
});

test('block comments close at first terminator, matching the vendored compiler', () => {
  const line = '/* outer /* inner */ int visible = 1;';
  const tokens = tokenize(line)[0];
  assertScope(tokens, line, 'inner', 'comment.block.vas');
  assertScope(tokens, line, 'int', 'storage.type.vas');
  assertNoScope(tokens, line, 'int', 'comment.');
});

test('quoted strings recognize escapes without leaking comment or keyword scopes', () => {
  const line = String.raw`string text = "class // \"quoted\"\n\xFF\u0041\U00000041"; string other = 'it\'s /* text */';`;
  const tokens = tokenize(line)[0];
  assertScope(tokens, line, 'class', 'string.quoted.double.vas');
  assertNoScope(tokens, line, '//', 'comment.');
  for (const escape of [String.raw`\"`, String.raw`\n`, String.raw`\xFF`, String.raw`\u0041`, String.raw`\U00000041`]) {
    assertScope(tokens, line, escape, 'constant.character.escape.vas');
  }
  assertScope(tokens, line, "it", 'string.quoted.single.vas');
  assertScope(tokens, line, String.raw`\'`, 'constant.character.escape.vas');
  assertNoScope(tokens, line, '/*', 'comment.');
});

test('invalid escapes are distinct and unterminated ordinary strings recover on the next line', () => {
  const lines = [String.raw`string text = "bad\q`, 'int visible = 3;'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[0], lines[0], String.raw`\q`, 'invalid.illegal.escape.vas');
  assertScope(tokens[1], lines[1], 'int', 'storage.type.vas');
});

test('triple-quoted heredoc strings persist across lines and do not process escapes', () => {
  const lines = ['string text = """', String.raw`class // /* "quoted" \n 0xFF`, '"""; int visible = 3;'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[1], lines[1], 'class', 'string.quoted.triple.double.vas');
  assertScope(tokens[1], lines[1], String.raw`\n`, 'string.quoted.triple.double.vas');
  assertNoScope(tokens[1], lines[1], String.raw`\n`, 'constant.character.escape');
  assertNoScope(tokens[1], lines[1], '//', 'comment.');
  assertScope(tokens[2], lines[2], 'int', 'storage.type.vas');
});

test('preprocessor directives include lowercase .vas paths and release their line state', () => {
  const lines = ['  #include "shared.vas" // dependency', '#if DEBUG', 'void main() {}', '#endif'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[0], lines[0], '#', 'punctuation.definition.directive.vas');
  assertScope(tokens[0], lines[0], 'include', 'keyword.control.directive.vas');
  assertScope(tokens[0], lines[0], 'shared.vas', 'string.quoted.double.vas');
  assertScope(tokens[0], lines[0], 'dependency', 'comment.line.double-slash.vas');
  assertScope(tokens[1], lines[1], 'if', 'keyword.control.directive.vas');
  assertScope(tokens[2], lines[2], 'void', 'storage.type.vas');
  assertNoScope(tokens[2], lines[2], 'void', 'meta.preprocessor');
});

test('base-prefixed integers and apostrophe digit separators tokenize as complete numbers', () => {
  const cases = {
    'constant.numeric.integer.binary.vas': ['0b1010', "0B10'01"],
    'constant.numeric.integer.octal.vas': ['0o755', "0O7'55"],
    'constant.numeric.integer.hexadecimal.vas': ['0xFF', "0XAB'CD"],
    'constant.numeric.integer.decimal.vas': ['0d42', "0D1'000", '42', "1'234'567"]
  };
  for (const [scope, literals] of Object.entries(cases)) {
    for (const literal of literals) {
      const line = `auto number = ${literal};`;
      const token = assertScope(tokenize(line)[0], line, literal, scope);
      assert.equal(token.text, literal);
    }
  }
});

test('fractional and exponent literals preserve adjacent arithmetic operators', () => {
  for (const literal of ['1.0', '1.', '.25', '6.02e23', '1e-3', '2E+4', '1.5f', '.25F', "1'234.5'67e+1'0f"]) {
    const line = `auto number = ${literal}+2-3;`;
    const tokens = tokenize(line)[0];
    const number = assertScope(tokens, line, literal, 'constant.numeric.float.vas');
    assert.equal(number.text, literal);
    assertScope(tokens, line, '+2', 'keyword.operator.vas');
    assertScope(tokens, line, '-3;', 'keyword.operator.vas');
    assertScope(tokens, line, '2-3', 'constant.numeric.integer.decimal.vas');
  }
  const line = 'int count = 1+2-3;';
  const numbers = tokenize(line)[0].filter(token => token.scopes.some(scope => scope.startsWith('constant.numeric')));
  assert.deepEqual(numbers.map(token => token.text), ['1', '2', '3']);
});

test('identifier digits and keyword prefixes are not tokenized as numbers or keywords', () => {
  const line = 'intensity = value42 + format0xFF + printable;';
  const tokens = tokenize(line)[0];
  for (const word of ['intensity', 'value42', 'format0xFF', 'printable']) {
    assertNoScope(tokens, line, word, 'storage.type');
    assertNoScope(tokens, line, word, 'constant.numeric');
  }
});

test('declaration names, primitive types, constants, and handle operations have distinct scopes', () => {
  const lines = ['shared class Player {', 'namespace Demo::Game {', 'Player@ actor = null;', '@actor = @other;', 'if (actor !is null and actor is other) return;', 'bool ready = true; uint64 flags = 0xFF;', 'actor.update();', 'this.tick(); super();'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[0], lines[0], 'shared', 'storage.modifier.vas');
  assertScope(tokens[0], lines[0], 'Player', 'entity.name.type.vas');
  assertScope(tokens[1], lines[1], 'Demo::Game', 'entity.name.namespace.vas');
  assertScope(tokens[2], lines[2], '@', 'keyword.operator.handle.vas');
  assertScope(tokens[2], lines[2], 'null', 'constant.language.vas');
  assertScope(tokens[3], lines[3], '@', 'keyword.operator.handle.vas');
  assertScope(tokens[4], lines[4], '!is', 'keyword.operator.word.vas');
  assertScope(tokens[4], lines[4], 'and', 'keyword.operator.word.vas');
  assertScope(tokens[4], lines[4], 'return', 'keyword.control.vas');
  assertScope(tokens[5], lines[5], 'bool', 'storage.type.vas');
  assertScope(tokens[5], lines[5], 'uint64', 'storage.type.vas');
  assertScope(tokens[5], lines[5], 'true', 'constant.language.vas');
  assertScope(tokens[6], lines[6], 'update', 'entity.name.function.vas');
  assertScope(tokens[7], lines[7], 'this', 'variable.language.vas');
  assertScope(tokens[7], lines[7], 'super', 'variable.language.vas');
});

test('all Rider language keywords remain recognized in VS Code', () => {
  const riderSource = fs.readFileSync(path.resolve(extensionRoot, '../rider-plugin/src/main/java/com/verseangelscript/rider/lang/VasKeywords.java'), 'utf8');
  const keywords = [...riderSource.matchAll(/"([a-z][a-z0-9_]*)"/g)].map(match => match[1]);
  assert.ok(keywords.length >= 60, 'Rider keyword reference was found');
  for (const keyword of keywords) {
    const token = tokenAt(tokenize(keyword)[0], keyword, keyword);
    assert.ok(token.scopes.some(scope => /^(?:keyword|storage|constant\.language|variable\.language)\./.test(scope)), `Missing keyword ${keyword}`);
  }
});

test('compound VAS operators stay intact', () => {
  for (const operator of ['**', '**=', '>>>', '>>>=', '<<=', '>>=', '^^', '&&', '||', '++', '--', '+=', '!=']) {
    const line = `left ${operator} right;`;
    const token = assertScope(tokenize(line)[0], line, operator, 'keyword.operator.vas');
    assert.equal(token.text, operator);
  }
});

test('language configuration supports comments, bracket pairs, indentation, and region folding', () => {
  assert.equal(configuration.comments.lineComment, '//');
  assert.deepEqual(configuration.comments.blockComment, ['/*', '*/']);
  assert.deepEqual(configuration.brackets, [['{', '}'], ['[', ']'], ['(', ')']]);
  for (const quote of ['"', "'"]) {
    const pair = configuration.autoClosingPairs.find(candidate => candidate.open === quote);
    assert.equal(pair.close, quote);
    assert.deepEqual(pair.notIn, ['string', 'comment']);
  }
  const increase = new RegExp(configuration.indentationRules.increaseIndentPattern);
  const decrease = new RegExp(configuration.indentationRules.decreaseIndentPattern);
  assert.ok(increase.test('if (ready) {'));
  assert.ok(increase.test('  {'));
  assert.ok(!increase.test('// {'));
  assert.ok(!increase.test('string value = "{";'));
  assert.ok(!increase.test('void main() {}'));
  assert.ok(decrease.test('    }'));
  assert.ok(!decrease.test('    value();'));
  assert.ok(new RegExp(configuration.folding.markers.start).test('// #region Helpers'));
  assert.ok(new RegExp(configuration.folding.markers.end).test('// #endregion'));
});

test('snippets are well-formed and use standalone VAS syntax', () => {
  const prefixes = new Set();
  assert.ok(Object.keys(snippets).length >= 10);
  for (const [name, snippet] of Object.entries(snippets)) {
    assert.equal(typeof snippet.prefix, 'string', `${name} prefix`);
    assert.ok(!prefixes.has(snippet.prefix), `Duplicate prefix ${snippet.prefix}`);
    prefixes.add(snippet.prefix);
    assert.ok(Array.isArray(snippet.body) && snippet.body.every(line => typeof line === 'string'), `${name} body`);
    assert.ok(snippet.body.join('\n').includes('$0'), `${name} has a final cursor position`);
    assert.equal(typeof snippet.description, 'string', `${name} description`);
    assert.ok(!/UCLASS|UFUNCTION|UPROPERTY|GENERATED_BODY|\.as\b|\.VAS\b/.test(snippet.body.join('\n')), `${name} does not suggest absent Unreal bindings or another extension`);
  }
  assert.ok(snippets['Include VAS file'].body[0].endsWith('.vas"'));
  assert.equal(snippets['VAS entry point'].body[0], 'void main()');
});


test('module imports, exported macro names and format fields have separate scopes', () => {
  const lines = ['import Arena.Demo;', '#define VAS_ARENA_MAX_ROUNDS 12', 'println("ABC {} {1:04d} {{literal}}", Value, Other);'];
  const tokens = tokenize(lines.join('\n'));
  assertScope(tokens[0], lines[0], 'Arena.Demo', 'entity.name.namespace.vas');
  assertScope(tokens[1], lines[1], 'VAS_ARENA_MAX_ROUNDS', 'entity.name.function.preprocessor.vas');
  assertScope(tokens[2], lines[2], '{}', 'constant.other.placeholder.vas');
  assertScope(tokens[2], lines[2], '{1:04d}', 'constant.other.placeholder.vas');
  assertScope(tokens[2], lines[2], '{{', 'constant.character.escape.format.vas');
});
