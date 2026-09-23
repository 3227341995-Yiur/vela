# Vela 语言，版本 0.1

[English](SPEC.md) | **简体中文**

<!--
源文件 : SPEC.md
源文件字节 : 33902
源文件 SHA256 : db7b7cb8fcb97b47646e8e58b45ef9540d9b61e4268f2bf21bb5441483bc075e
翻译日期 : 2026-09-24
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

Vela 是一门编译型的系统语言。它读起来像 Python，但语法里没有任何一条规则依赖空白，
也没有任何构造是可选的：如果你写了含糊的东西，编译器会拒绝它并告诉你原因。

本文档对 `selfhost/` 里的自举编译器是规范性的（normative），而那是本代码树里唯一的
实现——`vm.exe check` 是逐条规则的权威，过去放在 `vela/` 里的 Python 前端已在阶段 4
删除（`DESIGN.md` §7）。凡是编译器尚未实现某条已陈述的规则的地方，本文档都明确说出来，
其中 §6.1 和 §3.1 是最要紧的两处。

---

## 1. 词法结构

### 1.1 行与语句

* 一条**语句**在换行处结束。
* 两条语句不能共用一行：`mut a: int = 1 mut b: int = 2` 是语法错误。
  （Vela 从不猜语句在哪里结束。）
* 在 `(` 和 `[` **内部，换行是无意义的**，所以一个长调用或数组字面量可以铺开成几行。
  在 `{ }` 内部换行是*有意义的*，因为在一个块里换行分隔语句。
* 行尾紧跟换行的 `\` 也起续行作用。
* **缩进只是排版，永远不是语法。** 制表符和空格是等价的空白；词法器不保存缩进栈，
  也不存在 INDENT 或 DEDENT 记号。
* 文件最开头处的**字节序标记（byte-order mark，`U+FEFF`）被忽略**：它是写出该文件的
  工具留下的产物（PowerShell 的 `Set-Content` 和若干编辑器都会加一个），不属于程序的一部分。
  编译器自己从不写它。

### 1.2 注释

用 `#` 到行尾。没有块注释（这是一个决定：嵌套块注释在 C 系语言里是已被记录的 bug 来源）。

### 1.3 标识符与关键字

标识符是 `[A-Za-z_][A-Za-z0-9_]*`。关键字：

```
def return if elif else while for in range break continue pass
and or not True False None mut struct parallel pure
enum match                     # 带载荷的 enum 与 `match`，见 §13
extern                         # `extern c`, implemented in 0.1 — see §12
```

`enum` 与 `match` 是 §13 落地时成为关键字的。在那之前这两个拼写都是普通标识符，
而另一种做法——把语句位置上的名字识别成 `match`——必须在 `match = 1`、`match(x)`
与 `match x { ... }` 之间去猜。现在把其中任何一个当名字用的程序都会被拒绝，而且指名道姓，
这是决定，不是意外。

`elif` 会被词法分析，但**被语法分析器拒绝**：Vela 写作 `else if`，诊断信息就是这么说的。

`import`、`from` 和 `as` 在 0.1 里**不是**关键字：它们是普通标识符
（`mut import: int = 1` 是合法声明），没有任何东西拒绝它们。模块系统会占用它们，
所以它们是由文档、而非由词法器保留的——某天模块系统到来时，把这些词当作名字使用的
程序需要改名。

### 1.4 数字

* 十进制，可用 `_` 分隔：`1_000_000`
* 十六进制 `0x1F`、二进制 `0b1010`、八进制 `0o17`
* 浮点数：`1.0`、`2.5e3`、`1e-9`。以 `.5` 开头是语法错误。
* 整数字面量必须能放进有符号 64 位。

### 1.5 字符串

* `"..."` 或 `'...'`，仅限单行——一个字面量不能包含裸换行。
* 转义：`\n \t \r \0 \\ \" \' \a \b \f \v \xNN \uNNNN`。
* 字面量的**值**是解码后的文本，而不是被写下来的那段文本：`"\n"` 是一个换行字节，
  `len("\n")` 是 1。`unescape` 显式地做这种解码，而一个后端如果一边输出源文本、
  一边声称解码后的长度，那它输出的就是错误的字符串*和*错误的长度。
  （`DESIGN.md` §7.3——这正是第一次自举构建弄错的地方。）
* **相邻字面量被拒绝**：`"a" "b"` 是错误，因为在 Python 里这是静默拼接，在 C 里则不是。
* 0.1 里没有字符串拼接运算符，也没有切片。

### 1.6 运算符

```
( ) [ ] { } , : . -> = == != < <= > >=
+ - * / // % ** & | ^ ~ << >>
+= -= *= /= //= %= &= |= ^= <<= >>= **=
```

---

## 2. 块

块由花括号界定，且总是必需的：

```vela
if n < 2 {
    return n
} else if n == 2 {
    return 1
} else {
    return fib(n - 1) + fib(n - 2)
}
```

* 一个块可以为空（`{}`）；`pass` 也被接受，意思一样。
* `if`、`while`、`for` 和 `def` 后面必须跟 `{`；写 `:` 是语法错误，消息是
  *"expected '{' to open a block"*。
* `else if` 不用额外花括号即可继续这条链。以 `else` 继续的 `}` 必须与 `else` 在同一行
  （这是换行唯一要紧的地方——另一种写法在块内部是有歧义的）。
* 每个 `{` 都需要一个 `}`；词法器会在文件末尾报告
  *"missing '}': a block is never closed"*。

---

## 3. 类型

| 类型 | 含义 |
|---|---|
| `int` | 有符号 64 位 |
| `i32` | 有符号 32 位 |
| `u8` | 无符号 8 位 |
| `float` | IEEE-754 双精度 |
| `bool` | `True` / `False` |
| `str` | 字节串：指针 + 长度，没有 NUL，没有可越界的终止符 |
| `Array[T, N]` | `N` 个 `T` 类型元素的定长数组 |
| `Name` | 一个结构体类型 |
| `None` | 空类型，用作函数返回类型 |

### 3.1 转换

* **规则能进行比较的类型之间没有隐式转换。** `1 + 2.5` 是错误；要写
  `to_float(1) + 2.5`。运算符表会比较两个操作数，所以那里位宽也不混用——
  `x: i32` 加 `y: int` 会得到 `operator '+' mixes i32 and int`——而外部调用会把它的
  实参与其参数所声明的种类进行比较（§12）。
* 在这两处，唯一的例外都是：当一个**整数字面量**的值放得下时，它会适配到另一种整数
  类型，所以 `comp[i] == 0` 对 `u8` 数组也能用，`abs(-7)` 对 `i32` 参数也能用。
* `to_float(int) -> float` 和 `to_int(float) -> int`。
* **窄化绑定会被检查，而这个检查有两半。** 绑定正是那种「没有规则比较两个类型、位宽却
  承重」的地方，而两个前端过去给出的答案不同——`mut x: u8 = 300` 在 `vm.exe run` 下打印
  `300`，在编译出来的孪生体里是 `44`。那个分歧已经被删掉，取而代之的是：
  * **放不下所声明宽度的字面量，在写它的那一行就被拒绝**，由检查器给出，而 `run` 报告
    同一条拒绝而不是开始运行：`vela: type error: 256 does not fit in u8`、
    `vela: type error: 3000000000 does not fit in i32`。放得下的字面量不受影响——
    `mut x: u8 = 200` 与 `mut x: i32 = 7` 都被接受。
  * **值要到运行期才知道的窄化，就在运行期检查。** 有了 `mut big: int = 5000000000`，
    绑定 `mut x: i32 = big` 会通过检查器，然后程序以
    `vela: panic: 5000000000 does not fit in i32` 停下——解释器与编译产物给出**同一句
    消息、同一行**。一个程序按运行方式有两种含义，正是这条规则要删掉的那种 bug，所以三条
    路径只给一个答案。
  * **这条规则同样覆盖经过元素或字段的写入。** 对 `a[i] = big` 与 `p.x = big`，检查器
    接受，解释器与编译产物都以上面那句、在**同一行**停下——2026-09-24 实测；而在此之前，
    解释器对前一种会打印 `705032704`、对后一种会保留 `5000000000`，编译产物却已经停下。
    这一条以前标着「未闭」，现在收掉是因为**两条路已经一致**，不是因为改了文字。仍有一处
    差别且不属于这条规则：解释器在任何 panic 之后都会追加自己的
    `vela: panic: the interpreted program stopped` 那一行，所以两边的 stderr 即使消息与
    行号一致也不是逐字节相同。
  * **加宽不是窄化，不加检查。** `mut n: int = comp[i]`——从 `Array[u8, N]` 里读出的一个
    `u8` 元素——会被接受，并直接编译成加宽本身（`int64_t vl_n = ... vl_a[0LL]`）。
* 普通 Vela 调用仍然不做类型检查（对 `def f(x: int)` 而言 `f("s")` 被接受，错误稍后在 C
  编译器的话里浮现），这恰恰是**外部**调用为什么是小心处理的那一个：凡是规则比较两个类型
  的地方，位宽都是承重的——运算符，或者 C 边界（§12）——而普通调用不是这样的地方。
* **另有一条与上述全部无关、且未改变的位宽拒绝。** `vm.exe run` 接受一个 `u8` *标量*跑进
  `int` 绑定（编译出来的孪生体也同意），但会拒绝任何含有 `Array[u8]` 的程序——只要声明
  一个就够了，而从其中读出一个 `u8` 正是它的消息点名的情形：
  `vela: interpreter: Array[u8] needs an integer width conversion (SPEC 3.1)`。
  它陈述这项拒绝，而不是去猜那个加宽，这也是 `bench/sieve.vel` 只有编译结果、没有解释
  结果的原因（`DESIGN.md` §7.4）。
* 数组不退化（decay）、不改变大小，其长度是类型的一部分。

### 3.2 声明

```vela
x: int = 3          # immutable binding, annotated
mut y: int = 0      # mutable binding
mut buf: Array[float, 1024]    # zero-filled array; scalars need an initialiser
```

* 绑定**默认不可变**。对非 `mut` 绑定赋值是*安全性错误*，而不是静默重绑定——这是 Vela
  删掉的最常见的一类 Python bug。
* 标量不得没有初始化器就声明；数组可以，并被零填充（arena 内存总是先置零的）。
* **绑定必须把类型写出来，不从初始化器推断。** `mut a = 1` 被拒绝——
  `vela: type error: binding 'a' has no type annotation: write the type after the
  name, as in `mut a: int = 1``——无注解这种写法不是一个推迟到以后版本的特性，而是
  一条说「不」的规则。这条规则值得在这里占一句，是因为它原先所在的位置上是什么：按
  2026-09-24 的实测，检查器为缺失的注解去读了类型池的 `-1` 下标，`type_kind` 对负下标会
  提前返回而 `ty_len` 不会，取回来的那个值被当成绑定的 packed kind 并流进一个按长度驱动的
  循环。`vm.exe check` 在那四行上**永远不返回**——是挂死，不是错答案，而且 2026-09-20 的
  二进制同样挂，所以它早于结构与转换那一批工作。
* 比声明长度短的数组字面量会被补零；`mut a: Array[float, 4] = [0.0]` 合法。
* 结构体字段和参数声明为 `name: T`；参数前的 `mut` 意味着被调方可以通过它写入——
  **而本实现对标量并不守这个承诺。** 它实际做的是下面这些，2026-09-20 在
  `selfhost\build\vm.exe`（634368 字节）上实测，解释器和 C 后端打印出的字节完全一致、
  退出码 0、没有任何诊断：

  | 传给 `mut` 参数的东西 | 会写回吗？ | 实测 |
  |---|---|---|
  | 一个标量（`int`） | **不会**——被调方拿到的是一个副本 | `scalar after bump: 1`（本文档承诺 2） |
  | 一个数组*元素*（`a[0]` 传入 `mut int`） | **不会**——同样是那个副本 | `array element after bump: 5`（承诺 6） |
  | 一个数组（`mut a: Array[int, 2]`） | 会——数组本来就是一个指针 | `array parameter after fill: 42` |
  | 一个结构体（`mut b: Box`、`mut self: Box`） | 会 | `struct field after free function: 2` |

  两个探针是 `tests/probes/mut_scalar_parameter.vel` 和
  `tests/probes/mut_struct_parameter.vel`；`DESIGN.md` §7.5 在同一张表旁边带着源码自己
  关于这个缺口的说明。这一段写在规格里、而不是只写在设计笔记里，只为一个原因：
  **一个被丢掉的标量写入是一个退出码为 0 的错误数字**，所以一个相信上面那句话的读者会
  写出一个静默错误、且从编译器得不到任何帮助的程序。

---

## 4. 语句

| 形式 | 说明 |
|---|---|
| `def f(a: T, mut b: U) -> R { ... }` | 每个参数和返回类型都要标注，总是如此 |
| `pure def f(...) -> R { ... }` | 承诺无副作用；从 `parallel for` 调用它时需要 |
| `extern c def f(x: T) -> R` / `extern c pure def ...` | 一个 C 函数，只声明：没有函数体，仅标量类型，见 §12 |
| `struct S { field: T ... def m(self: S, ...) -> R { ... } }` | 带方法的值类型；**字段不得是数组** |
| `enum E { V1(f: T) V2 }` | 名义和类型（nominal sum type）；见 §13 |
| `match e { V1(x) { ... } else { ... } }` | 是语句，不是表达式；见 §13 |
| `x: T = e` / `mut x: T = e` / `x = e` / `x += e` | 声明、重新赋值、复合赋值 |
| `a[i] = e`、`s.field = e` | 下标赋值和字段赋值 |
| `if c { } else if c { } else { }` | `c` 必须是 `bool` |
| `while c { }` | `c` 必须是 `bool` |
| `for i in range(a, b)` / `range(a, b, step)` | 0.1 里唯一可迭代的形式 |
| `parallel for i in range(...) { }` | 见 §7 |
| `return e` / `break` / `continue` / `pass` | `break`/`continue` 只能在循环内 |

### 4.1 被拒绝的语句

* 表达式语句必须是一个**调用**。单独一行的 `x + 1` 是错误：
  *"this expression statement has no effect"*。这是一个非常常见的运行期 bug 的编译期版本。
* 链式比较 `0 < x < 10` 被拒绝（在 Python 里它意味着令人意外的东西，在 C 里它意味着
  另一种东西）。
* 元组不存在；`(1, 2)` 被拒绝，消息里就是这么说的。
* 0.1 里没有切片。
* 嵌套函数和闭包不存在；所以一个块的声明只在该块内可见。

---

## 5. 表达式

优先级，从最松到最紧：

```
or
and
not
==  !=  <  <=  >  >=          (non-associative: no chaining)
|
^
&
<<  >>
+  -
*  /  //  %
unary -  ~
**
calls f(x), indexing a[i], field access s.f
```

* `and` / `or` 短路，并产出 `bool`（而不是值）。
* `/` 是浮点除法，要求两个浮点数；`//` 和 `%` 是整数运算，并且对负数使用
  **Python 的向下取整语义**，而不是 C 的截断：`-7 // 2 == -4`，`-7 % 2 == 1`。
* `**` 对整数是整数幂，对浮点数是 `pow(float, float)`。

---

## 6. 安全规则

Vela 的主张是机械性的，而非风格上的：

1. **没有 `unsafe`，没有指针，没有 `free`，没有原始 FFI。** 不存在能产生未定义行为的
   语法。一个 C 函数可以被*声明*并调用，但只能作为标量到标量的调用（§12）：没有指针
   类型可写，所以外部签名无法把指针放进源码，而编译器无法诚实描述的边界——一个 `str`、
   一个数组、一个结构体——是被拒绝的，而不是被猜的。
2. **所有堆存储只有一个 arena。** `free` 不存在，所以 double-free 和 use-after-free
   写不出来。一个函数的帧只有在它退出时才释放。
3. **函数不得返回自己的数组**（检查器会拒绝），所以 arena 指针不可能比它的帧活得更久。
   `str` 的存储要么是字面量，要么是永久的主机内存，所以被返回的字符串永远不会悬空。
4. **下标访问是受检查的。** 若 `i` 越界，`a[i]` 会带着文件、行号和原因 panic，而一个
   不可能在范围内的*常量*下标会在程序运行之前被拒绝（§6.1）。
5. **整数算术的溢出总是受检查的。** 没有关闭检查的开关——没有 `--fast-int`，没有 `-O`，
   什么都没有——而且只有当编译器证明某检查不可能触发时，它才会消失（§6.1）。
6. **除以常量零是编译错误**；运行期的除数受检查。
7. **一个可证明越界的常量下标是编译错误。**
8. `parallel for` **仅在竞态自由（race-freedom）被证明时**被接受（§7）。

### 6.1 什么会被证明掉

检查只会被证明消除，永远不会被开关或标注消除——没有 `unsafe`，没有属性，没有开关。
证明机制今天实际上走到哪一步，因为本文档讲的是语言现在的样子：

| 证明 | 它的用途 |
|---|---|
| 对每个整数表达式做区间分析（interval analysis） | 允许一个 `parallel for`（§7），并拒绝一个不可能在范围内的常量下标 |
| 循环变量跟踪（`0 <= i < N`） | 同一个证明：一个它的写入无法被定位的循环会被拒绝，而不是被并行化 |
| 在循环入口处使循环内被赋值的任何东西失效 | 在回边（back-edge）上保持那些区间事实*可靠* |
| 条件收窄（condition narrowing，`if i < len(a)`） | 已设计，未实现 |
| `len(a)` 的常量折叠 | 长度计算本身，所以 `len` 在产出的 C 里是一个常量 |

**由证明删除检查尚未实现。** 后端会输出每一个边界和溢出检查——`vela_bounds_check`、
`vela_add_range` 之类——包括对它显然能证明的下标，而且没有任何数组参数被标成
`restrict`。因此"只由证明消除"是一条语言所遵守的*规则*（别的东西都无法删除检查），
而还不是关于这个编译器写出的 C 的事实：`vm.exe emit-c bench/matmul.vel` 会显示它保留下来的
检查。这些证明今天买到的是许可和拒绝，仅凭这一点它们就值得——一个未被证明竞态自由的
`parallel for` 是被拒绝的，而不是被输出、然后指望它没问题。

`DESIGN.md` §7.5 把这一条列在自举后端仍然欠下的东西里。

### 6.2 作用域规则

一个块引入一个作用域。块内的声明在块外不可见；对一个在当前函数中未声明的名字赋值是
错误；在同一个作用域里声明同一个名字两次是错误。编译器就这几件事自己的说法如下，
取自它打印的消息以及锁定这些消息的 golden 文件——本段之前的版本引用了一句在编译器里
**任何地方都不存在**的话，对 `selfhost\build\vm.exe`（634368 字节，2026-09-20）的一次
字节搜索就能定案：`'different meaning'` 和 `'one scope'` 都不在，而 `'cannot rebind'`、
`'already declared in this scope'` 和 `'declared immutable'` 都在。

| 被拒绝的东西 | 消息 |
|---|---|
| 在同一个作用域里声明同一个名字两次 | `'x' is already declared in this scope` |
| 对一个不可变绑定赋值 | `cannot assign to 'x': it was declared immutable` |
| 重新绑定一个数组名字 | `cannot rebind array 'a'` |

跨块的遮蔽（shadowing）仍然合法；被拒绝的是*在同一个作用域里的重新声明*，
而消息会说明是哪个名字、哪个作用域。

---

## 7. `parallel for`

```vela
parallel for i in range(0, n) {
    out[i] = a[i] * 2
}
```

只有当**下面全部**成立时，检查器才会接受它：

* 该循环必须是 `range(...)` 循环，这样迭代空间才是已知的；
* 被写入的每个数组必须是局部 `mut` 数组（绝不是参数，绝不是共享状态）；
* 一个数组只通过**一个**下标表达式写入，而该表达式必须在迭代空间上可证明是
  **单射的（injective）**——要么是 `c * i + rest`，其中 `|c|` 大于 `rest` 的范围，
  要么是行优先形式 `i * N + j`，其中 `j in range(0, N)`；
* **函数体写入的数组，在同一循环内只能在该同一下标表达式处被读。** 这就是别名
  （aliasing）规则，也是 Rust 的 `par_iter_mut` 在类型里编码的东西在语言层面的形式：
  一次迭代拥有它自己的元素。`out[i] = a[i] * 2` 合法，因为 `a` 在这里只被读、没有任何
  东西写它；`a[i] = a[i] + 1` 合法，因为每次迭代读的正是它写的；而 `a[i] = a[i - 1] + 1`
  被拒绝，因为它读了另一次迭代正在写的元素——这个循环携带依赖（dependence），而编译出的
  程序每次运行都会打印不同的答案。读一个函数体**不**写入的数组是自由的，任何下标都行；
* 函数体内被写入的标量必须是该次迭代私有的（在函数体内声明），否则该写入就是共享状态，
  循环被拒绝；
* 函数体内的调用必须是 `pure`：`print`、`read_text` 之类都被拒绝。

其他任何情况都会产生一个点名原因的安全性错误，而不是把一个
`#pragma omp parallel for` 交给 C 编译器、指望那些写入不会撞车：只有当规则接纳了这个循环时
才会输出该 pragma，而且只有当产出的 C 带有一个 pragma 时构建才会传 `/openmp`
（或 `-fopenmp`）。（`bench/matmul.cpp` 里 matmul 基准的 C++ 孪生体展示了这条规则所防止的
失败模式：把内层下标提到并行体之外后，它会静默地产生垃圾。）

---

## 8. 内建函数

纯的，并且可以从 `pure` 函数中调用：

| 内建 | 类型 |
|---|---|
| `len(a)` | `Array[T, N] -> int`（折叠为常量），`str -> int` |
| `to_float`, `to_int` | `int -> float`，`float -> int` |
| `sqrt`, `fabs`, `floor` | `float -> float` |
| `pow` | `(float, float) -> float` |
| `abs` | `int -> int` |
| `min_int`, `max_int`, `min_float`, `max_float` | 两个参数，同一类型 |
| `bytes_at(s, i)` | 受检查的字节值 0..255 |
| `substr(s, a, b)` | 字符串的一段切片（不拥有，而且安全，因为每个 `str` 要么是字面量要么是永久内存——§6 规则 3） |
| `unescape(s)` | 解码反斜杠转义 |
| `concat(a, b)` | 两个字符串拼接。刻意是一个*具名*内建而不是 `+`：两个字符串上的 `a + b` 仍然被拒绝（§1.5），但一个必须给文件命名、拼命令行或写消息的编译器需要某种制造字符串的办法，而没有别的办法。结果是永久内存，像字面量一样 |
| `interned(h)` | 字符串表查找 |
| `env(name)` | 环境所说的内容，当它什么都没说时是 `""` |
| `argc()`, `arg(i)` | 进程参数 |

不纯的，所以**不**允许在 `pure` 或 `parallel for` 内：

| 内建 | 类型 |
|---|---|
| `print(...)` | 值之间以空格分隔，然后一个换行 |
| `read_text(path)` | 整个文件作为 `str`（不可读时为空字符串） |
| `write_text(path, s)` | `bool` |
| `intern(s)` | 一个字符串的稳定整数句柄（内容寻址表） |
| `run_command(cmd)` | 经由 C 库的 `system()` 把一条命令行交给主机，并在它报告成功时回答 `0`。该命令自己的输出到本进程的 stdout/stderr，不被捕获。它是**唯一一条不是关于数据的能力**，它存在只为一个原因：一个刚写完 C 的编译器必须能启动一个 C 编译器，否则 `vm.exe build file.vel` 就需要一个用别的语言写的驱动——而自举要删除的正是这个东西（DESIGN §9.4） |
| `panic(msg)` | 打印到 stderr 并中止 |
| `emit_str/emit_int/emit_float/emit_nl/warn_str/warn_int/warn_nl` | 不带分隔的 stdout/stderr 输出 |
| `now()` | 墙钟秒数 |

`concat`/`intern`/`interned`/`unescape`/`read_text`/`write_text`/`env`/`run_command`/
`arg`/`argc` 存在有一个具体的原因：**它们是一个用 Vela 写的编译器所需要的、而没有指针、
glob 或进程 API 就无法表达的东西。** 它们是让自举成为可能的最小主机表面（§10），
而且它们没有一个交回指针、交回指向语言自身内存的句柄，或交回比调用活得更久的能力——
`run_command` 返回一个退出状态，什么都不保留。安全的 Rust 有 `std::process::Command`；
这是同一种表面，被削减到最小可用的形状。

---

## 9. 程序入口点

一个程序在顶层有一个 `def main() -> None`。编译器生成
`int main(int argc, char **argv)` 并调用它；除非某个受检查的操作失败，进程退出码为 0；
那种失败会带着文件和行号 panic。

---

## 10. 自举

Vela 不使用缩进，这不是一个装饰性的决定：自举的词法器不需要缩进栈、不需要 INDENT/DEDENT
记号，也不需要空行的特殊情形。

编译器就是 Vela，由它自己编译。`selfhost/vela.vel`（词法器）和 `selfhost/parts/*.vel`
（共享状态、语法分析器、解析器、检查器、解释器、C11 输出器、语法树转储器、驱动）
由 `tools/link_selfhost.vel` 拼装进单个文件 `selfhost/vm.vel`——一次构建期拼接，因为
Vela 0.1 没有模块系统——而 `vm.exe build selfhost/vm.vel` 把它变回编译器。走到这里的那些
阶段，以及每一个阶段是拿什么对已经不复存在的 Python 前端认证的，都在 `DESIGN.md` §7。

编译器所做的一切都能从它自己的命令行到达，§11 列出了这些：`lex`、`count`、`parse` 和
`nodes` 用于前端自己的视图，`check` 用于各种判定，`emit-c` 用于它写出的 C，`build` 用于原生二进制，
`run` 用于解释器。一个既想要编译器对某程序的看法、*又*想要该程序自己输出的测试，会运行两个模式并
比较它们，这正是 `tests/run_tests.vel` 里的语料所做的：它的 `run` 用例要求被解释和被编译的程序打印
同样的东西。

**有两个后端，而 `build` 是那个不需要 C 编译器的。** `build` 发出 LLVM IR，在进程内构建目标
文件——`libLLVM` 就链接在 `vm.exe` 自己里面——再用 `lld-link` 链接它。它不启动任何 C 编译器，
用户看得到的地方不会留下 `.c`、也不会留下 `.ll`，而且没有回退：LLVM 后端拒绝的程序就是被拒绝，
并给出理由。C 后端是参考实现，它留在树里，而且是按*名字*到达的——`build-c`——永远不会被意外用到。
§11 两个都列，并说明各自接受哪些构造。

---

## 11. 命令行

编译器是一个二进制，它的命令行是一个模式加一个文件：

```
selfhost\build\vm.exe lex    file.vel    dump the token stream
selfhost\build\vm.exe count  file.vel    how many tokens the file has
selfhost\build\vm.exe parse  file.vel    the syntax tree, in the canonical dump format
selfhost\build\vm.exe nodes  file.vel    the raw node pool, one line per node
selfhost\build\vm.exe check  file.vel    refuse the program if it is not allowed to exist
selfhost\build\vm.exe emit-c file.vel    the C11 the program compiles to, on stdout
selfhost\build\vm.exe emit-llvm file.vel the LLVM IR the program compiles to, on stdout
selfhost\build\vm.exe run    file.vel [program arguments...]
                                         interpret the program
selfhost\build\vm.exe build  file.vel [runtime-dir]
                                         build it through libLLVM in process and link it
                                         with lld-link: no C compiler is started, and
                                         file.exe is the only product
selfhost\build\vm.exe build-c file.vel [runtime-dir] [extra-link]
                                         the C back end: emit the C and hand it to the
                                         host's C compiler, producing file.exe beside
                                         the source (the C goes to the build scratch)
selfhost\build\vm.exe build-llvm file.vel [runtime-dir]
                                         the same path as `build`, under the name the
                                         gates and the LLVM plan use
```

* **没有模式带选项**，只有一个例外：文件名之后那个可选参数是存放 `vela_runtime.h` 的目录
  ——默认是 `runtime`，它既作为包含目录交给 C 编译器，也用来让 `build` 找到
  `vela_llvm_runtime.obj`。`build-c` 还多接受一个参数：追加到 C 编译器的命令行后面的一个字符串，
  只有 `tools\build.ps1` 会传它，用来把编译器自己的依赖链接进它正在构建的那个 `vm.exe`。没有
  `-O`，没有 `-o`，没有 `--fast-int`，没有 `--no-omp`。
* 当程序被接受时，`check` 打印 `ok` 并以 0 退出；当它被拒绝时，把诊断写到 stderr 并以非零退出。
  `run`、`build` 和 `build-c` 都先运行同一个检查，所以没有任何模式会输出或执行一个检查器会拒绝的
  程序。
* `run` 把参数交给程序：`arg(0)` 是源文件路径，`argc() - 1` 是它之后的参数个数。
* **`build` 只启动一个外部程序——一个链接器——而不启动编译器。** `libLLVM` 是 `vm.exe` 自己的
  载入期依赖，所以代码生成器就是这个编译器自己的库，正如 `rustc` 带着 LLVM 那样；模块在进程内构建
  并验证，目标文件由编译器自己写出，链接命令行上只出现一个程序：`lld-link`。如果被编译的程序自己
  调用了 LLVM shim（只有编译器是这样），链接行上还会多出那个目标文件和 `LLVM-C.lib`，而驱动之所
  以知道，是因为它去问了自己刚写出的那个模块。
* **`build-c` 是 C 后端，它把整条工具链装在一个二进制里**——没有驱动脚本，没有 Python。它通过以
  `emit-c` 模式重新进入自己来输出 C（这样后端只有一个输出目标，重定向由 shell 来做），通过
  `VELA_VCVARS` 或标准安装位置找到 MSVC，并回退到 `$VELA_CC`、`cc`、`gcc` 或 `clang`。它询问它
  刚写出的那份 C 里面有没有 `#pragma omp`，并且只在有时才传 `/openmp`（或 `-fopenmp`）。
* **每个后端接受哪些构造是关于这个实现的事实，不是关于语言的承诺。** LLVM 后端编译普通程序——
  带检查的算术、`if`/`while`/`for`、函数与递归、结构与方法、数组与下标、字符串及其内建函数、
  `extern c`——它**不**编译 `parallel for`，这是有意且永久的：LLVM IR 没有 OpenMP，发出那套运行
  时 ABI 超出范围，而一个自称并行却串行执行的循环是这个项目承诺永不发布的唯一失败模式。那些程序由
  `build-c` 编译，而 `tests/cases.txt` 用 `run-c` 标出那七行，并在旁边写明原因；另一半是
  `tests/llvm-refusals.txt` 里那份账本，`tools\llvm-column.ps1` 同时掌管两侧。
* **一次安装是三个文件，其中两个不是可选的。** `vm.exe` 内部带着 `libLLVM`，所以
  **`LLVM-C.dll` 是这个编译器自己的载入期依赖**：一个放在没有这个 DLL 的目录里的 `vm.exe` 根本
  起不来——`STATUS_DLL_NOT_FOUND`（退出码 `0xC0000135`）发生在 `main` 之前，两个输出流上都不会
  提到 LLVM，于是之后每一个检查都会失败并报出错误的问题。`vela_llvm_runtime.obj` 是第二个：
  `build` 把它链接进它构建的每一个程序，并且在编译器旁边找它。`tools\build.ps1` 把它写出的每个
  `vm.exe` 旁边都放上这两个文件，缺了就拒绝结束；`tools\smoke.ps1` 断言它们在那里。这与 Rust
  工具链带着自己的 `libLLVM` 是同一种形状——库待在编译器身边，而不是待在用户的程序身边。
  **`build` 的产物两个都不需要。** `build` 写出的可执行文件只链接 C 运行时，别的什么都不链接：
  它在一台既没有 LLVM、也没有那个 DLL、也没有 C 编译器的机器上照样启动并运行。实测：在一个不是仓库
  的目录里执行 `vm.exe build p.vel` 退出 0，程序能跑，而那个目录里只有 `p.vel` 和 `p.exe`。
* 源码里还带一个 **`debug` 模式**——同一个解释器，停下来，事件在 **stderr** 上，
  命令从调用者指定目录里的文件读取
  （`vm.exe debug file.vel <cmddir> [program arguments...]`）。
  `DESIGN.md` §10 是协议：`cmd.NNN` 文件通道、事件、命令、命令行，以及 2026-09-20 实测出的、
  仍然挡在它与一个编辑器之间的两个缺陷——`continue` 会解除发出它的那一帧的断点，
  以及 `vars` 在一个局部变量明明在作用域内的停点上报告 `locals 0`。本文档记录该模式存在
  以及它拒绝承诺什么；面向编辑器的判定是 §10.6 的。

构建编译器自己本身是 `tools\build.ps1`（§10，`DESIGN.md` §7）：一条命令从签入的 C 引导出编译器、
链接各个部分、用编译器自己的 LLVM 后端构建编译器——那一步不运行任何 C 编译器——并检查三代发出的 C
彼此逐字节相同、且等于那个种子。测试套件是 `tests\run_tests.vel`，一个由同一个编译器构建的 Vela
程序。

---

## 12. 外部函数：`extern c`

Vela 能调用 C 函数——而且只能调 C 函数，只能调标量形式的。写法是一个没有函数体的声明：

```vela
extern c def abs(x: i32) -> i32              # the C prototype `int abs(int)`
extern c pure def sqrt(x: float) -> float    # `double sqrt(double)`, no side effects
```

* **声明就是原型。** 没有头文件可包含，没有库可命名，而被声明的名字*就是* C 符号：
  后端用它的裸名调用它，没有 Vela 函数会得到的 `vl_` 前缀。如果声明的名字与后端为某个运算符
  已经发出的名字相同，那就是同一个符号，会复用那份声明（`sqrt`、`pow`、`floor`、`fabs`）；
  如果声明的类型与已有声明不一致，就拒绝它而不是改名。
* **类型是标量。** `int` 是 C 的 `long long`，`i32` 是 C 的 `int`，`u8` 是 C 的
  `unsigned char`，`float` 是 C 的 `double`，`bool` 是 C 的 `bool`；`-> None` 是 C 的
  `void` 结果。一个 `str`、一个数组和一个结构体在它们被写下的地方、在声明的那一行被拒绝
  ——Vela 将不得不发明一套 ABI 才能把它们中的任何一个交出去，而发明 ABI 正是一门安全
  语言不再安全的方式。指针根本无法表达，这就是这张列表到此为止的原因。
* **`pure`** 表示该外部函数没有副作用。编译器看不到外部函数体，所以这是唯一可用于此的
  词，也正是它让一个 C 函数可以从 `pure def` 或 `parallel for` 内部被调用。
* **一个实参只能以该参数所声明的种类跨界**，在调用的那一行受检查。唯一的例外就是 §3.1
  在规则比较两个类型时所允许的那个：一个值放得下的整数*常量*（`abs(-7)` 合法，
  `abs(1.5)` 不合法）。没有这条规则，C 编译器会静默转换——`abs(1.5)` 会打印 1，
  `abs(a)` 会截断一个地址——而边界正是 Vela 承诺要拼写清楚什么东西跨过它的那个地方。
  实参个数与调用 Vela 函数时完全一样受检查（`wrong number of arguments`）。
* **解释器没有外部调用**：`vm.exe run` 会求值实参，然后对结果什么都不产生。使用该结果是
  错误（`this value cannot be printed`），而一个结果被丢弃的调用会静默地成为一个空操作，
  副作用也包括在内——这是 `eval.vel` 里的一个缺口，在 `DESIGN.md` §7.4 和 §9.2 点名记录，
  而不是被藏起来。
* **刻意缺失：** `extern c "lib" { ... }` 块、`cstr`、以 `(T*, N)` 跨界的数组、
  `extern struct`、可变参数（variadics）、回调、由库保留的指针，以及任何比 `u8` 更宽的
  无符号类型。`DESIGN.md` §9 有每一条的理由，以及那些缺口。

§6 的安全主张之所以保持*机械性*，正是因为这件事如此之小：一个声明能说的就是一个标量 C
调用、别的什么都不能，所以没有指针需要类型，没有东西能被释放，没有东西能被存储。任何边界
都不能承诺的是库拿它得到的东西做了什么——一个 C 函数可以破坏内存，而一个调用 `abort()`
的 C 函数仍然会 abort。Rust 也有 FFI，这正是那里存在 `unsafe` 的原因。C++ 由决定排除在
外，因为它没有稳定的 ABI（`DESIGN.md` §9.3）。

---

## 13. 带载荷的 enum，以及 `match`

```vela
enum Shape {
    Circle(radius: float)
    Rect(w: float, h: float)
    Empty
}

def area(s: Shape) -> float {
    match s {
        Circle(r) {
            return 3.14159 * r * r
        }
        Rect(w, h) {
            return w * h
        }
        Empty {
            return 0.0
        }
    }
}

def main() -> None {
    c: Shape = Circle(2.0)     # 构造就是调用的形状
    print(area(c), area(Empty))
}
```

* **一个变体最多携带一个载荷结构体。** `Circle(radius: float)` 有一个字段，
  `Rect(w: float, h: float)` 有两个，`Empty` 没有；字段表写起来像参数表，解析方式也一样。
* **enum 是名义类型（nominal），也是值类型。** 和结构体一样，它活在栈帧里、大小固定、
  赋值即复制，不需要堆、不需要 `None`、也不需要异常：等泛型落地时 `Result[T, E]`
  就是一个双变体的 enum。
* **构造就是调用。** `Circle(2.0)` 用的就是调用本来就有的形状，没有载荷的变体则是它的裸名字
  （`Empty`）。该给字段的值没给会被拒绝，给多了也一样。
* **`match` 是语句**，分支是带花括号的块，绑定是*按位置*的：`Circle(r)` 按声明顺序把 `r`
  绑定到第 1 个载荷字段。嵌套模式（`Circle(Circle(r))`）与守卫（`Circle(r) if r > 0`）
  是拒绝项，不是留待以后半实现的东西。
* **每个变体都能按名字到达，并且只有一个 enum 能占住一个名字。** 变体名是文件全局的，
  因为 v1 没有"期望类型"那套机制去让 `Empty` 在一个位置读作 `Shape.Empty`、在另一个位置
  读作 `Tree.Empty`；第二个 enum 声明同名变体时会被拒绝，并同时点名两个 enum。
* **必须穷尽。** 对 enum 的 `match` 要么点出每一个变体，要么以 `else` 结尾；`else`
  是逃生口，而且必须是最后一个分支。缺变体会被拒绝，而且诊断里*点名*那个变体；重复分支会被
  拒绝；分支点到另一个 enum 的变体也会被拒绝。
* **主语必须是 enum 值**：对 `int` 做 `match n` 会被拒绝。
* **值类型不能包含自身。** `enum Tree { Node(child: Tree) }` 会被拒绝：包含自身需要装箱
  表示或者显式数组，而 0.1 里两者都不存在。

### 13.1 0.1 不做的部分，以及各后端拒绝什么

在这里点名，而不是留给人以后去发现：两个 enum 值之间的相等比较（`==`）、哈希、
`print` 一个 enum 值、泛型载荷，以及通过载荷递归。`Array[E, N]` 里的 enum 是大小固定的
值类型，解释器可以运行它；C 后端则**按名字**拒绝这个形状，因为它对结构体也没有降低
聚合元素的数组；LLVM 后端在任何地方都拒绝 enum 值，因为它降低结构体的方式是把字段摊进寄存器，
而"标签 + union"不是那个形状。`match` 分支里的 `break` 同样被 C 后端拒绝：在 Vela 里这个词
离开的是外层循环，而在 C 里 `switch` 里的 `break` 离开的是 switch。

