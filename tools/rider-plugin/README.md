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

## 安装

构建后的 ZIP 位于 `build/distributions`。在 Rider 中打开：

**Settings | Plugins | 齿轮菜单 | Install Plugin from Disk**

选择 `VerseAngelScript-Rider-Plugin-0.5.5.zip` 后重启 Rider。仓库同时会在 `plugins/rider` 保留一份可直接安装的插件包。
