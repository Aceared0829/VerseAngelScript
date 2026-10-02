'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { runTests } = require('@vscode/test-electron');

async function main() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vas-vscode-test-'));
  try {
    const workspace = path.join(root, 'VAS project with spaces');
    await fs.mkdir(path.join(workspace, 'src'), { recursive: true });
    await fs.writeFile(path.join(workspace, 'src', 'main.vas'), 'void main() { int answer = 42; }\n');
    await fs.writeFile(path.join(workspace, 'src', 'broken.vas'), '#include "shared 文.vas"\nvoid main() {}\n');
    await fs.writeFile(path.join(workspace, 'src', 'shared 文.vas'), 'int Broken() { return ; }\n');
    await fs.copyFile(path.resolve(__dirname, '../../../tests/vasbuild/fixtures/minimal-config.txt'), path.join(workspace, 'api.txt'));
    for (const mode of ['trusted', 'untrusted']) {
      const userData = path.join(root, mode);
      await fs.mkdir(path.join(userData, 'User'), { recursive: true });
      await fs.writeFile(path.join(userData, 'User', 'settings.json'), JSON.stringify({
        'security.workspace.trust.enabled': true,
        'security.workspace.trust.startupPrompt': 'never',
        'security.workspace.trust.emptyWindow': false,
        'update.mode': 'none', 'telemetry.telemetryLevel': 'off'
      }));
      await runTests({
        version: process.env.VSCODE_TEST_VERSION || '1.96.4',
        extensionDevelopmentPath: path.resolve(__dirname, '..'),
        extensionTestsPath: path.join(__dirname, 'integration.js'),
        extensionTestsEnv: { VAS_TEST_MODE: mode },
        launchArgs: [workspace, '--user-data-dir', userData, '--disable-extensions', '--skip-welcome',
          '--skip-release-notes', ...(mode === 'trusted' ? ['--disable-workspace-trust'] : [])]
      });
    }
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
