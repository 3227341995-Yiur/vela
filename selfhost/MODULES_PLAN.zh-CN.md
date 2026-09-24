# 模块与 `import`——计划、决策，以及每一步必须证明什么

[English](MODULES_PLAN.md) | **简体中文**

<!--
源文件 : MODULES_PLAN.md
源文件字节 : 18668
源文件 SHA256 : 66e3bb2a9ea69d60b03dea1f517c8753eb2060947734a17148b8e2ae2e3b77b9
翻译日期 : 2026-09-25
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

`ROADMAP.md` 的 Stream 2 第 2 条已经把目标和验收标准写在同一个地方，所以本文件不复述目标；它把目标变成一架梯子，并把那些**靠写第一个函数是发现不了的**决策写下来。它先于代码存在，理由正是这个仓库反复重学的那一条：**没有被测量的声称不存在**，而只存在某个 agent 脑子里的设计，不是读者可以反对的设计。

## 1. 语言自己说的状态（文档）

| 什么 | 在哪 | 原文 |
|---|---|---|
| 保留字 | `SPEC.md` §1.3，62-66 行 | "`import`、`from` 和 `as` 在 0.1 里**不是**关键字：它们是普通标识符（`mut import: int = 1` 是合法声明），没有任何东西拒绝它们。模块系统会占用它们，所以它们是**由文档**保留、而不是由词法器保留——今天把它们当名字用的程序，在模块到来的那天需要改名。" |
| 「没有模块系统」是一条设计事实 | `DESIGN.md` §2，123-124 行 | "`tools/link_selfhost.vel` 把各部分拼进 `selfhost/vm.vel`，因为 Vela 0.1 没有模块系统（§7.1）。" |
| 那些变通做法，用它自己的话 | `tools/link_selfhost.vel` 1-31 行 | "Vela 0.1 没有模块系统（`import` 被保留但未实现），所以一个多文件的 Vela 程序是一次构建期拼接——C 用 `#include` 玩的是同一个把戏，而**它会在 Vela 长出模块的那天消失**。" |
| 本文件为之存在的验收 | `ROADMAP.md` Stream 2 第 2 条 | "一个两文件程序能编译；自举构建不再需要那个链接器；IDE 能解析定义在另一个文件里的名字" |
| 同一句话，从解析器那一侧 | `selfhost/vm.vel` 1320 行（由 `selfhost/parts/parser.vel` 生成） | "…system（`import` 被保留但未实现），所以 `tools/link_selfhost.vel`…" |

## 2. 今天为真的事实，全部是实测（2026-09-25，`selfhost/build/vm.exe` 870 400 B）

下面每一行都是**跑出来的**，不是读出来的。命令都写出来了，下一个读者可以自己重测。

**2.1 `import` 就是普通标识符，和 `SPEC.md` §1.3 说的完全一致。** 一个含 `mut import: int = 1`、`mut from: int = 2`、`mut as: int = 3` 然后 `print(import + from + as)` 的程序：

    vm.exe run   <该程序>   ->  exit 0，打印 6
    vm.exe check <该程序>   ->  exit 0，打印 "ok"

**2.2 模块系统想要的那个语法，今天是语法错误**，而且报错说的是错的东西：

    vm.exe check <在 main() 里写 import "other.vel">   ->  exit 2
        vela: syntax error: expected a newline between statements, found 'other.vel'
          at line 3

`import` 被当成一个**名字表达式**解析，后面那个字符串字面量无处可去。抱怨针对的是字符串的内容，而不是这个构造。

**2.3 命令行在每个模式下都只接受一个文件。** 不带参数跑 `vm.exe` 会打印：

    usage: vela <lex|count|parse|nodes|emit-c|run|check|debug|build|build-c|emit-llvm|build-llvm> <file.vel>

而 `main()`（`selfhost/parts/vm_main.vel` 1189-1216 行）把一个 `arg(2)` 读进一个 `src: str`，然后在自己那个栈帧上声明所有池：tokens 1 572 864、token 浮点 65 536、节点 1 310 720 个 int（stride 10 → 131 072 个节点）、类型 262 144、字面量浮点 65 536。

**2.4 合并后的程序有空间，这是量出来的。** 这棵树编译过的最大输入是**连接后**的编译器 `selfhost/vm.vel`：

    vm.exe count selfhost/vm.vel            ->  tokens 130526
    vm.exe count selfhost/parts/parser.vel  ->  tokens 12641
    vm.exe count selfhost/parts/check.vel   ->  tokens 23750
    vm.exe count selfhost/vela.vel          ->  tokens 4164

130 526 / 1 572 864 是 **12 倍**余量，所以把若干个文件装进同一个 token 数组不是池子问题。这也正是**链接器**自己那个问题不会转移过来的原因：`tools/link_selfhost.vel` 之所以必须被编译再运行、而不能被解释，是因为**它用上千次拼接装配出一个 338 KB 的文件**、耗尽了字符串表。装载器读 N 个文件、调用 N 次 `read_text`，它不做拼接。

**2.5 真正存在的那个上限：8192 个驻留名字。** `VELA_INTERN_CAP 8192`（`runtime/vela_runtime.h` 688-698 行）是内容寻址的，耗尽它是一次硬 `panic("string table full")`。模块系统只有在被导入的文件声明了程序原本没有的名字时才会增加名字，而**整棵树**目前装得下，所以这是一个留到第 5 步（编译器导入它自己）去测的风险，而不是现在的阻塞项。测量时顺手发现的一处小错：`selfhost/AST.md` 304 行把这个上限引作 `:442`，而它不在那里。

**2.6 诊断在文件名这件事上并不一致，而这正是关键。** 解析器和词法器只打印一个行号：

    vela: syntax error: adjacent string literals are not allowed
      at line 3

（`selfhost/parts/parser.vel` 89-100 行）。而解析器之后的三个阶段——resolver、checker、以及解释器的 trap——打印**路径**和行号：

    vela: safety error: cannot rebind array 'a'
      at tests/build/check_cases/array_rebind.vel:4

（`selfhost/parts/resolve.vel` 35-46 行；`selfhost/parts/eval.vel` 27-36 行）。golden 逐字节记录这段文本——`tests/golden/adjacent_string_literals.err` 与 `tests/golden/array_rebind.err` 就是上面两个例子——所以**任何对单文件诊断的改动，按定义就是一次语料回归。**

**2.7 发出的 C 在每个调用点内嵌了路径。** `emit_location`（`selfhost/parts/emit.vel` 339-349 行）把源路径写成 C 字面量，供运行时 panic 消息使用，生成的 C 里能看见：

    vela_add_range((vl_tk_off(...)), (1LL), INT64_MIN, INT64_MAX, "selfhost/vm.vel", 1519)

一个程序、一个路径、成千上万个调用点。换成两个文件，被导入文件里的每个点都会带着**根文件**的路径。

**2.8 构建的形状，因为第 5 步会改它。** `tools/build.ps1` 跑八步；本计划涉及的是第 3 步（`build.ps1` 613-615 行：构建 `tools/link_selfhost.vel`，再运行它写出 `selfhost/vm.vel`）、第 5 步（819 行：构建独立的词法器 `selfhost/vela.vel`），以及第 6 步（不动点：向两代编译器索取 `selfhost/vm.vel` 的 C，比较字节与哈希）。

**2.9 词法器的关键字表是一张编号表**（`selfhost/vela.vel` 158-244 行）：`def`=1 … `extern`=23、`enum`=24、`match`=25，而 24-25 上方那段注释记录了本计划必须遵守的做法——在 `enum`/`match` 变成关键字之前，有人把树里每个 `.vel` 文件都检查了一遍，看有没有把它们当名字用，注释把这件事写出来了。

## 3. 决策

每条决策都点名它否掉的替代方案和理由，因为没有替代方案的决策只是偏好。

**D1——语法是顶层 `import "相对/路径.vel"`。** 用路径**字符串**，不用裸名字。裸名字（`import helpers`）需要一条查找规则——当前目录？某个根？一张列表？——而这个仓库的规则是：编译器不猜。字符串让文件身份变成显式的，让「相对于导入它的那个文件」成为唯一的规则，并且正好给装载器它需要的那串字符。`import` 成为**关键字**（D2）；`from`/`as` 形式不在范围内（§6），在它们自己那一步到来之前保持普通标识符。

**D2——`import` 变成关键字，这是本计划明知故犯的一次破坏性改动。** `SPEC.md` §1.3 已经承诺过它（"今天把它们当名字用的程序，在模块到来的那天需要改名"），而 2.1 量出了这样的程序**今天**是合法的。替代方案——在语句位置识别一个 `import`——就是 `enum`/`match` 已经付过代价的那种歧义（`selfhost/vela.vel` 228-236 行：`match(x)` 是调用、`match = 1` 是赋值）。把词变成关键字的那一步欠这棵树一次测量：哪些 `.vel` 文件变了判决，如果没有，也要说没有。

**D3——语义是扁平的、整文件可见的，也就是链接器语义在 AST 层面的样子。** `import "b.vel"` 让 `b.vel` 的**每一个**顶层声明对整个程序可见，包括 `b.vel` 自己导入的东西，传递地可见。没有限定访问，没有按模块划分的名字空间。这刻意的就是这个能退休掉链接器的最小语义：今天的拼接产生的可见性正是这个，所以第 5 步是**机制**的改动而不是含义的改动，而不动点就是关于它的证据。限定名、私有性和选择性导入都在 §6。

**D4——声明顺序由装载器决定，不由导入者决定。** 被导入文件的声明排在**前面**，最外层导入最先，然后才是根文件自己的——正是链接器今天产生的顺序，也是 `tools/link_selfhost.vel` 说它需要的顺序（"一个函数必须在使用它的地方之前被声明"）。所以导入行在导入文件里的位置，除了导入本身之外不携带含义；期待 Python 那样顺序语义的读者，会得到相同的**可见性**和不同的**顺序**，本计划把这件事说出来，而不是留给别人去发现。

**D5——`main` 只属于根文件。** 一个程序就是一个程序，所以被导入文件里的第二个 `main` 会被点名拒绝（连同两条路径），而不是悄悄遮蔽或悄悄胜出。装载器知道哪个文件是根，因为它是命令行给的那个。

**D6——每条路径只装载一次，环被点名拒绝。** 装载器以「相对导入文件的目录解析之后」的路径为键；同一个路径的第二次导入是空操作（替代方案——拒绝重复——会在不带来任何好处的情况下打断菱形导入），而 `a → b → a` 会被拒绝，并打印成一条链，因为扁平合并没有别的诚实答案。

**D7——文件身份是一条行**基数**，不是一个新的节点字段。** 这是最难的部分，而答案被两条实测事实逼出来了。节点是 stride 10（`selfhost/parts/vm_state.vel` 543-561 行：kind、a、b、c、`d`、line、flags、nx、e、f），第 4 个字（`d`）不是空的——它是一个 `for` 节点的步长；`e`/`f` 是 resolver 的。`struct VM` 不能加数组字段（"Vela 0.1 的结构体字段不能是数组"，`vm_state.vel` 420-422 行），而且字段必须**追加**，因为 `vm_new()` 是位置构造（436-443 行）。所以：**装载器以行基数来词法化第 k 个文件**，`BASE_0 = 0`、`BASE_k = BASE_{k-1} + lines(文件 k-1) + 1`，并保留一张 `(base, path)` 小表——表放在 `mem` 的保留区域（调试器给它的断点表用的就是这个模式，`vm_state.vel` 420-422 行），计数与游标追加到 `struct VM`。每个打印或发出位置的地方都问 `path_for_line(vm, mem, line)`。只有一个文件时，表里正好一项、base 为 0、路径就是那个参数，所以**每一份既有 golden 和每一个发出的 C 字节都不变**——这是让这一步在这棵树里安全可行的唯一性质。

**D8——后端一个一个地跟上，而默认路径宁可拒绝也不说谎。** 现在 `build` 就是 LLVM 路径（`ROADMAP.md` 的 "pure-bred" 一行），所以一个多文件程序必须在第 5 步之前抵达 LLVM 后端；在那之前，`build`、`build-llvm` 和 `emit-llvm` **按名字**拒绝一个多文件程序，并在 `tests/llvm-refusals.txt` 里留一行——就是那七个 `parallel for` 拒绝用的同一本双侧账本。一个声称并行却串行跑起来的循环，是这个项目绝不能发布的东西；一个声称是两个文件却当一个文件编译的程序，是同一种谎言。

## 4. 梯子

每一步都写明：改什么、哪条命令决定它、验收是什么、以及它落地的那一刻哪些文档变成假话。

### 第 1 步——语法，和一个点自己名字的拒绝

*改动。* `import` 作为 26 加入 `keyword_id`（`selfhost/vela.vel` 158-244 行）；解析器只在顶层接受 `import "路径"` 并为它建一个节点（接在 `parse_module` 之后，`selfhost/parts/parser.vel` 1611-1646 行，它用 `nx` 把顶层语句连成链）；每一个本该**对它采取行动**的模式按名字拒绝。
*命令。* `tools\build.ps1`（这个改动必须活过不动点），然后 `tools\refreeze.ps1`（196 个用例的语料），然后在树上每个 `.vel` 文件上跑 `vm.exe check`，来偿还 D2 欠下的那笔账：**哪些文件变了判决，或者没有**。
*验收。* 含 `import "b.vel"` 的程序被拒绝时，消息点名这个构造和路径，而不是 "expected a newline"；`mut import: int = 1` 现在被拒（关键字冲突），而那条拒绝是一个语料用例，因为它是 D2 可观测的代价；语料是绿的；判决变化清单在步骤自己的证据文件里。
*文档。* `SPEC.md` §1.3 那一段，以及它的中文孪生；`selfhost/vela.vel` / `parser.vel` 里 "reserved but not implemented" 的注释。

### 第 2 步——装载、合并、解释执行

*改动。* 装载器（放在驱动侧 `selfhost/parts/vm_main.vel`，因为今天只有这一部分做 I/O）：相对导入文件的目录解析路径、读取、带行基数词法化、解析进同一批池子、把链接到前面、递归。源表与 `path_for_line`（D7）。`check`、`run` 和 `parse` 接受多文件程序。
*命令。* `vm.exe run <两文件程序>`；对同一个跑 `vm.exe check`；语料。
*验收。* 一个两文件程序打印出和它单文件孪生相同的答案；缺文件、环、重复顶层名字和第二个 `main` 各自被拒绝，且消息点名肇事的文件；在被导入文件里抛出的诊断说的是 `at tests/build/import_helper.vel:3` 而不是根路径；语料是绿的，而 C/LLVM 模式仍然按名字拒绝多文件程序。
*文档。* `DESIGN.md` §2 124 行、`README.md` 的 "no module system" 段落。

### 第 3 步——C 后端

*改动。* `emit_location` 和各运行时调用点携带**节点自己的**路径（D7 的表）；`build-c` 与 `emit-c` 接受多文件程序。
*命令。* `vm.exe build-c <两文件程序>`，运行那个可执行文件；把它的字节与 `vm.exe run` 的、以及与单文件孪生的对比；在发出的 C 里 grep 被导入文件的名字和正确的行号。
*验收。* 三方一致（解释器、C、单文件孪生）；被导入文件里的运行期 trap 打印被导入文件的路径；不动点与语料仍然成立。

### 第 4 步——LLVM 后端

*改动。* 与第 3 步相同，针对 `emit_llvm.vel`——它的模块级路径是单个 `pfile` 字段（`selfhost/parts/emit_llvm.vel` 13846 行附近），因此需要同样的处理。在这一步落地之前，`build` 按名字拒绝多文件程序（D8）。
*命令。* `vm.exe build <两文件程序>`；`tools\llvm-no-cl.ps1`；`tests\llvm-refusals.txt` 账本。
*验收。* 默认路径在不启动任何 C 编译器的情况下构建出那个两文件程序，其输出与解释器逐字节一致。

### 第 5 步——自举切换，链接器就是在这里退休的

*改动。* `selfhost/vm.vel` 不再是生成的：它变成一个小文件，按 `tools/link_selfhost.vel` 今天列出的顺序导入词法法器那一半与十个部分（state、parser、resolve、check、eval、emit、llvm_shim、emit_llvm、dump、vm_main）。词法器的驱动从 `selfhost/vela.vel` 搬进它自己的文件，这样被导入的词法器就没有 `main`（D5）——这就是那条「取上半截」的规则随着它存在的理由一起消失。`tools/build.ps1` 第 3 步从「构建并运行链接器」变成「构建编译器」，`tools/link_selfhost.vel` 退休。
*命令。* `tools\build.ps1`（全部八步，包括不动点），然后 `tools\refreeze.ps1`，然后 `tools\llvm-no-cl.ps1`，然后 `tools\check-coherence.ps1`。
*验收。* 不动点仍然成立：两代编译器为 `selfhost/vm.vel` 发出逐字节相同的 C。`selfhost/vela.vel` 的独立词法器仍然能构建、能词法化。8192 驻留名的那次测量（2.5）在真东西上做一次。`RESULT: ok`。

### 第 6 步——编辑器

*改动。* 插件的解析器学会 `import`（它逐节点复刻编译器的语法树，`FEATURE_PARITY.md` 第 5 行），它的名字解析跨文件可达（第 10 行的 `VelaTargets.declarationFor`）。
*命令。* `idea-plugin\ast-diff.ps1`、`psi-tree-diff.ps1`、`harness.ps1`，以及插件自己的闸门；验收在编译器的第 2 步形状冻结之后再写，因为两棵语法树必须逐字节一致。

## 5. 什么会让这份计划变成错的

| 假设 | 会怎么现形 | 由谁判定 |
|---|---|---|
| 合并后的程序装得进池子 | 一次点名池子的耗尽 panic | 第 5 步（编译器导入它自己是这棵树最大的输入） |
| 一张行基数表足以表达身份 | 某个 trap 或诊断点错文件 | 第 2-4 步，包括第 3 步对发出的 C 的 grep |
| 解释器能装载 N 个文件 | 装载过程中的 `string table full` | 第 5 步的测量（2.5） |
| 扁平合并真的就是链接器的语义 | 今天能编译、第 5 步之后不能（或反过来）的程序 | 第 5 步的不动点，加上 `tools\refreeze.ps1` |
| 树里没有程序把 `import`/`from`/`as` 当名字 | 第 1 步里判决发生变化的某个文件 | 第 1 步的判决扫描（D2） |
| 插件的解析器跟得上 | `ast-diff.ps1` 报出一个无法解释的差异 | 第 6 步 |

## 6. 刻意不在这份计划里的东西

包与任何形式的仓库；查找路径；限定访问（`mod.name`）；私有性（`pub`/`export`）；选择性导入（`from "a.vel" import x`）；别名（`as`）；再导出；条件编译；增量或并行编译；循环导入（被拒绝，D6）。每一条都是自带证据负担的语言决策，而它们中没有一条是 `ROADMAP.md` 已经写下的那个验收所需要的。`from` 与 `as` 在被它们各自的那一步认领之前，保持「由文档保留的标识符」这一地位。

## 7. 账本

| 步骤 | 状态 | 证据 |
|---|---|---|
| 0. 设计，对照实测 | **完成** 2026-09-25 | 上面 §2：每个数字都在 `selfhost/build/vm.exe` 870 400 B 上跑过 |
| 1. 语法 + 一个点名的拒绝 | 未开始 | — |
| 2. 装载、合并、解释执行 | 未开始 | — |
| 3. C 后端 | 未开始 | — |
| 4. LLVM 后端 | 未开始 | — |
| 5. 自举切换 | 未开始 | — |
| 6. 编辑器 | 未开始 | — |
