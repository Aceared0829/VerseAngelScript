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
- Build 使用原生 VS Code Task 和 CustomExecution/Pseudoterminal；扩展通过独立 stdout/stderr 管道启动 `vasbuild`，不经过 shell 或 Windows ConPTY。真实错误与警告直接进入原生 Problems，Unicode 路径不会经过终端屏幕文本重建；完整输出仍流式显示在任务终端
- Build 将编译器的 UTF-8 字节列映射为 VS Code UTF-16 列，支持中文、emoji、制表符和 include 文件。不能读取源文件时退回到行定位
- Build 按入口保存独立诊断集合；其他入口、工作区的构建或 Run 不会清除它。相同入口重建会替换旧诊断，较旧的并发构建不能覆盖新结果
- 尚无依赖图：在 VS Code 编辑任何磁盘文件时，会保守地清除所有 Build 诊断，并放弃正在编译的旧位置结果；保存后需再次显式 Build。不会因打开或编辑文件启动编译器，也不提供后台文件变更监控
- Run 保留 ProcessExecution，支持交互式程序和终端输入。其终端问题匹配器只定位到行、共用 Run 诊断集合；Windows ConPTY（尤其 VS Code 1.96）可能损坏终端重建的长 Unicode 路径。需要精确 Problems 时请执行 Build
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

Windows 工具链使用 Unicode 参数和文件路径；原生工具与 Extension Host 测试覆盖中文、emoji 和空格的工程根目录、入口、include、配置与输出路径。Build 诊断通过 UTF-8 管道直接解析，不依赖旧版 Windows 终端的文本还原。

终端输出不整体缓存；单条诊断解析行限制为 64 Ki 字符，每次构建最多保留 2000 条诊断且路径和消息合计最多 1 Mi 字符。达到上限会在终端提示，完整工具输出仍可在终端滚动缓冲区查看。

## 测试

`npm test` 覆盖实际 TextMate/Oniguruma 分词、任务参数与路径、Workspace Trust（包括任务实际启动时复查）、未保存文件、输出目录安全、真实 Node 子进程管道/退出/取消、分片 UTF-8 解码、UTF-16 列转换、诊断隔离及过期结果防护。设置 `VAS_TEST_COMPILER` 时还会使用真实 `vasbuild` 验证 Unicode include 诊断和列位置。

原生 Extension Host 测试要求已构建的工具，执行真实 build/run、Problems 精确 URI/UTF-16 位置、入口隔离和编辑后的错误消除，并分别启动可信与不可信工作区：

```sh
VAS_TEST_COMPILER=/absolute/path/to/vasbuild \
VAS_TEST_RUNNER=/absolute/path/to/vasrun \
npm run test:integration
```

Linux 无桌面环境时使用 `xvfb-run -a npm run test:integration`。默认验证最低支持版本 1.96.4；设置 `VSCODE_TEST_VERSION=stable` 验证当前稳定版。Windows、macOS 和 Linux 的真实客户端行为由 CI 分别测试，不用路径单元测试代替宿主验证。
