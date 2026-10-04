# 模块化竞技场示例

从 `main.vas` 开始阅读。这是一套可运行的回合制模拟程序：剑士、守卫和医师两两比赛，攻击会受护甲和暴击影响，医师在低血量时治疗；奇偶回合交换先手，达到回合上限后判平局。每场使用新角色，比赛结果进入积分榜。

## 文件与阅读顺序

| 文件 | 内容 |
| --- | --- |
| `main.vas` | 宏配置、三种依赖写法、命令行参数与返回码 |
| `Arena/Config.vas` | 默认配置、对象宏、函数宏和编译期配置检查 |
| `Arena/Math.vas` | `FDeterministicRandom` 与数值边界函数 |
| `Arena/Combatant.vas` | `ECombatRole`、`FCombatant`、工厂与生命值规则 |
| `Arena/Skills.vas` | `ICombatSkill` 接口、攻击与治疗实现、句柄和多态 |
| `Arena/Battle.vas` | 技能选择、回合循环、先手切换与战斗结果 |
| `Arena/Report.vas` | 结果归档、同分排序、格式化排行榜 |
| `Arena/Checks.vas` | 23 项自检，失败会使程序返回非零 |
| `Arena/Demo.vas` | 三场循环赛与无逐回合输出的批量模拟 |

`Demo → Report → Battle → Combatant → Config/Math`；`Battle → Skills → Combatant`。这些依赖包含菱形引用。入口另外混用 import、引号 include 和尖括号 include：原生加载报告应包含恰好 9 个不同源码文件，不会重复编译。

## Epic 风格在 VAS 中的约定

参照 [Epic Coding Standard](https://dev.epicgames.com/documentation/en-us/unreal-engine/epic-cplusplus-coding-standard-for-unreal-engine)：类型、函数、字段和局部变量采用 PascalCase，普通类 `F` 前缀，接口 `I` 前缀，枚举 `E` 前缀，布尔变量 `b` 前缀；使用 Allman 大括号、Tab 缩进、显式 `int32/uint32`、一行一个变量声明，公共操作放在私有实现之前。布尔函数用 `IsAlive`、`ShouldRankBefore`、`AreSelfTestsPassing` 表达真/假含义。

这里使用 VAS 内建的 `string`、`array`、`@` 句柄和注册的宿主函数；`main()` 是运行器约定。宏使用项目对应的 `VAS_` 前缀，例如 `VAS_ARENA_MAX_ROUNDS`。脚本类使用 `F` 前缀，不引入 Unreal 的反射声明；代码注释使用英文，学习说明和终端输出保留中文。

## 运行

先按根目录 CMake preset 构建工具，再在仓库根目录执行：

```powershell
# 完整战斗过程、自检和榜单
& .\out\build\windows-msvc-v145-cxx23\Release\vasrun.exe .\examples\arena\main.vas

# 简洁战报和榜单
& .\out\build\windows-msvc-v145-cxx23\Release\vasrun.exe .\examples\arena\main.vas --quiet

# 单独自检
& .\out\build\windows-msvc-v145-cxx23\Release\vasrun.exe .\examples\arena\main.vas --self-test

# 批量模拟，不输出逐回合日志
& .\out\build\windows-msvc-v145-cxx23\Release\vasrun.exe .\examples\arena\main.vas --batch 2000

# 生成字节码
& .\out\build\windows-msvc-v145-cxx23\Release\vasbuild.exe .\sdk\samples\asbuild\bin\config.txt .\examples\arena\main.vas .\out\build\windows-msvc-v145-cxx23\arena-demo.vasbc
```

自检输出 `SELF_TEST passed=23 failed=0`。默认循环赛的榜首是守卫，积分 4；完整简洁输出见 `expected-quiet.txt`。固定 2,000 场输出：

```text
BATCH count=2000 wins=654 losses=1346 draws=0 checksum=9933226
```

参数不合法时返回 2，自检失败返回 1，成功返回 0。批量场数限制为 1..100000。随机数用于教学和可重放模拟，不用于安全场景；按取模生成范围随机数存在微小分布偏差。循环赛固定种子和配对顺序，用于验证行为，不代表游戏平衡结论。

## 配置与扩展

在入口的 import 之前定义 `VAS_ARENA_VERBOSE 0` 可在编译期去掉逐回合日志；`--quiet` 是运行时关闭日志。两者都不改变随机数消耗和战斗结果。修改 `VAS_ARENA_MAX_ROUNDS` 可控制回合上限，允许 1..100；无效配置会触发 `#error`。依赖中的宏全部自动可见，入口能够使用传递导出的版本、暴击率和暴击函数宏。

可以从以下三个方向练习：增加 `ICombatSkill` 的新实现；在 `CreateCombatant` 中增加角色并扩展配对名单；增加榜单统计并补充自检。函数宏只对普通值表达式演示，不将带副作用的复杂调用放进宏参数。

## 性能与回归

技能对象在每场比赛创建一次，在回合循环内复用。排行榜只移动对象句柄，并预留三个条目的容量；它面向小名单采用插入排序。角色、随机数、技能和结果对象仍会产生每场创建成本；这里没有宣称零分配，也没有为了三个条目引入对象池。

可重复执行性能测量：

```powershell
.\examples\arena\Measure-Arena.ps1
```

脚本先预热，每组默认测量三次并输出中位数及最小/最大值，同时核对输出是否一致。测量包含进程启动、源码读取、预处理、编译和执行，不能当作纯 VM 战斗时间。只有一次最终汇总输出，不含逐回合日志的终端开销。

本机 Windows Release 的一轮端到端测量：2,000 场中位数约 37.8 ms，20,000 场约 215.3 ms；逐次结果与校验和一致。日志保存在本地 `out/verification/arena/performance.json`。这些数据用于观察这套例子的规模变化，不作为其他机器的性能保证。

```powershell
ctest --preset windows-msvc-v145-cxx23-release -R vas_arena_example --output-on-failure
```

集成回归验证四套宿主配置都能编译同一源码，运行器实际执行、自检、榜单快照、批量校验和、CLI 错误返回、9 文件依赖闭包、日志宏关闭后结果一致以及无效宏配置的编译失败。测试也覆盖数组/字符串的 `length()` API，避免宿主配置和运行器再次不一致。

本地部署工程的 `src/main.vas` 已切换到本例，旧的 print/println 基础示例保留为 `src/basics.vas`，两个编译单元都可通过原生 Build Project 构建。
