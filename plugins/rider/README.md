# Verse AngelScript Rider 插件

此目录存放可直接安装到 JetBrains Rider 的 VAS 语言插件，不是 Visual Studio 扩展。

## 安装

1. 打开 Rider 的 **Settings | Plugins**。
2. 点击齿轮菜单，选择 **Install Plugin from Disk**。
3. 选择 `VerseAngelScript-Rider-Plugin-0.5.4.zip`。
4. 按提示重启 Rider。

当前安装包面向 Rider 2026.2（Build 262），提供 `.vas` 文件识别、语法高亮、项目符号索引、当前文件与跨文件补全、`#include` 目标跳转、函数/变量/类型的 `Ctrl+B`（或 Ctrl+鼠标点击）声明跳转、`Shift+F12` 查找用法、转到函数实现、编译器驱动的错误/警告波浪线、可点击的红色/黄色控制台诊断，以及调用 `vasbuild`/`vasrun` 构建和运行当前文件的功能。鼠标中键是否触发“转到声明”仍由 Rider 的鼠标快捷键映射决定；插件已经提供同一套 VAS 引用目标。

0.5.0 增加函数参数数量和类/命名空间容器解析，可区分常见重载及成员调用；支持接口/基类到实现类型、函数声明与定义互跳、编辑器左侧导航图标、声明上方引用数量提示，以及通过 Rider Rename 重命名声明和已解析引用。复杂泛型、隐式类型转换和动态分派仍由 VAS 编译器最终裁决。

0.5.1 在 **Navigate** 菜单增加 **VAS Callers** 与 **VAS Callees**，用于查看并跳转到选中函数的调用方和被调用函数。

0.5.2 修复了阻断 PSI 解析的运行时错误；`.vas` 的补全、声明跳转、查找用法、重命名、Code Vision 和调用关系导航现在可以正常创建语义模型。继承关系查询也改为专用索引，避免导航时扫描整个项目。

0.5.3 修复 `#include "file.vas"` 的文件跳转：Rider 的转到声明与直接导航会直接解析并打开相对路径的 VAS 文件，不再依赖预处理 token 的通用引用注册。

0.5.4 默认不会在后台自动执行项目提供的 `vasbuild.exe`；需要在 **Settings | Tools | VAS** 明确设置构建器路径后，才会启用编译诊断波浪线。由 Rider VAS 项目生成器创建的项目会自动写入其插件内置运行时路径。

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
