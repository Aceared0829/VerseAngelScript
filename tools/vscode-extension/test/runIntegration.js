'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('node:child_process');
const { downloadAndUnzipVSCode } = require('@vscode/test-electron');

const projectPaths = {
  source: 'project sources 文😀 $&;',
  config: 'project config 接口😀 $&;',
  output: 'project output 出力😀 $&;'
};

async function writeJson(file, value) { await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n'); }

async function createProjectFixture(root, single = false) {
  for (const directory of [projectPaths.source, projectPaths.config, 'trap tools $&;']) {
    await fs.mkdir(path.join(root, directory), { recursive: true });
  }
  const source = name => `${projectPaths.source}/${name}`;
  const config = name => `${projectPaths.config}/${name}`;
  const output = name => `${projectPaths.output}/${name} 出力😀 $&;.vasbc`;
  const includeAlias = single ? undefined : {
    unit: 'included-file-alias', entry: source('alias entry 文😀.vas'),
    include: source('alias include 文😀.vas'), alias: source('alias include 文😀.txt'),
    unrelated: source('unrelated notes 文😀.txt')
  };
  const files = {
    [source('main 文😀.vas')]: '#include "library 文😀.vas"\nvoid main() { int result = LibraryValue(); }\n',
    [source('library 文😀.vas')]: 'int LibraryValue() { return 42; }\n',
    [source('warning 文😀.vas')]: 'void main() { int a; int b = a; }\n',
    [source('broken 文😀.vas')]: '#include "broken include 文😀.vas"\nvoid main() {}\n',
    [source('broken include 文😀.vas')]: '/* 文😀 */ int Broken() { return ; }\n',
    [source('host 文😀.vas')]: 'void main() { HostOnly(); }\n',
    [config('empty 文😀.txt')]: '// Empty application interface\n',
    [config('host 文😀.txt')]: 'func "int HostOnly()"\n',
    [config('invalid 文😀.txt')]: 'func "nonsense invalid syntax"\n'
  };
  if (includeAlias) Object.assign(files, {
    [includeAlias.entry]: '#include "alias include 文😀.vas"\nvoid main() { int result = AliasValue(); }\n',
    [includeAlias.alias]: 'int AliasValue() { int value; int copy = value; return 42; }\n',
    [includeAlias.unrelated]: 'Unrelated project notes\n'
  });
  for (const [name, content] of Object.entries(files)) await fs.writeFile(path.join(root, name), content);
  // Hardlinks exercise physical identity on Windows without symlink privileges.
  // Never skip this regression or silently replace the alias with a copied file.
  if (includeAlias) await fs.link(path.join(root, includeAlias.alias), path.join(root, includeAlias.include));
  // The manifest's historical tool fields must never be discovered or invoked.
  // The process audit in the Extension Host also catches a failed spawn attempt,
  // including Windows refusing to launch a batch file without a shell.
  const trap = process.platform === 'win32' ? 'trap tools $&;/tool trap.cmd' : 'trap tools $&;/tool trap.sh';
  await fs.writeFile(path.join(root, trap), process.platform === 'win32' ?
    '@echo off\r\necho executed>"%~dp0..\\tool-trap-executed"\r\nexit /b 93\r\n' :
    '#!/bin/sh\nprintf executed > "$(dirname "$0")/../tool-trap-executed"\nexit 93\n');
  await fs.chmod(path.join(root, trap), 0o755);
  await fs.mkdir(path.join(root, '.vscode'), { recursive: true });
  await writeJson(path.join(root, '.vscode', 'settings.json'), {
    'vas.compilerPath': path.join(root, trap), 'vas.runnerPath': path.join(root, trap)
  });
  const unit = (id, entry, api = 'empty 文😀.txt') => ({ id, entry: source(entry),
    hostApi: { config: config(api) }, output: output(id) });
  const compilationUnits = single ? [unit('only-unit', 'main 文😀.vas')] : [
    unit('main-unit', 'main 文😀.vas'),
    unit('warning-unit', 'warning 文😀.vas'),
    unit('broken-include', 'broken 文😀.vas'),
    unit('host-missing', 'host 文😀.vas'),
    unit('host-present', 'host 文😀.vas', 'host 文😀.txt'),
    unit('invalid-config', 'main 文😀.vas', 'invalid 文😀.txt')
  ];
  // Append only: existing tests intentionally select the first/main and second/warning units.
  if (includeAlias) compilationUnits.push({ id: includeAlias.unit, entry: includeAlias.entry,
    hostApi: { config: config('empty 文😀.txt') }, output: output(includeAlias.unit) });
  const manifest = { schemaVersion: 1, name: 'Project 工程😀 $&;', compilationUnits };
  await writeJson(path.join(root, 'vas-project.json'), manifest);
  await writeJson(path.join(root, 'integration-project-fixture.json'), { projectPaths, trap, manifest, includeAlias });
  return { projectPaths, trap, manifest, includeAlias };
}

function launch(executable, args, mode) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { stdio: 'inherit', env: { ...process.env, VAS_TEST_MODE: mode } });
    child.once('error', reject);
    child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`VS Code ${mode} tests exited ${code ?? signal}`)));
  });
}

async function main() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-vscode-test-'));
  try {
    const executable = await downloadAndUnzipVSCode(process.env.VSCODE_TEST_VERSION || '1.96.4');
    // Exercise native UTF-16 argv/filesystem boundaries on Windows, including
    // surrogate pairs, spaces and literal shell metacharacters in project paths.
    const workspace = path.join(root, 'VAS project 工程 文😀 with spaces $&;');
    await fs.mkdir(path.join(workspace, 'src'), { recursive: true });
    await fs.writeFile(path.join(workspace, 'src', 'main 文😀.vas'), 'void main() { int answer = 42; }\n');
    await fs.writeFile(path.join(workspace, 'src', 'warning.vas'), 'void main() { int a; int b = a; }\n');
    await fs.writeFile(path.join(workspace, 'src', 'broken.vas'), '#include "shared 文😀.vas"\nvoid main() {}\n');
    await fs.writeFile(path.join(workspace, 'src', 'shared 文😀.vas'), '/* 文😀 */ int Broken() { return ; }\n');
    await fs.mkdir(path.join(workspace, 'config 文😀'));
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'),
      path.join(workspace, 'config 文😀', 'api 接口😀.txt'));
    const fixture = await createProjectFixture(workspace);
    await fs.mkdir(path.join(workspace, '.vscode'), { recursive: true });
    await writeJson(path.join(workspace, '.vscode', 'tasks.json'), { version: '2.0.0', tasks: [
      { label: 'Fixture build', type: 'vas', operation: 'build', file: 'src/main 文😀.vas' },
      ...fixture.manifest.compilationUnits.map(unit => ({ label: `Project ${unit.id}`, type: 'vas',
        operation: 'buildProject', project: 'vas-project.json', unit: unit.id })),
      { label: 'Project unknown-unit', type: 'vas', operation: 'buildProject', project: 'vas-project.json', unit: 'unknown-unit' }
    ] });
    const secondWorkspace = path.join(root, 'second project 二😀 $&;');
    await fs.mkdir(secondWorkspace);
    await fs.writeFile(path.join(secondWorkspace, 'main 二😀.vas'), 'void main() {}\n');
    await fs.mkdir(path.join(secondWorkspace, 'config 二😀'));
    await fs.copyFile(path.join(workspace, 'config 文😀', 'api 接口😀.txt'),
      path.join(secondWorkspace, 'config 二😀', 'second api 二😀.txt'));
    await createProjectFixture(secondWorkspace, true);
    const singleWorkspace = path.join(root, 'single project 单😀 $&;');
    await fs.mkdir(singleWorkspace);
    await createProjectFixture(singleWorkspace, true);
    const workspaceFile = path.join(root, 'test.code-workspace');
    await writeJson(workspaceFile, { folders: [{ path: workspace }, { path: secondWorkspace }] });
    for (const mode of ['trusted', 'single-root', 'untrusted']) {
      if (mode === 'untrusted') {
        const unit = fixture.manifest.compilationUnits[0];
        await writeJson(path.join(workspace, 'vas-project.json'), { entry: unit.entry,
          builderConfig: unit.hostApi.config, bytecodeOutput: unit.output, builder: fixture.trap, runner: fixture.trap });
      }
      const userData = path.join(root, mode);
      await fs.mkdir(path.join(userData, 'User'), { recursive: true });
      await writeJson(path.join(userData, 'User', 'settings.json'), {
        'security.workspace.trust.enabled': true,
        'security.workspace.trust.startupPrompt': 'never',
        'security.workspace.trust.emptyWindow': false,
        'update.mode': 'none', 'telemetry.telemetryLevel': 'off',
        // Test extension dirty-input guards with deterministic unsaved editors.
        // The workbench's own save-before-run must not clear the test condition.
        'task.saveBeforeRun': 'never', 'files.autoSave': 'off',
        'vas.compilerPath': process.env.VAS_TEST_COMPILER,
        'vas.runnerPath': process.env.VAS_TEST_RUNNER
      });
      // test-electron.runTests() unconditionally adds --disable-workspace-trust.
      // Launch its downloaded executable directly so Restricted Mode is real.
      await launch(executable, [mode === 'single-root' ? singleWorkspace : workspaceFile, '--user-data-dir', userData,
        '--extensions-dir', path.join(userData, 'extensions'), '--disable-extensions',
        '--skip-welcome', '--skip-release-notes', '--disable-updates', '--no-sandbox',
        `--extensionDevelopmentPath=${path.resolve(__dirname, '..')}`,
        `--extensionTestsPath=${path.join(__dirname, 'integration.js')}`,
        ...(mode !== 'untrusted' ? ['--disable-workspace-trust'] : [])], mode);
    }
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
module.exports = { createProjectFixture };
