SH4 特定内容作者
-----------------------
Fredrik Ehnbom - 2004 年 5 月的原始版本
Fredrik Ehnbom - 2005 年 1 月针对 2.0.0wip2 更新的版本

使用方法
-----------------------
本文假定你已正确安装 kallistios。此项目使用 kallistios 1.3.x
的 Subversion 第 183 次修订版本及 sh-elf-gcc 3.4.3 开发。不过，
它实际上并不依赖任何 kallistios 特有功能，因此应该也能在其他
基于 SH4 的架构上构建并运行。

AngelScript 使用了 KOS 似乎未提供的 memory.h。不过无需担心，
makefile 会为你创建该文件；)

要构建该库，只需进入 angelscript/source 并执行：

make -f ../projects/dreamcast/Makefile

或者在你放置本文档随附 makefile 的任意位置执行该命令。
该库将与其他 KOS 附加库一起，位于
$KOS_BASE/addons/lib/dreamcast/。
