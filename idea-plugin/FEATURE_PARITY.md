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
| 2 | lexer | the lexer lives inside `PythonParserDefinition`; not an extension | implemented | `VelaLanguage.kt` (`VelaLexer`) | `[harness]` `FeatureProbe` §1: **188,152 tokens over 195 files**, 12 distinct element types, every one produced from real source |
| 3 | syntax highlighting | `<lang.syntaxHighlighterFactory language="Python" implementationClass="com.jetbrains.python.highlighting.PySyntaxHighlighterFactory" />` (4 `syntaxHighlighterFactory` in `ce-plugin.xml`) | implemented | `VelaHighlighting.kt` | `[harness]` `FeatureProbe` §1: **12 of 12** token types the lexer emits get a non-empty `TextAttributesKey[]`; 0 types would be drawn uncoloured. `[registration]` `<lang.syntaxHighlighterFactory>` |
| 4 | semantic highlighting | 14 `<annotator>` entries in `ce-plugin.xml` (e.g. `PyKeywordHighlightingAnnotator`, `PySyntaxAnnotator`) plus the type system (`Pythonid.typeProvider`, `pyClassMembersProvider`, `pyModuleMembersProvider` — extension points the plugin *declares* for others) | implemented | `VelaSemanticHighlighting.kt` | `[harness]` `FeatureProbe` §4: **15,994 name uses** classified across 7 kinds (FIELD 898, FUNCTION_CALL 4967, FUNCTION_DECLARATION 1049, PARAMETER 2805, STRUCT_DECLARATION 28, STRUCT_USE 236, TYPE 6011), 0 unclassified, and the per-offset lookup `classify(text, offset)` agrees with `classifyAll` on **all 15,994**. `[registration]` `<annotator>` |
| 5 | real PSI parser | `<lang.parserDefinition language="Python" implementationClass="com.jetbrains.python.PythonParserDefinition" />`; plus `<lang.ast.factory language="Python" …PythonASTFactory />` and `<stubElementTypeHolder class="com.jetbrains.python.PyStubElementTypes" externalIdPrefix="py." />` | implemented | `VelaParserDefinition.kt`, `VelaSyntax.kt`, `VelaNodeTypes.kt` | `[harness]` `ast-diff.ps1`: **114 files identical to `vm.exe parse`, 127,318 node lines**, exact string equality line for line, `VERDICT: PASS` with 0 different / 0 suspect / 0 missing / 0 crashed and 12 files the compiler refuses (all 12 of which this parser also refuses); plus a self-test that rejects a wrong tree (`6 cross pairs, 6 detected as different, 0 missed`). `[harness]` `psi-tree-diff.ps1`: **126 of 126 files** replayed through the platform's own `PsiBuilderImpl`, `failed 0`, `VERDICT : PASS`, `COVERAGE: ran 126 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0`. The single FAIL this row was `partial` for — `tests/build/lexer_error.vel`, an unterminated string — is fixed at its root: the scanner's closing newline claimed a character it had never read and is now zero-width at the failure offset (0.1.5 entry, item 1). **What is still not measured**: nothing drove this parser from a running IDE, so the row rests on those two headless differentials and not on an editor |
| 6 | syntax error highlighting | `<annotator language="Python" implementationClass="com.jetbrains.python.validation.PySyntaxAnnotator" />` | implemented | `VelaParserDefinition.kt` (`PsiBuilder.error` during the replay), `VelaAnnotator.kt` | `[harness]` `psi-tree-diff.ps1` asserts that a parser problem produces an error element in the platform tree (`VELA_ERROR` / `ERROR_ELEMENT`); files where the parser reported a problem: 12 (recovery is exercised, not avoided). `[harness]` VerifyPlugin §9: problems parsed out of the compiler's own output with kind, message and line |
| 7 | code completion (incl. after `.`) | 28 `<completion.contributor language="Python" …>` in `ce-plugin.xml`; member completion after `.` comes from `PyClassMembersProvider` / `pyModuleMembersProvider` | partial | `VelaCompletion.kt` | `[registration]` `<completion.contributor language="Vela">`. **No behavioural measurement exists**: nothing in `idea-plugin\` drives the completion contributor headlessly. This row is registered and compiled, and that is all that is claimed |
| 8 | hover documentation | `<lang.documentationProvider language="Python" id="pythonDocumentationProvider" implementationClass="com.jetbrains.python.documentation.PythonDocumentationProvider" />` (4 entries), plus `pythonDocumentationQuickInfoProvider` | implemented | `VelaDocumentation.kt` | `[registration]` `<lang.documentationProvider language="Vela">`. `[harness]` `HoverTruth`: **11,830 row(s) judged, wrong 0**, over 311 corpus files — every struct, field, `def` and parameter hovered at its declaration and compared against the compiler's own `parse` dump (names, `mut`, types, return types, nesting) and its `lex` dump for the line number; every builtin name against SPEC.md §8; and every identifier in the token stream that neither the file nor the language declares must hover as **nothing**, which is the axis that catches invented content (an earlier defect in this area copied parameter names out of the parentheses and produced `s: s: s:`). A parameter list the model cannot read prints no names at all rather than guessing. Falsification is part of the evidence: `--demand-hover <a name that does not exist>` fails and says the demand failed, and `--swap-params` reports exactly the declaration whose parameter order it reversed. What is still not measured: a running IDE's popup — the harness calls `documentationAt(text, offset)`, which is the PSI-free entry point the popup itself calls, so the rendering is not driven |
| 9 | parameter info | `<codeInsight.parameterInfo language="Python" implementationClass="com.jetbrains.python.PyParameterInfoHandler" />`; pro adds `keywordArgumentProvider` and `Pythonid.pyBddParametersInspection` | **partial** | `VelaParameterInfo.kt`, `VelaLanguage.kt` (`callAt`), `VelaTargets.kt` | `[harness]` `HintDiff` / `HintShapes` / `HintDupes` measure the same declaration reader the popup calls: `VelaParameterInfo.kt:56` and `VelaHints.parameterHints` both go through `VelaTargets.declaredParameterNames` / `builtinParameterNames`. `HintDiff`: **11,441 hints drawn, 11,441 correct, 0 wrong, 0 beyond the declared list**, `COVERAGE: ran 31188 / skipped 14 (compiler-cannot-parse 2, arg-boundary-disagreement 12) / wrong 0`; `HintDupes`: 0 callables with a repeated parameter name (the old `s: s: s:`), 0 empty names. **The row stays `partial`, and not for the 30 positions it used to be `partial` for**: that finding is not in the current run — which is not the same corpus either (29,006 argument positions then, 31,188 now), and what removed it is not recorded in the evidence I have. What keeps the row `partial` is the other half: the harness measures *the reader the popup calls*, and nothing drives the popup itself, so its rendering is not measured |
| 10 | go to declaration | `<gotoDeclarationHandler implementation="com.jetbrains.python.psi.impl.PyGotoDeclarationHandler" />` + `PyBreakContinueGotoProvider`; the resolution itself is `pyReferenceResolveProvider` with `PyForwardReferenceResolveProvider` | implemented | `VelaGotoDeclaration.kt`, `VelaTargets.kt` | `[harness]` `GotoOracle` (one `vm.exe check` per declaration rename, the binding set verified by the file compiling again): **26,077 references judged, 0 WRONG**, and the skip side is categorised rather than dropped |
| 11 | find usages / references | `<lang.findUsagesProvider language="Python" implementationClass="com.jetbrains.python.findUsages.PythonFindUsagesProvider" />`; `usageTypeProvider` in `py-plugin.xml` | partial | `VelaFindUsages.kt`, `VelaReferenceContributor` in `VelaGotoDeclaration.kt` | `[registration]` `<lang.findUsagesProvider language="Vela">`, `<psi.referenceContributor implementation="…VelaReferenceContributor">` (the attribute is `implementation`, proven in `PLUGIN_SURFACE.md`). The same reference is what `GotoOracle` drives, but the *usages window* itself is not measured |
| 12 | rename refactoring | Python relies on PSI references; `py-plugin.xml` adds `vetoRenameCondition` and `customUsageSearcher` | partial | `VelaLeafManipulator` in `VelaGotoDeclaration.kt`, reference from `VelaReferenceContributor` | `[registration]` `<lang.elementManipulator forClass="com.intellij.psi.PsiElement" implementationClass="…VelaLeafManipulator">`. The write-back is compiled and registered; no harness renames a file and reads it back |
| 13 | structure view | `<lang.psiStructureViewFactory language="Python" implementationClass="com.jetbrains.python.structureView.PyStructureViewFactory" />` | implemented | `VelaStructureView.kt`, `VelaPsiStructureViewFactory.kt`, `VelaModel.kt` | `[registration]` `<lang.psiStructureViewFactory language="Vela">`. `[harness]` the symbol list it renders is `VelaModel.symbols`, measured by `SymbolDiff` over 126 files (see row 14's note and the SymbolDiff section below) |
| 14 | problems / inspections / quick fixes | **98** `<localInspection language="Python" …>` in `ce-plugin.xml` (e.g. `PyUnusedLocalInspection`, `PyTypeCheckerInspection`), and the plugin declares `Pythonid.inspectionExtension` for others to add to | implemented | `VelaAnnotator.kt` (`VelaExternalAnnotator`), `VelaDiagnostics.kt` | `[harness]` VerifyPlugin §9/§10: the compiler is run end to end — `arith_basics.vel` accepted (0 problems), `truthiness.vel` refused (1 problem, correct kind and **line read from the compiler**, not guessed), and the same diagnostic 4 lines lower moves to line 7. `[registration]` **three** `<localInspection language="Vela">` entries — `VelaImmutableAssignment`, `VelaStringConcatenation`, `VelaIntFloatMixing` — with a quick fix each: insert `mut `, rewrite to the `concat(...)` builtin, and wrap the int operand in `to_float(...)`. Until those three lines existed the verifier refused the classes, reporting all three as implementing `LocalInspectionTool` with "no registration and no class file names it", so `build-offline.ps1` was FAILING rather than passing a feature nobody could reach. `[harness]` `InspectionProbe` against a frozen compiler over 311 corpus files: **`ran 257 / skipped 54 / wrong 0`**, **zero findings on the 131 files the compiler accepts**, 13 of 13 findings walked to a refusal the compiler itself makes on that exact line, and every fix leaves `check` at exit 0. The method matters: `vm.exe check` reports only its **first** refusal, so a finding is verified by fixing it and asking again, not by looking for a diagnostic on its line. Negative control: one word changed in a rule's criterion gives `wrong 41` and exit 1. **Category, not parity: three rules against Python's 98** — the row is `implemented` because the category exists, is registered, and is measured, not because the counts match. What is not measured: no IDE was started, so `LocalQuickFix.applyFix`'s write action over a live document and the alt-Enter menu are untested |
| 15 | code formatter | `<lang.formatter language="Python" implementationClass="com.jetbrains.python.formatter.PythonFormattingModelBuilder" />`, `codeStyleSettingsProvider`, `fileIndentOptionsProvider` | implemented | `VelaFormatter.kt`, `VelaFormatRules.kt`, `VelaCodeStyle.kt` | `[harness]` `FeatureProbe` §5, over 195 files and 120,468 tokens: **18,899 gaps in the corpus contain a line feed and 0 of them can be closed by this rule set** (every such gap either keeps line breaks or demands ≥1 line feed), 0 depth/spacing length mismatches, 0 negative depths. That is the property that separates reformatting from changing the program. `[registration]` `<lang.formatter>`, `<codeStyleSettingsProvider>`, `<langCodeStyleSettingsProvider>` |
| 16 | code folding | `<lang.foldingBuilder language="Python" implementationClass="com.jetbrains.python.PythonFoldingBuilder" />` | implemented | `VelaFolding.kt` | `[harness]` `FoldDiff` over 126 files: 64 identical, 38 differing only in granularity and 24 disagreeing about content, against the **retired** brace-matching reference (`[29,103)` the whole function body vs `[44,66)` an inner block). See "the two regression detectors" below — this is a comparison against this plugin's own predecessor, not against an authority |
| 17 | commenter | `<lang.commenter language="Python" implementationClass="com.jetbrains.python.PythonCommenter" />` | implemented | `VelaCommenter.kt` | `[harness]` `FeatureProbe` §3: the prefix is `#` and **all 3,805 comment tokens** in the corpus start with it, so `Ctrl+/` writes what the lexer reads. `[registration]` `<lang.commenter>` |
| 18 | brace matcher | `<lang.braceMatcher language="Python" implementationClass="com.jetbrains.python.PyBraceMatcher" />` | implemented | `VelaBraceMatcher.kt` | `[harness]` `FeatureProbe` §2: `getPairs()` declares `{}`(structural) `()` `[]`, and **every delimiter in the corpus** (`{` 3617, `}` 3616, `(` 8271, `)` 8270, `[` 2628, `]` 2628) is one of those types; 0 unmatched. `[registration]` `<lang.braceMatcher>` |
| 19 | typed handler / Enter auto-indent | `<typedHandler implementation="com.jetbrains.python.codeInsight.PyKeywordTypedHandler" id="pyCommaAfterKwd" />` and `<typedHandler implementation="com.jetbrains.python.editor.PythonSpaceHandler" />` (4 in `ce-plugin.xml`); Enter indentation is in the formatter | partial | `VelaTypedHandler.kt` | `[registration]` `<typedHandler implementation="…VelaTypedHandlerDelegate">` and `<enterHandlerDelegate implementation="…VelaEnterHandlerDelegate" order="first">`, both **not** language-keyed (they test the file name themselves, which is why `velaIsVelaFile` exists). Not measured headlessly: the code is a document edit and needs an editor |
| 20 | colour settings page | `<colorSettingsPage implementation="com.jetbrains.python.highlighting.PythonColorsPage" />` | implemented | `VelaColorsAndFontsPage.kt`, `VelaColorSettingsPage.kt`, `VelaHighlighting.kt` | `[registration]` `<colorSettingsPage implementation="…VelaColorsAndFontsPage">`; `[harness]` the keys it exposes are the same 14 `TextAttributesKey`s `FeatureProbe` §1 proves are actually used, because it reads them out of `VelaColors` rather than listing strings |
| 21 | live templates | `<defaultLiveTemplates file="liveTemplates/Python.xml" />` and 3 `<liveTemplateContext contextId="Python" …>` | implemented | `VelaLiveTemplates.kt`, `src\main\resources\liveTemplates\Vela.xml` | `[harness]` `FeatureProbe` §6: `liveTemplates/Vela.xml` is present **inside the built artifact** (`dist\vela\lib\vela-idea-plugin.jar`, 5,050 bytes, read back out of the zip), declares its templates, and every one is in the `VELA` context. `[registration]` `<defaultLiveTemplates>`, `<liveTemplateContext implementation="…VelaTemplateContextType">` |
| 22 | run configuration (+ Run action) | `<configurationType implementation="com.jetbrains.python.run.PythonConfigurationType" />` (8 in `ce-plugin.xml`), `<runConfigurationProducer implementation="com.jetbrains.python.run.PythonRunConfigurationProducer" />` (4 in `ce-plugin.xml`, 4 in `py-plugin.xml`), plus `runnerFactory` and `programRunner` | implemented | `VelaRunConfig.kt`, `VelaRunConfigurationProducer.kt` | `[registration]` `<configurationType implementation="…VelaRunConfigurationType">`, `<runConfigurationProducer implementation="…VelaRunConfigurationProducer">` — this is what makes IDEA's *own* Run menu offer a `.vel` file, instead of a private submenu. `[harness]` VerifyPlugin §10 builds and runs `examples/hello.vel` (exit 0, both fixed lines printed) and §11 reads the run path out of the class constant pool (no private console) |
| 23 | debugger | `<xdebugger.breakpointType implementation="com.jetbrains.python.debugger.PyLineBreakpointType" />` + `PyExceptionBreakpointType`; the plugin *declares* `Pythonid.debugSessionFactory`; and `python-dap.jar` is a Debug Adapter Protocol client: `PythonDapAttachConfigurationType`, `platform.dap.debugAdapterSupportProvider`, `platform.dap.launchArgumentsProvider`, and 8 `python.dap.run.debugpyConfigProvider` entries | **refused-deliberately** | `VelaRunConfig.kt` | `[registration]` `VelaRunConfiguration` implements `RunConfigurationWithSuppressedDefaultDebugAction` and accepts any profile that is **not** `Debug`, so IDEA shows the refusal instead of a Debug button that would attach to nothing. `vm.exe debug` is **not advertised anywhere** in `plugin.xml`, `.kt` sources, README or CHANGELOG except as this refusal. There is no breakpoint type, no debug session, no DAP client, and no claim of one |
| 24 | PSI / AST tree window | **No registration found.** A descriptor scan of all 429 jars in the installed platform's `lib\` finds **0** hits for `PSI Structure` (and 0 for `idea.is.internal`), with a control string (`lang.parserDefinition`) found 3 times by the same scan — so the platform's PSI viewer is not a descriptor-registered extension, and the Python plugin registers nothing for it | implemented (more than Python) | `VelaAstToolWindow.kt`, `VelaSyntaxDump.kt` | `[registration]` `<toolWindow id="Vela AST" factoryClass="…VelaAstToolWindowFactory">`. `[harness]` the format it prints is the compiler's own, held there by `ast-diff.ps1`; and the window has **two** modes — `Compiler (vm.exe parse)` reads the file on disk, `Live parse (editor buffer)` runs this plugin's parser over the editor's text so unsaved edits and never-saved files still have a tree |
| 25 | parameter-name inlay hints | `ce-plugin.xml` registers no parameter-name inlay provider: the 2 `codeInsight.declarativeInlayProvider` entries are Ruff-ish (`group="OTHER_GROUP"`, `providerId="RuffSuppressionCodes"` / `RuffTomlCodes`). Core Python parameter hints are not in this descriptor | implemented | `VelaInlayHints.kt` | `[harness]` `HintDiff` (11,441 hints drawn, 11,441 correct, 0 wrong), `HintShapes` (every label is a plain identifier, over at most 256 prefixes of every file), `HintDupes` (0 repeated parameter names). `[registration]` `<codeInsight.inlayProvider id="dev.vela.plugin.parameterNames" isEnabledByDefault="true">` |
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
| `implemented` | **21** | 1, 2, 3, 4, 5, 6, 8, 10, 13, 14, 15, 16, 17, 18, 20, 21, 22, 24, 25, 26, 27 |
| `partial` | **5** | 7 completion, 9 parameter info, 11 find usages, 12 rename, 19 typed/enter handler |
| `missing` | **0** | — |
| `refused-deliberately` | **1** | 23 debugger |
| total | **27** | `21 + 5 + 1 + 0 = 27` |

Row 5 moved from `implemented` to `partial` when its own verifier went red (`psi-tree-diff`
125 of 126, one FAIL) and moved back to `implemented` when that verifier went green —
`126 of 126`, `failed 0`, `VERDICT : PASS`, `COVERAGE: ran 126 / skipped 0 / wrong 0` — with
the FAIL's root cause fixed rather than the expectation restated. Row 9's first reason
(30 positions wrong, 125 unjudged) is not in the current run either, but that row stays
`partial` on the part no harness reaches: the popup's own rendering. A row is not
`implemented` while its own verifier is red; a green verifier is not by itself enough when
the capability's own surface was never driven.

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
as one. Both totals are non-zero, and as of 0.1.5 each total is split into classes, only
one of which per tool is a disagreement. (Until the previous round neither tool had ever
reached the end of the corpus: `harness.ps1 -Tool All` sat on `HintShapes` for over 50
minutes because its prefix sweep was O(size²) over a 400 KB file, so these two tools never
ran in an `All` pass at all and their absence from the evidence read as their agreement.
`HintShapes` now sweeps at most 256 prefixes per file — 5,793 runs in the current pass —
and an `All` pass completes in under four minutes.)

| tool | files compared | identical | differ | of which | what the differences are |
|---|---|---|---|---|---|
| `SymbolDiff` | 126 | 86 | **40** | 2 type spelling only, **13 same count / different content**, 25 count differs | the tree model lists `extern` function declarations (e.g. `sqrt(x: float) -> float` at `tests/build/extern/extern_c_probe.vel:15`, where the tree counts 5 declarations and the scan 2) that the retired token scan does not see at all — that is the 25-file `tree-reports-more-declarations` class, the tree gaining a declaration you can navigate to |
| `FoldDiff` | 126 | 64 | **62** | 38 granularity only, **24 content** | the tree folds a whole body from its opening `{` to its `}` (`[29,103)`, 5 lines, `tests/build/recursion_fib.vel`); the brace matcher folded only inner blocks (`[44,66)`, 2 lines). The tree's shape is the one PyCharm uses for a method body |

Only the bolded class of each row is a disagreement, and the tools say so in their own
verdict lines: `SymbolDiff` — `VERDICT: 13 file(s) have the same symbol count as the
retired token scan and different content, and 0 threw`; `FoldDiff` — `VERDICT: 24 file(s)
fold text the retired brace matcher does not fold at all, and 0 threw`. A
type-spelling-only, count-differs or granularity-only file is counted and printed and does
not fail the run: the two tools exit 1 for those two classes alone, which is why both
still exit 1 here.

Both are enumerated in full, file and region, in
`idea-plugin\evidence\detectors-0.1.5.txt` — git-visible, unlike the extracts under
`build\`. The honest statement is: **the shipping side is
the tree, the retired side is the reference, the difference is the replacement, and
whether each individual difference is an improvement has been judged by reading the
examples above rather than by measurement** — there is no external authority to
measure it against. What the tools *do* guarantee is that the difference set is
printed and counted, so it cannot change silently.

## The coverage triple for every harness, in one place

Every verifier in this plugin ends with `COVERAGE: ran N / skipped M (categorised) / wrong K`, and
every skip category is declared up front so a declared-and-zero category is printed rather than
omitted. From the `harness.ps1 -Tool All` pass on the 0.1.5 build, plus `ast-diff.ps1` /
`psi-tree-diff.ps1` and the `SymbolDiff`/`FoldDiff` re-run with the classes in it — the raw logs
are `idea-plugin\evidence\harness-0.1.5.txt`, `ast-diff-0.1.5.txt`, `psi-tree-diff-0.1.5.txt` and
`detectors-0.1.5.txt`:

| tool | exit | coverage triple | verdict |
|---|---|---|---|
| `ast-diff.ps1` | 0 | `ran 114 / skipped 12 (compiler-refused 12, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS -- 127,318 node lines identical |
| `psi-tree-diff.ps1` | 0 | `ran 126 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0` | PASS -- 126 of 126 files replayed |
| `GotoOracle` | 0 | `ran 26077 / skipped 22960 (crashed-file 0, compiler-refused 22951, invisible-member 9, ambiguous 0) / wrong 0` | clean, and no longer clean-by-omission |
| `HintDiff` | 0 | `ran 31188 / skipped 14 (compiler-cannot-parse 2, arg-boundary-disagreement 12) / wrong 0` | clean -- every hint names the parameter its declaration gives, and every declared parameter has a hint |
| `HintNames` | 0 | `ran 1781 / skipped 2 (threw 0, missing-corpus-file 0, no-model-entry-for-this-def 2, too-large 0, not-judgeable-in-a-refused-file 0) / wrong 0` | clean, with the refused-file case counted rather than called a disagreement |
| `HintShapes` | 0 | `ran 5793 / skipped 4 (parameterHints-threw 0, missing-corpus-file 0, too-large 0, too-large-for-prefix-sweep 4) / wrong 0` | clean -- 0 labels that are not plain identifiers |
| `HintDupes` | 0 | `ran 1781 / skipped 0 (symbols-threw 0, missing-corpus-file 0, too-large 0) / wrong 0` | clean -- no repeated or empty parameter name |
| `SymbolDiff` | 1 | `ran 86 / skipped 27 (threw 0, missing-corpus-file 0, too-large 0, type-spelling-only-difference 2, tree-reports-more-declarations 25, scan-reports-more-declarations 0) / wrong 13` | regression detector, above |
| `FoldDiff` | 1 | `ran 64 / skipped 38 (threw 0, missing-corpus-file 0, too-large 0, granularity-only-difference 38) / wrong 24` | regression detector, above |
| `FeatureProbe` | 0 | `ran 976 / skipped 0 (missing-corpus-file 0, too-large 0, empty-or-whitespace-only 0) / wrong 0` | clean -- six features that had only a registration |

`HintNames`'s one case, in full: ``tests/build/check_cases/unannotated_parameter.vel line 2 `f` tree=[] model=[n]``.
That file is a compiler-refused case -- `def f(n)` with an unannotated parameter -- so the tree
records no parameter and the symbol model reads `n` out of the detail text. On illegal Vela the
tree's reading is the defensible one, and the case is now counted as
`not-judgeable-in-a-refused-file` (printed even at zero, and zero in this pass) rather than as a
disagreement -- so the count reads 0 while the case stays named here.
## Open defects, with the raw verdict line for each

These are the entries that were **not** green, written down here rather than averaged
away. Each one names the tool, the corpus, the raw line, and what is actually wrong, and
the ones the 0.1.5 round closed say so in their own heading rather than being deleted.

### 0. The `since-build="253"` claim was false — FOUND AND FIXED this round

The README carried a promise ("the same two commands are re-run against the final
shape, and this block is updated with the result") that had never been kept. Keeping
it produced a failure, not a confirmation:

```
build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"
    kotlinc reported errors - each line below is `<file>:<line>: error: <message>`:
    VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'.
    ERROR: kotlinc failed
```

`ProcessOutputType.isSystem(Key)` exists in IntelliJ IDEA 2026.2 and **not** in
PyCharm 2025.3 (253), so the sources did not compile against the platform
`plugin.xml` declares, and `since-build="253"` was false. The line now asks the same
question with an identity comparison (`outputType === ProcessOutputType.SYSTEM`),
which exists on both platforms and cannot throw the way
`ProcessOutputType.fromKey` does. Both builds now pass from this same tree:

| toolchain | classpath | result | jar | zip | zip sha256 |
|---|---|---|---|---|---|
| PyCharm 2025.3 (253) | 139 jars | `RESULT: PASS` | 344,574 | 324,147 | `b43c480b71e59843b85002e183cc1aa04f38b4eeb03d8926ef9d5bccda206fc2` |
| IntelliJ IDEA 2026.2 (262) | 429 jars | `RESULT: PASS` | 344,619 | 324,186 | `a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033` |

The 262 zip is the shipped artifact (`dist\vela-idea-plugin-0.1.4.zip`); the 253 one is
kept for the record at `build\evidence\artifact-0.1.4-253\`. This is the one defect in
this list that is **closed**, and it was closed by keeping a promise rather than by
weakening one.

### 1. `psi-tree-diff.ps1`: 126 of 126 — an unterminated string broke the replay's own invariant (row 5, FIXED in 0.1.5)

```
0.1.5 pass (`idea-plugin\evidence\psi-tree-diff-0.1.5.txt`):
         files replayed through the platform's builder : 126 of 126 in the corpus
         failed 0
         VERDICT : PASS
         COVERAGE: ran 126 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0

0.1.4 pass, the FAIL this entry was written for:
RAW  :   tests/build/lexer_error.vel: leaf VELA_STRING at 31..38 is not a token this
         parser claimed: `"hello)`
         ok 125   failed 1
         VERDICT : FAIL
         COVERAGE: ran 125 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0)
```

`tests/build/lexer_error.vel` is the file that `tests/cases.txt:174` had been naming
while it did not exist — another track created it during this round, and it immediately
found this. Its content is a deliberately unterminated string, and on it the scanner
refuses to tokenise; the parser therefore keeps only the tokens before the failure,
while `VelaLexer` produces one `VELA_STRING` token for the whole of `"hello)`. The
replay's alignment (`alignEndAfter`, which snaps a node's end up to the lexer's token
boundary so that a string element gets the text it denotes) then pulls that token into
the tree, where `psi-tree-diff` asserts that every non-trivia leaf is a token the
parser claimed — and it is not.

**The root cause was the scanner's, and it is fixed.** The token list a failed scan keeps
was closed with `newline(pos)` — a **one-character-wide** token at the offset the scan
stopped at, which on an unterminated string is the opening quote. The parser's list
therefore claimed both `31..32` and `31..36`, and the largest end among its tokens — the
newline's 32, not the string's 31 — made a leaf starting at exactly 31 fall inside "every
non-trivia leaf must be a token the parser claimed". The closing newline is now zero-width
at the failure offset (`VelaSyntax.kt:559-561`, `VelaTok(NEWLINE, pos, pos, …)`): still a
token, still a statement boundary for a truncated statement, and it claims no character.
126 of 126, `failed 0`, exit 0 — the root cause fixed rather than the expectation restated
with a reason.

**What is still not measured**: the parse agreeing with the compiler's, and the replay
tiling the text, say nothing about an editor rendering it — no IDE was launched, and row
5's evidence column says that in place.

Note the shape of this one: the file was a `missing-corpus-file` defect an hour before the
FAIL, and the FAIL is what a corpus entry pointing at nothing had been hiding.

### 2. `HintDiff`: 30 argument positions got no parameter-name hint — gone from the 0.1.5 run (row 9 keeps `partial` for another reason)

```
0.1.5 pass (`idea-plugin\evidence\harness-0.1.5.txt`):
RAW      : VERDICT: every hint names the parameter the compiler declares for that argument,
             and every declared parameter has a hint
           hints drawn 11441 / correct 11441 / WRONG 0 / beyond 0
           COVERAGE: ran 31188 / skipped 14 (compiler-cannot-parse 2,
             arg-boundary-disagreement 12) / wrong 0

0.1.4 pass, the finding this entry was written for:
TOOL     : HintDiff  (via harness.ps1 -Tool All)
CORPUS   : tests + examples + ide-demo + selfhost/parts + bench + selfhost/vela.vel
           = 194 files, 29,006 argument positions
RAW      : VERDICT: 30 hint position(s) are not right
           COVERAGE: ran 29006 / skipped 127 (compiler-cannot-parse 2,
             arg-boundary-disagreement 125) / wrong 30
```

Everything that *is* drawn is right — `hints drawn 10688 / correct 10688 / WRONG 0 /
beyond 0` in that run, `11441 / 11441 / 0 / 0` in the current one — so this was never the
`s: s: s:` class of bug and never a wrong name. It was 30 positions where a declared
parameter should have a hint and nothing was drawn. The findings, verbatim:

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

**Every occurrence was in `selfhost/parts/*`** (and the same text again in
`selfhost/vm.vel`, which is their concatenation), so it was the language's own 400 KB of
source that showed it — nothing in `examples/` or `tests/build/` did.

**Superseded, and the cause is not established.** The current run has no such position
(`wrong 0`) and the unjudged class fell from 125 to 12, but the two runs are not over the
same corpus (29,006 argument positions then, 31,188 now) and nothing in the evidence
records *which* change removed the 30. `VelaInlayHints.kt` — the file this entry
suspected — is not among the files the 0.1.5 round modified, while `selfhost/parts/*.vel`,
where every one of the 30 was, is. So this is recorded as "the finding is not in the
current run", not as "fixed here".

### 3. `HintDiff`: argument positions the harness cannot judge — 125 in the 0.1.4 run, 12 now

```
0.1.5: COVERAGE: ... skipped 14 (compiler-cannot-parse 2, arg-boundary-disagreement 12) ...
0.1.4: COVERAGE: ... skipped 127 (compiler-cannot-parse 2, arg-boundary-disagreement 125) ...
```

Measured with `--explain`, which prints the disagreement instead of asserting one (the
rows below are from the 0.1.4 run):

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
whether the position's own hint is present. It is counted rather than dropped
(`arg-boundary-disagreement 125` then, `12` now), and it is no longer why row 9 is
`partial` — the unmeasured popup is. What keeps this entry is that 12 is not 0.

**Why this is not just the harness.** The first version of this check compared
offsets exactly and reported 3,073 findings — those were the harness being wrong
about string literals (a string literal's node covers its contents, the hint engine
reports the opening quote). The second version widened the window to the whole
argument span and reported 2,392 — those were nested calls, where an inner argument's
hint falls inside an outer argument's span (`print(len(a))`). Both were found by
reading the raw rows, and both are written into the source as comments so the next
change does not re-introduce them. The 30 that remained were not explained by either, and
are not in the current run.

### 4. The verifier's remaining hole, and the one it had

`mutation-test.ps1` proves `VerifyPlugin` can fail: **13 mutants, 12 caught, 0 holes,
1 control correct, 0 script/verdict problems**, baseline `RESULT: PASS`, published to
`build\verify\mutation-0.1.5\mutation-report-0.1.5.txt` with the version, the jar's
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
published to `build\verify\negative-0.1.5\negative-report.txt`. Getting there required
fixing two real verifier holes, both found by the negative test rather than by reading:

| hole | what it looked like | fix |
|---|---|---|
| `check smoke` aborted the whole compiler section with an early `return`, so `emitterHeaderContract` — a static comparison of two texts that needs no C compiler — never ran when the build failed, which is exactly when it matters | case I4 reported MISSED while the verifier had exited 1 for a different reason | the build result is remembered; only the checks that need a built executable are skipped |
| `emitterHeaderContract` asked `header.contains(symbol)`, a *substring* test, so renaming `vela_bounds_check` to `vela_bounds_check_renamed` still "contained" the name the emitter calls | case I4 still MISSED, and the verifier printed `vela_bounds_check ... all defined` over a header that no longer declares it | whole-word match (`\bsym\b`), so a prefix or a suffix is not a declaration |

Both are the same class of failure this project keeps paying for: a check that is green
because its question was weaker than it looked.

### 5. `GotoOracle`: the `extern` NullPointerException — fixed, and counted

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

### 6. The corpus named a file that was not there — now fixed by another track

```
COVERAGE: ran 114 / skipped 12 (compiler-refused 12, too-large 0,
          missing-corpus-file 0, compiler-crashed 0) / wrong 0
VERDICT : PASS   (126 files in the corpus, 121,856 node lines identical)
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
