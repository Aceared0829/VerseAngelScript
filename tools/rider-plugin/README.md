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

## 安装

构建后的 ZIP 位于 `build/distributions`。在 Rider 中打开：

**Settings | Plugins | 齿轮菜单 | Install Plugin from Disk**

选择 `VerseAngelScript-Rider-Plugin-0.5.6.zip` 后重启 Rider。仓库同时会在 `plugins/rider` 保留一份可直接安装的插件包。

## 解析与重命名的安全边界

普通标识符解析只使用当前文件及递归 `#include` 闭包；项目符号索引仍用于补全和显式的实现/继承搜索，不再作为普通引用的兜底。真实注释里的 `#include` 会被忽略，循环和菱形 include 不会重复加入声明。

- 同文件和 include 中的候选一起筛选，保留作用域遮蔽、参数/局部变量声明顺序及可识别的命名空间/成员所属类型。声明需要位于参数列表或声明语句起始处，`a && b`、`flags & mask` 和运算式中的函数调用不会被当作声明
- 函数按必需参数数到总参数数筛选，支持尾部默认参数。筛选为空时不会退回不匹配的声明
- 引用方向参数（`&in`/`&out`/`&inout`）及 `@const` 句柄参数/局部变量保留词法遮蔽；构造/析构函数的参数和局部变量不会被当作其他方法可见的成员
- 数字词法匹配编译器支持的 `0b`/`0o`/`0d`/`0x` 进制前缀和数字间单引号分隔符，不会将数字后缀当作标识符，也不会吞掉相邻算式中的用法
- `Foo(...)` 在可唯一识别类名时绑定到类声明，保留隐式默认构造的跳转与类型重命名；此处不判断构造函数重载或实参类型。接口不能实例化，`IFoo(...)` 不作为有效构造解析
- 相同参数数的类型重载，以及默认参数范围重叠的重载，仍属于歧义。直接跳转和调用关系不任意选第一个；Go To Declaration 可以展示候选
- 不做参数类型推导、转换/重载排序、别名展开或继承成员绑定。复杂接收者（链式、索引、函数返回值）、未知类型/签名、不完整调用和含 `<`/`>` 的实参表达式保守地不解析；派生类中不能识别的成员也不会误绑定到同名全局符号
- 已识别的声明标识符本身不被算作其他声明的引用；只有唯一绑定的引用参与 Find Usages/Code Vision。显式实现/继承搜索仍是原有的语法级发现功能，不能当作类型精确的绑定证据
- 新名字限于非关键字的 ASCII 标识符（字母/下划线开头，后续可含数字）；`$` 始终非法，Unicode 新名字须先确认编译器属性 25，当前暂不提供。已有 Unicode 源码仍可读取，不会自动规范化名字
- include 支持单/双引号、`#include` 后无空格、跨行空白；同一行引号后的代码仍参与解析。依赖闭包按真实文件去重，并保留缺失文件或无法判定预处理的状态；不完整的闭包不产生确定的符号绑定。当前只支持相对 VAS 文件依赖，不支持绝对/驱动器路径、非 Windows 上的反斜杠路径、非 VAS 文件依赖和自定义加载回调
- 重命名前检查索引中的 VAS 文件及声明文件：先按旧名/新名标识符筛选可能相关的源文件（排除注释和字符串），相关文件的依赖不完整时拒绝该次操作，然后检查声明所在模块/include 闭包可见范围内的同名标识符，局部变量进一步限于其作用域。与旧名/新名无关的损坏模块不会阻止操作。相关文件的条件编译、未知指令、缺失依赖或未完成的字符串/注释仍需先修复或消除不确定性。出现可能指向目标的歧义引用或未解析用法时，预检会在写入前拒绝该次重命名；索引未就绪时也拒绝。默认重命名作用域和实际修改列表必须覆盖每个已知绑定用法；已索引但位于 Rider 项目模型之外的用法或用户缩小后的作用域不允许造成部分修改。存在显式构造/析构名称的类，以及可能与接口/基类实现相连的方法声明族，会按操作拒绝重命名，避免只改其中一部分；无此关联的类型与成员仍保留可验证的无歧义重命名能力。完整类型语义与未索引/外部文件的覆盖仍不在此实现范围内

验证包含 `VasSymbolScannerTest`、`VasSymbolSelectionTest`、`VasLexerTest` 以及真实 Rider Solution Host 的 `VasRiderSolutionIntegrationTest`。后者覆盖跨 include 重载、错误所属类型/参数数、歧义、默认参数、遮蔽、声明自身、未包含文件与循环 include；运行需要 Java 25 和 Rider 2026.2/Build 262。

类构造与结构声明族的语法边界另经实际 `vasbuild` 验证：隐式/显式类构造、析构、接口实现和基类 override 均可编译；接口实例化以及只重命名类/方法族的一部分会被编译器拒绝。宿主测试包含实际 `PsiReferenceService`、`ReferencesSearch`、`RenameProcessor` 拒绝/跨文件修改/单次撤销，以及缩小作用域时不修改文件的验证；另验证数字字面量不参与重命名、生命周期局部作用域、`@const` 接收者绑定、逻辑/位运算用法及三种 include 形式的跨文件重命名；依赖不完整的相关文件必须拒绝修改，无关文件则不阻止操作，并先断言 fixture 文件属于默认项目作用域且已进入符号索引。这些测试需在真实 Rider 宿主中执行。
