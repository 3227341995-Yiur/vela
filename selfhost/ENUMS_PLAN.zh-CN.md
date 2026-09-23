# 带负载的枚举与 `match`——设计，写在任何代码之前

[English](ENUMS_PLAN.md) | **简体中文**

<!--
源文件 : ENUMS_PLAN.md
源文件字节 : 14976
源文件 SHA256 : 45d3a8e5ae1f62f016a790e2d182cfa7b033e8532b32b9a10c11ff3a7f9a0341
翻译日期 : 2026-09-24
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
       本文件与英文文件都由 ENUMS_PLAN_AUDIT.md 的引用复核覆盖：那份报告把两边的
       每一处源码引用都对着当时的源码逐个核对过，并写明各文件的 SHA-256。
-->

状态：**尚未开始。** 这是流 2 第 1 项的规格（`ROADMAP.md` 中的"带负载的枚举 + `match`"），之所以先写它，是因为这个特性要进入一个用 Vela 写成、而且必须持续自举的编译器。这种规模的一次改动如果没有一份写下来的契约和一条验收测试，就是一个自举编译器染上一种没人能二分定位的回归的方式。

**本计划引用函数名，而不是行号。** `selfhost/parts/*.vel` 里的行号在多个 agent 同时改树的时候会漂移，而它们在那些抄了行号的文件里漂得最快。下面每一处源码引用都点名函数和文件；那些 `file:line` 是在 2026-09-24 对着每个文件的一个具名修订重新锚定的（每个文件的 SHA-256 与字节数、以及每一处偏移了多少，都记在 `ENUMS_PLAN_AUDIT.md` 里）。请把这里的行号读成"在那个修订上它在这里"，把函数名读成真正要去搜的东西。

## 为什么先做这一个

不是因为它被要求得最多，而是因为**编译器比语言更需要它**。今天 AST 的节点种类是整数：`check.vel` 用 `nd_kind(nd, e)` 分派并与字面量比较（下标节点是 `k == 31`，名字是 `k == 25`，调用是 `26`，列表是 `33`，再下一个是 `34`），而同一批数字又出现在 `eval.vel` 和 `emit.vel` 里。散文里点名的这五个就是解析器自己的种类表：31 下标、25 名字、26 调用、33 列表、34 切片，写在 `parser.vel` 的头注释里（`parser.vel:23`–`:53`），由 `parse_postfix` 里的 `new_node(nd, cx, 34, …)`（`parser.vel:1144`）、`new_node(nd, cx, 31, …)`（`parser.vel:1156`）、`32`（attr）（`parser.vel:1171`）、`parser.vel:1119` 的 `new_node(nd, cx, 26, …)`，以及 `parse_atom` 里的 `new_node(nd, cx, 33, …)`（`parser.vel:1301`）写出来。**六个**文件带着同一个隐式枚举，不是三个：`resolve.vel`、`check.vel`、`eval.vel`、`emit.vel`、`emit_llvm.vel` 和 `dump.vel`，而一个新的节点种类只要在其中一个里被忘掉就会静默失败——检查器接受一个程序，emitter 随后把它丢在地上，`d_node` 则打印 `unknown-node-<k>`（`dump.vel:536`–`:539`）而不是报错。（`emit_llvm.vel` 是 `LLVM_PLAN.md` 的第二个代码生成器；本计划写在它存在之前，所以它的第 4 步要覆盖两个后端。）

一个真正的枚举加上穷尽的 `match`，会把这件事变成每一个分派点上的编译错误。那就是整个特性的验收测试，而且它是可度量的：**给节点种类枚举加上一个变体，构建必须在每一处需要处理它的地方失败，而其它什么都不改。**

## 表面语法

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
```

这些决定，每一条都带着它的理由：

- **分支是带大括号的块**，和 Vela 里其他每一个块一样。Python 的缩进恰恰是这门语言拒绝的东西，而给 `match` 单独一套排版规则，会成为那项拒绝被悄悄撤销的唯一地方。
- **这个版本里绑定是按位置的**：`Circle(r)` 绑定变体的那个唯一字段；`Rect(w, h)` 按声明顺序绑定。具名绑定（`Circle(radius: r)`）被刻意地*不*放进 v1——它需要调用已经有的那套"参数名"机制，而第一个版本应该是那个不可能被读错版本。
- **v1 里没有嵌套模式，也没有守卫。** `Circle(Circle(r))` 和 `Circle(r) if r > 0` 是拒绝用例，不是留待以后发现的缺口。
- **`else` 是穷尽性的逃生口**，而且它不接受绑定：`else { ... }` 匹配在最后。没有它，针对一个枚举的 `match` 必须点名每一个变体。
- **v1 里 `match` 是语句，不是表达式**，这与 Vela 的规则一致：产生值的表达式不能当语句。

## 含义

一个枚举值就是**一个标签加上至多一个负载变体**，具有值语义——和结构体一样。这保持了这门语言已经承诺的四条性质：

1. **没有堆。** 值住在帧里，像结构体一样；arena 不参与，也没有任何东西能比任何东西活得更久。
2. **没有 `None`，没有空变体。** `Empty` 和别的变体一样，它不是魔法值：一个忘了处理它的程序编译不过。
3. **没有异常。** `Result[T, E]`（流 ④.3）只是一个有两个变体的枚举，这就是为什么这个特性排在错误处理之前而不是之后。
4. **大小固定。** `sizeof` 在编译期已知，所以枚举可以放进 `Array`、当结构体字段、当参数，都不需要特殊处理。

v1 的范围之外，并且点名说清楚以免有人误解：两个枚举值之间的相等（`==`）、哈希、用 `print` 打印枚举、泛型负载，以及通过负载递归（`enum Tree { Node(Tree) }`——一个值类型不能包含它自己；那需要要么装箱表示，要么一个显式数组）。

## C 后端里的表示

一个带标签的结构体，每个枚举生成一份：

```c
typedef struct {
    int64_t vl_tag;                      /* 0, 1, 2 … in declaration order */
    union {
        struct { double radius; } Circle;
        struct { double w; double h; } Rect;
    } vl_u;
} vl_Shape;
```

`match` 变成一个 `switch (v.vl_tag)`，其分支就是各个分支块。当 match 是穷尽的时，这个 switch **不带 `default`**，所以给枚举加了一个变体却没写它的分支，会让 `cl` 在生成出的 C 上给出一个编译器警告——那是检查器自身穷尽性规则之下的第二张网。

C 的写法必须对一件事诚实：带构造函数体的 union 是 C 擅长的东西，而 Vela 没有构造函数，所以值是通过先赋标签、再赋负载字段建起来的，读的时候也一样。那在生成出的 C 里很啰嗦，在 Vela 里不可见，而这是一笔正确的取舍。

## 解释器

这才是真正的工作，而且应该直说：树遍历解释器的值是标量、字符串、数组和结构体，而枚举需要**带标签的值**——一个标签加上一个本身可能是结构体或枚举的负载。解释器是编译器的一部分，所以这是由这门语言对它自身正在编译语言的那部分所做的一次改动。预期这一步是耗时间的那一步，也预期它是会发现上面那套表示里的 bug 的那一步。

这一步的验收：语料中每一个 `run` 用例都由**两条**路径执行——编译与解释——并且输出逐字节一致，就像测试套件今天已经对每一个 `run` 用例要求的那样。

有件事代码已经替你定了，而且是向着本计划的：结构体值是一个 **cell**，它的 tag 是 `K_ST()`（kind 12），word 1 里是堆基址、word 2 里是结构体 id、word 3 里是字段数，由 `exec_construct`（`eval.vel:1357`–`:1396`）建出来，并由 `copy_value`（`eval.vel:133`）赋予值语义。所以枚举的负载不是一套新机制：它就是同一条"堆单元格加拷贝"的路径，只是把**标签**放在结构体用来放字段数的那个字里。构造点是 `eval_call`（`eval.vel:1478`）里 `exec_construct` 的调用者，而各分支所执行的语句是 `exec_stmt`（`eval.vel:1851`，每一个语句都要穿过的那一个函数）。

## 工作顺序，以不动点作为每一步的闸门

1. **解析** `enum` 声明与 `match` 语句进新的节点种类。不要语义；emitter 与解释器用清楚的消息拒绝它们。编译器仍然能编译自己，因为没有现成的程序用它们。
   今天解析器的两个事实决定了这一步从哪里开始。第一，`enum` 和 `match` **都不是关键字**：`keyword_id`（`selfhost/vela.vel:155`–`:226`）到 `extern` = 23 就结束了，所以这两个拼写都按普通 NAME token 词法化（`tk_ival == 0`，`parser.vel:166`–`:168`）。这给了这一步一个便宜的次序——*语句*形态完全不碰 lexer 就能识别，关键字是后面一件独立的改动。第二，要扩展的分派器是 `parse_stmt`（`parser.vel:403`–`:509`），它是每种语句一个 `if at_kw(...)` 臂，以 `parse_simple`（`parser.vel:763`）作为赋值或表达式语句的兜底。`match` 臂是语句开头的关键字，属于那个分派器；而变体构造 `Circle(1.0)` 完全不需要新语法，因为 `parse_postfix` 已经为它建出一个 kind-26 调用节点（`parser.vel:1119`）。
   新的种类号：今天用到的最高是 34，而 16–19 从来没有被用过（那是 `dump.vel` 末尾的兜底，不是预留），所以 35 和 36 不花任何代价、也不改动任何现存节点——但**不要**把它们放进 16–19 那个洞里。
2. **解析与检查**：枚举表、`match` 主体的类型、绑定类型，以及**穷尽性**（少了一个变体是点名它的错误；重复分支是错误；`else` 必须在最后）。
   "解析"这一步在这里不是可选的：结构体由 `resolve.vel` 里的 `add_struct`（`resolve.vel:307`）登记进 `vm_state.vel` 用 `stb_*`（`vm_state.vel:389`–`:403`）索引的结构体表，而枚举需要同样的处理——一张在任何函数体被判定之前就建好的表，因为判定 `match` 的检查器与走过整个程序的那个解析器不是同一趟。`match` 会到达的两个分派点是语句分派器 `ck_stmt`（`check.vel:2932`），经由 `ck_block`/`ck_body`（`check.vel:2913`/`:2924`），以及——对变体构造和主体表达式——`ck_expr`（`check.vel:1281`）。
3. **解释器**：带标签的值、模式绑定，以及 `match` 的执行。
4. **Emitter**：那个带标签的结构体与 `switch`，配一个拒绝用例来证明穷尽的 match 不生成 `default`。
   **今天是复数。** 代码生成器有两个，不是一个：`emit.vel` 里的 C11 emitter，以及 `emit_llvm.vel` 里 `LLVM_PLAN.md` 的 LLVM 后端，后者作为自己的片段被拼进链接出的编译器（`tools/link_selfhost.vel`，`part_name(7)`）。语句分派器是 `emit_stmt`（`emit.vel:1628`）和 `ll_stmt`（`emit_llvm.vel:2783`）；一个只落在 C 后端的枚举只会让每一个 `llvm-*` 用例拒绝，而那不等于这个特性被实现了。请为每个后端决定：这一步是两边都交付，还是让 LLVM 那一边明确出声拒绝（LLVM 后端自己的惯例：宁可拒绝，绝不猜），并在这步的收尾句里写明选了哪个。
5. **自食其力**：AST 的节点种类变成一个枚举，而 `check.vel`、`eval.vel` 和 `emit.vel` 用 `match` 在它上面分派。这一步才是为这个特性付账的一步，而它被安排在*最后*，是为了让这个语言特性在编译器依赖它之前先被语料证明。
   要让下面那条验收测试有意义，六个分派点都必须重写，而其中三个不在上面那句话里：`resolve.vel`（`walk_stmt` `resolve.vel:552`、`walk_expr` `resolve.vel:422`、`add_struct` `resolve.vel:307`）、`emit_llvm.vel`（`ll_stmt` `emit_llvm.vel:2783`、`ll_expr` `emit_llvm.vel:2270`）和 `dump.vel`（`d_node` `dump.vel:195`），后者兜底时打印 `unknown-node-<k>`（`dump.vel:536`）。2026-09-24 实测：把种类与字面量比较的行，`check.vel` 里 83 行、`emit.vel` 里 58 行、`emit_llvm.vel` 里 41 行、`dump.vel` 里 30 行、`eval.vel` 里 29 行、`resolve.vel` 里 23 行。一个没有对**全部六个**文件穷尽的 `match`，在漏掉的那个点上什么也没给编译器换来。
6. **每一步之后跑不动点**，并在第 2、3、4、5 步之后跑测试套件。
   还有一道更弱、但任何一步都不能跳过的闸门：`tools/link_selfhost.vel` 把十个 `parts/*.vel` 文件拼成 `selfhost/vm.vel`，而不动点编译的正是那个拼好的文件。在宣布任何一步为绿之前先重新链接（`tools/link_selfhost.exe --check` 会报告它是否过期），否则不动点量的是刚被编辑的那个片段的上一个修订。

## 验收测试，写成用例

**这张表里的大部分早就写好了，而且已经写好一段时间了。** `tests/accept/` 里躺着十二个 `.vel` 文件（它们在提交 `778ff3a` 里从插件的语料中搬了出来），覆盖下面每一个用例：`enum_payload_bind`、`enum_match_no_else`、`enum_missing_variant`、`enum_duplicate_arm`、`enum_binding_arity`、`enum_unknown_variant`、`enum_nested_pattern`、`enum_in_array`、`enum_self_payload`、`enum_exhaustive_switch`，另外还有 `enum_else_not_last` 和 `enum_match_non_enum`——这两个表里原本没有列。所以这一栏的工作**不是**写用例，而是：(a) 表后点名的那两个缺口，以及 (b) **把 `tests/accept/` 接进某个测试装置**：今天没有任何东西读那个目录。它不在 `tests/cases.txt` 里（那里根本没有 `enum` 开头的行），`tools/` 下的脚本也没有一个会走过它——真正被套件跑的只有 `tests/cases.txt` 和 `tests/run_tests.vel`。一个没有 runner 跑的用例是一个文件，不是一条测试。

| 用例 | 模式 | 它证明什么 |
|---|---|---|
| `enum_payload_bind` | run | 每个变体的负载按位置绑定并打印出来 |
| `enum_match_no_else` | run | 覆盖所有变体的穷尽 match 能运行 |
| `enum_missing_variant` | refuse | 漏掉一个变体的 match 在诊断里点名它 |
| `enum_duplicate_arm` | refuse | 重复的变体被拒绝 |
| `enum_binding_arity` | refuse | 对只有一个字段的变体写 `Circle(r, s)` 被拒绝 |
| `enum_unknown_variant` | refuse | 对一个没有 `Square` 的枚举写 `Square(x)` |
| `enum_nested_pattern` | refuse | `Circle(Circle(r))`——v1 范围之外，拒绝而不是半能用 |
| `enum_in_array` | run | 枚举放进 `Array[T, N]` 并具有值语义 |
| `enum_self_payload` | refuse | `enum Tree { Node(Tree) }`——一个无限的值类型 |
| `enum_exhaustive_switch` | native + dumps | 生成出的 C 在穷尽 switch 里没有 `default` |
| `enum_else_not_last` | refuse | **原表没有的用例**：`else` 写在某个变体分支之前被拒绝（设计决定：`else` 匹配在最后） |
| `enum_match_non_enum` | refuse | **原表没有的用例**：对非枚举主体写 `match` 被拒绝，而不是被静默地不处理 |
| `node_kinds_are_enum` | fixpoint | 第 5 步之后，编译器以节点种类的枚举形态编译自己 |

## 本文刻意不声称什么

它不让 Vela 更快。它不添加泛型（v1 里负载不能是类型参数）、集合或错误处理——它是那三样东西被搭建起来的材料。它也不从后端移除任何一项检查；性能杠杆是流 ⑤，那与本文相互独立。
