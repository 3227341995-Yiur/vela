# Vela IDEA 插件 —— 变更日志

[English](CHANGELOG.md) | **简体中文**

<!--
源文件 : CHANGELOG.md
源文件字节 : 74080
源文件 SHA256 : 8f829d9aa750e7ee62229cd8f040eb12a6bc41e51fe323b0fee5e096510da5d9
翻译日期 : 2026-09-25
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

**规则，因为用户明确要求过：每一次插件更新都升版本，而每一次升版本都在这里有一条记录。**

版本住在一个地方——`src\main\resources\META-INF\plugin.xml` 的 `<version>`——其余一切都与它保持同步：

| 哪里 | 怎么同步 |
|---|---|
| `src\main\resources\META-INF\plugin.xml` | `<version>`——**真相的来源** |
| `build-offline.ps1` | 从 `plugin.xml` 里读出版本并用它命名 dist zip，所以它不可能漂移 |
| `build.gradle.kts` | `version` 和 `sinceBuild`，给 Gradle 那条路用，靠手工保持同步 |
| 本文件 | 一条记录说改了什么，**以及什么被验证过、什么没有** |

最后一列不是仪式。这个项目更老的那条规则是：没有人测量过的声称不存在。而这个插件已经发布过三个完成后完全无作用的功能——一个写错的文件扩展名、一个被当成组注册的动作，以及三个从未被注册（或者注册在错误属性名下）的扩展点。一条写着“加了 X，未验证”的记录，比一条写着“加了 X”的记录更有价值。

## 0.1.12 —— 每一个参数名提示都被画了 490 遍，而这里没有任何工具能说出这件事

**改了什么。** `VelaParameterNameInlayHintsCollector.collect` 忽略传给它的那个元素，在**每一次**回调里登记**整个文件**的提示表，并且返回 `true`——那是平台的「继续往子元素里走」。平台对每个 PSI 元素调用一次 `collect`，于是 `ide-demo/tour.vel` 的 7 个提示被画了 7 × 490 = **3430** 次，每个标签一直冲到行尾、再冲出屏幕。现在收集器只把整个文件画一次，并让遍历停下来。

* **这个缺陷是「看」出来的，不是「测」出来的**——主人发来一张编辑器截图：`ide-demo/tour.vel` 和 `tests/…` 里的文件上，`abs(n: n: n: n: …)` 一直重复到行的右边。那一刻屏幕上明明是这样，而这份文件里所有关于 hints 的数字都是**绿的**；原因值得作为本条记录的另一半写下来：`HintDiff`、`HintShapes`、`HintTruth` 和 `ParamNames` 量的都是 `VelaHints.parameterHints`——**那张表**——而没有任何东西驱动过平台真正调用的那个对象。一张正确的表被画了 N 遍，就是一个错误的屏幕。

* **标签是对的，错的只是次数。** `p.moved(10, 1)` 旁边的 `dx:` 正是编译器把这个实参绑到的那个参数（接收者 `self` 被丢掉，0.1.7 的那次修复），而 `describe(q)` 边的 `p:`、`scale(v, factor)` 边的 `v:`、`abs(...)` 边的 `n:` 都是声明自己的名字。所以上面那些工具并没有因为这一条而失效：它们判的是那张表，判得对，缺陷住在它们下面一层。

* **修复是一个标志位加一个 `false`。** `painted` 让第一次调用把文件画完、之后的调用变成空操作，返回值则让遍历停下而不是继续下潜到每个元素。偏移量是**文档**偏移量，所以第一次调用带着哪个元素到来都无所谓。

* **缺失的那根轴现在有了：`InlayProbe`。** 它驱动 `VelaParameterNameInlayHintsProvider.getCollectorFor(file, editor, settings, sink)` 返回的那个收集器，跑在文件**真实的 PSI 树**上（用平台自己的 `PsiBuilderImpl` 构建，也就是 `psi-tree-diff.ps1` 用的那次重放），并给一个会记录的 `InlayHintsSink`（一个 `Proxy`）数清每一次 `addInlineElement`；它断言一条不变量：**收集器登记的数量等于那张表**，无论遍历访问了多少个元素。它已经在 `harness.ps1` 的工具清单里，所以从这一版起整条 harness 都会量它。

**已验证，以及由什么验证。** 同一个工具、同一批文件，唯一的差别是插件构建——0.1.11 的 jar 从已提交的 `dist\vela-idea-plugin-0.1.11.zip` 里取出（398 763 B，sha256 `4eaa6746…`，正是 0.1.11 自己那份证据记录的哈希），对上本轮的 0.1.12 jar（398 817 B，sha256 `a35ac762…`）：

    file                                hints   elements   registered 0.1.11   registered 0.1.12
    ide-demo/tour.vel                      7        490               3430                   7
    examples/hello.vel                     1        149                149                   1
    tests/build/arith_basics.vel           2         90                180                   2
    tests/build/control_flow.vel           3        188                564                   3
    bench/matmul.vel                       3        336               1008                   3

    COVERAGE 0.1.11: ran 5 / skipped 0 / wrong 5   VERDICT: [FAIL] … exit 1
    COVERAGE 0.1.12: ran 5 / skipped 0 / wrong 0   VERDICT: [PASS] … exit 0

`build-offline.ps1`：`RESULT: PASS`，162 行 OK / 0 FAIL；`dist\vela-idea-plugin-0.1.12.zip` 375 236 B，jar 398 817 B，111 个具体顶层类，0 个死的。证据：`idea-plugin\evidence\inlay-hints-0.1.12-20260925-0115.txt`——两次运行逐字在内。

**仍未验证的部分。** 没有启动过 IDE：sink 是一个会记录的 `Proxy`，editor 是一个代理，遍历是 `InlayProbe` 对 `FactoryInlayHintsCollector.collect` 那个 `Boolean` 所描述契约的实现。被测的是收集器**登记了什么**；一个跑起来的 IDEA 会画出什么并没有被测，截图是这份证据的「编辑器那一半」，而不是那个数字的替代品。有一个显眼的后果被点名而不是藏起来：修复后那次运行的遍历读作 `elements 1`，因为第一次调用就把整个文件画完并返回 `false`——而有 7 个提示的那个文件 `registered` 是 7，这一点才说明收集器真的被调用过。

## 0.1.11 —— 一条被撤下的规则，而不是被改造的规则：`+` 拼两个 `str` 现在合法

**改了什么。** 语言学会了字符串拼接：`"ab" + "cd"` 是 `"abcd"`，`s += "b"` 会追加到一个 `mut s: str` 上（`SPEC.md` §1.5，2026-09-24 落地到编译器）。插件里有一条规则恰好以那个让这件事非法的拒绝为准——`type error: string concatenation is not implemented in Vela 0.1`——而**前提已经消失的规则比没有规则更糟，因为它仍然会给出一个修复。** 所以 `VelaStringConcatenation` 被撤下：类本身、它的 `shortName`、它在 `VelaInspectionRules.RULES` 里的条目与它的可接受措辞列表、它的遍历（`stringConcatenations`、`isConcatChain`、`rewriteConcat`），以及只有它才会问的那两个辅助函数（`isStringOperand`、`declaredString`）。插件现在注册 **两** 条 `localInspection`，以前是三条。

* **那个快速修复会把能正常运行的代码改掉。** 它的修复动作是 `"a" + "b"` → `concat("a", "b")`，而这已经不再是「修复」：它是在一条红色波浪线之下，把一个合法程序改写成另一个合法程序。它引用的那句拒绝，这个编译器根本产生不出来，所以这条规则在任何输入上都不可能诚实地触发——它只剩两种活法：永不触发，或者错误触发。`VelaInspections.kt` 把这段论证写在规则原来所在的位置，而不是留下一个悄悄变短的文件让下一个读者去猜。

* **它没有被反向改造成风格规则**（`concat(a, b)` → `a + b`），这是一个决定，不是遗漏。这里的每一条规则只按一个标准挑选，而且那个标准不是「有用」：它的 finding 必须能对上编译器**自己**在那一行给出的拒绝——这正是 `InspectionProbe` 走的那条路，也是让 finding 成为**事实**而不是观点的东西。编译器对两种写法都沉默（`SPEC.md` §8 的 `concat` 行说它们是同一次运行时调用），所以插件对「人该写哪一种」没有意见。

* **一条因为这条规则才成立的悬停文字不再成立，也一并修了。** `VelaModel` 的内建函数表把 `concat` 描述成「the only way to join strings」；现在它写的是「the same operation as `+` on two strings (SPEC.md 1.5)」。那张表是悬停和补全画出来的东西，所以旧文字是对用户关于这门语言的一句声称，而它已经开始说谎。

* **语言的语料动了，所以 harness 的 canonical 文件跟着动。** `tests/safety/cases/strict_no_string_concatenation.vel`——那条被撤下的规则的修复曾经被证明的那个文件——现在叫 `strict_string_concat_operator_ok.vel`：一个编译器**接受**的用例，它有一个孪生 `strict_string_concat_builtin_ok.vel`（同一个程序用命名内建写出来），而 `tests/safety/manifest.txt` 记录了这次替换和它的日期。`InspectionProbe` 的 before/after 表每条已注册规则各有一个 canonical 程序，所以它从三行变成两行；它失去的那一行，是任何东西都已经无法在上面测量的那一行。

* **工具自己数规则数量，而不是把一个数字打进句子里。** `InspectionProbe` 的 VERDICT 行和它「every refusal ... that one of these three rules claims」那一行，现在读的是 `VelaInspectionRules.RULES.size()`。把数量写在散文里，正是这个项目吃过亏的那种声称：它会继续对着一个注册了两条规则的插件说 **three**，而且是在读者当作结论引用的那一句话里。

* **这一次的 oracle 是另一个编译器，所以数字与 0.1.10 不可比。** 探针冻结的是树里的 `vm.exe`——**870,400 字节、sha256 `aaa0a599f3dc3ca55b19ed6ead7debe6586e10d446b38cedc5add6397c61effc`，也就是带上字符串 `+` 的那次构建**——而 0.1.10 那一轮冻结的是 `1e52032c…`（867,328 字节）。这就是这一轮把整个探针重跑、而不是从旧数字里减掉那条被撤下规则的 7 个 finding 的原因。

**已验证，以及由什么验证。**

    powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1
        RESULT: PASS —— 结构性、字节码、平台、注册、链接与行为各项检查全部通过；
        build\logs\verify.log 里有 **162 行 OK、0 行 FAIL**
        dist\vela-idea-plugin-0.1.11.zip       375,184 字节
        dist\vela\lib\vela-idea-plugin.jar     398,763 字节，170 个 class 文件，最高 major 65（Java 21）
        111 个具体顶层类：31 个被 plugin.xml 指名，80 个从另一个 class 文件可达，0 个死类
        plugin.xml 里的 <localInspection language="Vela"> 条目：2 条（VelaImmutableAssignment、
                     VelaIntFloatMixing——两条都在 jar 里、两条都是 LocalInspectionTool，
                     由验证器对着平台自己的 <with ... implements=...> 检查）
        版本纪律：plugin.xml、build.gradle.kts、dist 文件名和本节标题都是 0.1.11

    还有验证器在本条记录写作期间抓到的那一件事：XML 注释里不能出现 `--`，而这条注释正是
    在解释一条规则。新注释存在的第一次构建，在第 1 节三条 OK 之后就把自己的日志截断在 992
    字节上；现在那句话用一个句号收尾，而上面这次运行是同一条命令、同一棵树，只差一个字符。
    把它留在记录里，因为它是对本项目反复重学的那条规则最便宜的一次演示：抓到它的是闸门，
    不是阅读。

    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\harness\inspection-probe.ps1
        COVERAGE: ran 287 / skipped 43 (crashed-file 0, missing-corpus-file 0, too-large 0,
        compiler-did-not-run 0, check-did-not-terminate 0, fix-wrote-nothing 0,
        inspection-class-disagrees-with-the-rule 0, does-not-parse 43, empty-or-whitespace-only 0,
        excluded-by-request 0) / wrong 0
        VERDICT: [PASS] every finding the 2 registered inspections report was walked to a refusal the
        frozen compiler itself makes on that line, every fix removes the refusal it was offered for,
        and no finding lands on a file the compiler accepts -- over 287 judged file(s), wrong 0,
        no defect category tripped
        语料 330 个文件：150 个编译器接受、137 个拒绝，**在 150 个被接受的文件上 0 个 finding**
        （这正是「规则凭空发明一个拒绝」会最先现形的轴），7 个文件的修复全部应用成功
        逐规则：VelaImmutableAssignment 触发 3 / 验证 3，VelaIntFloatMixing 触发 4 / 验证 4
        —— 合计 **7 个 finding**；0.1.5 那一轮三条规则、311 个文件的语料上是 13 个，
        读数 `ran 257 / skipped 54`。`-CompileOnly` 先跑过一次，因为这个 harness 只编译
        `Coverage.java` 与 `InspectionProbe.java`，本轮对 `.java` 的修改没有别的办法可查

**没有被验证的，仍然是没有被验证的那些。** 没有启动过 IDE：关于快速修复，被测的是**已注册的 `LocalQuickFix` 对象携带的那次编辑**，而不是 `LocalQuickFix.applyFix` 通过一个活的文档写下去；alt-Enter 菜单没有被打开。`InspectionProbe` 读的是编译出来的 class，从不读 `plugin.xml`，所以上面那条注册证据来自验证器而不是 harness。有一个既存缺陷保持可见，而不是在这一轮被顺手藏掉：`VelaImmutableAssignment` 漏掉了 `tests/build/check_cases/mutate_loop_variable.vel:3`（`cannot modify 'i': it was declared immutable`），与 0.1.5 报告里记作 `missed 1` 的是同一个文件、同一行。

## 0.1.10 —— §13 做不到的四件事：变体名可解析、弹窗会开、颜色是自己的、payload 是声明

**改了什么。** 0.1.9 教会插件**解析** `enum` 与 `match`；这一版是另一半：语言新得到的那些名字，现在是插件能跳转、能查找、能重命名、能悬停、能补全的名字。每一件都用已经存在的工具测，每一个「之前」的数字都是用**同一个**工具、跑在 **0.1.9 插件自己的 class 文件**上、对**同一个冻结编译器**测出来的——唯一的差别是插件构建。

* **变体名和枚举名进了引用机制。** 新增 `VelaTargetKind.ENUM` / `VARIANT`，并在 `VelaTargets.declarationFor` 的两处接上：名字是变体的调用（构造 `Circle(2.0)`——arm 的模式 `Circle(r) { … }` 是同一个形状）、类型位置上的裸枚举名（`c: Shape`）、以及当值写的无 payload 变体（`Empty`）。前后对照（同一 harness、同一冻结编译器，唯一差别是插件构建），在 `tests/build/enum_exhaustive_switch.vel` 上：

      GotoOracle 0.1.9    variant  3 个引用 / 0 正确 / 3 个无目标
      GotoOracle 0.1.10   variant  3 个引用 / 3 正确 / 0 个无目标

      RenameOracle 0.1.9    variant  3 个声明 / 6 个绑定 / 0 个声明/ 6 个 MISSED / 3 个错声明
      RenameOracle 0.1.10   variant  3 个声明 / 6 个绑定 / 6 个声明 / 0 个 MISSED / 0 个错声明

  全语料：`GotoOracle` **判定 30,880 个引用、错 0**（`invisible-member 18`，逐个打印）；`RenameOracle` **判定 901 个声明、2,998 个编译器实际绑定的使用、0 MISSED、0 WRONG-SCOPE、错 0**，并且自己有一行分类计数（`enum 1 1 1 0 0 0`、`variant 21 42 42 0 0 0`）；`RenameWriteback` 把 **830 个声明**开过插件自己的写回通路，编译器对其中 **828** 个拒绝「只重命名声明」这种改法。这 22 个声明在这一版之前**根本没被判过**——`RenameOracle` 的声明遍历只枚举 `def`/`struct`/`field`/`param`/`decl`/`for`，对 §13 一无所知，所以那个「0 MISSED」其实属于一张连变体都解析不出来的表。

* **查找引用现在能在一个枚举或变体自己的名字上回答。** `VelaUsageSearch.canSearchAt` 现在接受一个「解析到它自己」的叶子：它原来用的 token 形状规则只认 `struct X`、`def f`、`for i` 和 `name: T`，§13 一个都不写。这一条也是测出来的——当遍历已经认识 §13、而这一行还缺着时，`RenameOracle` 把这 22 个全报成 `NOT-SEARCHABLE: the platform would refuse Find Usages on this declaration's own name`，连**使用处**也一样（`the usage on line 17 (offset 553) cannot be searched for from its own name`）。加这一行之前 `wrong 22`，之后 `wrong 0`。

* **变体构造有参数信息弹窗了。** `VelaNames.resolveCall` 回答带 payload 的变体，`VelaParameterInfoHandler` 画出它的 payload 字段名——读的是模型从 `variant` 节点自己的字段表建出来的那个符号，和补全的 `Circle` → `Circle(radius)` 模板用的是**同一个**读取器，所以两者不可能对同一个声明给出两种说法。`PlatformEntry` 第 9 行正好多出这些调用：同一份语料上，0.1.9 插件把它们算进 `callee-not-a-declared-def`，0.1.10 插件判定它们（`find`/`show`/`update` 各 +9，全部正确）。

* **payload 字段是声明了。** `VelaModel.readEnumFromTree` 为每个 payload 条目产出一个符号，归属它的变体，于是 `radius` 的悬停就是编译器 dump 打印的那个声明（`field radius: float`，owner `Circle`）——而 `HoverTruth` 现在**判**它，不再**数**它：计数的类别 `payload-field-not-a-model-declaration`（15 个位置）消失，那一行变成 **判定 13,028 / 跳过 81 / 错 0**。它先红过一次，而红的是我这一版自己的 harness：dump 读取器在识别出该行的地方设了 payload 的 owner，紧接着读取器的公共尾部又把它覆盖成 `structOf(parent)`——而 payload 的父节点是 `variant` 不是 `struct`，所以答案成了空串。15 条 finding，每一条都在说「编译器把它嵌在 `-` 下」，而悬停是对的。

* **变体名有自己的颜色了。** 新增 `VelaNameKind.VARIANT`，用平台的常量色，覆盖语言写变体的全部四种位置（枚举体里的声明、构造、arm 的模式、裸值）——编译器自己的 dump 就是「这是变体、不是普通名字」的权威。**没有任何东西测量这个颜色**：没有 harness 会问一个名字被画成哪个 key，`FeatureProbe` 检查的是**词法器**画的 key 与配色页注册的 key 一致，而这些语义类别用的是平台默认色、哪儿也没注册。这个决定写在做出决定的地方，并在这里点名，而不是暗示它被覆盖了。

**§13 仍然做不到的，点名并带上代价。** **模式绑定**的名字（`Circle(r)` 里的 `r`）不解析：arm 按位置绑定一个新名字，函数体里对它的使用属于 `GotoOracle` 的 `invisible-member` 类别（该文件 3 个，全语料 18 个，逐个带行号打印）。**payload 字段**的名字也不可解析，而且不可能解析——语言里没有任何地方写得出它。两者都是计数的类别，不是声称。

**一条流程上的发现，因为它让这一版差点丢掉最干净的那个物证。** 0.1.9 插件的 class 文件几乎丢了：`build-offline.ps1` 用 `plugin.xml` 里的版本给 dist zip 命名，而这一版的版本号直到最后一步才升，于是三次中间构建把 `dist\vela-idea-plugin-0.1.9.zip`（连同上面每个「之前」数字所测的那份插件）**覆盖成了半成品**。它们靠 `harness.ps1` 自身的设计活下来——它在每次运行前把 `build\classes` 快照到 `build\tools\harness\classes\classes-snapshot-*`，所以并发构建打扰不到它，`classes-snapshot-41940` 就是那次的 0.1.9；上面每一对前后对照都是用同一 harness、同一冻结编译器从它重跑的。规矩现在按另一个顺序执行：**先升版本，再构建。**

**这一版没有证明什么。** 没有启动任何 IDE：每个 harness 都是拿替身驱动插件自己的入口。裁判是冻结的 `vm.exe` 867,328 字节、sha256 `1e52032c…`（`LLVM-C.dll` 74,159,616 字节、`1286e894…`），语料同样没有冻结——这一轮里它从 147 个文件长到 152 个（`template-names-not-in-spec` 146 → 149），所以位置计数是某一次运行在当时的树上的结果。`SymbolDiff` 与 `FoldDiff` 按设计仍非零（错 13 与 41），且这一版没有移动：对它们而言变化的是树模型现在也声明变体的 payload 字段。

## 0.1.9 —— 插件跟上语言走进 §13：`enum`、`match`，以及它们造出的树

**改了什么。** 0.1.8 还在测量的时候，编译器那边落下了 `SPEC.md` §13：`enum` 和 `match` 成了保留字，语料一次多了十八个文件（`tests\build\enum_*.vel`、`tests\probes\enum_*`），而它们的树这个插件从来没有见过。那是语言在动、插件必须跟上；这一版就是跟上：两个关键字、编译器为它们打印的 PSI，以及把 `FEATURE_PARITY.md` 里每一个数字都在结果上重测一遍。

* **两个关键字。** `enum` 与 `match` 进了 `VelaTokenTypes.KEYWORDS`（词法器的关键字集合，补全和 `VelaDeclarations` 的「关键字不是引用」共用它），也进了 `VELA_KEYWORDS`，带着编译器自己的编号 24 和 25。判定它们是否真的落地的是 `PlatformEntry` 第 7 行的 `bare-end` 家族：这一版之前它是**红的**——语料里 **139 个位置错，每个文件一个，每条都报 `not offered: keyword enum, keyword match`**；现在是 **146 判定 / 错 0**。而这条检查仍然能失败，这才是要紧的部分：把这两个词从插件的关键字表里再去掉，同一个家族就会报出同一行并以 1 退出——`tests/build/enum_else_arm.vel: the caret at the end of the file; not offered: keyword enum, keyword match`，`COVERAGE: ran 17 / skipped 4 (...) / wrong 1`，`VERDICT: ... [FAIL]`——那次变异运行的原始输出在证据文件里。

* **树。** 解析器新增了 `enum`/`variant`/`match`/`subject`/`arm`/`binding`，逐一对应 `selfhost/parts/parser.vel` 里的 `parse_enumdef`、`parse_variant`、`parse_match` 和 `parse_arm`——包括编译器的**解析器**拒绝的东西，而刻意不包括它**检查器**拒绝的东西：嵌套模式、把 `enum` 当名字用，在这里就被拒；穷尽性、重复的 arm、未知的变体、payload 的参数个数，都留给 `vm.exe check`，因为编译器的前端在它们之前就停住，而一个靠猜的解析器会拒绝编译器接受的程序。`VelaSyntaxDump` 按 `selfhost/parts/dump.vel` 逐行打印这六种形状（`enum name=…`、`variant name=… fields=N`、每个 payload 条目一行 `field name=… type=…`、`match`、`subject`、`arm pattern=… binds=N`、每个绑定的名字一行 `binding name=…`，然后是 arm 的块）。实测，同一冻结编译器（`vm.exe` 867,328 字节，`1e52032c…`）：**`ast-diff.ps1`——语料 147 个文件，133 个完全相同，共比较 `147,346` 行节点，0 处不同、0 处存疑、0 处缺失，14 个编译器拒绝（其中 14 个本解析器也拒绝），`COVERAGE: ran 133 / skipped 14 / wrong 0`，VERDICT PASS**——而 0.1.8 那次是 117 个文件、134,587 行节点。以及 **`psi-tree-diff.ps1`——147 个文件全部经平台自己的 `PsiBuilderImpl` 重放，比较 147,388 个复合节点与 399,393 个叶子 token，`failed 0`，`COVERAGE: ran 147 / skipped 0 / wrong 0`，VERDICT PASS**。

* **插件自己的功能也看得见这些新声明了。** `VelaModel` 把 `enum` 读成声明、把它的变体读成子项（`VelaSymbolKind.ENUM` / `VARIANT`），于是补全在模块层提供枚举自己的类型名，在需要值的位置提供变体名；`VelaHints.callTemplate` 把带 payload 的变体写成语言所说的那种调用（`Circle` → `Circle(radius)`），对不带 payload 的则什么都不写（`Empty`），因为 `Empty()` 是编译器会拒绝的文本。`PlatformEntry` 的 insert 家族也学会了同一条规则并真的去判它：**`insert` 判定 1909、错 0**，其中 **13 个 `insert-not-a-callable`** ——语料里每个不带 payload 的变体一个，被计数并打印，而不是被当成错，因为这才是「这个名字是值、不是调用」的诚实读法。payload 字段**不是**模型的符号，这是刻意的：语言里没有任何办法指名一个 payload 字段，给它一个符号就是给读者一个无法导航到的声明。

* **`HoverTruth` 在这一版里红过一次，而红的是工具、不是插件。** 它的 dump 读取器和它的文本扫描器只认识 `struct`、`def`、`field`、`param`；§13 进了语料之后，枚举的变体在它眼里成了「谁都没声明的名字」，于是「未声明的名字必须悬停出空」这条轴把真实的悬停 `enum Color` / `variant Red` 报成了凭空捏造（21 条 finding，exit 1）。现在工具两侧都认识 `enum`/`variant`/payload，而插件模型刻意不声明的那个东西——payload 字段——是计数的类别 `payload-field-not-a-model-declaration`（15）。那次红的运行、修法与数字在 `FEATURE_PARITY.md` 的 `## Open defects` 第 9 条。同一种盲目也是 `SymbolDiff` 的 `tree-reports-more-declarations` 从 25 涨到 42、`FoldDiff` 的 `wrong` 从 24 涨到 41 的原因：这两个是与已退役实现做**差分**的探测器，按设计本来就非零，而这次是语料让它们动了，不是回归。

**没有跟上的部分，点名写出而不是留着暗示。** 以下都是这个插件对 §13 仍然做不到的事，每条都带上它在上面那些测量里的代价：

* **变体名没有被跳转、查找引用或重命名解析。** 引用表（`VelaTargets.declarationFor`）把调用解析到 `def`、把裸名字解析到结构体；变体两者都不是，所以 `Circle(2.0)` 和 `Empty` 什么都解析不到。`GotoOracle`（`ran 30880 / skipped 27305 / wrong 0`）把这些算作 `invisible-member`：这个类别随着 §13 语料从 10 涨到 18，并且它的日志里逐个打印。`RenameOracle`（`ran 879 / skipped 5962 / wrong 0`，0 MISSED、0 WRONG-SCOPE）根本不去判变体，因为它的声明遍历只枚举 `def`/`struct`/`field`/`param`/`decl`/`for`。那是这两个工具对新语料的覆盖限度，不是「变体重命名能用」的声称。
* **变体构造没有参数信息弹窗。** `Circle(` 不指向任何 `def`，所以 `findElementForParameterInfo` 答 null；`PlatformEntry` 第 9 行把这些调用算进 `callee-not-a-declared-def`（623，比之前多 24），一个也不判。
* **变体名没有自己的语义高亮颜色。** 它既不是类型也不是可调用物，而 `VelaSemanticHighlighting` 没有「变体」这个 key；名字被留给默认颜色，这个决定写在做出决定的那段代码里。
* **payload 字段不是符号**，所以它的悬停就是上面那个计数类别，而结构视图把变体的 payload 显示在它的 detail 文本里，而不是作为子节点。

**这一版没有证明什么。** 没有启动任何 IDE：每个 harness 仍是拿替身驱动插件自己的入口。上面每个数字都用同一个冻结编译器测出：`vm.exe` 867,328 字节，sha256 `1e52032c…`（`LLVM-C.dll` 74,159,616 字节，`1286e894…`）；语料也没有冻结——`tests\` 属于另一条轨道并且还在长，所以那些数字是某一次运行在当时的树上的结果。`SymbolDiff` 与 `FoldDiff` 按设计保持非零：它们是回归探测器，数字就是当前读法与已退役读法之间的实测差。插件的解析器复现的是编译器**解析**阶段的拒绝，而不是**检查**阶段的拒绝：被检查器拒绝的文件在这里是一棵树，在编辑器里则由 `vm.exe check` 给出诊断，那正是拒绝抵达用户的地方。

## 0.1.8 —— 0.1.7 数过但没有修的那四件事

**改了什么。** 0.1.7 那一轮把第 7、9、19 行推进到 `implemented`，并留下一份它**数过**、却刻意没有修的清单。这一版就是那份清单，按它自己列出的顺序。

* **带类型标注的局部变量，它的成员根本够不到。** 写下 `q: Vec2 = Vec2(1.0, 2.0)` 之后再用 `q.`，补全什么都不给，`q.dot(` 也弹不出参数信息——因为模型的类型读取器（`VelaNames.structTypeOf`）只认 `self`、参数和结构体名，而 `VelaModel` 只知道结构体、def、字段、方法和参数，**完全没有局部变量**。这不是测量缺口：带类型标注的局部变量，正是这门语言里写局部变量的最普通方式（不带标注的绑定编译器直接拒绝：`binding 'n' has no type annotation`），所以这是用户在第一个程序里就会撞上的洞。`VelaTargets` 新增 `localBindingType`，读解析树自己的 `decl` 节点——与其它所有读取器读的是同一棵树——由内层块向外层找，于是内层块的重新绑定按编译器自己的规则（SPEC.md 6.2）遮蔽外层同名者。实测，同一 harness、同一冻结编译器：`PlatformEntry` 第 7 行的 `after-dot` 家族从 **166 判定 / 错 0、另有 29 个 `receiver-is-a-local` 被跳过**变成 **194 判定**，而在 0.1.7 的 jar 上，这 28 个新判定的位置是 **28 个错**（`the compiler declares [x, y, dot, scale]; not offered [x, y, dot, scale]`），本版 **0**；第 9 行那 6 个 `receiver-is-a-local` 调用也被判定了，而且在两个版本上都干净，因为按名字兜底找方法恰好找对了那一个方法——这个类别消失是因为**读取器**现在能解出接收者，这一点照实写明，不冒充第二个缺陷。有一个位置**不能**变得可判定，harness 用它自己的类别说明原因：`o.inner.bump()`，其中 `inner` 是外层结构体的**字段**——它的类型在编译器的 dump 里，却没有任何绑定把它写在名字旁边，这就是 `receiver-not-a-written-binding`，不是本插件声称能解出来的东西。

* **`findElementForUpdatingParameterInfo` 死掉的那一半，现在活着而且被测。** 0.1.7 把「它的 `objectsToView` 兜底永远跑不到」写了下来，然后原样留在代码里。它确实跑不到：平台交回来的条目是 `ParameterHint`，而代码写的是 `(shown[0] as? PsiElement)`——这个转型不可能成功。后果是用户看得见的：编辑之后锚点叶子被重建，没人找得到它，弹窗就关了。现在它会去 `objectsToView` 里找那个条目、确认该偏移处仍然是这次调用自己的 `(`、把 hint 重新挂回找到的叶子，找不到就答 `null`；而 `PlatformEntry` 把它测了：新的 `update-rebuilt` 家族为每一次被判定的调用重建锚点叶子，要求处理器重新找到它，并要求 `objectsToView` 为空时必须答 `null`。实测：**0.1.7 的 jar 上判定 663、错 663；0.1.8 上错 0。**

* **构建树不再把工作区塞满，负向 harness 不再为每个 mutant 复制一份编译器那份 74 MB 的库。** `VerifyPlugin` 会把 `vm.exe` 和它旁边的 DLL 复制进每个插件根的 `build\verify\`；负向测试为每个 mutant 建一个根，所以每个根一份真拷贝是每个版本约 890 MB，`build\` 已经长到 **2,876,518,684 字节**，其中 **2,743,905,792** 是 `build\verify\` 下的 `LLVM-C.dll`。现在这份 DLL 是指向编译器自己那份文件的**硬链接**——同样的字节，不占第二份空间——拷贝保留为退路，用于工作区在另一个卷上的情形，因此对照没有被削弱：mutant 仍然跑真正的编译器，之前跑的每一项检查现在照样跑。实测：六个「已不是当前版本」的树（`build\verify\negative-0.1.4`、`-0.1.5`、`-0.1.6`、`mutation-0.1.4`、`-0.1.5`、`-0.1.6`）共 **2,774,319,999 字节**，删除它们把 `build\` 从 **2,876,518,684** 变成 **102,231,703 字节**——剩下的 74 MB 里正好有 0.1.8 构建已经做出来的那一份 `LLVM-C.dll` 拷贝，下一次运行会把它变成链接。它们的 `*-report*.txt` 保留在 `build\verify\pruned-reports\`，免得每一套集合当时发现了什么被无声丢掉；0.1.8 的负向集合被重跑，以证明硬链接没有把它削弱：`evidence\build-trees-0.1.8-20260924-0546.txt`。

* **`FEATURE_PARITY.md` 的覆盖三元组表里，凡是能重测的数字都在这个 jar 上重测了**；不能重测的那些行，要么其工具属于另一条轨道（`ast-diff.ps1`、`psi-tree-diff.ps1`），要么那次测量是一次性的、有自己的证据文件；每一行现在都写明属于哪一种，而不是继续带着另一个版本的数字。

**这一版没有证明什么。** 没有启动任何 IDE：`PlatformEntry` 仍是拿替身驱动平台自己的入口——`Document`、`Editor`、PSI 以及参数信息的三个 context 接口——每一个替身都在那个文件里点名。上面第 7 行的数字是语料当时状态下的同一次运行，编译器冻结在 `20a15de8…`（0.1.7 的证据之后，另一条轨道重建了树里的 `vm.exe`，所以它的 `b69557cf…` **不是**这些数字被测量时用的裁判；在本条写就之前它又被重建成 978,944 字节）。不带类型标注的局部绑定，其所在文件编译器一律拒绝，所以「它仍然遮蔽、并且答不出任何东西」这条规则是从编译器自己的报错和解析树推出来的，不是在某个被接受的文件上测出来的。`VelaGotoDeclaration` 保留了自己那份读绑定的 token 扫描器，因为它需要绑定的**范围**而不只是类型；两个读取器在语料里每一个带类型的局部变量上都一致（`GotoOracle`、`RenameOracle`、`RenameWriteback` 重跑，数字相同），而「一致」是被测出来的，不是假设的。**另外 SPEC.md 在这一轮中间动了**：§1.3 在 05:59 增加了关键字 `enum` 与 `match`（见 §13 与新增的十四个 `tests\build\enum_*.vel`），而插件自己的关键字表还不认识它们——实测：把 `PlatformEntry` 在单个文件上重跑，它第 7 行的 `bare-end` 家族就报 `not offered: keyword enum, keyword match`。那是语言跑到了插件前面，这里点名写出而不是糊过去；本条里的数字属于 05:35-05:44 那个 SPEC.md。

## 0.1.7 —— 把平台自己的入口真正驱动起来（第 7、9、19 行）

**改了什么。** 三个缺陷，全部是**驱动**该行所讲的平台对象发现的，而不是靠读代码发现的；三个都是对已发布行为的修复：

* **补全把语言内置名排在了文件自己的声明前面。** `VelaCompletion.offer` 先遍历 `VelaModel.BUILTINS`，再遍历文件自身的符号，于是声明了 `extern c def abs(x: float)` 的文件会先被提供内置的 `abs(n)`，随后的去重又把文件自己的 `abs` 压掉——接受那一项会插入 `abs(n)`，而该声明根本没有这个参数。这条规则在这个插件里已经写过两遍（`VelaTargets.declaredParameterNames` 只对文件没写的名字才查语言表，`VelaInlayHints.parameterHints` 也照此办理），只有补全没有照做。现在，文件若以函数、方法或结构体的形式声明了某个名字，对应内置名会被跳过。实测：语料 2089 个位置中有 4 个，
  `template (n) names [n], and the declaration names [x]`；修后为 0。

* **参数信息弹窗高亮的是弹窗打开时插入符所在的那个参数。** 弹窗从 `ParameterHint.index` 取索引，而这个值只有 `findElementForParameterInfo` 设过：`updateParameterInfo` 算出新索引、通过 `setCurrentParameter` 告诉平台，却没有告诉将要绘制的字符串——于是插入符移动了，被高亮的参数却一动不动。更糟的是，索引是用 `callAt(context.offset)` 重算的，而这个函数只看插入符**所在的那一行**，所以对跨行参数表（`cap(a,\n  b)`）的第二行它什么也答不出来，索引就停在弹窗打开时的值。现在调用自己的 `(` 随条目一起传递，每次更新都从它开始计索引，并写回平台交给 `updateUI` 的那个条目。实测：修复前 2678 个位置中 emphasis 错 690、index 错 23；修复后为 0。

* **方法调用的接收者参数被画出来并高亮了。** 弹窗画的是**照写**的声明参数表，包括方法开头的 `self`，而索引数的是括号里的实参——于是每一次带接收者的方法调用都错位一个参数（`p.moved(10, 1)` 高亮 `self`，而编译器把那个实参绑定到 `dx`），而一个根本没有实参的调用也仍然高亮 `self`（`p.manhattan()`）。`VelaInlayHints.parameterHints` 早就解决了这件事（`declared.drop(1)`），弹窗没有。实测：emphasis 错 5、draw 错 3；修复后为 0。

**这不能证明什么，以及是谁测的。** `PlatformEntry`——`tools\harness\src` 下的第 15 个工具，已接进 `harness.ps1` 的 `-Tool PlatformEntry`——把第 7、9、19 行所讲的那三个对象都实例化出来，并调用平台所调用的方法：用平台自己的 `CompletionParameters` 和一个记录型 `CompletionResultSet` 驱动补全贡献者，在真实的 `InsertionContext`（底下的 `OffsetMap` 也是真的）里驱动 `LookupElement.handleInsert`；驱动参数信息处理器的五个步骤；以及用替身编辑器驱动 `charTyped` / `postProcessEnter`。一次遍历语料：**判定 13096 个位置，按 23 个具名类别跳过 1900 个，错 0**——第 7 行 `ran 2089 / wrong 0`，第 9 行 `ran 2678 / wrong 0`，第 19 行 `ran 8329 / wrong 0`——并且在读它的那些 0 之前，每个比较器都必须先在一个真实的语料位置上**按指令报错**（从答案里删掉一个成员、加进一个没人声明的名字、把实参索引挪一位、让处理器什么都不写、让 Enter 少写一个字符），否则工具以 3 退出。**没有启动任何 IDE**：测到的是判定本身——写了哪些字符、写在哪里、插入符与选区变成什么、补全贡献者提供并写入什么、弹窗被喂进什么又画出了什么——其中 `Document`、`Editor`、PSI 以及参数信息的三个 context 接口都是替身，每一个替身都在证据文件里点名。完整的原始运行、三个缺陷的前后数字，以及这个 harness **自己**在途中发现并修掉的四个缺陷（插入符被放进字符串字面量里、被遮住的闭合符被当成漏写、单元素列表被自己的对照"倒序"、以及按错误参数个数去反射构造函数）都在 `evidence\platform-entry-0.1.7-20260924-0520.txt`。

## 0.1.6 —— 第一批 inspection，以及一个**能失败**的高亮检查

**改了什么。**

* **三条 `localInspection` 规则，配上能用的 quick fix。** `FEATURE_PARITY.md` 对这一项的说法
  是「完全没有 `localInspection`，也完全没有 quick fix」，而这在当时是真的：类已经写好，验证器
  拒绝接受它们，把三条都报成「实现了 `LocalInspectionTool`，但没有注册、也没有任何 class 文件
  指向它」。现在三条注册都在了，每条规则报的都是**编译器自己在那一行拒绝的东西**：对不可变绑定
  赋值（修法：插入 `mut `）、`"a" + "b"`（修法：`concat(...)` 内建）、以及一个 int 与一个
  float 混在同一运算里（修法：给 int 那一侧套 `to_float(...)`）。

  对着冻结编译器、311 个语料文件实测：`ran 257 / skipped 54 / wrong 0`，并且在**编译器接受的
  131 个文件上零条 finding**；13 条 finding 里 13 条都被走到「编译器在该行、用该条措辞拒绝」，
  每个 fix 之后 `check` 都停在 exit 0。方法本身是要紧的，因为 `vm.exe check` **只报第一条**
  拒绝：一条 finding 是按「修掉它再问一次」验证的，不是按「这一行有诊断吗」验证的。负对照是把
  某条规则的判据改了一个词，得到 `wrong 41` 与 exit 1，改回后归零。

* **`FeatureProbe` 的第 1 节又能失败了。** 它曾报 `BAD_CHARACTER` 没有颜色 key。**插件是对的，
  探针是瞎的**：它的查找只在一个写死的 13 个 token 常量里找，而词法器吐的是**平台的**
  `TokenType.BAD_CHARACTER`，于是这一节在调用高亮器**之前**就短路了。现在它把高亮器实际画出的
  key 与配色设置页注册的 key **双向**比较。改名之后它仍然通过，而这是这个插件的**结构事实**、
  不是检查的缺口：设置页那一行与高亮器持有**同一个** `TextAttributesKey` 对象，改名会同时移动
  两边。这条检查抓到的是「那一行指向了另一个 key」和「那一行自带一份名字」。

**这一版没有证明什么。** 没有启动过 IDE。`LocalQuickFix.applyFix` 最后四行（在活的文档上做写
操作）没有被驱动，alt-Enter 菜单没有被打开，而「某个颜色能不能在用户的**配色方案**里解析出来」
被记为具名跳过（`needs-an-application-instance`），不是记为通过。

## 0.1.5 —— 每一个红的验证器都变绿，两个永远不可能对的变成可判定的

**改了什么。** 两个缺陷，都是被测量找出来的而不是被读出来的，外加两个验证器——它们先前
报告的东西，读者没法据以行动。

1. **扫描器那份被截断的 token 清单声称了一个它从来没有读过的字符。**
   `VelaSyntaxScanner.scan` 保留它在一次失败之前扫到的那些 token，并用一个换行和一个 EOF
   把这份清单收尾。那个换行是 `newline(pos)`——一个**一字符宽**的 token，落在扫描停下来
   的那个偏移上。对一个没有终结的字符串，那个偏移就是开引号，所以：

   * 解析器的 token 清单同时声称了 `31..32`（那个 `"`）*和* `31..36`（字符串的内容）——
     同一个字符被声称了两次，被两个不同种类的 token；
   * `psi-tree-diff.ps1` 判定“这个叶子在解析器读到的最后一个 token 之后”时，比的是解析器
     那些 token 里最大的那个 end；那是换行的 32 而不是字符串的 31，所以一个恰好从 31 开始
     的叶子不满足 `>= 32`，掉进了“每一个非平凡叶子都必须是解析器所声称的一个 token”那项
     检查。**`tests/build/lexer_error.vel` 让它失败**，126 里的 125。

   收尾的那个换行现在在失败偏移处是零宽的：仍然是一个 token，仍然是一条被截断语句的语句
   边界，而它不声称任何字符——这就是事实，扫描器从来没有读到过一个字符。

2. **`FoldDiff` 和 `SymbolDiff` 得到了它们自己的数字所缺的那条判据。**
   两者都拿这个插件*已退休的*实现（一个括号匹配器和一次 token 扫描）来对照那个要发布出去的
   树派生实现。编译器和任何别的权威都没有定义一个折叠区域清单或一个符号清单，所以“62 个
   不同”和“40 个不同”不是对某个缺陷的读数，却被当成 62 个和 40 个 bug 在读。两个工具现在
   给每一处差异分类：

   | 工具 | 类别 | 由什么判定 | 语料 |
   |---|---|---|---|
   | `FoldDiff` | **只是粒度** | 每一份清单的每一个区域都落在另一份的某个区域之内——两者对*哪段文本可折叠*意见一致，差异只在切得多细 | 38 |
   | `FoldDiff` | **内容** | 某个区域落在另一份的任何区域之外——一次真正的分歧，也就是失败计数 | 24 |
   | `SymbolDiff` | **只是类型拼法** | 退休扫描的 `[int, 786432]` 拼法一旦归一成规范的 `Array[int,786432]`，两份清单就完全相同；同一批声明，拼法不同 | 2 |
   | `SymbolDiff` | **结构性** | 符号个数与退休扫描相同，但 kind/name/line/parent 有差异，或者有一处熬过归一化的细节差异——失败计数 | 13 |
   | `SymbolDiff` | **个数不同** | 两者对一个文件里有多少个声明意见不一，树报告的比退休扫描多——被计数并打印，不算失败（`tree-reports-more-declarations` 25、`scan-reports-more-declarations` 0） | 25 |

   `FoldDiff` 那条规则是**在被写进去之前先被测量过的**，而那次测量被留着：
   `tools\probes\src\FoldShapeProbe.java`。在 126 个文件的语料上，它发现 64 个完全相同、
   38 个两个方向都不同、24 个只在一个方向不同，而**0** 个文件出现任何区域不在另一份清单的
   跨度里。

   按总数读，`FoldDiff` 的 62 处差异和 `SymbolDiff` 的 40 处就是这些类别，没有别的：
   38 + 24 = 62，而 2 + 25 + 13 = 40。五类里只有两类是**分歧**——`FoldDiff` 的内容类（24）
   和 `SymbolDiff` 的个数相同而内容不同类（13）。另外三类（38 只是粒度、2 只是类型拼法、
   25 个数不同）只被计数并打印，不是失败。退出码跟着这条线走：`FoldDiff` 只为那 24 个内容
   文件退出 1，`SymbolDiff` 只为那 13 个个数相同而内容不同的退出 1。

3. **`HintNames` 不再把一个被声明的限度叫作一次分歧。** 在
   `tests/build/check_cases/unannotated_parameter.vel` 上——`def f(n)`，被编译器拒绝——
   树正确地声明**没有** `param` 节点（一个没有标注的参数不是这门语言有的一种声明），而符号
   模型报告 `[n]`。那曾经是这个工具唯一的那一个 `wrong`。它现在被计成
   `not-judgeable-in-a-refused-file`，一个被打印出来的类别，因为“一个参数清单的两个来源
   互相分歧”和“解析器拒绝声明某个非法的东西”绝不能以同样的方式打印。**在一个确实解析成功了
   的文件里的分歧，仍然是一次分歧。**

4. **缓存写入那条声称是靠制造它来证明的，不是靠读代码——而证明它找出了第二个缺陷。**
   `GotoOracle --probe-cache` 跑**8 个线程在同一个路径上调用真正的 `saveCache`**，同时一个
   读者对那个文件采样，并拒收任何不是一份完整缓存的快照。它先检查它自己的仪器——一行被故意
   截断的行、一行没有制表符的行、以及一个值里含换行——都必须被读成撕裂的，因为一个不可能失败
   的验证器什么都证明不了。`harness.ps1 -Tool GotoOracle -CacheProbe` 跑它。

   这个探针的头三次运行以三种不同的方式出错，而每一个错误都被写进源码里，免得重犯：

   | 运行 | 报告 | 它实际上是什么 |
   |---|---|---|
   | 1 | 9,065 份快照“撕裂”，一共 9,025 份 | 读者看到了 `Files.move` 还没让那个路径可见的那个窗口——一次仪器故障，现在它有自己的计数结果（“在移动的窗口期内读取”） |
   | 2 | 8,151 份“撕裂” | **探针自己的夹具**：值被写成了 `"21,34\n99,120"`，而这个缓存是一行一个条目，所以每个这样的值的后半段是一行没有制表符的内容。真正的格式是 `key<TAB>comma,separated,lines` |
   | 3 | 320 次保存里有 38 次抛出，而没有一份快照被撕裂 | `java.nio.file.AccessDeniedException: probe-cache.txt.tmp -> probe-cache.txt`——**`saveCache` 里的一个真缺陷** |

   那个缺陷：在 Windows 上，当另一个句柄打开着目标文件时，把一个文件重命名到一个已存在的
   文件上会以 `AccessDeniedException` 失败，而 `saveCache` 只捕了
   `AtomicMoveNotSupportedException`。所以恰恰在 `synchronized` 为之而写的那个争用之下，
   写入是抛异常而不是落地。从来没有东西被撕裂过（老文件整个活了下来），但那个 map 里的条目
   **静默地不在磁盘上**。原子移动现在被重试，有界、带退避，而一个熬过重试的失败会作为它自己
   被重新抛出。修好之后，同样 8 个线程在同一个路径上：`320` 次保存里 `320` 次返回、`0` 次
   抛出、**`11,608` 次读取里 `0` 份被撕裂的快照**、`0` 份短快照，而最终那个文件持有每一次
   保存所写下的全部 `2,000` 个键。

   这是这个发布里唯一一个被同一个发布里写出来的验证器找到的缺陷。

**这个发布证明了什么** —— 下面每一个数字都来自这一版构建上一次
`harness.ps1 -Tool All` 通过，而原始日志留在
`idea-plugin\evidence\harness-0.1.5.txt`；下面那一行里 `SymbolDiff` / `FoldDiff`
的分类是之后重跑的，它的原始输出是 `idea-plugin\evidence\detectors-0.1.5.txt`：

| 工具 | 覆盖率三元组 | 裁决 |
|---|---|---|
| `ast-diff.ps1` | `ran 114 / skipped 12 (compiler-refused 12, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS |
| `psi-tree-diff.ps1` | 见证据文件 | PASS, 126 of 126 |
| `GotoOracle` | 见证据文件 | 0 wrong |
| `HintDiff` / `HintNames` / `HintShapes` / `HintDupes` | 见证据文件 | 0 wrong |
| `SymbolDiff` / `FoldDiff` | 见证据文件 | 13 structural, 24 content |
| `FeatureProbe` / `ParamNames` / `HintTruth` | 见证据文件 | 0 wrong |

**它不证明什么，直说。**

* 没有启动任何 IDE。这里没有任何东西表明插件能加载、工具窗口会出现、或者有哪一个功能在
  屏幕上是正确的；最强的证据仍然是平台注册的读回加上这些无头差分。
* `SymbolDiff` 和 `FoldDiff` 仍然是**回归探测器**。它们的类别名说的是差异*由什么构成*，
  不是说要发布的那一侧更好：两者在粒度或拼法上分歧的地方，那个选择是一个设计决定，是靠
  例子读出来并判定的，不是对着某个权威测出来的。
* `partial` 那些行保持 `partial`。补全、悬停、查找用法、重命名和输入/回车处理器仍然
  **没有行为测量**——只有一个平台注册，它证明平台会来问，而对回答什么都不证明。
  真 PSI 解析器那一行的验证器现在是绿的；那一行自己的状态在 `FEATURE_PARITY.md` 里讨论，
  而不在这里决定。

## 0.1.4 —— 验证器变得可证伪，而 AST 窗口长出第二种模式

**改了什么，以及为什么每一条都是一个 bug 而不是一个功能。**

1. **跳转声明的裁决器在每一个带 `extern` 声明的文件上都崩，而那次崩溃是看不见的。**
   `GotoOracle.bindUseLines` 对“编译器没有注意到关于重命名这个声明的任何事”回答
   `null`，对“未验证”回答一个空列表，而调用点把那个回答当成它永远是一个列表。对
   `null` 做增强 for 抛出了 `NullPointerException: Cannot invoke "java.util.List.iterator()"`，
   按文件捕获的 `catch (Throwable)` 把它记成“裁决器跑不了”，而那次运行仍然以
   `VERDICT: no reference goes to a declaration the compiler does not bind it to` 结束。
   有九个文件从来没有被判定过——`tests/probes/extern_*.vel`、
   `tests/build/extern/extern_c_probe.vel`、`selfhost/vela.vel`、`tests/run_tests.vel`，
   以及两个 `tests/safety/cases/hole_*` 文件——而那次的裁决是对那些碰巧活下来的文件
   的一次通过。已修复，而缓存分支本来就已经理解的那种 `-` 缓存写法，现在是 null 情形
   所写下的东西。

2. **每一个验证器现在都以三元覆盖率结束，而不是一个光秃秃的裁决。**
   `COVERAGE: ran <n> / skipped <n> (<why> <n>, …) / wrong <n>`。一个工具可能因之跳过的
   每一个类别都**事先声明，即使为零也打印**，因为“0”和“这个类别从来没有被计数过”
   绝不能看起来一样，而 `ran + skipped` 会被对着语料断言，所以一个既没被比较也没被
   归类的文件是一条打印出来的 NOTE，而不是沉默。这是对 (1) 那个失败的一般性修复：
   一个裁决现在可以被它自己的测量证伪。

3. **裁决现在有退出码。** `AstDiff`、`SymbolDiff`、`FoldDiff` 和 `HintNames` 无条件退出 0，
   所以 `VERDICT: FAIL` 和 `VERDICT: PASS` 对一个构建来说无法区分。现在：1 = 插件错了，
   3 = 测试框架或语料有问题，0 = 通过。`GotoOracle` 对一个崩溃的文件退出 3，
   而不是报告一次通过。

4. **崩溃和分歧被分开计数。** `SymbolDiff` 和 `FoldDiff` 把抛出的异常计成 `differ`；
   `HintDiff` 把一个没有画出提示的实参位置计成什么都没有。两者现在都有自己的计数器，
   而缺提示那种情形是一条发现。

5. **`AstDiff` 会点出指向空处的语料条目所对应的清单行。** `tests/cases.txt:174`
   命名 `tests/build/lexer_error.vel`，而那个文件不在磁盘上，所以测试框架是 FAIL，
   却带着**零**个树差异——108 个文件在 119,923 行节点上完全相同，外加一条 MISSING 行。
   那是语料的一个缺陷，而这个插件的所有者并不拥有语料；它现在是自己一个类别
   （`missing-corpus-file`）并带自己的退出码，它被报告而不是被吸收。

6. **`VelaSyntaxDump` 现在可达了，所以“无作用的功能”那项检查是被诚实地满足的，
   而不是靠一份白名单。** 验证器先前已经在 `[check unregisteredImplementations]` 下
   让它失败：它编译过、发布过，而 jar 里没有任何东西能调用它。它现在成了
   **Vela AST 工具窗口的第二种模式**——`Live parse (editor buffer)`——它解析编辑器
   正持有的文本（包括未保存的编辑，以及从来没有被保存过的文件），并用编译器自己的
   树格式把它打印出来。`vm.exe parse` 只能描述最后保存下来的那些字节，所以这是一个
   能力，不是一个重复品。那个窗口有一个视图和一个活动模式，所以两种模式不可能为同一个
   树模型竞争；`Compiler (vm.exe parse)` 是默认值，行为没有变。

7. **裁决器的缓存写入在它自己的并发下是安全的。** 八个线程每 200 个新条目就在同一个
   路径上调用 `Files.write`，所以两个可能把文件写坏——而一个写坏的缓存不是一次崩溃，
   它在下一次运行时是一个*错误的裁决器*，因为那个值是一串以声明哈希为键的行号。
   现在一次只有一个写者，通过一个临时文件加一次移动。

**这个版本里测量到的东西**

- 四个版本位置读回来都是 `0.1.4`（plugin.xml 的 `<version>`、`build.gradle.kts` 的
  `version`、dist zip 自己的名字、本文件的标题）。
- `build-offline.ps1` 退出 0、`RESULT: PASS`，以及它写出的产物：
  `dist\vela\lib\vela-idea-plugin.jar` **344,619 字节**（sha256
  `db00087120f914ce7a76c49c540c69c969a2c08cc05b3186944fdf7058684415`）和
  `dist\vela-idea-plugin-0.1.4.zip` **324,186 字节**（sha256
  `a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033`，一个条目：
  `vela/lib/vela-idea-plugin.jar`）。36 个 Kotlin 源文件、148 个 class 文件，
  最高的字节码主版本 65（Java 21）。
  这些数字在这一轮里动过两次，两次移动都被记录下来而不是被藏起来：
  344,626/324,198（sha256 `55bf14a3...`）是下面那个 `isSystem` 修复之前的构建，而
  344,574/324,147（sha256 `b43c480b...`）是同一批源码的 PyCharm 2025.3 构建。读者在
  `dist\` 里找到的应当与第一对相符。
- **关于可安装性，不能验证的是什么**：没有启动任何 IDE，所以“安装这个 zip，它就加载”
  在这里没有被测量。*被*测量的是除那之外的一切：jar 里的描述符与源码描述符逐字节
  相同、`plugin.xml` 命名的每一个类都在 jar 里、每一个扩展点 id 都解析到已安装平台里的
  一条声明、每一个注册所用的属性都是平台自己的描述符所绑定的那一个、全部 30 个被注册
  的类都被从 jar 里重新读出来作为平台所要求的确切接口的实例，以及 class 文件主版本是
  IDEA 2024.2+ 能接受的那一个。
- 验证器的 `RESULT: PASS`：36 个 Kotlin 源文件、145 个 class 文件、每一个注册都对照
  平台自己对其扩展点的声明读回来，以及那些无头行为检查。
- `FEATURE_PARITY.md` 是新的：每一行一个用户可见的能力，连同 Python 插件在做什么
  （从 `PythonCore 253.28294.336` 的 `python-ce.jar`/`python.jar`/`python-dap.jar`
  里 grep 出来）、Vela 的状态、实现它的文件，以及支撑它的那个测试框架或平台注册。
- **`vm.exe debug` 保持 `refused-deliberately`。** 插件里任何地方都没有宣传它：
  `VelaRunConfiguration` 实现了 `RunConfigurationWithSuppressedDefaultDebugAction`，
  而这个配置被写成接受任何不是 `Debug` 的 profile，所以 IDEA 显示那个拒绝，而不是一个
  会挂接到空处的按钮。没有实现任何调试器，也没有声称有。

**这个版本带着发布出去的未决缺陷，以及它们背后那些原始数字。** 这些被写在这里，写在发布
记录里，而不是写在别处的某条注记里，因为一条只列出顺利之处的发布说明，正是这个项目一直在
为之付代价的东西。

1. **`HintDiff`：30 个实参位置没有得到参数名提示，而 125 个无法被判定。** 原始的最后一行：

   ```
   VERDICT: 30 hint position(s) are not right
   COVERAGE: ran 29006 / skipped 127 (compiler-cannot-parse 2,
             arg-boundary-disagreement 125) / wrong 30
   ```

   *绘制出来*的每一样都是对的——`hints drawn 10688 / correct 10688 / WRONG 0 /
   beyond 0`——所以这不是 `s: s: s:` 那一类缺陷。那 30 个里的每一个都是一个实参，其文本
   是含 `'`、`(` 或 `)` 的字符串字面量，或者是一个嵌套调用：
   `ck_quoted(s, x, "', which is not declared 'pure'")`、`concat(a, " does not fit in
   int (64-bit signed)")`、`perr(kind, msg, ti_line(tk, cx))`。怀疑对象是
   `VelaInlayHints.kt` 里那个逐字符扫描的实参范围读取器（`argumentRanges` /
   `endOfQuoted` / `matchingParen`）；声明读取器是干净的（`HintDupes`：0 个重复参数名、
   0 个空名字）。那 30 个全在 `selfhost/parts/*` 里，以及在 `selfhost/vm.vel` 里重复
   出现的同一段文本。**被点名，没有被修复**，而这就是参数提示那一行在
   `FEATURE_PARITY.md` 里是 `partial` 的原因。

2. **`AstDiff` 现在是 PASS，而且它是在一个长出六个文件的语料上通过的。**
   最终：`126 files in the corpus / 114 match (121 856 node lines) / 0 different /
   0 suspect / 0 missing / 12 compiler refused (all 12 of which this parser also
   refused) / 0 crashed / VERDICT: PASS`，退出 0。一小时前同一个测试框架是 `FAIL`
   而 `0 different`——唯一的不匹配是 `tests/cases.txt:174` 命名了
   `tests/build/lexer_error.vel`，而那个文件不存在。本轮里另一个 track 创建了那个
   文件，这修好了清单，并立刻暴露了下面的缺陷 3。这个测试框架现在把这样的条目连同它的
   清单行和自己的退出码一起报告（3 = 语料缺陷，1 = 插件是错的），所以这两者再也不可能
   被弄混。

3. **`psi-tree-diff.ps1` 在 126 个文件里失败 1 个，而找到它的那个文件是一个指向空处的
   语料条目。** 原始输出：

   ```
   tests/build/lexer_error.vel: leaf VELA_STRING at 31..38 is not a token this parser claimed: `"hello)`
   files replayed through the platform's builder : 126 of 126;  ok 125  failed 1
   VERDICT : FAIL
   COVERAGE: ran 125 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0)
   ```

   `tests/cases.txt:174` 命名 `tests/build/lexer_error.vel`，而那个文件不存在；本轮里
   另一个 track 创建了它，它立刻找到了这个。在一个扫描器拒绝接受的文件上（一个没有终结的
   字符串），解析器只保留失败之前的那些 token，`VelaLexer` 为整个 `"hello)` 产出一个
   `VELA_STRING`，而重放的 `alignEndAfter` 把那个 token 拉进树里——在那个地方，测试框架
   断言每一个非平凡的叶子都是解析器所声称的一个 token。编译器和解析器*一致*认为这个文件
   不是合法的 Vela，所以这个插件关于这门语言是对的，而它关于哪个 token 覆盖一个没有终结的
   字符串与自己不一致。在那个被决定之前，第 5 行是 `partial`。一个指向空处的语料条目，
   一直在藏着一个测试着东西的语料条目。

4. **`since-build="253"` 是假的，而它是靠守住所许下的承诺才被发现的。**
   `build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"` 失败于
   `VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'`——
   `ProcessOutputType.isSystem(Key)` 存在于 IntelliJ IDEA 2026.2 而不存在于 PyCharm
   2025.3，所以那些源码无法对着 `plugin.xml` 所声明的平台编译。修法是一次同一性比较
   （`outputType === ProcessOutputType.SYSTEM`），它存在于两个平台上，而且不会像
   `ProcessOutputType.fromKey` 那样抛异常。两边的构建现在都从这同一棵树上通过：
   253 -> jar 344 574 / zip 324 147
   （sha256 `b43c480b71e59843b85002e183cc1aa04f38b4eeb03d8926ef9d5bccda206fc2`），
   262 -> jar 344 619 / zip 324 186
   （sha256 `a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033`，即
   已发布的产物）。**这是这张清单里唯一一个被关掉的缺陷。**

5. **`HintNames`：一处分歧。** `tests/build/check_cases/unannotated_parameter.vel line 2 `f`  tree=[]  model=[n]`。那个文件是一个编译器拒绝的用例（`def f(n)` 带一个未标注的参数），所以树没有记录任何参数，而符号模型从细节文本里读出 `n`。在非法的 Vela 上，树是那个站得住的读法，但两个来源有分歧，而计数说的是 1 而不是 0。

6. **`harness.ps1 -Tool All` 跑不完，而这就是为什么这些数字里有两个从来没有被产出过。** `HintShapes` 扫过每一个文件的每一个 16 字节前缀，那是 O(size^2 / step)：`selfhost/vm.vel` 大约 400 KB，所以一个文件花掉大约 25 000 次对一个平均 200 KB 文档的解析。两次独立的 `-Tool All` 运行都在 `=== HintShapes` 上坐了超过 50 分钟，这意味着 `HintDupes`、`SymbolDiff`、`FoldDiff` 和 `FeatureProbe` **在一次 `All` 通过里根本没有跑过** - 而它们的缺席一直被当成它们的同意。那次扫描现在被限制到每个文件最多 256 个前缀（`runs: 5797`、`too-large-for-prefix-sweep 4`，两者都打印），而一次 `All` 通过不到四分钟就完成。

7. **`SymbolDiff` 有 40 个文件不同，`FoldDiff` 有 62 个文件不同**，两者都是对着这个插件
   自己*已退休的*实现，而不是对着某个权威（没有权威：编译器不打印折叠区域，也不打印
   符号清单）。那些差异就是这次替换——树模型列出了 token 扫描漏掉的 `extern` 声明；
   树把整个函数体从 `{` 折到 `}`，而那正是 PyCharm 对一个方法体所做的。两者都在
   `build\evidence\harness-0.1.4.txt` 里逐文件枚举。不存在任何外部权威可以把每一个
   单独的差异称为一次改进，而说得相反就会是一个声称。

**在产生那份否定证明的过程中，发现并修复了两个验证器漏洞**——两个都是被否定测试发现的，
而不是被读出来的，而且两个都是同样的形状：一项检查之所以是绿的，是因为它的问题比它看起来
要弱。

- `check smoke` 用了一个提前的 `return`，它中止了整个编译器部分——包括
  `emitterHeaderContract`，那是两个文本的一次静态比较，并不需要 C 编译器。所以那项为
  一个坏掉的 emitter/header 契约而存在的检查，在构建真的坏掉时从来没有跑过。构建结果现在
  被记住了，只有那些需要一个已构建可执行文件的检查才被跳过。
- `emitterHeaderContract` 问的是 `header.contains(symbol)`。把
  `runtime/vela_runtime.h` 里的 `vela_bounds_check` 改名成
  `vela_bounds_check_renamed`，仍然“包含”着 emitter 所调用的那个名字，所以那项检查对着
  一个不再声明它的头文件打印了
  `vela_bounds_check ... all defined`。它现在是一次整词匹配。

**这个版本里没有被验证的东西**

- 没有启动任何 IDE。这个会话里在这台机器上启动不了任何 IntelliJ 实例，所以这里的
  *任何东西*都不是插件能加载、工具窗口会出现、菜单项在它该在的地方、或者这些功能
  里有哪一个在屏幕上是正确的证据。能拿到的最强证据是平台注册的读回加上无头差分，
  而它在 `FEATURE_PARITY.md` 里被标为如此。
- `Live parse (editor buffer)` 模式编译过了、可达；它**没有**在 IDE 里跑过。被验证的
  是它的解析就是编译器的解析（那是 `ast-diff.ps1`），不是 Swing 面板把它渲染出来。
- 变异证明是当前的，而且它是与那次通过*不同的一个文件*：
  `build\verify\mutation-0.1.4\mutation-report-0.1.4.txt`（头几行里有版本、jar SHA256 和
  时间，外加每一个变异体一份验证器日志）报告 **13 个变异体、12 个被抓到、0 个漏洞、
  1 个对照正确、0 个脚本/裁决问题**，基线是干净的——`classRegisteredNowhere` 确实被
  抓到了，靠的是 `[check unregisteredImplementations]` 从已安装平台自己的描述符里读出
  **1,332** 个必需类型。`build\verify\negative-0.1.4\negative-report.txt` 是更老的那套
  A–I，现在是 **12/12 抓到、0 漏掉、0 无效**。仍然没有被抓到的是：一个实现着没有任何
  已安装扩展点所声明的契约、并且哪里都没有注册的类——没有清单可以拿它去对照；这次构建里
  那个计数是 0。
- **没有启动任何 IDE**，所以“安装这个 zip，它就加载”没有被测量。



## 0.1.3 —— 构建过并验证过；第一个能编译的版本

**为什么会有 0.1.3。** 下面 0.1.2 那条记录写着“未验证，还没构建”，而它是被诚实
地那样写的：那一整轮里，在这台机器上跑命令的测试框架都是死的。第一个有可用 shell 的
轮次把 0.1.2 的源码送进 `build-offline.ps1`，它们**没有**编译过——四个错误，在两个
文件里，属于读多少遍都找不出来的那种：

- `VelaFormatter.kt` —— 在这个平台上（`IU-262.9437.185`，kotlinc 2.3.20）
  `ASTBlock.getSpacing` 把 `Block?` 当第一个参数，不是 `Block`：`VelaRootBlock` 和
  `VelaLeafBlock` 既被报成“不实现抽象成员”，又被报成“什么都没覆写”。两个签名被修好。
- `VelaFindUsages.kt` —— `private val wordsScanner` 生成了一个 `getWordsScanner()`
  访问器，它在 JVM 层面与这个类所覆写的那个接口方法（`getWordsScanner`）冲突。
  字段现在叫 `scannerOnce`，理由写在声明处。

所以 0.1.2 从来没有被构建过，也从来没有被发布过；那四处修复记录在这里，而不是通过
修改 0.1.2 自己的那条记录，因为一个版本的记录是那个版本当时是什么，不是它本该是什么。

**这个版本里测量到的东西**

- 全部 32 个 Kotlin 源文件在离线路径下编译通过（`kotlinc` 来自已安装的 IDE，
  没有 Gradle，没有网络）。这是这个插件里的东西第一次被编译。
- `jar` 256,668 字节；`dist\vela-idea-plugin-0.1.3.zip` 240,264 字节。
- 验证器的 `RESULT: PASS`——每一项结构性、字节码、平台、注册、链接和行为检查
  （11.0 s；完整清单是 `build\logs\verify.log`，机器写出的表面是
  `build\logs\surface.txt`）。
- **在走到那里的过程中发现并修复了一个验证器缺陷**，它值得被点名，因为它正是这个
  验证器存在去抓的那一类 bug：`<with attribute="..." implements="...">` 那次扫描
  在每一个 `<extensionPoint>` 之后读一个固定的 4000 字符窗口，并且只在 400 个之后才停，
  所以*下一条*声明的属性被算到了这一条头上。`configurationType` 是用 `interface=`
  声明的，没有自己的 `<with>`，所以它继承了邻居的拼法，而这个插件的八个注册被报成
  未绑定的属性。扫描现在只读声明自己的主体——从标签结束处到 `</extensionPoint>` 和
  下一个 `<extensionPoint` 中更早的那个，而对一个自闭合声明什么都不读。那八个是平台
  自己的拼法，对照平台自己的插件核对过：`configurationType`、`runConfigurationProducer`、
  `gotoDeclarationHandler`、`colorSettingsPage`、`typedHandler`、`enterHandlerDelegate`、
  `codeStyleSettingsProvider` 和 `langCodeStyleSettingsProvider` 用 `implementation=`
  ——那些扩展点里每一个都在 `D:\JetBrains\IntelliJ IDEA 2026.2.1\lib\*.jar` 和
  `plugins\**\*.jar` 里被那样注册过几十次。

**仍然没有验证、也不可能从这里验证的东西**：这个插件从来没有被加载进一个运行中的 IDE。
`ActionManager` 和扩展注册表需要一个启动起来的应用，所以折叠、输入行为、格式化器的输出
和 Run 控制台是按结构、对着字节码和对着描述符检查的——不是通过驱动编辑器。安装那个 zip
才能了结这件事。

## 0.1.2 —— 未验证，还没构建

下面的一切都以源码形式存在于这棵树里，而**没有任何东西被编译或运行过**：在这台机器上
跑命令的测试框架在一次构建之后就死了（一个游离的 `cl.exe` 助手让它的 Windows job 
object 一直非空），而它在那整项工作期间都是死的。`idea-plugin/BUILD_CHECKLIST.md`
装着编译闸门、最可能出错的地方和验收测试，所以第一个有可用 shell 的轮次可以一趟了结
全部。

**编辑器机制 —— 10 个 Kotlin 文件，11 个注册**
- 格式化：`VelaFormatRules.kt`（token 序列的一个纯函数）、`VelaFormatter.kt`、
  `VelaTokenScan.kt`，以及代码样式设置（`VelaCodeStyle.kt`），好让缩进大小是一个
  可见的设置。
- 折叠（`VelaFolding.kt`）、注释（`VelaCommenter.kt`）、括号匹配
  （`VelaBraceMatcher.kt`，只有 `{ }` `( )` `[ ]`）、输入和回车处理
  （`VelaTypedHandler.kt`）、一个配色方案页面（`VelaColorSettingsPage.kt`），以及
  实时模板（`VelaLiveTemplates.kt` 加上 `resources\liveTemplates\Vela.xml`：
  `defn`、`main`、`struct`、`forr`、`whilee`、`ifel`、`parfor`）。

**导航与洞察**
- 跳转声明（`VelaGotoDeclaration.kt`）、语义着色（`VelaSemanticHighlighting.kt`）、
  参数名内联提示（`VelaInlayHints.kt`）、查找用法（`VelaFindUsages.kt`，带一个真正的
  `WordsScanner`），以及引用/重命名的管道（`VelaReferenceContributor`、
  `VelaLeafManipulator`）。

**被纠正的注册**
- `psi.referenceContributor` 被注册在 `implementationClass` 下，而平台忽略它——那个
  扩展点的 bean 把它的字段标注为 `@Attribute("implementation")`，所以重命名和查找用法
  是死的，而 Ctrl+Click 还能用。已修复，三路证明记录在 `plugin.xml` 里。
- 三个类做完了却根本没被注册：语义标注器、跳转声明处理器和内联提示提供者。三个现在
  都注册了，而验证器正在被教会抓这一类 bug。

**运行配置**
- Run 现在直接解释执行那个文件；不产出可执行文件，而控制台打印命令行、解释器的输出
  和退出状态。
- 控制台以前什么都没显示，而且是构造上就必然如此：那个配置自己构建了一个 `ConsoleView`
  并打印进那里面，而 Run 工具窗口显示的是平台为处理器创建的那一个。输出现在走
  `notifyTextAvailable`，所以平台的 console 是唯一的控制台，子进程自己的流类型也被带上
  （诊断保住它们的错误颜色）。
- **Run 以前的形状是错的，它现在是一个子进程，不是两个。** 它先构建（`vm.exe build`）
  再启动那个 `.exe`，那需要一个 C 编译器和 MSVC 的环境，会在用户的源码旁边写下
  `.c`/`.obj`/`.exe`，并继承一个 include 路径问题：随包交付的 `vm.exe` 把它的运行时
  include 目录解析成*相对*字符串 `runtime`，所以从用户的项目目录——`vela\tests`——
  它会以 `fatal error C1083: cannot open include file: 'vela_runtime.h'` 死掉，退出 2，
  而屏幕上什么都没有。`vm.exe run <file>` 这些都不需要。运行路径现在是：打印确切的
  命令行、启动解释器、转发它写出的每一个字节、传播它的退出状态。
- 代价是被说出来而不是被藏起来：控制台显示的是*解释器*的输出，它比一个编译出来的
  二进制慢（语料的 `run` 用例把两者都测了；README 把解释器放在一个 MSVC 无法向量化的
  循环上约 76x 的位置）。编译仍然是 `Vela.BuildAndRun`。
- 缺少编译器现在被报告**在控制台里**，而不是只作为一个对话框：`checkConfiguration`
  仍然拒绝一个缺失的*文件*，但编译器是运行路径自己的事，所以一次起不来的运行会在
  用户正在看的地方解释自己。

**已知且刻意的缺口**，全都在 `BUILD_CHECKLIST.md` 里点过名：代码样式页面的空格/换行
标签页是空的、配色页面列出 14 个词法键而不是语义种类、Enter 按常量 4 缩进而不是按
读者的设置、`<`/`>` 不配对（词法分析器给每一个运算符同一个 token 类型），以及后缀
模板缺席，理由写了下来。

## 0.1.1 —— 构建过并验证过

- 文件类型只有 `extensions="vela"`。这个仓库里每一个 Vela 文件都以 `.vel` 结尾，所以
  插件在一个真实的文件上什么都没做。现在描述符读作 `extensions="vel;vela"`，而
  `VelaFileType.EXTENSIONS` 是 `[vel, vela]`，`getDefaultExtension()` 返回 `vel`
  ——由 `VerifyPlugin` 第 6 节实测，它从 jar 的描述符里读出那个属性，并从加载出来的
  类里读出那两个常量。（这条记录到 0.1.4 之前一直写着 `vela;vela`：这里第一个后缀是
  错的，而 `plugin.xml`、`README.md` 和那个类本身说的都是 `vel`。是按测量更正的，
  不是按哪种拼法先出现更正的。）
- `<add-to-group group-id="Vela.BuildAndRun">` 把一个*动作*当成组来命名：平台记录了
  `SEVERE ... should be instance of DefaultActionGroup`，而两个动作都没进过任何菜单。
  改成一个动作，放进平台自己的 `NewGroup`。
- `<parserDefinition>` 命名一个没有任何已安装描述符声明的 id（真的那个是
  `lang.parserDefinition`），而 `<lang.structureViewBuilder>` 命名一个不存在的扩展点
  ——两个都是静默空操作，两个都是被验证器发现的。
- 注册了 `runConfigurationProducer`，所以 IDEA 自己的 Run 菜单、装订区箭头和重跑历史
  都会提供一个 `.vel` 文件。

## 0.1.0 —— 构建过并验证过

- 第一次构建：文件类型、语法高亮、来自真实编译器的诊断、一个结构视图、一个 AST 工具
  窗口、一个编译器路径的设置页面，以及 New 菜单里的一个“Vela File”条目。
