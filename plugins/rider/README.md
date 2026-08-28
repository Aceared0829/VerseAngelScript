# Verse AngelScript Rider 插件

此目录存放可直接安装到 JetBrains Rider 的 VAS 语言插件，不是 Visual Studio 扩展。

## 安装

1. 打开 Rider 的 **Settings | Plugins**。
2. 点击齿轮菜单，选择 **Install Plugin from Disk**。
3. 选择 `VerseAngelScript-Rider-Plugin-0.4.1.zip`。
4. 按提示重启 Rider。

当前安装包面向 Rider 2026.2（Build 262），提供 `.vas` 文件识别、语法高亮、项目符号索引、当前文件与跨文件补全、`#include` 目标跳转、函数/变量/类型的 `Ctrl+B`（或 Ctrl+鼠标点击）声明跳转、`Shift+F12` 查找用法、转到函数实现、编译器驱动的错误/警告波浪线、可点击的红色/黄色控制台诊断，以及调用 `vasbuild`/`vasrun` 构建和运行当前文件的功能。鼠标中键是否触发“转到声明”仍由 Rider 的鼠标快捷键映射决定；插件已经提供同一套 VAS 引用目标。

## 安装 VAS 项目模板

Rider 的新建项目窗口读取 .NET 模板系统，因此项目模板需要单独注册一次。在仓库根目录运行：

```powershell
dotnet new install .\templates\rider\vas-starter --force
```

也可以直接运行仓库附带的一键脚本：

```powershell
.\plugins\rider\install-vas-template.ps1
```

也可以在 Rider 新建项目窗口左下角选择 **管理模板…**，安装 `templates/rider/vas-starter` 文件夹。安装后重新打开新建项目窗口，搜索 **VAS** 或 **Verse AngelScript Project**。

该模板会创建 `src/main.vas`、`vas-project.json`、Windows x64 运行工具，以及可直接由 Rider 打开的 `.sln/.vcxproj`。构建解决方案会生成 `out/main.vasbc`。

插件源码位于 `tools/rider-plugin`。
