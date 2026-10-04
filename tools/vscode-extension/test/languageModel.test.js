'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { parse, resolve, LanguageWorkspace } = require('../src/languageModel');

test('strings, comments, based integers and macro bodies cannot forge declarations/imports', () => {
  const model = parse('/* import Wrong; */ string Text = "import Fake; class Spoof {}";\n#define CODE class Forged {}\nint Value = 0xAB\'CD;\nimport Real.Library;\nimport int External(int Value) from "Legacy";');
  assert.deepEqual(model.includes.map(i => i.path), ['Real/Library.vas']);
  assert(model.symbols.some(s => s.name === 'External' && s.kind === 'function'));
  assert(model.symbols.some(s => s.name === 'CODE' && s.kind === 'macro'));
  assert(!model.symbols.some(s => ['Spoof', 'Forged', 'AB', 'CD'].includes(s.name)));
});

test('declarations, lexical shadowing, namespace and typed receiver navigation', () => {
  const library = parse('namespace Arena { class FHero { int Health; void Heal(int Amount) {} } int Add(int A, int B) { return A+B; } }', 'library.vas');
  const source = parse('import Arena.Library;\nvoid Main() { Arena::FHero Hero; int Value = 1; { int Value = 2; print(Value); } print(Value); Hero.Heal(3); Arena::Add(1,2); }', 'main.vas');
  const graph = [source, library];
  const localUses = [...source.text.matchAll(/print\(Value/g)].map(m => m.index + 6);
  assert.equal(resolve(graph, source, localUses[0])[0].start, source.text.indexOf('Value = 2'));
  assert.equal(resolve(graph, source, localUses[1])[0].start, source.text.indexOf('Value = 1'));
  assert.equal(resolve(graph, source, source.text.indexOf('Heal(3)'))[0].qualifiedName, 'Arena::FHero::Heal');
  assert.equal(resolve(graph, source, source.text.indexOf('Add(1,2)'))[0].qualifiedName, 'Arena::Add');
  assert.equal(library.symbols.find(s => s.name === 'Amount').kind, 'parameter');
});

test('arena example: nine-file import closure, transitive macros, typed members and unsaved changes', async () => {
  const file = path.resolve(__dirname, '../../../examples/arena/main.vas');
  const text = await fs.readFile(file, 'utf8'), document = { uri: { scheme: 'file', fsPath: file }, getText: () => text };
  const workspace = new LanguageWorkspace(), graph = await workspace.graph(document);
  assert.equal(graph.length, 9);
  const target = resolve(graph, graph[0], text.indexOf('RunDemo('));
  assert.equal(target.length, 1);
  assert.equal(path.basename(target[0].file), 'Demo.vas');
  assert.equal(target[0].container, 'Arena');
  assert(graph.flatMap(m => m.symbols).some(s => s.name === 'VAS_ARENA_MAX_ROUNDS' && s.kind === 'macro'));
  const report = graph.find(m => m.file.endsWith('Report.vas'));
  assert(report.symbols.some(s => s.name === 'PrintStandings'));
  const original = graph.find(m => m.file.endsWith('Demo.vas'));
  workspace.documents = () => [{ uri: { scheme: 'file', fsPath: original.file }, getText: () => original.text.replace('RunDemo(', 'RunChangedDemo(') }];
  const changed = await workspace.graph(document);
  assert.equal(resolve(changed, changed[0], text.indexOf('RunDemo(')).length, 0);
});

test('dependency cycles deduplicate; ambiguous entry roots do not choose an arbitrary file', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-language-'));
  try {
    await fs.mkdir(path.join(root, 'src', 'Nested'), { recursive: true });
    await fs.writeFile(path.join(root, 'vas-project.json'), JSON.stringify({ compilationUnits: [{ entry: 'src/main.vas' }] }));
    await fs.writeFile(path.join(root, 'src/main.vas'), 'import Nested.Module;');
    await fs.writeFile(path.join(root, 'src/Nested/Module.vas'), 'import Common;');
    await fs.writeFile(path.join(root, 'src/Common.vas'), '#include "main.vas"\nint Value;');
    const document = { uri: { scheme: 'file', fsPath: path.join(root, 'src/main.vas') }, getText: () => 'import Nested.Module;' };
    const workspace = new LanguageWorkspace();
    assert.equal((await workspace.graph(document)).length, 3);
    await fs.writeFile(path.join(root, 'Common.vas'), 'int Unrelated;');
    assert.equal((await workspace.graph(document)).length, 2);
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});


test('enum constants support both namespace exports and explicit enum qualification', () => {
  const model = parse('namespace Arena { enum ERole { Warrior, Medic } void Main() { print(Warrior); print(ERole::Warrior); print(Arena::ERole::Warrior); } }');
  const offsets = [...model.text.matchAll(/Warrior\);/g)].map(match => match.index);
  for (const offset of offsets) assert.equal(resolve([model], model, offset)[0].kind, 'enumMember');
});
