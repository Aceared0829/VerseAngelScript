# VAS IDE 支持与验收路线

目标：Rider、VS Code、Visual Studio 2026 中接近 C++ 的原生工作流。每个模块单独提交、PR、审查、修复与测试，满足验收再合并；安装包发布另行处理。

## 已知基线

以 `5fa262db` 为本轮开发基线：Rider 0.5.6 有词法高亮、索引、导航、补全、用法/重命名和编译器诊断；VS Code 与 VS2026 没有语言扩展。VS2026 的现有 `.sln` / MSBuild 工程入口不等于 VAS 语言服务。

Rider 的平面 token PSI 与符号扫描器目前依赖启发式解析，补全不是接收者类型/作用域驱动，重载身份不包含完整参数类型。导航、引用和重命名不能因此视为编译器级语义正确。后台诊断只覆盖当前文件内容，include 使用磁盘版本，也没有完整入口模块上下文。

运行时为 AngelScript 2.39.0 WIP。已有编译、反射注册、调试行回调和命令行调试器；尚无 LSP/DAP 服务、UE 反射导出或可用 UE SDK 符号清单。

## 验收矩阵

| 模块 | 交付能力 | 验收标准 |
| --- | --- | --- |
| 编辑器基础 | 三种 IDE 的 `.vas` 识别、词法高亮、注释、括号、缩进、安装包 | 真实宿主启动；词法对齐编译器；打开工程不自动运行项目工具 |
| 工程与工具链 | 统一版本化工程配置、入口模块、宿主 API、输出路径、原生 build/run | 多根工作区、空格/Unicode 路径、嵌套 include、错误退出/取消；同一配置在三种 IDE 构建一致 |
| 编译诊断 | 完整未保存缓冲区、入口模块上下文、依赖更新、结构化错误/警告 | include/config 错误不丢失；UTF-8 字节列转换为 LSP/IDE UTF-16；取消、超时和旧请求不覆盖新诊断 |
| 编辑智能 | 容错语法树、作用域/类型模型、接收者补全、重载签名、悬浮信息、文档符号 | 未完成源码仍可编辑；不泄露无关模块符号；宿主 API 来自真实注册信息 |
| 导航与引用 | include/定义/实现、引用、类型/调用层次 | 同名、遮蔽、命名空间、继承、同参数数量重载等正反例；歧义不选择任意目标 |
| 重命名 | 绑定到唯一符号身份的跨文件修改、预览与撤销 | 不修改注释/字符串/同名无关符号；无法确定绑定时拒绝修改 |
| 格式化与语义高亮 | 保留源码含义的格式化、增量语义 token | 格式化幂等，编译/行为不变，注释/字符串/预处理保真；损坏源码安全退出 |
| IDE 调试 | DAP 启动/附加、断点、单步、堆栈、局部变量、监视、异常与终止 | 实际运行时验证嵌套 include、暂停/恢复、线程或协程上下文、停止/重启；CLI `-d` 不作为 IDE 调试验收 |
| UE/SDK | 精确宿主 API 清单、文档、C++ 声明位置、版本与 UE 会话集成 | IDE、离线编译器与运行宿主的符号版本一致；未实现的 UObject/UFunction 等不能伪造 |

## 分阶段模块

1. VS Code 编辑与显式编译/运行基础。先闭合可安装、真实编译诊断、宿主测试的链路，不将语法高亮宣称为语义支持
2. 统一 `vas-project` 配置与编译单元/宿主 API 描述，修复 Rider 不可靠的导航回退与缓冲区诊断，建立共享测试语料
3. `vas-analysis` 与 `vas-language-server`：共享语法、绑定、依赖图与 LSP；三端保留各自原生 UI，避免重复语言提供器
4. 基于唯一语义身份逐项开启准确导航、引用、重命名、格式化和语义高亮；提供负例和歧义测试
5. `vas-debug-adapter` 与运行时调试协议，随后接入真实 UE 宿主导出与启动/附加。先实现依赖，再验收客户端

编译成功后的 AngelScript 反射可提供函数、类型、属性和声明位置，但不能直接提供未完成源码的完整引用绑定图。不能用一个 LSP 传输层替代分析器。

## 测试与平台边界

- 每个变更检查具体提交的 CI、代码审查和回归结果；区分通过、失败、未运行
- Linux C++ 测试不能替代 Windows MSVC v145、Rider Solution Host 或 VS2026 扩展宿主测试
- VS Code 的基础模块需真实 Extension Host 的可信/不可信工作区、build/run 与 Problems 测试
- Visual Studio 使用 VSIX 原生编辑与显式构建入口，覆盖 Open Folder 与 `.sln`；共享语言服务器交付前不注册 `ILanguageClient`，VS2026 兼容性必须用实际宿主确认
- Rider 继续使用固定 Build 262 API，迁移共享服务时保留已验证的原生交互，避免双重诊断与重命名

参考：[VS Code LSP](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide)、[Workspace Trust](https://code.visualstudio.com/api/extension-guides/workspace-trust)、[VS LSP 扩展](https://learn.microsoft.com/en-us/visualstudio/extensibility/adding-an-lsp-extension?view=visualstudio)、[VS2026 扩展兼容](https://learn.microsoft.com/en-us/visualstudio/extensibility/migration/extension-compatibility?view=visualstudio)、[JetBrains LSP](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)

## Visual Studio 2026 显式工程构建模块

`tools/visualstudio-extension` 在既有七项原生编辑验收上增加 Build VAS Project / Cancel VAS Build、独立用户编译器设置、`.sln` / Open Folder 根目录与原生两阶段选择界面。先由用户选择 Read Project Units 调用原生描述器，再明确选择编译单元并构建；活动文件不决定入口，打开/编辑/索引/设置不自动执行工具。

共享原生 JSONL 诊断进入 Error List；只有已验证的原生 loaded-source 内容证明、当前保存文件身份和编辑器文本一致时才将 UTF-8 字节列转换为 UTF-16 导航位置。包含依赖部分遍历、缺失路径规范 watch alias、脏物理别名、取消/关闭/更换编译器与旧结果抑制均有专门验收。实际 VS18 宿主测试与证据 gate 必须在目标提交 CI 通过；本地编译不替代 Windows 原生运行。

公开 SDK 中未验证原生 workspace trust 查询，显式操作授权不等同于 VS 原生信任状态；该同步与真实 MOTW 提示行为须如实单列。当前范围不含 Run Project、LSP、调试器、未保存缓冲区完整语义或 C++ 功能对齐。详见 [模块验收](requirements/vs2026-project-build.md)。
