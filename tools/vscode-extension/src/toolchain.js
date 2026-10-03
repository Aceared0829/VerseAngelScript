'use strict';

const path = require('node:path');

function contains(root, file, paths = path) {
  const relative = paths.relative(root, file);
  return relative !== '..' && !relative.startsWith(`..${paths.sep}`) && !paths.isAbsolute(relative);
}

function resolvePath(value, root, label, paths = path) {
  if (typeof value !== 'string' || !value.trim() || value.includes('\0')) {
    throw new Error(`${label} must be a non-empty path.`);
  }
  const expanded = value.replaceAll('${workspaceFolder}', root);
  if (/\$\{[^}]*\}/.test(expanded)) {
    throw new Error(`${label} only supports the \${workspaceFolder} variable.`);
  }
  return paths.resolve(root, expanded);
}

/** A pure plan: never searches PATH, starts a shell, or executes a workspace file. */
function createPlan({ trusted, operation, root, file, settings }, paths = path) {
  if (!trusted) throw new Error('Trust this workspace before building or running VAS.');
  if (operation !== 'build' && operation !== 'run') throw new Error('Unknown VAS task operation.');
  if (!root || !paths.isAbsolute(root)) throw new Error('Open the VAS project as a filesystem workspace folder.');
  const source = resolvePath(file, root, 'VAS source', paths);
  if (!contains(root, source, paths)) throw new Error('The VAS entry must be inside its workspace folder.');
  if (paths.extname(source) !== '.vas') throw new Error('VAS sources must use the lowercase .vas extension.');
  const key = operation === 'build' ? 'compilerPath' : 'runnerPath';
  const executable = settings[key];
  if (typeof executable !== 'string' || !paths.isAbsolute(executable) || executable.includes('\0') || executable.includes('${')) {
    throw new Error(`Set vas.${key} to the absolute path of a trusted executable in User or Remote settings.`);
  }
  // Windows .cmd/.bat files require a shell; do not silently introduce one.
  if (paths === path.win32 && paths.extname(executable).toLowerCase() !== '.exe') {
    throw new Error(`vas.${key} must name a native .exe executable on Windows.`);
  }
  if (operation === 'run') {
    return { executable, args: [source], cwd: paths.dirname(source), source };
  }
  const config = resolvePath(settings.configFile, root, 'vas.configFile', paths);
  const outputRoot = resolvePath(settings.outputDirectory, root, 'vas.outputDirectory', paths);
  if (!contains(root, outputRoot, paths)) throw new Error('vas.outputDirectory must stay inside the workspace folder.');
  const relativeSource = paths.relative(root, source);
  const output = paths.join(outputRoot, relativeSource.slice(0, -4) + '.vasbc');
  return { executable, args: [config, source, output], cwd: paths.dirname(source), source, config, output, outputRoot };
}

// Filesystem matching is conservative on Windows; compiler identity keys remain untouched.
function sameFileName(first, second, paths = path) { return paths.relative(first, second) === ''; }

module.exports = { contains, resolvePath, createPlan, sameFileName };
