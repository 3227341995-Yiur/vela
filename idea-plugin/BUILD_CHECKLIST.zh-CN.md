# 编译闸门——它发现了什么，以及下次该看哪里

[English](BUILD_CHECKLIST.md) | **简体中文**

<!--
源文件 : BUILD_CHECKLIST.md
源文件字节 : 10544
源文件 SHA256 : 021ba3c5ad7f042749f968d02d7a77d050fb4454647f4649c33f653e56fee53e
翻译日期 : 2026-09-22
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过期，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

写在“这台机器上的命令运行器卡死”的那段时间里，然后**跑过了**。这个闸门不再是一个指望：§0 记录了第一次真实编译发现了什么。§0 以下的一切仍然保留着，作为平台变化时要看的地方的清单，因为下一次 IDE 升级时同一类错误会回来。

## 0. 第一次编译的结果（0.1.3，平台 IU-262.9437.185，kotlinc 2.3.20）

闸门按写的那样跑了——整个源文件集、整个输出、什么都没过滤——而它花了**三**趟才到绿：

| 趟 | 它说了什么 | 它是什么 |
|---|---|---|
| 1 | `VelaFormatter.kt:95/137/158/172` | `ASTBlock.getSpacing` 在这个平台上把 `Block?` 当第一个参数，不是 `Block`。两个类各被报了两遍：“不实现抽象成员”*和*“什么都没覆写”。§2 第 1 项预言的正是这种形状的失败，而且它是对的 |
| 2 | `VelaFindUsages.kt:56/65` | `private val wordsScanner` 生成了一个 `getWordsScanner()` 访问器，在 JVM 层面与这个类所覆写的接口方法冲突。改名为 `scannerOnce` |
| 3 | — | `RESULT: PASS`，所有检查为绿，`dist\vela-idea-plugin-0.1.3.zip` |

下面四条预言里有两条是对的，一个文件的问题是另外一个完全不同的问题（那个 JVM 签名冲突——与可空性没有关系），而其余的从来没被触到，因为编译在验证器能发问之前就停了。这就是一份没有编译器时写下的清单的诚实得分。

**那些计数，连同作用范围，因为它们在有人去核对之前读起来像矛盾。** 本文件讲的是**一个批次**：编辑器机制那项工作，八项由单个 agent 写成 10 个 Kotlin 源文件、一个资源文件和十一个注册。同一个停摆期间其他 agent 写了别的插件文件——跳转声明、语义着色、参数名内联提示、查找用法，以及运行路径的重构——那些由 `CHANGELOG.md` 计数。所以这里的“八个功能，十个文件”和那里的“大约十三个功能”都对，是不加范围的版本看起来像错的。当时的诚实状态是“源码存在，功能未证明”；到 0.1.3，编译和整个验证器都是绿的，而仍然未证明的是在一个**运行中的** IDE 里的行为。

`PLUGIN_SURFACE.md` 装着那张已验证的扩展点表；本文件装着这个闸门、它发现了什么，以及它从这里不能了结什么。

## 1. 怎么跑这个闸门

现在**确实**有一次已知可用的编译了——§0 描述的那次——所以照同样的方式重跑它：整个源文件集，整个输出不加过滤地贴出来。那次首跑的有一个性质值得抄下来：编译器在第一个有错的文件处就停了，所以一份来自*过滤过的*日志的绿报告，证明的东西比它看起来要少。然后再对着 `D:\JetBrains\PyCharm 2025.3`（253）编译一遍，因为描述符声称 `since-build="253"`，而只有 0.1.1 那个源文件集走过了那次编译。

## 2. 最可能出错的地方，按我会去看的顺序

1. **被假定而不是被读过的可空性标注**——`Block` 的
   `getIndent`/`getSpacing`/`getWrap`/`getAlignment`、`ChildAttributes(…, null)`、
   `isPairedBracesAllowedBeforeType` 的第二个参数，以及
   `AttributesDescriptor(String, TextAttributeKey)` 的 String 重载（可能已弃用，而警告是不可接受的）。这些是标准插件的惯用法，不是转储，而 Kotlin 把一个可空性不匹配变成错误。
2. **`CodeStyleSettings.getCommonSettings(Language)`**——它的存在是从 JSON 格式化器的字节码调用
   `getCommonSettings` 推断出来的，但它的重载从来没有被列出来过，所以 `Language`-对-`FileType` 这个选择是未验证的。一个调用点，`VelaFormatter.kt`。
3. **那个 `Result` 枚举的绕法。** `EnterHandlerDelegate$Result` 被读作
   `Default, Continue, DefaultForceIndent, DefaultSkipIndent, Stop`（PascalCase；
   更早的发布用 `CONTINUE`）。shell 在同样的探针能对 `TypedHandlerDelegate$Result`
   或 253 下的任一个枚举跑之前就死了——而这个源文件集是对着两个平台编译的，所以把任一种
   拼法硬编码进去都是一次抛硬币。因此这些值是**在类加载时按名字**查找的，优先
   `Continue`，退回第一个常量。它在两种拼法下都能编译；它是对一个未验证标识符的绕法，
   并在源码里被标为绕法。
4. **实时模板的 XML 模式。** `liveTemplates/Vela.xml` 是按标准形式写的
   （`<templateSet group>`、`<template name value description toReformat toShortenFQNames context>`、
   `<variable name expression defaultValue alwaysStopAt>`），从来没有与一个随 IDE
   交付的文件做过 diff。第一件要检查的事是 `context="VELA"` 相对更老的
   `<context><option name="VELA" value="true"/></context>` 子元素形式。

## 3. 要对照平台自己的声明验证的十一个注册

```
lang.formatter            implementationClass   language="Vela"
codeStyleSettingsProvider implementation
langCodeStyleSettingsProvider implementation
lang.foldingBuilder       implementationClass   language="Vela"
lang.commenter            implementationClass   language="Vela"
lang.braceMatcher         implementationClass   language="Vela"
typedHandler              implementation
enterHandlerDelegate      implementation        order="first"
colorSettingsPage         implementation
defaultLiveTemplates      file="liveTemplates/Vela.xml"
liveTemplateContext       implementation        contextId="VELA"
```
其中三个是人们会写错的形状，每一个都有一个记录在案的理由：`typedHandler`/`enterHandlerDelegate` 是用 `interface=` 声明的，所以它们收 **`implementation`**，并且**不是按语言键控的**（没有 `language` 属性——那些类自己测 `isVelaFileName`）；`liveTemplateContext` 被声明为 `beanClass="…LiveTemplateContextBean"` 并带 `<with attribute="implementation" …>`，所以那里写 `implementationClass` 会成为这个插件历史上第四个静默空操作。

## 4. 格式化器的验收测试，以及什么会证伪它

对着 `vela\.work\editor\` 下的一个**临时**插件目录跑——绝不要用 `dist\`：

1. 把源文件集编译到一个临时输出目录；
2. 组装 `classes + src\main\resources`，好让 `META-INF/plugin.xml` 和
   `liveTemplates/Vela.xml` 随它一起走；
3. 把 `tests\build\*.vel` 复制到一个临时语料；
4. 用 `D:\JetBrains\IntelliJ IDEA 2026.2.1\bin\format.bat -r -allowDefaults` 在它上面跑，
   并让 `IDEA_PROPERTIES` 把 config/system/log/plugins 指向那棵临时树
   （在停摆前已被证明能启动：它打印出自己的用法并以 0 退出）；
5. 再跑第二次并 diff——**幂等性**；
6. 用插件自己的 `VelaLexer` 在改前和改后各做一次词法分析，并要求 (type, text)
   序列完全相同——**token 保持**。

证伪项，按它们应当被相信的顺序：

| 观察到的东西 | 它意味着什么 |
|---|---|
| 语料在改前和改后逐字节相同 | `lang.formatter` 那个注册根本没有被走到——“注册了但无作用”这个失败，这个插件已经发过三次 |
| 引擎输出与手工施加的 `VelaFormatRules` 不同 | 对 `Spacing`/`Indent` 的读法是错的（这就是为什么那些规则是 token 序列的一个纯函数，并且*可以*手工施加） |
| 格式化两次 ≠ 格式化一次 | 有一条规则依赖顺序 |
| 有一个 token 变了 | 保持含义这个声称死了 |

## 5. 已知的、刻意的、绝不能被称为已完成之事的缺口

- **`customizeSettings` 没有被覆写**，所以代码样式页面的空格、换行和空行标签页是空的。
  填充它们意味着要命名 `CodeStyleSettingsCustomizable` 的选项常量，而那些常量从来没有被
  转储过——一个猜出来的常量就是一个编译错误，所以它被放着没做，而不是被猜。
  **未验证：同时注册 `codeStyleSettingsProvider` 和 `langCodeStyleSettingsProvider`
  是得到一个 Vela 页面还是两个**（JSON 插件两个都注册；CSS 插件只注册语言那一个）。
- **语义标注器的颜色不在配色方案页面上。** 那个页面列出 `VelaSyntaxHighlighter` 能返回的
  每一个键（14 行，每一行都命名高亮器返回的同一个 `TextAttributesKey` 对象，所以一行是
  一个真的设置，不是一个诱饵）。`VelaSemanticHighlighting` 按*含义*给名字上色，并且不
  声明自己的键——要显示那些，需要还不存在的键，而且那在别人拥有的一个文件里。
- **`<` 和 `>` 没有被注册成一对括号**，而且不能：插件的词法分析器给每一个运算符
  同一个类型 `VelaTokenTypes.OPERATOR`，所以一对会把 `+` 和 `<` 配起来。出于同样的理由，
  匹配器*能*注册的每一对都是左类型 == 右类型（`(`/`)` 都是 `PARENS`，`[`/`]` 都是
  `BRACKETS`，`{`/`}` 都是 `BRACES`），这把左右之分留给了平台的括号匹配层。
  **最后这个声称没有从字节码验证过。** 外观上的后果：插入符位于两个相同的括号之间
  （`((`）时，可能会被高亮成一对。
- **Enter 的缩进用的是常量 4**，不是读者配置的缩进大小：那需要一次
  `Project`→`CodeStyleSettings` 的读取和第二个设置读取点。这件事写在源码里，而不是被藏起来。
- **注释器只贡献了契约。** `#` 作为行前缀，另外四个成员为 null，而注释/取消注释的
  *行为*是平台自己的 `CommentByLineCommentAction`。除了那个前缀字符串，插件自己的东西
  一样都没被走到。（`Commenter` 在 262 里没有常量——没有 `END_OF_LINE_COMMENT` 字段——
  所以 `"#"` 是一个字面量。）
- **后缀模板是刻意缺席的**，理由写在那个模板文件里：一个后缀模板只有在能把一个类型
  表达式追加到一个已有表达式后面时才配得上它的位置，而 Vela 没有空安全运算符，也没有
  追加 lambda 的构造；它唯一的后缀 `: T` 是一个声明。

## 6. 临时目录

长期规则是每一个工作目录都住在 `vela\` 下面。`C:\Users\lu\Downloads\_editor\` 是唯一的违规——它装着只读的 API 转储和三个列表工具，没有一个是交付物，而每一次移动它的尝试都随着那个卡死的 shell 一起死了。命令再次可用时，先把它移到 `vela\.work\editor\`。
