# 模块与 `import`——计划、决策，以及每一步必须证明什么

[English](MODULES_PLAN.md) | **简体中文**

<!--
源文件 : MODULES_PLAN.md
源文件字节 : 37137
源文件 SHA256 : 2981f03d8b3de944031d50880bd46f1445d56b1abf564dbc53b0f22618d6a0ae
翻译日期 : 2026-10-30
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

**这一节是第 1 步落地*之前*的状态**——它是这份设计的实测底稿，按当时测得的样子保留。
第 1 步落地时发生了什么，见 §8。

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

**2.9 语料已经把这个功能写出来了，而且记录了今天的结果。** 之前某一轮把提案留在这个仓库存放提案的地方：用例里。`tests/accept/modules/` 有九个文件，`tests/accept/BASELINE.txt` 20-28 行逐个记下判决：

    mathlib.vel                        REFUSED   vela: type error: no 'main' function
    module_cycle_a.vel                 REFUSED   syntax error: ... found 'module_cycle_b'
    module_cycle_b.vel                 REFUSED   syntax error: ... found 'module_cycle_a'
    module_import_from.vel             REFUSED   syntax error: ... found 'mathlib'
    module_import_missing.vel          REFUSED   syntax error: ... found 'nosuchmodule'
    module_import_qualified.vel        REFUSED   syntax error: ... found 'mathlib'
    module_import_reserved_name.vel    ACCEPTED
    module_import_twice.vel            REFUSED   syntax error: ... found 'mathlib'
    module_import_unknown_name.vel     REFUSED   syntax error: ... found 'mathlib'

它们不是装饰，它们在三处改变了这份计划。(i) **它们提议的语法不是 D1 选的那个**：`module_import_from.vel` 写的是 `from mathlib import add`，`module_import_qualified.vel` 写的是 `import mathlib` 加 `mathlib.add(2, 3)`——一个**模块名**，在导入它的文件旁边找到，以及限定访问。(ii) **它们已经包含了第 2 步欠消息的那些错误用例**：模块缺失、名字未知、同一个模块被导入两次，以及两文件构成的环。(iii) `module_import_reserved_name.vel` 就是 D2 的代价，在决策做出之前就被写成了用例——这个文件今天能跑（实测 `exit 0`），而它自己的注释说两种诚实结局是「这个用例翻转成被拒」或者「因为关键字是上下文式的而把这个用例删掉」，无论哪种，`SPEC.md` 里那句话都要给个答复。baseline 文件是一条边界，所以这份梯子里任何改动这九行之一的步骤，都欠它的证据一份新的 baseline。

**2.10 词法器的关键字表是一张编号表**（`selfhost/vela.vel` 158-244 行）：`def`=1 … `extern`=23、`enum`=24、`match`=25，而 24-25 上方那段注释记录了本计划必须遵守的做法——在 `enum`/`match` 变成关键字之前，有人把树里每个 `.vel` 文件都检查了一遍，看有没有把它们当名字用，注释把这件事写出来了。

## 3. 决策

每条决策都点名它否掉的替代方案和理由，因为没有替代方案的决策只是偏好。

**D1——第一个落地形态是顶层 `import "相对/路径.vel"`，语料里那两种拼法是**目标**而不是第一步。** 用路径**字符串**，不用裸名字。裸名字（`import helpers`）需要一条查找规则——当前目录？某个根？一张列表？——而这个仓库的规则是：编译器不猜。字符串让文件身份变成显式的，让「相对于导入它的那个文件」成为唯一的规则，并且正好给装载器它需要的那串字符。`import` 成为**关键字**（D2）。语料提议的东西更丰富（2.9）：`from mathlib import add`，以及 `import mathlib` 配 `mathlib.add(2, 3)`。它们在自己的那一步到来（第 6 步），在那之前**它们那两个用例保持红，而消息会变**——`module_import_from.vel` 和 `module_import_qualified.vel` 不再说 "expected a newline between statements"，而开始说：*模块名*还不是这个拼法。这个顺序是诚实的：查找规则和名字空间都是自带证据负担的决策，而 `module_import_qualified.vel` 自己的注释已经写明「如果只有一种形式先落地」该怎么办——「这两个用例里正好一个留红，而报告说清是哪一个」。这份文件就是那份报告。

**D2——`import` 变成关键字，这是本计划明知故犯的一次破坏性改动。** `SPEC.md` §1.3 已经承诺过它（"今天把它们当名字用的程序，在模块到来的那天需要改名"），而 2.1 量出了这样的程序**今天**是合法的。替代方案——在语句位置识别一个 `import`——就是 `enum`/`match` 已经付过代价的那种歧义（`selfhost/vela.vel` 228-236 行：`match(x)` 是调用、`match = 1` 是赋值）。把词变成关键字的那一步欠这棵树一次测量：哪些 `.vel` 文件变了判决，如果没有，也要说没有——而语料已经把其中一个写下来了：`module_import_reserved_name.vel` 之所以单独存在，正是为了让这个代价成为一条被记录的行，而不是一次发现。

**D3——语义是扁平的、整文件可见的，也就是链接器语义在 AST 层面的样子。** `import "b.vel"` 让 `b.vel` 的**每一个**顶层声明对整个程序可见，包括 `b.vel` 自己导入的东西，传递地可见。**在这个语义里**没有限定访问，没有按模块划分的名字空间。这刻意的就是这个能退休掉链接器的最小语义：今天的拼接产生的可见性正是这个，所以第 5 步是**机制**的改动而不是含义的改动，而不动点就是关于它的证据。限定访问不是被拒绝，而是被**排期**：语料为它留了一个用例（`module_import_qualified.vel`），而为这份计划做的侦察量出了它为什么是清单上最难的一件事——resolver 的键是**裸的驻留句柄**（在整个进程里按内容寻址，`runtime/vela_runtime.h` 688-705 行），所以每一张存储或查找名字的表（作用域栈、结构体表、枚举与变体表，以及那条本该表示 `mathlib.add` 的 attr 路径、节点 kind 32）都得学会一个模块分量，而且不能破坏遮蔽。私有性、选择性导入和别名仍然留在 §6。

**D4——声明顺序由装载器决定，不由导入者决定。** 被导入文件的声明排在**前面**，最外层导入最先，然后才是根文件自己的——正是链接器今天产生的顺序，也是 `tools/link_selfhost.vel` 说它需要的顺序（"一个函数必须在使用它的地方之前被声明"）。所以导入行在导入文件里的位置，除了导入本身之外不携带含义；期待 Python 那样顺序语义的读者，会得到相同的**可见性**和不同的**顺序**，本计划把这件事说出来，而不是留给别人去发现。

**D5——`main` 只属于根文件。** 一个程序就是一个程序，所以被导入文件里的第二个 `main` 会被点名拒绝（连同两条路径），而不是悄悄遮蔽或悄悄胜出。装载器知道哪个文件是根，因为它是命令行给的那个。

**D6——每条路径只装载一次，环被点名拒绝。** 装载器以「相对导入文件的目录解析之后」的路径为键；同一个路径的第二次导入是空操作（替代方案——拒绝重复——会在不带来任何好处的情况下打断菱形导入），而 `a → b → a` 会被拒绝，并打印成一条链，因为扁平合并没有别的诚实答案。

**D7——文件身份是一条行**基数**，不是一个新的节点字段。** 这是最难的部分，而答案被两条实测事实逼出来了。节点是 stride 10（`selfhost/parts/vm_state.vel` 543-561 行：kind、a、b、c、`d`、line、flags、nx、e、f），第 4 个字（`d`）不是空的——它是一个 `for` 节点的步长；`e`/`f` 是 resolver 的。`struct VM` 不能加数组字段（"Vela 0.1 的结构体字段不能是数组"，`vm_state.vel` 420-422 行），而且字段必须**追加**，因为 `vm_new()` 是位置构造（436-443 行）。所以：**装载器以行基数来词法化第 k 个文件**，`BASE_0 = 0`、`BASE_k = BASE_{k-1} + lines(文件 k-1) + 1`，并保留一张 `(base, path)` 小表——表放在 `mem` 的保留区域（调试器给它的断点表用的就是这个模式，`vm_state.vel` 420-422 行），计数与游标追加到 `struct VM`。每个打印或发出位置的地方都问 `path_for_line(vm, mem, line)`。只有一个文件时，表里正好一项、base 为 0、路径就是那个参数，所以**每一份既有 golden 和每一个发出的 C 字节都不变**——这是让这一步在这棵树里安全可行的唯一性质。

**D8——后端一个一个地跟上，而默认路径宁可拒绝也不说谎。** 现在 `build` 就是 LLVM 路径（`ROADMAP.md` 的 "pure-bred" 一行），所以一个多文件程序必须在第 5 步之前抵达 LLVM 后端；在那之前，`build`、`build-llvm` 和 `emit-llvm` **按名字**拒绝一个多文件程序，并在 `tests/llvm-refusals.txt` 里留一行——就是那七个 `parallel for` 拒绝用的同一本双侧账本。一个声称并行却串行跑起来的循环，是这个项目绝不能发布的东西；一个声称是两个文件却当一个文件编译的程序，是同一种谎言。

## 4. 梯子

每一步都写明：改什么、哪条命令决定它、验收是什么、以及它落地的那一刻哪些文档变成假话。

### 第 1 步——语法，和一个点自己名字的拒绝

*改动。* `import` 作为 26 加入 `keyword_id`（`selfhost/vela.vel`）；解析器在语句位置上认出它，并把它的**每一种**形态都按名字拒绝：路径拼法（`import "path/file.vel"`，连读者写的路径一起回显）、裸模块名（`import mathlib`，指向第 6 步）、`from M import x`（按「三个词」的形状识别，因为 `from` 仍然是标识符）、块内的 `import`，以及站在本该是名字的位置上的 `import`（`mut import: int = 1`、函数名、参数、枚举变体），最后这一类用的是 `SPEC.md` §1.3 早就承诺过的那句话。不为它建节点，也没有任何模式对它采取行动：背后没有装载器时，一个「解析了却被忽略」的 `import`，正是一个「嘴上说自己有两个文件、实际按一个文件编译」的程序——D8 存在的意义就是拒绝这一类谎言。
*命令。* `tools\build.ps1`（这个改动必须活过不动点），然后 `tools\refreeze.ps1`（语料），然后在树上**每一个被跟踪的** `.vel` 文件上跑 `vm.exe check`，来偿还 D2 欠下的那笔账：**哪些文件变了判决，或者没有**。
*验收。* 含 `import "b.vel"` 的程序被拒绝时，消息点名这个构造和路径；`mut import: int = 1` 现在作为关键字冲突被拒；语料是绿的；判决变化清单在 §8。**而语料自己那九行会按已经记录下来的方式移动**（2.9）：`module_import_reserved_name.vel` 从 `ACCEPTED` 变成 `REFUSED`（这就是 D2 的代价，正是那个文件自己的注释要求的），那五条 `... found 'mathlib'` / `'nosuchmodule'` 消息变成上面那两种具名拒绝，两个环用例也同样变成裸名拒绝——它们自己的主张（一条点名那个环的诊断）在装载器存在之前无法兑现，所以它仍然欠给第 2 步。新的 `tests/accept/BASELINE.txt` 是本步骤证据的一部分，因为旧的那份就此变成假话。
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

### 第 6 步——语料里的那两种拼法：模块**名**，以及 `mathlib.add(2, 3)`

*改动。* `from mathlib import add` 与 `import mathlib`（2.9）把装载器的路径参数变成**名字**，并配一条**由语言说出来**而不是猜出来的查找规则——「在导入它的文件旁边」就是语料说的那句话，它在 `SPEC.md` 里是一句话，不是一张路径列表。限定访问是深的那一半：今天 `name.field` 形状的节点是对**值**读属性（节点 kind 32），经接收者的结构体 id 解析，所以 `mathlib.add` 必须走**另一条**解析路线——模块自己的名字空间；而侦察的结论是这是整架梯子上改动面最宽的一步，因为名字在任何被存储的地方都是裸的驻留句柄（`resolve.vel` 的作用域栈、结构体/枚举/变体表、`STB_NAME`/`ENB_NAME`/`VRB_NAME`），而 `intern` 在整个进程里按内容寻址。
*命令。* 点名那两个语料用例（`tests/accept/modules/module_import_from.vel`、`module_import_qualified.vel`），加上整套套件与不动点。
*验收。* 两个文件都能编译，并逐字节打印出它们自己注释里承诺的东西（`5 / 8` 与 `5 / 9`），`module_import_unknown_name.vel` 仍然被拒，`import mathlib` 两次仍然只装载一次。`tests/accept/BASELINE.txt` 重新生成，它的 diff 就是本步骤的证据。
*为什么它不是第 1 步。* 查找规则和名字空间都是语言决策；扁平形态先落地，好让两文件程序先存在，而这一步正是语料那两个红用例转绿的地方。

### 第 7 步——编辑器

*改动。* 插件的解析器学会 `import`（它逐节点复刻编译器的语法树，`FEATURE_PARITY.md` 第 5 行），它的名字解析跨文件可达（第 10 行的 `VelaTargets.declarationFor`），等限定形态存在之后也包括它。
*命令。* `idea-plugin\ast-diff.ps1`、`psi-tree-diff.ps1`、`harness.ps1`，以及插件自己的闸门；验收在编译器的第 2 步形状冻结之后再写，因为两棵语法树必须逐字节一致。

## 5. 什么会让这份计划变成错的

| 假设 | 会怎么现形 | 由谁判定 |
|---|---|---|
| 合并后的程序装得进池子 | 一次点名池子的耗尽 panic | 第 5 步（编译器导入它自己是这棵树最大的输入） |
| 一张行基数表足以表达身份 | 某个 trap 或诊断点错文件 | 第 2-4 步，包括第 3 步对发出的 C 的 grep |
| 解释器能装载 N 个文件 | 装载过程中的 `string table full` | 第 5 步的测量（2.5） |
| 扁平合并真的就是链接器的语义 | 今天能编译、第 5 步之后不能（或反过来）的程序 | 第 5 步的不动点，加上 `tools\refreeze.ps1` |
| 树里没有程序把 `import`/`from`/`as` 当名字 | 第 1 步里判决发生变化的某个文件 | 第 1 步的判决扫描（D2）——而 2.9 已经点名了一个预期会变的文件 |
| 裸模块名就够用，限定访问可以等 | 语料那两个红用例红得比梯子说的更久 | 第 6 步，它的全部工作就是那两个文件 |
| 插件的解析器跟得上 | `ast-diff.ps1` 报出一个无法解释的差异 | 第 7 步 |

## 6. 刻意不在这份计划里的东西

包与任何形式的仓库；**路径列表**；私有性（`pub`/`export`）；别名（`as`）；再导出；条件编译；增量或并行编译；循环导入（被拒绝，D6）。每一条都是自带证据负担的语言决策，而它们中没有一条是 `ROADMAP.md` 已经写下的那个验收所需要的。有两件看起来属于这份清单的事**不**属于：在导入文件旁边找到的裸模块名，以及限定访问（`mathlib.add`）——因为语料已经为两者写好了用例（2.9），它们是第 6 步。`from` 与 `as` 作为**关键字**也在第 6 步被认领；它们拼出来的形态属于那一步，在那之前它们保持「由文档保留的标识符」这一地位。

## 7. 账本

| 步骤 | 状态 | 证据 |
|---|---|---|
| 0. 设计，对照实测 | **完成** 2026-09-25 | 上面 §2：每个数字都在 `selfhost/build/vm.exe` 870 400 B 上跑过，加上语料那九条已记录的判决（2.9） |
| 1. 语法 + 一个点名的拒绝 | **完成** 2026-10-20 | §8：构建与不动点 `RESULT: ok`，语料 228/228，338 个被跟踪文件里只有 1 个判决变化，`tests/accept/BASELINE.txt` 已重新记录 |
| 2. 装载、合并、解释执行 | **完成** 2026-10-20 | §8.9：构建 `RESULT: ok`，语料 235/235，四种拒绝都点名，被导入文件里的诊断说出它自己的文件 |
| 3. C 后端 | 未开始 | — |
| 4. LLVM 后端 | 未开始 | §8.1 是这一步路上撞到的后端缺陷：大程序里两个调用的 `or` |
| 5. 自举切换 | 未开始 | — |
| 6. 模块名、`from M import x`、以及 `mathlib.add` | 未开始 | `tests/accept/modules/module_import_from.vel`、`module_import_qualified.vel` |
| 7. 编辑器 | **已开始** 2026-10-20：**解析**那一半 | `idea-plugin/CHANGELOG.md` 0.1.13 与 §8.14：`import` 是关键字、是节点（kind 40）、是一行 dump；`ast-diff.ps1` **167 个文件，148 个一致，0 个不同，exit 0**；`psi-tree-diff.ps1` **167/167，failed 0**。跨文件**名字解析**仍然欠着 |

## 8. 第 1 步，实测（2026-10-20）

下面每一行都是**跑出来的**，不是读出来的；命令都写出来了，下一个读者可以自己重测。

| 项目 | 命令 | 结果 |
|---|---|---|
| 构建，含不动点 | `tools\build.ps1` | `RESULT: ok`——seed、第 1 代、第 2 代全是 `4568E09E9740A9D5`，1 121 217 B |
| 语料，在有意重新冻结摘要锁之后 | `tools\refreeze.ps1` | `228 passed, 0 failed (of 228 cases, 303 captures)`；`RESULT: green` |
| 验收语料 | `tools\accept-status.ps1` | 34 行，8 行变化（见下表），重新记录，复判 `RESULT: ok` |
| D2 的账：哪些被跟踪的 `.vel` 文件判决变了 | 对 `git ls-files '*.vel'`（338 个文件）跑 `vm.exe check`，旧二进制对新二进制 | **只有一个**：`tests/accept/modules/module_import_reserved_name.vel`，exit 0 -> 2。另有八个文件只改了**消息**：七个模块用例，第八个是 `selfhost/parts/parser.vel`——它是片段，片段级诊断不是程序判决 |
| 编译器自己是这个关键字的第一桩案子 | `vm.exe check tests/accept/modules/module_import_reserved_name.vel` | `vela: syntax error: \`import\` is a keyword since MODULES_PLAN.md step 1 … rename this binding`，`at line 17` |

八行验收用例，before -> after：

| 用例 | 之前 | 之后 |
|---|---|---|
| `module_cycle_a.vel` | `expected a newline between statements, found 'module_cycle_b'` | `a module name is not this spelling yet (module_cycle_b)` |
| `module_cycle_b.vel` | `… found 'module_cycle_a'` | `a module name is not this spelling yet (module_cycle_a)` |
| `module_import_from.vel` | `… found 'mathlib'` | `` `from mathlib import x` is MODULES_PLAN.md step 6 `` |
| `module_import_missing.vel` | `… found 'nosuchmodule'` | `` `from nosuchmodule import x` is MODULES_PLAN.md step 6 `` |
| `module_import_qualified.vel` | `… found 'mathlib'` | `a module name is not this spelling yet (mathlib)` |
| `module_import_reserved_name.vel` | **ACCEPTED** | **REFUSED**，上面那条关键字冲突 |
| `module_import_twice.vel` | `… found 'mathlib'` | `a module name is not this spelling yet (mathlib)` |
| `module_import_unknown_name.vel` | `… found 'mathlib'` | `` `from mathlib import x` is MODULES_PLAN.md step 6 `` |

这一步学到两件计划没有预料到的事，两件都记录下来而不是抹平：

**8.1 当一个 `if` 的条件是两个调用用 `or` 连起来时，LLVM 后端拒绝构建编译器本身。** `parse_import`
的第一稿写的是 `if at_eof(tk, cx) or at_nl(tk, cx) { perr(...); return -1 }`，而构建的第 4 步回答：

    vela: llvm: adding an argument:
    vela: panic: the LLVM back end could not build this program

同一个程序 `vm.exe check` 通过、`emit-c` 写出它的 C、解释器能跑；只有 `vm.exe emit-llvm` 拒绝。
用替换分支的办法二分：同样的 `or` 但分支里**没有**调用 → 能构建；条件里只有一个调用 → 能构建；
一个调用 `or` 一个标量比较 → 能构建；两个调用、同一函数里**任何**位置再出现一次调用 → 不能。
把同一形状放进小的独立程序里则能构建（`.work/` 下的 `or_repro`、`or_repro2`、`or_repro3`、
`or_repro4` 四个都是绿的），所以决定它的是**程序的体积**，不是形状——这是后端的容量缺陷，
不是解析器的规则。`at_import_end`（`selfhost/parts/parser.vel`）是绕过它的办法——一次调用，
发射器吞得下——而这一段就是该缺陷的记录，一直到第 4 步的后端把它接管为止。它不是错码的 bug：
它拒绝得很响，所以绕着它落地第 1 步不会往树里放进一句假话。

**8.2 在装载器存在之前，环无法被点名。** 计划的验收说两个环用例「保留一条点名那个环的消息」；
第 1 步里它们拿到的是和其它模块用例一样的裸名拒绝，因为一次从不打开 `module_cycle_b.vel` 的编译
根本没有环可以点名。它们自己的注释要求的是「拒绝，而不是一直递归到栈底或者永远循环」，那是
第 2 步的验收，而这两个用例会把它们的主张保留到那时——§8.9 就是兑现它的地方。

## 8.9 第 2 步，实测（2026-10-20）

第 2 步是装载器：`import "path"` 会被读取、按导入文件所在目录解析、带行基数词法化、解析进同一批
池子，它的声明被拼到根文件自己的声明前面（D4）。`check`、`run` 与 `parse` 接受多文件程序；后端
按名字拒绝多文件（D8）。

| 项目 | 命令 | 结果 |
|---|---|---|
| 构建，含不动点 | `tools\build.ps1` | `RESULT: ok`——seed、第 1 代、第 2 代全是 `7D815659CAC565B6`，1 146 245 B |
| 语料，在有意重新冻结摘要锁之后 | `tools\refreeze.ps1` | `235 passed, 0 failed (of 235 cases, 312 captures)` |
| 两文件程序与它的单文件孪生 | `vm.exe run tests\build\modules\two_files.vel`、`… twin.vel` | 都打印 `42`；语料里 `module_two_files`（模式 `interpret`）与 `module_twin`（`run`）从此负责让这两个答案保持相等 |
| 缺模块 | `vm.exe check tests\build\modules\missing.vel` | ``no module at 'tests\build\modules\nosuch.vel' -- the path is read relative to the file it is written in``，`at tests\build\modules\missing.vel:3` |
| 环 | `vm.exe check tests\build\modules\cycle_a.vel` | `import cycle: …cycle_a.vel -> …cycle_b.vel -> …cycle_a.vel`，位置在 `cycle_b.vel` 里那行 import |
| 重复顶层名 | `vm.exe check tests\build\modules\dup_name.vel` | `duplicate definition of 'twice' -- also declared in '…helper.vel'` |
| 第二个 `main` | `vm.exe check tests\build\modules\second_main.vel` | ``a second `main` in '…with_main.vel': the program's `main` belongs to '…second_main.vel' (D5)`` |
| **被导入文件里**的诊断 | `vm.exe check tests\build\modules\bad_root.vel` | `operator '+' mixes int and str` … `at tests\build\modules\bad_helper.vel:11`——说的是被导入文件，不是根路径 |
| 后端仍然拒绝多文件 | 对 `two_files.vel` 跑 `build`、`emit-c`、`emit-llvm`、`build-c` | `` `build` takes one file, and '…two_files.vel' imports '…helper.vel' (D8: the C and LLVM back ends learn in steps 3 and 4)`` |
| 两个语料上的 LLVM 列 | `tools\llvm-column.ps1` | `match 49  refused-by-design 12/12  DIVERGE 0  unexpected refusal 0  stale ledger 0`，`RESULT: ok` |
| 验收语料 | `tools\accept-status.ps1` | 34 行，`RESULT: ok`——没有变化，因为单文件程序的表只有一项 |

**8.10 源表放在 `path` 里走，而不是放在 `mem` 里。** D7 说的是「表放在 `mem` 的一个保留区，
计数与游标追加进 `struct VM`」，那是**节点**需要的东西——但没有任何节点读它。真正的读者是六个
诊断助手（`fail`、`fail_name`、`fail_kind`、`ck_type`、`ck_safety`、`trap`），而它们每一个
本来就已经拿到路径和行号，所以驱动交给各阶段的是一行一行的 `<首行>` TAB `<路径>` 字符串，助手
改问 `path_for_line` 而不是直接打印 `path`。单文件程序里它没有换行符，所以这个字符串**就是**
那条路径，于是所有 golden、所有发出的 C 字节都不变；多文件程序根本到不了发射器（D8）。

另一条路是量过之后才否掉的。把 `mem` 穿进那些助手，等于把它穿进调用图上每一个会抛诊断的函数——
第一遍闭包就点出二十来个函数（`ck_arith`、`eval.vel` 的那批算术助手、`ck_pread`、`emit_expr`……）
和 resolve、check、eval、emit 里约一百五十个调用点——只为了读一张只在程序已经被拒绝时才会被查的
表。第 3、4 步无论如何都需要**每节点**自己的路径（C 与 LLVM 发射器把路径写进生成代码），它们
可以调用同一个 `path_for_line`；如果字符串形式到那时碍事了，那才是把它挪走的时候，由发射器的
需要来决定形状。

**8.11 重复只在**文件之间**拒绝，不在文件内部。** `note_decl` 拒绝的是另一个**文件**已经声明过的
顶层名——这是一个文件从前问不出的问题。同一个文件里的两个声明，各自按自己的规则处理：语料用例
`extern_conflicting_prototypes` 故意为同一个 C 符号写两条声明，检查器用一条关于原型的消息拒绝它，
那比「重复定义」更准确。实测：第一版由装载器拒绝了它，语料直接说了出来（`wrong diagnostic`），
规则于是改成「另一个文件」。

**8.12 正向语料行需要一个新模式。** `run` 会先构建，而 `build` 按名字拒绝多文件（D8），所以没有
任何 `run` 行能说「这个两文件程序打印 42」。`tests\cases.txt` 于是多了一个模式 `interpret`：
只跑解释器，与 `tests/golden/<name>.out` 比较。它就是为这一刻存在的——等后端能收第二个文件的那天，
`module_two_files` 变成 `run` 行，这个模式也就没有客户了。

**8.13 `selfhost/AST.md` 的节点表停在 34**，而解析器从 §13 起就用到了 35-38（`enum`、`variant`、
`match`、`arm`），现在又多 40（`import`），后者的记录在 `selfhost/parts/parser.vel` 节点列表上方
的注释块里。这个缺口早于本步骤，这里不去半修它；计划的第 7 步负责编辑器那一份语法树。

**8.14 编辑器的闸门抓到的是一个错误答案，不是缺一个功能。** 第 7 步本来不在这轮的计划里，但
`ast-diff.ps1` 必须先学会「什么是一个**程序**」才能比较（插件只解析一个文件，而 `vm.exe parse`
现在会装载），而它在 `tests/build/modules/bad_root.vel` 上报出：插件打印 `str "text"`，编译器打印
`str ""`。被导入文件里的字符串字面量，被当成**根**文件源码里的字节区间在读——而且这不是 dump 的
假象：`vm.exe run` 从那个字面量打出来的也是空串。于是装载器把每个文件的文本接进同一个源缓冲
（`Load.all`），并在解析**之后**把这个文件的字面量偏移平移到它在缓冲里的位置。是之后不是之前：
解析器按本文件自己的文本读名字和字面量，import 那一趟也按同样的方式读路径。单文件程序的缓冲
**就是**那个文件（偏移 0），所以所有 golden、所有发出的 C 字节都不变；
`tests/build/modules/str_module.vel` 是钉住它的用例。

编辑器今天拿到的第 7 步是**解析**那一半：`import` 是关键字（`VelaKw.IMPORT = 26`）、是一个节点
（`VelaNodeKind.IMPORT`，编译器的 kind 40）、是一行 dump，而 `AstDiff` 复刻了装载器的拼接，所以
两文件程序是按「程序」来比的。跨文件的**名字解析**——`VelaTargets.declarationFor` 伸进被导入
文件——仍然欠着，`FEATURE_PARITY.md` 第 10 行就是这么写的。

