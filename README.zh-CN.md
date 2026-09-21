# Vela

[English](README.md) | **简体中文**

<!--
源文件 : README.md
源文件字节 : 34727
源文件 SHA256 : 8ffbb2177ad5deea413c89f262e2fd110d151abe77d155fc14e3b1ff16a45be6
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

一门系统级语言，具备 **Python 形状的语法、C 形状的性能，以及一套没有逃生舱的安全叙事**：没有 `unsafe`，没有裸指针，没有手工 `free`。

`no FFI, ever` 曾经是那句话的结尾，而它今天不再为真：stage 5 交付了面向 **C** 函数的 `extern c`，形状就是 `DESIGN.md` §9 描述的那个小形状——一条没有头文件、没有库名的声明，参数与结果只能是标量，检查器在它无法诚实描述这条边界的地方拒绝（声明处的 `str`、一个数组或一个结构体；调用点处种类错误的实参）。活下来的承诺更窄，就在下面的 stage 表里：Vela 程序里的任何东西都不能把*指针*放进这门语言，所以外部函数无法被用来绕过一项检查。

```vela
struct Vec2 {
    x: float
    y: float

    def dot(self: Vec2, o: Vec2) -> float {
        return self.x * o.x + self.y * o.y
    }
}

pure def fib(n: int) -> int {
    if n < 2 {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

def main() -> None {
    mut total: int = 0
    for i in range(0, 15) {
        total += fib(i)
    }

    mut a: Array[float, 262144] = [0.0]
    mut b: Array[float, 262144] = [0.0]
    mut c: Array[float, 262144] = [0.0]
    for i in range(0, 262144) {
        a[i] = to_float(i % 7) * 0.5 + 1.0
        b[i] = to_float(i % 11) * 0.25 + 2.0
    }

    parallel for i in range(0, 512) {           # accepted only when race-freedom is proved
        for j in range(0, 512) {
            mut s: float = 0.0
            for k in range(0, 512) {
                s += a[i * 512 + k] * b[k * 512 + j]
            }
            c[i * 512 + j] = s
        }
    }
    print(total, to_int(c[0]))
}
```

## 今天实测到了什么，什么还只是承诺

这个项目已经为"写进文档但并不为真的断言"付出过好几次代价：一个并不存在的 `--fast-int` 开关、被描述为"已被证明消除"而实际上每一处检查都照旧发出的边界与溢出检查、一个从未被发出的 `restrict` 子句，以及三个因为缺少一行注册代码而发布后什么都不做的插件功能。所以这门语言所立足的四条断言在这里被列出，每条都配上判定它的命令，以及最近一次实测到的数字。补上这些差距的工作在 `ROADMAP.md` 里，其中每一项都点名了自己的验收测试。

| 断言 | 判定它的命令 | 实测 |
|---|---|---|
| **比 C++ 更快** | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` | **尚未成立，而差距现在被集中在一处测量出来了。** 在 2026-09-19 的 emitter 修复之后（每个数组下标只计算一次，而不是两次），串行 matmul 512² 比它的 C++ 孪生版本**慢 2.4 倍**（0.5433 s 对 0.2241 s，`bench\RESULTS.md`），并行 matmul 慢 3.1 倍（0.0680 s 对 0.0221 s），sieve 在一个显式未检查的 C++ 行面前慢 1.5 倍*同时又保留着自己的检查*（0.0197 s 对 0.0134 s），而 mandelbrot 精确到微秒地打平（两者都是 0.011950 s）。剩下的 matmul 差距全部是**带检查的下标算术**——下标上的 `vela_mul_range`/`vela_add_range` 约占修复后耗时的 68%，而边界检查只有约 0.04 s——所以决定这条断言的数字是 `selfhost/ELISION_PLAN.md` 将产出的那个数字，而不是本行 |
| **Rust 的安全设计** | 语料中的拒绝用例、`tools\smoke.ps1`、`vm.exe check tests\probes\parallel_alias_*.vel` | 没有指针、没有 `unsafe`、没有 `free`，只有 arena；边界与溢出检查在编译后的代码*以及*解释器里都会触发，而且消息相同；写数组的 `parallel for` 循环体只能在自己的下标处读该数组——**由一次运行证明**：跨迭代的读被拒绝（`'parallel for' reads 'a' at an index other than the one it writes …`），而那四种合法形状仍能编译并照旧发出 `#pragma omp` |
| **Python 的语法，更严格的语义** | `vm.exe check` | 条件必须是 `bool`，`//` 和 `%` 是地板除，字符串 `+` 被拒绝，产生值的语句被拒绝——但一个绑定到 `int` 的 `str` 仍会被 `check` 接受，而未声明的类型名是被 emitter 的 panic 捕获的，不是被诊断捕获的 |
| **纯血** | 在 `PATH` 上没有 `cl.exe` 的情况下 `vm.exe build selfhost\vm.vel` | 编译器的源码是 Vela，链接器是 Vela，任何地方都没有 Python、没有 C++，并且 `selfhost_fixpoint` 通过——但 **`build` 仍然需要 C 编译器**：C 就是代码生成后端。`run` 什么都不需要（`vm.exe run` 是内置的解释器），而那个要把 C 编译器从 `build` 里拿掉的 LLVM 后端是**已开始、未完成**：它的 M1 已达成（同一个程序，分别经由解释器、C 后端、以及由 `clang-cl` 链接的手写 LLVM IR，逐字节打印出完全相同的字节——`tools\llvm-m1.ps1`），而将要写出那份 IR 的 emitter 尚不存在 |

三个缺陷是通过把代码树对着这些断言读、而不是相信它们而找到的，且三个都已修复：编译器无法链接任何程序，因为运行时头文件丢了 `vela_bounds_check`；编译后的代码里每一次减法都错误地 panic，因为溢出判定并不等价于
`a - b < INT64_MIN`；以及 `build` 把自己的 include 目录解析得高了一层，所以它只在从仓库根目录运行时才工作——这正是 IDE 插件的 Run 按钮什么都不产出的原因。

有三个工具各自用一条命令回答大部分"它还活着吗"的问题。**三个都已经跑过，而且截至 2026-09-20 三个都是绿的**——这一段以前说的是相反的话，而它的理由（执行框架被一个游离的 `cl.exe` 辅助进程卡住）已经是历史：那个卡死在源头被修好了，在 `selfhost/parts/vm_main.vel` 里（每次 `cl` 之后 `reap_helpers()`），`STATUS.md` §1 记录了这个机制，而此后的各轮跑了几十次构建和 190 用例的套件运行都没有再遇到它：

```bat
powershell -ExecutionPolicy Bypass -File tools\smoke.ps1      :: build+run, build from elsewhere, compiled == interpreted
powershell -ExecutionPolicy Bypass -File tools\refreeze.ps1   :: re-record the digests, keep the expectations, run the suite
powershell -ExecutionPolicy Bypass -File tools\build.ps1      :: the whole toolchain, six steps
```
## 相对 Python，Vela 站在哪里

| | Python | Vela |
|---|---|---|
| 结构 | 缩进 | **花括号**（`{ }`），缩进永远不是语法 |
| 声明 | 推导出来的，无类型 | 每个参数、返回类型和非数组绑定都要标注类型 |
| 转换 | 隐式，且无处不在 | **没有隐式转换**，唯一例外是能装进另一个操作数类型的整数字面量 |
| 真值性 | `if x:` | 条件必须是 `bool`；要写 `if x != 0` |
| 变量 | 永远可变 | 默认不可变，用 `mut` 选择加入 |
| 语句 | 任何表达式 | 不是调用、却产生值的语句会被**拒绝**（什么都不干的 `x + 1` 是 bug） |
| `a < b < c` | 合法，但常常是 bug | 拒绝 |
| `a // b`、`a % b` | 地板语义 | 地板语义——有意*不是* C 的截断 |
| 字符串 `+` | 隐式拼接 | 拒绝；两个字面量并排摆放也被拒绝 |
| `"a" "b"` | 意外的拼接 | 错误 |
| 单行 `if` | 允许 | 花括号让两种写法都变得明确 |

## 相对 Rust，Vela 站在哪里

Rust 的安全性来自所有权系统外加一个 `unsafe` 逃生舱。Vela 的活动部件更少，而且**完全没有逃生舱**：

* 所有内存都来自同一个 **arena**——`free` 不存在，所以 use-after-free 或 double-free 写不出来；
* 任何函数都不能**返回自己的数组**，所以 arena 指针永远不可能比它的栈帧活得更久；`str` 的存储要么是字面量，要么是永久内存；
* 数组索引是**带检查的**，只有当编译器*证明*下标在范围内时检查才会消失（区间分析 + 循环变量跟踪 + 条件收窄）；
* 整数运算的溢出是**带检查的**，检查只在有证明时消失。没有任何开关能关掉检查：检查由编译器发出，而这门语言没有可要求的回绕模式；
* `divide by constant zero` 和 `constant index out of range` 是编译错误，不是运行期惊吓；
* `parallel for` 只有在编译器证明各次迭代无数据竞争时才被接受（单射下标、无共享写入、无非纯调用）。

这些证明在编译器写出的 C 里看得见，而那是它给出的唯一报告——没有横幅提示，而且这棵树里任何地方都没有 Python：

```
$ selfhost\build\vm.exe emit-c tests\build\parallel_for_correct.vel
    ...
    #pragma omp parallel for          <- race-freedom was proved, so this is real OpenMP
    ...
    vela_bounds_check(...)            <- and two indexes it could not prove kept their check
```

编译器自己那 760 KB 的发明的 C 里带着 2215 处 `vela_add_range`、1751 处
`vela_mul_range` 和 952 处 `vela_bounds_check` 调用：它是开着检查编译出来的。

## 性能

同样的算法、同一台机器、MSVC `/O2`、**7 次运行取中位数**，时间在每个程序内部测量，并且每个变体的答案都被检查过是否一致。
用 `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` 复现。
完整表格、离散度与方法：`bench/RESULTS.md`。

一台机器：AMD Ryzen 7 9800X3D，8 核 / 16 逻辑处理器，约 4.7 GHz，机器其余部分空闲。换一颗 CPU，就是另一组数字。

**这些数字只住在一个地方：`bench/RESULTS.md`，而本节有意不再带第二张表。** 它曾经带过。曾经立在这里的那张表说 Vela 的串行 matmul 比 C++ 构建*快*约 10%（0.2055 s 对 0.2270 s），而本文件靠前的断言表把同一个基准引为慢 2.4 倍（0.5433 s 对 0.2241 s）——同一份文档里两个互斥的答案，是有人为了翻译而逐行读这个文件时发现的。更快的那一组是**在** 2026-09-19 那个 emitter 缺陷被发现**之前**的那次运行（每个数组下标都被计算了两次）；`bench/RESULTS.md` 是替换掉了那张表，而不是去编辑它，而它的撤回声明原话如此：*"The old table said Vela's serial matmul beat both C++ serial builds. It does not."*

`bench/RESULTS.md` 现在所说的，是在那里描述的同一台机器上、对着同样的 C++ 孪生版本：串行 matmul **慢 2.4 倍**（0.5433 s 对 0.2241 s），并行 matmul 慢 3.1 倍（0.0680 s 对 0.0221 s），sieve 在一个显式未检查的 C++ 行面前慢 1.5 倍*同时又保留着自己的检查*（0.0197 s 对 0.0134 s），而 mandelbrot 精确到微秒打平（两者都是 0.011950 s）。剩下的 matmul 差距全部是带检查的下标算术——下标上的 `vela_mul_range` / `vela_add_range` 约占修复后耗时的 68%，而边界检查约 0.04 s——所以决定"比 C++ 更快"的数字是 `selfhost/ELISION_PLAN.md` 将产出的那个，而**那条断言今天不成立**。

`--fast-int` 的 sieve 变体不再存在：自举编译器总是发出带检查的算术，所以已经没有未检查的 Vela 构建可供引用。以前出现在这里的那一行，是被删掉的 Python 前端产出的。

Vela 做到这些，是靠把机器级工作交给一个成熟的优化器（C11 后端），同时供给 C++ 编译器只能靠猜的那些事实：

* 每个数组参数都是 `restrict`，因为这门语言没有别名——这是一条语言规则，不是某个人可以忘记的标注；
* 热代码从 arena 分配：循环里没有 `malloc`，没有 `free`；
* `#pragma omp parallel for` 只在检查器**证明**了迭代无数据竞争的地方发出——而且它现在是真的并行代码，不再是"带着一句注释说这是故意的"串行循环；
* C++ 版本不得不保留（或者不安全地丢掉）的溢出与边界检查，在这里被证明消除。`sieve` 就是这件事在算术上的证据：Vela 全程保持检查，却依然与那个丢掉检查的构建打平。

诚实的保留意见：这是三个微基准，C++ 孪生版本是为这个仓库写的，都在一台机器上，换一颗 CPU 或换成受内存带宽限制的工作负载，结果可能翻转。Vela *没有*赢的地方都会被报告出来，而上面四行里有三行就是这种情况：mandelbrot 和 sieve 是打平，并行 matmul 是落后。并行变体的计时在多次运行之间还有明显离散：C++ OpenMP 构建的最差/最好之比是 2.3×，Vela 的是 1.4×——`bench/RESULTS.md` 在每个中位数旁边都列出最好与最差，所以请把并行那一列看作指示性的，把串行那一列看作扎实的。
## 快速上手

下面的一切都是自举编译器。这个仓库里没有 Python，本页任何一条命令里也没有 Python。

```bat
selfhost\build\vm.exe run   examples\hello.vel   :: interpret it (no C compiler)
selfhost\build\vm.exe build examples\hello.vel   :: compile it with MSVC/gcc and run
selfhost\build\vm.exe check examples\hello.vel   :: front end only: diagnostics + proofs
selfhost\build\vm.exe emit-c examples\hello.vel  :: show the generated C11
selfhost\build\vm.exe lex   bench\matmul.vel     :: the token stream
selfhost\build\vm.exe parse bench\matmul.vel     :: the syntax tree

powershell -File tools\build.ps1 -Suites          :: rebuild everything and test it
tests\run_tests.exe                              :: the test suite on its own
tests\run_tests.exe record                        :: re-freeze the goldens
tests\run_tests.exe struct                        :: only cases whose name matches
```

`tools/build.ps1` 就是整条工具链：从签入的 C 里引导出 `vm.exe`，用一个用 Vela 写的链接器把编译器的各个部件链接起来，让编译器编译它自己，并检查不动点——它构建出的编译器必须再写出同样的 C。它的记录落在 `..\vela-build-report.txt`。

测试套件是 `tests/run_tests.vel`——一个由 Vela 运行的 Vela 程序，它把语料（源文件加 `tests/golden/`）按住它必须做到的事：一个程序在编译*以及*解释时打印什么、每个被拒绝的程序必须产出什么诊断、每一份 token 流、语法树和发明的 C 的摘要，以及编译器的自我编译。

## IDE 是一个 IntelliJ 插件

这里曾经建过一个浏览器编辑器，并且**已经删掉**：一门语言想要的是人们已经在用的编辑器——它的导航、它的重构、它的调试——而一个自制的 Web IDE 与之相比只是玩具。一个需要 Python 才能把自己组装起来的工具链也是如此；stage 0 跟着它一起走了。

[`idea-plugin/`](idea-plugin/) 是替代品，它为这台机器上的 IDE 而构建，不用 Gradle，也不用网络（IDE 自带的 JBR 和捆绑的 Kotlin 编译器就够了；`idea-plugin/build-offline.ps1`）：

| 你能得到什么 | 它如何工作 |
|---|---|
| `.vel` 和 `.vela` 文件都算 Vela | 文件类型同时认领两者；起初它只认领 `.vela`，也就是说这个插件在本仓库赖以构成的那些文件上什么都不做 |
| 语法高亮 | 一个按 `selfhost/vela.vel` 同样规则写的词法分析器 |
| **边输入边给出错误与警告** | `VelaExternalAnnotator` 运行编译器，并原样画出它说的话。检查的是*缓冲区*，不是已保存的文件，所以一条波浪线永远不会是关于你程序的上一个版本的 |
| 补全、悬停、参数信息 | `VelaModel` 用插件自己的词法分析器读取名字（声明、字段、参数、内置函数）；关于*含义*，仍然只问编译器 |
| Structure 视图 | 同一批声明构成的树，可导航到它们所在的行 |
| **Vela AST** 工具窗口 | 编译器自己的 `vm.exe parse` 转储，缩进树一并呈现。它在你切换文件或按下 Refresh 时运行——绝不按每次按键运行 |
| 运行 / 调试一个文件 | IDEA **自己的** `Run 'fib.vel'` 和 `Debug 'fib.vel'`，由一个 `RunConfigurationProducer` 产出——所以 Run 菜单、行号栏的箭头、Run 窗口、重跑历史和停止按钮都是平台的，不是它们的私有副本。该配置**解释执行**文件（`vm.exe run`），把命令行、程序输出和退出状态打印到平台的控制台里：没有构建步骤，源码旁边没有可执行文件，也不需要 C 工具链。Debug 被*拒绝*，直到 Vela 有调试器可以交给它（见下文），而不是用"运行一次"来假装 |
| New → **Vela File** | 创建一个包含合法程序的 `.vel` 文件，位置与 Java Class、Python File 同一个 New 菜单 |

有一件事被有意**从那张表里省掉**：上下文菜单里没有 "Vela" 子菜单，也没有自定义的 Check 动作。更早的版本两者都有，而它们是仪式——"Check" 与实时诊断重复，而实时诊断就是编译器自己的话，随打字画出；"Run with Vela" 比 IDEA 的 Run *更差*地重复了它（一个控制台而不是 Run 窗口，没有重跑历史，没有行号栏箭头，没有 Debug）。一个语言插件应该添加编辑器本来就做不到的东西，而不是为它已经能做到的事再提供第二个查看的地方。

如果你要编辑 `plugin.xml`，有一个注册细节值得知道：错的扩展点 id 是一次**静默的空操作**，而不是错误。插件照常加载，那个条目什么都不做，功能永远不会出现——第一个版本发出了 `extensions="vela"`、`<parserDefinition>`（它的 id 是 `com.intellij.lang.parserDefinition`），以及一个在 IDE 的 1987 个 jar 里都不存在的 `lang.structureViewBuilder`。`idea-plugin/build-offline.ps1` 更像一个验证器而不是构建脚本：它检查文件类型认领了 `vel`、每个 `add-to-group` 都指名了一个真实的分组、`plugin.xml` 点名的每个类都能加载并与已安装平台链接，并且它端到端跑通编译器/parse/诊断这条路径。它最后一次记录的判定是 `RESULT: PASS`——针对 262 平台，每一项结构性、字节码、平台、注册、链接和行为检查都是绿的，版本是 **0.1.3**，也就是那个带着格式化器（缩进设置、折叠、注释、花括号匹配、类型化处理与回车处理、配色方案页、实时模板、转到声明、重命名、查找用法、语义着色和参数名内联提示）的发布版。0.1.2 是从未被编译过的那个版本：它第一次编译就在两个文件里找出四个错误，列在 `idea-plugin/CHANGELOG.md` 里。扩展点表格读 `idea-plugin/PLUGIN_SURFACE.md`，编译门禁与仍未验证的部分读 `idea-plugin/BUILD_CHECKLIST.md`。Debug 保持被有意拒绝，而不是被伪造：编译器源码里有一个 `debug` 模式，却没有一个能回答它的晋级二进制，所以运行配置会一直压制 Debug 动作，直到这件事为真。

它仍然做不到的事：补全是名字级的，不是类型导向的（Vela 没有可读的局部类型标注）；参数信息把签名画成纯文本，因为 253/262 的 `ParameterInfoHandler` 接口已经没有按参数提供富文档的余地；AST 窗口读的是磁盘上的文件，所以它反映的是你已保存的内容；`<` 和 `>` 无法做花括号匹配，因为词法分析器给每个运算符一种 token 类型，配对会让 `+` 与 `<` 匹配；也没有后缀模板，原因写在模板文件里。

`since-build="253"` 这条断言是检查过的，不是假设的，**针对 0.1.1 的源文件集**：那 18 个源文件被同时对着**已安装的 IntelliJ IDEA 2026.2（构建 262）和已安装的 PyCharm 2025.3（构建 253）**编译过，它们点名的每一个平台类与成员在两者中都存在。`idea-plugin/CHANGELOG.md` 列出的 0.1.2 源文件没有经过那次编译，所以这条断言目前是为 `dist/` 里的产物成立的，而不是为这棵树的工作集成立的——`BUILD_CHECKLIST.md` §1 就是重新确立它的门禁。任何编译都不能证明的、无论好坏的一件事，是在任一 IDE 中的运行期行为：参见 `idea-plugin/BUILD_CHECKLIST.md` 的编译门禁与验收测试、`idea-plugin/PLUGIN_SURFACE.md` 的每一项注册及它被读出的声明，以及 `idea-plugin/CHANGELOG.md` 里每个发布版是靠什么验证的。

## 手工运行套件，以及那个让三十个用例失败的陷阱

```bat
tests\run_tests.exe          :: everything
tests\run_tests.exe fast     :: skip the fixpoint case
tests\run_tests.exe record   :: re-freeze the goldens
```

**在运行它之前把 `VELA_SELF` 设为一个绝对路径。** `tests/run_tests.vel` 通过那个变量找到编译器，若没有就回退到相对路径 `selfhost/build/vm.exe`——用的是正斜杠——而套件为了 cmd.exe 的缘故会把每条命令多包一对引号。cmd.exe 于是在第一个 `/` 处切开那个路径，回答 `'selfhost' is not recognized as an internal or external command`；套件把*捕获到的 stderr* 报告为原因，而那是驱动自身无害的第一行，于是每个用例看起来都像编译器失败。实测两次，在两个不同的日子，这就是为什么本仓库里有两份文档在这件事上不一致：**不设置时，30 个用例失败并报 "could not be built"**——两次运行都是如此，而且它可复现——而**设置时**，计数在第一次运行是 5、在第二次是 **0**，因为那五个是别的事，并且此后已被修复（今天的套件是 **190 passed, 0 failed**，`STATUS.md` §2）。30 才是这一段存在的理由。`tools\refreeze.ps1` 正是为此设置它，`tools\build.ps1` 则一直都设置。

关于这棵树，还有两件事也都是实测而非猜测：

* **一次构建会留下一个游离的 `vctip.exe`**，而这个执行框架的 Windows 作业对象在它还活着时拒绝结束——一个跑了大量构建命令的会话可能死掉，需要重启宿主。驱动现在会在 C 编译器退出后立刻收割那个辅助进程，`tools\build.ps1` / `tools\smoke.ps1` 同样会清理。
* **`vm.exe build` 把它的中间 C 与目标文件写到系统临时目录下**（`%TEMP%\vela-build\<source name>`），所以装有一个程序的目录只多出一个新文件：可执行文件。C 是这个实现的代码生成细节，永远不是 Vela 程序的产物。
## 自举

引导总得从某处开始（Rust 从 OCaml 开始，Go 从 C 开始，Swift 从 C++ 开始），而 Vela 把各个阶段说得很明确：

| 阶段 | 它是什么 | 状态 |
|---|---|---|
| **0** | `vela/`——用 Python 写的词法分析器、解析器、检查器、C11 emitter | **在 stage 4 中删除。** 那个 Python 前端、它的 IDE 和它的差分套件都走了；`Get-ChildItem -Recurse -Include *.py` 什么都不返回。下面的一切都是替代它的东西——而曾经站在这里的那个句子（"working; 60 tests"）描述的是一个已经不存在的代码树 |
| **1** | `selfhost/vela.vel`——**用 Vela 写的词法分析器** | 可用；在这个仓库的每个文件上（包括它自己的源码）都与 stage 0 逐 token 相同 |
| **2** | 用 Vela 写的**解析器**，链接进 `selfhost/vm.vel` | 可用：在每个 `.vel` 文件上（包括它自己的源码）语法树输出都与 stage 0 相同 |
| **2.5** | 用 Vela 写的**解释器**（`selfhost/parts/resolve.vel`、`eval.vel`） | 可用：`vm.exe run file.vel` 执行程序时环路里没有 Python、没有 C 编译器，语料中每个程序打印的东西与编译版孪生完全一致 |
| **3** | 用 Vela 写的 C11 emitter（`selfhost/parts/emit.vel`）及它自己的驱动：`vm.exe emit-c file.vel`、`vm.exe build file.vel` | 可用：它写出的 C 在语料中每个程序上的含义都与 stage 0 写出的相同；它**编译它自己**（`vm.exe build selfhost/vm.vel` → `vm_by_vela.exe`，字节相同的 C，当它再次编译编译器时达到不动点）；而且 `vm.exe build` **自己驱动宿主的 C 编译器**，所以一个二进制就是整条工具链 |
| **4** | 用 Vela 写的**检查器**（`selfhost/parts/check.vel`），然后把 stage 0 **移出代码树** | **完成**：`selfhost/parts/check.vel` 承载了 stage 0 曾有过的每一个判定——最后一次差分运行把代码树里的每个程序*以及每个拒绝用例*都送给了两个检查器，得到 **70/70 一致，其中 42 个逐字节一致，0 个判定差异**。由此认证之后，测试 golden 被冻结，**Python 前端被删除**：`vela/`（词法分析器、解析器、检查器、emitter、CLI）、浏览器 IDE、Python 构建工具（`rebuild.py`、`bootstrap.py`、`link_selfhost.py`）、Python 测试套件，以及 `tools/legacy/` 里冻结的缩进式前端。`check` 会在每次 `emit-c`、`run` 和 `build` 之前运行，所以活下来的前端会拒绝它必须拒绝的东西，而不是为它发出 C |
| **5** | 外部函数：声明到 **C** 库的 `extern c` 绑定 | **可用，只是小形状**：声明*就是* C 原型（没有头文件，没有库名，声明的名字就是后端调用的符号），类型是标量（`int`/`i32`/`u8`/`float`/`bool`，以及用 `-> None` 表示 C 的 `void`），而 `pure` 是使用者对一个无副作用的 C 函数所作的陈述。检查器在它无法诚实描述这条边界的地方拒绝：在声明处（`'extern c' parameter 's' has type str`；数组和结构体也一样——这就是为什么没有外部方法），**也在调用点**：实参只能以参数所声明的种类穿过，唯一例外是装得下的 int 常量，所以 `abs(1.5)` 和 `abs(int_value)` 都在调用所在的那一行是 `vela: type error`，而不是一次 C 编译器会静默完成的转换；参数个数是解析器的判定，而解释器根本没有外部调用（`refuse-interp` 断言了这一点）。被有意排除在外的——`extern c "lib" { ... }` 块、`cstr`、`(T*, N)` 数组、`extern struct`、可变参数、回调、比 `u8` 更宽的无符号类型——是 `DESIGN.md` §9.5 |

关于*这棵树是如何*被构建出来的有两件事，因为它们是"Vela 要存在，必须有什么存在"的答案。下面的记录是一次**真实的运行，逐字保留，现在被贴上了标签**：它早于 LLVM 那部分工作，而那份工作加了一个部件（`emit_llvm.vel` 现在是第 7 个部件，所以链接后的编译器更大、它的不动点哈希也不同），这就是为什么它写的是 74115 个 token、741204 字节的不动点和 180 个用例。当前语料是 **190 个用例**，而当前的不动点哈希在 `tools\build.ps1` 自己的输出里，不在这里——在这个仓库的三份文档里，曾经有四个不同的哈希被标成"当前"，而这就是把一个"每当某个部件改动就会移动"的数字写进散文里会发生的事。

```
> powershell -File tools\build.ps1 -Suites
=== 1/6  bootstrap vm.exe from selfhost\build\vm.c          -> exit 0
=== 2/6  link the parts      tokens 74115 -> 74115           -> exit 0
=== 3/6  build the compiler with the compiler                -> exit 0
=== 4/6  build the standalone lexer                          -> exit 0
=== 5/6  fixpoint: does generation 2 write what generation 1 wrote?
    seed  (selfhost\build\vm.c) : 669CCA3EA85504DA  741204 bytes
    gen 1 (selfhost\vm.c)       : 669CCA3EA85504DA  741204 bytes
    gen 2 (emitted by itself)   : 669CCA3EA85504DA  741204 bytes
    byte-identical: the compiler reproduces itself
=== 6/6  the test suite      180 passed, 0 failed (of 180)   -> exit 0
RESULT: ok
```

`tools/build.ps1` 是寻常路径，而且它是*唯一*的路径：第 1 步是用 `cl.exe` 编译 `selfhost/build/vm.c`，那是 Vela 为编译器发出的 C11——一个 C 文件和 `runtime/vela_runtime.h` 就是地板。从这里往后，一切由编译器自己完成，包括链接它自己的源码：链接器（`tools/link_selfhost.vel`）是一个 Vela 程序，先被构建、再被运行，而第 2 步会检查产出的东西仍是检查器接受的程序——一个吞掉某个部件或者把某个部件拼接两次的链接器，会在不动点为此花掉一分钟之前就被抓住。

第 5 步的不动点就是整件事的意义所在：第 1 代写出 `selfhost/vm.vel` 的 C，第 2 代由它构建，而第 2 代再次写出同一个文件——三者同一个 SHA-256。字节数和前十六个十六进制数字由该步骤自己打印（`tools\build.ps1` §5，`seed`/`gen 1`/`gen 2` 三行），并且有意不在此重述：抄进文档里的数字会在某个部件下次改动时过期，而这份文档已经为此付过账。一个对自己的源码微妙地出错的后端会在这里漂移，而不是通过。

可以测到的是，今天——这里的每一条都是 `tests/run_tests.vel` 里的一个用例，而套件是绿的。逐用例的计数不在此引用：`tests/cases.txt` 是清单，套件打印自己的计数，所以引用一个每次语料变化都会过期的数字，恰恰是本节存在要避免的那种句子。

```
$ tests\run_tests.exe
  ok    recursion_fib            (compiled + interpreted)
  ok    string_escapes           (compiled + interpreted)
  ok    truthiness               (refused: the diagnostic is byte-exact)
  ok    extern_c_probe           (compiled)
  ok    extern_c_probe_interp    (refused by the interpreter, as promised)
  ok    vm                       (lex/parse/emit-c digests)
  ok    selfhost_fixpoint        (generation 2 reproduces the C byte for byte)

$ selfhost/build/vm.exe run tests/build/recursion_fib.vel
986
```

解释器是让 Vela 在真正要紧的意义上自举的东西：它执行一个含义由语言、而不是由某个 C 编译器固定的程序。它是一个树遍历器，所以比编译形式慢——在一个 MSVC 无法向量化的千万次迭代循环上（每次迭代有一个依赖数据的分支），编译后的二进制要 9.1 ms，解释器要 695 ms，也就是约 76 倍，输出完全相同。

Vela 0.1 没有模块系统（`import` 是保留字），所以一个多文件的 Vela 程序是构建期的拼接：`tools/link_selfhost.vel` 把词法分析器的前半部分（`selfhost/vela.vel`，直到它的驱动小节）与 `selfhost/parts/{vm_state,parser,resolve,check,eval,emit,dump,vm_main}.vel` 拼成 `selfhost/vm.vel`，那才是交给编译器的东西。这是 C 用 `#include` 玩的老把戏，而它会在 Vela 长出模块的那一天消失。链接器是用 Vela 写的，并且在运行之前先被*构建*，因为解释器的 `str` 是一张固定表里的句柄，而一个把上千次拼接组装成 338 KB 文件的程序会把它耗尽——这是解释器值表示的限制，不是编译器的。

```bat
selfhost\build\vm.exe build tools\link_selfhost.vel  :: build the linker
tools\link_selfhost.exe                              :: regenerate selfhost/vm.vel
tools\link_selfhost.exe --check                      :: is it up to date?
selfhost\build\vm.exe build selfhost\vm.vel          :: the compiler compiles itself
selfhost\build\vm.exe parse bench\matmul.vel         :: the tree, from Vela
selfhost\build\vm.exe lex   selfhost\vm.vel          :: the tokens (74115 of them)
selfhost\build\vm.exe run   tests\build\recursion_fib.vel
```

**编译器编译它自己。** C11 后端完整到足以让 `selfhost/vm.vel` 里的程序成为它能翻译的程序，而构建脚本检查其后果——它构建出的编译器写出的 C，就是构建它所用的那份 C：

```bat
selfhost\build\vm.exe build selfhost\vm.vel   :: -> selfhost\vm.c + selfhost\vm.exe
selfhost\vm.exe emit-c selfhost\vm.vel        :: must equal selfhost\vm.c, byte for byte
```

这不是假设。第一次自举构建编译、运行，并在每个该是换行的地方打印出 `` \ ``：emitter 写出的是字符串字面量的**原始源文本**（转义仍是转义），却声称自己给出的是它**解码后取值**的长度，于是每个带转义的字面量都丢掉了最后一个字节。这两件事必须是同一件事——值，以及它字节的计数——而 `tests/cases.txt` 里的 `string_escapes` 会在它们再次分离时失败。

每一个自举阶段都只用这门语言自己的安全表面写成：没有指针，没有 unsafe，没有检查器会拒绝的东西。编译器是**开着**溢出检查构建的——那个曾经关掉它们的开关（`--fast-int`）在自举驱动里不存在，而它自己发出的 C 就是证据：2215 处 `vela_add_range`、1751 处 `vela_mul_range` 和 952 处 `vela_bounds_check` 调用。Stage 0 曾有这样的开关，理由是它今天已不适用：*解释器*自己实现被解释程序的检查，就像 `runtime/vela_runtime.h` 为编译后的代码所做的那样，而这要求宿主自己的 `+`/`-`/`*` 是有定义且全函数的，而不是因为自己源码的某一行让编译器 panic 停下。

## 目录布局

```
runtime/         vela_runtime.h — arena, checked arithmetic, strings, host I/O
selfhost/        vela.vel (lexer in Vela), parts/ (state, parser, resolver,
                 checker, evaluator, emitter, dumper, driver), vm.vel (linked)
selfhost/build/  vm.c (the seed the compiler bootstraps from), vm.exe, vela.exe,
                 vm_by_vela.exe (the compiler compiled by the compiler)
examples/        hello.vel
bench/           three benchmarks, their C++ twins, RESULTS.md
tests/           run_tests.vel (the suite, in Vela), cases.txt (the corpus),
                 golden/ (what each case must print, refuse with, or produce),
                 build/ (the corpus sources and the build outputs)
tools/           build.ps1 (bootstrap + rebuild + fixpoint + suite),
                 link_selfhost.vel (the linker, in Vela)
```

`SPEC.md` 是语言定义；`DESIGN.md` 解释它为什么被造成这样。

**已经不在这里的东西，以及为什么。** Stage 0——用 Python 写的 `vela/`：词法分析器、解析器、检查器、C11 emitter 和命令行——在 stage 4 被删除，一起走的还有浏览器 IDE（`vela/ide/`）、Python 构建工具（`rebuild.py`、`bootstrap.py`、`link_selfhost.py`、`build_selfhost_by_vela.py`、格式化器）、七个 Python 差分套件，以及 `tools/legacy/` 里冻结的基于缩进的前端。它们被删除，是在最后一件只有它们能做的事做完*之后*：认证测试 golden。当时两个前端都还在树里，语料中每个程序都被送给两者，而冻结 `tests/golden/` 的那一次运行结果是 **70/70 一致，其中 42 个逐字节一致，0 个判定差异**。从那时起，golden 与不动点就是证据，而这个仓库里没有一条命令需要一个它本来不必写的解释器。

