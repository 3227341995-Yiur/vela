# LLVM 后端——范围、接口，以及我们不声称什么

[English](LLVM_PLAN.md) | **简体中文**

<!--
源文件 : LLVM_PLAN.md
源文件字节 : 36064
源文件 SHA256 : 0087eb838f60f61846e91ef7256e0b4b73b22dfd7115cfd88996f7402fc62b1d
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

状态：本文件就是这项工作所依据的那份契约，它写在代码之前，为的是那些难的部分只被决定一次，而不是由每个 agent 各自去猜。**它的决定经得起时间；它的自我描述经不起**，所以这里的状态是以测量形式给出的，而本文件其余部分要当作一本标着日期的流水账来读。测量于 2026-09-20 01:30，对当时那棵树：

| 什么 | 现在测到的值 |
|---|---|
| `tools\link_selfhost.vel` | `part_count() == 10`；`llvm_shim.vel` 是第 **6** 个部件，`emit_llvm.vel` 是第 **7** 个部件 |
| `selfhost\parts\` | 磁盘上 **10** 个文件，与链接器的清单是同一个集合 |
| `selfhost\vm.vel`（链接后的） | 14 645 行 / 520 297 字节 |
| 驱动新增的模式 | `selfhost\parts\vm_main.vel`——`:1148` 处的 `emit-llvm`，`:1164` 处的 `build-llvm` |
| 那个 shim | `runtime\vela_llvm_shim.{c,h}` 62 378 / 28 203 字节，**64** 个函数，其中 **55** 个在 Vela 里声明 |
| 那个 emitter | `selfhost\parts\emit_llvm.vel`，73 064 字节 / 1631 行 |
| 这台机器上的 LLVM | `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\` 下的 23.1.1——**在磁盘上，不在 `PATH` 上**，而 `find_lld()` 从 `exe_dir()` 往上走三层目录，正好停在它下面一层（检查了 `<repo>\llvm`，没有检查 `<repo>\..\llvm`），所以在那修好之前，`VELA_LLD` 只能手动设置 |
| `vela_llvm_runtime.obj` | **现在存在**——38 819 字节，就在 `selfhost\build\` 和 `selfhost\` 里编译器的旁边 |
| `vm.exe` | 770 560 字节（在链接 `libLLVM` 之前是 634 368），而驱动的用法文本里列出了 `emit-llvm` 和 `build-llvm` |
| **LLVM 后端今天能编译什么**（由 leader 核实，2026-09-20） | 两个不含数组的程序——一个字符串和一个整数表达式的 `print`，以及一个带检查算术的 `while` 循环——用三种方式构建（**解释器、C 后端、`build-llvm`**）打印出**逐字节相同**的输出（`4E7186877AA30E1E`、`D87D921C2479A85B`），退出码全为 0；把 `PATH` 剥成 `C:\Windows\System32;C:\Windows`，那里 `where cl`、`where clang` 和 `where clang-cl` 都找不到任何东西，`build-llvm` 仍然以 0 退出，可执行文件也仍然打印同样的字节，而用户的目录里只有 `<stem>.vel` 和 `<stem>.exe`。**链接命令行只点了一个外部程序：`lld-link`。** 那就是北极星的 ①②③，作用在这个子集上 |
| **整份 `run` 语料，三种方式**（同一天测了两次——第二次是在 arena 那项工作落地之后） | `tests/cases.txt` 里所有 **23** 个 `run` 用例，每一个都用三种方式构建并运行，再按输出哈希和退出码比较。**第一次运行：4 个一致，19 个被拒绝。第二次运行：7 个一致，16 个被拒绝。** **两次运行里给出错误答案的都是 0 个**——后面这个数字才是要紧的那个，而它没有动过：这个后端要么逐字节一致，要么大声拒绝。开始变为一致的三个是 `arrays_and_len_folding`、`array_zero_filled` 和 **`hello`**，也就是说那个随仓库交付的示例现在能在这个后端上编译。剩下的拒绝是一份可枚举的清单，而不是一团谜：**`parallel for` 6 个**（*刻意*拒绝：LLVM IR 没有 OpenMP，而一个自称并行却串行执行的循环，正是这个项目承诺绝不交付的那种失败模式）、**`print` 和数组 `len` 之外的内建函数 3 个**、**结构体参数或局部变量 2 个**、**`str` 的 `len` 与 `str` 比较 2 个**、**对某一类值的原地更新 1 个**、**`and`/`or` 短路 1 个** |
| **它仍然拒绝什么** | **数组**是被带着理由拒绝的，不是被近似处理：`vela_arena_alloc` 在 `runtime/vela_runtime.h` 里是 `static`，所以发出的 IR 没有任何可以调用的符号。`examples\hello.vel` 声明了数组，所以那个随仓库交付的示例还不能在这个后端上编译 |

**emitter 那项工作测出来的、而本计划没有带上的三条约束**——三条都是后端的第一次尝试编译编译器时发现的：

* 链接后的编译器，它的**第一个浮点字面量必须出现在第 65536 个 token 之前**：词法分析器把一个浮点数的值放在 `tkf[cx.ntok - 1]`，而 `tkf` 是 `Array[float, 65536]`（`selfhost/vela.vel:465`）。一个写在第 85263 个 token 处的 `0.0` 杀掉了**每一个**模式，消息是 `index 85262 out of range for array of length 65536`；
* 发出字符串字面量时**绝不要给它套一层 `unescape()`**：两个后端都是一边发出、一边解码转义，所以第二次解码会吃掉那个 `\` 字节（0x5C），并从第 92 个字节往下把整张表移了一位——实测为 `len(unescape(tbl))` 从 256 → 255，而 `ModuleID` 取出来变成了 `MpevmfID`；
* **数组是第 3 步的前置条件，不是以后再说的小好处。** 它们正是 `examples/hello.vel` 所需要的东西，而它们带来的新东西恰好只有两样：`vela_llvm_arena_alloc`（一个套在 arena 外面的非 `static` 包装）和 `vshim_build_gep`。GEP 刻意**不**检查下标——"越界"是什么意思只由一个地方决定，而那个地方就是两个后端都会调用的运行时边界检查。

**还有一路上撞见的一个反例，针对第 6 步。** 在 `for i in range(...)` 里的 `continue`，在 **C** 后端里会跳过一次自增：那个后端把这个循环降成一个 `while`，而它的最后一条语句就是这次自增，于是 C 的 `continue` 会跳过循环体剩下的部分。2026-09-20 实测：`for i in range(0, 3) { continue }`——解释器在 **58 ms** 内打印 `survived`；**`vm.exe build` 产出的可执行文件在 20 秒之后还在运行**，只能杀掉；而 `build-llvm` 产出的可执行文件会终止。所以现在有一个程序，两个后端在它上面不一致，而**较新的那个才是对的**。这就是把 C emitter 排在别的一切之前修的理由，也是一个具体的理由说明 `build-llvm` 今天还不能取代 `build`。复现放在 `tests/probes/continue_in_for_range.vel`。

**一次安装是三个文件，而其中一个不是可选的。** `vm.exe` 把 libLLVM 装在自己里面，所以 `LLVM-C.dll` 是一个**加载期**依赖：2026-09-20 实测，一份 `vm.exe` 被放进没有那个 DLL 的目录时**根本不会启动**——退出码 `0xC0000135`（`STATUS_DLL_NOT_FOUND`），发生在 `main` 之前，两个输出流上没有任何东西点名 LLVM。此后每一项检查都会失败，却指向错误的问题。`tools\smoke.ps1` 断言那个 DLL 和 `vela_llvm_runtime.obj` 就坐在编译器旁边，正是出于这个理由，而 `tools\build.ps1` 会把两者都复制过去，并且在缺 DLL 时拒绝结束。交付路径除此之外是完好的，而且是站在一个不属于仓库的目录里测的：`vm.exe build p.vel` 以 0 退出，可执行文件能运行，而用户的目录里只有 `p.vel` 和 `p.exe`，没有别的。

**另一条路是量出来的，不是假设出来的。** 解包后的 LLVM 包在 `lib\` 里带着 **99 个超过 1 MB 的静态归档**——`LLVMCodeGen.lib` 是 24,471,254 B，`LLVMAnalysis.lib` 是 15,509,928 B——所以编译器本可以**静态**链接它需要的那些组件，从而完全不要加载期依赖，代价是一个大出几十兆字节的 `vm.exe`。这里两条都没有被选中。被记下来的是：这个选择存在；当前这一条在 DLL 缺失时会*静默*失败；以及 `rustc` 以同样的形状交付自己的库，所以"库放在编译器旁边"是一个先例，不是一条捷径。

下面这些段落有意保留着它们写作时的时态——它们是代码之前那些决定的记录，而其中某一条读起来像"尚未开始"或"这里还没有"的地方，在当时都是真的。**本文件里的一条声称与一次测量冲突时，对的是测量**，而那个段落应当被更正，而不是被相信。

## 这件事到底为什么存在

`vm.exe build` 今天发出 C，再把它交给 `cl.exe`。关于这件事，要紧的每一面都是好的——C 是一个可移植、优化良好、到处都有的后端，而整份语料都是经由它验证的——只有一个例外：一个 Vela 程序没法在一台没有 C 编译器的机器上构建，而这门语言本该是自足的。解释器（`vm.exe run`）已经让 Vela 在什么都不装的情况下可运行；这项工作让它在不专门依赖 MSVC 的前提下原生地*可构建*。

Vela 自身设计带来的两个后果塑造了整份计划，而两者都是实测事实，不是意见：

1. **Vela 不能直接调用 `llvm-c` API。** `extern c` 只接受标量到标量的签名（SPEC §12——这门语言没有指针类型），而 `llvm-c` 是一个指针 API（`LLVMBuildAdd(builder, lhs, rhs, name)`）。所以阶段 1 发出 LLVM **IR 文本**——跟 C 后端已经在做的是同一类工作——而阶段 2 通过一个 C shim 走，那个 shim 只暴露标量表面。
2. **安全语义住在 `runtime/vela_runtime.h` 里。** 边界检查、带检查的算术、panic 约定和 arena 全都在那里，而 C 后端发出的代码是按名字调用它们的。LLVM 后端必须调用*同一批*函数，这样"Vela 默认就是带检查的算术"只有一个实现，而不是两个会互相漂移的实现。

## 各个阶段，以及每一段实际上买到了什么

| 阶段 | 它交付什么 | 机器上仍然需要什么 |
|---|---|---|
| **1** | `vm.exe emit-llvm f.vel` 把 `f.ll` 写**进构建的临时目录，绝不写在源码旁边**；`clang-cl`（或者 `llc` + 一个链接器）把它变成 `f.exe` | 一个 LLVM 工具链二进制。**`clang` 自己就是一个 C 编译器**，所以这一段是用 LLVM 顶替 MSVC；它*并不*移除对 C 编译器的依赖 |
| **2** | `runtime/vela_llvm_shim.c` 在 `llvm-c` 之上暴露一个标量接口；`libLLVM` 被链接进 `vm.exe`，而它此后自己写出**一个目标文件**——没有 C 文本，没有 IR 文本，用户目录里什么都没有 | 一个链接器（`lld-link`，它随我们已经有的那个 LLVM 包交付） |
| **later** | 我们自己的 COFF/PE writer，这样链接就不需要任何外部东西 | 什么都不需要 |

**阶段 2 不是猜测：这些零件是在这台机器上清点过的**（2026-09-19，`tools\get-llvm.ps1` 解包出来的 LLVM 23.1.1）：

| 什么 | 在哪里 | 大小 |
|---|---|---|
| C API 的头文件 | `include\llvm-c\Core.h`、`BitWriter.h`、`Target.h`、… | — |
| 导入库 | `lib\LLVM-C.lib` | 299,868 B |
| 共享库 | `bin\LLVM-C.dll` | 74,159,616 B |
| 链接器 | `bin\lld-link.exe` | — |

**而且这个 C API 不只是被清点过，它被跑过。** `selfhost/llvm/phase2_spike.c` 是一份手写的探针，精神与 M1 相同：它初始化 X86 target，为宿主 triple 构建一个模块，发出一个返回常量 42 的函数 `main`，并用 `LLVMTargetMachineEmitToFile` 写出一个 COFF 目标文件。在这台机器上实测：

```
host triple: x86_64-pc-windows-msvc
wrote object: C:\...\vela-phase2\out.obj        330 B
lld-link /entry:main /subsystem:console out.obj /out:out.exe
out.exe   ->   exit code 42
```

所以整条进程内路径在这里是通的：`cl` 用 `include\llvm-c\Core.h` 编译那份探针并链接 `LLVM-C.lib`；运行时 `LLVM-C.dll` 必须在程序旁边（或者在 `PATH` 上），就像一条 Rust 工具链需要它自己的库一样。这条路径上没有任何东西写出 C 或 IR 文本，而那个目标文件进了一个临时目录。所以阶段 2 是一个工程问题，不是一个研究问题。

所以交付路径可以是：**进程内的 LLVM-C** 构建这个模块，写出 `%TEMP%\vela-build\<flattened>\<stem>.obj`，然后 `lld-link` 把它和 `vela_llvm_runtime.obj` 一起链接成 `<stem>.exe` **放在源码旁边——而那是用户唯一会看到的文件**，这正是北极星真正的要求。没有 MSVC，没有 `cl.exe`，没有 `clang.exe`，用户会看到的任何地方都没有 `.c`，没有 `.ll`。唯一还留在外面的东西是一个链接器，而 Rust 同样需要它。

阶段 1 留在计划里，因为它仍然有价值：它用手写的 IR 证明了 IR 的形状、模块头和 MSVC 结构体 ABI（M1），而它是渐进地做一个 emitter 的诚实方式，因为文本形式的 `.ll` 可以被阅读、可以被 diff。但它**绝不能**把 `.ll` 交付到用户的源码旁边；C 后端出于这个理由早就把它的中间产物搬进了 `%TEMP%`，而阶段 1 遵循同一条规则。

C 后端**留着**。它是语料目前端到端验证过的唯一后端，而且它会一直保持被验证：上面每一个阶段都是增量添加，而 `build` 会完全照今天的样子继续工作，直到某个阶段完成并被测量。

### 把 shim 接进 `vm.exe`——逐个文件的计划

这座桥在语言里已经存在：`extern c` 声明今天就已经被解析、被解析名字、被检查、被发出（`parser.vel:422`、`check.vel:2582`、`emit.vel:2122`），所以编译器可以直接调用 shim。要紧的是那些声明所产生的**确切 C 类型**，而它们是从 `emit_ctype`（`emit.vel:177`）里读出来的，不是假设出来的：

| Vela | 发出的程序里的 C |
|---|---|
| `int` | `int64_t` |
| `float` | `double` |
| `bool` | `bool` |
| `str` | `vela_str` |
| `i32` | `int32_t` |
| `u8` | `uint8_t` |
| `None` | `void` |

所以 shim 的签名由那张表固定下来——只有**一处更正，是本计划第一版弄错的，而且属于要花掉一整天的那一类**：

> `extern c` **拒绝把 `str` 当参数。** 实测：
> `vm.exe check selfhost\parts\llvm_shim.vel` →
> `vela: type error: 'extern c' parameter 'name' has type str`。

这条规则是刻意为之、有理由的，不是疏忽——`check.vel:2580-2598`：

> **这些类型是标量**。一个 `str` 是一个长度加上一个指向 Vela 自己字符串区域的指针，不是 `char *`；而数组是一块存储，它的长度归 Vela 所有——把其中任何一个递过去，都是在发明一套 ABI，而发明 ABI 正是一门安全语言不再安全的开始。

`ck_is_extern_scalar` 接受的恰好是 `int`、`i32`、`u8`、`float` 和 `bool`，参数和返回值一视同仁。上面那张表关于每种 Vela 类型的 C *拼法*仍然是对的——不同的是边界上的**许可**，而 `str` 没有任何许可。这件事在这里要紧，在别处还不要紧：编译器必须把它正在编译的程序的符号名、字符串字面量和输出路径交给自己的代码生成器，而这些东西每一个都是字符串。

**解决办法是别去碰那条规则，把接口做成标量的**——2026-09-19 决定，它取代了这里最早写下的那个解决办法，而那个是错的。

* 本计划的第一版说：允许 `str` 作为 `extern c` 的**参数**，继续拒绝它作为返回值，理由是：一个用 `str` 声明的被调用者本身就必须是对着 Vela 的 `vela_str` 写的。再读一遍 `check.vel:2580` 就能看出那套推理为什么经不起接触：*"一个 `str` 是一个长度加上一个指向 Vela 自己字符串区域的指针，**不是 `char *`**"*。如果允许 `str` 参数，

      extern c def strlen(s: str) -> int

  就会编译通过，而那次调用把一个 16 字节的结构体递给了一个想要指针的东西。C 会一声不吭地把指针转成整数——同一个文件里那条调用点规则就是为这个隐患写的——所以这个错误在编译期是静默的，在运行期是未定义的。这条规则不是官僚程序；它就是边界之所以安全的原因，而它所要付出的那些管道工作是值得付的。
* 于是 shim 通过**它自己那侧边界上的一个字节缓冲区**来暴露字符串：`vshim_buf_reset` / `vshim_buf_byte(u8)` / `vshim_buf_len` / `vshim_buf_read_byte`，外加一个 `*_buf` 兄弟版本，给每一个接收名字、字面量或路径的函数，以及那三个返回 `str` 的函数。每次调用一个字节，对一个编译器那么大的程序来说就是 ~10⁵–10⁶ 次调用，也就是毫秒级。这样一来 shim 的每一个函数都能按 Vela 今天的语法表达出来，没有语言规则要改，而上面那个 `strlen` 隐患根本写不出来。
* 那三个 `str` **返回值**（`vshim_last_error`、`vshim_host_triple`、`vshim_module_ir`）也有了缓冲区形式，这就去掉了生成器必须把它们记成遗漏项的理由——不过它仍然记录遗漏，因为它曾经静默地丢掉过三个函数，而一个悄悄少掉的计数就是它当时丢掉它们的方式。

缓冲区协议落地之后测到的账目：shim 的头文件声明了 **64** 个函数；`tools\gen-shim-decls.ps1` 把它们变成 **55** 条标量 `extern c` 声明，并把 **9** 个作为显式遗漏项记在文件自身里，而这个计数每次运行都会与头文件对账，所以无论是漂移还是静默丢失都过不了 `-Check`。这 **55** 条现在全都通过类型检查：`vm.exe check selfhost\parts\llvm_shim.vel` 打印 `ok`，退出码 0——缓冲区形式除掉了那六个过去因为接收 `str` 而被拒绝的，剩下的遗漏项就是那三个 `str` 返回值。
这些声明是**就地生成在 `selfhost\parts\llvm_shim.vel` 并注册为链接后编译器的第 6 个部件**的。它们过去被暂放在 `parts\` 之外，理论是一个被检查器拒绝的部件会弄坏每一次构建；缓冲区协议让那个理论不再成立，所以那个暂放目录被删掉了，而不是留成一份会漂移的第二份副本。本来会抓住这个错误的守卫在 `tools\build.ps1` 里，它现在打印的是：

```
parts: 9 on disk, 9 in the linker's list, the same set
```

**第 2 步已关闭**，凭的是：上面那行守卫；链接后的编译器能通过 `vm.exe check selfhost\vm.vel`（`ok`）；以及 `tools\build.ps1` 在三代之间达到 `RESULT: ok`，不动点逐字节相同（`0A6A273C5328A435`，每份 758020 字节）。`tools\smoke.ps1` 当时也是绿的，所以那个承载着 55 条声明的编译器仍然能构建并运行程序，而 `.exe` 仍然是它留在用户目录里的唯一文件。
那一段里的数字是那个提交测到的——**9 个部件，12687 行 / 436910 字节**——而本文件顶部的状态块承载的是这棵树现在的说法，因为 `emit_llvm.vel` 是在这之后才注册为第 7 个部件的。上面打印的那行守卫同样是那个提交的输出。

有两件后果值得点名，趁第 3 步还没有依赖它们。`vm.exe` **尚未**链接 `LLVM-C.lib`，而它照样能构建：还没有东西调用那 55 条声明，所以链接器从来不去找它们。还有，发出 C 的不动点哈希从 `17CF78257FE7A2BD` 移到了 `0A6A273C5328A435`，而 `vm.exe` 保持在 634368 字节——声明不携带代码，所以二进制大小不变，变的只是编译器写出的 C。两件事是自洽的，而如果以后的某次大小比较看起来奇怪，要记住的是第二件。如果那条规则后来被证明比看起来更承重，备选方案是：把 shim 需要的字节逐个经由 `u8` 标量送过去（慢，而且编译器会对每一个字面量都这么做），或者让 shim 从环境里自己推出输出路径，并用一个数字选择器查找自己的运行时符号（能去掉大部分字符串，但去不掉字符串字面量，而那些才是真正的数据）。

这些步骤按顺序排列，每一步都以关闭它的那份证据收尾：

1. **`runtime\vela_llvm_shim.{c,h}`**——`llvm-c` 之上的标量 C API。结束条件：一个独立驱动经由它构建 M1 程序，而那份输出与另外三条路径逐字节相同（`tools\llvm-shim-probe.ps1`）。
2. **`selfhost\parts\llvm_shim.vel`**（新部件）——同一套 API，写成 `extern c def …` 声明，而 **`tools\link_selfhost.vel` 的部件清单必须认识这个新文件**，否则这些声明永远到不了 `selfhost/vm.vel`。结束条件：`vm.exe` 仍然能构建，不动点仍然成立。
3. **`selfhost\parts\emit_llvm.vel`**（新部件）——emitter：与 `emit.vel` 相同的 AST 遍历，只是把 C 文本换成 shim 调用。按逐步扩大的顺序：一次调用、一个字符串字面量和一个常量（也就是 M1 所证明的），然后是 `print`，然后是带检查的算术，然后是 `if`/`while`/`for i in range`。结束条件：`vm.exe build-llvm examples\hello.vel` 产出一个能运行的可执行文件，而它的输出等于解释器和 C 后端的输出。
4. **`selfhost\parts\vm_main.vel`**——两个模式：`emit-llvm`（临时目录里的 `.ll` 文本，供阅读和 diff）和 `build-llvm`（shim → `%TEMP%\vela-build\` 里的 `.obj` → 与 `vela_llvm_runtime.obj` 一起交给 `lld-link` → 可执行文件**放在源码旁边，别的什么都没有**）。`tools\smoke.ps1` 已经断言了最后那条性质，所以通过这一步的是 smoke。
5. **`tools\build.ps1`**——编译那个 shim，用 `LLVM-C.lib` 把它链接进 `vm.exe`，并把 `LLVM-C.dll` 复制到 `vm.exe` 旁边。从那时起，编译器自带它的代码生成器，就像 `rustc` 自带 LLVM；那个 DLL 是编译器在自己目录里的依赖，什么都不会落进用户的项目里。
6. **`tests\run_llvm.vel`**——验收标准所要求的那个差分测试框架：对 `tests\cases.txt` 里的每一个 `run` 用例，解释器的输出、C 后端的输出和 LLVM 后端的输出必须逐字节相同，而且三者都必须等于记录下来的 golden。这就是把"新后端是对的"变成事实的东西，而在 `build-llvm` 被允许成为 `build` 现在所做的事**之前**，它必须通过。
7. **晋级与重新证明**——`build-llvm` 变成 `build`，C 后端为了差分测试被降级成 `build-c`，而不动点在新后端上重新确立：编译器经 LLVM 自己编译自己，发出同样的目标文件。那就是 M7，而它被放在最后声称。

## 接口（阶段 1）

```
vm.exe emit-llvm  <file.vel>          # the module's IR text, on stdout, like emit-c
vm.exe build-llvm <file.vel> [rtdir]  # emit the object in process, then lld-link it
```

**驱动实际做什么，2026-09-20 从它身上读出来的**（`vm_main.vel`，`:1164` 处的 `build-llvm` 模式）——这正是上面那条最初的接口说明弄错的地方，因为那段描述的是阶段 1 的形态（把 `.ll` 文本交给 `clang-cl`），而阶段 2 用一个在进程内构建的目标文件取代了它：

* 那个目标文件来自 shim（`vshim_emit_object_buf`），也就是来自 **`vm.exe` 里面的 libLLVM**——不是来自一个解析 IR 文本的程序；
* 同一个模块还会由 `write_ll` **作为给人看的文本**写进临时目录，这样一个人不用第二个工具就能读到发出的是什么；
* 从那往后**唯一的外部程序就是 `lld-link`**，它通过 `VELA_LLD` 环境变量或者 `tools\get-llvm.ps1` 创建的那份安装来找到。如果它不在，这个模式会 panic 并说出来，而不是退回去用一个 C 编译器；
* `vela_llvm_runtime.obj` 必须坐在**编译器旁边**，而它不在时这个模式会 panic 并点名它（`vm_main.vel:978`）。还没有任何东西写出那个目标文件：它该在的地方是 `tools\build.ps1`，这就是为什么 `build-llvm` 今天还不能端到端地跑。
- 输出路径遵循 C 后端的规则，那条规则是：**可执行文件落在源码旁边，除此之外没有别的**。`.ll` 和 `.obj` 进 `%TEMP%\vela-build\<压平后的源码路径>\`，也就是 C 后端已经在用的那个临时目录，理由也一样——这个项目的北极星是 `vm.exe build x.vel` 在用户的目录里只留下 `x.exe`，不留其他任何语言的文件。C 后端在这件事上的历史值得记两遍：它曾经依赖"从仓库根目录运行"（由此产生的每一次失败，都是一个不知道自己程序为什么构建不出来的用户），而它曾经把 `.c` 和 `.obj` 写在用户源码旁边，那正是阶段 1 绝不能重新引入的东西。
- LLVM 后端上的 `parallel for` 是**被带着说明理由的消息拒绝的**，不是被静默串行化，也不是被静默丢掉。LLVM IR 没有 OpenMP；发出那个运行时 ABI（`__kmpc_fork_call`）是实打实的工作量，而且属于阶段 1 范围之外。一个自称并行却串行执行的循环，是这个项目已经承诺绝不交付的那种失败模式。

## 运行时契约

`runtime/vela_llvm_runtime.c` 是新的，它只做一件事：`#include` `vela_runtime.h`，并暴露一批**非 `static`** 的包装，名字就是发出的 IR 所调用的那些，这样带检查的算术、边界检查、arena 和 panic 约定就有唯一一份真相来源，与 C 后端共享。

这些名字就是 C 后端自己的那些——`vela_bounds_check`、`vela_add_range`、`vela_sub_range`、`vela_mul_range`、`vela_print_i64`、`vela_print_f64`、`vela_print_str`、`vela_print_nl`、`vela_panic`，以及字符串表函数、arena 函数。复用它们是刻意的：两个后端之间*名字*上的差别，是没有人会注意到的差别，而下面那个差分测试比较的是行为，不是词汇。

**绝不要 `abort()`。** 一次失败的检查会调用 `vela_panic`，它打印到 stderr 并以 `VELA_PANIC_STATUS`（2）退出。`abort()` 会弹出 Windows 错误报告对话框，而这个运行时早先有一版用了它，结果把执行框架的作业运行器卡死了两轮。这是实测的，不是理论上的。

**打印。** `vela_print_f64` 必须精确复现 C 后端的格式化，因为语料比较的是字节。C 库对 `%g` 风格的输出做了什么，那就是可观察的契约；LLVM 这一侧调用同一个运行时函数，而不是自己格式化。

## 类型与布局（要用测量钉住，而不是靠记忆）

| Vela | IR | 说明 |
|---|---|---|
| `int` | `i64` | 有符号；溢出由那些 range 辅助函数处理 |
| `float` | `double` | |
| `bool` | `i1`，以 `i8` 存储 | |
| `str` | 现有的 `vela_str` 结构体 | 布局从运行时头文件里读出，不在这里重述 |
| `Array[T, N]` | `[N x T]`，在帧里 alloca/global | 除非某项证明把它去掉，否则边界总是检查的 |
| `struct` | LLVM struct，字段顺序与声明一致 | |

目标 triple、data layout 字符串，以及 `printf` 的调用约定，都是**从已安装的工具链里测出来的**，并在知道之后写进本文件；IR 文本对版本敏感，所以这里把在用的 LLVM 版本钉住，并由构建脚本断言它，而不是假设它。

## 差分测试——这就是验收标准

语料已经证明了：一个程序编译后的输出等于它解释执行的输出。LLVM 后端加了第三条路径，而规则是：

> 对 `tests/cases.txt` 里的每一个 `run` 和 `native` 用例，**C 构建**的输出、**LLVM 构建**的输出和**解释器**的输出必须相同，逐字节相同，而且它们每一个都必须等于记录下来的 golden。

那个测试框架是一个 Vela 程序（`tests/run_llvm.vel`，没有 Python），它跑的是套件所跑的那份同样的用例清单。一个又快又错的后端不是后端。

## 里程碑，每一个都在下一个开始之前先被测量

1. **M1——已达成（2026-09-19）。** `hello` 形状的输出、环路里跑着 `clang-cl`、一个能运行的可执行文件，而证明是差分的，不是看一眼屏幕：`tools\llvm-m1.ps1` 把一个程序按三种方式运行——解释器（`vm.exe run`）、C 后端（`vm.exe build` + 运行）以及手写 IR（`selfhost/llvm/m1_probe.ll`，由 `clang-cl` 对着 `runtime/vela_llvm_runtime.c` 链接）——并要求三者打印相同的字节。`RESULT: ok -- three paths, identical bytes`，退出码 0。
   有两件事是靠测量而不是记忆定下来的，而它们如果是靠假设，两件都会错：
   - 模块头，从 `clang.exe -S -emit-llvm` 对一个两行 C 文件的输出里读出：`target datalayout = "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"`、`target triple = "x86_64-pc-windows-msvc19.44.35227"`；
   - `vela_str` 的 ABI，从 clang 为一个 C 调用者生成的 IR 里读出：Microsoft x86-64 ABI 把一个 16 字节的结构体**按引用**传递，并通过一个**隐藏指针**返回它，所以声明是 `declare void @vela_llvm_print_str(ptr)` 和 `declare void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)`——不是那些看起来对却链接不上的按值写法。
   这一片还找到了 LLVM 运行时里第一个真正的可移植性缺陷：`runtime/vela_llvm_runtime.c` 在 C 里用了 `bool` 却没有 `<stdbool.h>`，MSVC 的 C 模式容忍它，clang 不容忍（`error: unknown type name 'bool'`）。
   M1 的描述里仍然缺的一样东西：`build-llvm` 本身。*写下这段的时候*还没有 emitter，所以 `vm.exe build-llvm` 不存在，而 M1 的证据是由一个 PowerShell 驱动把一份手写的 `.ll` 交给 `clang-cl` 产出的。2026-09-20 实测，emitter 已经存在（`selfhost\parts\emit_llvm.vel`，73 064 字节 / 1631 行，注册为第 7 个部件），而 `build-llvm` 是驱动里的一个模式（`vm_main.vel:1164`）——但它仍然不能端到端地跑，因为 `vela_llvm_runtime.obj` 还没有被写出来（`vm_main.vel:978` 会 panic 并点名它）。所以这里**不声称 M2**：下面的 M2 清单不变，而一个里程碑算作关闭，是在它背后有一次差分运行之后，而不是在一个 emitter 能编译之后。
2. **M2**——带检查的算术，`if`/`elif`/`else`、`while`、`for i in range`。证据：差分运行，其中包含一个溢出用例，它必须在两个后端上以同样的消息 panic。
3. **M3**——函数、调用、参数、返回、递归（`recursion_fib`）。
4. **M4**——结构体、方法、定长数组、带边界检查的下标。
5. **M5**——字符串：字面量、`concat`、`len`、`substr`、`bytes_at`、字符串表，以及那套不含 `-` 的算术规则。
6. **M6**——整份 `run` 语料在两个后端上都变绿，加上由 LLVM 构建、并拿来与 C 构建版的数字比较的基准。
7. **M7**——LLVM 后端上的不动点：编译器经由 LLVM 编译后，逐字节地发出它以前发出的那份 C。这就是让"带 LLVM 后端的自举"成为事实而不是希望的里程碑，而它被放在最后是有理由的。

## 我们不声称什么

- 在链接器也是我们自己的之前，不声称"完全没有依赖"——也就是上面阶段表里的 `later` 那一行；这段话过去把它叫作"阶段 4"，而表里并没有那样一个阶段。表里不承载的一个编号不是计划。
- 不声称"比 C++ 更快"——那是一个测量，不是一个意图，而现有的表已经报告了并行 matmul 上的落后。如果 LLVM 改变了这些数字，`bench/RESULTS.md` 会把它们连同它们是哪个后端构建的一起记下来。
- 不声称"`parallel for` 在 LLVM 后端上可用"，直到 M6 带着数字这么说。
- 不声称"C 后端已被废弃"——它继续是参考实现，而两个后端之间的分歧，在被证明是另一回事之前，都是较新的那个后端的 bug。

## 可行性探针在这台机器上发现了什么（实测，2026-09-19）

**这里没有 LLVM 编译器。** *请把它读成它就是的那份 2026-09-19 快照，它被留下来，是因为里面那套推理才是要紧的：LLVM 是一个安装步骤，不是一个设计问题，而 `tools\get-llvm.ps1` 随后被写出来并运行了。LLVM 23.1.1 现在就在磁盘上，在 `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\` 下，而且仍然**不在 `PATH` 上**——这是驱动必须处理的一个事实，而不是一次缺失的安装。* 那是这条工作流的第一个事实，而它是一个安装步骤，不是一个设计问题：

- Visual Studio 2022 Build Tools 住在 `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools`——那个 `(x86)` 很重要，只扫 `Program Files` 会错误地得出"根本没有 Visual Studio"的结论。它的 `VC\Tools\Llvm\` 树里**只有** `clang-format.exe` 和 `clang-tidy.exe`（LLVM 19.1.5）加上运行时 DLL：没有 `clang.exe`，没有 `clang-cl.exe`，没有 `llc`，没有 `opt`，没有 `lld-link`，没有 `llvm-as`。
- `vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Llvm.Clang -property installationPath` 返回**空**——Clang *编译器*那个组件没有安装，所以脚手架在场，编译器不在。
- `C:\Program Files\LLVM` 不存在，没有任何 `libLLVM*.dll` 能够到，`Get-Command clang,clang-cl,llc,opt,lld-link,llvm-as` 什么都找不到，而 chocolatey、scoop、msys64 以及 winget 包，谁都不提供。`winget` 自己则在每一次调用上都失败，包括 `winget --version`（退出码 `-1978335231`，`0x8A150001`），到 llvm.org 和 github.com 的 HTTPS 会关闭连接，而这个会话没有提权——所以从它里面也抓不到任何东西。
- C 工具链是完好的：`cl.exe` 在 `VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64` 下，`tools\build.ps1` 已经能解析出那个路径。

那条便宜的路径，等到有人要做这个决定的时候：**把 `Microsoft.VisualStudio.Component.VC.Llvm.Clang` 加到已经装好的那个 Build Tools 实例上。** `clang-cl` 随后复用已经安装的 MSVC 链接器和 CRT——不需要 `lld`，不需要第二个运行时，不需要另一棵树。它需要管理员权限和一个能用的网络。

对本计划的两处更正，都来自那次探针：

1. 阶段 1 那句"一个 LLVM 工具链二进制"的意思是**先来一个安装步骤**，不是一个配置步骤。
2. "用 LLVM 取代 MSVC"只对 **`cl.exe`** 成立。Visual Studio 那份安装必须留着，因为 `clang-cl` 从它那里取 CRT 和链接器。消失的依赖是那个 C 编译器二进制，不是那份安装。

### emitter 将会需要的事实，靠读运行时发现

- **在 UCRT 下，`stderr` 不是一个全局符号。** 它是 `__acrt_iob_func(2)` 的一个宏，所以 panic 的 IR 必须 `declare i8* @__acrt_iob_func(i32)` 并用 `2` 调用它。声明 `@stderr`（MinGW 的习惯）会链接不上——而这条路径上走着每一条 panic 消息。
- `vela_bounds_check` 是一次**无符号**比较，`(uint64_t)idx >= (uint64_t)len`，正是为了让负下标被抓住：在 IR 里就是 `icmp uge i64`。
- 溢出测试是 `b > 0 && a > INT64_MAX - b`，而那个 `sub` **不能**是 `nsw`：当 `b <= 0` 时那次减法会溢出，`nsw` 会让结果变成 poison，而那个 `and` 只是看起来救了它。一个朴素的 `add i64`（没有 `nsw`，没有 `nuw`）已经与运行时的 `(uint64_t)a + (uint64_t)b` 技巧一致——所以 **emitter 绝不能用 `nsw`/`nuw` 来表达一次检查。**
- 可变参数：调用点必须**重复**那个标记——`call i32 (i8*, ...) @printf(i8* <fmt>, i64 <arg>)`——整数需要 `i64` 配 `%lld` 风格的格式，`float` 在可变参数调用之前必须 `fpext`，而 `main` 是未经名称修饰的 `i32 @main()`。
- `vela_str` 是 `{ i8* data; i64 len }`，而 arena 按 16 字节对齐：两样都从 `runtime/vela_runtime.h` 里读，不要在这里重述。
- 浮点格式化是一份**字节**契约：今天是 `%.6f`，而陷阱在于 `%f` 与它恰好一致——一个自己格式化浮点数的 emitter 会通过每一项测试，直到它静默地发散。
- 那些*确实*在场的工具报告的是 LLVM 19.1.5，所以就预期 19.x 的约定（不透明指针是正常的；`i8*` 仍然被接受）。任何手写 `.ll` 里的目标 triple 和 datalayout 行，在有一个编译器能把它们打印出来之前，都是**未经验证的占位符**——而后来它们被打印出来了，由 `clang.exe -S -emit-llvm` 对一个两行的 C 文件打印，上面 M1 的记录就是从那里拿到 `x86_64-pc-windows-msvc19.44.35227` 和 `e-m:w-p270:32:32-…` 那一行的。所以这一段是历史，它被留下来，是因为它承载的那份谨慎，正是那些字符串如今是被测量出来、而不是被记住的原因；而那些字符串本身现在就是测量。

### 运行层面的警告，它比看起来更要紧

这台机器上的执行框架会在一次构建之后死掉——之后每一条命令都失败于 `Windows Job runner exited with exit code 1 before proving its managed range empty`——而探针自己的那次死亡，与一条经由 `vcvars64.bat` 启动 `cl.exe` 和 `link.exe` 的命令精确相关，而同一批里没有碰 MSVC 工具链的命令则成功了。**是相关，不是证明**，但有两个后果随之而来：每一次 Clang 调用都必须经过与 C 后端相同的那套卫生措施（驱动现在会设置 `VSCMD_SKIP_SENDTELEMETRY=1`，而 `tools\build.ps1` 和 `tools\smoke.ps1` 会在之后清理 `vctip`/`mspdbsrv`），而一个直接调用编译器的实验，应当预期它之后需要重启宿主。
