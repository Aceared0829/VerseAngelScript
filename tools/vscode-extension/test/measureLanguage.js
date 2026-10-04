'use strict';
const { performance } = require('node:perf_hooks');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const { LanguageWorkspace, resolve } = require('../src/languageModel');

async function main() {
  const file = path.resolve(__dirname, '../../../examples/arena/main.vas'), text = await fs.readFile(file, 'utf8');
  const document = { uri: { scheme: 'file', fsPath: file }, getText: () => text }, workspace = new LanguageWorkspace();
  const phases = {};
  for (const phase of ['uncached', 'cached']) {
    const durations = [];
    for (let repeat = 0; repeat < 31; repeat++) {
      if (phase === 'uncached') workspace.invalidate();
      const start = performance.now(), graph = await workspace.graph(document);
      assert.equal(graph.length, 9);
      assert.equal(resolve(graph, graph[0], text.indexOf('RunDemo(')).length, 1);
      durations.push(performance.now() - start);
    }
    durations.sort((a, b) => a - b);
    phases[phase] = { medianMs: durations[15], p95Ms: durations[29] };
  }
  const graph = await workspace.graph(document), durations = [];
  for (let repeat = 0; repeat < 31; repeat++) {
    const start = performance.now(); let resolved = 0;
    for (const word of graph[0].tokens.filter(t => t.kind === 'id')) if (resolve(graph, graph[0], word.start).length) resolved++;
    assert(resolved > 0); durations.push(performance.now() - start);
  }
  durations.sort((a, b) => a - b);
  phases.semanticLookup = { medianMs: durations[15], p95Ms: durations[29] };
  const result = { node: process.version, files: 9, samples: 31, scope: 'source model/import graph only; excludes IDE startup/rendering; filesystem cache may be warm', ...phases };
  const output = path.resolve(__dirname, '../../../out/verification/ide/vscode-performance.json');
  await fs.mkdir(path.dirname(output), { recursive: true }); await fs.writeFile(output, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
