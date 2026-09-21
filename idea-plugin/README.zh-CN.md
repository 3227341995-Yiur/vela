# 面向 IntelliJ IDEA 的 Vela

[English](README.md) | **简体中文**

<!--
源文件 : README.md
源文件字节 : 20170
源文件 SHA256 : 1cbf1fe71de71716d974e274c54d488fab6c860fda6753222822fc591a8ca05b
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

一个给 `.vel` 文件（以及 `.vela`，同一个名字的另一种写法）用的 IDEA 插件。它有意做得薄：**只有编译器决定一个 Vela 程序是什么意思**，而这个插件去问编译器。

| 什么 | 怎么做 |
|---|---|
| `.vel` / `.vela` 文件类型 + 编辑器 | `VelaFileType`，它的 `extensions="vel;vela"` 是承重的：第一个发布只声明了 `.vela`，因此在一个真实的 Vela 文件上什么都没做 |
| 语法高亮 | `VelaLexer` + `VelaSyntaxHighlighter`，颜色取自平台自己的键，所以它会跟随用户的配色方案 |
| 语义着色 | `VelaSemanticAnnotator` 按一个*名字*的含义给它上色，取自 `VelaModel` |
| **编辑器里的错误和警告** | `VelaExternalAnnotator` 运行 `vm.exe check <file>`，并原样画出它说的东西，画在它说的那一行 |
| 补全、悬停、参数信息 | `VelaCompletionContributor`、`VelaDocumentationProvider`、`VelaParameterInfoHandler`——全都读同一个模型 |
| 跳转声明、重命名、查找用法 | `VelaGotoDeclarationHandler`、`VelaReferenceContributor`、`VelaLeafManipulator`、`VelaFindUsagesProvider` |
| 内联提示 | `VelaParameterNameInlayHintsProvider`——被调者声明的参数名，画在实参旁边 |
| 结构视图、AST 窗口 | `VelaPsiStructureViewFactory`，以及“Vela AST”工具窗口 |
| 格式化、折叠、注释、括号、输入 | `VelaFormattingModelBuilder`、`VelaFoldingBuilder`、`VelaCommenter`、`VelaPairedBraceMatcher`、`VelaTypedHandlerDelegate`；缩进大小在 代码样式 → Vela 下 |
| 配色方案页面、实时模板 | `VelaColorsAndFontsPage`（那 14 个词法键），模板 `defn` / `main` / `struct` / `forr` / `whilee` / `ifel` / `parfor` |
| **运行一个文件** | IDEA 自己的 Run 菜单、装订区箭头和重跑历史，经由 `VelaRunConfigurationProducer`——而 Run **解释执行**：`vm.exe run <file>`。没有构建步骤，程序旁边不写可执行文件，不需要 C 工具链，输出直接进控制台。这是刻意的：一个先编译的 Run 按钮很慢，会在源码旁边留下一个产物，并且继承第一版死于其中的那一类 include 路径问题（`fatal error C1083: cannot open include file: 'vela_runtime.h'`，来自一个不是仓库根目录的项目目录）。不再有私有的“Vela”子菜单，也不再有“Check”动作：那个子菜单比 IDEA 自己的 Run 更差地重复了它，而 Check 重复了实时诊断，而实时诊断是编译器自己更早说出的话 |
| debug | **刻意拒绝。** 编译器的源码里有一个 `debug` 模式，而没有一个已提升的二进制能回答它，所以运行配置抑制 Debug 动作，而不是提供一个会挂接到空处的按钮 |
| 编译器在哪里 | 设置 → 语言与框架 → Vela，否则 `$VELA_VM`，否则往上最多六层的任何目录，否则 `PATH` |

截至 **0.1.3**，上表里的每一件东西都**编译过并验证过**：全部 32 个 Kotlin 源文件在离线路径下构建，产物是 `dist\vela-idea-plugin-0.1.3.zip`，而 `build-offline.ps1` 以 `RESULT: PASS` 结束——每一项结构性、字节码、平台、注册、链接和行为检查都是绿的（`build\logs\verify.log` 里有那份清单，`build\logs\surface.txt` 是机器写出的表面）。从来没能编译过的是 0.1.2；它的第一次编译在两个文件里找出四个错误，列在 `CHANGELOG.md` 里。仍然未验证的是 **IDE 本身**：这些检查读描述符、字节码和 class 文件，而编辑器里的行为问题由安装那个 zip 来定，不是从这里定。每一个注册都对照平台自己的声明验证过——`PLUGIN_SURFACE.md` 记录了每一个注册是从哪条声明读出来的——因为这个插件短暂的一生里已经有三个功能发布出去时是完成的、却完全无作用的，其中一次是因为一个属性名写错。

## 为什么这些标注是真的东西

编译器用两行写出它的拒绝，而这个插件读那两行，别的什么都不读：

```
vela: safety error: 'parallel for' writes to 'a', which is not a mutable array local to this function
  at /home/me/sum.vela:7
```

所以没有第二个检查器会漂移过时，也没有对英文做模式匹配：`kind` 是一个字段，消息就是消息。Vela 在构建时强制的每一条规则——包括 `parallel for` 的竞态证明和“比 Python 更严格”的那些拒绝——在你打字的时候就是一条波浪线，不花钱。

## 构建与安装

### 在这台机器上：离线，没有 Gradle，没有网络

```powershell
cd idea-plugin
.\build-offline.ps1
```

`gradlew buildPlugin` 在这里跑不起来：`PATH` 上没有 JDK、没有 Gradle、也没有 `~/.gradle` 缓存，而 IntelliJ Platform Gradle 插件想下载 Gradle 和另一份 IDE。这台机器*确实*有的是一个已安装的 IDE，而一个 IDE 带着为它自己构建插件所需要的每一样东西：

| 什么 | 在哪里 |
|---|---|
| 用来编译的平台 | `D:\JetBrains\IntelliJ IDEA 2026.2.1`（build IU-262.9437.185） |
| JDK（JetBrains Runtime） | `<ide>\jbr`——包含 `javac` 和 `jar`（OpenJDK 25.0.3） |
| Kotlin 编译器 | `<ide>\plugins\Kotlin\kotlinc\lib\kotlin-compiler.jar`（2.3.20） |

`build-offline.ps1` 对着那个平台自己的 429 个 jar 编译 Kotlin 源码，把结果打包，包进 IDE 期望的那个 ZIP 里，然后*验证*这个产物——60 项检查，每一项打印 `OK` 或 `FAIL`，以 `RESULT: PASS` / `RESULT: FAIL` 结束，并在任何一项失败时以非零码退出：

| 层次 | 它证明什么 |
|---|---|
| 1 结构 | `META-INF/plugin.xml`、两个图标，以及 jar 里为 `plugin.xml` 中*任何*携带类的属性所命名的每一个 FQN 都有一个 `.class`（`implementationClass`、`instance`、`factoryClass`、`class`、`implementation`、`serviceImplementation`） |
| 2 字节码 | 148 个 class 文件，最高的 class-file 主版本 65（Java 21）——在 2026-09-22 00:26 的 0.1.4 构建上实测 |
| 3 平台 | 已安装 IDE 自己的描述符被读取并建索引——2356 个 jar 里的 2705 个 XML 描述符，给出 297 个插件/模块 id、1773 个扩展点 id 和 5277 个动作/组 id（0.1.4 构建） |
| 4 扩展 | 每一个 `<extensions>` 条目都命名一个已安装平台声明的扩展点，或者另一个已安装插件在其下注册了扩展的扩展点 |
| 5 depends | 每一个 `<depends>` 模块都是已安装平台提供的 |
| 6 文件类型 | `<fileType extensions="vel;vela">`、`VelaFileType.getDefaultExtension() = vel`、`EXTENSIONS = [vel, vela]`、`isVelaFileName("x.vel")` / `("x.vela")`；以及每一个 `language="..."` 属性都等于这个文件类型所绑定到的语言的 id |
| 7 动作 | 每一个 `<add-to-group group-id="X">` 都解析到一个**组**——`plugin.xml` 里声明的，或者已安装平台定义的；一个只有 `<action>` 的 id 在这里失败，而那正是 0.1.0 发布出去的东西。这个插件现在注册**一个动作**（`Vela.NewFile` -> `NewGroup`），而且**一个组都没有**：`Vela` 子菜单和它的两个动作被删掉了，而运行一个 Vela 文件改由 `runConfigurationProducer` 提供，所以 Run 菜单、装订区箭头、Run 窗口和 Debug 都由平台提供 |
| 8 链接 | 每一个被命名的类都能从 jar 里对着已安装平台加载出来，并实现它的扩展点所要求的类型——那个要求取自平台自己的 `<with attribute=... implements=...>` 声明 |
| 9 行为 | 词法分析器在真实 Vela 文本上的表现、诊断读取器在编译器两行格式上的表现，以及用户真正看到的那条路径，端到端：一个真实的 `tests/build/*.vel` 喂给 `vm.exe check`，它的 stderr 按 `VelaAnnotator` 读它的方式读 |

```
    OK  VelaLexer produced 54 tokens; first 16:
        VELA_COMMENT[0,9) WHITE_SPACE[9,10) VELA_KEYWORD[10,13) ...
    OK  arith_basics.vel (accepted)      -> 0 problem(s), which is what the editor would draw
    OK  truthiness.vel (refused)         -> 1 problem on line 3, kind "type error"
    OK  same refusal, 4 lines lower      -> problem moves to line 7 (line numbers are read, not guessed)
    OK  VelaCompiler.run+parse           -> exit 2, same 1 problem (the annotator's own call path)
```

两条路线，被指名而不是被混为一谈：结构、字节码和链接由加载构建出来的 jar 来证明；扩展点、模块 id 和动作组 id 由已安装 IDE 自己的描述符来证明——那是一次*描述符扫描*，不是一个活的 `ActionManager`，因为平台的 `ActionManager` 和扩展注册表需要一个启动起来的应用，无法无头地被问。因此只在代码里注册的一个组 id 会被报为未知。

**当前判定，0.1.4——是重新测量的，不是照搬下来的。** 下面每一个数字都是从 2026-09-22 00:26 那次构建里读回来的，并且带着它所属的版本被点名，因为这个块曾经连续三个发布都写着 `0.1.1` 和 `60 OK`，而 `dist\` 里放着的是 `0.1.3`——这正是那种读者有资格为之生气的过期行：

```
build-offline.ps1            exit 0, RESULT: PASS   (build\logs\verify.log)
artifact                     dist\vela\lib\vela-idea-plugin.jar   344 619 bytes
                             dist\vela-idea-plugin-0.1.4.zip      324 186 bytes
                             sha256 a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033
sources                      36 Kotlin file(s) -> 148 class file(s), highest major 65 (Java 21)
compiler warnings            none ("no warnings, no errors")
dead classes                 0 (98 concrete top-level classes: 29 named by plugin.xml, 69 reached)
surface inventory            build\logs\surface.txt (36 fact(s))
```

`0.1.1` 的记录，用于历史而不是用于当前声称：60 OK、jar 180 613 字节、`dist\vela-idea-plugin-0.1.1.zip` 169 056 字节、19 个 Kotlin 源文件。那个 `0.1.1` 的 zip 仍然在 `dist\` 里，这就是为什么版本必须在这个块里被点名，而不是从目录里推断。

验证器读的那份清单——每一个扩展点 id 连同它命名的类、那个动作、`add-to-group` 的目标、文件类型的后缀和它的默认扩展名——被写到 `build\logs\surface.txt`，每行一条排好序的事实。一份摘要可以引用它而不是誊抄它，而对 `plugin.xml` 的下一次改动会与上一次验证过的运行做 diff。

**这些检查是干什么的**：这个文件里的四个注册到现在已经以同一种方式错过三次——一个平台没有的 id，它让插件加载起来然后*什么都不做*，而从内部没有任何人能看到错误：

| 那个 bug | 它是怎么被抓到的 |
|---|---|
| `extensions="vela"` 没有 `vel`（0.1.0）：插件在一个真实的 `.vel` 文件上什么都没做 | 被变异测试 A 抓到的（`build\tools\negative-tests.ps1`） |
| `add-to-group group-id="Vela.BuildAndRun"`（0.1.0）：一个 `<action id>` 被当成组用，`SEVERE ... should be instance of DefaultActionGroup`，两个动作都没进任何菜单 | 被变异测试 B 抓到 |
| `<parserDefinition ...>`：id `com.intellij.parserDefinition`，没有任何东西声明它；PSI 树从来不存在 | 在第一次真实运行时被抓到，修成 `<lang.parserDefinition>` |
| `<lang.structureViewBuilder language="Vela" .../>`：id `com.intellij.lang.structureViewBuilder`，在 1987 个 jar 里一个都没有 | 在第一次真实运行时被抓到，修成 `<lang.psiStructureViewFactory>` |

两个 0.1.0 的 bug 是*被证明*抓到了，而不是被断言：`build\tools\negative-tests.ps1` 把每个 bug 重新引入（再加上一个缺失的类、一个未知的组 id、一个写错的 `language=`、那个裸的 `<parserDefinition>`，以及只带 `.vela` 的 extensions）重新构建 jar，并要求验证器在每一个上都失败。上次运行：**6/6 抓到，0 漏掉**（`build\logs\negative-tests.log`）。它不是构建的一部分，因为构建应该保持快；改完验证器之后再跑它。

还有一个缺口是被补上的，而不是被发现的：之前的验证器只看 `<action class=...>`、`implementationClass` 和 `instance`，所以 `<configurationType implementation="..."/>` 和 `<toolWindow factoryClass="..."/>` 命名的类从来没有被任何东西加载过。现在每一个携带类的属性都会被检查，而且每一个类还必须*实现*它的扩展点所要求的类型。

### `since-build="253"` 这个声称，实测

`plugin.xml` 写着 `since-build="253"`，这声称插件用到的每一个平台类和成员在 PyCharm 2025.3 里也存在，和 IntelliJ IDEA 2026.2 里一样。两半现在都检查过，因为 PyCharm 2025.3 *是*装在这台机器上的（`D:\JetBrains\PyCharm 2025.3`，build `PY-253.28294.336`，139 个平台 jar）：

```
compile against PyCharm 2025.3 (253),  139 jars  -> rc=0, no errors
compile against IntelliJ IDEA 2026.2 (262), 429 jars -> rc=0, no errors, no warnings

build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"      (0.1.4, re-measured)
    RESULT: PASS, exit 0              (build\logs\verify-253.log)
    139 jars on the compile classpath; verified against 253's own descriptors:
    1656 descriptors out of 1249 jars, 246 plugin/module ids, 1259 extension points,
    4879 action/group ids, 1079 required contract types
    jar 344 574 bytes, zip 324 147 bytes
    sha256 of that zip: b43c480b71e59843b85002e183cc1aa04f38b4eeb03d8926ef9d5bccda206fc2

build-offline.ps1                      (IntelliJ 2026.2 -- the artifact in dist\)
    RESULT: PASS, exit 0              (build\logs\verify.log)
    429 jars on the compile classpath; 2705 descriptors out of 2356 jars,
    297 plugin/module ids, 1773 extension points, 5277 action/group ids
    jar 344 619 bytes, zip 324 186 bytes
    sha256 of that zip: a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033
```

**这个块在 0.1.4 之前一直只是一个承诺，而守住所发现的，是一个真实的缺陷。** 上面那两条命令现在真的对着最终形状重跑过了，而第一次重跑**失败了**：PyCharm 2025.3 用 `VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'`——`ProcessOutputType.isSystem(Key)` 存在于 262 而不存在于 253，所以那些源码无法对着 `plugin.xml` 声称支持的那个平台编译，而 `since-build="253"` 是**假的**——拒绝了那批源码。那一行现在用一次同一性比较（`outputType === ProcessOutputType.SYSTEM`）问同一个问题，它存在于两个平台上，并且不会像 `ProcessOutputType.fromKey` 那样抛异常。两边的构建都在上面通过了，来自这同一棵树。两个 jar 相差 45 字节（编译器元数据），这就是为什么两个大小都被引用，而不是用一个代表两者。

这**不能**证明什么：在两个 IDE 里的运行时行为。对着两个平台编译和链接说明插件命名的每一个类和成员在两者里都存在；它对那些 API 在 IDE 跑起来之后干什么什么都不说。那个缺口就是下面“离线检查不覆盖什么”里点名的那个。（上面两个块都是对着 0.1.4 的最终形状重新测量过的；它们下面那段注记，就是 `isSystem` 缺陷被发现的地方。）


用 **设置 → 插件 → ⚙ → 从磁盘安装插件…** 安装，选 `dist\vela-idea-plugin-0.1.4.zip`（或者把 IDE 指向解包后的 `dist\vela\`）。`plugin.xml` 命名的每一个类都从构建出来的 jar 里加载出来，并对着它将运行于其中的平台做链接——否则那个失败只表现为“插件没有加载”，对原因没有任何解释。

### 其他地方：Gradle

`build.gradle.kts` 保留着，在任何 Gradle 能触到网络的地方都能用：`./gradlew buildPlugin` 在 `build/distributions/` 下产出同一个插件。`intellijIdeaCommunity(...)` 以 `sinceBuild = 242` 面向 IntelliJ IDEA Community 2024.2.4；目标 IDE 一动，两个一起改。这里没有验证过——这台机器上没有任何东西能跑它。

### 把它指向编译器

**设置 → 语言与框架 → Vela → 编译器**，例如 `C:\Users\lu\Downloads\vela\selfhost\build\vm.exe`。留空的话，插件会看 `$VELA_VM`，然后看 `<project>/selfhost/build/vm.exe`，然后看 `PATH`。

构建编译器本身不需要 Python，也不需要 Gradle——它就是一个 PowerShell 脚本，从一个签入的 C 文件出发：

```powershell
powershell -File ..\tools\build.ps1 -Suites
```

## 它还不能做什么

被指名而不是被忘掉——每一个都是真实的功能，每一个都有一个理由：

* **没有跳转定义，没有重命名。** 两者都需要一个 PSI，也就是*插件里的*一个解析器，或者*来自编译器的*一份结构化转储。正确的答案是第二个——`vm.exe parse` 已经在遍历那棵树了，而它的一个 JSON 形状的版本同时是一个插件功能和一个编译器功能——它是这里下一件要做的事。补全、悬停、参数信息和结构视图改由*词法分析器*来回答（`VelaModel.kt`），这就是为什么它们是名字层面的，并且对此诚实：它们显示一个名字写在哪里，不显示它是什么意思。
* **没有格式化器**，也没有平台自己推导之外任何额外的折叠。（以前随那个被删掉的 web IDE 一起发布的格式化器跟它一起走了；格式化器属于这里，不属于一个工具链脚本。）
* 标注是**整行的**，不是精确到 token 下的一个插入符；编译器报告一行，而编造一列就是在编造数据。
* 没有调试器。`extern c`（第 5 阶段）现在对标量参数是真的了，所以一个 C 库可以被*调用*——但没有调试器可以进到它里面。

### 离线检查不覆盖什么

直白地说，因为一个未验证的声称比一个已知的缺口更糟：这些检查加载的是 jar，不是 IDE。它们**不**启动一个 IntelliJ 应用，所以它们看不到一个菜单项是否真的出现、一条波浪线是否真的画在编译器说的那一行上、工具窗口是否打开、或者补全是否在插入符下弹出。动作组和扩展点 id 是从已安装 IDE 自己的描述符解析的，而不是从一个活的 `ActionManager`，而只在代码里注册的一个组会被报为未知。唯一*是*端到端被证明的路径是诊断：一个真实的 `tests/build/*.vel`、真实的 `vm.exe check`，以及插件自己的读取器读它的 stderr。这张表里其他所有东西都是关于产物的陈述、关于平台 API 表面的陈述，以及关于插件自己函数的陈述。

## 布局

```
build-offline.ps1, build\tools\src\*.java                  离线构建 + VerifyPlugin
build.gradle.kts, settings.gradle.kts, gradle.properties   Gradle 构建（在这里跑不了）
src/main/resources/META-INF/plugin.xml                     每一个扩展点
src/main/kotlin/dev/vela/plugin/VelaLanguage.kt            语言、文件类型、词法分析器、token 类型
src/main/kotlin/dev/vela/plugin/VelaHighlighting.kt        颜色
src/main/kotlin/dev/vela/plugin/VelaCompiler.kt            找到编译器、运行它、读它
src/main/kotlin/dev/vela/plugin/VelaDiagnostics.kt         标注器画出的那些问题
src/main/kotlin/dev/vela/plugin/VelaAnnotator.kt           外部标注器 + 设置页
src/main/kotlin/dev/vela/plugin/VelaActions.kt             Check、Build and Run
src/main/kotlin/dev/vela/plugin/VelaModel.kt               名字，用词法分析器读
src/main/kotlin/dev/vela/plugin/VelaNames.kt               某个偏移上的一个名字可能是什么意思
src/main/kotlin/dev/vela/plugin/VelaParserDefinition.kt    一个平铺的 token PSI：没有文法，没有第二意见
src/main/kotlin/dev/vela/plugin/VelaCompletion.kt          补全
src/main/kotlin/dev/vela/plugin/VelaDocumentation.kt       悬停
src/main/kotlin/dev/vela/plugin/VelaParameterInfo.kt       参数提示
src/main/kotlin/dev/vela/plugin/VelaStructureView.kt       结构视图
src/main/kotlin/dev/vela/plugin/VelaAstToolWindow.kt       语法树工具窗口
src/main/kotlin/dev/vela/plugin/VelaRunConfig.kt           运行配置类型
src/main/kotlin/dev/vela/plugin/VelaNewFile.kt             New Vela File
```

这里的词法分析器是*高亮器的*词法分析器，按与 `selfhost/vela.vel` 相同的规则写（同样的关键字、同样的运算符、同样的转义）。如果两者有分歧，仍然是编译器说了算——被问的是它。
