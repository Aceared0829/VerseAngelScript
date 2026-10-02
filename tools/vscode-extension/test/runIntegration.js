'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('node:child_process');
const { downloadAndUnzipVSCode } = require('@vscode/test-electron');

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
    const workspace = path.join(root, 'VAS project with spaces');
    await fs.mkdir(path.join(workspace, 'src'), { recursive: true });
    await fs.writeFile(path.join(workspace, 'src', 'main.vas'), 'void main() { int answer = 42; }\n');
    await fs.writeFile(path.join(workspace, 'src', 'warning.vas'), 'void main() { int a; int b = a; }\n');
    await fs.writeFile(path.join(workspace, 'src', 'broken.vas'), '#include "shared 文.vas"\nvoid main() {}\n');
    await fs.writeFile(path.join(workspace, 'src', 'shared 文.vas'), '/* 文😀 */ int Broken() { return ; }\n');
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'), path.join(workspace, 'api.txt'));
    await fs.mkdir(path.join(workspace, '.vscode'));
    await fs.writeFile(path.join(workspace, '.vscode', 'tasks.json'), JSON.stringify({ version: '2.0.0', tasks: [
      { label: 'Fixture build', type: 'vas', operation: 'build', file: 'src/main.vas' }
    ] }));
    const secondWorkspace = path.join(root, 'second project');
    await fs.mkdir(secondWorkspace);
    await fs.writeFile(path.join(secondWorkspace, 'main.vas'), 'void main() {}\n');
    await fs.copyFile(path.join(workspace, 'api.txt'), path.join(secondWorkspace, 'second-api.txt'));
    const workspaceFile = path.join(root, 'test.code-workspace');
    await fs.writeFile(workspaceFile, JSON.stringify({ folders: [{ path: workspace }, { path: secondWorkspace }] }));
    for (const mode of ['trusted', 'untrusted']) {
      const userData = path.join(root, mode);
      await fs.mkdir(path.join(userData, 'User'), { recursive: true });
      await fs.writeFile(path.join(userData, 'User', 'settings.json'), JSON.stringify({
        'security.workspace.trust.enabled': true,
        'security.workspace.trust.startupPrompt': 'never',
        'security.workspace.trust.emptyWindow': false,
        'update.mode': 'none', 'telemetry.telemetryLevel': 'off'
      }));
      // test-electron.runTests() unconditionally adds --disable-workspace-trust.
      // Launch its downloaded executable directly so Restricted Mode is real.
      await launch(executable, [workspaceFile, '--user-data-dir', userData,
        '--extensions-dir', path.join(userData, 'extensions'), '--disable-extensions',
        '--skip-welcome', '--skip-release-notes', '--disable-updates', '--no-sandbox',
        `--extensionDevelopmentPath=${path.resolve(__dirname, '..')}`,
        `--extensionTestsPath=${path.join(__dirname, 'integration.js')}`,
        ...(mode === 'trusted' ? ['--disable-workspace-trust'] : [])], mode);
    }
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
