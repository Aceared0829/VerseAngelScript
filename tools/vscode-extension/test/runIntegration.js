'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('node:child_process');

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
    rerunAlias: source('rerun alias include 文😀.txt'),
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
  if (includeAlias) {
    await fs.link(path.join(root, includeAlias.alias), path.join(root, includeAlias.include));
    await fs.link(path.join(root, includeAlias.include), path.join(root, includeAlias.rerunAlias));
  }
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

const hostTimeoutMs = 180000;
const shutdownTimeoutMs = 5000;

async function activeGroupMembers(group) {
  const exists = () => {
    try { process.kill(-group, 0); return true; }
    catch (error) { if (error.code === 'ESRCH') return false; throw error; }
  };
  if (!exists()) return [];
  // A killed orphan can remain a zombie until its new parent reaps it. Signal 0
  // alone cannot distinguish that harmless state from a live fixture writer.
  // On Linux -g selects the session; on macOS it selects the process group.
  // This detached launch owns both IDs. Filter the returned group explicitly.
  const output = await new Promise((resolve, reject) => {
    const reader = spawn('ps', ['-o', 'pid=,pgid=,stat=', '-g', String(group)], {
      shell: false, stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '', stderr = '';
    reader.stdout.setEncoding('utf8');
    reader.stderr.setEncoding('utf8');
    reader.stdout.on('data', chunk => { stdout += chunk; });
    reader.stderr.on('data', chunk => { stderr += chunk; });
    const timer = setTimeout(() => {
      reader.kill('SIGKILL');
      reject(new Error(`Timed out inspecting owned VS Code process group ${group}`));
    }, shutdownTimeoutMs);
    reader.once('error', error => { clearTimeout(timer); reject(error); });
    reader.once('close', code => {
      clearTimeout(timer);
      if (code === 0 || code === 1 && !stdout.trim()) resolve(stdout);
      else reject(new Error(`Cannot inspect owned VS Code process group ${group}: ps exited ${code}: ${stderr.trim()}`));
    });
  });
  const members = [];
  for (const line of output.trim().split('\n').filter(Boolean)) {
    const match = /^\s*(\d+)\s+(\d+)\s+(\S+)\s*$/.exec(line);
    if (!match) throw new Error(`Cannot parse process state for owned VS Code group ${group}`);
    if (Number(match[2]) === group) members.push({ pid: Number(match[1]), state: match[3] });
  }
  if (!members.length && exists()) throw new Error(`Cannot establish quiescence of owned VS Code process group ${group}`);
  return members.filter(member => !/^[ZX]/.test(member.state));
}

async function waitForGroupQuiescence(group) {
  const deadline = Date.now() + shutdownTimeoutMs;
  while (true) {
    const active = await activeGroupMembers(group);
    if (!active.length) return true;
    if (Date.now() >= deadline) return false;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
}

async function terminateHost(child, closed) {
  if (!Number.isInteger(child.pid) || child.pid <= 0) return;
  // detached gives this child an owned process group. Descendants may outlive
  // their leader, so also address the group after the leader has exited.
  const signal = name => {
    try { process.kill(-child.pid, name); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
  };
  signal('SIGTERM');
  let quiescent = false;
  try { quiescent = await waitForGroupQuiescence(child.pid); }
  catch { /* Still force termination if process-state inspection fails. */ }
  if (!quiescent) {
    signal('SIGKILL');
    if (!await waitForGroupQuiescence(child.pid)) {
      throw new Error(`Owned VS Code process group ${child.pid} remains active after SIGKILL`);
    }
  }
  let timer;
  try {
    await Promise.race([closed, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`VS Code PID ${child.pid} did not close after termination`)), shutdownTimeoutMs);
    })]);
  } finally { clearTimeout(timer); }
}

function launch(executable, args, mode, { timeoutMs = hostTimeoutMs } = {}) {
  if (process.platform === 'win32') {
    return require('./windowsJob').launchWindowsJob(executable, args, mode, { timeoutMs });
  }
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      detached: process.platform !== 'win32', shell: false, stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, VAS_TEST_MODE: mode }
    });
    const closed = new Promise(resolveClosed => child.once('close', resolveClosed));
    let settled = false, lastStage = '(no test stage received)';
    for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
      stream.setEncoding('utf8');
      let pending = '';
      const record = line => {
        const marker = line.indexOf('VAS_TEST_STAGE:');
        if (marker !== -1) lastStage = line.slice(marker + 'VAS_TEST_STAGE:'.length).trim();
      };
      stream.on('data', chunk => {
        destination.write(chunk);
        const lines = (pending + chunk).split(/\r?\n/);
        pending = lines.pop();
        for (const line of lines) record(line);
        // A host failure can interrupt a final stage line before its newline.
        record(pending);
        pending = pending.slice(-4096);
      });
    }
    const fail = async reason => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const error = new Error(`VS Code ${mode} tests ${reason}; last stage: ${lastStage}`);
      console.error(error.message);
      try { await terminateHost(child, closed); }
      catch (cleanupError) {
        error.hostCleanupFailed = true;
        error.message += `; child cleanup failed: ${cleanupError.message}`;
      }
      reject(error);
    };
    const timer = setTimeout(() => { void fail(`timed out after ${timeoutMs}ms`); }, timeoutMs);
    child.once('error', error => { void fail(`failed to launch: ${error.message}`); });
    child.once('exit', (code, signal) => {
      if (code !== 0) void fail(`exited ${code ?? signal}`);
    });
    child.once('close', async (code, signal) => {
      if (settled) return;
      if (code !== 0) { void fail(`exited ${code ?? signal}`); return; }
      settled = true;
      clearTimeout(timer);
      try {
        // Leader exit and closed pipes do not prove that ignored-stdio,
        // unref'd descendants have stopped touching this mode's fixtures.
        if (process.platform !== 'win32') await terminateHost(child, closed);
        resolve();
      } catch (cleanupError) {
        const error = new Error(`VS Code ${mode} tests exited 0 but child cleanup failed: ${cleanupError.message}; last stage: ${lastStage}`);
        error.hostCleanupFailed = true;
        reject(error);
      }
    });
  });
}

async function removeBuildArtifacts(workspaces) {
  for (const workspace of workspaces) {
    for (const directory of ['.vas', projectPaths.output]) {
      await fs.rm(path.join(workspace, directory), { recursive: true, force: true });
    }
  }
}

async function writeLegacyManifest(workspace, fixture) {
  const unit = fixture.manifest.compilationUnits[0];
  await writeJson(path.join(workspace, 'vas-project.json'), { name: 'Legacy 工程😀', entry: unit.entry,
    builderConfig: unit.hostApi.config, bytecodeOutput: unit.output, builder: fixture.trap, runner: fixture.trap });
}

async function main() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-vscode-test-'));
  let cleanupRoot = true;
  try {
    const { downloadAndUnzipVSCode } = require('@vscode/test-electron');
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
    const projectTasks = [
      ...fixture.manifest.compilationUnits.map(unit => ({ label: `Project ${unit.id}`, type: 'vas',
        operation: 'buildProject', project: 'vas-project.json', unit: unit.id })),
      { label: 'Project unknown-unit', type: 'vas', operation: 'buildProject', project: 'vas-project.json', unit: 'unknown-unit' }
    ];
    const tasksFile = path.join(workspace, '.vscode', 'tasks.json');
    await writeJson(tasksFile, { version: '2.0.0', tasks: [
      { label: 'Fixture build', type: 'vas', operation: 'build', file: 'src/main 文😀.vas' },
      ...projectTasks
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
    const singleFixture = await createProjectFixture(singleWorkspace, true);
    const workspaceFile = path.join(root, 'test.code-workspace');
    await writeJson(workspaceFile, { folders: [{ path: workspace }, { path: secondWorkspace }] });
    const workspaces = [workspace, secondWorkspace, singleWorkspace];
    // Separate hosts/profiles prevent queued saves, configuration updates and
    // filesystem events from a completed scenario invalidating the next one.
    for (const mode of ['current', 'project', 'alias-dirty', 'alias-saved', 'user-rerun-alias', 'user-rerun', 'single-root', 'legacy', 'untrusted']) {
      if (mode === 'legacy') await writeLegacyManifest(singleWorkspace, singleFixture);
      if (mode === 'untrusted') await writeLegacyManifest(workspace, fixture);
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
      await launch(executable, [mode === 'single-root' || mode === 'legacy' ? singleWorkspace : workspaceFile, '--user-data-dir', userData,
        '--extensions-dir', path.join(userData, 'extensions'), '--disable-extensions',
        '--skip-welcome', '--skip-release-notes', '--disable-updates', '--no-sandbox',
        `--extensionDevelopmentPath=${path.resolve(__dirname, '..')}`,
        `--extensionTestsPath=${path.join(__dirname, 'integration.js')}`,
        ...(mode !== 'untrusted' ? ['--disable-workspace-trust'] : [])], mode);
      if (mode === 'single-root') {
        const unit = singleFixture.manifest.compilationUnits[0];
        await fs.copyFile(path.join(singleWorkspace, unit.output),
          path.join(singleWorkspace, 'versioned-bytecode-reference.bin'));
      }
      // Only mutate artifacts after the preceding host has completely exited.
      // Retain source/config bytes and hardlinks across alias and legacy modes.
      await removeBuildArtifacts(workspaces);
    }
  } catch (error) {
    cleanupRoot = !error.hostCleanupFailed;
    throw error;
  } finally {
    if (cleanupRoot) await fs.rm(root, { recursive: true, force: true });
    else console.error(`Preserving fixtures because VS Code cleanup was not confirmed: ${root}`);
  }
}

if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
module.exports = { createProjectFixture, launch };
