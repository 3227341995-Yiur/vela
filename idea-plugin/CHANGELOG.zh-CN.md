# Vela IDEA 插件 —— 变更日志

[English](CHANGELOG.md) | **简体中文**

<!--
源文件 : CHANGELOG.md
源文件字节 : 33569
源文件 SHA256 : 3969e63fb6c402e62dd30394361ed5c38e254f22a61fff07c9c72907b5cc8230
翻译日期 : 2026-09-24
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
   | `FoldDiff` | **只是粒度** | 每一份清单的每一个区域都落在另一份的某个区域之内——两者对*哪段文本可折叠*意见一致，差异只在切得多细 | 62 |
   | `FoldDiff` | **内容** | 某个区域落在另一份的任何区域之外——一次真正的分歧，也就是失败计数 | 0 |
   | `SymbolDiff` | **只是类型拼法** | 退休扫描的 `[int, 786432]` 拼法一旦归一成规范的 `Array[int,786432]`，两份清单就完全相同；同一批声明，拼法不同 | 40 |
   | `SymbolDiff` | **结构性** | 符号个数不同，或者 kind/name/line/parent 有差异，或者一处熬过归一化的细节差异——失败计数 | 0 |

   `FoldDiff` 那条规则是**在被写进去之前先被测量过的**，而那次测量被留着：
   `tools\harness\src\FoldShapeProbe.java`。在 126 个文件的语料上，它发现 64 个完全相同、
   38 个两个方向都不同、24 个只在一个方向不同，而**0** 个文件出现任何区域不在另一份清单的
   跨度里。退出码跟着可判定的那一半走：只有内容/结构性才退出 1。

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
   | 3 | 37 份“撕裂”，以及底下的那个发现 | `java.nio.file.AccessDeniedException: probe-cache.txt.tmp -> probe-cache.txt`——**`saveCache` 里的一个真缺陷** |

   那个缺陷：在 Windows 上，当另一个句柄打开着目标文件时，把一个文件重命名到一个已存在的
   文件上会以 `AccessDeniedException` 失败，而 `saveCache` 只捕了
   `AtomicMoveNotSupportedException`。所以恰恰在 `synchronized` 为之而写的那个争用之下，
   写入是抛异常而不是落地。从来没有东西被撕裂过（老文件整个活了下来），但那个 map 里的条目
   **静默地不在磁盘上**。原子移动现在被重试，有界、带退避，而一个熬过重试的失败会作为它自己
   被重新抛出。修好之后，同样 8 个线程在同一个路径上：`320` 次保存里 `320` 次返回、`0` 次
   抛出、**`10,135` 次读取里 `0` 份被撕裂的快照**、`0` 份短快照，而最终那个文件持有每一次
   保存所写下的全部 `2,000` 个键。

   这是这个发布里唯一一个被同一个发布里写出来的验证器找到的缺陷。

**这个发布证明了什么** —— 下面每一个数字都来自这一版构建上一次
`harness.ps1 -Tool All` 通过，而原始日志留在
`idea-plugin\evidence\harness-0.1.5.txt`：

| 工具 | 覆盖率三元组 | 裁决 |
|---|---|---|
| `ast-diff.ps1` | `ran 114 / skipped 12 (compiler-refused 12, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS |
| `psi-tree-diff.ps1` | 见证据文件 | PASS, 126 of 126 |
| `GotoOracle` | 见证据文件 | 0 wrong |
| `HintDiff` / `HintNames` / `HintShapes` / `HintDupes` | 见证据文件 | 0 wrong |
| `SymbolDiff` / `FoldDiff` | 见证据文件 | 0 structural, 0 content |
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
