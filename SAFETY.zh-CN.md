# Vela 的安全究竟在哪里

[English](SAFETY.md) | **简体中文**

<!--
源文件 : SAFETY.md
源文件字节 : 34674
源文件 SHA256 : 489dfcf7bca72604d1c295453cc7c15d4c80a220086e291c01a5a8143cf0f29e
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

`DESIGN.md` §1.2 声称 Vela "比 Rust 更安全"，并列出了那些承诺。本文件就是那份清单，
每一行背后都有一个程序：声称的是什么、证明它的那个用例是什么、它跑在哪个模式下、
实测到了什么、以及这条承诺今天是否成立。下面没有任何一句是从文档里引来的却没有
用例，下面也没有任何一个测量是散文——每一个数字都来自 `tools/safety.ps1`，它是唯一
产出过这些数字的东西。

**如何复现整个文件。** 没有参数，没有准备工作：

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1
```

套件在任何用例运行之前，把当前的编译器复制**一次**，放进
`%TEMP%\vela-safety\frozen\vm.exe`——它自己创建那个目录，并把 `LLVM-C.dll` 复制到它
旁边，这个编译器启动时会加载它——打印这份副本的 SHA256 和字节数，然后让全部 69 个
用例对着这份副本运行。这个仓库的编译器在语料被人摆弄的时候会被重新构建（写这些用例
的那次会话里重建了五次，其中一次就在一次运行的中间），所以由两个不同的编译器判出来的
一次运行，对它们哪一个都证明不了什么；`-FrozenVm <path>` 在一个记录下来的数字必须被
复现时，钉住一个更旧的构建。

**下面每一个数字都是对着这个编译器实测的：**

```
SHA256  AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0
size    795136 bytes       selfhost\build\vm.exe, 2026-09-20 02:52
```

- 统计：**55 passed, 0 failed**，然后把三类记录下来的行分开计数：

  ```
  violations still open (FAIL-as-expected): 4
  violations now closed (CLOSED):            7
  other xfail rows failing as recorded:      2
  deliberate gaps documented (DELIBERATE):   1
  RESULT: PASS
  ```

  这些行在 §3.1 和 §3.2（已关闭）以及 §3.3（仍然开放）里被点名，而计数规则是 §0。
- 另有一个更旧的构建被钉在旁边，用来做前后对比，而正是它让那几列变得可读：
  `67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A` / `770560
  bytes` → **55 passed, 0 failed**、`violations still open: 11`、`CLOSED: 0`、`other
  xfail rows: 2`、`DELIBERATE: 1`。同样的 55 个非洞行在两者之下都通过，这正是那七个
  移动过的行只关乎这七行、与别的无关的原因。
- 那些用例：`tests/safety/cases/*.vel`（69 个程序，只有 ASCII）。
- 那些期望：`tests/safety/manifest.txt`，一个用例一行，在这一切被运行之前照着规范
  手写的。
- **运算符表的一元/比较那一半（`bfd70fc`）被用例看着，已经没有缺口可记。** 成员访问
  （`099f6fc`）比这份语料更新：它那四种够不到的接收者形状是行（§3.2），而那一种由
  后端而不是由规则拒绝的形状，被记在 §4 里算作未覆盖，而不是被装扮成一个检查器的洞。
  把两者都写下来的意义在于：只读 `tests/safety/` 的读者否则不会知道成员规则伸展到了
  多远。

## 0. 一行的判定是怎么算出来的

三种行，而**每一种都必须能在两个方向上失败**。这套件的第一版做不到：一条承诺已被守住
的行，无论那次拒绝给出的是什么理由，都报告 `CLOSED`，于是语料对自己的主题视而不见。
那和一个不会失败的检查是同一种缺陷，而这正是下面那张表为每一类里居中的那个结果各留
一行的原因。

| 模式 | 承诺（断言） | 实测 | 判定 |
|---|---|---|---|
| `violation` | `check` 拒绝，带着记录下来的 `check_msg` | 拒绝，消息相同 | `CLOSED` |
| | | 拒绝，消息**不同** | **`FAIL`**——这条规则成立的理由没有人记录过；要由人来读它 |
| | | 接受 | `FAIL-as-expected`——洞开着，正如记录的那样 |
| `deliberate` | `check` **接受**（exit 0、`ok`、stderr 为空），而后端随后拒绝它 | 接受，后端拒绝 | `DELIBERATE` |
| | | 拒绝 | **`FAIL`**——被记录下来的决定变了，而陈述它的那个文件也必须改变 |
| `check` / `ok` / `native` / `run-ok` / `diverge` | 这一行自己的期望 | 相符 | `PASS` |
| | | 不相符 | `FAIL` |
| 任何带运行期记录的行 | ——（是记录，不是断言） | 移动了 | 在记录*就是*断言的地方是 `BEHAVIOUR-CHANGED`；否则是一条打印出来的 `note` |

`violation` 行是唯一会有 `CLOSED` 的行，因为它们是唯一断言一条*规则*被守住的那些行。
像 `hole_mut_scalar_parameter` 这样的一行陈述的是一个普通的期望（调用点打印 `11`），
只是它已知会失败；它报告 `FAIL-expected`，而如果编译器开始打印 `11`，它会报告
`UNEXPECTED-PASS`——把那件事装扮成 `FAIL-as-expected`，只会是又一个不会失败的检查。


## 1. 承诺表

| # | 声称的是什么（原文引用） | 用例 | 模式 | 实测 | 判定 |
|---|---|---|---|---|---|
| 1 | *"Integer arithmetic is checked for overflow, always."*（"整数算术永远带溢出检查。"）(SPEC §6.5) | `arith_overflow_add`, `_sub`, `_mul`, `_division`, `_negation`, `_abs`, `_pow`, `arith_shift_negative_variable`, `arith_shift_64_variable` | native | 编译后的程序 **exit 2**、`vela: panic: integer overflow (addition)` 等；`vm.exe run` 以同样的理由 exit 2 | **ENFORCED TODAY** |
| 2 | *"Division by a constant zero is a compile error"*（"除以常量零是一个编译错误"）(SPEC §6.6) | `arith_div_by_const_zero`, `arith_mod_by_const_zero` | check | **exit 2**、`vela: safety error: division by constant zero`，stdout 上什么都没有 | **ENFORCED TODAY** |
| 3 | *"a runtime divisor is checked"*（"运行期的除数会被检查"）(SPEC §6.6) | `arith_div_by_zero_variable`, `arith_mod_by_zero_variable` | native | exit 2，两个前端都给出 `division by zero` / `remainder by zero` | **ENFORCED TODAY** |
| 4 | 浮点除以常量零（SPEC §6.6 只拼出了整数的那几种形式；`check.vel` 有意拒绝这一种：IEEE "would hand back an infinity or a nan"（"会还回一个 infinity 或者一个 nan"）） | `arith_float_div_by_literal_zero` | check | exit 2、`float division by constant zero` | **ENFORCED TODAY**——这条规则是一次拒绝，而这次拒绝正是用例所钉住的东西 |
| 5 | *"A constant index that is provably out of range is a compile error."*（"一个可证明落在范围之外的常量下标是一个编译错误。"）(SPEC §6.7) | `bounds_const_index_oob`, `_negative`, `_len2`, `_len1` | check | exit 2、`constant index 4 is out of range for Array[int, 4]` | **ENFORCED TODAY**，除了零长度数组——见洞 6 |
| 6 | *"Indexing is checked. `a[i]` panics with file, line and reason if `i` is out of range"*（"下标会被检查。如果 `i` 落在范围之外，`a[i]` 会带着文件、行号和理由 panic"）(SPEC §6.4) | `bounds_runtime_index_oob`, `_negative`, `bounds_runtime_write_oob` | native | 编译后：`vela: panic: index 4 out of range for array of length 4 (the compiler could not prove this index in range)`，exit 2；解释执行：`index out of range for this array`，exit 2 | **ENFORCED TODAY** |
| 7 | 同样的东西，对零长度数组（`Array[int, 0]`） | `bounds_zero_len_array_runtime`, `bounds_zero_len_array_write` | native | exit 2、`index 0 out of range for array of length 0` | **ENFORCED TODAY** |
| 8 | *"A function may not return its own array (the checker refuses it), so an arena pointer cannot outlive its frame."*（"一个函数不能返回它自己的数组（检查器拒绝它），所以一个 arena 指针活不过它的栈帧。"）(SPEC §6.3) | `memory_return_own_array`, `memory_return_param_array` | check | exit 2、`function 'make' returns Array[int, 4]` | **ENFORCED TODAY** |
| 9 | *"`str` storage is either a literal or permanent host memory, so a returned string can never dangle."*（"`str` 的存储要么是一个字面量，要么是宿主的永久内存，所以返回的字符串不可能悬垂。"）(SPEC §6.3) | `memory_return_str_binding`, `memory_return_substr` | run-ok | 编译后打印 `hi` / `he`，解释器打印同样的东西，两者都 exit 0 | **ENFORCED TODAY**（而且这个形状仍然被*接受*，如果这条规则是靠拒绝来实现的，坏掉的正是这一半） |
| 10 | *"`free` does not exist, so double-free and use-after-free cannot be written."*（"`free` 不存在，所以 double-free 和 use-after-free 写不出来。"）(SPEC §6.1) | `escape_no_free_builtin` | check | exit 2、`vela: type error: undeclared name 'free'` | **REFUSED BY DESIGN**——这个名字根本不在语言里 |
| 11 | 没有指针，没有 `unsafe`（SPEC §6.1） | `escape_no_free_builtin`, `escape_nested_function_refused`, `escape_no_closures` | check / run-ok | `free` 未声明；一个嵌套函数得到 `nested functions are not supported`；一个带局部变量的函数打印 `1` 并 exit 0 | **REFUSED BY DESIGN**——没有语法能把 `unsafe` 写出来，所以也没有哪个用例能选择退出 |
| 12 | 没有全局变量（SPEC §6.2） | `escape_no_global_state` | check | exit 2、`a top-level statement must be a function or a struct` | **REFUSED BY DESIGN** |
| 13 | 结构体字段不能是数组（SPEC §4） | `escape_array_struct_field_refused` | check | exit 2、`field 'a' may not be an array` | **REFUSED BY DESIGN** |
| 14 | *"an array the body writes may be read inside the same loop only at that same index expression"*（"循环体写入的一个数组，在同一个循环里只能在那同一个下标表达式处被读"）(SPEC §7) | `concurrency_cross_iteration_read_refused`, `concurrency_cross_iteration_bare_index` | check | exit 2、`'parallel for' reads 'a' at an index other than the one it writes` | **ENFORCED TODAY** |
| 15 | 三种合法的 `parallel for` 形状仍然必须能编译（SPEC §7） | `concurrency_read_any_index_legal`, `concurrency_same_index_legal`, `concurrency_linear_stride_legal` | run-ok | check 接受；三个形状发出的 C 里都有 `#pragma omp parallel for`；编译后和解释执行都打印 `14`、`8`、`4` | **ENFORCED TODAY**——如果一条规则过严，坏掉的正是这一半 |
| 16 | 一个证明无法安放的 `parallel for` 是被*拒绝*，而不是被发出（SPEC §7） | `concurrency_parallel_outer_rowmajor_refused`, `concurrency_serial_outer_parallel_inner` | check | exit 2、`cannot prove that 'parallel for' writes 'out' to distinct elements` | **ENFORCED TODAY** |
| 17 | *"Bindings are immutable by default"*（"绑定默认不可变"）(SPEC §3.2) | `strict_immutability` | check | exit 2、`cannot assign to 'x': it was declared immutable` | **ENFORCED TODAY** |
| 18 | *"truthiness → conditions must be bool"*（"真值性 → 条件必须是 bool"）(SPEC §4) | `strict_no_truthiness` | check | exit 2、`'if' condition must be bool, got int` | **ENFORCED TODAY** |
| 19 | *"An expression statement must be a call."*（"一个表达式语句必须是一次调用。"）(SPEC §4.1) | `strict_useless_expression_statement` | check | exit 2、`this expression statement has no effect` | **ENFORCED TODAY** |
| 20 | *"No implicit conversion between the types a rule can compare."*（"在一条规则能比较的类型之间没有隐式转换。"）(SPEC §3.1)——运算符表的二元那一半 | `strict_no_implicit_conversion` | check | exit 2、`operator '+' mixes int and float` | **ENFORCED TODAY** |
| 21 | 同一条承诺，对**比较运算符和一元运算符**——运算符表的另一半，`DESIGN.md` §7.5 曾承认它未受检查 | `hole_cmp_bool_bool`, `hole_cmp_bool_int`, `hole_cmp_int_float`, `hole_cmp_str_int`, `hole_unary_neg_bool`, `hole_unary_neg_str`, `hole_unary_not_int` | violation | 全部七个在 `AD4A997B` 之下都是 **`CLOSED`**：`check exit 2`，消息为 `operator '<' cannot be applied to bool and bool`、`operator '<' mixes bool and int`、`operator '<' mixes int and float`、`operator '<' cannot be applied to str and int`、`operator '-' cannot be applied to bool`、`operator '-' cannot be applied to str`、`operator 'not' cannot be applied to int`。在被钉住的 `67ACF63B` 之下，七个全都是 `FAIL-as-expected`（`check exit 0, ok`，而且其中一个在运行期回答 `False`） | **ENFORCED TODAY as of build `AD4A997B…3D0`**（修正：`bfd70fc`）——这七行从此就是这条承诺被看着的方式 |
| 22 | *"Tuples do not exist; `(1, 2)` is refused"*（"元组不存在；`(1, 2)` 被拒绝"）(SPEC §4.1) | `strict_no_tuples` | check | exit 2、`tuples are not supported` | **ENFORCED TODAY** |
| 23 | *"`elif` is not part of Vela"*（"`elif` 不是 Vela 的一部分"）(SPEC §4) | `strict_no_elif` | check | exit 2、`'elif' is not part of Vela; write 'else if'` | **ENFORCED TODAY** |
| 24 | *"An ordinary Vela call is not type-checked either"*（"一次普通的 Vela 调用同样不做类型检查"）(SPEC §3.1，作为一处有意的缺口写明) | `strict_call_int_into_float_param`, `strict_call_float_into_int_param` | run-ok | 被接受；编译后打印 `3.000000` / `3`，解释器打印 `3` / `3.500000`——**两个前端不一致** | **NOT ENFORCED，并且就这么记录在案**——用例断言的就是这次分歧，所以两边都不能静默地漂移 |
| 25 | `to_int` / `to_float`（SPEC §3.1） | `strict_to_int_explicit`, `strict_to_float_explicit` | run-ok | 两个前端都打印 `3` / `3.000000` | **ENFORCED TODAY** |
| 26 | 一个 `mut` 结构体参数可以被写穿（SPEC §3.2 第 148 行） | `probe_mut_struct_parameter` | run-ok | 编译后和解释执行都打印 `11` | **ENFORCED TODAY** |
| 27 | 一个 `mut` 数组参数可以被写穿（SPEC §3.2 第 148 行） | `probe_mut_array_parameter` | run-ok | 编译后和解释执行都打印 `99` | **ENFORCED TODAY** |
| 28 | 一个 `mut` *标量*参数可以被写穿（SPEC §3.2 第 148 行，"the callee may write through it"（"被调用者可以写穿它"）） | `hole_mut_scalar_parameter` | run-ok | 两个前端都打印 **`10`**，而承诺是 `11`——被调用者的 `n += 1` 被丢掉了 | **NOT ENFORCED**——洞 1 |
| 29 | 同一个名字的两条 `extern c` 声明不能有冲突的原型（SPEC §12） | `hole_extern_conflicting_prototypes` | check | `check exit 0, ok`——第二条声明被接受，而调用点对着最后落地的那一条解析 | **NOT ENFORCED**——洞 5 |

## 2. 什么*不是*洞

有两件事看起来像洞而并不是，它们被写下来，是为了没有人去"修"它们：

* **`1 + 2.5` 被拒绝，但对 `def wants(f: float)` 的 `wants(3)` 被接受。** SPEC §3.1
  一字不差地这么说（"An ordinary Vela call is not type-checked either"），并且解释了
  为什么运算符表和 C 边界是宽度承重的两个地方。代价在表的第 24 条里被实测：发出的 C
  做拓宽，解释器不做，所以同一份源码回答 `3.000000` 和 `3`。它是一处有用例的缺口，
  不是一条带着 bug 的承诺。
* **`a[i] = a[i - 1] + 1` 被拒绝，而 `out[i] = out[i] + 1` 被接受。** 这条规则管的是
  一个迭代可以*读*被循环体*写入*的那个数组的哪个元素——读你正要写的那个元素，正是
  Rust 里 `par_iter_mut` 给你的东西，而拒绝它会弄坏 `bench/matmul.vel`。


## 3. 洞：什么被关闭了，什么仍然开着

这一节里的每一样东西，都是一条被实测出编译器破坏了、或者被实测出编译器不再破坏了的
承诺。按每一个洞能产生的失败排序，最坏的在前：**静默的错误答案**，然后是**错了但
响亮**，然后是**一个漏掉的诊断**。每一条断言所对着实测的那个编译器，就是本文件顶部
点名的那个（`AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0`, `795136
bytes`），它由不带参数的 `tools/safety.ps1` 冻结。

### 3.1 在这次会话里被关闭：运算符表的一元和比较那一半

这些用例里有七个曾经是洞；它们现在是 `CLOSED`，而它们留在语料里作为对这条承诺的看守，
而不是被删掉。

**这条承诺：** SPEC §3.1，*"No implicit conversion between the types a rule can
compare."*（"在一条规则能比较的类型之间没有隐式转换。"）`DESIGN.md` §7.5 一字不差地承认了
这个缺口——"the operator table is checked for binary operators, not yet for unary ones or
comparisons (`-x` on a string, `"a" < 1`)"（"运算符表是按二元运算符检查的，还没有检查一元
运算符或比较（`-x` 作用于字符串、`"a" < 1`）"）——而那句话现在已经是历史。

**由谁关闭：** 提交 `bfd70fc`（运算符表），用上面点名的那个编译器实测。**`holes CLOSED`
一列里的行：** `hole_cmp_bool_bool`、`hole_cmp_bool_int`、`hole_cmp_int_float`、
`hole_cmp_str_int`、`hole_unary_neg_bool`、`hole_unary_neg_str`、`hole_unary_not_int`。

这就是展示出差别的那一对运行——同样的 69 个用例、同样的 55 个非洞行、两个构建，而这
七行是唯一移动过的东西：

```
$ ... -FrozenVm %TEMP%\vela-safety\frozen-pin\vm.exe        # 67ACF63B…903D8A / 770560
hole_cmp_bool_bool                             violation  FAIL-as-expected
hole_cmp_bool_int                              violation  FAIL-as-expected
hole_cmp_int_float                             violation  FAIL-as-expected
hole_cmp_str_int                               violation  FAIL-as-expected
hole_unary_neg_bool                            violation  FAIL-as-expected
hole_unary_neg_str                             violation  FAIL-as-expected
hole_unary_not_int                             violation  FAIL-as-expected
tally           : 55 passed, 0 failed
                  xfail rows still showing their violation: 14
                  holes CLOSED (the promise now holds): 0

$ powershell -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1   # AD4A997B…73D0 / 795136
hole_cmp_bool_bool                             violation  CLOSED
hole_cmp_bool_int                              violation  CLOSED
hole_cmp_int_float                             violation  CLOSED
hole_cmp_str_int                               violation  CLOSED
hole_unary_neg_bool                            violation  CLOSED
hole_unary_neg_str                             violation  CLOSED
hole_unary_not_int                             violation  CLOSED
tally           : 55 passed, 0 failed
                  xfail rows still showing their violation: 3
                  holes CLOSED (the promise now holds): 7
```

（那四行统计是 65 个用例的语料、以及当时那天的统计措辞；套件现在打印的是本文件顶部展示
的四列形式，而四个成员访问行是在那之后加上去的，这就是为什么同样的信息读作
`violations still open: 11 / CLOSED: 0` 和 `open: 4 / CLOSED: 7 / other: 2 /
DELIBERATE: 1`。那七行运算符行对两者都没有改变——当前的运行见 §3.3。）

检查器现在打印什么——并且注意，这些消息正是这份语料的清单在修正之前作为*期望的*拒绝
手写下来的那些，而这正是事先把它们写下来的全部意义：

```
$ vm.exe check tests\safety\cases\hole_cmp_bool_int.vel
exit=2   vela: type error: operator '<' mixes bool and int

$ vm.exe check tests\safety\cases\hole_unary_not_int.vel
exit=2   vela: type error: operator 'not' cannot be applied to int
```

修正之前（`67ACF63B…903D8A`），同一份源码被接受，然后从一个前端到另一个前端给出了
不同的回答——留在这里，作为产出这些用例的那次实测：

```
> vm.exe check hole_cmp_bool_int.vel          -> exit=0, stdout=[ok]
> vm.exe run   hole_cmp_bool_int.vel          -> exit=2, vela: panic: a bool can only be compared with == and !=
> vm.exe build hole_cmp_bool_int.vel          -> exit=0
> hole_cmp_bool_int.exe                       -> exit=0, stdout: False     (the program is `print(True < 1)`)
```

现在那些行读作 `check exit 2`，而且什么都不构建：
`native not built (the checker refuses the program, which is the promise)`。这套件的第
一版无论如何都会构建它们，并报告 `the program must compile, but build exited 2`——一个
被关闭的洞被装扮成一次失败，也正是这个 bug 让语料对这次修正视而不见。

### 3.2 在这次会话里被关闭：成员访问

**这条承诺：** SPEC §4，结构体形式 `struct S { field: T ... def m(self: S, ...)
-> R { ... } }`——一个结构体的字段和方法就是它声明的那些，所以 `p.zzz`
和 `p.dotq(q)` 什么都没命名。在 `099f6fc` 之前，检查器根本不看成员名字。

**由谁关闭：** 提交 `099f6fc`，用同一个编译器实测
（`AD4A997B…73D0` / `795136 bytes`）。现在两半都被行看着，而它在 `check` 之上产生的
那些拒绝，是由手写的探针而不是由清单里的行覆盖的——它们是这条规则的*正面*，为它们
准备的用例属于一份后续的语料：

```
$ vm.exe check m1_bad_field.vel        # print(p.zzz)
exit=2   vela: type error: Pt has no field 'zzz'

$ vm.exe check m2_bad_method.vel       # print(p.dotq(q))
exit=2   vela: type error: Pt has no method 'dotq'

$ vm.exe check m3_self_bad.vel         # inside the method: return self.zzz
exit=2   vela: type error: Pt has no field 'zzz'
```

第三行是这一类规则通常会漏掉的那一半——结构体自己函数体里的 `self.`——而它被覆盖了。

**它够不到的东西——现在是这份语料里的四行。** 其中三个是 `violation` 用例，它们的
`check` 那一侧断言这条规则欠下的那次拒绝，所以它们今天报告 `FAIL-as-expected`，而规则
够到它们的那一天它们会报告 `CLOSED`，不需要任何人去改清单。第四个（`p.sum`）是一个
`deliberate` 行，因为它的被接受是一个决定而不是一个缺口——见下文。它们的名字，以及
今天*转而*把程序停下来的是什么（那从来不是检查器，而且在任何情况下都没有被观察到产出
一个错误答案）：

| 接收者 | 用例 | `check` 今天 | 转而把程序停下来的是什么 |
|---|---|---|---|
| 一个**嵌套字段**（`o.inner.zzz`） | `hole_member_nested_receiver` | `exit=0  ok` | `build` -> `vela: panic: this struct has no field of that name`；`run` -> exit 2 |
| 方法**参数个数**（在一个单参数方法上的 `p.dot(p, q, p)`） | `hole_member_method_arity` | `exit=0  ok` | `build` -> 宿主的 C 编译器：`error C2197: 'int64_t vl_Pt__dot(vl_Pt,vl_Pt)': too many arguments for call`；解释器把同一个错误说成 `wrong number of arguments for this method`，exit 2 |
| 一个在**非结构体**上被调用的方法（`int n` 的 `n.foo()`） | `hole_member_non_struct_receiver` | `exit=0  ok` | `build` -> `vela: panic: this struct has no method of that name`；解释器说 `only a struct has methods`，exit 2 |
| 一个**被当作值来读**的方法名（`p.sum`） | `hole_member_method_as_value` *（一个 `deliberate` 行，不是 `violation`）* | `exit=0  ok` | `build` -> `vela: panic: this struct has no field of that name`；`run` -> exit 2 |

对这个缺口被报告给我时的方式，有两处更正，两处都朝着这个二进制实际所做的事情：

* **调用结果**接收者*是*被抓住的——`make().zzz` 给出 `check exit 2, vela:
  type error: cannot read a field from a temporary struct`——所以它既不是一个行，也不是
  一次够不到；
* **数组元素**接收者也被抓住了，但抓住它的是后端"structs are not implemented in this
  slice"那次拒绝，而不是某条成员规则。那是与"检查器本该拒绝而它没有"不同的事实，所以
  它有意**不**成为一个行；它改在 §4 里。

`p.sum`（把一个方法名当作值来读）是一个决定而不是一次疏忽，而它的行是一个
**`deliberate`** 行——不是 `violation`。这就是全部要点：另外三种成员形状是这条规则
还没有够到的承诺（等它够到了，它们会报告 `CLOSED`），而这一个承诺的是*反面*——即
`check` 会接受，因为检查器被记录为放它通过。所以一个 `deliberate` 行在检查器哪一天
开始拒绝它时会失败（`FAIL`，exit 1：被记录下来的决定变了，而陈述它的那个文件必须被
重读），这正是让它不至于成为一个没有人跑的决定的东西。在这次会话里两个方向都实测过：

```
# as it stands
hole_member_method_as_value   deliberate DELIBERATE
    actual: check exit=0 stdout="ok" stderr= || build exit=2 (the back end refuses it)
            vela: panic: this struct has no field of that name || run exit=2

# the same row with its expectation flipped (check_exit 2), to prove DELIBERATE can fail
hole_member_method_as_value   deliberate FAIL
    reason: a DELIBERATE row is no longer accepted: the row requires check exit 2 and
            stdout "ok", but check gave exit 0 stdout "ok" — the documented decision
            has changed, so the file that states it has to change too
harness exit = 1 / RESULT: FAIL
```

它在 `tests/safety/manifest.txt` 里的 `note` 在那一行本身里说着同样的话，而不只是在这里。

### 3.3 仍然开着（4 个），以及一个开着但不是 violation 的行

在当前的构建之下，统计读作 `violations still open (FAIL-as-expected): 4` 和
`other xfail rows failing as recorded: 2`——这是为这一修订重新实测的，不是假定的。那四个
开着的 `violation` 行是一条承诺加三种成员形状：`bounds_zero_len_array_constant`
（下面 §3.3）加上 `hole_member_nested_receiver`、`hole_member_method_arity`、
`hole_member_non_struct_receiver`（§3.2 那张表；第四种成员形状 `p.sum` 是那个
`deliberate` 行，报告 `DELIBERATE`）。

那两个 `other xfail` 行，是那些期望只是*已知被破坏*、没有"这条规则被守住了吗？"这个
问题要回答的行：`hole_mut_scalar_parameter` 和 `hole_extern_conflicting_prototypes`
——它们报告 `FAIL-expected`，而编译器开始做那一行所说的事情的那一天，它们会报告
`UNEXPECTED-PASS`。

**洞 1——标量参数上的 `mut` 不会写穿，而两个前端在错误答案上一致。**
`DESIGN.md` §7.5 已经点名了这一个；用例是
`tests/safety/cases/hole_mut_scalar_parameter.vel`。这是三个里最宽的一个，因为一个用到
它的程序会编译、会运行、exit 0，而它干脆就不是被写下来的那个程序——与那些已关闭的洞
不同，这里没有第二种意见：解释器同意那个错误答案，于是工具链里没有任何东西提出异议。

```
def bump(mut n: int) -> None { n += 1 }
def main() -> None { mut x: int = 10  bump(x)  print(x) }

> vm.exe check hole_mut_scalar_parameter.vel            -> exit=0, ok
> vm.exe build hole_mut_scalar_parameter.vel && hole_mut_scalar_parameter.exe
exit=0   stdout: 10
> vm.exe run   hole_mut_scalar_parameter.vel
exit=0   stdout: 10
```

承诺是 `11`（SPEC §3.2："`mut` before a parameter means the callee may write through
it"（"参数前的 `mut` 意味着被调用者可以写穿它"））。发出的 C 正好展示出它是在哪里
丢掉的：

```
static void vl_bump(int64_t);                     <- arguments are by value
static void vl_bump(int64_t vl_n) {               <- the callee's own copy
int64_t vl_x = 10LL;
(void)(vl_bump(vl_x));                            <- the caller's x is untouched
```

两个对照是 `probe_mut_struct_parameter`（`11`，写穿）和 `probe_mut_array_parameter`
（`99`，写穿），所以这件事是标量特有的——一个 `mut` 标量参数是一个语言接受、随后忽略
的标注。

**洞 2——一个绑定在编译后的程序里收窄，在解释器里不收窄**
（`hole_narrowing_binding_i32`、`hole_narrowing_binding_u8`，模式 `diverge`；那些行
PASS，因为那次分歧*就是*断言）：

```
> vm.exe check hole_narrowing_binding_i32.vel   -> exit=0
> compiled: 705032704        (mut x: i32 = 5000000000)
> vm.exe run: 5000000000
> compiled: 44               (mut x: u8 = 300)
> vm.exe run: 300
```

SPEC §3.1 把这一点作为一处有意的停点指出（"A binding is not type-checked, and that is
where the width question stops"（"绑定不做类型检查，宽度的问题就停在那里"）），所以那条
*规则*是有记录的；哪里都没有记录的是，同一份源码编译后回答 `705032704`、解释执行回答
`5000000000`。那两个 `diverge` 行断言这两个数字，所以这件事无法漂移：如果编译器哪一天
不再收窄，或者解释器开始收窄，用例会大声失败。

**洞 3——一个冲突的 `extern c` 原型被接受，而最后一条声明获胜。**
`tests/safety/cases/hole_extern_conflicting_prototypes.vel` 用不同的签名声明了
`host_add` 两次。没有任何东西拒绝它：

```
> vm.exe check hole_extern_conflicting_prototypes.vel
exit=0   stdout: ok
```

给这个文件一次调用，发出的 C 就会带上*两条*声明，而这次调用对着第二条解析：

```
> vm.exe emit-c ext_conflict_call.vel
  int64_t host_add(int64_t, int64_t);
  double  host_add(double, double);
  vela_print_f64(host_add(1.0, 2.0));
> vm.exe build ext_conflict_call.vel
exit=2
  error LNK2019: unresolved external symbol host_add referenced in function main
  error LNK1120: 1 unresolved externals
  vela: panic: build: no C compiler on this host could build the emitted C
```

这是响亮的（错了但响亮，不是静默地错），因为 C 编译器抓住了它，但要注意这次失败*落在
哪里*：在链接时，用的是宿主工具链的话，而它对着的是一个 Vela 自己的检查器说没问题的
程序——而 SPEC §12 那条承诺（"the argument kinds are checked against the
declaration"）已经静默地变成了"对着最后到达的那条声明检查"，那不是同一条承诺。同一个
名字的两条声明，是一个外来接口错误可能有的最小版本，而检查器对它无话可说。

**洞 4——零长度数组上的一个常量下标被接受。**
SPEC §6.7："A constant index that is provably out of range is a compile error."（"一个
可证明落在范围之外的常量下标是一个编译错误。"）`Array[int, 0]` 上的 `a[0]` 可证明落在
范围之外，而它被接受。原因就在 `selfhost/parts/check.vel`（`ck_index`）里的一行：

```vela
ln: int = unpack_len(ck)
if ln <= 0 {
    return          # <-- the constant-index rule does not run for a length of 0
}
```

```
> vm.exe check bounds_zero_len_array_constant.vel
exit=0   stdout=[ok]
> vm.exe emit-c bounds_zero_len_array_constant.vel | select-string bounds_check
  vela_print_i64((vela_bounds_check((vl_ix_6 = 0LL), 0, "<file>", 3), vl_a[vl_ix_6]));
> vm.exe run bounds_zero_len_array_constant.vel        -> exit=2, index out of range for this array
> compiled program                                      -> exit=2, index 0 out of range for array of length 0
```

排在最后，因为它不产生任何错误答案：运行期检查先开火，两个前端都把程序停下来。它是一
次没有发生的编译错误——而那正是常量下标规则存在的唯一理由——所以"可证明落在范围之外
就是编译错误"这条承诺，比规范那句话读起来要窄。

**洞 5——`DESIGN.md` §7.5 承认的那个 `parallel for` 证明不完整：一个外层循环的变量不在
区间环境里。** 它不被算在上面那三个里，因为套件把这个形状报告为 `PASS` 而不是一次
violation：它接纳的代码被实测出与解释器*一致*。

* `concurrency_read_any_index_legal` 和 `concurrency_same_index_legal` 是这个证明存在
  的目的所保住的形状，而它们仍然*带着* pragma 编译
  （`#pragma omp parallel for` 在场，两个前端都给出 `14` 和 `8`）。
* `concurrency_nested_outer_read_accepted` 是代码里够得到的那个被接纳的收窄：
  `a[i + j]`，其中 `i` 来自一个*外层*循环。这个循环被接纳，pragma 被发出（`emit-c` ->
  1 行 `#pragma omp parallel for`）。对编译后的程序实测 20 次运行：每次都给出 `28`，
  与解释器相同。**所以这次收窄在检查器里是真的，只是在这个程序上无法被复现成一个错误
  答案**——外层循环的更新发生在两个并行区之间，而 MSVC 的 OpenMP 静态调度恰好保住了
  那个顺序。为不安全的方向写过一个用例（`out[j] = a[i + j]`，读依赖外层下标），而编译
  后的程序二十次里二十次都打印 `28`；没有实测到的错误答案可以报告，这里也不会发明一个。
* 把它说成一次漏掉的证明而不是一次竞态：写的那一侧仍然是可靠的，因为一次无法安放的写
  是被*拒绝*的（`concurrency_parallel_outer_rowmajor_refused`、
  `concurrency_serial_outer_parallel_inner` 两者都以 `cannot prove that 'parallel for'
  writes 'out' to distinct elements` 拒绝），所以环境浅的代价是拒绝，而不是被接纳的
  竞态。这就是那个发现：缺口在*接纳*那一侧，而它只有在一个未来的写证明依赖一个环境
  没有的外层循环区间时，才可能变成一个错误答案。

### 3.4 `DESIGN.md` §7.5 指向的那两个探针

`tests/probes/mut_scalar_parameter.vel` 和 `mut_struct_parameter.vel` 确实存在
（`Get-ChildItem tests\probes -Name -Filter 'mut*'` 两个都返回，而 `check.vel` 第 1346 行
点名了它们）。这个仓库没有的，是任何套件里一个会注意到行为改变了的用例：它们是探针
文件夹的输入，而不是语料里的行，而 `DESIGN.md` §7.5 把那个缺口用散文记在了
"what stage 4 left"（"stage 4 留下了什么"）之下。这份语料加上了那三行
（`hole_mut_scalar_parameter`、`probe_mut_struct_parameter`、
`probe_mut_array_parameter`），所以如果洞 1 哪一天被关上，是用例停止报告一次 violation、
这个文件被更正，而不是反过来。运算符表现在也是这样：那七行 `hole_*` 是对 `bfd70fc` 的
看守（§3.1），而 §3.2 的四个成员行是对 `099f6fc` 那些够不到的形状的看守——在那次修正
之后加上：三个报告 `FAIL-as-expected` 直到规则够到它们，一个（`p.sum`）是 `deliberate`
行，在检查器不再放它通过的那一天失败。

## 4. 这份语料没有覆盖的东西

- **有一种成员访问形状是被后端拒绝的，而不是被某条规则拒绝的。** 一个作为数组元素的
  接收者（`a[0].zzz`）被 `check` 接受（`exit=0, ok`），然后被后端的切片限制停下来：
  `build` -> `vela: panic: structs are not implemented in this slice of the back end`，
  `run` -> exit 2。它有意不是一个 `violation` 行：把它记成"检查器本该拒绝"，会把成员
  规则和一个后端限制合并在一起，而这两个事实有不同的归属者。它的探针住在
  `%TEMP%\vela-safety\member\`，在仓库之外。
- **没有任何 `parallel for` 竞态被复现成一个错误答案。** §3.3 的洞 5 形状被检查器接纳，
  并在 20/20 次运行里产出正确答案；最有力的诚实陈述就是上面那一句，而一台有不同
  OpenMP 调度或线程数的机器当时不可用，无法去试着弄坏它。
- **这份语料是独立的。** 它没有接到 `tests/cases.txt` 里，`tests/run_tests.vel` 也不跑
  它；`tools/safety.ps1` 是唯一的运行者。（清单的接线是 leader 的决定，而
  `tests/cases.txt` 在这一轮里是越界的。）
- **两条 `extern c` 声明是唯一被试过的 FFI 洞。** 关于 `extern c` + `parallel for`，或者
  关于一次声明在其调用点之后才被加上的外部调用，什么都没有被测过。
- **数字会动。** 编译器在这次会话里被反复重建；顶部的哈希是这里每一个数字所对着实测的
  那个构建，被钉住的更旧构建是让那些已关闭的洞变得可见的东西，而 `tools/safety.ps1`
  会打印它实际跑的那个东西的哈希，当两者都不是时还会附一条响亮的注记。
