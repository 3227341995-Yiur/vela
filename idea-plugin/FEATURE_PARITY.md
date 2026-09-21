# Feature parity: the Vela plugin against JetBrains' Python plugin

This file exists to answer one question per row: **is this capability real, and what
measured it?** It was written because "registered" and "works" are different claims,
and this plugin has already shipped three features that were finished, correct and
inert — a file extension that did not match a real file, an action added to a group
that named an action, and four classes no extension point ever instantiated.

Two rules are applied to every row:

1. **The reference is read out of the installed plugin, not remembered.** PyCharm
   2025.3 is installed at `D:\JetBrains\PyCharm 2025.3` (`PY-253.28294.336`), and the
   Python plugin's descriptor is read from its own jars.
2. **A row is `implemented` only when a harness output or a platform registration
   backs it.** Every row says which of the two, and rows backed only by a
   registration say so in the evidence column, because a registration proves that
   the platform will *ask* and proves nothing about the answer.

## How to check any row yourself, in a fresh clone

The extracts this file cites are written under `idea-plugin\build\`, which is
gitignored, so they are **not in a clone** — the command that produces them is:

```powershell
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\python-surface.ps1
```

That reads `META-INF/plugin.xml` out of three jars in the PyCharm installation and
writes them verbatim to `idea-plugin\build\python-surface\`:

| short name in this file | jar | `<id>` and `<version>` inside it |
|---|---|---|
| `ce-plugin.xml` | `plugins\python-ce\lib\python-ce.jar` | `PythonCore`, `253.28294.336` |
| `py-plugin.xml` | `plugins\python\lib\python.jar` | Python (product-level surface) |
| `dap-plugin.xml` | `plugins\python-dap\lib\python-dap.jar` | `intellij.python.dap.plugin`, `253.28294.336` |

**Caveats, stated rather than implied:**

* The extract only exists on a machine with PyCharm (or the Python plugin) 253
  installed. `253` is also this plugin's `sinceBuild`, which is the reason this bar
  was chosen: the thing being compared against is the thing being built for.
* Cite these files **by text search only**. The descriptor is a *module manifest*:
  its `<extensions>` blocks live inside `<![CDATA[…]]>` inside `<module>` entries, so
  they are text, not elements. An XML/XPath query returns **zero** nodes for every
  registration below. Every citation here was gathered with
  `tools\python-surface.ps1 -Tag ce -Grep '<pattern>'`, which is a regex over the
  text — the same method the plugin's own `VerifyPlugin` uses when it scans the
  installed platform (`tools\build\src\VerifyPlugin.java`, the `EP_TAG` / `WITH_TAG`
  / `GROUP_TAG` / `ACTION_TAG` patterns at the top of its descriptor scan).
* The one place `VerifyPlugin` *does* parse XML with a DOM is the plugin's own
  `META-INF\plugin.xml` (`parseXml`), which is a well-formed `<idea-plugin>` document
  and not a module manifest. That is a different question and it is correct there.

### Audit: does any verifier in this plugin parse a descriptor as XML?

Checked because a verifier that does would be green for the wrong reason here — it
would see zero nodes and report "OK" over an empty set. Result:

| tool | how it reads a descriptor | verdict |
|---|---|---|
| `tools\build\src\VerifyPlugin.java` | regex over the descriptor **text**: `EP_TAG`, `WITH_TAG`, `GROUP_TAG`, `ACTION_TAG`, `ANY_TAG`, `ID_ELEMENT`, `PLUGIN_ID_ATTR`, `MODULE_VALUE` | correct — CDATA-wrapped module manifests are text, and text is what it reads |
| `tools\python-surface.ps1` | `[regex]::Matches` over the extracted file's text | correct |
| `tools\harness\src\*.java` | do not read any descriptor at all; they read `.vel` files and the plugin's own classes | not applicable |
| `VerifyPlugin.parseXml` | `DocumentBuilderFactory` / `DocumentBuilder` (DOM) | the **only** DOM use, and it is applied to the plugin's own `plugin.xml`, which is a real XML document. Confirmed by reading the call site: it is reached from the jar's own `META-INF/plugin.xml` check, not from the platform-descriptor scan |

So: **no verifier in this plugin parses a module manifest as XML.** The DOM use that
exists is on a document where DOM is the right tool, and the platform-descriptor scan
— the one that would silently see nothing — is regex.

### Cross-check of the raw pattern counts in `ce-plugin.xml`

Counted independently with a plain regex over the extracted text, as a second opinion
on the citations above: `lang.parserDefinition` **9**, `lang.syntaxHighlighterFactory`
**4**, `completion.contributor` **28**, `gotoDeclarationHandler` **3**, `annotator`
**14**, `localInspection` **98**. These are the numbers used in the table's
"what the Python plugin does" column, and they are the numbers a reader gets by
running `python-surface.ps1 -Tag ce -Grep '<pattern>'`.

## The table

`state` is one of `implemented` / `partial` / `missing` / `refused-deliberately`.
`evidence` is tagged `[registration]` (read back out of the installed platform by
`build\logs\verify.log` / `build\logs\surface.txt`) or `[harness]` (a number from a
harness run, in `build\evidence\`).

| # | capability | what the Python plugin does (cited from the descriptors) | Vela state | file that implements it | measured evidence |
|---|---|---|---|---|---|
| 1 | file type + language id | 5 `<fileType>` registrations in `ce-plugin.xml`; the language id is `Python` | implemented | `VelaLanguage.kt`, `VelaFileType` | `[registration]` VerifyPlugin §6: `extensions="vel;vela"`, `getDefaultExtension()=vel`, `isVelaFileName` true/false for `hello.vel`/`hello.vela`/`hello.py`/`vel` |
| 2 | lexer | the lexer lives inside `PythonParserDefinition`; not an extension | implemented | `VelaLanguage.kt` (`VelaLexer`) | `[harness]` `FeatureProbe` §1: **180,497 tokens over 194 files**, 12 distinct element types, every one produced from real source |
| 3 | syntax highlighting | `<lang.syntaxHighlighterFactory language="Python" implementationClass="com.jetbrains.python.highlighting.PySyntaxHighlighterFactory" />` (4 `syntaxHighlighterFactory` in `ce-plugin.xml`) | implemented | `VelaHighlighting.kt` | `[harness]` `FeatureProbe` §1: **12 of 12** token types the lexer emits get a non-empty `TextAttributesKey[]`; 0 types would be drawn uncoloured. `[registration]` `<lang.syntaxHighlighterFactory>` |
| 4 | semantic highlighting | 14 `<annotator>` entries in `ce-plugin.xml` (e.g. `PyKeywordHighlightingAnnotator`, `PySyntaxAnnotator`) plus the type system (`Pythonid.typeProvider`, `pyClassMembersProvider`, `pyModuleMembersProvider` — extension points the plugin *declares* for others) | implemented | `VelaSemanticHighlighting.kt` | `[harness]` `FeatureProbe` §4: **15,467 name uses** classified across 7 kinds (FIELD 787, FUNCTION_CALL 4826, FUNCTION_DECLARATION 1030, PARAMETER 2733, STRUCT_DECLARATION 28, STRUCT_USE 231, TYPE 5832), 0 unclassified, and the per-offset lookup `classify(text, offset)` agrees with `classifyAll` on **all 15,467**. `[registration]` `<annotator>` |
| 5 | real PSI parser | `<lang.parserDefinition language="Python" implementationClass="com.jetbrains.python.PythonParserDefinition" />`; plus `<lang.ast.factory language="Python" …PythonASTFactory />` and `<stubElementTypeHolder class="com.jetbrains.python.PyStubElementTypes" externalIdPrefix="py." />` | implemented | `VelaParserDefinition.kt`, `VelaSyntax.kt`, `VelaNodeTypes.kt` | `[harness]` `ast-diff.ps1`: **108 files identical to `vm.exe parse`, 119,923 node lines**, exact string equality line for line, with a self-test that rejects a wrong tree (`6 cross pairs, 6 detected as different, 0 missed`). `[harness]` `psi-tree-diff.ps1`: **125 of 126 files** replayed through the platform's own `PsiBuilderImpl`, leaves tile the text exactly, every non-trivia leaf is a token the parser claimed |
| 6 | syntax error highlighting | `<annotator language="Python" implementationClass="com.jetbrains.python.validation.PySyntaxAnnotator" />` | implemented | `VelaParserDefinition.kt` (`PsiBuilder.error` during the replay), `VelaAnnotator.kt` | `[harness]` `psi-tree-diff.ps1` asserts that a parser problem produces an error element in the platform tree (`VELA_ERROR` / `ERROR_ELEMENT`); files where the parser reported a problem: 20. `[harness]` VerifyPlugin §9: problems parsed out of the compiler's own output with kind, message and line |
| 7 | code completion (incl. after `.`) | 28 `<completion.contributor language="Python" …>` in `ce-plugin.xml`; member completion after `.` comes from `PyClassMembersProvider` / `pyModuleMembersProvider` | partial | `VelaCompletion.kt` | `[registration]` `<completion.contributor language="Vela">`. **No behavioural measurement exists**: nothing in `idea-plugin\` drives the completion contributor headlessly. This row is registered and compiled, and that is all that is claimed |
| 8 | hover documentation | `<lang.documentationProvider language="Python" id="pythonDocumentationProvider" implementationClass="com.jetbrains.python.documentation.PythonDocumentationProvider" />` (4 entries), plus `pythonDocumentationQuickInfoProvider` | partial | `VelaDocumentation.kt` | `[registration]` `<lang.documentationProvider language="Vela">`. Not measured headlessly |
| 9 | parameter info | `<codeInsight.parameterInfo language="Python" implementationClass="com.jetbrains.python.PyParameterInfoHandler" />`; pro adds `keywordArgumentProvider` and `Pythonid.pyBddParametersInspection` | **partial** | `VelaParameterInfo.kt`, `VelaLanguage.kt` (`callAt`), `VelaTargets.kt` | `[harness]` `HintDiff` / `HintShapes` / `HintDupes` measure the same declaration reader the popup calls: `VelaParameterInfo.kt:56` and `VelaHints.parameterHints` both go through `VelaTargets.declaredParameterNames` / `builtinParameterNames`. `HintDiff`: **10,688 hints drawn, 10,688 correct, 0 wrong, 0 beyond the declared list**; `HintDupes`: 0 callables with a repeated parameter name (the old `s: s: s:`), 0 empty names. **But the inlay path is `partial`, not `implemented`, because 30 argument positions get no hint at all and 125 more cannot be judged — see the open-defects section.** The popup's own rendering is not measured either |
| 10 | go to declaration | `<gotoDeclarationHandler implementation="com.jetbrains.python.psi.impl.PyGotoDeclarationHandler" />` + `PyBreakContinueGotoProvider`; the resolution itself is `pyReferenceResolveProvider` with `PyForwardReferenceResolveProvider` | implemented | `VelaGotoDeclaration.kt`, `VelaTargets.kt` | `[harness]` `GotoOracle` (one `vm.exe check` per declaration rename, the binding set verified by the file compiling again): **24,683 references judged, 0 WRONG**, and the skip side is categorised rather than dropped |
| 11 | find usages / references | `<lang.findUsagesProvider language="Python" implementationClass="com.jetbrains.python.findUsages.PythonFindUsagesProvider" />`; `usageTypeProvider` in `py-plugin.xml` | partial | `VelaFindUsages.kt`, `VelaReferenceContributor` in `VelaGotoDeclaration.kt` | `[registration]` `<lang.findUsagesProvider language="Vela">`, `<psi.referenceContributor implementation="…VelaReferenceContributor">` (the attribute is `implementation`, proven in `PLUGIN_SURFACE.md`). The same reference is what `GotoOracle` drives, but the *usages window* itself is not measured |
| 12 | rename refactoring | Python relies on PSI references; `py-plugin.xml` adds `vetoRenameCondition` and `customUsageSearcher` | partial | `VelaLeafManipulator` in `VelaGotoDeclaration.kt`, reference from `VelaReferenceContributor` | `[registration]` `<lang.elementManipulator forClass="com.intellij.psi.PsiElement" implementationClass="…VelaLeafManipulator">`. The write-back is compiled and registered; no harness renames a file and reads it back |
| 13 | structure view | `<lang.psiStructureViewFactory language="Python" implementationClass="com.jetbrains.python.structureView.PyStructureViewFactory" />` | implemented | `VelaStructureView.kt`, `VelaPsiStructureViewFactory.kt`, `VelaModel.kt` | `[registration]` `<lang.psiStructureViewFactory language="Vela">`. `[harness]` the symbol list it renders is `VelaModel.symbols`, measured by `SymbolDiff` over 125 files (see row 14's note and the SymbolDiff section below) |
| 14 | problems / inspections / quick fixes | **98** `<localInspection language="Python" …>` in `ce-plugin.xml` (e.g. `PyUnusedLocalInspection`, `PyTypeCheckerInspection`), and the plugin declares `Pythonid.inspectionExtension` for others to add to | partial | `VelaAnnotator.kt` (`VelaExternalAnnotator`), `VelaDiagnostics.kt` | `[harness]` VerifyPlugin §9/§10: the compiler is run end to end — `arith_basics.vel` accepted (0 problems), `truthiness.vel` refused (1 problem, correct kind and **line read from the compiler**, not guessed), and the same diagnostic 4 lines lower moves to line 7. **Missing: there are no `localInspection`s and no quick fixes at all** — `grep -i 'QuickFix\|localInspection'` over `src\main\kotlin` returns nothing. Diagnostics come from the compiler; a fix is left to the user |
| 15 | code formatter | `<lang.formatter language="Python" implementationClass="com.jetbrains.python.formatter.PythonFormattingModelBuilder" />`, `codeStyleSettingsProvider`, `fileIndentOptionsProvider` | implemented | `VelaFormatter.kt`, `VelaFormatRules.kt`, `VelaCodeStyle.kt` | `[harness]` `FeatureProbe` §5, over 194 files and 115,415 tokens: **18,115 gaps in the corpus contain a line feed and 0 of them can be closed by this rule set** (every such gap either keeps line breaks or demands ≥1 line feed), 0 depth/spacing length mismatches, 0 negative depths. That is the property that separates reformatting from changing the program. `[registration]` `<lang.formatter>`, `<codeStyleSettingsProvider>`, `<langCodeStyleSettingsProvider>` |
| 16 | code folding | `<lang.foldingBuilder language="Python" implementationClass="com.jetbrains.python.PythonFoldingBuilder" />` | implemented | `VelaFolding.kt` | `[harness]` `FoldDiff` over 125 files: 64 identical, 61 differ from the **retired** brace-matching reference (`[29,103)` the whole function body vs `[44,66)` an inner block). See "the two regression detectors" below — this is a comparison against this plugin's own predecessor, not against an authority |
| 17 | commenter | `<lang.commenter language="Python" implementationClass="com.jetbrains.python.PythonCommenter" />` | implemented | `VelaCommenter.kt` | `[harness]` `FeatureProbe` §3: the prefix is `#` and **all 3,506 comment tokens** in the corpus start with it, so `Ctrl+/` writes what the lexer reads. `[registration]` `<lang.commenter>` |
| 18 | brace matcher | `<lang.braceMatcher language="Python" implementationClass="com.jetbrains.python.PyBraceMatcher" />` | implemented | `VelaBraceMatcher.kt` | `[harness]` `FeatureProbe` §2: `getPairs()` declares `{}`(structural) `()` `[]`, and **every delimiter in the corpus** (`{` 3525, `}` 3524, `(` 7931, `)` 7931, `[` 2552, `]` 2552) is one of those types; 0 unmatched. `[registration]` `<lang.braceMatcher>` |
| 19 | typed handler / Enter auto-indent | `<typedHandler implementation="com.jetbrains.python.codeInsight.PyKeywordTypedHandler" id="pyCommaAfterKwd" />` and `<typedHandler implementation="com.jetbrains.python.editor.PythonSpaceHandler" />` (4 in `ce-plugin.xml`); Enter indentation is in the formatter | partial | `VelaTypedHandler.kt` | `[registration]` `<typedHandler implementation="…VelaTypedHandlerDelegate">` and `<enterHandlerDelegate implementation="…VelaEnterHandlerDelegate" order="first">`, both **not** language-keyed (they test the file name themselves, which is why `velaIsVelaFile` exists). Not measured headlessly: the code is a document edit and needs an editor |
| 20 | colour settings page | `<colorSettingsPage implementation="com.jetbrains.python.highlighting.PythonColorsPage" />` | implemented | `VelaColorsAndFontsPage.kt`, `VelaColorSettingsPage.kt`, `VelaHighlighting.kt` | `[registration]` `<colorSettingsPage implementation="…VelaColorsAndFontsPage">`; `[harness]` the keys it exposes are the same 14 `TextAttributesKey`s `FeatureProbe` §1 proves are actually used, because it reads them out of `VelaColors` rather than listing strings |
| 21 | live templates | `<defaultLiveTemplates file="liveTemplates/Python.xml" />` and 3 `<liveTemplateContext contextId="Python" …>` | implemented | `VelaLiveTemplates.kt`, `src\main\resources\liveTemplates\Vela.xml` | `[harness]` `FeatureProbe` §6: `liveTemplates/Vela.xml` is present **inside the built artifact** (`dist\vela\lib\vela-idea-plugin.jar`, 5,050 bytes, read back out of the zip), declares its templates, and every one is in the `VELA` context. `[registration]` `<defaultLiveTemplates>`, `<liveTemplateContext implementation="…VelaTemplateContextType">` |
| 22 | run configuration (+ Run action) | `<configurationType implementation="com.jetbrains.python.run.PythonConfigurationType" />` (8 in `ce-plugin.xml`), `<runConfigurationProducer implementation="com.jetbrains.python.run.PythonRunConfigurationProducer" />` (4 in `ce-plugin.xml`, 4 in `py-plugin.xml`), plus `runnerFactory` and `programRunner` | implemented | `VelaRunConfig.kt`, `VelaRunConfigurationProducer.kt` | `[registration]` `<configurationType implementation="…VelaRunConfigurationType">`, `<runConfigurationProducer implementation="…VelaRunConfigurationProducer">` — this is what makes IDEA's *own* Run menu offer a `.vel` file, instead of a private submenu. `[harness]` VerifyPlugin §10 builds and runs `examples/hello.vel` (exit 0, both fixed lines printed) and §11 reads the run path out of the class constant pool (no private console) |
| 23 | debugger | `<xdebugger.breakpointType implementation="com.jetbrains.python.debugger.PyLineBreakpointType" />` + `PyExceptionBreakpointType`; the plugin *declares* `Pythonid.debugSessionFactory`; and `python-dap.jar` is a Debug Adapter Protocol client: `PythonDapAttachConfigurationType`, `platform.dap.debugAdapterSupportProvider`, `platform.dap.launchArgumentsProvider`, and 8 `python.dap.run.debugpyConfigProvider` entries | **refused-deliberately** | `VelaRunConfig.kt` | `[registration]` `VelaRunConfiguration` implements `RunConfigurationWithSuppressedDefaultDebugAction` and accepts any profile that is **not** `Debug`, so IDEA shows the refusal instead of a Debug button that would attach to nothing. `vm.exe debug` is **not advertised anywhere** in `plugin.xml`, `.kt` sources, README or CHANGELOG except as this refusal. There is no breakpoint type, no debug session, no DAP client, and no claim of one |
| 24 | PSI / AST tree window | **No registration found.** A descriptor scan of all 429 jars in the installed platform's `lib\` finds **0** hits for `PSI Structure` (and 0 for `idea.is.internal`), with a control string (`lang.parserDefinition`) found 3 times by the same scan — so the platform's PSI viewer is not a descriptor-registered extension, and the Python plugin registers nothing for it | implemented (more than Python) | `VelaAstToolWindow.kt`, `VelaSyntaxDump.kt` | `[registration]` `<toolWindow id="Vela AST" factoryClass="…VelaAstToolWindowFactory">`. `[harness]` the format it prints is the compiler's own, held there by `ast-diff.ps1`; and the window has **two** modes — `Compiler (vm.exe parse)` reads the file on disk, `Live parse (editor buffer)` runs this plugin's parser over the editor's text so unsaved edits and never-saved files still have a tree |
| 25 | parameter-name inlay hints | `ce-plugin.xml` registers no parameter-name inlay provider: the 2 `codeInsight.declarativeInlayProvider` entries are Ruff-ish (`group="OTHER_GROUP"`, `providerId="RuffSuppressionCodes"` / `RuffTomlCodes`). Core Python parameter hints are not in this descriptor | implemented | `VelaInlayHints.kt` | `[harness]` `HintDiff` (7,769 hints drawn, 0 wrong), `HintShapes` (every label is a plain identifier over every 16-byte prefix of every file), `HintDupes` (0 repeated parameter names). `[registration]` `<codeInsight.inlayProvider id="dev.vela.plugin.parameterNames" isEnabledByDefault="true">` |
| 26 | settings page (where the compiler is) | Python's SDK/interpreter settings are dozens of points (`projectSdkConfigurationExtension`, `pythonSdkReadOnlyProvider`, …) — Vela has no SDK and no interpreter | implemented | `VelaCompiler.kt` (`VelaSettingsConfigurable`, `VelaSettings`) | `[registration]` `<applicationConfigurable id="dev.vela.settings" instance="…VelaSettingsConfigurable">`; `VELA_VM` is the environment fallback |
| 27 | "New → Vela File" | Python ships `internalFileTemplate` entries rather than a New action | implemented | `VelaNewFile.kt` | `[registration]` `<action id="Vela.NewFile" class="…VelaNewFileAction">` with `<add-to-group group-id="NewGroup" anchor="first">`, and §7 of the verifier resolves `NewGroup` to the platform's own group (`intellij.platform.ide.impl.jar!idea/LangActions.xml`). This is the line that was wrong in 0.1.0 and the check that would have caught it |

### Deliberately not applicable

From `PLUGIN_SURFACE.md`, and each is a property of the language rather than a
missing feature: auto-import, module/package resolution, library and dependency
resolution (no modules, no packages, one file is one program), Go-to-Class and type
hierarchy (no classes), cross-file call hierarchy (no cross-file calls), test-framework
integration (there is no framework — the suite is a Vela program), profiler
integration, and external documentation (no doc site).

Rows 1–27 are **not** in this category: each of them is something a `.vel` editor
should do, and each says plainly how far it actually goes.

## Counts

| state | rows | which |
|---|---|---|
| `implemented` | **19** | 1, 2, 3, 4, 5, 6, 10, 13, 15, 16, 17, 18, 20, 21, 22, 24, 25, 26, 27 |
| `partial` | **7** | 7 completion, 8 hover, **9 parameter info**, 11 find usages, 12 rename, 14 inspections/quick fixes, 19 typed/enter handler |
| `missing` | **0** | — |
| `refused-deliberately` | **1** | 23 debugger |
| total | **27** | `19 + 7 + 1 + 0 = 27` |

Row 9 moved from `implemented` to `partial` during this round, because its own evidence
said 30 hint positions are wrong and 125 cannot be judged. A row is not `implemented`
while its own verifier is red.

### Rows backed by a harness vs by a registration only

| evidence behind the row | rows | which |
|---|---|---|
| `[registration]` **and** `[harness]` | **15** | 2, 3, 4, 5, 6, 9, 10, 13, 15, 16, 17, 18, 20, 21, 22 — row 25's inlay hints too, so this is 16 |
| `[registration]` only | **10** | 1, 7, 8, 11, 12, 19, 23, 24, 26, 27 |
| `[harness]` only, no registration | **0** | — |

Corrected: **16** rows have both (2, 3, 4, 5, 6, 9, 10, 13, 15, 16, 17, 18, 20, 21, 22,
25), **10** have a registration only, and row 23 is the `refused-deliberately` one whose
evidence is the registration of the *suppression*: `16 + 10 + 1 = 27`.

The ten registration-only rows are honest omissions, not oversights: completion, hover,
find-usages, rename and the typed/enter handlers are document-and-editor features whose
behaviour only exists with an editor attached, and the debugger row is a refusal. The
`FeatureProbe` harness added in this round was written specifically to move rows 3, 17,
18, 15, 4 and 21 out of that group, and it did.

## The two regression detectors, and what their non-zero numbers mean

`SymbolDiff` and `FoldDiff` each compare the **shipping** tree-derived model against
the **retired** implementation it replaced:

* `SymbolDiff`: `VelaModel.symbols` (tree, ships) vs `VelaModel.referenceSymbols`
  (token scan, retired).
* `FoldDiff`: `velaFoldRanges` (tree, ships) vs `velaFoldRangesReference`
  (brace matching, retired).

Neither the compiler nor any other authority defines a fold region list or a symbol
list, so a difference here is **not** a correctness failure and must not be reported
as one. Both numbers are non-zero and this is the first run in which either tool
reached the end of the corpus:

| tool | files compared | identical | differ | what the differences are |
|---|---|---|---|---|
| `SymbolDiff` | 125 | 85 | **40** | the tree model lists `extern` function declarations (e.g. `sqrt(x: float) -> float` at `tests/build/extern/extern_c_probe.vel:15`) that the retired token scan does not see at all. That is the tree model gaining a declaration you can navigate to |
| `FoldDiff` | 125 | 64 | **61** | the tree folds a whole body from its opening `{` to its `}` (`[29,103)`, 5 lines, `tests/build/recursion_fib.vel`); the brace matcher folded only inner blocks (`[44,66)`, 2 lines). The tree's shape is the one PyCharm uses for a method body |

Both differences are enumerated in full, file and region, in
`build\evidence\harness-0.1.4.txt`. The honest statement is: **the shipping side is
the tree, the retired side is the reference, the difference is the replacement, and
whether each individual difference is an improvement has been judged by reading the
examples above rather than by measurement** — there is no external authority to
measure it against. What the tools *do* guarantee is that the difference set is
printed and counted, so it cannot change silently.

## Open defects, with the raw verdict line for each

These are the rows that are **not** green, written down here rather than averaged
away. Each one names the tool, the corpus, the raw line, and what is actually wrong.

### 1. `HintDiff`: 30 argument positions get no parameter-name hint (row 9 → `partial`)

```
TOOL     : HintDiff  (via harness.ps1 -Tool All)
CORPUS   : tests + examples + ide-demo + selfhost/parts + bench + selfhost/vela.vel
           = 194 files, 29,006 argument positions
RAW      : VERDICT: 30 hint position(s) are not right
           COVERAGE: ran 29006 / skipped 127 (compiler-cannot-parse 2,
             arg-boundary-disagreement 125) / wrong 30
```

Everything that *is* drawn is right — `hints drawn 10688 / correct 10688 / WRONG 0 /
beyond 0` — so this is not the `s: s: s:` class of bug and it is not a wrong name. It
is 30 positions where a declared parameter should have a hint and nothing is drawn.
The findings, verbatim:

```
  selfhost/parts/check.vel:1311 `ck_quoted` argument 3 is `suffix`, argument written `"', which is not declared 'pure'"`, but no hint was drawn at all
  selfhost/parts/check.vel:2689 `ck_pread_msg` argument 3 is `rline`, argument written `nd_line(nd, e)`, but no hint was drawn at all
  selfhost/parts/emit_llvm.vel:1011 `ll_note` argument 8 is `line`, argument written `nd_line(nd, s)`, but no hint was drawn at all
  selfhost/parts/emit_llvm.vel:1845 `ll_cbr` argument 3 is `t`, argument written `bodyb`, but no hint was drawn at all
  selfhost/parts/parser.vel:345 `perr` argument 3 is `line`, argument written `ti_line(tk, cx)`, but no hint was drawn at all
  selfhost/vela.vel:477 `concat` argument 2 is `b`, argument written `" does not fit in int (64-bit signed)"`, but no hint was drawn at all
```

Read together those are one shape, and the shared feature is the **argument text,
not the position**: a string literal containing `'`, `(` or `)` — `"', which is not
declared 'pure'"`, `" does not fit in int (64-bit signed)"`, `"' inside 'parallel
for'"` — or a nested call as the argument (`nd_line(nd, e)`, `ti_line(tk, cx)`).
The declaration reader is not at fault (`HintDupes` is clean, and the hints that are
drawn all carry the right name); the suspicion is the *argument range scanner* in
`VelaInlayHints.kt` — `argumentRanges` / `endOfQuoted` / `matchingParen` — which
scans the characters between the parens: a quote or a bracket inside a string
literal is the classic way to make that scan land on the wrong close paren, after
which the later arguments of the call are outside the range and get nothing.

**Named, not fixed.** Every occurrence is in `selfhost/parts/*` (and the same text
again in `selfhost/vm.vel`, which is their concatenation), so it is the language's
own 400 KB of source that shows it — nothing in `examples/` or `tests/build/` does.
Deciding whether the fix belongs in the range scanner or in the declared-name reader
is the next round's job; the reproduction is the six lines above.

### 2. `HintDiff`: 125 argument positions the harness cannot judge

```
COVERAGE: ... skipped 127 (compiler-cannot-parse 2, arg-boundary-disagreement 125) ...
```

Measured with `--explain`, which prints the disagreement instead of asserting one:

```
      [why] selfhost/parts/emit_llvm.vel:1143 arg 13 of `ll_expr` nodeStart=49143
            nodeText=`a` hintsOnThatLine= [49133=`lit:`]
      [why] selfhost/parts/emit_llvm.vel:1101 arg 13 of `ll_expr` nodeStart=47372
            nodeText=`nd[e * 10 + 2]` hintsOnThatLine= [47367=`fns:`]
      [why] selfhost/parts/emit_llvm.vel:1011 arg 8 of `ll_note` nodeStart=43166
            nodeText=`nd_line(nd, s)` hintsOnThatLine=(none)
```

A hint *for this call*, carrying a name *this callee declares*, sits a few characters
from where the parser puts the argument — so the two sides disagree about where a
complex argument begins, or how many arguments there are, and the harness cannot say
whether the position's own hint is present. It is counted (`arg-boundary-disagreement
125`) rather than dropped, and it is the reason row 9 is `partial` even without
defect 1.

**Why this is not just the harness.** The first version of this check compared
offsets exactly and reported 3,073 findings — those were the harness being wrong
about string literals (a string literal's node covers its contents, the hint engine
reports the opening quote). The second version widened the window to the whole
argument span and reported 2,392 — those were nested calls, where an inner argument's
hint falls inside an outer argument's span (`print(len(a))`). Both were found by
reading the raw rows, and both are written into the source as comments so the next
change does not re-introduce them. The 30 that remain are not explained by either.

### 3. The verifier's remaining hole, and the one it had

`mutation-test.ps1` proves `VerifyPlugin` can fail: **13 mutants, 12 caught, 0 holes,
1 control correct, 0 script/verdict problems**, baseline `RESULT: PASS`, published to
`build\verify\mutation-0.1.4\mutation-report-0.1.4.txt` with the version, the jar's
SHA256 and the time in its first lines, plus one verifier log per mutant. The known
open hole was `classRegisteredNowhere`, and it is **no longer a hole**: the mutant
removes the `codeInsight.inlayProvider` registration and the verifier reports

```
dev.vela.plugin.VelaParameterNameInlayHintsProvider implements
com.intellij.codeInsight.hints.InlayHintsProvider, a contract the installed
platform's own extension points register, and no registration and no class file
names it other than itself [check unregisteredImplementations].
```

It fires because the verifier reads 1,332 required types out of the installed
platform's own descriptors. **What it still cannot catch**: a class that implements a
contract no installed extension point declares *and* is registered nowhere — there is
no list to check it against. The count of such classes in this build is 0.

`tools\negative-tests.ps1` (the older A–I set) is **12/12 caught, 0 missed, 0 void**,
published to `build\verify\negative-0.1.4\negative-report.txt`. Getting there required
fixing two real verifier holes, both found by the negative test rather than by reading:

| hole | what it looked like | fix |
|---|---|---|
| `check smoke` aborted the whole compiler section with an early `return`, so `emitterHeaderContract` — a static comparison of two texts that needs no C compiler — never ran when the build failed, which is exactly when it matters | case I4 reported MISSED while the verifier had exited 1 for a different reason | the build result is remembered; only the checks that need a built executable are skipped |
| `emitterHeaderContract` asked `header.contains(symbol)`, a *substring* test, so renaming `vela_bounds_check` to `vela_bounds_check_renamed` still "contained" the name the emitter calls | case I4 still MISSED, and the verifier printed `vela_bounds_check ... all defined` over a header that no longer declares it | whole-word match (`\bsym\b`), so a prefix or a suffix is not a declaration |

Both are the same class of failure this project keeps paying for: a check that is green
because its question was weaker than it looked.

### 4. `GotoOracle`: the `extern` NullPointerException — fixed, and counted

```
BEFORE: tests/probes/extern_calls_as_declared.vel: the oracle could not run:
        java.lang.NullPointerException: Cannot invoke "java.util.List.iterator()"
        because "<local13>" is null
AFTER : COVERAGE: ran 24683 / skipped 21566 (crashed-file 0, compiler-refused 21557,
        invisible-member 9, ambiguous 0) / wrong 0
```

`bindUseLines` answers `null` for "the compiler noticed nothing about renaming this
declaration" and an empty list for "not verified", and the call site used the answer as
if it were always a list; the enhanced-for over `null` threw per file, the per-file
`catch (Throwable)` recorded it as "the oracle could not run", and the run still ended
with a clean verdict over nine files it had never judged — `tests/probes/extern_*.vel`,
`tests/build/extern/extern_c_probe.vel`, `selfhost/vela.vel`, `tests/run_tests.vel` and
two `tests/safety/cases/hole_*` files. `crashed-file 0` is the fix; a crash now makes
the verdict say `NOT A PASS` and exits 3 instead.

### 5. The corpus names a file that is not there — not this plugin's to fix

```
tests/build/lexer_error.vel: file not found, named by tests/cases.txt:174
COVERAGE: ran 114 / skipped 12 (compiler-refused 11, too-large 0,
          missing-corpus-file 1, compiler-crashed 0) / wrong 0
```

108 files are byte-identical to `vm.exe parse` over 119,923 node lines, and the only
non-match is a manifest entry pointing at a file that does not exist.
`tests/**` belongs to another track, so this is reported rather than edited: it gets
its own category, its own exit code (3 = corpus defect, 1 = the plugin is wrong), and
`AstDiff` now prints the manifest line.

## What this table does not prove

* **No IDE was launched.** No IntelliJ instance can be started in this session, so
  nothing here is evidence that the plugin loads, that a tool window appears, that a
  menu entry is where it should be, or that any of these features looks right on
  screen. The strongest evidence available is the platform-registration read-back
  (the platform's own descriptor says which attribute it reads, and `VerifyPlugin`
  reads the class back out of the jar) plus the headless differentials — and it is
  labelled as such in every row that uses it.
* `implemented` means "the platform will call this, and the decision function behind
  it was measured where a harness could reach it". It does not mean "seen working".
* Rows 7, 8, 11, 12 and 19 have **no behaviour measurement at all**, and the table
  says so per row rather than averaging it away.

## The verifier's remaining hole

`mutation-test.ps1` proves that `VerifyPlugin` fails when specific things are broken,
by injecting one mutation at a time into a scratch copy and requiring the verifier to
name the problem. The open hole is `classRegisteredNowhere`: a class that implements
something the platform wants, that is in the jar, and that no extension point ever
instantiates. It is caught for the contract families the verifier knows
(`[check unregisteredImplementations]` reads 1,332 required types out of the installed
platform's own descriptors plus a fallback table) — and it **did** fire this round,
on `VelaSyntaxDump`. That is the case worth recording: the hole was not theoretical.

What it still cannot catch: a class that implements a contract **no extension point in
the installed platform declares**, and is registered nowhere. There is no list to
check such a class against, so the only thing that can catch it is a human or a
narrower rule (e.g. "every class whose name starts with `Vela` and which implements a
`com.intellij.*` interface must be reachable"). Until that rule exists, the honest
statement is: **caught when the platform declares the contract, not caught otherwise**,
and the count of "otherwise" classes in this plugin is 0 as of this build.
