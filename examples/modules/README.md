# import、include 和宏示例

`main.vas` 同时演示 `print`、`println`、字符串拼接、格式化，以及以下三种依赖写法：

```cpp
import math;
#include "math.vas"
#include <math.vas>
```

它们解析到同一个文件时，只加载、预处理和编译一次。`math.vas` 中的函数和全部宏自动可见；其依赖 `demo_constants.vas` 的宏也自动传递。入口预定义的 `DEMO_BASE` 会影响导入文件中的条件编译。

在仓库根目录运行已构建的 Windows Release 工具：

```powershell
& .\out\build\windows-msvc-v145-cxx23\Release\vasrun.exe .\examples\modules\main.vas
& .\out\build\windows-msvc-v145-cxx23\Release\vasbuild.exe .\sdk\samples\asbuild\bin\config.txt .\examples\modules\main.vas .\out\build\windows-msvc-v145-cxx23\modules-demo.vasbc
```

模块部分的输出为：

```text
[模块导入 / 宏自动导出]
来自 demo_constants：math v1
函数宏：72 + 10 = 82
传递导出的宏：DEMO_OFFSET = 110
导入后的宏也能用于条件编译。
```

例子中注释保留了格式参数缺失、类型不匹配和宏冲突的反例；单独取消对应注释，可以看到编译诊断。更多解析规则、宏语义和性能测量见 [模块与宏说明](../../docs/modules-and-macros.md)。
