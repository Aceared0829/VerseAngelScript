'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');

const keywords = new Set(('abstract auto bool break case cast class const continue default do double else enum explicit external false final float for foreach from funcdef get if import in inout int int8 int16 int32 int64 interface is mixin namespace null out override private protected property public return set shared string super switch this true try typedef uint uint8 uint16 uint32 uint64 void while').split(' '));
const modifiers = new Set(['const', 'private', 'protected', 'public', 'shared', 'external', 'explicit', 'import']);
const nonTypes = new Set(['return', 'new', 'throw', 'case', 'else', 'break', 'continue', 'namespace', 'class', 'interface', 'enum']);
const runtime = ['print', 'println', 'getCommandLineArgs', 'getSystemTime', 'assert', 'formatInt', 'formatUInt', 'formatFloat', 'parseInt', 'parseUInt', 'parseFloat'];
const isId = text => /^[\p{L}\p{N}_]+$/u.test(text) && !/^\d/.test(text);

/** UTF-16 offsets match editor APIs. Comments, strings and directives never become declarations. */
function tokenize(text) {
  const tokens = [], directives = [], lexicalProblems = [], protectedSpans = [];
  const pattern = /\s+|\/\/[^\r\n]*|\/\*[\s\S]*?(?:\*\/|$)|"""[\s\S]*?(?:"""|$)|"(?:\\[\s\S]|[^"\\])*(?:"|$)|'(?:\\[\s\S]|[^'\\])*(?:'|$)|0[xX][\da-fA-F](?:[\da-fA-F]|'(?=[\da-fA-F]))*|0[bB][01](?:[01]|'(?=[01]))*|0[oO][0-7](?:[0-7]|'(?=[0-7]))*|0[dD]\d(?:\d|'(?=\d))*|(?:\d(?:\d|'(?=\d))*(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[fF]?|[\p{L}_][\p{L}\p{N}_]*|::|==|!=|<=|>=|&&|\|\||[\s\S]/gu;
  let match;
  while ((match = pattern.exec(text))) {
    const value = match[0], start = match.index;
    if (value.startsWith('//') || value.startsWith('/*') || /^["']/.test(value)) protectedSpans.push({ start, end: pattern.lastIndex,
      closed: value.startsWith('/*') ? value.endsWith('*/') : !value.startsWith('//') && value.length > 1 && value.endsWith(value.startsWith('"""') ? '"""' : value[0]) });
    if (value.startsWith('/*') && !value.endsWith('*/')) lexicalProblems.push({ start, end: start + 2, message: 'Unterminated block comment' });
    if (/^["']/.test(value)) {
      const delimiter = value.startsWith('"""') ? '"""' : value[0];
      const slashes = delimiter.length === 1 ? /\\*$/.exec(value.slice(0, -1))[0].length : 0;
      if (value.length < delimiter.length * 2 || !value.endsWith(delimiter) || slashes % 2)
        lexicalProblems.push({ start, end: start + delimiter.length, message: 'Unterminated string literal' });
    }
    if (/^\s/.test(value) || value.startsWith('//') || value.startsWith('/*')) continue;
    if (value === '#') {
      const end = text.indexOf('\n', start), stop = end < 0 ? text.length : end;
      const raw = text.slice(start, stop).replace(/\r$/, '');
      directives.push({ text: raw, start, end: stop });
      pattern.lastIndex = stop;
      continue;
    }
    tokens.push({ text: value, start, end: pattern.lastIndex, kind: /^["']/.test(value) ? 'string' : isId(value) ? 'id' : /^\d|^\.\d/.test(value) ? 'number' : 'punctuation' });
  }
  return { tokens, directives, lexicalProblems, protectedSpans };
}

function parse(text, file = '') {
  const { tokens: t, directives } = text.length > 4 * 1024 * 1024 ? { tokens: [], directives: [] } : tokenize(text), symbols = [], includes = [], pairs = new Map(), stack = [];
  for (let i = 0; i < t.length; i++) {
    if (['(', '[', '{'].includes(t[i].text)) stack.push(i);
    else if ([')', ']', '}'].includes(t[i].text)) {
      const left = stack.pop();
      if (left !== undefined && '([{'.indexOf(t[left].text) === ')]}'.indexOf(t[i].text)) pairs.set(left, i);
    }
  }
  for (const directive of directives) {
    const include = /^#include\s*(?:"([^"\r\n]+)"|'([^'\r\n]+)'|<([^>\r\n]+)>)/.exec(directive.text);
    if (include) includes.push({ path: include[1] || include[2] || include[3], kind: include[3] ? 'system' : 'quoted', start: directive.start, end: directive.start + include[0].length });
    const macro = /^#define[ \t]+([A-Za-z_]\w*)(.*)/.exec(directive.text);
    if (macro) symbols.push({ name: macro[1], kind: 'macro', start: directive.start + macro.index + macro[0].indexOf(macro[1]), end: directive.start + macro[0].indexOf(macro[1]) + macro[1].length, scopeStart: 0, scopeEnd: text.length, container: '', exported: true, detail: directive.text, file });
  }
  const scopes = [], bodyScopes = new Map(), declarations = new Set();
  const container = () => scopes.filter(s => ['namespace', 'class', 'interface', 'enum'].includes(s.kind)).map(s => s.name).join('::');
  const inFunction = () => scopes.some(s => s.kind === 'function');
  const add = (i, kind, extra = {}) => {
    declarations.add(i);
    const scope = scopes.at(-1);
    const symbol = { name: t[i].text, kind, start: t[i].start, end: t[i].end, file, container: container(),
      scopeStart: scope?.start || 0, scopeEnd: scope?.end ?? text.length, exported: !inFunction(), detail: '', ...extra };
    symbol.qualifiedName = symbol.container ? `${symbol.container}::${symbol.name}` : symbol.name;
    symbols.push(symbol); return symbol;
  };
  function typeBefore(i) {
    let j = i - 1;
    while (j >= 0 && ['@', '&', 'const', 'in', 'out', 'inout'].includes(t[j].text)) j--;
    if (t[j]?.text === '>') { let depth = 1; while (--j >= 0 && depth) { if (t[j].text === '>') depth++; if (t[j].text === '<') depth--; } }
    if (!t[j] || t[j].kind !== 'id' || nonTypes.has(t[j].text)) return null;
    const end = j;
    while (j >= 2 && t[j - 1].text === '::' && t[j - 2].kind === 'id') j -= 2;
    const name = t.slice(j, end + 1).map(x => x.text).join('');
    let head = j - 1;
    while (head >= 0 && modifiers.has(t[head].text)) head--;
    if (head >= 0 && ![';', '{', '}', '(', ','].includes(t[head].text)) return null;
    return name;
  }
  for (let i = 0; i < t.length; i++) {
    const token = t[i], next = t[i + 1]?.text;
    if (token.text === '{') {
      const scope = bodyScopes.get(i) || { kind: 'block', name: '', start: token.start, end: t[pairs.get(i)]?.end ?? text.length };
      scopes.push(scope); continue;
    }
    if (token.text === '}') { scopes.pop(); continue; }
    if (token.text === 'import' && scopes.length === 0) {
      let j = i + 1, parts = [];
      while (t[j]?.kind === 'id' && !keywords.has(t[j].text)) {
        parts.push(t[j++].text);
        if (t[j]?.text !== '.') break;
        j++;
      }
      if (parts.length && t[j]?.text === ';') {
        includes.push({ path: parts.join('/') + '.vas', kind: 'module', start: token.start, end: t[j].end });
        i = j; continue;
      }
    }
    if (['namespace', 'class', 'interface', 'enum'].includes(token.text) && t[i + 1]?.kind === 'id') {
      let j = i + 2;
      while (j < t.length && !['{', ';'].includes(t[j].text)) j++;
      const type = add(i + 1, token.text);
      if (t[j]?.text === '{') bodyScopes.set(j, { kind: token.text, name: type.name, start: t[j].start, end: t[pairs.get(j)]?.end ?? text.length });
      continue;
    }
    if (token.kind !== 'id' || keywords.has(token.text) || declarations.has(i)) continue;
    if (next === '(' && pairs.has(i + 1) && !inFunction()) {
      const close = pairs.get(i + 1);
      let tail = close + 1;
      while (['const', 'override', 'final', 'property'].includes(t[tail]?.text)) tail++;
      const type = typeBefore(i);
      const ctor = scopes.at(-1)?.name === token.text && ['class', 'interface'].includes(scopes.at(-1)?.kind);
      if ((type || ctor) && (['{', ';'].includes(t[tail]?.text) || t[tail]?.text === 'from' && t[tail + 1]?.kind === 'string' && t[tail + 2]?.text === ';')) {
        const parameters = [], start = t[tail]?.text === '{' ? t[tail].start : text.length, end = t[pairs.get(tail)]?.end ?? start;
        let partStart = i + 2, depth = 0;
        for (let j = partStart; j <= close; j++) {
          if (['(', '[', '<'].includes(t[j]?.text)) depth++;
          if ([')', ']', '>'].includes(t[j]?.text) && j !== close) depth--;
          if (j === close || t[j].text === ',' && depth === 0) {
            const part = t.slice(partStart, j), beforeDefault = part.slice(0, part.findIndex(x => x.text === '=') < 0 ? part.length : part.findIndex(x => x.text === '='));
            const nameToken = [...beforeDefault].reverse().find(x => x.kind === 'id' && !keywords.has(x.text));
            if (part.length) parameters.push(text.slice(part[0].start, part.at(-1).end));
            if (nameToken && beforeDefault.indexOf(nameToken) > 0) {
              const at = t.indexOf(nameToken); add(at, 'parameter', { type: typeBefore(at), scopeStart: start, scopeEnd: end, exported: false });
            }
            partStart = j + 1;
          }
        }
        const fn = add(i, scopes.some(s => ['class', 'interface'].includes(s.kind)) ? 'method' : 'function', { type, parameters, detail: `${type || ''} ${token.text}(${parameters.join(', ')})` });
        if (t[tail]?.text === '{') bodyScopes.set(tail, { kind: 'function', name: fn.name, start, end });
        i = close; continue;
      }
    }
    const type = typeBefore(i);
    if (type && ['=', ';', ',', '['].includes(next)) add(i, inFunction() ? 'variable' : scopes.some(s => ['class', 'interface'].includes(s.kind)) ? 'property' : 'variable', { type });
    else if (scopes.at(-1)?.kind === 'enum' && ['=', ',', '}'].includes(next)) add(i, 'enumMember', { type: container(), container: scopes.filter(s => ['namespace', 'class', 'interface'].includes(s.kind)).map(s => s.name).join('::') });
  }
  const directiveWords = directives.flatMap(d => tokenize(d.text.slice(1)).tokens.filter(t => t.kind === 'id').map(t => ({ ...t, start: d.start + 1 + t.start, end: d.start + 1 + t.end })));
  return { text, file, tokens: t, symbols, includes, directiveWords };
}

function wordAt(model, offset) { return model.tokens.find(t => t.kind === 'id' && t.start <= offset && offset <= t.end); }

function resolve(graph, model, offset) {
  const word = wordAt(model, offset);
  if (!word) return [];
  const index = model.tokens.indexOf(word), t = model.tokens;
  const own = model.symbols.find(s => s.start === word.start);
  if (own) return [own];
  const all = graph.flatMap(m => m.symbols.filter(s => m === model || s.exported));
  const locals = model.symbols.filter(s => s.name === word.text && !s.exported && s.start <= offset && s.scopeStart <= offset && offset <= s.scopeEnd);
  if (locals.length) return locals.sort((a, b) => (a.scopeEnd - a.scopeStart) - (b.scopeEnd - b.scopeStart) || b.start - a.start).slice(0, 1);
  if (t[index - 1]?.text === '.') {
    const receiver = t[index - 2];
    if (!receiver || receiver.kind !== 'id') return [];
    const variables = resolve(graph, model, receiver.start);
    if (variables.length !== 1 || !variables[0].type) return [];
    const variable = variables[0], names = [variable.type, `${variable.container}::${variable.type}`];
    return all.filter(s => s.name === word.text && names.includes(s.container));
  }
  let qualifier = '', j = index;
  while (j >= 2 && t[j - 1].text === '::' && t[j - 2].kind === 'id') { qualifier = t[j - 2].text + (qualifier ? '::' + qualifier : ''); j -= 2; }
  if (qualifier) {
    let lexical = model.symbols.filter(s => s.start <= offset && s.scopeStart <= offset && offset <= s.scopeEnd).sort((a, b) => b.container.length - a.container.length)[0]?.container || '';
    const owners = [qualifier];
    for (;;) { if (lexical) owners.push(`${lexical}::${qualifier}`); if (!lexical.includes('::')) break; lexical = lexical.slice(0, lexical.lastIndexOf('::')); }
    for (const owner of owners) {
      const matches = all.filter(s => s.name === word.text && (s.container === owner || s.kind === 'enumMember' && s.type === owner));
      if (matches.length) return matches;
    }
    return [];
  }
  const candidates = all.filter(s => s.name === word.text && s.exported);
  // Unqualified names first belong to the lexical namespace/class, then its parents.
  const context = model.symbols.filter(s => s.start <= offset && s.scopeStart <= offset && offset <= s.scopeEnd).sort((a, b) => b.container.length - a.container.length)[0]?.container || '';
  let owner = context;
  for (;;) {
    const selected = candidates.filter(s => s.container === owner);
    if (selected.length) return selected;
    if (!owner) break;
    owner = owner.includes('::') ? owner.slice(0, owner.lastIndexOf('::')) : '';
  }
  return [];
}

class LanguageWorkspace {
  constructor(documents = () => []) { this.documents = documents; this.cache = new Map(); this.rootCache = new Map(); this.generation = 0; }
  invalidate() { this.generation++; this.cache.clear(); this.rootCache.clear(); }
  async read(file) {
    const open = this.documents().find(d => d.uri.scheme === 'file' && path.resolve(d.uri.fsPath) === path.resolve(file));
    if (open) {
      const text = open.getText(), prior = this.cache.get(file);
      if (prior && prior.open && prior.model.text === text) return prior.model;
      const model = parse(text, file); this.cache.set(file, { open: true, model }); return model;
    }
    try {
      const stat = await fs.stat(file);
      if (!stat.isFile() || stat.size > 4 * 1024 * 1024) return null;
      const prior = this.cache.get(file);
      if (prior && !prior.open && prior.mtime === stat.mtimeMs && prior.size === stat.size) return prior.model;
      const model = parse(await fs.readFile(file, 'utf8'), file);
      if (this.cache.size >= 256) this.cache.clear();
      this.cache.set(file, { mtime: stat.mtimeMs, size: stat.size, model }); return model;
    } catch (error) { if (['ENOENT', 'ENOTDIR', 'EACCES', 'EPERM'].includes(error.code)) return null; throw error; }
  }
  async roots(file) {
    if (this.rootCache.has(file)) return this.rootCache.get(file);
    const roots = [], original = path.dirname(file);
    for (let directory = original, depth = 0; depth < 16; directory = path.dirname(directory), depth++) {
      roots.push(directory);
      try {
        const manifestFile = path.join(directory, 'vas-project.json');
        if ((await fs.stat(manifestFile)).size > 1024 * 1024) break;
        const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
        for (const unit of manifest.compilationUnits || []) if (typeof unit.entry === 'string') roots.push(path.dirname(path.resolve(directory, unit.entry)));
        break;
      } catch { /* malformed or absent manifest does not disable source editing */ }
      try { await fs.stat(path.join(directory, '.git')); break; } catch { }
      if (directory === path.dirname(directory)) break;
    }
    for (const root of (process.env.VAS_INCLUDE_PATH || '').split(path.delimiter).filter(Boolean)) roots.push(path.resolve(original, root));
    const unique = [...new Set(roots)];
    if (this.rootCache.size >= 256) this.rootCache.clear();
    this.rootCache.set(file, unique); return unique;
  }
  async dependency(file, include, roots) {
    if (path.isAbsolute(include.path) || include.path.includes(':')) return null;
    const relative = include.path.replace(/\\/g, '/');
    if (include.kind !== 'system') { const local = await this.read(path.resolve(path.dirname(file), relative)); if (local) return local; }
    const matches = new Map();
    for (const root of roots) { const candidate = await this.read(path.resolve(root, relative)); if (candidate) matches.set(candidate.file, candidate); }
    return matches.size === 1 ? [...matches.values()][0] : null;
  }
  async graph(document, cancel) {
    const source = parse(document.getText(), document.uri.fsPath), roots = await this.roots(source.file), graph = [], queue = [source], visited = new Set();
    let bytes = 0;
    while (queue.length && graph.length < 256 && !cancel?.isCancellationRequested) {
      const model = queue.shift(), identity = process.platform === 'win32' ? path.resolve(model.file).toLowerCase() : path.resolve(model.file);
      if (visited.has(identity)) continue;
      visited.add(identity); bytes += model.text.length;
      if (bytes > 16 * 1024 * 1024) break;
      graph.push(model);
      for (const include of model.includes) { const dependency = await this.dependency(model.file, include, roots); if (dependency) queue.push(dependency); }
    }
    return graph;
  }
}

module.exports = { tokenize, parse, resolve, wordAt, LanguageWorkspace, keywords, runtime };
