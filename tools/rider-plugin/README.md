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

## 后台诊断与项目安全

打开或编辑 `.vas` 文件触发的后台编译诊断，仅在 Rider 已信任项目且已配置 builder 路径时运行。项目配置中的可执行文件路径本身不代表信任；未信任或已释放的项目不会启动后台编译器。插件会在收集输入、处理排队请求和启动进程之前重新检查，不会自动更改信任状态或弹出信任提示。

显式 **Build/Run** 动作和起步项目生成器保持原有行为。原生 Rider 信任回归测试使用隔离的测试信任存储与项目级记录启动器，检查未信任文件的打开/编辑、排队请求、恢复信任后的普通高亮及诊断范围；不会执行项目提供的测试二进制文件。
