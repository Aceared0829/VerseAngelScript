# Verse AngelScript for VS Code

VAS 的 VS Code 基础扩展：`.vas` 语法高亮、注释/括号/缩进、代码片段，以及由真实 `vasbuild` / `vasrun` 驱动的构建、运行和 Problems 错误列表。

这是分阶段实现的第一步。当前没有语义补全、跨文件重命名、格式化器、后台诊断或 IDE 断点调试；完整验收范围见 [IDE 路线图](../../docs/ide-roadmap.md)。

## 安装与构建

VS Code 1.96 或更新版本；扩展开发与打包使用 Node.js 24。

```sh
cd tools/vscode-extension
npm ci
npm test
npm run package
```

在 VS Code 执行 **Extensions: Install from VSIX...**，选择生成的 `verseangelscript-vscode-0.1.0.vsix`。这里仅生成本地安装包，不发布到 Marketplace。

从仓库根目录构建本机工具：

```sh
cmake -S . -B out/build/vscode -DCMAKE_BUILD_TYPE=Release
cmake --build out/build/vscode --config Release
ctest --test-dir out/build/vscode --output-on-failure -C Release
```

Windows 正式工具链仍使用根目录的 Visual Studio 2026 / MSVC v145 预设。多配置生成器的可执行文件通常位于 `Release` 子目录。

## 配置

以文件夹方式打开 VAS 工程。在 **User Settings** 中设置可信工具的绝对路径；SSH、WSL 或 Dev Container 使用 **Remote Settings** 中该环境的路径：

```json
{
  "vas.compilerPath": "/absolute/path/to/vasbuild",
  "vas.runnerPath": "/absolute/path/to/vasrun"
}
```

Windows 使用本机 `.exe`，如 `C:\\VAS\\vasbuild.exe`。不接受 PATH 查找、`.cmd` / `.bat` 或 shell 命令。扩展不会从打开的工程自动发现或运行可执行文件。

工程/文件夹设置：

```json
{
  "vas.configFile": "${workspaceFolder}/.vas/vasbuild.config.txt",
  "vas.outputDirectory": "${workspaceFolder}/.vas/build"
}
```

`configFile` 必须对应应用实际注册的 API；不能用空配置替代完整运行时。仓库的 `templates/rider/vas-starter/.vas/vasbuild.config.txt` 对应现有 `vasrun` 示例宿主。相对路径与 `${workspaceFolder}` 按当前源文件所在工作区文件夹解析，支持多根工作区；暂不支持其他变量。

## 日常使用

- 保存工程的 `.vas` 文件，在入口文件执行 **VAS: Build Current File** 或 **VAS: Run Current File**；也可在编辑器右键菜单选择
- 当前文件会先保存；同一工作区还有未保存的 VAS 文件时会提示先保存，避免悄悄使用旧 include 内容
- Build 生成 `.vas/build/<源文件相对路径>.vasbc`，不同目录的同名文件不会相互覆盖
- Run 由 `vasrun` 重新编译并执行源文件，不执行 Build 产生的字节码
- 真实编译错误与警告进入 Problems，包含被 include 文件的诊断；完整工具输出保留在集成终端
- Problems 定位到行；编译器当前报告 UTF-8 字节列，不能直接当作 VS Code UTF-16 列使用，精确列映射在后续结构化诊断模块实现
- VAS 任务共用一个 Problems 诊断集合：后续任务可能替换或清除其他入口/工作区的诊断；并行任务不隔离诊断，较早启动的长任务结束时也可能清除较新的结果。还不是持久的全工程诊断服务
- 使用 VS Code 的 **Tasks: Terminate Task** 停止运行；程序可通过终端读取输入
- Restricted Mode 保留静态编辑功能，但禁用构建和运行；仅打开/编辑文件不会启动工具

可以用原生任务固定入口，即使正在编辑辅助文件也能构建整个模块：

```json
{
  "version": "2.0.0",
  "tasks": [{
    "label": "Build VAS project",
    "type": "vas",
    "operation": "build",
    "file": "src/main.vas",
    "group": { "kind": "build", "isDefault": true }
  }]
}
```

将其保存为工程 `.vscode/tasks.json` 后使用 **Run Build Task**。扩展不推断入口模块；将依赖入口声明的辅助文件直接编译可能产生编译错误。

Windows 现有命令行工具仍使用窄字符 `argv` 和部分文件 API；含非 ASCII 字符的入口、工程根目录、配置或输出路径尚未保证可用。这是运行时工具链需要补齐的能力，不以 JS 路径测试宣称已解决。

## 测试

`npm test` 覆盖实际 TextMate/Oniguruma 分词、任务参数与路径、Workspace Trust、未保存文件、输出目录安全以及编译器消息格式。

原生 Extension Host 测试要求已构建的工具，执行真实 build/run、Problems 跳转位置和错误消除，并分别启动可信与不可信工作区：

```sh
VAS_TEST_COMPILER=/absolute/path/to/vasbuild \
VAS_TEST_RUNNER=/absolute/path/to/vasrun \
npm run test:integration
```

Linux 无桌面环境时使用 `xvfb-run -a npm run test:integration`。默认验证最低支持版本 1.96.4；设置 `VSCODE_TEST_VERSION=stable` 验证当前稳定版。Windows、macOS 和 Linux 的真实客户端行为由 CI 分别测试，不用路径单元测试代替宿主验证。
