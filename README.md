<p align="center">
  <img src="assets/branding/ninghong-ning-logo.png" alt="宁鸿" width="112" align="middle">&nbsp;&nbsp;&nbsp;
  <img src="assets/branding/vas-logo-v3-cursive-ink.png" alt="VAS：VerseAngelScript" width="56%" align="middle">&nbsp;&nbsp;&nbsp;
  <img src="https://repository-images.githubusercontent.com/1036687907/6cd0e5bb-3856-4ad1-ac4c-da02be8e3ed7" alt="AngelScript 原始标志" width="112" align="middle">
</p>

# VerseAngelScript（VAS）

VerseAngelScript（VAS）**基于 AngelScript（AS）开发**，而不是从零实现一套脚本虚拟机。AngelScript 为 VAS 提供编译器、字节码、执行上下文、暂停/恢复、垃圾回收与 C++ 原生 API 绑定等基础能力；VAS 将在这一基础上扩展面向 Unreal Engine 的语言语义、运行时能力与工具链。

VAS 以熟悉的 C++ 风格和面向对象模型为基础，结合更简洁的语法糖、Verse 启发的游戏逻辑能力，以及与 Unreal Engine 深度绑定的可视化脚本编辑体验。

我们的目标不是把 C++ 原样搬进脚本层，而是保留其清晰的类型、类与接口模型，同时让运行时承担更多内存、对象生命周期与调度细节，使开发者能把防御性代码集中在真正的业务边界：网络、存档、外部输入与权限。

## 愿景

- **C++ 风格，但更简洁**：保留类、接口、继承、组合与强类型等熟悉能力；通过语法糖减少样板声明、集合操作、任务表达和对象使用中的重复代码。
- **Verse 启发的游戏逻辑能力**：逐步引入适合游戏脚本的异步、可暂停执行、任务与组合模型；不承诺与 Epic Verse 的语法或运行时完全兼容。
- **为 Unreal Engine 深度集成**：围绕 UObject、UFunction、属性、生命周期、蓝图工作流和游戏运行时建立脚本绑定与开发体验。
- **支持运行时热更新**：目标是在安全的运行时边界替换脚本模块，并保留可诊断、可回滚的模块与状态管理流程。

## VAS 与可视化脚本

VAS 源码是游戏逻辑的**唯一可信、可读、可审查、可合并**的载体。与 Unreal Engine 深度绑定的可视化 VAS 编辑器将提供类似蓝图的节点、端口与连线创作方式，但不会成为独立的逻辑真源。

```text
VAS 源码 ──实时解析──► 语义模型 ──► 可视化 VAS 二进制资产
   ▲                                      │
   └──── 可视化编辑回写 VAS 源码 ───────────┘
```

- 编辑 VAS 时，可视化脚本会实时生成或增量更新。
- 编辑可视化脚本时，图编辑操作会转换为语义修改并回写为 VAS 源码，再同步更新图资产。
- 可视化资产可以是二进制形式，并保存布局、折叠状态、注释与编辑体验数据；即使该资产冲突、丢失或需要重新生成，也不应丢失游戏逻辑。
- 无论从 Git、Perforce、UGS、多人协作还是编辑器分支发起合并，最终都以 VAS 为逻辑产物。长期目标是让所有合并入口共享同一个语义合并内核，并可在文本或图视图中解决冲突。

复杂的文本逻辑不应被强行拆成难读的节点海。可视化编辑器会将难以自然映射的代码保留为函数、子图或代码块节点，确保 VAS 仍然是清晰、可维护的源码。

## 规划方向

1. 定义 VAS 的简化 C++ 风格语法、语法糖与 Verse 启发的语言能力。
2. 建立 Unreal Engine 反射绑定、对象生命周期、模块管理与热更新管线。
3. 建立由 VAS 语义模型驱动的蓝图式可视化编辑器，并实现文本与图的实时双向同步。
4. 建立独立于具体版本控制系统的语义差异、合并与冲突解决能力，最终以 VAS 源码落盘。

## 当前状态

当前仓库仍以 AngelScript `2.39.0 WIP` 为编译器和运行时基线。上述 VAS 语言扩展、Unreal Engine 绑定、热更新、可视化编辑和语义合并均属于规划与后续开发内容，尚未在本仓库实现。

## 当前开发约定

- **VAS 源码后缀为 `.vas`**。新建的 VAS 脚本、示例脚本和脚本间 `#include` 均使用小写 `.vas`。
- **`vasbuild` 严格检查后缀**：入口脚本和递归引入的脚本必须为 `.vas`；传入旧的 `.as` 文件会在编译前失败并提示迁移。底层 AngelScript API 仍保持通用，以便嵌入式宿主继续使用内存脚本或自定义虚拟文件名。
- Windows 的正式开发预设使用 Visual Studio 2026、MSVC `v145` 和 C++23：

  ```powershell
  cmake --preset windows-msvc-v145-cxx23
  cmake --build --preset windows-msvc-v145-cxx23-release
  ctest --preset windows-msvc-v145-cxx23-release
  ```

也可以直接打开仓库根目录的 `VerseAngelScript.sln`。该解决方案提供 `vasbuild`、`VAS Core`、`VAS Runner` 和 `VAS Tests` 四个统一入口，并复用上述 CMake/MSVC C++23 配置。

## JetBrains Rider 插件

仓库内附带专门的 **Rider 插件**：

- 可安装插件：`plugins/rider/VerseAngelScript-Rider-Plugin-0.5.0.zip`
- 插件源码：`tools/rider-plugin`
- Rider 起步项目模板：`templates/rider/vas-starter`

当前版本面向 Rider 2026.2（Build 262），支持 `.vas` 文件识别、语法高亮、项目符号索引、当前文件及跨文件补全、`#include` 目标跳转、函数/变量/类型声明跳转、查找用法、转到函数实现、由真实 VAS 编译器提供的红色错误/黄色警告波浪线，以及 **Build | Build Current VAS File** 和 **Run | Run Current VAS File**。VAS 项目模板通过 Rider 使用的 .NET 模板系统注册；安装后可在 **New Project** 中搜索 **VAS** 或 **Verse AngelScript Project**。模板内置 Windows x64 构建器、运行器、MSBuild 解决方案和示例项目，创建后即可构建运行。安装方式见 `plugins/rider/README.md`。

0.5.0 进一步支持常见函数重载和成员类型解析、类/接口继承实现导航、声明/定义行标、引用数量 Code Vision，以及跨文件符号重命名。

构建完成后，可按以下方式调用构建器：

```text
vasbuild <config file> <script.vas> <output>
```

AngelScript 是一个可嵌入 C++ 应用的跨平台脚本库，为 VAS 提供编译、字节码、执行上下文、暂停/恢复、垃圾回收与原生 API 绑定等基础能力。

- AngelScript 官方仓库：https://github.com/anjo76/angelscript
- AngelScript 官方网站：https://www.angelcode.com/angelscript

## 致谢

VAS 建立在 AngelScript 提供的成熟编译器与运行时能力之上。感谢 AngelScript 作者 Andreas Jönsson 及所有贡献者；本项目会保留原有署名与许可声明，并遵守 [AngelScript 许可证](LICENSE.md)。
