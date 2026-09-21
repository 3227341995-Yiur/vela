# 这个插件的表面，以及其中的陷阱

[English](PLUGIN_SURFACE.md) | **简体中文**

<!--
源文件 : PLUGIN_SURFACE.md
源文件字节 : 10254
源文件 SHA256 : 4f0de26f246fdea3cd1c4b62e70a6f665542826fae7a623f3fa32da77e91bfaf
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

下面每一行都是从已安装的平台里读出来的，不是凭记忆写的：某个具体 jar 的 `META-INF/*.xml` 里的 `<extensionPoint …>` 声明、平台真正去读的那个属性名，以及同一个扩展点在某个随 IDE 交付的插件里的一处真实注册。工具写在最后，因为“你读的是哪个 jar”是那个能了结每一场关于扩展点的争论的问题。

这个文件存在是因为这个插件里有三个彼此独立的功能做完之后什么都没做，每一个的原因都是不同的一行，而平台对这些原因一个都不报告：一个未知的扩展点 id、一个挂在错误属性名下的类，以及一个根本没有被注册的类，全都是静默的。

## 今天注册了什么，连同平台去读的那个名字

| 标签 | 扩展点 | 平台去读的属性 | 备注 |
|---|---|---|---|
| `<fileType>` | `com.intellij.fileType` | `implementationClass`、`fieldName="INSTANCE"`、`extensions="vel;vela"` | 两个后缀都要有，否则插件在一个真实的 `.vel` 文件上什么都不做 |
| `<lang.parserDefinition>` | `com.intellij.lang.parserDefinition` | `implementationClass` | `lang.` 前缀要紧：裸的 `<parserDefinition>` 命名一个没有任何描述符声明的 id |
| `<lang.syntaxHighlighterFactory>` | `com.intellij.lang.syntaxHighlighterFactory` | `implementationClass` | |
| `<annotator>` | `com.intellij.annotator` | `language` + `implementationClass`（beanClass `LanguageExtensionPoint`） | 按*含义*做语义着色 |
| `<externalAnnotator>` | `com.intellij.externalAnnotator` | `implementationClass` | 编译器的诊断 |
| `<lang.psiStructureViewFactory>` | `com.intellij.lang.psiStructureViewFactory` | `implementationClass` | 没有 `lang.structureViewBuilder` 这个东西 |
| `<lang.documentationProvider>` | `com.intellij.lang.documentationProvider` | `implementationClass` | |
| `<completion.contributor>` | `com.intellij.completion.contributor` | `implementationClass` | beanClass `CompletionContributorEP` |
| `<codeInsight.parameterInfo>` | `com.intellij.codeInsight.parameterInfo` | `implementationClass` | |
| `<gotoDeclarationHandler>` | `com.intellij.gotoDeclarationHandler` | **`implementation`**（`interface=`） | `intellij.platform.analysis.jar!META-INF/Analysis.xml` |
| `<psi.referenceContributor>` | `com.intellij.psi.referenceContributor` | **`implementation`** | 见下面的陷阱——这一条曾经被写错过一次 |
| `<lang.elementManipulator>` | `com.intellij.lang.elementManipulator` | `forClass` + `implementationClass` | 重命名的回写 |
| `<codeInsight.inlayProvider>` | `com.intellij.codeInsight.inlayProvider` | `language` + `implementationClass` + `id`（+ `isEnabledByDefault`） | 见下面的陷阱 |
| `<configurationType>` | `com.intellij.configurationType` | **`implementation`**（`interface=`） | |
| `<runConfigurationProducer>` | `com.intellij.runConfigurationProducer` | **`implementation`**（`interface=`） | 它让 IDEA 自己的 Run 菜单提供一个 `.vel` 文件 |
| `<toolWindow>` | `com.intellij.toolWindow` | `factoryClass` | |
| `<applicationConfigurable>` | `com.intellij.applicationConfigurable` | `instance` | |
| `<action id="Vela.NewFile">` | `com.intellij.action` | `class`，在 `<add-to-group group-id="NewGroup"/>` 里面 | New 菜单 |
| `<lang.formatter>` | `com.intellij.lang.formatter` | `language` + `implementationClass` | beanClass 形式。`FormattingModelBuilder` **没有抽象成员**，而它的五个 `createModel` 重载互相成环调用，所以覆写点是 `createModel(FormattingContext)` |
| `<codeStyleSettingsProvider>` | `com.intellij.codeStyleSettingsProvider` | **`implementation`**（`interface=`） | 没有 `language` 属性 |
| `<langCodeStyleSettingsProvider>` | `com.intellij.langCodeStyleSettingsProvider` | **`implementation`**（`interface=`） | 一门语言的缩进大小在这里变得可见 |
| `<lang.foldingBuilder>` | `com.intellij.lang.foldingBuilder` | `language` + `implementationClass` | beanClass 形式；`FoldingBuilderEx` 增加了一个具体的 `getPlaceholderText(ASTNode, TextRange)`，它委托给那个抽象方法 |
| `<lang.commenter>` | `com.intellij.lang.commenter` | `language` + `implementationClass` | `Commenter` 在 262 里**没有常量**——没有 `END_OF_LINE_COMMENT` 字段，所以 `"#"` 是一个字面量 |
| `<lang.braceMatcher>` | `com.intellij.lang.braceMatcher` | `language` + `implementationClass` | 只有 `{ }` `( )` `[ ]`。`<`/`>` **不能**配对：词法分析器给每一个运算符同一个 `OPERATOR` 类型，所以一对会把 `+` 和 `<` 配起来 |
| `<typedHandler>` | `com.intellij.typedHandler` | **`implementation`**，并且**没有 `language`** | 接口形式，而且*不是*按语言键控的：类必须自己测文件名 |
| `<enterHandlerDelegate>` | `com.intellij.enterHandlerDelegate` | **`implementation`**，没有 `language`，`order="first"` | 同样不是按语言键控的。`Result` 的常量在 262 里拼作 `Continue`/`Default…`，而更早的发布用 `CONTINUE`——代码按名字查找它们，而不是挑一种拼法 |
| `<colorSettingsPage>` | `com.intellij.colorSettingsPage` | **`implementation`** | 这一行曾经存在过一段时间，而它命名的类并不存在，这一点被验证器的类存在性那一层抓到 |
| `<defaultLiveTemplates>` | `com.intellij.defaultLiveTemplates` | `file` | 根本不需要类；路径对着插件自己的资源解析 |
| `<liveTemplateContext>` | `com.intellij.liveTemplateContext` | **`implementation`** | **一个 `beanClass` 的扩展点，却收 `implementation`**——这里写 `implementationClass` 会是第四个静默空操作 |
| `<lang.findUsagesProvider>` | `com.intellij.lang.findUsagesProvider` | `language` + `implementationClass` | `getWordsScanner()` 是**非抽象的**，默认返回 null，而 null 意味着平台什么都不搜，所以一个“能用”却什么都找不到的查找用法，看起来像一个能用的功能 |

## 那些陷阱，每一个都付了代价

1. **`implementation` 与 `implementationClass` 是逐扩展点的，不是全局的。**
   `psi.referenceContributor` 的 bean 是 `PsiReferenceContributorEP`，它的*字段*名叫
   `implementationClass`，却被标注为 `@Attribute("implementation")`——所以 XML 里的名字是
   `implementation`。写成另一种，这个注册就被忽略：`registerReferenceProviders` 永远不
   运行，重命名和查找用法从零开始，而 Ctrl+Click 照旧可用，因为那是另一个扩展点。
   它就是这样一个活过了一次评审和这个项目自己的验证器的东西。

2. **两个看起来很合理的 id 并不存在**：`codeInsight.inlayHintsProvider`（真的那个是
   `codeInsight.inlayProvider`）和 `lang.indentOptions`。对着一个未被声明的 id 做注册是
   一个平台不报告的空操作。

3. **`InlayHintsProvider` 没有 `isEnabledByDefault` 成员。** 开关是注册上的
   `isEnabledByDefault` 属性；`id` 是用户的开关被存进去所用的那个键，并且是必需的。

4. **有些接口一个抽象成员都没有**，而默认实现可以结成一个环。`FormattingModelBuilder`
   的五个 `createModel` 重载全都是默认方法，并且互相循环调用——一个什么都不覆写的实现
   会一直递归到栈死。覆写点是 `createModel(FormattingContext)`，那也是平台自己的 JSON
   格式化器所覆写的。`TypedHandlerDelegate` 和 `EnterHandlerDelegate` 是同样的形状
   （只有默认方法）。

5. **`Block` 的形状变了**：九个抽象成员、用 `getChildAttributes(int)` 而不是
   `getChildIndent()`、没有 `getParent()`，而且已安装的 jar 里哪儿都没有 `AbstractBlock`
   这个类。可用的接口是 `com.intellij.formatting.ASTBlock`。

6. **Vela 的词法分析器让一些功能按原样就是不可能的**：每一个运算符都是同一个共享的
   `VelaTokenTypes.OPERATOR`，所以一个 `BracePair` 会把 `+` 和 `<` 配起来。`<`/`>`
   绝不能注册成一对，而对 `()`/`[]`/`{}`，配对的两侧类型必然相同，因为词法分析器对
   每一种分隔符只用一个类型。

7. **`todoIndexer` 收的是 `filetype=`，不是 `language=`**——按语言键控的那一族里其他
   所有东西收的都是 `language`。写错就意味着 TODO 静默地永远不出现。

## 不适用于 Vela，而且硬填进去会更糟

自动导入、模块/包解析和库/依赖解析（没有模块，没有包，一个文件就是一个程序）；转到类和类型层次（没有类）；跨文件调用层次（没有跨文件调用）；测试框架集成（没有框架——那套套件是一个 Vela 程序）；剖析器集成；外部文档（没有文档站点，而且那个扩展点在已安装的 jar 里根本解析不了）。

## 这里没有验证的东西

- `BreadcrumbsProvider`、`PostfixTemplateProvider`、`SpellcheckingStrategy`、
  `DataIndexer`、`IndexPatternBuilder`、`XBreakpointType` 以及提供调用层次的那个东西的
  成员清单——一个 agent 的 shell 在把它们转储出来之前就死了，所以它们被列为未知，
  而不是被猜出来。
- 我们实现的那些类是否带 `@ApiStatus.Internal`。扩展点*声明*在全部 429 个 jar 里
  扫过一遍，**没有一条**带 `internal` 属性，但那是与类不同的另一个问题。
- `since-build="253"`：与格式化器相关的类在 262 和 PyCharm 2025.3（253）两条 classpath
  下都转储过，成员集合相同，但自从最后那些注册加进来之后，整个描述符还没有对着 253
  重新链接过。

## 怎么自己检查一行

这台机器上没有 `javap`。验证器自己的工具在 `build\tools\src\` 里，并被编译进 `build\tools\classes`：`ClassSig <classpath-file> <FQCN>` 打印声明出来的成员，`MethodFlags` 打印 class 文件标志，包括哪些成员是抽象的，而一个 classpath 文件就是每行一个 jar 路径（`build\probe\*.args` 是例子）。至于扩展点，去读平台 `lib\` 目录下每个 jar 里的 `META-INF/*.xml`——上面每一个 id、`interface=` 和 `<with attribute=… implements=…>` 都是从那里来的。
