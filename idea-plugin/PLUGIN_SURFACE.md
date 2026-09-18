# The plugin's surface, and the traps in it

Every row below was read out of the installed platform rather than remembered:
the `<extensionPoint …>` declaration in a specific jar's `META-INF/*.xml`, the
attribute name the platform actually reads, and a real registration of the same
extension point in one of the bundled plugins. The tooling is described at the
bottom, because "which jar did you read" is the question that settles every
argument about extension points.

This file exists because three separate features in this plugin were finished and
then did nothing, each for a different one-line reason, and because the platform
reports none of those reasons: an unknown extension-point id, a class under the
wrong attribute name, and a class that is never registered at all are all silent.

## Registered today, with the name the platform reads

| tag | extension point | attribute the platform reads | notes |
|---|---|---|---|
| `<fileType>` | `com.intellij.fileType` | `implementationClass`, `fieldName="INSTANCE"`, `extensions="vel;vela"` | both suffixes, or the plugin does nothing on a real `.vel` file |
| `<lang.parserDefinition>` | `com.intellij.lang.parserDefinition` | `implementationClass` | the `lang.` prefix matters: bare `<parserDefinition>` names an id no descriptor declares |
| `<lang.syntaxHighlighterFactory>` | `com.intellij.lang.syntaxHighlighterFactory` | `implementationClass` | |
| `<annotator>` | `com.intellij.annotator` | `language` + `implementationClass` (beanClass `LanguageExtensionPoint`) | semantic colouring by *meaning* |
| `<externalAnnotator>` | `com.intellij.externalAnnotator` | `implementationClass` | the compiler's diagnostics |
| `<lang.psiStructureViewFactory>` | `com.intellij.lang.psiStructureViewFactory` | `implementationClass` | there is no `lang.structureViewBuilder` |
| `<lang.documentationProvider>` | `com.intellij.lang.documentationProvider` | `implementationClass` | |
| `<completion.contributor>` | `com.intellij.completion.contributor` | `implementationClass` | beanClass `CompletionContributorEP` |
| `<codeInsight.parameterInfo>` | `com.intellij.codeInsight.parameterInfo` | `implementationClass` | |
| `<gotoDeclarationHandler>` | `com.intellij.gotoDeclarationHandler` | **`implementation`** (`interface=`) | `intellij.platform.analysis.jar!META-INF/Analysis.xml` |
| `<psi.referenceContributor>` | `com.intellij.psi.referenceContributor` | **`implementation`** | see the trap below — this one was written wrong once |
| `<lang.elementManipulator>` | `com.intellij.lang.elementManipulator` | `forClass` + `implementationClass` | rename's write-back |
| `<codeInsight.inlayProvider>` | `com.intellij.codeInsight.inlayProvider` | `language` + `implementationClass` + `id` (+ `isEnabledByDefault`) | see the trap below |
| `<configurationType>` | `com.intellij.configurationType` | **`implementation`** (`interface=`) | |
| `<runConfigurationProducer>` | `com.intellij.runConfigurationProducer` | **`implementation`** (`interface=`) | what makes IDEA's own Run menu offer a `.vel` file |
| `<toolWindow>` | `com.intellij.toolWindow` | `factoryClass` | |
| `<applicationConfigurable>` | `com.intellij.applicationConfigurable` | `instance` | |
| `<action id="Vela.NewFile">` | `com.intellij.action` | `class`, inside `<add-to-group group-id="NewGroup"/>` | the New menu |
| `<lang.formatter>` | `com.intellij.lang.formatter` | `language` + `implementationClass` | beanClass form. `FormattingModelBuilder` has **no abstract members** and its five `createModel` overloads call each other in a cycle, so the override point is `createModel(FormattingContext)` |
| `<codeStyleSettingsProvider>` | `com.intellij.codeStyleSettingsProvider` | **`implementation`** (`interface=`) | no `language` attribute |
| `<langCodeStyleSettingsProvider>` | `com.intellij.langCodeStyleSettingsProvider` | **`implementation`** (`interface=`) | where a language's indent size becomes visible |
| `<lang.foldingBuilder>` | `com.intellij.lang.foldingBuilder` | `language` + `implementationClass` | beanClass form; `FoldingBuilderEx` adds a concrete `getPlaceholderText(ASTNode, TextRange)` that delegates to the abstract one |
| `<lang.commenter>` | `com.intellij.lang.commenter` | `language` + `implementationClass` | `Commenter` has **no constants** in 262 — there is no `END_OF_LINE_COMMENT` field, so `"#"` is a literal |
| `<lang.braceMatcher>` | `com.intellij.lang.braceMatcher` | `language` + `implementationClass` | `{ }` `( )` `[ ]` only. `<`/`>` **cannot** be paired: the lexer gives every operator one shared `OPERATOR` type, so a pair would match `+` with `<` |
| `<typedHandler>` | `com.intellij.typedHandler` | **`implementation`**, and **no `language`** | interface form, and *not* language-keyed: the class must test the file name itself |
| `<enterHandlerDelegate>` | `com.intellij.enterHandlerDelegate` | **`implementation`**, no `language`, `order="first"` | also not language-keyed. `Result`'s constants are spelled `Continue`/`Default…` in 262, and older releases used `CONTINUE` — the code looks them up by name rather than picking one spelling |
| `<colorSettingsPage>` | `com.intellij.colorSettingsPage` | **`implementation`** | this line existed for a while while the class it named did not, which the verifier's class-existence layer catches |
| `<defaultLiveTemplates>` | `com.intellij.defaultLiveTemplates` | `file` | no class at all; the path resolves against the plugin's own resources |
| `<liveTemplateContext>` | `com.intellij.liveTemplateContext` | **`implementation`** | **a `beanClass` point that nevertheless takes `implementation`** — `implementationClass` here would be the fourth silent no-op |
| `<lang.findUsagesProvider>` | `com.intellij.lang.findUsagesProvider` | `language` + `implementationClass` | `getWordsScanner()` is **non-abstract** and null by default, and null means the platform searches nothing, so a find-usages that "works" but finds nothing looks like a working feature |

## The traps, each of which cost something

1. **`implementation` vs `implementationClass` is per extension point, not
   global.** `psi.referenceContributor`'s bean is `PsiReferenceContributorEP`,
   whose *field* is named `implementationClass` but is annotated
   `@Attribute("implementation")` — so the XML name is `implementation`. Written
   the other way the registration is ignored: `registerReferenceProviders` never
   runs, rename and find-usages start from zero, and Ctrl+Click keeps working
   because it is a different extension point. That is how it survived a
   review and the project's own verifier.

2. **Two plausible ids do not exist**: `codeInsight.inlayHintsProvider` (the real
   one is `codeInsight.inlayProvider`) and `lang.indentOptions`. A registration
   against an undeclared id is a no-op the platform does not report.

3. **`InlayHintsProvider` has no `isEnabledByDefault` member.** The on/off switch
   is the registration's `isEnabledByDefault` attribute; `id` is the key the
   user's toggle is stored under and is required.

4. **Some interfaces have no abstract members at all**, and the defaults can form
   a cycle. `FormattingModelBuilder`'s five `createModel` overloads are all
   default methods and call each other in a loop — an implementation that
   overrides nothing recurses until the stack dies. The override point is
   `createModel(FormattingContext)`, which is what the platform's own JSON
   formatter overrides. `TypedHandlerDelegate` and `EnterHandlerDelegate` are the
   same shape (defaults only).

5. **`Block` changed shape**: nine abstract members, `getChildAttributes(int)`
   instead of `getChildIndent()`, no `getParent()`, and no `AbstractBlock` class
   anywhere in the installed jars. The usable interface is
   `com.intellij.formatting.ASTBlock`.

6. **Vela's lexer makes some features impossible as specified**: every operator is
   one shared `VelaTokenTypes.OPERATOR`, so a `BracePair` would pair `+` with
   `<`. `<`/`>` must not be registered as a pair, and for `()`/`[]`/`{}` the pair
   types are necessarily equal because the lexer uses one type per delimiter kind.

7. **`todoIndexer` takes `filetype=`, not `language=`** — everything else in the
   language-keyed family takes `language`. Getting it wrong means TODOs silently
   never appear.

## Does not apply to Vela, and filling it in would be worse

Auto-import, module/package resolution and library/dependency resolution (no
modules, no packages, one file is one program); goto-class and type hierarchy (no
classes); cross-file call hierarchy (no cross-file calls); test-framework
integration (there is no framework — the suite is a Vela program); profiler
integration; external documentation (no doc site, and the extension point does
not resolve in the installed jars at all).

## What is not verified here

- The member lists of `BreadcrumbsProvider`, `PostfixTemplateProvider`,
  `SpellcheckingStrategy`, `DataIndexer`, `IndexPatternBuilder`, `XBreakpointType`
  and whatever provides call hierarchy — one agent's shell died before dumping
  them, so they are named as unknown rather than guessed.
- Whether the classes we implement carry `@ApiStatus.Internal`. Extension-point
  *declarations* were scanned across all 429 jars and **none** carries an
  `internal` attribute, but that is a different question from the class.
- `since-build="253"`: the formatter-related classes were dumped under both the
  262 and the PyCharm 2025.3 (253) classpaths with identical member sets, but the
  full descriptor has not been re-linked against 253 since the last registrations
  were added.

## How to check a row yourself

There is no `javap` on this machine. The verifier's own tools live in
`build\tools\src\` and are compiled into `build\tools\classes`:
`ClassSig <classpath-file> <FQCN>` prints declared members, `MethodFlags` prints
class-file flags including which members are abstract, and a classpath file is one
jar path per line (`build\probe\*.args` are examples). For extension points, read
`META-INF/*.xml` inside each jar under the platform's `lib\` directory — that is
where every id, `interface=` and `<with attribute=… implements=…>` above came
from.
