# ide-demo — 用 IntelliJ IDEA 试 Vela 插件的小项目

A small, real project for trying the Vela plugin in IntelliJ IDEA. 每个文件都很小，
但都是真程序，而且是**跑过、量过**的：下面每一段输出都是从这台机器上真实的
`vm.exe build` + 真实运行的结果里逐字节抄下来的，不是"应该是这样"。

本目录**故意**放在 Vela 仓库里面（`C:\Users\lu\Downloads\vela\ide-demo\`），原因见
下面「为什么放在仓库里面」。它和 `vela\tests\` 无关，不依赖那里的任何东西。

---

## 目录内容 / What is in here

| 文件 | 作用 |
|---|---|
| `hello.vel` | 最小的合法程序，打印三行。用来确认"从 IDEA 里能跑起来"。 |
| `tour.vel` | 演示编辑功能的文件：struct + 方法、带参数的函数、函数调用函数、一个 `mut` 局部变量、一个合法的 `parallel for`。 |
| `broken.vel` | **故意写错**的一个文件：故意的类型错误，插件会在编辑器里画出诊断。 |
| `expected\hello.txt` | `hello.vel` 必须打印的确切 stdout（就是真机输出本身，逐字节）。 |
| `expected\tour.txt` | `tour.vel` 必须打印的确切 stdout（同上）。 |
| `README.md` | 本文件。 |

运行之后目录里会多出 `hello.c` / `hello.exe` / `hello.obj` / `tour.c` / `tour.exe` /
`tour.obj`：那是编译产物，正常现象。`vm.exe build` 的规则就是"把 `.c` 和 `.exe`
写在源文件旁边"。

---

## 为什么放在仓库里面 / Why inside the checkout

插件不保存编译器路径也能工作，因为它从**项目目录往上走**找编译器
（`VelaCompiler.locate`：从项目目录开始，最多往上 6 层，找
`<目录>\selfhost\build\vm.exe`）。

从 `vela\ide-demo` 往上走**一层**就是 `vela\`，那里有 `selfhost\build\vm.exe`，
所以把 `vela\ide-demo` 当 IDEA 项目打开，插件不用任何配置就能找到编译器。
这一走法我在本机实测过（照插件源码里的循环原样走一遍）：

```
项目目录: C:\Users\lu\Downloads\vela\ide-demo

locate()   第 0 跳: C:\Users\lu\Downloads\vela\ide-demo\selfhost\build\vm.exe  -> 不存在
locate()   第 1 跳: C:\Users\lu\Downloads\vela\selfhost\build\vm.exe           -> 存在
           -> 编译器 = C:\Users\lu\Downloads\vela\selfhost\build\vm.exe（往上 1 层）

runtimeDir()  从编译器自己的目录往上最多 4 层找 <目录>\runtime\vela_runtime.h
           第 0 跳: ...\vela\selfhost\build\runtime\vela_runtime.h  -> 不存在
           第 1 跳: ...\vela\selfhost\runtime\vela_runtime.h        -> 不存在
           第 2 跳: ...\vela\runtime\vela_runtime.h                 -> 存在
           -> runtimeDir = C:\Users\lu\Downloads\vela\runtime
           -> velaRoot   = C:\Users\lu\Downloads\vela
```

（`locate()` 两条规则用的是正斜杠 `selfhost/build/vm.exe`，Windows 上两种写法等价。）

---

## 怎么打开、怎么运行 / How to open it and run a file

1. **装插件**（已经装过就跳过）：`Settings → Plugins → ⚙ → Install Plugin from
   Disk…`，选 `C:\Users\lu\Downloads\vela\idea-plugin\dist\vela-idea-plugin-0.1.1.zip`，
   按提示重启 IDEA。
2. **打开项目**：`File → Open`，选**目录本身**
   `C:\Users\lu\Downloads\vela\ide-demo`（不是选里面的某个文件）→ 确认
   `Trust Project`。IDEA 把它当成一个项目打开，而不是"往当前项目里加一个目录"。
3. **等索引**：第一次打开时右下角会跑 indexing，几秒钟；等它结束再做别的。
   语法高亮、Structure、补全都靠它。
4. **编译器路径**：`Settings → Languages & Frameworks → Vela` 留空就行 —— 插件会按
   上面那条规则自己找到 `vm.exe`。（也可以手填
   `C:\Users\lu\Downloads\vela\selfhost\build\vm.exe`，效果一样。）
5. **运行**（三种任选其一，都是同一个动作）：
   * 在编辑器里右键 `hello.vel` → **Run 'hello.vel'**；
   * 点行号槽（gutter）里的**绿色三角**；
   * 光标在该文件里，按 **Ctrl+Shift+F10**。
   插件会自己建一个 "Vela" 运行配置：先 `vm.exe build <文件>`，再跑生成出来的
   `.exe`，两步的输出都进 IDEA 的 Run 控制台。运行配置也可以在
   `Run → Edit Configurations…` 里存下来、改名、重复运行。

---

## 每个文件演示什么 / What each file demonstrates

### `hello.vel`
最小可运行程序：`def main() -> None` 加三条 `print`。没有数组、没有循环。
用来看"Run 这一整条路通不通"。

### `tour.vel`
一个文件里集中放齐了要试的东西，一眼能对上：

| 位置 | 演示的功能 |
|---|---|
| `struct Point` + 两个方法 `manhattan` / `moved` | Structure 视图里出现结构体和方法；`.manhattan()` 这种调用有语义高亮 |
| `def scale(v: int, factor: int)` 等带参数的函数 | 光标放进 `scale(` 里按 `Ctrl+P` 有参数信息；悬停（`Ctrl+Q`）有说明 |
| `def moved(self: Point, dx: int, dy: int) -> Point` | 两个参数 + 返回结构体（用 `Point(x, y)` 构造，构造就是一次"调用"） |
| `describe` 里调用 `scale(...)` | **Go to declaration 有目标**：在 `describe` 里 `Ctrl+Click` `scale` → 跳到 `def scale` |
| `mut n: int = 8` | 一个可变局部变量，没和任何东西重名 |
| `mut a` / `mut out` / `parallel for` | 一个**合法**的 `parallel for`（`SPEC.md` §7）：`out[i] = a[i] * 2`，被写的 `out` 是本函数的 `mut` 局部数组，只用一个循环下标索引，被读的 `a` 是**另一个**数组 |

`Ctrl+Click` 值得点几下，能跳到目标的有：`describe` 里的 `scale`、`Point(3, -4)`
里的 `Point`、`p.manhattan()` 里的 `manhattan`、`p.moved(...)` 里的 `moved`、
`p.x` / `q.x` 里的 `x`。

有一类**故意**没有目标：像 `range(0, n)` 里的 `n`、`out[i]` 里的 `out` 这种"光秃秃
读一个局部名"，插件不提供跳转目标（`VelaGotoDeclaration.kt` 里写明了：局部变量只
作为接收者 `p.x` 这种形式解析，单独一个名字不猜）。这是设计，不是坏了。

### `broken.vel`
顶部中英文都写了"这个文件是故意写错的"。它演示的是**输入时就能看到的实时诊断**，
不是运行。

### `expected\`
两个 `.txt` 是可运行文件的**确切 stdout**，是从真实运行里逐字节复制过来的
（不是手打的），所以可以直接拿去比对，不必相信这份 README。注意它们是 Windows
原样字节，行尾是 **CRLF**，末尾有换行。

---

## 确切的控制台输出 / The exact expected output

下面每段都是逐字节真机输出（代码块里行尾显示为换行，实际是 CRLF）。

### `hello.vel` — 运行 `hello.exe`

```
hello from Vela
3 * 14 = 42
goodbye
```

39 字节，SHA256 `FD3FA73FF967EDE73152D341738CA70B3598C33177D49CB75D334DD7C6955567`
（`expected\hello.txt` 的哈希与它完全相同）。
解释器 `vm.exe run ide-demo\hello.vel` 打印出**同样**的 39 字节。

### `tour.vel` — 运行 `tour.exe`

```
p = 3 -4
p.manhattan() = 7
q = 13 -3
describe(q) = 173
sum(out) = 72
out[7] = 16
```

87 字节，SHA256 `3D766FE028E4B8790C7A8FE2B851DC3BD8EC46B2E7D44AB2516908929EA093FF`
（`expected\tour.txt` 的哈希与它完全相同）。

最后两行就是那个 `parallel for` 的结果：`sum(out) = 2×(1+2+…+8) = 72`，
`out[7] = 8×2 = 16`。

`tour.exe` 是**带 OpenMP** 编出来的（`vm.exe build` 的原话是
`built ide-demo\tour.exe  (with OpenMP)`）。三种线程数下输出逐字节相同，而且和
解释器也相同 —— 四个文件的 SHA256 一模一样：

| 怎么跑 | 输出 SHA256 |
|---|---|
| `OMP_NUM_THREADS=1` 跑 `tour.exe` | `3D766FE0…93FF` |
| `OMP_NUM_THREADS=8` 跑 `tour.exe` | `3D766FE0…93FF` |
| 不设 `OMP_NUM_THREADS`（默认）跑 `tour.exe` | `3D766FE0…93FF` |
| `vm.exe run ide-demo\tour.vel`（解释器） | `3D766FE0…93FF` |

### `broken.vel` — 预期就是"被拒绝"

在编辑器里打开 `broken.vel`，应该看到**第 11 行**（`if 1 {` 那一行）有一条红色
波浪线，悬停的说明是：

```
'if' condition must be bool, got int
```

（这是插件在后台跑 `vm.exe check` 的结果：`kind` 是 `type error`，行号 11 是编译器
报的，不是插件猜的。整行标红，因为编译器只给行号不给列。）

在这个文件上按 Run（或 Ctrl+Shift+F10），程序**不会**跑起来。Run 控制台里会是
（构建这步的 stderr，退出码 **2**）：

```
vela: type error: 'if' condition must be bool, got int
  at ide-demo\broken.vel:11

vela: panic: the program was refused

vela: panic: build: this compiler could not write the C for that file
```

前两行就是那条诊断；后两行 `panic:` 是驱动在说"我已经拒绝了"，插件**不**把它们当成
额外的问题（`VelaCompiler.parse` 里明确跳过 `panic`），所以编辑器里只有**一条**波浪
线，不是三条。把 `if 1` 改成 `if True`（或 `if n > 0`）就能跑起来，这正是拿它做
"改一下就通"练习的用法。

---

## 关于 Debug：故意没有 / Debug is deliberately not offered

**这个插件现在没有调试器，而且不是"能点但没用"，是 IDEA 里根本不会给你 Debug 这个
选项。** 原因有两层，都是实测的：

1. **插件这边**：运行配置 `VelaRunConfiguration` 实现了平台接口
   `RunConfigurationWithSuppressedDefaultDebugAction`。平台的
   `DapProgramRunner.canRun` 正是检查这个接口，所以它不会接管 Vela 的运行配置，
   也没有别的 runner 会接管 —— Debug 就不出现。插件源码里的理由写得很清楚：一个
   看起来能用的 Debug，其实只是"贴了调试器标签的 Run"，用户要到最需要断点的那一刻
   才会发现真相；不如一开始就说没有。
2. **语言这边**：编译器源码里有一个 `debug` 模式（同一个解释器，停下来，从目录里读
   命令），但**现在树里的这个 `vm.exe` 比那部分工作旧**，实测：

   ```
   > selfhost\build\vm.exe debug ide-demo\hello.vel
   usage: vela <lex|count|parse|nodes|emit-c|run|check|build> <file.vel>
     ...
   vela: panic: unknown mode            [退出码 2]
   ```

   所以它答的是"未知模式"。要让它答，得先用 `tools\build.ps1` 把编译器重建一遍。

结论：**断点、单步、看帧都还没有。** 现在能"调试"的手段是 `print` / `emit_int`
这类打印，以及在编辑器里靠实时诊断和 `vm.exe check` 的判断。

---

## 一个已知的坑：编译器的 include 路径 / The one known trap

`vm.exe build` 的 include 目录默认是**相对路径** `runtime`，所以它要求**当前工作
目录就是仓库根目录**。实测（从别的目录跑，即使源文件给的是绝对路径）：

```
> cd C:\Users\lu\Downloads\_demo
> C:\Users\lu\Downloads\vela\selfhost\build\vm.exe build C:\...\ide-demo\hello.vel
hello.c
...\ide-demo\hello.c(5): fatal error C1083: 无法打开包括文件: "vela_runtime.h": No such file or directory
vela: build: the C compiler refused ...
                                                   [退出码 2]
```

**在 IDEA 里 Run 不受影响**：插件把 `vm.exe` 的工作目录设成 vela 根目录
（`VelaCompiler.velaRoot`），并且额外把 runtime 目录作为**第 4 个参数**传给 `build`。
这个第 4 参数的路子我也单独测过 —— 从别的目录跑，只要给上 runtime 目录就成功：

```
> cd C:\Users\lu\Downloads\_demo
> C:\...\vm.exe build C:\...\ide-demo\hello.vel C:\Users\lu\Downloads\vela\runtime
built C:\Users\lu\Downloads\vela\ide-demo\hello.exe      [退出码 0]
```

（顺带量到一件事：`VELA_RUNTIME` 环境变量在这版 `vm.exe` 里**不**起作用，源码里有、
二进制里没有 —— 所以手工跑的时候用第 4 个参数，或者干脆先 `cd` 到根目录。）

如果你想自己在命令行里重跑一遍，**先 `cd C:\Users\lu\Downloads\vela`**：

```powershell
cd C:\Users\lu\Downloads\vela
.\selfhost\build\vm.exe build ide-demo\hello.vel      # -> built ide-demo\hello.exe
.\ide-demo\hello.exe                                  # -> hello from Vela / 3 * 14 = 42 / goodbye
.\selfhost\build\vm.exe build ide-demo\tour.vel       # -> built ide-demo\tour.exe  (with OpenMP)
.\ide-demo\tour.exe
.\selfhost\build\vm.exe run ide-demo\tour.vel         # 解释器，输出应完全相同
.\selfhost\build\vm.exe check ide-demo\broken.vel     # -> 上面那条诊断，退出码 2
```

---

## 这些数字是怎么来的 / How these numbers were measured

* 编译器：`C:\Users\lu\Downloads\vela\selfhost\build\vm.exe`，508928 字节，
  SHA256 `D35B330E3A72D00AFA4E84FBEB4F91A31F77971674E017FEA417D184BB990D6`。
* 所有 `build` 都在仓库根目录执行，源文件写作 `ide-demo\<名字>.vel`；
  `hello.exe`、`tour.exe` 的退出码都是 **0**，`check broken.vel` 是 **2**。
* `expected\*.txt` 是用 shell 重定向抓下来的**原始 stdout 字节**，然后原样复制进
  `expected\`，再对比 SHA256 确认一致（不是照着屏幕重打的）。
* 四个 `tour` 输出（解释器、默认、1 线程、8 线程）SHA256 全等，就是
  `3D766FE028E4B8790C7A8FE2B851DC3BD8EC46B2E7D44AB2516908929EA093FF`。
* 编译器往上找的走法（`locate()` / `runtimeDir()`）是按
  `idea-plugin\src\main\kotlin\dev\vela\plugin\VelaCompiler.kt` 里的循环原样重走
  一遍量出来的，输出见上面「为什么放在仓库里面」。

一个诚实的边界：这里证明的是"程序真能编译、真能跑、输出就是这个"，以及"插件在本机
应该怎么找到编译器和 runtime"。IDEA **界面里**的东西（菜单项是否出现、波浪线画在
第几行、补全弹窗）没法在无头环境里证明，插件自己的 README 也这么写。
