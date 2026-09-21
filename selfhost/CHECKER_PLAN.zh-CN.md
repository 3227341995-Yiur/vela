# 把语义检查器移植到 Vela —— 实现清单

[English](CHECKER_PLAN.md) | **简体中文**

<!--
源文件 : CHECKER_PLAN.md
源文件字节 : 87655
源文件 SHA256 : 97f1d58fb3c99ba27e0a1d8bc36b1b8aaf8a41a0746f170ee4254d4c1f63c815
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

> **状态：已移植。**  `selfhost/parts/check.vel` 承载了本计划中的各项判定。为这次移植
> 背书的证据是 `tests/diff_check.py`——在 stage 0 仍然存在的时候，它把树中*每一个程
> 序、以及每一个 reject 用例*同时交给两个检查器：**70/70 判定一致，其中 42 条消息逐
> 字节相同，判定差异 0 处**。那套测试脚本、它的参考实现 `vela/checker.py`，以及它们
> 所属的 Python 前端，都在 stage 4 中被删除了，所以**这项一致性已经无法重跑，也无法
> 从当前这棵树复现**——本文件引用的行号指向一个已不存在的文件。如今维系这些判定的是
> `tests/golden/<name>.err`，它是在两个检查器都还在的时候记录下来的。本文件写成之后
> 才落地的内容是
> `parallel for` 的证明及它所需的区间算术（`Iv`、
> `ck_iv_*`、`ck_linear`、`ck_match_linear`、`ck_rowmajor`，以及 `ck_p*` 的遍历）、
> 运算符表（`ck_arith`/`ck_etype`，正是它让“没有隐式转换”成为一条规则）、整数字面量
> 溢出（放在词法分析器里，因为那里数字还存在）、`float division by constant zero`
> （作为字面量上的一个标记，因为值存放在检查阶段拿不到的一个池子里）、“不能修改”的
> augassign 写法，以及**诊断的形状**——`vela: <kind>:
> <message>`，位置在下一行——没有它，工具就分不清语法错误和安全错误。仍然存在的收窄
> 之处列在
> `DESIGN.md` §7.5；这里值得点名的是三条：区间环境只包含并行体*内部*的循环，不包含
> 它嵌套于其中的循环；只有当非纯方法的接收者是裸名字时才会被抓到；以及 `ck_etype`
> 对调用结果回答“unknown”。

事实依据：`vela/checker.py`（1637 行，全文读过）、`vela/analysis.py`
（251 行）、`vela/types.py`（205 行）、`vela/codegen.py`（765 行）、`vela/nodes.py`（234 行）、
`vela/parser.py`（660 行）、`vela/errors.py`，外加 `SPEC.md` §3–§9 与 `DESIGN.md`
§3、§7.1–§7.4。下文的行号是该文件真实的、从 1 开始的行号。

**上面每一个 `vela/…` 路径，以及下面每一条 `…:NNN` 引用，都指向本计划写作时那个
Python 前端。stage 4 删掉了那棵树，所以这些引用再也无法追查——保留它们是因为它们记录
了每条规则当时是从哪里读出来的，而 `selfhost/parts/check.vel` 就是它们最终变成的
实现。**

范围：这是一份**规格说明**，不是代码。消息文本按原文逐字引用（f-string 占位符写作
`{...}`）；每一条都必须逐字节复现——过去是靠 `tests/run_tests.py` 的子串匹配来强制这
一点，现在靠 `tests/golden/<name>.err`。

---

## 0. 执行摘要

* 检查器对模块**走两遍**：阶段 1 构建类型/签名表（`collect_structs`、
  `collect_signatures`），阶段 2 对每个函数体做类型检查（`check_function`）。因此阶段
  1 的错误优先于任何函数体错误，而按源码顺序靠前的那个函数体里的错误，会抢先于靠后
  函数体里的错误。
* **永远只报告第一个错误**——每条规则都立即抛出（`checker.py:182-186`、
  `errors.py:25-35`）。`ide/services.py:379-404` 把那个异常转成只有一个元素的诊断列表。
  Vela 的移植继承这一点：它*不是*多诊断检查器，实践中也没有恢复/污染（poison）路径
  （`checker.py:670-671`、`1215-1216`、
  `1137` 处的 `ErrorT` 机制是死代码，因为 `check_expr` 只有在错误已经被抛出之后才会返
  回 `ErrorT`）。
* **126 处不同的拒绝点：恰好 93 处 `TypeErr`（“type error”）与恰好
  33 处 `SafetyErr`（“safety error”）**——按抛出点统计；§1 的清单也同样是这 126 条，
  编号为 1-59 与 76-145——60-75 这段空缺的存在只是为了在 §1.12 中让并行规则按执行顺序
  排在函数体检查之后。两处 `range() step must not be zero` 是彼此不同的规则 57/58。二
  者都是硬错误；检查器中**任何地方都没有警告**。错误*类型*字符串是渲染出的消息的一部
  分
  （`filename:line:col: type error: …` / `… safety error: …`），所以 `SafetyErr`
  还是 `TypeErr` 的选择必须精确保留——`tests/run_tests.py` 比较的是 `ex.render()` 的子
  串，而 `run_ide_tests.py` 断言的是 `kind`。
* 检查器还会**注解 AST**，而这就是它与后端的全部接口（§4）：每个表达式的类型、
  `need_bounds`、`need_overflow`、
  `const_value`、`array_size`，外加 `Program` 上的六个计数器。

---

## 1. 规则清单

下面的顺序按执行为准。阶段 1 的检查顺序恰好是：按源码顺序逐个顶层语句 →
`collect_structs`（先所有 struct，再它们的所有字段）→ `collect_signatures`（先所有自
由 def，再所有方法）。阶段 2 随后按源码顺序遍历 `mod.body`：`FuncDef` 在它出现的位
置被检查，而 `StructDef` 只贡献**它的方法**——所以**每个自由函数的函数体都在任何方法
体之前被检查**，不论它们在文件里的先后（`checker.py:345-364`）。最后是 `main` 入口的
要求。

图例：**T** = `TypeErr`（type error），**S** = `SafetyErr`（safety error）。

### 1.1 类型与类型语法（`resolve_type`，`checker.py:366-389`）

`TYPE_NAMES` = `int i32 u8 float bool str None`（`checker.py:51-54`）。

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 1 | `Array` 必须带一个方括号内的元素类型 | `Array needs an element type: Array[T, N]` | T | 371 |
| 2 | `Array` 必须带一个编译期长度 | `Array needs a compile-time length: Array[T, N]` | T | 373 |
| 3 | `Array` 长度必须 > 0 | `Array length must be positive, got {te.size}` | T | 376 |
| 4 | `Array` 长度必须 ≤ 2^40 | `Array length {te.size} is absurdly large` | T | 378 |
| 5 | 数组元素类型不可以是 `None`/错误类型 | `Array element type may not be {elem}` | T | 381 |
| 6 | 内建标量名不可以带类型实参 | `'{te.name}' does not take type arguments` | T | 386 |
| 7 | 未知类型名 | `unknown type '{te.name}'` | T | 387 |
| 8 | 无尺寸的 `Array[T]` 由 (2) 拒绝，尽管解析器接受它（`parser.py:438-445`） | — | — | — |

注意：*struct* 名只有在不在 `TYPE_NAMES` 里时才会压过内建名（checker 367 先于 383），
而 `collect_structs` 在签名之前运行，所以名为 `int` 的 struct 是不可达的。

### 1.2 Struct 声明（`collect_structs`，391-418）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 9 | 不允许两个同名 struct | `duplicate struct '{st.name}'` | T | 397 |
| 10 | 字段不可以是 `None`/错误类型 | `field '{f.name}' may not be {ft}` | T | 407 |
| 11 | 字段不可以是数组 | `field '{f.name}' may not be an array` | T | 409 |

关于 (11) 的提示：*“struct 是按值复制的，而数组的存储属于声明它的那个函数；请把数组放
在局部变量里”*。
副作用：`st.resolved_fields: List[(name, VType)]`、`st.vtype = StructT`、
`StructT.defn = st`（`types.py:128-130`）。重复字段**不是**检查器规则——它是解析器错误
（`parser.py:262-265`，`duplicate field '…' in
struct …`），因此 `tests/run_tests.py` 的 `duplicate_field` 根本走不到检查器。struct 体
中出现既非字段也非方法的语句、以及没有任何字段的 struct，同样是解析器错误
（`parser.py:270-275`）。

### 1.3 函数与方法的签名（`collect_signatures`、`_make_sig`，420-466）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 12 | 不允许两个同名自由函数 | `duplicate function '{st.name}'` | T | 426 |
| 13 | 不允许同一 struct 的两个同名方法 | `duplicate method '{m.name}' in struct {st.name}` | T | 435-436 |
| 14 | 方法名不可以等于自由函数名 | `method '{m.name}' collides with a free function` | T | 438-439 |
| 15 | 不允许重复的参数名 | `duplicate parameter '{p.name}'` | T | 447 |
| 16 | 每个参数类型都必须能解析（且不是 `None`） | `parameter '{p.name}' has an invalid type` | T | 451 |
| 17 | 方法的第一个参数必须名为 `self` | `method '{fn.name}' must take 'self' as its first parameter` | T | 457-459 |
| 18 | 那个 `self` 必须被标注为所属 struct | `'self' must be typed {struct_name}, got {params[0][1]}` | T | 462-463 |

关于 (17) 的提示：`write: def {fn.name}(self: {struct_name}, ...) -> T:`。
逐个参数的 `p.vtype` 在这里写入；`fn.ret_vtype` 在 464 写入。
由于 (14) 是在按源码顺序遍历 struct 时检查的，一个*方法*只有在那个自由函数更早于循环
中被登记时才能遮蔽它。

### 1.4 `main` 入口要求（`run`，355-363）——最后检查的东西

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 19 | 必须存在一个顶层 `def main` | `no 'main' function` | T | 356 |
| 20 | `main` 不接受参数 | `'main' must take no parameters` | T | 361 |
| 21 | `main` 必须声明为 `-> None` | `'main' must be declared '-> None'` | T | 363 |

(19) 只有在每个函数体都检查完之后才会抛出；(20)/(21) 指向
`mod.body[0]`——也就是**第一个顶层语句**，而不是 `main` 自身（一个刻意的怪癖；如果逐
字节相同的诊断很重要，移植时不要“修好”它）。(19) 的提示：*“每个 Vela 程序都需要
'def main() -> None:'”*。

### 1.5 逐函数的入口检查（`check_function`，470-506）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 22 | 函数完全不能返回 `Array` 类型 | `function '{fn.name}' returns {sig.ret}` | S | 479-480 |
| 23 | 当 `ret != None` 时，每条控制流路径都必须返回 | `function '{fn.name}' may finish without returning {sig.ret}` | T | 495-497 |
| 24 | `-> None` 函数不可以 `return e` | `this function is declared '-> None' but returns a value` | T | 502-503 |

关于 (22) 的提示：*“数组活在被调用者的 arena 帧里，返回时会变成悬垂；改为把一个输出
数组以 'mut' 参数传进来”*（`return_own_array` 检查的就是子串 “would dangle”）。
关于 (23) 的提示：*“Vela 要求每一条控制流路径上都有返回”*。

`always_returns`（519-529）在以下情况返回成功：语句列表中任意位置出现
`Return`（即使它之后还有其他语句）、一个 **`orelse` 非空**且两个分支都总是返回的
`If`、以及函数体（只递归穿过 `If` 体——`_contains_break`，535-542）中不含
`break` 的 `while True`。它**不**考虑 `for` 循环、嵌套 `while` 里的 `break`，也不考虑
`pass`。注意 (24) 是对同一函数体的*第二*遍遍历（`walk_returns`，508-517，会进入
`If`/`While`/`ForRange`/`ForIter` 的函数体，但不进入 `for` 内部嵌套 `if` 的
`orelse`），所以一个带早退 `return 3` 的 `-> None` 函数，只有在整个函数体都类型检查完
之后，才会报告 (23) 的近亲。

作用域：每个函数一次 `push()`，参数在 `fn.line` 处声明为
`is_param=True`（`is_mut` 来自标注）；结束时 `pop()`。`current_ret`、
`current_fn`、`in_pure`、`current_struct` 在这里被设置/清除（473-476、
504-506）。

### 1.6 语句（`check_stmt`，550-602）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 25 | 表达式语句必须是 `Call` | `this expression statement has no effect` | T | 559-560 |
| 26 | `if` 条件必须是 `bool` | `'if' condition must be bool, got {ct}` | T | 567-568 |
| 27 | `while` 条件必须是 `bool` | `'while' condition must be bool, got {ct}` | T | 579-580 |
| 28 | 嵌套 `def` 被拒绝 | `nested functions are not supported` | T | 594-595 |
| 29 | 块里的 `struct` 被拒绝 | `struct definitions must be at the top level` | T | 600 |
| 30 | 未知的语句节点种类 | `unsupported statement {type(s).__name__}` | T | 602 |

提示：(25) *“这里只有调用可以用作语句；请把值赋给变量，或者写 'x += 1' 而不是 'x +
1'”*；(26) *“Vela 没有真值性（truthiness）：请写 'if
x != 0:'”*；(27) *“Vela 没有真值性：请写 'while i < n:'”*；(28) *“Vela
没有闭包；请把它提升到顶层，并把它需要的东西作为参数传入”*。
`Break`/`Continue`/`Pass` 被接受，**完全没有检查器规则**——
循环外的 `break`/`continue` 是解析器错误（`parser.py:190-198`）。
(30) 通过当前解析器不可达（它的节点集合是封闭的）。
`ExprStmt.vtype` 被设置（557）。

### 1.7 `return`（`check_return`，604-618）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 31 | 在声明了返回类型的函数里裸 `return` | `this function must return {self.current_ret}` | T | 607-608 |
| 32 | 在 `-> None` 函数里 `return e` | `this function is declared '-> None', so it cannot return a value` | T | 611-614 |
| 33 | 返回表达式的类型必须等于声明的返回类型 | `returning {t}, but the function is declared -> {self.current_ret}` | T | 617-618 |

关于 (32) 的提示：*“如果它确实产生一个值，请把签名改成 '-> T'”*。
(33) 会先调用 `check_expr(value, expected=current_ret)`，所以*字面量*返回可能会被静默
地重新定类型（§2.3），只有无法适配的不匹配才会走到 (33)；一个放不下、也不适配的字面量
改由规则 81 报告 `expected {T}, got
{t}`。

### 1.8 声明与作用域（`check_bind`，631-715；`declare` 208-215）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 34 | 无效的赋值目标节点 | `invalid assignment target` | T | 629 |
| 35 | 新绑定需要类型或初始化式 | `'{name}' has no type and no initialiser` | T | 639 |
| 36 | 只有数组可以不写初始化式就声明 | `'{name}' is declared without an initialiser` | T | 644-646 |
| 37 | 数组字面量不可以长于声明的数组 | `'{name}' is declared {declared} but the literal has {len(elems)} elements` | T | 659-661 |
| 38 | 绑定的类型不可以是 `None` | `'{name}' cannot have type None` | T | 669 |
| 39 | 初始化式类型必须等于声明的类型 | `'{name}' is declared {declared} but initialised with {vt}` | T | 674-676 |
| 40 | 不允许重新声明已在作用域中的名字 | `'{name}' is already declared in this scope` | T | 686-688 |
| 41 | （不可达）对已存在名字的 `mut` 重复标注 | `'{name}' is already declared` | T | 704 |
| 42 | 以不同类型重新声明 | `'{name}' has type {existing.type}, not {declared}` | T | 709-710 |
| 43 | 对已有绑定赋值必须匹配它的类型 | `cannot assign {t} to '{name}' of type {existing.type}` | T | 712-713 |
| 44 | **safety**：同一作用域内同名绑定的第二次出现 | `'{info.name}' is already declared in this scope` | S | 211-214 |
| 45 | **safety**：对不可变绑定赋值 | `cannot assign to '{name}': it was declared immutable` | S | 690-694 |
| 46 | **safety**：数组名永远不可以被重新指向 | `cannot rebind array '{name}'` | S | 699-702 |

提示：(36) *“只有数组可以裸声明；它们会被零填充。标量必须初始化。”*；(40) *“Vela
没有隐式遮蔽”*；(44) *“Vela 禁止在同一作用域内把名字重新绑定到另一种含义；请换一个
新名字”*；(45) *“如果你确实想改它，就声明为 'mut {name}: {existing.type} = ...'”*；
(46) *“数组在 Vela 中具有值语义，但不可重新绑定；请用循环逐元素复制”*。

对移植重要的细节：

* 新绑定：如果 `declared_type` 存在，它会被解析（规则 1-7 的错误在这里触发）；如果值是
  一个 `ListLit` 且声明类型是 `ArrayT`，每个元素都以
  `expected=declared.elem` 检查，规则 37 生效，并记录
  `s.value.pad_to = declared.size`（662）——**`pad_to` 被写入，但 `codegen.py`
  从不读它**；短字面量的零填充是通过 arena 隐式发生的。当字面量比数组短时，*整个*字面
  量在 `check_listlit` 中保持类型 `Array[elem, len(elems)]`，但在 663 处被改写为声明类
  型，所以后端看到的是声明类型。
* 否则 `vt = check_expr(s.value, expected=declared)`；`s.vtype = vt`；
  规则 39 比较二者。
* `is_local_array = isinstance(vt, ArrayT) and s.is_mut`（677-679）——这就是
  `verify_parallel` 依赖的那个标志，所以**非 `mut` 数组、参数数组和数组字段在并行中都
  不可写**。
* 已有绑定：`is_decl`（语法强制要新建绑定）→ 规则 40；不是
  `mut` → 规则 45；数组类型 → 规则 46；`is_mut and declared_type` → 规则 41
  （在 40 之后不可达）；然后按*已有*类型检查右值（43），再做可选的重复标注比较
  （42）。注意对已有 `mut x` 写普通的 `x = 3` 会带着 `expected=existing.type`
  走到规则 43，整数字面量适配正是在那里生效的。
* `declare()` 抛的是规则 44，不是规则 40——两段不同的文本都很重要。
  在*外层*块中已存在的名字上做声明是合法的（跨块遮蔽允许；只有同作用域重新绑定被拒
  绝）。

### 1.9 下标存储与字段存储（731-750）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 47 | 只有数组可以被索引 | `cannot index into {bt}` | T | 735 |
| 48 | struct 没有字段 | `{bt} has no fields` | T | 746 |
| 49 | **safety**：通过非 `mut` 名字写数组元素 | `cannot write to elements of '{base.id}': the array is not mutable` | S | 757-760 |
| 50 | **safety**：通过非 `mut` 名字写 struct 字段 | `cannot modify '{base.id}': it is not mutable` | S | 761-763 |

提示：(49) `declare it as 'mut {base.id}: Array[...]'`；(50) `declare it as 'mut
{base.id}: {bt} = ...' or take it as a 'mut' parameter'`。
`_require_writable`（752-767）只在基名是作用域中能找到的 `Name` 时才触发；
`self.field = …` 会穿过 `Attribute` 递归到 `self` 这个名字，所以非 `mut self` 的方法不
能给字段赋值（规则 50）。下标存储还会运行 `_check_index`（规则 57/58），并设置
`s.vtype = bt.elem`（740）。

### 1.10 复合赋值（`check_augassign`，769-814）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 51 | 对未定义名字写 `x += 1` | `'{target.id}' is not defined` | T | 774 |
| 52 | `x += 1` 而 `x` 不是 `mut` | `cannot modify '{target.id}': it was declared immutable` | S | 776-779 |
| 53 | 带下标的目必须是一个数组 | `cannot index into {bt}` | T | 784 |
| 54 | 属性目必须是一个 struct | `{bt} has no fields` | T | 792 |
| 55 | 未知的复合赋值目 | `invalid augmented assignment target` | T | 796 |

关于 (52) 的提示：`use 'mut {target.id} = ...'`。
右值以 `expected=t` 检查，运算符走的是*同一个* `_arith_result`（规则 97-106），那么在一
个 `int` 上写 `a += f` 会报告
`operator '+=' mixes …` 吗？**不会**——传入的运算符字符串是基础运算符
（`s.op` 已被解析器去掉了 `=`，`parser.py:416`），所以消息读作
`operator '+' mixes int and float`。对于 `Name` 目，整数区间随后通过
`plan_op_check`（805-812）针对 `+ - *` 更新，其他情况则失效。对于 `Index`/`Attribute`
目，既不更新区间，**也永远不会写入 `need_overflow` 这个词**——
`codegen.augassign` 用的是
`getattr(s, "need_overflow", True)`，所以那里总是发出带检查的加法，且两边都不增加计数
器（`codegen.py:554-556`）。这种不对称必须复现：**`elided_overflow` + `emitted_overflow`
不等于程序中复合赋值的个数。**

### 1.11 `for` 循环（`range_interval` 818-841，`check_for_range` 843-876）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 56 | 起止值必须是 `int` | `range() bounds must be int` | T | 847 |
| 57 | 常量步长为零是编译错误 | `range() step must not be zero` | S | 853 |
| 58 | （重复路径）步长为零，也可经 `range_interval` 到达 | `range() step must not be zero` | S | 835 |
| 59 | 常量边界超出 int64 是编译错误 | `range bound overflows int` | S | 862 |

843-876 内部的顺序：先 `start` 后 `stop`，以 `expected=INT`
检查（不匹配会表现为规则 81 的 `expected int, got …`，*而不是* 56）；步长以
`expected=INT` 检查（849）；步长做常量折叠，`s.step_const` = 该常量或
`None`（851-856）；然后对 start/stop/step 中存在的每一个做 `const_int`
范围检查（59）。接着计算循环变量的区间，用 `loop_entry` 压入作用域，并把该变量声明为
`VarInfo(target, INT, is_mut=False)`（870）——**这正是
`mutate_loop_variable`（“declared immutable”）会触发的原因**。函数体在该作用域内检
查，`parallel` 在同一作用域内被验证（874），然后 `pop()`。
`end_of_line`：`range` 之外的 `for` 可迭代对象是解析器错误；检查器的
`check_for_iter`（878-883）不可达，但如果真被走到，它会报告
`iterating over a {it} is not implemented in Vela 0.1`（T），提示为 *“请用 'for
i in range(0, len(x)):' 并显式下标”*。

`range_interval`（循环变量被跟踪的区间）：无步长时，如果两端都已知且
`stop <= start`，循环从不运行 → `[lo, lo]`；否则
`[start, stop-1]`；如果只有 `start` 是精确的 → `[start, +inf]`；否则未知。有步长时，
start 与 step 都必须精确：零 → 规则 58，
`last = stop-1`（负步长时为 `+1`），空区间 → `[lo, lo]`，否则
`[lo, max(lo, last)]`；stop 不精确 → 未知。所以循环变量**绝不会**只拿到一个下界，除非
它的 start 是编译期常量。

### 1.12 `parallel for` 的限制（`verify_parallel` 887-933）

`verify_parallel` 内部的进入顺序：保存/替换 `self.loop_bounds` 为
`{target: (start, stop, step)}`，构建环境，收集写操作，恢复
`loop_bounds`，按基名对写操作分组，然后按下面的规则处理**顺序为 `by_base` 的迭代顺序
（插入顺序 = 首次写入顺序），并且对每个基名先应用“什么都没写”那条规则——前提是完全没
有任何写操作。**

（这里的规则编号是 130-145，不是 60-75：这一块是在整个函数体都类型检查*之后*才检查
的，所以它是检查器对每个函数做的最后一件事，编号也就跟着执行顺序。不存在 60-75 的规
则——清单里共有 126 条，编号为 1-59 与 76-145。）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 130 | 什么都不写的并行体 | `'parallel for' has no effect: the body writes nothing` | S | 911-913 |
| 131 | 每个被写的基名都必须是局部的 `mut` 数组 | `'parallel for' writes to '{base}', which is not a mutable array local to this function` | S | 918-921 |
| 132 | 一个基名，一个下标表达式 | `'parallel for' writes to '{base}' at {len(keys)} different index expressions, so two iterations could land on the same element` | S | 925-929 |
| 133 | 那个下标必须可证为单射 | `cannot prove that 'parallel for' writes '{base}' to distinct elements` | S | 1032-1034 |
| 134 | 带下标的写不可以穿过嵌套表达式 | `'parallel for' may only write to a local array, not through a nested expression` | S | 940-942 |
| 135 | 带下标的复合赋值必须是裸数组名 | `'parallel for' may only write to a local array` | S | 960-961 |
| 136 | 对非局部名字赋值 | `'parallel for' assigns to '{st.target.id}', which is shared across every iteration` | S | 948-952 |
| 137 | 对非局部名字做复合赋值 | `'parallel for' accumulates into '{st.target.id}', which is shared across iterations` | S | 966-970 |
| 138 | 任何其他赋值目 | `'parallel for' may only write to array elements` | S | 954-955 |
| 139 | 任何其他复合赋值目 | `'parallel for' may not write this target` | S | 972-973 |
| 140 | 嵌套的并行循环 | `nested 'parallel for' is not supported in Vela 0.1` | S | 979-980 |
| 141 | 函数体里出现非 range 的 `for` | `'parallel for' bodies may not contain a non-range 'for'` | S | 994-995 |
| 142 | 函数体里出现 `return` | `'parallel for' bodies may not 'return'` | S | 999 |
| 143 | 函数体里出现未知语句 | `unsupported statement in 'parallel for': {type(st).__name__}` | S | 1003-1005 |
| 144 | 函数体里调用 `print` | `'print' inside a 'parallel for' would interleave output unpredictably` | S | 1012-1014 |
| 145 | 函数体里调用非 `pure` 的用户函数或方法 | `call to impure function '{sig.name}' inside 'parallel for'` | S | 1021-1025 |

提示：(130) *“删掉 'parallel' 关键字”*；(131) *“只有局部声明的 'mut'
数组才能在并行中被写：参数可能别名到另一个数组”*；(132) *“让每个元素只通过一个下标表
达式写一次”*；(133) *“下标必须形如  c * i + j ，其中 c 是严格大于  j 的取值范围的编
译期常量；或者形如  i * N + j ，其中 j 取遍 range(0, N)”*；(136) *“改为把结果写进一
个局部数组，或者顺序执行这个循环”*；(137) *“逐迭代的累加器必须在循环体内声明”*；
(145) *“如果它没有副作用，就把它标为 'pure def
{sig.name}(...)'”*。

移植必须复现的结构性事实（`_collect_writes`，935-1005）：

* 一次写是 `(base_name, Index node, snapshot of env)`；只存在
  `Index`/`Name`/`Attribute` 三种目，而 `Attribute` 会落入规则
  138/139；
* 一次**纯** `a[i]` 扫描会下探进 `If`（两个分支、同一环境）、`ForRange`
  （仅非并行；把内层循环的 变量→边界 加入 `loop_bounds`
  和环境，然后递归）以及 **`While`**（同一环境，不做收窄）——
  `While` 是被刻意允许的，好让 Mandelbrot 的逃逸循环仍然可并行化；
* `_parallel_rhs_ok`（1007-1027）递归要求赋值右值中和每个 `ExprStmt`
  中的每次调用都是纯的。它通过
  `_resolve_call_target` 解析用户函数，通过 `e.func.value.vtype`
  解析方法（接收者必须已经被类型检查过——确实如此，因为函数体在这一遍之前就检查过
  了）。**要保留、或者要有意修掉的 bug**：对于*内建*调用，`_resolve_call_target`
  （1448-1466）会对 `BUILTIN_SIGS` 的**每一个**条目（1459-1462）返回一个带
  `is_pure=True` 的合成 `FuncSig`——元组的第三个元素（`pure`）从未被读取——所以
  `now()`、`read_text()`、
  `write_text()`、`intern()`、`panic()`、`emit_*` 和 `warn_*`
  全都在 `parallel for` 里被接受，尽管 `check_call` 在 `pure`
  函数里会拒绝它们（规则 118）。`print` 仅通过 `kind == "print"`
  分支（1011-1014）按名字抓到，而 `len()` 则以 `sig = None` 直接放行。另注意
  1015-1019 的方法分支会重新解析签名，所以先前的
  `sig = self.prog.method_sigs.get(...)` 赋值是多余的，但无害。
* `self.loop_bounds` 对**每个并行循环都是一个新的 dict**，所以嵌套的*顺序*循环的边界只
  有在外层并行体正在被收集时才可见于 `_match_rowmajor`。内层循环被扫描完之后没有任何
  东西清除它的条目，所以两个复用了同一个变量名的嵌套循环（两个兄弟循环里的 `j`）会把
  后一个条目留在那里。
* `_loop_bound_for`（1103-1105）是**死代码**。

### 1.13 下标读取与边界计数（`_check_index`，1109-1130）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 76 | 下标必须是 `int` | `array index must be int, got {itype}` | T | 1111 |
| 77 | 常量下标落在 `[0, size)` 之外 | `constant index {cv} is out of range for {bt}` | S | 1117-1120 |

关于 (77) 的提示：`valid indices are 0..{bt.size - 1}`。
副作用，按此顺序：`node.array_size = bt.size`、`node.vtype =
bt.elem`，然后要么走常量路径（77 → `need_bounds = False`，程序计数器
`elided_bounds += 1`，1121-1123），要么走区间路径
（`iv.within(bt.size)` → 省去检查，否则 `need_bounds = True` 且 `emitted_bounds
+= 1`，1124-1130）。`within` 即 `lo >= 0 and hi <= size - 1`（`analysis.py:60-62`）。
调用方只在*写*的位置传 `expected=INT`——`check_index_store`
（737）和 `check_augassign` 的带下标分支（786）——而普通读取
（1180）和 `_check_expr` 的快路径什么都不传，所以读取时用 `u8` 下标报告的是规则 76，而
不是规则 81 的通用消息。每个下标节点——无论读取还是写目——都恰好经过这里一次，所以边界
计数器正好等于“下标操作个数”。

### 1.14 表达式：分发与字面量上限（`check_expr` 1134-1145，`_check_expr` 1147-1195）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 78 | 整数字面量必须能放进 64 位有符号 | `integer literal {e.value} does not fit in int (64-bit signed)` | S | 1150-1152 |
| 79 | 不存在切片 | `slices are not implemented in Vela 0.1` | T | 1190-1194 |
| 80 | 未知表达式节点 | `unsupported expression {type(e).__name__}` | T | 1195 |
| 81 | `expected` 与实际不符（在字面量适配之后） | `expected {expected}, got {t}` | T | 1144 |

关于 (79) 的提示：*“这是设计中的真实组成部分（切片是一个带静态生存期的借用视图）；它
不在首个版本里”*。
`check_expr` 是**唯一**为一般表达式赋值 `e.vtype` 的地方（1136），也是字面量适配唯
一运行的地方（1137-1143）。
节点类型的分发顺序：`IntLit` → `FloatLit` → `StrLit` → `BoolLit` →
`NoneLit` → `ListLit` → `Name` → `BinOp` → `UnaryOp` → `BoolOp` → `Compare` →
`Call` → `Index` → `Attribute` → `SliceExpr` → 错误。
`NoneLit` 返回单例 `NoneT`；`NONE == NONE` 靠结构相等（`types.py:64-68`），而不是靠身
份。

### 1.15 名字（`check_name`，1197-1209）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 82 | struct 名被当作值使用 | `'{e.id}' is a struct; it must be called to build a value: {e.id}(...)` | T | 1201-1203 |
| 83 | 未定义的名字 | `'{e.id}' is not defined` | T | 1204 |

副作用：`is_array_param`、`is_struct_param`、`var_is_mut`、
`var_is_local_array` 被写在 `Name` 节点上（1205-1208）——后端读取前两个
（`codegen.py:273, 285, 290`），后两个目前无人使用。规则 82 只在该名字既不是局部变量也
不是参数、但*确实*是一个 struct 时触发。

### 1.16 数组字面量（`check_listlit`，1211-1219）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 84 | 空字面量没有元素类型 | `empty array literal has no element type` | T | 1213 |
| 85 | 第一个之后的元素必须与第一个元素的类型一致 | `expected {first}, got {t}` | T | 1218 via 1144 |
| 86 | 在不期望数组的地方使用了数组字面量 | `expected {expected}, got {ArrayT}` / `cannot assign …` | T | 1144/712 |

结果类型是 `ArrayT(first, len(elems))`（1219）——注意这是*字面量*长度，不是声明的长
度。没有元素的 `[` `]` 同时也是解析器错误
（`parser.py:650-654`），所以 84 在今天不可达。

### 1.17 字段与位置（`_field_type` 1221-1225，`_require_place` 1227-1235）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 87 | 未知字段名 | `{bt.name} has no field '{attr}'` | T | 1225 |
| 88 | 字段访问需要位置（place）形状的基名 | `cannot {what} a temporary struct` | T | 1232-1234 |
| 89 | `.` 的基名不是 struct | `{bt} has no attribute '{e.attr}'` | T | 1186 |

规则 88 里的 `{what}` 要么是 `"read a field from"`（1187），要么是 `"call a method on"`
（1594）；提示：*“先把 struct 绑定到一个变量：'p = Vec2(1.0, 2.0)'，然后再用
p.x”*。*位置*只能是 `Name`、`Index` 或 `Attribute`——所以
`P(1).x`、`f().x` 和 `(a + b).x` 都被拒绝。注意字段读取路径也会调用
`_require_place`（1187），而 `check_field_store`（742-750）**不**调用：对临时值存储是
不可能的，因为解析器已经拒绝了 `Call` 目（`parser.py:424-427`）。

### 1.18 一元、布尔与比较运算符

`check_unary`（1237-1251）：

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 90 | `not` 需要 `bool` | `'not' needs bool, got {t}` | T | 1241 |
| 91 | `~` 需要整数 | `'~' needs an integer, got {t}` | T | 1245 |
| 92 | 一元 `-`/`+` 需要数字 | `unary '{e.op}' needs a number, got {t}` | T | 1249 |
| 93 | 未知一元运算符 | `unsupported unary operator '{e.op}'` | T | 1251 |

`check_boolop`（1253-1260）：

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 94 | `and`/`or` 两边都需要 `bool` | `'{e.op}' needs bool on both sides, got {lt} and {rt}` | T | 1257-1258 |

提示：*“Vela 没有真值性，也没有会返回值的 'and'”*；结果是 `BOOL`
（1260）。注意 `and`/`or` 在 AST 中是严格二元的（`parser.py:454-470`
构建的是双操作数节点的左结合链）。

`check_compare`（1262-1294）：

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 95 | 大小比较运算符需要两个数字 | `'{e.op}' needs numbers, got {lt} and {rt}` | T | 1273 |
| 96 | 两侧必须类型相同 | `cannot compare {lt} with {rt}` | T | 1275-1278 |

提示：*“Vela 从不隐式转换数字；请显式使用 to_float() 或 to_int()”*；结果是 `BOOL`
（1279）。注意大小比较是在相等/同类型检查*之前*尝试的，并且两个 `str`
值上的 `==`/`!=` 是允许的（同类型）；`bool` 上的 `==` 允许；跨 `int`/`i32` 的 `==`
被拒绝（97）。

### 1.19 算术与运算符表（`_arith_result`，1296-1344）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 97 | 没有字符串拼接 | `string concatenation is not implemented in Vela 0.1` | T | 1300-1304 |
| 98 | 两个操作数都必须是标量 | `operator '{op}' cannot be applied to {lt} and {rt}` | T | 1306-1307 |
| 99 | `+ - *` 不可以混合类型 | `operator '{op}' mixes {lt} and {rt}` | T | 1310-1313 |
| 100 | `/` 只接受浮点 | `'/' is float division; got {lt} and {rt}` | T | 1319-1322 |
| 101 | `//` 只接受整数 | `'//' is integer division; got {lt} and {rt}` | T | 1326 |
| 102 | `%` 只接受整数 | `'%' is integer remainder; got {lt} and {rt}` | T | 1330 |
| 103 | `<<`/`>>` 需要整数（消息里用运算符） | `'{op}' needs integers, got {lt} and {rt}` | T | 1334 |
| 104 | `&`/`|`/`^` 需要整数（文本同上） | `'{op}' needs integers, got {lt} and {rt}` | T | 1338 |
| 105 | `**` 需要数值类型匹配的操作数 | `'**' needs matching numeric operands, got {lt} and {rt}` | T | 1342-1343 |
| 106 | 运算符不在表中时的兜底 | `operator '{op}' is not defined for {lt} and {rt}` | T | 1344 |

提示：(97) *“返回的拼接串可能指向已释放的 arena 帧，所以 0.1 只交付字面量，而不是交付
一个悬垂字符串的 bug”*；(99) *“Vela 从不隐式转换数字；请写 to_float(x) 或
to_int(x)”*；(100) *“整数除法请用 '//'——Vela 拒绝像 Python 那样让 '/' 对整数悄悄返回
浮点”*。
结果类型小结：`+ - *` → 若 `is_numeric`，则为（相等的）操作数类型；`/` →
`F64`；`//`、`%`、`<<`、`>>`、`& | ^` → 左操作数的类型（两者都必须是
`is_int`；注意这意味着 `u8 // u8` 是 `u8`，`i32 << int` 是 `i32`）；`**` → 若
`is_numeric`，则为公共类型。**`bool` 不是数值类型**，所以 `True + True` 被
(99)/(98) 拒绝。`str` 运算符：`+` 被 (97) 拒绝；`*`、`<` 等被 (98) 拒绝。

操作数类型来自**不带** `expected` 的 `check_expr`，所以 `1 + 1.0`
报告 (99) 的 `operator '+' mixes int and float`，而 `1 + x`（`x: float`）同样报告
(99)——这与两个 REJECT 用例
`implicit_int_to_float`/`float_to_int_variable` 一致，它们断言的正是 “mixes”。

### 1.20 类型之后的二元运算检查（`check_binop`，1346-1375）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 107 | 整数 `+ - * **` 可能溢出（证明见 §3） | — | — | 1351-1352, 1377-1410 |
| 108 | 除以/模以常量零 | `division by constant zero` | S | 1358 |
| 109 | 浮点除以字面量 `0.0` | `float division by constant zero` | S | 1361-1365 |
| 110 | 常量移位数超出 0..63 | `shift by constant {sh} is out of range 0..63` | S | 1369-1370 |

关于 (109) 的提示：*“IEEE 在这里会给出 inf 或 nan，而这几乎从来不是代码的本意；如果
你确实想要 inf，就除以一个运行时的值”*。
按运算符的副作用：

* 整数结果类型上的 `+ - *` → `plan_op_check` 写入
  `e.need_overflow` 并给某个计数器加一。`**` 被包含在 1351 处
  “`result.is_int` 且 op 不在 `<<`/`>>` 中”的条件里，所以 `2 ** 3`
  确实会经由 `plan_op_check` 拿到 `need_overflow` 这个词，而它的 `else`
  分支返回 `Interval.unknown()` 时**不**写这个标志（1402-1403）——
  也就是说 **`2 ** 3` 留下 `need_overflow` 未设置，且不增加任何计数器**；
  `codegen.py:218-221` 无条件发出 `vela_pow_int`，其运行时会在负指数上 panic
  （`runtime/vela_runtime.h:217`）。这是 Python 检查器记账里的一个真实
  漏洞；请如实说明，在比较计数器时不要悄悄“修好”它。
* `/ // %` → 常量零规则 (108)/(109)；`// %` 另外计算
  `e.need_divzero = not (biv.known and (biv.lo > 0 or biv.hi < 0))`（1372-1374）。
* `<< >>` → `e.need_shift_check = (const amount is None)`（1371）；范围内的常量置为
  `False`，越界的常量抛出 110。
* 二元移位被**排除**在溢出检查之外，但 `vela_shl_int` /
  `vela_shr_int` 在运行时仍会对 0..63 之外的非恒定移位数 panic。

### 1.21 调用（`_resolve_call_target` 1448-1470，`check_call` 1472-1587）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 111 | 被调用者必须是名字或属性 | `only named functions can be called` | T | 1479 |
| 112 | 对数组做 `print` | `cannot print an {t}` | T | 1487 |
| 113 | 对 `None`/错误/struct 做 `print` | `cannot print {t}` | T | 1490 |
| 114 | `pure` 函数里出现 `print` | `a 'pure' function may not print` | T | 1494 |
| 115 | `len()` 的参数个数 | `len() takes 1 argument, got {len(e.args)}` | T | 1499 |
| 116 | 对既不是数组也不是 `str` 的东西用 `len()` | `len() needs an array or a str, got {t}` | T | 1509-1512 |
| 117 | 内建函数的参数个数 | `{name}() takes {len(ptypes)} argument(s), got {len(e.args)}` | T | 1522-1524 |
| 118 | `pure` 函数里调用非纯内建 | `a 'pure' function may not call '{name}', which touches the host` | T | 1528-1532 |
| 119 | 用户函数的参数个数 | `'{name}' takes {len(sig.params)} argument(s), got {len(e.args)}` | T | 1540-1542 |
| 120 | `pure` 函数里调用非纯用户函数 | `a 'pure' function may not call '{name}', which is not declared 'pure'` | T | 1550-1553 |
| 121 | 构造函数的参数个数 | `{name}(...) takes {len(fields)} field value(s), got {len(e.args)}` | T | 1560-1564 |
| 122 | 未知的被调用者 | `'{name}' is not a function` | T | 1573 |
| 123 | `mut` 实参必须是变量 | `parameter '{pname}' is 'mut', so the argument must be a mutable variable, not a computed expression` | S | 1585-1587 |
| 124 | `mut` 实参必须是 `mut` 变量 | `parameter '{pname}' is 'mut' but '{a.id}' is immutable` | S | 1579-1583 |

提示：(112) *“逐个打印元素”*；(116) *“数组的长度是它类型的一部分，所以 len()
在编译期就被折叠成常量”*；(118) *“纯函数必须可复现且没有副作用，因此不可以做
I/O”*；(121) *“字段按声明顺序：" + `", ".join(f"{n}: {t}" for n, t in
fields)`*；(124) *“把 '{a.id}' 声明为 'mut'，这样这个改动在调用点可见”*。

`check_call` 中的分发顺序（决定了被归错类的调用会拿到哪条消息）：

1. `Attribute` 被调用者 → `check_method_call`（§1.22）。不是
   `Name` 的其他任何东西 → 111。
2. `print` → 每个实参 `check_expr(a)`，**不带期望**；Array → 112；
   `NoneT`/`ErrorT`/`StructT` → 113；然后 `e.target = "builtin:print"`、
   `e.vtype = NONE`；`in_pure` → 114。（所以 `print(x)` 里 `x` 是一个*函数*
   名时报告 83 的 `'x' is not defined`，而打印一个 `Array` 会在 113 的各类别之前先报
   告 112。）
3. `len` → 参数个数 115；`Str` → `target = "builtin:len_str"`，`const_value` =
   `StrLit` 解码后的字节长度或 `None`，`vtype = INT`（**没有**
   `folded_len_calls` 自增，并且对非字面量的 `str` 用 `len` 会留下
   `const_value = None`，`codegen.py:300-304` 把它变成
   `((int64_t)(expr).len)`）；`ArrayT` → `target = "builtin:len"`，
   `const_value = t.size`，`folded_len_calls += 1`；否则 116。
4. `BUILTIN_SIGS`（56-95）→ 参数个数 117；每个实参 `check_expr(a,
   expected=ptype)`——所以 `sqrt(1)` 报告 `expected float, got int`（规则 81），
   **而不是**一次转换；然后若 `in_pure and not pure` 则 118；`target =
   "builtin:{name}"`，`vtype = ret`。
5. 用户签名 → 参数个数 119；实参带 `expected=ptype`，对 `mut`
   参数则走 `_require_mut_arg`（123/124）；`target = "user:{name}"`，
   `e.sig = sig`，`vtype = sig.ret`；然后是 120。
6. struct 名 → 构造函数：字段来自 `StructT.defn.resolved_fields`，参数个数
   121，实参带 `expected=ftype`，`target = "ctor:{name}"`，`vtype = st`。
7. 其他 → 122。（1571-1572 是一个死掉的无操作分支。）

要逐字复现的内建签名表（`checker.py:56-97`），全部使用仅位置参数的合成参数名
`_p0, _p1, …`：

* 纯的：`to_float([int])->float`、`to_int([float])->int`、`sqrt([float])->float`、
  `fabs([float])->float`、`abs([int])->int`、`floor([float])->float`、
  `pow([float,float])->float`、`min_int([int,int])->int`、
  `max_int([int,int])->int`、`min_float([float,float])->float`、
  `max_float([float,float])->float`、`bytes_at([str,int])->int`、
  `substr([str,int,int])->str`、`argc([])->int`、`arg([int])->str`、
  `interned([int])->str`、`unescape([str])->str`
* 非纯的（在 `pure` 里被拒绝）：`now([])->float`、`read_text([str])->str`、
  `write_text([str,str])->bool`、`emit_int([int])->None`、
  `emit_float([float])->None`、`emit_str([str])->None`、`emit_nl([])->None`、
  `intern([str])->int`、`panic([str])->None`、`warn_str([str])->None`、
  `warn_int([int])->None`、`warn_nl([])->None`
* 特殊的：`print`（变参，非纯）、`len`（编译期）。

要保留的非显然后果：`float` **没有 `abs`**
（`abs(1.5)` 报告 `expected int, got float`）；`min_int(1.0, 2.0)` 报告
`expected int, got float`；`to_int(3)` 报告 `expected float, got int`；
`arg(-1)` *不*被检查；`bytes_at` 的下标不由检查器做边界检查（运行时做，
`runtime/vela_runtime.h:365`）。

### 1.22 方法调用（`check_method_call`，1589-1617）

| # | 规则 | 消息 | kind | site |
|---|---|---|---|---|
| 125 | 对非 struct 调 `.m()` | `{bt} has no method '{attr.attr}'` | T | 1593 |
| 126 | struct 上的未知方法 | `{bt.name} has no method '{attr.attr}'` | T | 1597 |
| 127 | 方法参数个数（不含 `self`） | `{bt.name}.{attr.attr}() takes {len(expected_args)} argument(s), got {len(e.args)}` | T | 1601-1603 |
| 128 | `pure` 函数里调用非纯方法 | `a 'pure' function may not call '{bt.name}.{attr.attr}'` | T | 1615-1616 |
| 129 | `mut self` 需要可变接收者 | 规则 124 的文本，其中 `pname = "self"` | S | 1608-1609 |

顺序：先检查接收者的类型（所以未定义的接收者报告 83），
然后 `_require_place`（88），再是 125/126/127，然后是实参（带
`expected=ptype` 与 `mut` 规则），然后是 `mut self`（129），最后是副作用
`e.target = "method:{Struct}.{name}"`、`e.sig`、`e.vtype = sig.ret`、
`e.receiver_type = bt`。`expected_args = sig.params[1:]`，所以 `self`
永远不计入参数个数。没有任何规则阻止在非 struct 的*字段*上、或在 struct 的数组元素上调
用方法——`_require_place` 接受 `Index` 和 `Attribute`，而 `codegen.receiver_addr`
会取那个位置的地址。

### 1.23 计数器与副作用小结

| 计数器 | 自增位置 | 何时 |
|---|---|---|
| `folded_len_calls` | 1516 | 仅 `len(array)` |
| `elided_bounds` | 1122, 1127 | 每个被证明在范围内的下标节点（常量或区间） |
| `emitted_bounds` | 1130 | 每个未被证明的下标节点 |
| `elided_overflow` | 1406 | 每个被证明不会溢出的整数 `+ - *`（二元运算或 `Name` 复合赋值） |
| `emitted_overflow` | 1409 | 每个未被证明的此类运算 |
| `parallel_loops` | 933 | 每个通过了 §1.12 的 `parallel for` |

---

## 2. 严格性规则

“比 Python 更严格”的规则，以及每一条在哪里强制执行、什么在**任何地方**都**没有**强制
执行。

| 规则 | 由谁强制 | 位置 | 备注 |
|---|---|---|---|
| 没有真值性（`if n`、`while s`） | 检查器 | 564-569, 576-581 | `ct != BOOL`；`and`/`or`/`not`/大小比较的操作数同理 |
| 没有隐式数值转换 | 检查器 | 99 (mixes), 95/96 (compare), 81 (`expected`) | 唯一的转换是整数字面量适配 |
| 整数字面量适配（唯一的例外） | 检查器 | 1137-1143, 1281-1294 | 见下文 |
| 默认不可变；不可重新绑定 | 检查器 | 45, 52, 44 | safety 错误 |
| 标量必须初始化 | 检查器 | 36 | 数组可以裸声明并被零填充 |
| 数组不可重新绑定 | 检查器 | 46 | 值语义，但没有别名 |
| struct 字段不可以是数组 | 检查器 | 11 | 与复制语义合并考虑 |
| 没有链式比较 | **解析器** | `parser.py:485-491` | `chained comparison is not allowed`；SPEC §4.1 |
| 没有相邻字符串字面量 | **词法分析器** | `lexer.py:214-220` | `adjacent string literals are not allowed` |
| 字符串没有 `+` | 检查器 | 97 | 消息说的是 “not implemented” |
| 表达式语句必须是调用 | 检查器 | 25 | 不是解析器规则 |
| `elif` 被拒绝 | **解析器** | `parser.py:284-288` | `'elif' is not part of Vela; write 'else if'` |
| 元组被拒绝 | **解析器** | `parser.py:629-634` | `tuples are not supported` |
| 相邻字符串字面量被拒绝 | **词法分析器** | `lexer.py:214-220` | `adjacent string literals are not allowed`，提示 *“Vela 没有隐式字符串拼接（在 Python 里漏掉逗号时它是一个安静的暗雷）；请用 + 运算符——0.1 还没有它——或者用一个字面量”*——注意这条提示自身的不一致：两个 `str` 上的 `+` 是规则 97 |
| 嵌套函数被拒绝 | 检查器 | 规则 28 | 语法接受它们；只有检查器反对 |
| 切片被拒绝 | 检查器 | 规则 79 | 解析器会构建 `SliceExpr`（`parser.py:586`） |
| 循环外的 `break`/`continue` | **解析器** | `parser.py:190-198` | 经由 `loop_depth` |
| 无标注的参数 / 缺少返回类型 | **解析器** | `parser.py:219-223`, `239-243` | `no type annotation` / `no return type annotation` |
| `mut` 绑定需要初始化式 | **解析器** | `parser.py:379-382` | |
| struct 体的形状（只有字段，≥1 个字段） | **解析器** | `parser.py:270-275` | |
| 赋值目标的形状 | **解析器** + 检查器 | `parser.py:421-427`, 检查器规则 34/55 | 解析器拒绝字面量/调用；检查器再查一遍 |
| 空数组字面量 | **解析器** | `parser.py:650-654` | 检查器规则 84 不可达 |
| `parallel` 需要显式的 `range(...)` | **解析器** | `parser.py:345-350` | `'parallel for' requires an explicit range(...) so the compiler can prove the iteration space` |
| 语句以换行分隔 | **解析器** | `parser.py:106-122` | |
| 块是花括号，永远不是 `:` | **解析器** | `parser.py:124-129` | `expected '{' to open a block` |
| 每个 `pure` 必须在 `def` 之前，`parallel` 必须在 `for` 之前 | **解析器** | `parser.py:155-159`, `176-180` | 还有 `d_flag`/`dump.vel:352` |
| 有返回类型的函数里 `return` 不带值 | 检查器 | 规则 31 | |
| **没有 `unsafe`、没有指针、没有 `free`、没有 FFI** | *没有任何东西需要强制* | — | 语法不存在；`extern/import/from/as` 是解析器错误（`parser.py:148-153`） |
| **整数宽度转换（`u8`→`int`）** | **什么都没有**——这是一个缺口，不是规则 | — | SPEC §3.1；`DESIGN.md:302-307`。在 `Array[u8,…]` 上写 `mut n: int = comp[i]` 会被拒绝（规则 39/43），而且没有转换函数，所以 `Array[u8]` 在标量语境里实际上不可用。在 *C* 里它会隐式加宽，所以编译执行与解释执行的行为不同；自举子集直接拒绝 `Array[u8]`（`selfhost/parts/eval.vel`、`DESIGN.md:365-367`） |
| **`float` 的真值性/`bool` 算术** | 检查器 | 规则 98/99 | `bool` 不是 `is_numeric`；这是 SPEC 在代码里比在文字里更严格的少数几处之一 |
| `parallel for` 里**内建**函数的纯度 | **什么都没有** | — | `_resolve_call_target` 把每个内建都标为纯（1459-1462）——见规则 145 的注 |
| 模块/多文件（`import`） | **解析器** | `parser.py:148-153` | 保留关键字，带理由拒绝 |

**126 条清单规则里的每一条都是硬错误——`checker.py` 中没有任何规则产生警告，
`errors.py` 或 `ide/services.py` 里也不存在 `severity: warning` 这条路径。**唯一的区别
是 `TypeErr` 还是 `SafetyErr`，而它只改变渲染消息里的 `kind` 那个词。

### 2.1 整数字面量适配——唯一的隐式转换（务必完全弄对）

它只在恰好两处运行，两处都是必需的：

1. `check_expr(e, expected)`（1137-1143）：如果 `t != expected`，`t` 和
   `expected` 都是整数标量，`e` 是一个 `IntLit`，且该字面量放得进
   `INT_RANGE[expected]`，那么 `e.vtype = expected` 并返回 `expected`。
   **超出目标范围的字面量不会被适配**，而是落到规则 81（`expected u8, got int`）。
2. 比较时的 `_adapt_literal_pair`（1268-1269、1281-1294）：*左*字面量先适配到右类型，
   然后右字面量适配到左类型，各自只在放得下时适配。所以 `comp[i] == 0` 在
   `Array[u8,…]` 上可用，而 `0 == x`/`x == 0` 都归一到同一对。被适配操作数的
   `vtype` 会被改写，这正是发出的 C 里没有强制转换的原因。

它**不**适用于：`_check_index` 的读取路径（1180 不传期望——一个 `IntLit`
下标保持 `INT`，这没问题）、二元运算操作数（1166-1167，总是 `INT`）、
`check_listlit` 的第一个元素（1214，没有期望 → 声明为 `Array[u8, 3]` 的 `[1,2,3]`
会逐个元素单独适配，每个都必须放得下）、以及 `print` 的实参（1485）。

`INT_RANGE`（`checker.py:45-49`）：`int` ±2^63，`i32` −2^31..2^31−1，`u8` 0..255。
**没有** `f64` 条目，而 `FloatLit` 从不适配。

### 2.2 *解析器*留给检查器的严格性

`AST.md` §9 列出的正是这些，值得把它们说清楚，因为它们就是 Vela 移植必须在现有自举解析
器之上补上的东西：

* 调用的参数个数与实参类型（没有被调用者种类的词，也没有从调用指向 `def` 的链接）→
  规则 111-122、125-127；
* 绑定与可变性（`undeclared = 1`、`1 = 2` 都能解析）→ 规则 34、40、45、
  51-55、83；
* 数组长度/下标类型（`a[i]` 不携带元素类型）→ 规则 47、76、77；
* pure/`parallel` 语义（标志存在于 `def` 上，但没有任何东西记录函数体调用了哪些函数）→
  规则 134-145、114、118、120、128；
* 一切与数值有关的东西，因为**没有任何表达式携带类型** → 规则 78、81、
  90-110；
* `len()` 折叠与区间机制 → §3。

---

## 3. 证明机制

### 3.1 `analysis.py` —— 区间

`Interval` 是 `(lo, hi)`，其中 `None` = 未知，`CLAMP = 1 << 62` 用来给算术定界
（`analysis.py:23-27`）。操作有：`exact(v)`、`unknown()`、
`known`、`is_exact()`、`width`、`within(size)` = `known and lo >= 0 and hi <=
size-1`；`add`、`sub`（单侧：未知的一侧得到未知的一侧）、
`mul`（两侧都要求已知，四个乘积）、`neg`、`floordiv`（如果除数的区间跨过 0 则未知；用
的是 Python 的 `//` = 向下取整）、`intersect`、`join`。

`eval_interval(expr, env)`（`analysis.py:118-165`）覆盖：
`IntLit` → 精确；`BoolLit` → 精确的 0/1；`Name` → `env` 中的值或未知；一元
`-` → `neg`，一元 `+` → 恒等，`~` → `neg(add(x, 1))`（**注意：`~x = -x-1`，
正确，但是分两步构造的**）；二元运算 `+ - *` → 对应运算，`/`
和 `//` → `floordiv`（**所以这里把 `/` 当作向下取整除法，尽管检查器只允许 `/` 用于浮
点——对浮点数而言这个区间没有意义，但无害**），`%` → 当除数区间严格为正时取
`[0, min(r.hi, b.hi-1)]`，否则未知；`**` → 只在两个操作数都精确、指数在 0..40 且
`|base| < 2^24` 时精确；`Call` → 只经
`getattr(expr, "const_value", None)` 得到精确值（折叠后的 `len()` 就是这样进入分析
的）；**其他一切都是未知**（所以 `Compare`、`BoolOp`、`Index`、
`Attribute`、`ListLit`、`StrLit` 全都是未知）。

`const_int(expr)`（`168-175`）：空环境下的精确区间，外加对一元 `+`
的特殊处理。正是它让 `a[3]`、`a[0 - 1]` 和 `1 << 99` 成为编译期判定，而且它**确实**能透
视字面量上的 `+`/`-`/`*`/`//`/`%`/`**` 以及折叠后的 `len()`。

`linear_form(expr, var)`（`196-250`）→ `(c, rest)` 或 `None`，针对形如 `c*var + rest`
的表达式，其中 `c` 是编译期整数：

* `IntLit` → `(0, lit)`；`Name` → 如果它就是 `var` 则为 `(1, None)`，否则
  `(0, node)`；
* 一元 `+`/`-` → 递归，并对两部分取负（`rest` 变成 `-rest`）；
* `+`/`-` → 合并 `c`，合并 `rest`（`rest` 为 `None` 表示常量 0）；
* `*` → **仅当**至多一侧依赖 `var`；如果依赖 `var` 的那一侧是
  `lc*var + lr` 而另一侧*不是*编译期整数，则返回 `None`
  （所以 `var * n` 里 `n` 是运行时值时不构成线性式）；常量会被乘进
  `rest`；
* 其他一切 → `None`。`var * var`、调用、下标、属性 → `None`。

### 3.2 检查器记录什么，以及什么时候证明成立

两个作用域量级的状态（`scopes`、`interval_scopes`、`dirty_scopes`、
`checker.py:190-241`）：

* `push(intervals)` / `pop()`——离开一个块时，把该块弄脏的每个名字在**最近的一个认识它
  的外层作用域**里标为 `unknown`，并在遇到第一个这样的作用域时停下（199-203）。正是这
  条规则防止分支与回边（back-edge）上的事实泄漏出去。
* `set_interval` 写入栈顶作用域；如果该名字活在某个外层作用域里，就把它记入
  `dirty_scopes[top]`（223-239）。
* `assigned_names(stmts)`（245-265）收集语句列表里任何位置、普通
  `Assign` 与 `AugAssign` 的每一个 `Name` 目，并递归进
  `body`/`orelse`——**它有意不去看数组元素或字段**，所以 `a[i] = …` 永远不会让 `i`
  失效。
* `loop_entry(stmts, cond, extra)`（267-279）：先把每个被赋值的名字置为
  未知，然后从循环条件（正极性）通过取交集来*收窄*，再应用 `extra`（循环变量区间）。
  这就是 `DESIGN.md:127-132` 称为最重要单条规则的失效处理。
* `narrow_from(cond, positive)`（298-341）只使用确定的事实：
  `A and B`（正极性）→ 两者；`A or B`（负极性）→ 两者取反；`not X` →
  `X` 且极性翻转；一侧是 `Name`、另一侧是
  `const_int` 的 `Compare` → 对 `< <= > >= ==` 分别得到 `Interval(None, k-1)` / `(None, k)` / `(k+1, None)` /
  `(k, None)` / `exact(k)`；`!=` 以及任何非常量比较都得不到东西；翻转的比较
  （`k < x`）会翻转运算符。
  结果与该名字当前的区间取交集。
* `track_interval(name, t, value)`（717-729）：只针对整数标量——数组、
  浮点、布尔、字符串和 struct 永远不被跟踪（所以 `bool` 和 `float`
  循环得不到收窄）；*裸*数组声明记录 `exact(0)`（即零填充），否则记录初始化式的区间，
  **在未知时降级为未知**。

**边界省去（`_check_index`）。** 对 `a[i]` 的检查恰好在下述情况下被省略：(1) `i` 是
`[0, size)` 内的编译期常量——越界的常量会被规则 77 变成编译期错误，而不是走到这里——
或者 (2)
`eval_interval(i, interval_env()).within(size)`。其他任何情况都会发出
`need_bounds = True`，后端则发出带数组大小与当前行的 `vela_bounds_check`
（`codegen.py:259-268, 505-521, 531-546`）。
`need_bounds` 是*逐下标节点*的一个词。

**溢出省去（`plan_op_check`，1383-1410）。** 对整数标量类型 `t` 上的
`op ∈ {+,-,*}`，其中 `INT_RANGE[t] = (lo, hi)`：

* `+`：需要 `_add_no_up(a,b,hi) and _add_no_down(a,b,lo)`。
  `_add_no_up`：如果 `a.hi` 未知，则要求 `b.hi is not None and b.hi <= 0`；
  如果 `b.hi` 未知，则要求 `a.hi <= 0`；否则 `a.hi + b.hi <= hi`。
  `_add_no_down`：若 `b.lo >= 0`，或 `a.lo >= 0` 则为真，否则要求两个下界都已知且
  `a.lo + b.lo >= lo`。
* `-`：若 `b.lo >= 0` 则 `_sub_no_up` 为真，否则要求两侧都已知且 `a.hi - b.lo <= hi`；
  若 `a.lo >= 0 and b.hi <= 0` 则 `_sub_no_down` 为真，否则要求两侧都已知且
  `a.lo - b.hi >= lo`。
* `*`：要求乘积区间是 `known` 且落在 `[lo, hi]` 内。
* 为目标（`iv`）记录的结果区间是 `A.add/sub/mul`，
  **与检查是否被省去无关**。
* 其他任何运算符（`**`、移位等）返回 `unknown()` 且**不写
  `need_overflow`**——见 §1.20 中 `**` 的那个漏洞。

设计所围绕的那个有趣情形：`it + 1`，其中 `it <= 99` 且下界未知，它会被省去，因为
`b.lo = 1 >= 0` 解除了下界那一侧。`while_with_narrowing` 和 Mandelbrot 的 `it`
都依赖这一点。

**区间在哪里被更新。** 只有：新绑定（`track_interval`）、对已有名字的普通赋值
（`track_interval`）、`+ - *` 的普通 `Name` 复合赋值（`plan_op_check` 的结果），以及循环
入口（`loop_entry`）。其他所有写名字的地方都调用
`invalidate_interval`（其他 augassign 运算符见 814）——而对数组元素/字段的写什么都不
使之失效，因为 `assigned_names` 看不到它们。

`program.elided_bounds` 统计被证明检查多余的**下标节点**（无论是常量路径还是区间路径）
——每个 `a[i]` 一个，读或写都一样；`emitted_bounds` 统计保留下标检查的节点数。
`elided_overflow` / `emitted_overflow` 统计整数 `+`/`-`/`*` 运算
（二元表达式**以及** `Name` 为目标的复合赋值）；
`folded_len_calls` 只统计 `len(array)`（`len(strliteral)` 也被折叠，但不计数）；
`parallel_loops` 统计被验证通过的 `parallel for` 语句。
`driver.py:97-105` 与 `ide/services.py:360-376` 暴露的正是这六个数，而 `format_stats`
打印 `bounds checks : E proved away, K kept
(P% elided)`，其中 `P = round(100*E/(E+K))`。

### 3.3 `verify_parallel` —— 无数据竞争的证明

要求按顺序列出，每条附上它产生的拒绝（消息见 §1.12）：

1. **循环是一个 `range(...)` 循环**——由*解析器*强制
   （`parser.py:345-350`），不是检查器。
2. **函数体确实写了东西。**没有任何写 → 规则 130。
3. **每个被写的基名都是局部的 `mut` 数组。**`lookup(base).is_local_array`
   必须成立；`is_local_array` 只在*绑定*具有数组类型且
   `s.is_mut` 时才被设置（677-679）。所以参数（`a: Array[…]`）、不可变局部变量
   （`a: Array[int,4] = [0]`）和 struct 字段全都不合格 → 规则 131。
   注意在**外层函数**里声明为 `mut` 的 `a`，只要在作用域内就仍能通过归属测试——规则是
   “局部于这个函数”，而不是“局部于这个循环”。
4. **每个基名只有一个下标表达式。**`{expr_key(index)}` 必须是单独一个键
   → 规则 132。`expr_key`（101-132）是一个*结构化*的字符串键，覆盖字面量、名字、每个
   二元/一元/boolop/compare 的两个操作数、调用的被调用者 + 实参、下标的值 + 下标、属性
   的基名 + 名字，以及列表元素；其他任何东西都变成 `?<NodeName>`。所以 `a[i]` 和
   `a[(i)]` 是同一个键，而 `a[i]` 和 `a[i+0]` 是两个键 → 被拒绝。**共享同一个基名的标量
   在这里不出现：**只有带下标的写会被分组，所以 `tmp[i] = …` 和 `tmp[j] = …`
   是一个基名、两个键。
5. **嵌套的 `parallel for` 被拒绝**（规则 140），非 range 的 `for`
   被拒绝（规则 141）。**`while` 是允许的**，并会被递归进去（986-992）。
6. **不允许 `return`**（规则 142），不允许未知语句（规则 143）；`break`、
   `continue` 和 `pass` 是允许的。
7. **标量写必须是该迭代私有的。**`self.lookup` 能解析到的 `Name` 目是共享的 →
   规则 66/67；*不能*解析的名字（在函数体内声明的）是私有的，可以通过——它的写根本不会
   被收集，也永远不会被索引。基名不是裸 `Name` 的带下标写 → 规则 64/65。
8. **从某次写的右值或从某个 `ExprStmt` 可达的每次调用都必须可证为纯**——
   `print` → 规则 144；`is_pure == False` 的用户函数或方法 → 规则 145。递归覆盖
   `left right operand value
   index func iterable cond args elems`（`_children`，1621-1633），并且**不会**
   下探进 `attr.value`，所以藏在方法接收者表达式里的非纯调用
   （在 `mut` 接收者上的 `a[f()].m()`）会被漏掉；而内建的纯度则根本不被查询（§1.12，规
   则 145 的注）。
9. **每次带下标的写都必须可证在整个迭代空间上单射**
   → 规则 133。会尝试两种证明，先 `_match_rowmajor`
   （`_match_linear`，1039-1059）：
   * **行主序（row-major）**：`expr` 必须是 `(var * K) + rest` 或 `(K * var) + rest`
     （或者是裸的 `var * K`，`_split_mul_add` 1086-1101），`rest` 必须是裸
     `Name`，且该名字必须在 `self.loop_bounds` 中，并满足 `const_int(start)
     == 0` 且没有步长或有常量步长；然后 `K` 必须与包围循环的 `stop`（有步长时为
     `stop * step`）在*结构上相等*（`expr_key`）——1061-1083。这就是 `i * N + j`
     的情形：`(i, j)` 是下标的 N 进制各位，所以无论 `N` 的运行时值是什么，不同的迭代都
     不可能碰撞。
   * **步幅（stride）**：`linear_form(expr, var) = (c, rest)` 且 `c != 0`；如果
     `rest` 存在，它的区间必须 `known`，而证明需要
     `abs(c) > width(rest)`——**严格大于**（1051-1059）。`rest` 不存在意味着宽度为
     0，于是任何 `c != 0` 都能通过（即普通的 `a[i]`）。
   * 其他任何情况 → 规则 133，提示会点名两种被接受的形式。
   `self.loop_bounds = {s.target: (start, stop, step)}` 这个初始化（894）对每个并行循环
   整体替换，之后再恢复；**用于 `rest` 的区间环境是在写位置取得的快照**
   （每次写 `dict(env)`，943/962），所以函数体早先发生的收窄是可见的。

`parallel_loops += 1` 只在所有检查都通过之后发生（933），这正是 `driver.py`
能借它决定要不要加 `/openmp` 或 `-fopenmp` 的原因
（`driver.py:119, 135`）。自举的 emitter 刻意**不**发出这个 pragma，并把
`parallel for` 顺序执行（`emit.vel:1396`、
`DESIGN.md:381-383`）。

---

## 4. 后端需要的数据

### 4.1 确切的契约

`codegen.py` 读取这些属性（全部可选——每次读取都是
`getattr(..., default)`），而这就是**完整的**接口：

| 属性 | 由检查器写入 | 由 codegen 读取 | 含义 |
|---|---|---|---|
| `expr.vtype` | `check_expr` 1136，目标位置 649/663/715/740/750/801 | `ct()`、`expr()`、`dispatch_print`、`binop`（`lt`）、`unary`（`operand.vtype`）、`compare`（`left.vtype`）、`_apply_op` | 每个表达式的类型 |
| `Index.node.need_bounds` | 1121/1126/1129 | 261, 508, 536 | 是否发出 `vela_bounds_check` |
| `Index.node.array_size` | 1112 | 264, 513, 541 | 传给检查的长度 |
| `BinOp.need_overflow` | 1405/1408 | 197 | `vela_{add,sub,mul}_range` 还是裸 C 运算符 |
| `BinOp.need_divzero` | 1374 | 209, 214 | `vela_floor_{div,mod}` 还是不做检查的变体 |
| `BinOp.need_shift_check` | 1371 | 224 | `vela_{shl,shr}_int` 还是 `<<`/`>>` |
| `AugAssign.need_overflow` | **从未写入** | 555（`getattr(..., True)`） | 永远是带检查的运算 |
| `Call.const_value` | 1504/1513 | 299, 301 | 折叠后的 `len` 值 |
| `Call.target` | 1491/1502/1514/1533/1547/1567/1610 | 295-323 | `builtin:print`、`builtin:len`、`builtin:len_str`、`builtin:NAME`、`user:NAME`、`ctor:Struct`、`method:Struct.name` |
| `Call.sig` | 1548/1611 | —（`_call_args` 用的是 `prog.sigs`，311/316） | 已诊断的签名 |
| `Call.receiver_type` | 1613 | — | 未使用 |
| `Name.is_struct_param` | 1206 | 273, 285, 290 | 接收者是 `T*`，访问用 `->` |
| `Name.is_array_param` | 1205 | — | stage 0 未使用 |
| `Name.var_is_mut`、`var_is_local_array` | 1207/1208 | — | stage 0 未使用 |
| `ForRange.step_const` | 854/856 | 599 | 字面量边界 + `++`/`--`/`+= k` |
| `StrLit/ListLit.pad_to` | 662 | — | 未使用；零填充是隐式的 |
| `StructDef.resolved_fields` | 416 | 692, 699, 700, 705 | 按声明顺序的字段名/类型 |
| `StructDef.vtype`、`FuncDef.ret_vtype`、`Param.vtype`、`StructField.vtype` | 414/417, 464, 452, 452 | `prog.structs`、`sig.params[i][1]`、`sd.resolved_fields` | |
| `Program.sigs`、`method_sigs`、`struct_defs`、`funcs`、`structs` | 354, 427/440, 418, 399 | 311/316, 713-727 | 解析完成的程序 |
| `Program.{elided,emitted}_bounds`、`.{elided,emitted}_overflow`、`parallel_loops`、`folded_len_calls` | 1122-1130, 1406-1409, 933, 1516 | `driver.py`、`ide/services.py` | 统计量 |

因此一个 emitter 必须读取**恰好三个布尔量**来决定“是否保留检查”：`need_bounds`
（默认 **False**——缺失意味着不检查）、
`need_overflow`（默认 **True**——缺失意味着要检查）、`need_divzero`
（默认 **True**）、`need_shift_check`（默认 **True**）。这些不对称的默认值很重要：缺
少 `need_bounds` 意味着*省去*，缺少 `need_overflow` 意味着*发出*。

### 4.2 这在自举池里意味着什么

当前的自举 emitter **完全没有**这些：它在发出代码时遍历表达式树，重新推导出一个粗糙的
`K_*` 种类
（`emit.vel:1520-1560`，`expr_packed`/`expr_kind`），数组长度取自
*声明时的类型记录*（`unpack_len`，`emit.vel:1497`），并且刻意发出**每一条**边界与溢出
检查（`emit.vel:23-26, 1720-1728`）。移植需要有个地方存放逐节点的证明数据：

* 节点跨步是 **10 个 int**（`AST.md` §1，`parser.vel:11, 95`），0-9 号字除了 4 号字
  （`d`）之外都已被占用——解析器把 4 号字记为
  *“未初始化的死空间……可供解释器自由使用”*（`AST.md` §9.11，
  `parser.vel:13`）——但它被 `if`（else 头）和 `for`（步长）占用，所以在这两种节点上它
  *并不*空闲；而且只有一个字可用，上面却是四个彼此独立的标志；
* 6 号字（`flags`）已经承载 `mut`/`declaration`/`pure`/`parallel`
  （`AST.md` §1.1）且由解析器写入，所以复用它高位是可能的，但绝不能与
  `dump.vel:87` 的 `d_flag` 输出冲突——`tests/diff_ast.py` 要求后者与 stage 0
  逐字节相同；
* 因此现实的做法是一张**旁表**——一个按节点编号索引的 Vela 侧数组，每个证明事实一个条目
  （`need_bounds`、`need_overflow`、
  `need_divzero`、`need_shift_check`、静态类型 id、`const_value`、
  `array_size`，外加作用域/绑定信息），按节点池的容量分配（65 536 个节点，`AST.md`
  §1：共 655 360 个 int，跨步 10）。Vela 只有一维数组、没有堆对象，所以几个并行的数组
  （或者一个数组配手工跨步）是仅有的选择——正是 `mem:
  Array[int, 3479552]` 这个技巧，`resolve.vel`/`eval.vel`/`emit.vel` 已经用它来存符号、
  帧和字段表；
* **今天没有任何节点字或旁槽承载类型**（`AST.md` §9.1、§9.10），所以“这个表达式是哪个
  静态类型”要么是一张新的旁表，要么是一次与 `expr_packed` 遍历完全相同的重算。检查器自
  己无法廉价地重新推导它：它需要一个任意名字的*声明*类型，而那个类型活在解析器
  （resolver）的符号表里（`resolve.vel:392-514`），不在池里；
* 驱动规则 81、85 和字面量适配的 `expected` 类型参数**在当前 AST 里根本没有容身之处**
  ——它是沿递归的 `check_expr` 向下传的一个值，在 Vela 里这没问题（多传 `mut` 参数就是
  惯用法，`DESIGN.md:283-286`），但这意味着适配决定必须记录在 emitter 能找到的地方，因
  为 emitter 以不同的顺序访问节点（`emit_expr` 先发出操作数，并且需要*操作数*的类型来
  选择运算符，`codegen.py` 的 `binop` 读取 `e.left.vtype`）。

### 4.3 移植必须复现的统计量

六个整数，不多不少；`driver.py:97-105`、`ide/services.py:363-376` 和
`__main__.py:72-76` 全都按这些名字读取它们，而 `vela ide` 的状态栏打印
`bounds 12/12 proved away`。Python 实现里没有别的东西读 `Program`。

---

## 5. 移植顺序

从最小的一片开始，每一片都能独立测试。**开始第 3 片之前先读 §5.6**——它改变了排序约
束。

### 第 0 片 —— 准备载体（还没有任何规则）

加上存放逐节点事实的旁表，以及一套静态类型表示（小整数：五个标量、`str`、`None`、
`Array`、`Struct`，外加一张由类型记录构建的 struct id / 数组长度 / 元素类型表）。什么都
不填充。用 `tests/diff_ast.py` 和 `tests/diff_lexer.py` 验证（必须保持 “identical”），
并用 `tests/diff_emit.py` + `tests/diff_run.py` 验证（必须保持绿灯），以证明这些新数组
是惰性的。

### 第 1 片 —— 符号解析、作用域与 `main`

规则：40、44、34、83、82、19、20、21，外加声明绑定记录
（名字 → 类型、`is_mut`、`is_param`、`is_local_array`），复用 `resolve.vel` 的
`bind_name`/`declare_local`/`add_function`/`add_struct` 脚手架
（`resolve.vel:250-514`）。
依赖 `types.py` 的设施：没有特别的东西——只有 `VarInfo` 和 `StructT.name`
的身份。
验证方式：`tests/run_tests.py`（`no_main`、`uninitialised_scalar`、
`nested_function`、`duplicate_field`），以及 `tests/diff_run.py` 的
`both-refuse` 列——它**目前对每一个解释器照跑不误的 reject 用例都报 DIFF**；这一片是第
一个能推动那个数字的。

### 第 2 片 —— 类型、字面量、运算符（不含证明）

规则：1-7（类型语法）、78、81、90-106、`check_expr` 分发那一批、84/85、
33、31/32/24、26/27、94、95/96、99-101、25、28、79、87、88、89、24。
依赖 `types.py`：`Scalar` 家族以及 `is_numeric`/`is_int`/`is_float`/
`is_bool`、`ArrayT.elem/size`、`Str`/`NoneT` 相等性、`INT_RANGE`、
`TYPE_NAMES`，以及整数字面量适配（`_adapt_literal_pair`）。
验证方式：`tests/run_tests.py` 的 reject 用例 `implicit_int_to_float`、
`float_to_int_variable`、`truthiness`、`truthiness_string`、
`useless_expression_statement`、`string_concat_not_implemented`、
`slice_not_implemented`、`struct_requires_place`、`literal_overflow`、
`value_from_void_function`、`missing_return`、`missing_return_annotation`、
`unannotated_parameter`、`chained_comparison`（解析器——断言它保持被拒绝）、
`adjacent_string_literals`（解析器），并且每个 `run` 用例都必须继续能跑
（`diff_run.py` 不得倒退成 “interpreter refuses by its own
limits”）。

### 第 3 片 —— 调用、签名、struct 与方法

规则：12-18、22、111-129、41-43、39、36、37、45、46、48-50、51-55、9-11、29、86。
依赖 `types.py`：`FuncSig`（name/params/ret/is_pure/struct_name/defn）、
`StructT.defn`、`resolved_fields`、`is_copyable`（今天未使用，203-205）、
`cname`/`ctype`（检查器不用）。
验证方式：`run_tests.py` 的 `immutable_rebind`、`immutable_array_element`、
`array_rebind`、`array_struct_field`、`struct_and_methods`（run 用例）、
`struct_value_semantics`（run 用例）、`pure_function_impure_call`、`mutate_loop_variable`，
外加 `tests/run_ide_tests.py` 的 hover/签名/补全——如果这次移植要喂给 IDE 的话。

### 第 4 片 —— 区间引擎、边界省去

规则：57/59/56（range）、76/77，以及对 `analysis.py` 的忠实移植
（`Interval`、`add/sub/mul/neg/floordiv/intersect/join`、`eval_interval`、
`const_int`、`linear_form`）、`narrow_from`、`assigned_names`、`loop_entry`、
`set_interval`/`dirty_scopes`/`pop`、`track_interval`、`_check_index`。
依赖 `types.py`：只有 `Scalar.is_int`（跟踪）和 `ArrayT.size/elem`。
这是第一个会改变发出的 C **以及**计数器的片。
验证方式：`tests/run_tests.py` 的 `arrays_and_len_folding`、`array_zero_filled`、
`while_with_narrowing`、`const_index_out_of_range`、`negative_const_index`、
`divide_by_mutable_zero_checks_at_runtime`；以及语料程序在
`python -m vela check` 下给出的六个统计量（先把 stage 0 的数字记下来——它们就是回归基
线）。

### 第 5 片 —— 溢出、除法与移位证明

规则：107-110、`plan_op_check` 和四个单侧辅助函数、`check_augassign` 里的
`Iterval` 更新路径（805-814）、`step_const`、`need_divzero`、
`need_shift_check`、`folded_len_calls`。
验证方式：`run_tests.py` 的 `int_width_literals`、`arith_basics`、
`division_by_constant_zero`、`divide_float_by_literal_zero`、
`shift_out_of_range`、`literal_overflow`；再看计数器。

### 第 6 片 —— `parallel for`

规则：130-145、133 的两套证明、`expr_key`、`loop_bounds`、`_collect_writes`、
`_parallel_rhs_ok`、`_match_linear`/`_match_rowmajor`/`_split_mul_add`。
依赖：`ExprKey` 的结构相等（池上等价的字符串键）、方法调用那个*已经类型检查过的*接收
者，以及第 1/3 片的 `is_local_array` 绑定标志。
验证方式：`run_tests.py` 的 `parallel_for_correct`、`parallel_nested_2d`、
`pure_call_inside_parallel`（必须*继续*能编译），以及 reject 用例
`parallel_shared_accumulator`、`parallel_unprovable_index`、
`parallel_writes_parameter`、`parallel_impure_call`、`parallel_print`；
`bench/matmul.vel` 和 `bench/mandelbrot.vel` 通过
`tests/diff_run.py --bench`（它们同时锻炼收窄与行主序）。

### 第 7 片 —— 一致性测试台

加上一个 Vela 侧的 `check` 子命令（或者一个无副作用的 `check-only` 模式），打印同样的六
个计数器，并加一个与现有测试类似的差分测试：对仓库里每个 `.vel` 文件，Vela 检查器的判
定（接受/拒绝 + 第一条消息）必须等于 stage 0 的——形状与
`diff_lexer.py`/`diff_ast.py` 相同，也是唯一诚实收尾这次移植的方式。

### 5.1 哪些规则依赖 `types.py` 的哪些设施

| `types.py` 设施 | 需要它的规则 |
|---|---|
| `Scalar` + `is_int`/`is_float`/`is_bool`/`is_numeric`/`bits` | 78, 81, 90-110, 区间跟踪, `INT_RANGE`, codegen 里的 `int_bounds` 选择 |
| `INT/I32/U8` 的身份（不只是 `.name`） | 适配（1137-1143, 1281-1294）与规则 99 的 “mixes”（身份比较 `lt != rt`） |
| `F64` | `/` (100), `**` (105), `to_float`/`to_int`/`sqrt`/... 的实参期望 |
| `BOOL` | 条件 (26/27), `not` (90), `and`/`or` (94), `bool` 不是数值类型 |
| `NoneT` / `NONE` | 22, 23, 31, 32, 38, 113, 返回类型比较 |
| `Str` / `STR` | 6 (`len_str`), 97, `==` 的 95/96, 116, 113, `bytes_at`/`substr`/`unescape` 的实参 |
| `ArrayT(elem, size)` + 结构化 `__eq__` | 1-5, 11, 22, 36, 37, 39, 43, 47, 57-59 的边界检查, 76/77, 84/85, 116, `len` 折叠 |
| `StructT(name, defn)` + `resolved_fields` | 9-11, 22, 48-50, 87-89, 121, 125-128 |
| `FuncSig(params[(name, type, is_mut)], ret, is_pure, struct_name, defn)` | 12-18, 22, 23, 119/120, 123/124, 125-129 |
| `ErrorT` / `ERROR` | 只作为死代码污染（1137, 1215, 670）——移植可以丢掉它 |
| `cname` / `ctype` / `is_copyable` | **检查器不用它们**（只有 codegen/types 内部用） |
| `VType.name`（`__str__`） | 每条插值消息——`Array[…]`、struct 名、`str`、`None`、`<error>` 的拼写都必须一致 |

### 5.2 自举 AST 不携带的节点池字

依据 `AST.md` §1.1、§2、§6、§9：`kind 0` / `a 1` / `b 2` / `c 3` / `d 4` /
`line 5` / `flags 6` / `nx 7` / `e 8` / `f 9`，跨步 10。

* **表达式类型**——缺失（缺口 1）；规则 33、39、43、81、85、90-110、
  116、121、125-127 以及 emitter 的运算符选择都需要它；
* **声明类型**——只出现在 `param`（2 号字）、`def` 返回（2 号字）
  和声明（3 号字）/ struct 字段（`kind 4` 体里的 `kind 6`，3 号字）上，
  并且只有 `name/elem/size` 记录（缺口 4）——所以 `Array` 的元素/长度是白拿的，但没有可
  用于比较的结构化类型身份（Python 比较的是
  `ArrayT.__eq__` 和 `StructT.__eq__`，也就是按值比较）；
* **下标/元素之间的关联**——缺失（缺口 4）：一个 `kind 31` 节点既不知道数组的长度也不知
  道它的元素类型，所以规则 76/77 和 `array_size` 需要解析器把它们附上去；
* **绑定/作用域**——缺失（缺口 6、7）：没有作用域 id、没有局部槽位、没有
  局部/参数/字段/函数的区分，也没有 `is_mut` 强制。`resolve.vel`
  已经在运行时建立了一张符号表（`SYM_LOCAL`、`NT_SYM`、
  `resolve.vel:359-514`），所以检查器应当消费/接管那张表，而不是另造一张；
* **方法的归属**——缺失（缺口 3）：方法就是普通的 `kind 2` 节点，没有 owner 字，而名字
  句柄是内容寻址的，所以两个 struct 的 `def
  get` 是*相等*的；`(struct, name)` 映射必须由检查器构建
  （`resolve.vel:298-348` 已经在字段表 `FT_OWNER`、`emit.vel:47-57` 里记录了 owner）；
* **被调用者的种类**——缺失（缺口 9）：规则 111-122 需要回答“这是 `len`、`print`、一个
  `BUILTIN_SIGS` 名字、一个 struct 构造函数、一个用户函数，还是一个方法？”，而解析器
  不存被调用者种类的字；
* **函数体上的 `pure`**——标志存在于 `def` 上（6 号字第 4 位，
  `parser.vel:531`），但没有任何东西记录函数体调用了哪些函数，所以规则
  114/118/120/128 和 75 需要自己的调用遍历（`_parallel_rhs_ok` 已经是了）；
* **`pad_to` / `step_const` / `need_*` / `const_value` / `array_size` /
  `resolved_fields` / `receiver_type` / `ret_vtype` / `is_*_param`**——全都是没有池槽位的
  Python 属性；4 号字是唯一名义上空闲的字，而它已被 `if`（else 头）和 `for`（步长）占
  用；
* **列号与结束位置**——只有语句行存在（缺口 13）；每条消息都必须用语句/表达式节点的行
  来发出，而 Python 检查器报告的 `col` 来自它抛错的那个具体节点
  （`self.err`/`self.safety` 用 `node.line, node.col`）。**自举池里根本没有列**，所以
  想逐字节相同地复现 `file:line:col:` 前缀，只能靠加一个列字或加一张错误位置旁表——这是
  这次移植最大的单个保真缺口，值得明确决定目标究竟是“判定相同、消息文本相同”还是
  “渲染出的诊断逐字节相同”。

### 5.3 复用解释器已经做过的事

`resolve.vel` 已经在*解释器*里做了第 1/3 片的一个子集——它绑定名字、按声明顺序记录
struct 字段、把方法记录到所属 struct 上、拒绝错误的参数个数（`check_call`，
`resolve.vel:359-392`，注释在 140）、并拒绝嵌套数组 / `Array[u8]`——但那是在*运行*时、
一次一条程序路径，而且用自己基于 panic 的报告方式
（`resolve.vel:35, 49`，`"the interpreted program was refused"`）。移植要做的是在执行之
前一次性完成这些，并以编译器的格式报告，而不是 panic。凡是 `resolve.vel` 算出来的东西
（字段表、方法归属、参数个数）都应提升进检查器，而不是重复实现。

### 5.4 已经存在的测试语料

* `tests/run_tests.py`——48 个 reject 用例（其中一个，`duplicate_field`，期望的
  expected 子串是*空*的，也就是“任意 VelaError”；另一个，
  `divide_by_mutable_zero_checks_at_runtime`，必须**能编译**并在运行时以
  “division by zero” panic，走的正是第 5 片的 `need_divzero` 路径）。
* `tests/diff_emit.py`——语料 = `tests/build/*.vel` + `examples/hello.vel`，
  减去 `ide_http`/`vm`/`vela`；它比较的是**运行输出**，不是 C 文本，并且把自举限制造成
  的拒绝（`SELF_LIMITS`，`diff_emit.py:49-50`）当作失败而不是跳过。
* `tests/diff_run.py`——同样的语料（加上 `tests/interp/*.vel`（如果存在），加上带
  `--bench` 的 `bench/*.vel`）；stage 0 拒绝的程序必须被解释器以*非自举限制*的消息拒绝
  （`diff_run.py:143-148`），所以从第 1 片起会直接提高这套测试的分数，而自举限制造成的
  拒绝算 DIFF。
* `tests/diff_selfhost.py`——`vm.exe` 与
  `vm_by_vela.exe` 之间逐字节相同的 C，外加用于第 3 代的 `--fixpoint`。
* `tests/diff_ast.py` / `diff_lexer.py`——每一片之后都必须保持 “identical”；任何新的节
  点字都会改变 `dump.vel` 的输出并打断它们。

### 5.5 哪些规则从现有语料里*不可达*

值得补测试，因为移植可能悄悄漏掉它们：规则 4、5、8、
14、17、18、41、42、54、55、58、84、91、92、93、97（只被部分覆盖）、
103、104、105、106、109、113、115、117、118、121、122、123、129、135、139、141、
142、143。其中若干是真的不可达（30、41、42 的 `is_mut` 分支、58 的
重复路径、84、90-93 的 “unsupported” 分支、领先一步的 111 的 `Attribute` 路径、
122 的死分支），应当把它们*记录在案*，而不是去测。

### 5.6 现有测试套件施加的排序约束

> **事后更正（见本节末尾的注）。** 这里最初给出的约束在“`diff_selfhost.py` 比较的是哪
> 两个编译器”这点上是错的。关于*省去检查改变了什么*的分析是对的、值得保留；结论不对。

`tests/diff_selfhost.py` 要求**同一份 Vela 源码**的两次构建发出逐字节相同的 C。今天自
举 emitter 无条件发出每一条检查
（`emit.vel:23-26`、`1718-1728`），而 stage 0 的 emitter 会省去检查器已证明多余的部分
（`codegen.py:197, 261`）。因此把证明接进 `emit.vel`，会让自举 emitter 的 C 对任何存在
可省去检查的语料程序都与 **stage 0 的** C 不同——`parallel_for_correct`
（在 `Array[int, 100]` 上对 `range(0, 100)` 写 `out[i]`）、`parallel_nested_2d`、
`arrays_and_len_folding`、
`while_with_narrowing`、`arith_basics`、`int_width_literals` 以及基准程序全都符合。

**但 `diff_selfhost.py` 并不与 stage 0 比较。** `compare_corpus`
调用的是 `emit_c(VM, ...)` 和 `emit_c(other, ...)`，其中 `other` 是
`vm_by_vela.exe`，而 `fixpoint` 比较的是第 1 代与第 3 代——这些都是跑同一份源码的*自举*
emitter。省去检查对它们所有人同样且确定地生效，所以改前改后逐字节相同都成立。*确实*与
stage 0 比较的套件是 `diff_emit.py`
（比较的是：被编译程序的 stdout）和 `diff_run.py`（解释器），而两者都不比较 C 文本。

这个改动真正推动的是：

* `diff_selfhost.py` 保持绿灯——**前提**是省去检查的决定是解析后模块的纯函数，在每次构建
  中都相同。它是一道关于确定性的闸门，不是关于检查策略的闸门。
* 自举编译器**自己的** C 现在也是带着省去检查发出的，所以一个错误的证明将误编译编译器
  本身，而不只是某个测试程序。因此证明必须偏向于发出检查，而 `diff_emit.py` 加上计数器
  （第 7 片）就是表明这一点的闸门。
* 第 4 片仍然是第一个改变发出的 C 与六个计数器的片，所以它应当与 §4.3 记录的 stage-0
  计数器基线一起落地。

所以：先做判定，把省去检查与计数器基线一起接进去，不要拿与 stage 0 的 C 文本一致性来
给这次移植设闸门——那项一致性从未被声称过，也不是 stage 3 所承诺的东西。

### 5.7 建议的片到套件映射（紧凑版）

| 片 | 主要闸门 | 次要闸门 |
|---|---|---|
| 0 | `diff_ast.py`、`diff_lexer.py` 保持 identical | `diff_emit.py` 绿灯 |
| 1 | `run_tests.py -k main` / `-k uninitialised` / `-k nested` | `diff_run.py` 的 both-refuse 计数上升 |
| 2 | `run_tests.py`（所有 reject 用例） | `diff_run.py` 的 run 用例仍然匹配 |
| 3 | `run_tests.py -k struct` / `-k immutable` / `-k pure` | `run_ide_tests.py`（如果 IDE 被喂数据） |
| 4 | `run_tests.py -k array` / `-k index` / `-k narrowing`；stage-0 计数器基线 | `diff_run.py`、`diff_emit.py` |
| 5 | `run_tests.py -k zero` / `-k shift` / `-k overflow`；计数器 | `diff_run.py` |
| 6 | `run_tests.py -k parallel`；`bench/*.vel` 经 `diff_run.py --bench` | `diff_emit.py` |
| 7 | 覆盖树中每个 `.vel` 的新判定一致性套件 | `diff_selfhost.py`（见 §5.6） |

---

## 6. 无法核实 / 不确定的点（如实陈述，而非猜测）

* **`2 ** 3` 留下 `need_overflow` 未设置**且不增加任何计数器（§1.20）——
  这是通过阅读 `plan_op_check` 的 `else` 分支（1402-1403）和
  `codegen.py:218-221` 核实的；其*意图*明显是带检查的 `vela_pow_int`，而运行时确实会在
  负指数上 panic
  （`runtime/vela_runtime.h:217`），但标志/计数器这条路径是个漏洞。
* **`a[i] += 1` 会算一次边界检查，但不算溢出检查**，并且
  `AugAssign.need_overflow` 从未被写入（§1.10）——这是从代码读出来的，没有实际执行。
* **`parallel for` 里内建函数的纯度**从未被强制，这与 SPEC §7 的“`print`、`read_text`
  及其同类会被拒绝”相反（§1.12）。唯一会触发的是按名字检查 `print`。
* **`_parallel_rhs_ok` 不下探进 `attr.value`**（`_children`，
  1621-1633）——方法接收者表达式里的调用会被跳过。实践中这一点被上一条削弱了。
* **`_loop_bound_for`（1103-1105）是死代码**；`check_for_iter`（878-883）和
  `_resolve_call_target` 的 `kind == "method"` 分支（1468-1469）
  实际上也是死的。
* **`pad_to`、`Call.receiver_type`、`Name.is_array_param`、
  `Name.var_is_mut`、`Name.var_is_local_array`、`Call.sig`、`FuncDef.ret_vtype`、
  `Param.vtype`、`StructField.vtype`、`types.is_copyable`、`types.cname`**
  由检查器写入，而在当前后端中没有任何东西读取。移植可以丢掉它们，但应当记录这个决定，
  因为 IDE 或未来的 emitter 可能会想要它们。
* **逐字节相同的渲染诊断**（`file:line:col: <kind>:
  <msg>` + 脱字符 + 提示）是不是一个目标，在我读过的任何地方都没有说明；池里没有列
  （`AST.md` §9.13）。§5.2 记录了这个决策点。
* `analysis.floordiv` 在 `eval_interval` 中被用于 `/`，尽管在检查器层面 `/` 只接受浮点；
  今天无害，但如果 Vela 移植让两者共用一个区间例程，就必须保持同样的行为，才能精确复现
  计数器。
* `tests/run_tests.py` 的 `duplicate_field` 用例期望空子串，所以它在*任何* `VelaError`
  上都会通过；它根本没有钉住检查器。
* 我没有运行任何套件（这次会话只产出了一份文档）；上面每一条断言都来自阅读开头点名的那些
  源码，而非观察到的输出。
