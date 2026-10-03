# Verse AngelScript for VS Code

VAS 的 VS Code 基础扩展：`.vas` 语法高亮、注释/括号/缩进、代码片段，以及由真实 `vasbuild` / `vasrun` 驱动的构建、运行和 Problems 错误列表。

当前已交付编辑/当前文件任务基础，并增加独立的编译器工程构建客户端。当前没有语义补全、跨文件重命名、格式化器、后台诊断或 IDE 断点调试；完整验收范围见 [IDE 路线图](../../docs/ide-roadmap.md)。

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
- 在 VS Code 编辑任何磁盘文件时，会保守地清除所有 Build 诊断并放弃旧位置结果；项目构建还监视工作区 VAS/manifest 与已观察到的配置/include 文件变更并失效项目结果。保存后需再次显式 Build；不会因打开、编辑或任务枚举启动编译器
- Run 保留 ProcessExecution，支持交互式程序和终端输入。其终端问题匹配器只定位到行、共用 Run 诊断集合；Windows ConPTY（尤其 VS Code 1.96）可能损坏终端重建的长 Unicode 路径。需要精确 Problems 时请执行 Build
- 使用 VS Code 的 **Tasks: Terminate Task** 停止运行；程序可通过终端读取输入
- Restricted Mode 保留静态编辑功能，但禁用构建和运行；仅打开/编辑文件不会启动工具

仍可用原生当前文件任务固定入口（不读取工程 manifest），即使正在编辑辅助文件也能构建整个模块：

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

## 显式 Build Project

**VAS: Build Project** 是独立命令，不改变 Build Current File / Run Current File 的配置、参数或源文件运行方式。需要从本仓库当前源码构建、支持 `--describe-project=json` 与 `--report=jsonl --project` 的 `vasbuild`；旧版随模板附带的二进制不会自动升级。

1. 必须是可信的文件系统工作区，并在 User/Remote 的 machine 配置中指定绝对 compilerPath
2. 多根工作区总是先选择根，只读取所选根的 `vas-project.json`；不根据活动编辑器猜入口，也不递归寻找 manifest
3. 扩展显式调用编译器的只读 descriptor，再显示编译单元选择器，包含 entry、host API config 和 output；只有一个单元也必须选择，取消不构建、不创建输出
4. 实际 task 启动时重新 describe，检查 manifest/config/编译器设置的新鲜度、信任和未保存输入，再由编译器重新解析项目并构建该 id

扩展不解析另一份 manifest schema。v1 与现有 App1 风格的 legacy 数据都由同一个原生编译器规范化；legacy builder/runner 字段只产生迁移警告，永远不授权启动工程内工具。`hostApi.config` 是离线宿主声明，不承诺运行时实现或 UE SDK。

```json
{
  "version": "2.0.0",
  "tasks": [{
    "label": "Build VAS unit",
    "type": "vas",
    "operation": "buildProject",
    "project": "vas-project.json",
    "unit": "main",
    "group": "build"
  }]
}
```

project task 必须显式指定根 manifest 与大小写敏感的 unit；枚举/解析任务不运行 descriptor。项目输出的包含关系、源文件别名保护和延迟创建目录由原生 project 层执行，客户端预检不会创建目录。没有 Run Project；Run Current File 仍然由 vasrun 重新编译源文件，不能声称运行构建的字节码。

### 项目诊断、依赖与边界

- 项目构建用独立 stdout 管道消费严格的 vasbuild v1 JSONL；版本、顺序、UTF-8、终止记录和真实退出码必须一致。取消、截断、错误帧、传输失败或成功报告后非零退出都失败，不能显示成功
- 按编译器的 manifest identity + unit id 保存诊断和观察结果；同一 entry 的不同 config 单元、不同工作区相同 id 不相互覆盖。保留 compiler section 字符串与 rawBytes 身份；含无效字节的路径不把替换字符路径绑定到编辑器；实际加载的输入若没有可绑定的 Unicode 路径，无法验证脏编辑器别名，任务明确失败并仅保留部分依赖观察。有效路径上的无效 UTF-8 源内容仍使用文件级定位策略
- section_loaded/include 事件来自真实编译器上下文。完整且当前的结果替换该单元的依赖；不完整、取消或过期结果只合并观察，保留此前依赖。它们不是语义绑定图，不能用于保证重命名或导航正确
- 源码、manifest 和 config 的未保存文档阻止项目构建；配置别名/硬链接与 Windows 大小写差异也检查。扩展自己的项目预检不会自动保存输入；VS Code 的任务工作流可能先保存编辑器（`task.saveBeforeRun`，以及 VS Code 1.96 的 Rerun Last Task 自身的保存行为）。若宿主已经保存，构建使用保存后的内容；只要参与输入仍未保存，客户端就拒绝成功。此前观察到的 include 物理别名在启动前检查；首次构建才发现的 include 若对应未保存编辑器，构建结果会在发布前标为失败并丢弃诊断（原生工具可能已写出字节码，不能将其视为当前编辑内容的产物）。不因此阻止无关的未保存文本文件。文件级记录不会读取字节码/设备内容；有效 UTF-8 源字节支持 BOM、中文、emoji 与 UTF-16 列转换
- 编译前对 manifest、config、entry 与此前观察到的 include 记录稳定物理身份和内容摘要，发布前再次核对。延迟的同一次保存通知只有与该诊断代次的先验版本相同才保留结果；clean 文档重新加载也须同时匹配实际文本和磁盘版本。脏编辑、删除、身份替换、真正内容变化和无法证明的输入仍使结果失效；异步事件核对在启动与发布前等待完成
- 编译器观察到根外 include 后也注册文件监视。首次从报告才发现的 include 已被编译器读取，客户端不会用事后哈希冒充其编译前版本；该次收到它的变化通知仍保守失效，下次构建才建立先验版本。监视是操作系统的尽力通知，不是原子源码快照；尚未发现的外部文件、文件系统丢失事件、瞬变后恢复的并发写入仍有边界。不要在构建期间从外部修改输入；发生外部变更后重新显式构建。当前不支持完整未保存 compilation-unit 快照或后台编译
- 客户端安全上限：descriptor 16 MiB / 30 秒，manifest 验证 1 MiB，定位源码 16 MiB；已知输入验证每次最多 4096 个文件、单文件 16 MiB、总计 64 MiB，最多保留 256 个当前单元的验证版本。事件核对串行进行，待处理路径最多 64 个、连续事件批次最多 256 次/64 MiB，超限保守失效。JSONL 单行 1 Mi 字符、总输入 64 MiB、100000 事件，最多监视 512 个根外目录。报告/构建输入验证超限明确失败，不把截断图当完整。无法定位/不可读/无效 UTF-8 的源码使用文件或行定位；未选择且不属于该单元此前依赖的 config 不读取内容

## 测试

`npm test` 覆盖实际 TextMate/Oniguruma 分词、任务参数与路径、Workspace Trust（包括任务实际启动时复查）、未保存文件、输出目录安全、真实 Node 子进程管道/退出/取消、分片 UTF-8 解码、UTF-16 列转换、诊断隔离及过期结果防护。设置 `VAS_TEST_COMPILER` 时还使用真实 `vasbuild` 验证 descriptor、v1/legacy 项目构建、Unicode/BOM include、配置错误、新鲜度与无产物失败；CI 的 unit 步骤和宿主步骤都设置刚构建的工具。

原生 Extension Host 测试要求已构建的工具，执行真实 build/run、Problems 精确 URI/UTF-16 位置、入口隔离和编辑后的错误消除。Current、多根工程、脏/已保存 include 别名、真实 Rerun、单根 v1、legacy 和 Restricted Mode 使用独立宿主/profile；场景之间的 manifest 转换与输出清理只在宿主退出后进行。v1/legacy 仍使用同一批源文件/config，并逐字节比较真实编译产物。

Rerun 场景保留同一宿主中的编辑→实际宿主保存→原生构建，并记录保存、扩展实际 watcher 通知、任务关闭和诊断事件；收到保存文件的真实通知后仍检查当前警告。会话隔离不替代该保存后构建的验收。单会话上限 180 秒，任务启动/命令与发现请求有 15 秒上限；超时明确失败并报告最后阶段，只清理该测试创建的进程树，不跳过用例。

所有模式仍覆盖真实 Quick Pick、明确单元选择、取消、配置隔离、工程工具陷阱和 dirty 输入：

```sh
VAS_TEST_COMPILER=/absolute/path/to/vasbuild \
VAS_TEST_RUNNER=/absolute/path/to/vasrun \
npm run test:integration
```

Linux 无桌面环境时使用 `xvfb-run -a npm run test:integration`。默认验证最低支持版本 1.96.4；设置 `VSCODE_TEST_VERSION=stable` 验证当前稳定版。Windows、macOS 和 Linux 的真实客户端行为由 CI 分别测试，不用路径单元测试代替宿主验证。
