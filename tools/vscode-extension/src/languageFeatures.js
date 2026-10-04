'use strict';

const path = require('node:path');
const { LanguageWorkspace, resolve, wordAt, keywords, runtime } = require('./languageModel');
const { callAt, signature, inCode } = require('./editingSupport');

function registerLanguageFeatures(vscode, context) {
  const selector = { language: 'vas', scheme: 'file' }, workspace = new LanguageWorkspace(() => vscode.workspace.textDocuments);
  const kinds = { class: 'Class', interface: 'Interface', enum: 'Enum', enumMember: 'EnumMember', namespace: 'Module', function: 'Function', method: 'Method', property: 'Field', parameter: 'Variable', variable: 'Variable', macro: 'Constant' };
  const legendTypes = ['class', 'interface', 'enum', 'enumMember', 'namespace', 'function', 'method', 'property', 'parameter', 'variable', 'macro'];
  const legend = new vscode.SemanticTokensLegend(legendTypes, ['declaration']);
  const range = (document, start, end) => new vscode.Range(document.positionAt(start), document.positionAt(end));
  const location = async symbol => {
    const document = await vscode.workspace.openTextDocument(vscode.Uri.file(symbol.file));
    return new vscode.Location(document.uri, range(document, symbol.start, symbol.end));
  };
  const graphAt = async (document, token) => { const graph = await workspace.graph(document, token); return { graph, model: graph[0] }; };
  require('./liveDiagnostics').registerLiveDiagnostics(vscode, context, workspace);
  context.subscriptions.push(
    vscode.languages.registerDefinitionProvider(selector, {
      async provideDefinition(document, position, token) {
        const { graph, model } = await graphAt(document, token);
        if (!model || token.isCancellationRequested) return [];
        const offset = document.offsetAt(position), include = model.includes.find(i => i.start <= offset && offset < i.end);
        if (include) {
          const dependency = await workspace.dependency(model.file, include, await workspace.roots(model.file));
          return dependency ? new vscode.Location(vscode.Uri.file(dependency.file), new vscode.Position(0, 0)) : [];
        }
        // A macro in a directive has no ordinary lexer token. Keep it read-only.
        const word = document.getWordRangeAtPosition(position), name = word && document.getText(word);
        const declarations = wordAt(model, offset) ? resolve(graph, model, offset) : graph.flatMap(m => m.symbols.filter(s => s.kind === 'macro' && s.name === name));
        return Promise.all(declarations.map(location));
      }
    }),
    vscode.languages.registerCompletionItemProvider(selector, {
      async provideCompletionItems(document, position, token) {
        if (!inCode(document.getText(), document.offsetAt(position))) return [];
        const { graph, model } = await graphAt(document, token);
        if (!model || token.isCancellationRequested) return [];
        const offset = document.offsetAt(position), prefix = document.getText().slice(0, offset), member = /([\p{L}_][\p{L}\p{N}_]*(?:::[\p{L}_][\p{L}\p{N}_]*)*)(::|\.)[\p{L}\p{N}_]*$/u.exec(prefix);
        let symbols = graph.flatMap(m => m.symbols.filter(s => m !== model ? s.exported : s.exported || s.start <= offset && s.scopeStart <= offset && offset <= s.scopeEnd));
        if (member) {
          let owner = member[1];
          if (member[2] === '.') {
            const receiver = model.tokens.find(t => t.start === member.index), variable = receiver && resolve(graph, model, receiver.start);
            if (!variable || variable.length !== 1 || !variable[0].type) return [];
            const type = variable[0].type, qualified = `${variable[0].container}::${type}`;
            const resolvedType = symbols.find(s => ['class', 'interface'].includes(s.kind) && (s.qualifiedName === type || s.qualifiedName === qualified));
            owner = resolvedType?.qualifiedName || type;
          }
          if (member[2] === '::' && !owner.includes('::')) {
            const head = model.tokens.find(t => t.start === member.index);
            const containers = head ? resolve(graph, model, head.start).filter(s => ['class', 'interface', 'enum', 'namespace'].includes(s.kind)) : [];
            const identities = [...new Set(containers.map(s => s.qualifiedName))];
            if (identities.length === 1) owner = identities[0];
          }
          symbols = symbols.filter(s => (s.container === owner || s.kind === 'enumMember' && s.type === owner) && s.exported);
        }
        const items = [], seen = new Set();
        for (const symbol of symbols) {
          const identity = `${symbol.qualifiedName || symbol.name}/${symbol.detail}/${symbol.file}`;
          if (seen.has(identity)) continue;
          seen.add(identity);
          const item = new vscode.CompletionItem(symbol.name, vscode.CompletionItemKind[kinds[symbol.kind]]);
          item.range = document.getWordRangeAtPosition(position) || new vscode.Range(position, position);
          item.detail = symbol.detail || `${symbol.kind} ${symbol.qualifiedName || symbol.name}`;
          item.documentation = `${path.basename(symbol.file)}${symbol.kind === 'macro' ? '\nMacro activation depends on the compilation unit.' : ''}`;
          item.sortText = `${symbol.file === model.file ? '0' : '1'}${symbol.name}`;
          if (['function', 'method'].includes(symbol.kind) && !/^\s*\(/.test(document.getText().slice(offset))) {
            const parameters = signature(symbol.detail || `${symbol.name}()`).parameters;
            item.insertText = new vscode.SnippetString(`${symbol.name}(${parameters.length ? '$0' : ''})`);
            item.command = { command: 'editor.action.triggerParameterHints', title: 'Show VAS parameters' };
          }
          items.push(item);
        }
        if (!member) for (const name of [...keywords, ...runtime]) if (!symbols.some(s => s.name === name)) {
          const item = new vscode.CompletionItem(name, keywords.has(name) ? vscode.CompletionItemKind.Keyword : vscode.CompletionItemKind.Function);
          item.sortText = '2' + name;
          if (!keywords.has(name) && !/^\s*\(/.test(document.getText().slice(offset))) item.insertText = new vscode.SnippetString(`${name}($0)`);
          items.push(item);
        }
        return items;
      }
    }, '.', ':'),
    vscode.languages.registerSignatureHelpProvider(selector, {
      async provideSignatureHelp(document, position, token) {
        const call = callAt(document.getText(), document.offsetAt(position)); if (!call) return undefined;
        const { graph, model } = await graphAt(document, token); if (!model || token.isCancellationRequested) return undefined;
        const declarations = resolve(graph, model, call.nameOffset).filter(s => ['function', 'method'].includes(s.kind));
        const labels = declarations.map(s => s.detail);
        if (!labels.length && ['print', 'println'].includes(call.name)) labels.push(`${call.name}(const string &in Format, const ?&in Arguments...)`, `${call.name}(Value)`);
        if (!labels.length) return undefined;
        const help = new vscode.SignatureHelp();
        help.signatures = [...new Set(labels)].map(label => {
          const parsed = signature(label), info = new vscode.SignatureInformation(label);
          info.parameters = parsed.parameters.map(range => new vscode.ParameterInformation(range)); return info;
        });
        help.activeParameter = call.parameter;
        help.activeSignature = Math.max(0, help.signatures.findIndex(s => s.parameters.length > call.parameter));
        return help;
      }
    }, '(', ','),
    vscode.languages.registerDocumentSymbolProvider(selector, {
      provideDocumentSymbols(document) {
        const { parse } = require('./languageModel');
        return parse(document.getText(), document.uri.fsPath).symbols.filter(s => s.exported).map(s =>
          new vscode.DocumentSymbol(s.name, s.detail || s.container, vscode.SymbolKind[kinds[s.kind]], range(document, s.start, s.end), range(document, s.start, s.end)));
      }
    }),
    vscode.languages.registerHoverProvider(selector, {
      async provideHover(document, position, token) {
        const { graph, model } = await graphAt(document, token);
        if (!model || token.isCancellationRequested) return undefined;
        const declarations = resolve(graph, model, document.offsetAt(position));
        if (!declarations.length) return undefined;
        return new vscode.Hover(declarations.map(s => {
          const contents = new vscode.MarkdownString();
          contents.appendCodeblock(s.detail || `${s.type || s.kind} ${s.qualifiedName}`, 'vas');
          contents.appendText(`\n${path.basename(s.file)}`); return contents;
        }));
      }
    }),
    vscode.languages.registerDocumentSemanticTokensProvider(selector, {
      async provideDocumentSemanticTokens(document, token) {
        const version = document.version, { graph, model } = await graphAt(document, token), builder = new vscode.SemanticTokensBuilder(legend);
        if (!model || token.isCancellationRequested || document.version !== version) return builder.build();
        const declarations = new Map(model.symbols.map(s => [s.start, s]));
        for (const word of model.tokens) {
          if (word.kind !== 'id' || keywords.has(word.text)) continue;
          const targets = declarations.has(word.start) ? [declarations.get(word.start)] : resolve(graph, model, word.start);
          const kindsFound = new Set(targets.map(s => s.kind));
          if (kindsFound.size === 1) builder.push(range(document, word.start, word.end), [...kindsFound][0], declarations.has(word.start) ? ['declaration'] : []);
        }
        const macroNames = new Set(graph.flatMap(m => m.symbols.filter(s => s.kind === 'macro').map(s => s.name)));
        for (const word of model.directiveWords) if (macroNames.has(word.text)) builder.push(range(document, word.start, word.end), 'macro', declarations.has(word.start) ? ['declaration'] : []);
        return builder.build();
      }
    }, legend)
  );
  context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(event => {
    if (event.document.languageId === 'vas' && event.contentChanges.length) workspace.invalidate();
  }));
  const watcher = vscode.workspace.createFileSystemWatcher('**/*.{vas,json}');
  context.subscriptions.push(watcher, watcher.onDidChange(() => workspace.invalidate()), watcher.onDidCreate(() => workspace.invalidate()), watcher.onDidDelete(() => workspace.invalidate()));
}

module.exports = { registerLanguageFeatures };
