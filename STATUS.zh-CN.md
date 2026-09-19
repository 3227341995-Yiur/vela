[English](STATUS.md) | **简体中文**

<!--
源文件 : STATUS.md
源文件字节 : 19760
源文件 SHA256 : b987ac295d938a0125d1c7dcfa2b2428646e69388afe01ad0326436d3c6d2f79
翻译日期 : 2026-09-20
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

# STATUS —— 什么已验证、什么只是写好、什么挡在路上

一次工作会话的快照，不能替代运行它所点名的命令。下面每一条 "verified" 都是在这台机器上实测的；每一条 "written" 都是尚未经过它所需要的那件工具的源码，而它自己也这么说。

## 1. 曾经挡在路上、现在不再挡路的东西

在上一轮的大部分时间里，这个会话**任何**命令都跑不起来：每一次尝试都死于

```
Error: subprocess-local: Windows Job runner exited with exit code 1
before proving its managed range empty
```

机制是实测出来的：`cl.exe` 会启动一个**游离的** `vctip.exe`（它的遥测客户端），它会比构建活得更久，几分钟到几小时不等，而执行框架在一个 Windows **作业对象**里跟踪子进程，该作业对象在作业非空时拒绝结束。一次构建就足以让会话卡死，而只有重启宿主或让那个进程退出才能清除。

**在源头、在仓库里修好了，而且这个修复是活的**：`selfhost/parts/vm_main.vel` 里的 `reap_helpers()` 在每次 `cl` 调用之后立刻杀掉那个辅助进程，而那是唯一能做到这件事的地方——驱动套件的脚本会在它走到自己的清理逻辑之前就被杀掉。`tools/build.ps1` 的第 7 步在结尾再收割一次。这一轮跑了几十次构建，包括 190 个用例的套件运行，没有一次卡死。

## 2. 已验证，连同验证它们的命令

| 什么 | 命令 | 结果 |
|---|---|---|
| 工具链构建自己并晋级自己 | `powershell -ExecutionPolicy Bypass -File tools\build.ps1` | `RESULT: ok`；不动点 `0A6A273C5328A435`，758020 bytes，seed / gen-1 / gen-2 **逐字节相同**——而且这项检查现在读的是驱动确实会写出的文件（见 §5）。（哈希从 `15EAE445780BAA58` 移开，是因为编译器现在承载着代码生成器的声明；见下一行） |
| **编译器承载着自己代码生成器的声明** | `tools\build.ps1`（它的 parts 守卫），然后 `vm.exe check` | `parts: 9 on disk, 9 in the linker's list, the same set`；链接后的编译器是 12687 行 / 436910 字节，并通过 `vm.exe check selfhost\vm.vel`（`ok`，exit 0）；生成的 `selfhost\parts\llvm_shim.vel` 也通过 `vm.exe check`（`ok`，exit 0），而那正是当初 55 条声明被暂放在 `parts\` 之外时存疑的东西。`tools\gen-shim-decls.ps1 -Check` 仍然把它的计数与头文件对齐：`matches runtime\vela_llvm_shim.h (64 declarations)`，exit 0。`vm.exe` 保持在 634368 字节，因为声明不携带代码 |
| shim 的 API，用四种方式证明而不是断言 | `powershell -ExecutionPolicy Bypass -File tools\llvm-shim-probe.ps1` | `RESULT: ok`：M1 程序经由解释器、C 后端、`clang-cl` 下的手写 `.ll`、以及 shim + `lld-link` 四种方式运行结果完全相同——四者都产出同样的 24 个字节，而那个流程程序（5 个块、`icmp sge`、`cond_br`、alloca/store/load）产出同样的 7。失败路径以 1 退出并点名那个不同的字节，所以这个证明是会失败的 |
| 这棵树是活的，而且可搬移 | `powershell -ExecutionPolicy Bypass -File tools\smoke.ps1` | `RESULT: ok`——构建+运行、**从仓库之外的目录构建**、以及编译 == 解释，包括一次 panic |
| 套件，全部 | 先 `tools\refreeze.ps1`，再 `tests\run_tests.exe`（`VELA_SELF` 为绝对路径） | **190 passed, 0 failed（共 190 个用例，245 次捕获）**，exit 0 |
| `parallel for` 的别名规则 | `vm.exe check tests\probes\parallel_alias_*.vel` | 跨迭代的读被**拒绝**；四种合法形状仍然发出 `#pragma omp` |
| 编译器在没有 C 编译器的情况下运行程序 | `vm.exe run x.vel` | 解释器不需要 C、不需要 `.ll`、不需要其他语言——这个承载二进制就是它所需的一切 |
| IDEA 插件 | `powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1` | **`RESULT: PASS`**，exit 0：32 个 Kotlin 源文件编译通过，`dist\vela-idea-plugin-0.1.3.zip`（240277 B），每一项结构性/字节码/平台/注册/链接/行为检查都是绿的，版本纪律在四处一致 |
| 供原生后端使用的 LLVM/Clang | `powershell -ExecutionPolicy Bypass -File tools\get-llvm.ps1` | 已安装，**`clang version 23.1.1`**，`llc.exe` 与 `lld-link.exe` 都在，位于 `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\bin\` |
| LLVM 后端，里程碑 M1 | `powershell -ExecutionPolicy Bypass -File tools\llvm-m1.ps1` | **已达成**：一个程序走三条路径——解释器、C 后端、以及由 `clang-cl` 链接的手写 LLVM IR——全都打印**完全相同的字节**（`RESULT: ok`，exit 0）。datalayout、triple 和 MSVC 结构体 ABI 都是从 clang 自己的输出里读出来的，不是凭记忆写的 |
| 基准 | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` | 已针对这个编译器冻结在 `bench/RESULTS.md`：串行 matmul **0.543 s** 对 C++ 孪生版本的 **0.224 s**（2.4×，在 emitter 修复之前是 5×），并行 matmul 0.068 s 对 0.022 s（3.1×，原来是 6.6×），sieve 1.5× 对一行显式*未检查*的 C++，mandelbrot 精确到微秒打平（两者都是 0.011950 s），而 `parallel for` 仍然是真并行（从 1 到 16 线程有 8.0×，每个线程数下校验和都一样）。"比 C++ 更快"仍未成立；见 §4 |

## 3. 写好了，但未验证

- **LLVM 后端**：`selfhost/LLVM_PLAN.md`（阶段划分、差分测试、里程碑 M1–M7）、`runtime/vela_llvm_runtime.c`（它的每个符号都是从 C 后端自己发出的输出里读出来的，不是凭记忆写的），以及 `selfhost/llvm/m1_probe.{ll,vel}` + `tools\llvm-m1.ps1`（M1，**已验证**——§2）。LLVM **emitter** 还一行都不存在，这是有意的：流水线先用一份手写的 `.ll` 证明，因为一个跑得快但错的后端不是后端。在 emitter 存在之前，`vm.exe build-llvm` 也不存在，而通往原生二进制的唯一路径仍然是 C 后端。
- **语言特性**：`selfhost/ENUMS_PLAN.md`（先做枚举 + `match`）和 `selfhost/ELISION_PLAN.md`（两个保留的标志位：下标节点上的 8 × `NF_NO_BOUNDS`，word 6 的运算符节点上的 16 × `NF_NO_OVERFLOW`）。在这些位背后没有任何东西被写出来，直到使它们成立的分析存在为止——那份计划的第一版断言了一个并不存在的区间分析，是在前提被核对之后重写的。今天的测量改变了那份计划的优先级，但没有改变它的规则：事实证明这些检查几乎不花什么代价，所以消除检查的价值低于 §4 里的那个 emitter 缺陷。
- **本轮写好并且至少被跑过一次的工具**：`tools\refreeze.ps1`（已验证，绿的）、`tools\smoke.ps1`（已验证）、`tools\bump-plugin-version.ps1`（用于 0.1.3；它曾有两个缺陷，都已修复——见 §5）、`tools\get-llvm.ps1`（用过，可用）、`tools\llvm-m1.ps1`（已验证，M1）、`tools\bench.ps1`（用过并修复过——见 §5）、`tools\snapshot.ps1`（仍未被跑过）。

## 4. 已知的缺口，被点名而不是日后才发现

- 这个插件**从未被载入一个运行中的 IDE**：`ActionManager` 和扩展注册表需要一个已启动的应用，所以折叠、输入处理、格式化器输出和 Run 控制台都是结构性地检查的——对着字节码、对着平台自己的描述符——而不是通过驱动编辑器来检查。
- 解释执行的运行路径比编译后的慢（在一个 MSVC 无法向量化的循环上约 76×）；插件现在用解释执行来跑程序，而当 C 编译器在场时 `Vela.BuildAndRun` 会编译。数字在 `bench/RESULTS.md` 里，而插件的控制台会说明它走了哪条路径。
- `bench/RESULTS.md` 早于运行时的减法修复，正在围绕今天的数字重写；在那之前它的表是历史，不是测量。要紧的数字在 README 的断言表和下面的 §4 里。
- **"比 C++ 更快"不成立。** 今天重新实测，运行时的检查终于能工作了：串行 matmul 512² **1.058 s** 对 C++ 孪生版本的 **0.213 s**（5×），并行 matmul 0.128 s 对 0.019 s（6.6×），sieve 0.0196 s 对 0.0129 s，mandelbrot 打平。原因是实测出来的，不是猜的，而这个测量有一个值得读两遍的修正版。**请把上面的数字读作修复前的状态**，这也正是它们被保留的原因：它们变成了什么是串行 matmul 0.543 s 对 0.224 s（2.4×）、并行 matmul 0.068 s 对 0.022 s、sieve 0.0197 s 对 0.0134 s——见 `bench/RESULTS.md`，以及 §2。下面那张表在这里，是因为它是第二个杠杆的论证，而不是因为它是当前的。

  | 同一份发出的 C 的变体（都打印 1090512707） | 秒 |
  |---|---|
  | 编译器今天发出的样子 | 1.058 |
  | 下标提到临时变量里，算术仍然带检查 | 0.550 |
  | 同上，边界检查也去掉 | 0.514 |
  | 下标不带检查，边界检查保留 | 0.177 |
  | 什么都不检查 | 0.181 |
  | C++ 孪生版本 | 0.213 |

  所以有**两个**杠杆，按顺序：emitter 把每个下标表达式写了**两次**（值 0.51 s——把程序的耗时砍掉一半；**已完成**，1.058 s → 0.543 s），在那之后带检查的下标算术（`vela_mul_range` + `vela_add_range`）约 0.37 s，占剩下的约 68%。边界检查本身约 0.04 s。第二个杠杆正是 `selfhost/ELISION_PLAN.md` 的用途所在，而这张表就是它的理由——它就是决定"比 C++ 更快"这条断言的工作。

  **更正，因为这段话的第一版是错的。** 它说"这些检查几乎不花代价，重复才是全部的故事"，引用的是一个手工编辑的变体，那个变体被标注为 "index computed once, checks kept"，却把*不带检查*的算术提了出来，只保留了边界检查。那个标注没有描述那个变体，因此由它得出的结论是假的。第二次测量在隔离同一项代价时独立进行，产出了上表和真实的拆分。实验上的标注是承重的。
- **版本控制现在有了。** 在这次会话之前这个仓库没有版本控制——这很重要，因为三个测试源文件已经丢失且无法找回。这台机器上有 `git.exe`，代码树已提交（见 `git log`），而 `.gitignore` 被写成：一份全新的检出保留全部 65 个语料源文件，同时忽略每一个生成文件，除了构建启动所需的那两个（`selfhost\build\vm.c` 和 `selfhost\build\vm.exe`）。

## 5. 本轮通过运行东西发现的缺陷

下面每一个都是靠读发现不了的：

- **`build` 能为一次成功的构建报告失败，并且在那时把可执行文件留在身后。** 用被裁剪到 `C:\Windows\System32;C:\Windows` 的 `PATH`（所以 `cl` 只能通过绝对路径的 `vcvars64.bat` 到达）在一个全新目录里实测：`vm.exe build hello.vel` 以 **2** 退出，并打印 `vela: build: the C compiler refused <…>\hello.vel.c` 与 `vela: panic: build: no C compiler on this host could build the emitted C`，而 `hello.exe`（150016 B）**已被写出且能正确运行**（`hello from Vela` / `sum 0..99 = 4950`，exit 0）。把同样的序列手工打成一个 `.bat`——`call vcvars64.bat`、`set VSLANG=1033`、同一行 `cl`——每一步都返回 `ERRORLEVEL=0` 并产出同一个可执行文件，所以*编译器*没有错；错的是驱动的判定。形状在 `vm_main.vel` 里看得见：退出码在第 722-729 行做决定，而产物到之后第 730 行才被检查，于是"非零退出码 + 磁盘上有一个新的可执行文件"会在那个本可说出实情的检查之前 panic——留给用户的是一个能用的 `hello.exe` 和一条坚称什么都没构建出来的消息。修复方向，待构建路径不被其他工作占用时实施：**在调用 C 编译器之前删除目标文件**（Vela 没有 `stat`，所以"文件存在"只有在之前什么都不存在时才能证明新鲜），然后让产物做决定、让退出码做解释。目前有意不修：两个 agent 正在运行中，而构建路径是承重的。
- **`vm.exe debug` 被宣传了却什么都不做。** 用法行列出了 `lex|count|parse|nodes|emit-c|run|check|debug|build`，而 `vm.exe debug examples\hello.vel` 以 2 退出、**完全没有输出**。所以 IDEA 插件拒绝提供 Debug 按钮是诚实的，并且在那个模式存在之前都保持诚实。

- `runtime/vela_runtime.h` 缺少 `vela_bounds_check`，于是**每一个编译过的程序**都链接失败；而 `vela_sub_overflows` 是错的，于是每一个编译后的减法都 panic——包括 `1 - 5`。
- `find_runtime`/`has_runtime` 把运行时 include 目录解析成了仓库根目录，于是在其他任何目录下的构建都会死于 `C1083: cannot open include file: 'vela_runtime.h'`。
- 发出 C 的临时目录是按文件*名*做键的，所以不同目录下两个同名的源文件会互相撞车；现在按压平后的完整路径做键。
- `VelaFormatter.kt`：在这个平台上 `ASTBlock.getSpacing` 接受的是 `Block?`，不是 `Block`——四个编译错误。
- `VelaFindUsages.kt`：`private val wordsScanner` 生成了一个 `getWordsScanner()` 访问器，在 JVM 层面与它所覆写的接口方法冲突。
- 插件验证器（`idea-plugin/build/tools/src/VerifyPlugin.java`）把声明的*邻居*记上了 `<with>` 属性，因为它扫描的是一个固定的 4000 字符窗口；八个按 JetBrains 自己捆绑插件写法写的注册被报告为未绑定。它现在只读声明自己的主体。
- `tools/bump-plugin-version.ps1` 在仓库根目录找 `plugin.xml`（它在 `idea-plugin\` 下），而且它的版本替换什么都不匹配，因为在 `$a -replace 'x' + $v + 'y', 'z'` 里逗号比 `-replace` 结合得更紧，把模式变成了一个数组。两处都已修复并用于 0.1.3。
- `runtime/vela_llvm_runtime.c` 在 C 里用了 `bool` 却没有 `<stdbool.h>`：MSVC 的 C 模式容忍它，clang 不容忍（`error: unknown type name 'bool'`）。是 M1 探针发现的——这正是 M1 要在 emitter 之前存在的原因。
- C emitter 把**每个数组下标表达式写了两次**——这就是丢掉"比 C++ 更快"的那个缺陷，在 matmul 基准上实测为 5–6.6×，并由受控实验量化（见 §4）。**已修复**：下标进入一个语句作用域的临时变量，只计算一次；串行 matmul 1.058 s → 0.543 s，发出的编译器 850572 → 754867 字节，没有去掉任何检查。这个修复的第一版有一个竞态——临时变量声明在函数作用域，所以在 `parallel for` 内部所有线程共享一个变量，一个线程的下标给另一个线程的存储做下标；`tests/probes/parallel_alias_nested_rowmajor.vel` 失败并打印 `1 1`。现在声明是按语句的，位于最内层块里，OpenMP 会把它变成私有的。
- **`tools\build.ps1` 的不动点步骤读的是一个没有任何东西写的文件。** 它找的是 `%TEMP%\vela-build\vm.vel.c`，并把它复制到 `selfhost\vm.c` 上作为种子，但驱动在临时目录键变成*完整*源路径（`build_scratch`/`flat_name` → `selfhost_vm.vel.c`）时就不再写那个名字了。一个 03:05:47 的陈旧 `vm.vel.c` 还在磁盘上，于是这一步一直在把一个更早世代的产物晋级，而它报告的"逐字节相同的不动点"是对着那个产物测量的。现在文件名由驱动自己的规则算出，构建前先删除该文件，文件缺失就是失败并打印临时目录列表，而源路径只写一种拼法（`selfhost/vm.vel`），因为这种拼法被嵌进每一个发出的 file/line 字面量——对同一个程序，`selfhost\vm.vel` 发出 758641 字节，而 `selfhost/vm.vel` 发出 754867 字节。
- 基准框架自己跑不起来：`bench/run_bench.vel` 用正斜杠、没有绝对路径去 spawn `selfhost/build/vm.exe`，于是 cmd.exe 在第一个 `/` 处切开，编译器从未启动，而失败读起来像"Vela 那一侧没有构建成功"。它现在从 `VELA_SELF` 解析编译器，并在失败时带着这个解释 panic，而 `tools\bench.ps1` 会设置那个变量。
- 文档此前声称有一个 Python 前端、一个 `--fast-int` 开关、一个浏览器 IDE 和一个 `restrict` 关键字。它们都不存在；那一轮产出了 19 处更正。

## 6. 环境陷阱，每一条都实测过

- **跑套件必须设置 `VELA_SELF`**，且必须是绝对路径。相对回退写的是正斜杠，套件为了 cmd.exe 把命令多包一对引号，cmd 在第一个 `/` 处切开，而被报告的原因则是驱动无害的第一行。实测：不设置，30 个用例失败；设置，0 个。
- **`C:\Users\lu\Downloads` 是这个会话所运行的一切的可写根**。
- **`curl.exe` 和 .NET 在这里做不了 HTTPS**（TLS 拦截：schannel `SEC_E_NO_CREDENTIALS`，.NET `基础连接已经关闭`）。`node --use-system-ca` 可以；在 curl 做不到的时候，它抓下了 LLVM 那个 489740247 字节的归档。
- **`javap` 不存在**；验证器的 `ClassSig`/`MethodFlags` 反射工具是它的替代品。
- **机器上任何地方跑着的两个 `vm.exe` 构建曾经会互相摧毁，而这件事——不是磁盘争用——才是让套件连续两轮不稳定的原因。** 驱动的每次构建诊断文件是按*编译器二进制*命名的，于是每次构建都写同一个 `…\selfhost\build\vm.exe_cc.bat` 并运行它：进程 A 执行了进程 B 刚刚写下的批处理，于是 A 编译了 B 的 C 文件，两者为 B 的目标文件打了起来。当场抓住，消息里点名了错误的 C：

  ```
  cl … "…\dsh-AYfS5n\vela-build\tools_link_selfhost.vel.c"   <- what A asked for
  C__Users_lu_…_dsh-ZYih20_vela-llvm-shim_m1_probe.vel.c     <- what cl actually compiled
  …m1_probe.vel.c : fatal error C1083: cannot open compiler generated file:
      "…m1_probe.vel.obj"  Permission denied
  vela: panic: build: no C compiler on this host could build the emitted C
  ```

  不同源文件的两个构建同时启动，每次都复现（`a.err` 和 `b.err` 都点名 `_b.vel.obj`）；单个构建从不复现。驱动现在按构建自己的临时 C 给这些文件命名（`<scratch>/<flat>.vel.c_cc.bat`），这同时也把它们放到其余中间产物该去的地方，而不是放在编译器旁边。已验证：同样的两个并发构建都打印 `built …  (with OpenMP)` 并以 0 退出，`selfhost\build\` 也不再堆积 `*_cc.bat` 杂物。不动点在 `57D6650FC7824D50`、754954 bytes 处重新确立，seed / gen-1 / gen-2 逐字节相同。
- 本文件中更早的那个解释——"大量并发 I/O 让 `cl.exe` 不稳定"，是在解包一个 2 GB 的 LLVM 归档时推断出来的——是**错的**，它被留在这里作为更正而不是被删除。在给定两个并发构建的前提下，这种不稳定是确定性的；那个归档只是时间上的巧合，而记录里一个错误的机制比没有机制更糟。
- **`tools\verify-all.ps1` 不会失败。** 它第一次真正的运行打印了 `RESULT: ok - 4 step(s) ran, all green`，而套件那一步刚刚报告了两个失败用例：失败过滤器读的是 `$r.Ok -eq $false`，其中 `$r` 是汇总循环的变量、在 `Where-Object` 块里永远是 `$null`，而 `$null -eq $false` 是假。已修成 `$_.Ok`，并对着一个合成的结果列表验证过：旧表达式找到 0 个失败，修好的找到 1 个并点名那一步。一个不会失败的门禁，正是门禁本身要抓的缺陷。
