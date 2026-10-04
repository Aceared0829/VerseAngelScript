# 三种 IDE 的安装与工程构建

Rider、VS Code、Visual Studio 2026 当前源码均支持显式 **Build Project**：使用同一原生 `vasbuild` 读取根目录 `vas-project.json`，由用户确认编译单元，再构建已保存输入。这里介绍当前适配器的用法；语义补全及其前置模块暂缓，历史计划保留在 [IDE 路线图](ide-roadmap.md)。

## 当前能力与宿主范围

| 能力 | Rider | VS Code | Visual Studio 2026 |
| --- | --- | --- | --- |
| `.vas` 编辑 | 词法高亮、注释、括号；已有启发式补全/导航/用法/重命名 | TextMate 高亮、注释、括号、缩进、代码片段 | 共用 VS Code 的词法/编辑配置，无代码片段注册 |
| 工程构建 | `Build VAS Project` | `VAS: Build Project` | `Build VAS Project...` |
| 工程诊断 | `VAS Project Build` 工具窗口、已验证位置导航 | Problems、任务终端、已验证位置导航 | Error List、`VAS Project Build` Output、已验证位置导航 |
| 当前文件 | 既有 Build / Run、受信任项目中的后台编译诊断 | Build / Run，无后台编译诊断 | 无对应扩展命令 |
| 执行授权 | Rider 项目信任 + 应用级编译器设置 | Workspace Trust + User/Remote 编译器设置 | 每次对话框的明确操作授权；原生信任同步未验证 |
| 声明的宿主范围 | Rider 2026.2 / Build `262.*` | `engines.vscode: ^1.96.0` | Windows、VS 18.x、x64 |
| 本轮真实宿主 CI | Windows Rider Build 262 Solution Host | Linux/macOS/Windows × 1.96.4/stable | Windows 实际 VS18，Open Folder 和 `.sln` |

Rider 编辑服务仍是启发式解析；逐次重命名预检不等于编译器绑定。另两端的词法资产不提供语义补全。三端均无 Run Project、完整未保存语义服务、LSP、DAP、UE SDK 或 C++ 功能对齐。Linux 工具/协议测试不能证明 Rider 或 VS2026 的其他平台宿主行为。

## 先准备独立的原生工具

扩展安装与编译器配置是两件事。Build Project 需要支持项目 descriptor、JSONL 和 loaded-source 内容证明的当前源码版 `vasbuild`；不要选模板内的旧 `vasbuild.exe`。工程内的 legacy `builder` / `runner` 字段只作迁移元数据，不决定三端项目构建要执行哪个程序。

从仓库根目录构建 Windows x64 工具，需要 Visual Studio 2026 C++ 工具（MSVC v145）和支持 VS2026 生成器的 **CMake 4.2 或更新版本**：

```powershell
cmake --preset windows-msvc-v145-cxx23
cmake --build --preset windows-msvc-v145-cxx23-release --target vasbuild vasrun
```

产物为 `out/build/windows-msvc-v145-cxx23/Release/vasbuild.exe` 与 `vasrun.exe`。只做项目构建时不需要 `vasrun`。

Linux/macOS 的工具构建需要 CMake 3.21+、支持 C++23 的编译器及相应构建工具；以下使用单配置 Makefiles：

```sh
cmake -S . -B out/build/ide-tools -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build out/build/ide-tools --target vasbuild vasrun
```

产物为 `out/build/ide-tools/vasbuild` 与 `vasrun`。配置 IDE 时填该环境中可信可执行文件的**绝对路径**，不填 shell 命令、PATH 中的名字或 `.cmd` / `.bat` 包装脚本。

## 获取 CI 安装包或自行打包

优先选择目标提交对应、**整个工作流已成功结束**的 GitHub Actions 运行。在该运行的 Artifacts 中按 IDE 下载：

| IDE | 工作流 | 安装产物前缀 | 安装兼容范围 |
| --- | --- | --- | --- |
| Rider | [Automated test](../.github/workflows/auto-test.yml) | `vas-rider-` | Build `262.*` |
| VS Code | [VS Code extension](../.github/workflows/vscode-extension.yml) | `vas-vscode-` | `^1.96.0`；选相应 OS/测试版本的验收记录 |
| VS2026 | [Visual Studio 2026 extension](../.github/workflows/visualstudio-extension.yml) | `vas-vs2026-` | Windows VS `[18.0,19.0)`、x64 |

安装产物仅在各自打包和必需验收成功后上传，保留 **7 天**。VS Code 的六个 OS/版本矩阵单元各有独立产物；一个单元上传成功不能代替整个工作流通过。名称包含实际 checkout 提交、runner OS/架构、IDE 版本与兼容范围；VS Code 还区分请求的 `1.96.4` / `stable`。Rider 版本来自固定 Gradle 运行时配置，VS Code/VS2026 记录实际宿主版本；不要将一个平台的记录解释为全部平台保证。

先解压 Actions 下载的外层 ZIP，里面仅有原始安装 ZIP/VSIX、`manifest.json` 和 `SHA256SUMS`。核对 manifest 的源码 commit/tree、宿主和包 SHA-256，再按下方对应 IDE 安装**内层安装包**。PR 工作流可能构建合并 checkout；manifest 分别记录实际构建 commit 和 PR head，不能将两者混为一谈。`vs2026-editor-project-results` 是独立的测试证据包，不能安装。

这些是 CI 开发产物，不是 Releases 或 Marketplace 发布；未更改扩展版本号，也没有新增签名流程。过期或找不到成功运行的产物时，可从所需提交自行打包。三端 Build Project 仍需单独构建并设置上述原生编译器。

### 从源码打包和安装

#### Rider

安装 Java 25，设置 `JAVA_HOME` 后，在 `tools/rider-plugin` 中运行：

```powershell
.\gradlew.bat buildPlugin --no-daemon
```

默认下载 Rider `2026.2.0.2`。使用已有安装时加 `-PriderPath='C:\Path\To\Rider-262'`，必须匹配 Build 262。ZIP 位于 `tools/rider-plugin/build/distributions/`。在 Rider **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择本次生成的 ZIP，按提示重启。

仓库 `plugins/rider/VerseAngelScript-Rider-Plugin-0.5.6.zip` 是较早的已检入安装包，不能当作当前源码产物；源码版本号仍为 `0.5.6`，同版本号不表示相同内容。当前适配能力应使用本次源码打包的 ZIP。[Rider 详细说明](../tools/rider-plugin/README.md)

#### VS Code

扩展打包使用 Node.js 24。在仓库根目录运行：

```sh
cd tools/vscode-extension
npm ci
npm run package
```

产物为 `tools/vscode-extension/verseangelscript-vscode-0.1.0.vsix`。在 VS Code 执行 **Extensions: Install from VSIX...**，选择该文件。[VS Code 详细说明](../tools/vscode-extension/README.md)

#### Visual Studio 2026

在 Windows 的 VS2026 **Developer PowerShell** 中，从仓库根目录执行；需要 **Visual Studio extension development** 工作负载及该实例的 MSBuild：

```powershell
MSBuild.exe tools/visualstudio-extension/VerseAngelScript.VisualStudio.csproj /restore /t:Build /p:Configuration=Release /p:DeployExtension=false
Get-ChildItem tools/visualstudio-extension/bin/Release -Filter *.vsix -Recurse
```

VSIX 在 `tools/visualstudio-extension/bin/Release` 下（包括其子目录）。关闭 Visual Studio，打开生成的 `.vsix`，在 VSIX Installer 中选择兼容的 VS2026 实例并安装，再重新打开 IDE。它与 VS Code 的 `.vsix` 不能互换；包内不附带 `vasbuild.exe`。

完整原生验收入口是 `tools/visualstudio-extension/scripts/test-vs2026.ps1`，需要额外的 C++ 工具、CMake、Python 和 `dotnet` 测试命令，并会启动隔离的真实 VS18 测试宿主、重置实验根设置。日常安装不需要运行该脚本。[VS2026 详细说明](../tools/visualstudio-extension/README.md)

## 选一个真实工程

三端只使用所选工程根目录中的 `vas-project.json`，不递归查找，也不根据活动编辑器猜测入口。所有路径由原生编译器解释；`hostApi.config` 只描述编译所需的宿主 API，并不提供它们的运行实现。

- **Rider 起步工程**：直接打开已检入的 [`templates/rider/vas-starter/VASStarter.sln`](../templates/rider/vas-starter/VASStarter.sln)，无需先安装模板。其同目录 legacy manifest 由当前编译器适配为 `main` 单元，入口为 `src/main.vas`，项目构建产物为 `out/vas/main.vasbc`；迁移警告是预期行为。VS Code/VS2026 也可打开该模板目录。这里指扩展的 Build Project，既有 MSBuild 解决方案构建与 Current File 命令使用各自配置
- **多单元与错误诊断**：VS Code/VS2026 可打开 [`tests/vasbuild/fixtures/project-contract`](../tests/vasbuild/fixtures/project-contract) 文件夹。选择 `main` 应生成 `build/main.vasbc`；`other-host` 刻意使用不声明 `hostCall` 的配置，应编译失败。这个 fixture 只验证编译，没有 `hostCall` 的可执行实现，不应用 Run Current File 运行

也可先在仓库根目录用 Windows 原生工具核对第二个 fixture：

```powershell
& .\out\build\windows-msvc-v145-cxx23\Release\vasbuild.exe --describe-project=json tests/vasbuild/fixtures/project-contract/vas-project.json
& .\out\build\windows-msvc-v145-cxx23\Release\vasbuild.exe --report=jsonl --project tests/vasbuild/fixtures/project-contract/vas-project.json --unit main
```

Linux/macOS 将可执行文件换为 `./out/build/ide-tools/vasbuild` 即可。descriptor 只读配置，不编译、不创建输出；构建必须显式传入区分大小写的 unit，即使只有一个。自己的新工程格式见 [manifest 协议](vas-project.md)，诊断格式见 [JSONL 协议](vas-build-report.md)。

## 三端日常操作

### Rider

1. 在 **Settings → Tools → VAS → Build Project native compiler (this application)** 指定当前 `vasbuild`。这是应用级、不漫游的独立设置；同页 `vasbuild executable` / `vasrun executable` 等项目设置属于既有 Current File/后台诊断流程
2. 在 Rider 中信任你要构建的项目，保存输入，执行 **Build → Build VAS Project**（`VAS.BuildProject`）
3. descriptor 返回后，在对话框确认所需单元并点击 **Build selected unit**。列表初始显示首个单元，仍须明确确认；不要把当前打开的 `.vas` 当作所选入口
4. 在 **VAS Project Build** 工具窗口查看结果，双击诊断或点击 **Open diagnostic** 跳转。用窗口的 **Cancel** 取消；新 Build 请求会替换旧请求

既有当前文件动作 ID 为 `VAS.BuildCurrentFile` / `VAS.RunCurrentFile`。Build Project 不增加另一套编辑器波浪线，也不会设置这些动作所需的 builder/runner。

### VS Code

1. 在 **User Settings** 设置 `vas.compilerPath`；SSH/WSL/Dev Container 则在 **Remote Settings** 填该环境的路径。该设置为 machine scope，不从 workspace 设置获取执行权限。只有当前文件 Run 另需 `vas.runnerPath`
2. 信任文件系统工作区，保存输入，运行 **VAS: Build Project**（`vas.buildProject`）。多根工作区先选根，再在 **VAS: Select Compilation Unit** 中选 unit；单单元也不跳过选择
3. 查看 **Problems** 与该构建的任务终端。使用 **Tasks: Terminate Task** 停止活动任务；在选择器中取消不会开始构建

固定任务可用 `type: "vas"`、`operation: "buildProject"`、`project: "vas-project.json"`、显式 `unit`；完整 `.vscode/tasks.json` 示例见 [扩展说明](../tools/vscode-extension/README.md#显式-build-project)。既有 `vas.buildCurrentFile` / `vas.runCurrentFile` 使用 `vas.configFile` / `vas.outputDirectory`，不读取项目 manifest；Run 重新编译并执行源码，不运行 Build 产物。

### Visual Studio 2026

1. 在 **Tools → Options → VerseAngelScript → Toolchain → Compiler executable** 填当前 `vasbuild.exe` 的绝对路径（用户设置 `CompilerPath`）
2. 打开 `.sln` 或使用 **Open Folder**，确保 manifest 位于解决方案目录或所开文件夹根目录；保存输入
3. 执行 **Tools → Build VAS Project...**（`VerseAngelScript.BuildProject`）。核对 root、manifest、compiler 后点击 **Read Project Units**；此前取消不会执行任何工具
4. 明确选中单元，核对 entry、host API、output，再点击 **Build Selected Unit**；此阶段取消不会开始构建。用 **Tools → Cancel VAS Build**（`VerseAngelScript.CancelBuild`）停止活动操作；活动期间重复 Build 会被忽略
5. 在 **Error List** 查看编译诊断并导航，在 **Output → VAS Project Build** 查看状态

两次按钮操作仅授权本次 descriptor/build，不建立持久 workspace trust。公开 SDK 的原生 trust-state 查询与实际 MOTW（网络来源标记）提示/取消行为仍未验证，不能把打开文件夹当作已通过原生信任检查。取消只保证终止扩展拥有的直接编译器进程，不承诺终止其子孙进程。

## 保存、失效与常见问题

- **先保存再构建**：三端项目构建均以磁盘输入为准；未保存源码、manifest、所选 host config 及相关物理别名会阻止构建或结果发布。Rider 不自动保存；VS Code 自身任务流程可能按 `task.saveBeforeRun` 或旧版 Rerun 行为先保存。首次编译才发现的 dirty include 可能在产物已写出后使结果被拒绝，此产物不能代表未保存编辑内容
- **编辑后重新 Build**：输入或编译器设置变化以及取消/关闭会使旧结果失效；Rider/VS Code 还检查各自原生信任状态，VS2026 不宣称已同步原生信任变化。保存、打开、索引及依赖通知不会自动触发项目重建。Rider 既有后台当前文件诊断是独立功能
- **有消息但不能精确跳转**：精确位置依赖已验证源码和 UTF-8 字节列到 UTF-16 的转换。不能证明位置时，Rider/VS Code 可能只提供文件/行级位置；VS2026 保留不可导航消息，不猜测列号。使用与扩展匹配的当前编译器，保存或重新加载输入后再构建
- **找不到 manifest / 协议不兼容**：检查实际工程根及独立 compiler 设置；不会回退到编译当前文件。legacy 迁移警告不代表已调用 manifest 中的旧工具
- **构建期间不要改写输入或链接**：loaded-source SHA-256 证明实际加载字节，不是原生文件句柄身份或原子文件系统快照。并发外部修改后应重新构建。各端资源上限、取消与测试边界见扩展各自 README
