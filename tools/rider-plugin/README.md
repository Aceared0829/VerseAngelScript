# Verse AngelScript Rider Plugin

这是 VAS（Verse AngelScript）的 JetBrains Rider 插件源码工程，目标版本为 Rider 2026.2（Build 262）。

## 已实现

- 将小写 `.vas` 注册为 VAS 源文件。
- VAS 关键字、字符串、数字、注释、预处理指令和操作符高亮。
- 行注释、块注释、括号匹配和基础关键字补全。
- 项目级符号索引：类、接口、枚举、命名空间、函数与全局变量。
- 当前文件变量、运行时 API 和跨文件符号补全。
- 标识符引用解析及 `Ctrl+B` 跨文件声明跳转。
- Rider 的 **Build | Build Current VAS File** 动作。
- Rider 的 **Run | Run Current VAS File** 动作。
- Rider **New Project | Verse AngelScript** 起步项目生成器。
- 新项目内置 Windows x64 的 `vasbuild`、`vasrun` 和接口配置，创建后即可构建、运行。
- 可在 **Settings | Tools | VAS** 配置 `vasbuild.exe`、`vasrun.exe`、接口配置文件和字节码输出目录。

## 构建

本机安装 Rider 时：

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\JetBrains Rider 261.20362.35\jbr'
.\gradlew.bat buildPlugin -PriderPath='C:\Program Files\JetBrains\JetBrains Rider 261.20362.35'
```

未指定 `riderPath` 时，Gradle 会从 JetBrains 仓库获取 Rider 2026.2.0.2。

`test` 会同时执行普通语言单元测试和 Rider Solution Host 集成测试。后者会启动 Rider 前后端、打开 `testData/solutions/vas-navigation/VasNavigation.sln`，验证 `#include` 文件跳转与跨嵌套 include 的函数声明跳转。

宿主 fixture 使用与 VAS starter 一致的 NMake `.vcxproj`，显式列出所有 `.vas` 文件，使默认项目作用域来自 Rider 的真实项目模型。宿主测试需要 Windows 上已安装的 Visual Studio 2026 C++ 工具（v145；CI 的 `windows-latest` 已提供），通过 `BuildTool.AUTODETECT` 检测；不固定 Windows SDK 版本，不请求 .NET SDK、NuGet restore 或项目构建。打开解决方案后等待项目缓存和 solution builder 初始化，再保留原有项目作用域/索引断言及重命名/撤销检查。Gradle 会输出失败异常、cause 和完整堆栈。在下载/启动 Rider 前，可先运行 `python scripts/check-project-fixture.py`；它只用 Python 标准库检查项目 XML、解决方案 GUID/配置映射以及全部 VAS fixture 的显式项目项覆盖，不执行 MSBuild 或修改文件。

## 安装

构建后的 ZIP 位于 `build/distributions`。在 Rider 中打开：

**Settings | Plugins | 齿轮菜单 | Install Plugin from Disk**

选择 `VerseAngelScript-Rider-Plugin-0.5.6.zip` 后重启 Rider。仓库同时会在 `plugins/rider` 保留一份可直接安装的插件包。

## 后台诊断与项目安全

打开或编辑 `.vas` 文件触发的后台编译诊断，仅在 Rider 已信任项目且已配置 builder 路径时运行。项目配置中的可执行文件路径本身不代表信任；未信任或已释放的项目不会启动后台编译器。插件会在收集输入、处理排队请求和启动进程之前重新检查，不会自动更改信任状态或弹出信任提示。

显式 **Build/Run** 动作和起步项目生成器保持原有行为。原生 Rider 信任回归测试使用隔离的测试信任存储与项目级记录启动器，检查未信任文件的打开/编辑、排队请求、恢复信任后的普通高亮及诊断范围；不会执行项目提供的测试二进制文件。

原生信任测试的 JUnit XML 可用 `python scripts/check_trust_results.py <报告文件>` 检查。检查器要求指定原生测试实际通过，缺失、跳过或失败均返回非零状态；成功时只输出 `VAS_RIDER_TRUST_AUDIT_V1` 协议中的信任布尔值、过滤器类名/判定和枚举数量，不转发其他标准输出、错误或环境信息。CI 在启动 Rider 前执行解析器回归，在 Gradle `test buildPlugin` 之后执行证据检查，并分别保留两个命令的失败状态；证据检查成功不会掩盖 Gradle 或打包失败。解析器回归可用 `python -B -m unittest discover -s scripts -p test_trust_results.py` 单独执行。

## 解析与重命名的安全边界

普通标识符解析只使用当前文件及递归 `#include` 闭包；项目符号索引仍用于补全和显式的实现/继承搜索，不再作为普通引用的兜底。真实注释里的 `#include` 会被忽略，循环和菱形 include 不会重复加入声明。

- 同文件和 include 中的候选一起筛选，保留作用域遮蔽、参数/局部变量声明顺序及可识别的命名空间/成员所属类型。声明需要位于参数列表或声明语句起始处，`a && b`、`flags & mask` 和运算式中的函数调用不会被当作声明
- 函数按必需参数数到总参数数筛选，支持尾部默认参数。筛选为空时不会退回不匹配的声明
- 引用方向参数（`&in`/`&out`/`&inout`）及 `@const` 句柄参数/局部变量保留词法遮蔽；构造/析构函数的参数和局部变量不会被当作其他方法可见的成员
- 普通跨行字符串与三引号 heredoc 均保持为完整字符串 token；heredoc 中的名字、伪声明和 include 文本不参与导航或重命名，未闭合字符串也不会暴露内部标识符
- 数字词法匹配编译器支持的 `0b`/`0o`/`0d`/`0x` 进制前缀和数字间单引号分隔符，不会将数字后缀当作标识符，也不会吞掉相邻算式中的用法
- `Foo(...)` 在可唯一识别类名时绑定到类声明，保留隐式默认构造的跳转与类型重命名；此处不判断构造函数重载或实参类型。接口不能实例化，`IFoo(...)` 不作为有效构造解析
- 相同参数数的类型重载，以及默认参数范围重叠的重载，仍属于歧义。直接跳转和调用关系不任意选第一个；Go To Declaration 可以展示候选
- 不做参数类型推导、转换/重载排序、别名展开或继承成员绑定。复杂接收者（链式、索引、函数返回值）、未知类型/签名、不完整调用和含 `<`/`>` 的实参表达式保守地不解析；派生类中不能识别的成员也不会误绑定到同名全局符号
- 已识别的声明标识符本身不被算作其他声明的引用；只有唯一绑定的引用参与 Find Usages/Code Vision。显式实现/继承搜索仍是原有的语法级发现功能，不能当作类型精确的绑定证据
- 新名字限于非关键字的 ASCII 标识符（字母/下划线开头，后续可含数字）；`$` 始终非法，Unicode 新名字须先确认编译器属性 25，当前暂不提供。已有 Unicode 名字按原生 token 边界整体保留，包括 emoji、组合字符和非 ASCII 空白，不会将其 ASCII 后缀当作另一个符号或自动规范化名字；原生保留字前缀和 BOM 的特殊边界也按编译器处理
- include 支持单/双引号、`#include` 后无空格、跨行空白；同一行引号后的代码仍参与解析。依赖闭包按真实文件去重，并保留缺失文件或无法判定预处理的状态；不完整的闭包不产生确定的符号绑定。当前只支持相对 VAS 文件依赖，不支持绝对/驱动器路径、非 Windows 上的反斜杠路径、非 VAS 文件依赖和自定义加载回调
- 编译器会在同一根脚本的兄弟 include 片段之间共享全局符号，当前引用解析仍只使用当前文件的向外 include 闭包。重命名前还会检查所有已索引源文件的已知闭包：若旧名/新名出现在与声明共享某个根文件、但自身不能看到声明的片段中，且该名字不能证明为该片段的局部声明/局部引用，则拒绝该次操作。此处不猜测当前编译根，也不把兄弟片段解析成确定引用；没有共同根的模块及可证明的局部遮蔽不受该规则阻止
- 重命名前检查索引中的 VAS 文件及声明文件：先按旧名/新名标识符筛选可能相关的源文件（排除注释和字符串），相关文件的依赖不完整时拒绝该次操作，然后检查声明所在模块/include 闭包可见范围内的同名标识符，局部变量进一步限于其作用域。与旧名/新名无关的损坏模块不会阻止操作。相关文件的条件编译、未知指令、缺失依赖或未完成的字符串/注释仍需先修复或消除不确定性。出现可能指向目标的歧义引用或未解析用法时，预检会在写入前拒绝该次重命名；索引未就绪时也拒绝。默认重命名作用域和实际修改列表必须覆盖每个已知绑定用法；已索引但位于 Rider 项目模型之外的用法或用户缩小后的作用域不允许造成部分修改。存在显式构造/析构名称的类，以及可能与接口/基类实现相连的方法声明族，会按操作拒绝重命名，避免只改其中一部分；无此关联的类型与成员仍保留可验证的无歧义重命名能力。完整类型语义与未索引/外部文件的覆盖仍不在此实现范围内

验证包含 `VasSymbolScannerTest`、`VasSymbolSelectionTest`、`VasLexerTest` 以及真实 Rider Solution Host 的 `VasRiderSolutionIntegrationTest`。后者覆盖跨 include 重载、错误所属类型/参数数、歧义、默认参数、遮蔽、声明自身、未包含文件与循环 include；运行需要 Java 25 和 Rider 2026.2/Build 262。

类构造与结构声明族的语法边界另经实际 `vasbuild` 验证：隐式/显式类构造、析构、接口实现和基类 override 均可编译；接口实例化以及只重命名类/方法族的一部分会被编译器拒绝。宿主测试包含实际 `PsiReferenceService`、`ReferencesSearch`、`RenameProcessor` 拒绝/跨文件修改/单次撤销，以及缩小作用域时不修改文件的验证；另验证数字/heredoc 字面量不参与重命名、未闭合字符串和兄弟片段覆盖不明时拒绝修改、生命周期局部作用域、`@const` 接收者绑定、逻辑/位运算用法及三种 include 形式的跨文件重命名；依赖不完整的相关文件必须拒绝修改，无关文件则不阻止操作，并先断言 fixture 文件属于默认项目作用域且已进入符号索引。这些测试需在真实 Rider 宿主中执行。

Unicode 边界 fixture `unicode-identifiers.vas` 使用同目录上一级的 `unicode-identifiers.config.txt`（`ep 25 1`）经过实际编译器验证；宿主测试只把 ASCII 符号改为 ASCII 新名，并断言 Unicode 同后缀名字、BOM 分隔成员和撤销结果均完整保留。

## 显式 Build Project（源码模块）

新增 **Build | Build VAS Project**（`VAS.BuildProject`），读取当前 Rider 项目根目录的
`vas-project.json`，要求在对话框中确认一个明确的编译单元。即使只有一个单元，也不会
省略选择步骤。当前活动编辑器不决定编译入口；既有 Build Current / Run Current 保持原样。

先在 **Settings | Tools | VAS | Build Project native compiler (this application)**
选择绝对路径的原生 `vasbuild`。此设置保存在当前应用、禁用 roaming，与项目共享
`vas.xml` 的 builder/runner 字段独立；不会自动迁移、信任或执行项目提供的工具。
支持 ELF、Windows `.exe` 和 Mach-O，不支持 shell/batch 包装脚本。设置保存、打开项目、
索引或编辑文件都不会启动项目编译。项目必须已被 Rider 信任。

编译器独占项目配置解释：先调用 `--describe-project=json`，随后仅用
`--report=jsonl --project <manifest> --unit <id>`。新版多单元配置、同一入口的不同
host API、以及旧格式适配都来自编译器。缺少 manifest、旧编译器或错误协议会明确失败，
不会解析 manifest 猜测配置，也不会退回编译当前文件。插件不会预先创建输出目录或运行产物。

**VAS Project Build** 工具窗口显示本次单元的编译诊断。双击或 **Open diagnostic**
使用编译器的实际 section 和已验证的源文件快照导航；正数 UTF-8 字节位置转换为 Rider
UTF-16 点位，保留 tab、Unicode、BOM 和 CRLF 差异。未知/零位置、无效 UTF-8 源内容只提供
文件级位置；无效字节文件名或不能确认的 section 不绑定到任意文件。此模块不会创建另一套
编辑器波浪线，也不把依赖观察当作精确语义绑定。

只编译已保存输入：任何未保存的 `.vas`，以及 manifest、所选 host config/entry 的
未保存别名会阻止编译；不会自动保存。取消、重新发起请求、项目释放、信任/工具设置变化、
输入变化都会取消进程或拒绝旧结果。取消后不会将晚到结果显示为成功。文件改动不会自动重建。
依赖按 manifest + unit 独立记录，失败或不完整遍历保留之前的观察；只有当前完整遍历可以替换。

客户端限制：descriptor 16 MiB、单条报告 1 MiB、报告总计 64 MiB/100,000 条事件、
每个输入 16 MiB、输入快照总计 64 MiB/4096 个文件。描述超时 30 秒、构建超时 120 秒；
stderr 单独读取，保留前 16 KiB、总量限制 4 MiB。保存文件构建并非原子文件系统快照或对抗性
竞态沙箱；不要在构建期间改写目录、链接或外部依赖。尚不支持未保存缓冲区、项目运行或
编译器精确补全/重命名。

### 验证边界

`VasProjectProtocolTest`、`VasProjectProcessTest`、`VasProjectInputsTest` 覆盖纯 JVM
协议、真实子进程取消/限流、UTF-8 点位与快照。`VAS_TEST_COMPILER` 指向本次源码编译的
原生编译器时，`VasProjectProtocolCompilerTest` 验证真实协议往返。

`VasRiderProjectBuildIntegrationTest` 在真实 Rider Build 262 Solution Host 中通过注册动作
调用真实编译器，覆盖显式单元、不同 host、嵌套诊断和实际编辑器导航、旧格式与特殊路径、
脏输入、被动事件不执行工具、未信任项目、选择取消/排队请求替换、选择后的输入/工具/信任变化、
不完整依赖保留、缺少 manifest 无回退。宿主中的取消案例覆盖选择/排队阶段；已运行进程及其
子进程的终止由独立 JVM 测试验证，不能混称为宿主中的在途取消验证。

运行 `python -B -m unittest discover -s scripts -p test_project_build_results.py` 验证证据解析器。
CI 保留 Gradle、既有 trust gate、新 project-build gate 各自失败状态；缺失、跳过或失败的
任何必需原生案例都不能算通过。源码通过普通 JVM/编译器测试不等于已在 Rider 中运行，也不等于
仓库中旧安装 ZIP 已包含此模块；打包及原生宿主检查需对发布提交单独验证。
