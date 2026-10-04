'use strict';
const { tokenize } = require('./languageModel');

function inspectSyntax(text) {
  const { tokens, lexicalProblems, directives } = tokenize(text), problems = [...lexicalProblems], stack = [];
  const conditional = directives.some(d => /^#(?:if|ifdef|ifndef)\b/.test(d.text));
  if (conditional) return problems;
  for (const token of tokens) {
    if (['(', '[', '{'].includes(token.text)) stack.push(token);
    else if ([')', ']', '}'].includes(token.text)) {
      if (!stack.length || '([{'.indexOf(stack.at(-1).text) !== ')]}'.indexOf(token.text))
        problems.push({ start: token.start, end: token.end, message: `Unexpected '${token.text}'` });
      else stack.pop();
    }
  }
  const closes = { '(': ')', '[': ']', '{': '}' };
  for (const token of stack) problems.push({ start: token.start, end: token.end, message: `Expected '${closes[token.text]}'` });
  return problems;
}
function inCode(text, offset) {
  return !tokenize(text).protectedSpans.some(span => span.start < offset && (offset < span.end || offset === span.end && !span.closed));
}

function callAt(text, offset) {
  const { tokens } = tokenize(text), stack = []; let previous;
  for (const token of tokens) {
    if (token.start >= offset) break;
    if (['(', '[', '{'].includes(token.text)) stack.push({ name: token.text === '(' && previous?.kind === 'id' ? previous.text : '', nameOffset: previous?.start, open: token.start, parameter: 0 });
    else if ([')', ']', '}'].includes(token.text)) stack.pop();
    else if (token.text === ',' && stack.length) stack.at(-1).parameter++;
    previous = token;
  }
  return stack.reverse().find(call => call.name && !['if', 'for', 'while', 'switch', 'catch'].includes(call.name));
}

function signature(label) {
  const { tokens } = tokenize(label), open = tokens.findIndex(t => t.text === '('), parameters = [];
  if (open < 0) return { label, parameters };
  let nesting = 0, begin = tokens[open].end;
  for (const token of tokens.slice(open + 1)) {
    if (['(', '[', '{', '<'].includes(token.text)) nesting++;
    else if (token.text === ')' && nesting === 0 || token.text === ',' && nesting === 0) {
      let start = begin, end = token.start;
      while (start < end && /\s/.test(label[start])) start++;
      while (end > start && /\s/.test(label[end - 1])) end--;
      if (end > start) parameters.push([start, end]);
      begin = token.end; if (token.text === ')') break;
    } else if ([')', ']', '}', '>'].includes(token.text)) nesting--;
  }
  return { label, parameters };
}

module.exports = { inspectSyntax, callAt, signature, inCode };
