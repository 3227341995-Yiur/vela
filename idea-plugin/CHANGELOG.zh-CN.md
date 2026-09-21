# Vela IDEA 插件 —— 变更日志

[English](CHANGELOG.md) | **简体中文**

<!--
源文件 : CHANGELOG.md
源文件字节 : 17700
源文件 SHA256 : 3d16f921eec59c5f4db7aabfb97dc4c77369b62fe7c0f0c77740a503f3f1a91b
翻译日期 : 2026-09-22
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
  `dist\vela\lib\vela-idea-plugin.jar` **344,626 字节**（sha256
  `0a36128e032a1b4fbd67e82d9d2f3fffe827d5e8905db762ed0072e152f0896e`）和
  `dist\vela-idea-plugin-0.1.4.zip` **324,198 字节**（sha256
  `55bf14a3b68e5ccc3486bf9a6ec669fd2ce1dc48c859881704616128cb1409f7`，一个条目：
  `vela/lib/vela-idea-plugin.jar`）。36 个 Kotlin 源文件、145 个 class 文件，
  最高的字节码主版本 65（Java 21）。
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

**这个版本里没有被验证的东西**

- 没有启动任何 IDE。这个会话里在这台机器上启动不了任何 IntelliJ 实例，所以这里的
  *任何东西*都不是插件能加载、工具窗口会出现、菜单项在它该在的地方、或者这些功能
  里有哪一个在屏幕上是正确的证据。能拿到的最强证据是平台注册的读回加上无头差分，
  而它在 `FEATURE_PARITY.md` 里被标为如此。
- `Live parse (editor buffer)` 模式编译过了、可达；它**没有**在 IDE 里跑过。被验证的
  是它的解析就是编译器的解析（那是 `ast-diff.ps1`），不是 Swing 面板把它渲染出来。
- `classRegisteredNowhere` 现在只在一个类实现某个扩展点所要求的契约时被抓到；
  精确的剩余限度见 `FEATURE_PARITY.md` 的验证器那一节。



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
