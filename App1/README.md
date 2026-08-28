# VAS Starter for JetBrains Rider

这是 Verse AngelScript 的 Rider 起步项目模板。

- 入口：`src/main.vas`
- 公共函数：`src/math.vas`
- Rider/MSBuild 入口：`App1.sln`
- 项目配置：`vas-project.json`

模板通过 Rider 使用的 .NET 模板系统安装。安装 Verse AngelScript Language Support 插件后，可在 Rider 的 **新建项目** 中搜索 **VAS** 或 **Verse AngelScript Project**。创建后打开生成的解决方案，选择 `main.vas`，使用 **Run | Run Current VAS File** 运行；也可以直接构建解决方案生成 `out/main.vasbc`。

模板内置 Windows x64 的 `.vas/bin/vasrun.exe`、`.vas/bin/vasbuild.exe` 和匹配的接口配置，不依赖 VAS SDK 仓库即可运行。需要替换为自定义运行时或宿主接口时，可在 **Settings | Tools | VAS** 中修改路径。
