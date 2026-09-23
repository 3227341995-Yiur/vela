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
| 7 | code completion (incl. after `.`) | 28 `<completion.contributor language="Python" …>` in `ce-plugin.xml`; member completion after `.` comes from `PyClassMembersProvider` / `pyModuleMembersProvider` | implemented | `VelaCompletion.kt` | `[registration]` `<completion.contributor language="Vela">`. `[harness]` **`PlatformEntry` section row 7**, 0.1.8, same frozen compiler (`evidence\platform-entry-0.1.8-20260924-0546.txt`): it calls `VelaCompletionContributor.fillCompletionVariants` with the platform's own `CompletionParameters` and a recording `CompletionResultSet`, and `LookupElement.handleInsert` in a real `InsertionContext` over a real `OffsetMap` — **2117 positions judged, wrong 0**, `COVERAGE: ran 2117 / skipped 139 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, receiver-type-not-written 0, receiver-not-a-struct 0, receiver-not-a-written-binding 1, insert-not-a-callable 0, template-names-not-in-spec 138, contributor-threw 0) / wrong 0`. Three families, each held to the compiler's own dump: `bare-end` (138 files: every keyword `SPEC.md` 1.3 lists, every builtin whose section 8 row documents parameter names, and every module-level name `vm.exe parse` declares must be offered, and no offered name may be one nothing declares), `after-dot` (194 positions: exactly the members the dump lists for that struct, for the four receiver kinds the model resolves), `insert` (1785: the written template names the declaration's own parameters in order, and the caret lands inside what it wrote). **Two defects, found by measuring and both fixed**: (1) in 0.1.7, a file declaring `extern c def abs(x: float)` was offered the language's builtin `abs(n)` *first* and the dedupe then suppressed the file's own declaration, so the inserted call named `n` (4 positions against 0.1.6; `VelaCompletion.offer` now skips a builtin whose name the file declares as a function, method or struct, which is the rule `VelaTargets.declaredParameterNames` and `VelaInlayHints` already followed); (2) in 0.1.8, **a local binding with a written type got no member completion at all** — `q: Vec2 = Vec2(1.0, 2.0)` followed by `q.` offered nothing, because `VelaNames.structTypeOf` resolved only `self`, parameters and struct names, and `VelaModel` declares structs, defs, fields, methods and parameters and no locals. `VelaTargets.localBindingType` now reads the tree's own `decl` node, innermost block first, so a rebinding in an inner block shadows an outer name the way the compiler's own rule (SPEC.md 6.2) says it does. Measured with the same harness and the same frozen compiler, the plugin build being the only difference: **`after-dot` 194 judged / 28 wrong on the 0.1.7 jar** ("the compiler declares [x, y, dot, scale]; not offered [x, y, dot, scale]"), **0 on 0.1.8**. **Counted rather than called wrong**: 1 position whose receiver is a *field* the compiler's dump types and no binding writes beside a name (`inner.` where `inner` is a member of the enclosing struct — the plugin answers nothing there by design, and the class prints the declaration's own node kinds), and 138 files where the plugin's builtin table holds a name section 8 documents no parameter names for. **What is still not measured: the popup itself** — the recording result set does not filter by prefix, sort or draw — so this row claims what the contributor offers and what its insert handler writes, not how the popup looks |
| 8 | hover documentation | `<lang.documentationProvider language="Python" id="pythonDocumentationProvider" implementationClass="com.jetbrains.python.documentation.PythonDocumentationProvider" />` (4 entries), plus `pythonDocumentationQuickInfoProvider` | implemented | `VelaDocumentation.kt` | `[registration]` `<lang.documentationProvider language="Vela">`. `[harness]` `HoverTruth`: **11,830 row(s) judged, wrong 0**, over 311 corpus files — every struct, field, `def` and parameter hovered at its declaration and compared against the compiler's own `parse` dump (names, `mut`, types, return types, nesting) and its `lex` dump for the line number; every builtin name against SPEC.md §8; and every identifier in the token stream that neither the file nor the language declares must hover as **nothing**, which is the axis that catches invented content (an earlier defect in this area copied parameter names out of the parentheses and produced `s: s: s:`). A parameter list the model cannot read prints no names at all rather than guessing. Falsification is part of the evidence: `--demand-hover <a name that does not exist>` fails and says the demand failed, and `--swap-params` reports exactly the declaration whose parameter order it reversed. What is still not measured: a running IDE's popup — the harness calls `documentationAt(text, offset)`, which is the PSI-free entry point the popup itself calls, so the rendering is not driven |
| 9 | parameter info | `<codeInsight.parameterInfo language="Python" implementationClass="com.jetbrains.python.PyParameterInfoHandler" />`; pro adds `keywordArgumentProvider` and `Pythonid.pyBddParametersInspection` | implemented | `VelaParameterInfo.kt`, `VelaLanguage.kt` (`callAt`), `VelaTargets.kt` | `[harness]` `HintDiff` / `HintShapes` / `HintDupes` measure the declaration reader the popup shares with the inlay hints (`VelaTargets.declaredParameterNames` / `builtinParameterNames`), re-measured on 0.1.8: `HintDiff` `COVERAGE: ran 33676 / skipped 122 (compiler-cannot-parse 95, arg-boundary-disagreement 12, compiler-refused-the-file 15, check-timed-out 0) / wrong 0`, `HintShapes` `ran 9049 / skipped 4 (parameterHints-threw 0, missing-corpus-file 0, too-large 0, too-large-for-prefix-sweep 4) / wrong 0` over 251,022 labels, `HintDupes` `ran 1977 / skipped 0 (symbols-threw 0, missing-corpus-file 0, too-large 0) / wrong 0`. `[harness]` **`PlatformEntry` section row 9** drives the handler itself (`evidence\platform-entry-0.1.8-20260924-0546.txt`, 0.1.8): `findElementForParameterInfo` → `showParameterInfo` → `findElementForUpdatingParameterInfo` → `updateParameterInfo` (`setCurrentParameter`) → `updateUI`, with stand-ins for the three context interfaces — **3359 positions judged, wrong 0** in four families (`find` 684, `show` 663, `update` 1349, `update-rebuilt` 663), `COVERAGE: ran 3359 / skipped 620 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, callee-not-a-declared-def 599, receiver-not-a-written-binding 0, receiver-on-a-function 0, method-call-without-receiver 0, spec-row-documents-no-names 0, plugin-claims-no-names 0, parameter-list-untrusted 0, struct-constructor-popup-closed 21, rebuilt-anchor-unavailable 0) / wrong 0`. The two axes the row was `partial` for are the ones it judges: the index `setCurrentParameter` is given must name the parameter the *compiler* binds that argument to (a receiver expression is a method's first parameter; the parentheses' arguments are the rest, which is what `vm.exe parse` prints), and the string `updateUI` draws must be the declaration's own names in order with the emphasis covering exactly that argument. **Three defects, all found by measuring and all fixed**: (1) and (2) in 0.1.7 — the drawn list and the index counted different things for a method call (the popup drew `self, o -> float` and emphasised `self` for the caret in `q`, which the compiler binds to `o`, and `p.manhattan()` emphasised `self` for a call with no argument at all; 5 emphasis and 3 draw wrongs once the other defect was fixed), and the emphasis never followed the caret at all (`updateUI` drew from an index only `findElementForParameterInfo` had set, while `callAt(caret)` reads the caret's own line, so on the second line of a multi-line argument list the index stayed 0; 690 emphasis and 23 index wrongs) — the receiver's parameter is now dropped exactly as `VelaInlayHints.parameterHints` drops it, the call's `(` travels on the item, and the index is written back so the drawing sees it; (3) in 0.1.8 — **the `objectsToView` half of `findElementForUpdatingParameterInfo` was unreachable code**, because the items the platform hands back are `ParameterHint`s and the code cast them to `PsiElement`, which cannot succeed, so after any edit the rebuilt anchor leaf was never found and the popup closed. It now looks for the item's own `(` again, checks that the offset still holds that `(`, puts the hint back on the leaf it finds, and answers `null` when `objectsToView` is empty. The new `update-rebuilt` family rebuilds the anchor leaf for every judged call and holds the handler to exactly that: **663 judged / 663 wrong on the 0.1.7 jar with the same harness and the same frozen compiler, 0 on 0.1.8**. **Counted rather than called wrong**: 599 positions whose callee is not a `def` this file declares and not a builtin section 8 documents (`print(`, `to_float(` — no oracle writes a parameter list for it), 21 `Vec2(…)` struct-constructor calls (the model opens no popup for a struct's own name, printed with the field list the compiler declares), and any position whose parameter list the item carries as null, where the popup is disabled by design rather than drawn with names that are not there. The 6 method calls whose receiver is a local binding are judged from 0.1.8 as well, and they are clean on *both* builds — the by-name method fallback happened to name the right method — so the class disappeared because the *reader* resolves the receiver now, which is said here rather than claimed as a third wrong answer. **What is still not measured**: the platform's own `ParameterInfoControllerBase` (this tool calls the five methods it calls, in its order, with stand-ins for `CreateParameterInfoContext`, `UpdateParameterInfoContext` and `ParameterInfoUIContext`); the `objectsToView` fallback is no longer in that list — the `update-rebuilt` family is what drives it |
| 10 | go to declaration | `<gotoDeclarationHandler implementation="com.jetbrains.python.psi.impl.PyGotoDeclarationHandler" />` + `PyBreakContinueGotoProvider`; the resolution itself is `pyReferenceResolveProvider` with `PyForwardReferenceResolveProvider` | implemented | `VelaGotoDeclaration.kt`, `VelaTargets.kt` | `[harness]` `GotoOracle` (one `vm.exe check` per declaration rename, the binding set verified by the file compiling again): **26,077 references judged, 0 WRONG**, and the skip side is categorised rather than dropped. The shadowing case of the same resolver — Ctrl+Click on an inner `x` jumping to the outer one — is measured separately in `evidence\inner-first-scope-0.1.6.txt`: `VelaTargets.declarationFor` case 3 now walks the ancestor chain inner-first, and that file states its own limit (the case could not be made to exit non-zero, because the inner `x` is itself compile-silent, so the row disappears instead of turning red) |
| 11 | find usages / references | `<lang.findUsagesProvider language="Python" implementationClass="com.jetbrains.python.findUsages.PythonFindUsagesProvider" />`; `usageTypeProvider` in `py-plugin.xml` | implemented | `VelaFindUsages.kt`, `VelaReferenceContributor` in `VelaGotoDeclaration.kt` | `[registration]` `<lang.findUsagesProvider language="Vela">`, `<psi.referenceContributor implementation="…VelaReferenceContributor">` (the attribute is `implementation`, proven in `PLUGIN_SURFACE.md`). `[harness]` **Two independent measurements of the same table, on 0.1.6 and the same frozen compiler** (`vm.exe` 905,216 bytes, sha256 `feeb0271…`). `RenameOracle` asks the compiler about one occurrence at a time — rename the declaration and every candidate *except* that one, and a refusal means the compiler binds it: **853 declarations judged, 2,855 bound uses, 0 MISSED, 0 WRONG-SCOPE, 4 claimed-but-unprovable, wrong 0**, `COVERAGE: ran 853 / skipped 5543 (compiler-refused 5363, too-large 0, missing-corpus-file 0, compiler-silent 29, not-attributable 151, unverified-lines 0, unverifiable-basis 0, too-many-candidates 0, new-name-refused 0, crashed 0, contradiction 0) / wrong 0`; the same run holds the results view's own decisions to the compiler (`canSearchAt` on every bound use and on the declaration's own name, and the label `typeAt` returns — the `NOT-SEARCHABLE` and `LABEL` findings, 0). `RenameWriteback` (`evidence\rename-writeback-0.1.6-20260924-0445.txt`) drives the provider **itself** over copies of real corpus files: 782 declarations, 1,675 claimed references, and **leaving any one of them unwritten is refused by `vm.exe check` for 1,667** of the 1,675 — the other 8 being compile-silent positions that are counted and printed rather than called wrong (`b: P = a` is one shape, a shadowed inner declaration whose use would rebind to the outer same-typed one is the other). The shadowing half of the same table has its own measurement, `evidence\inner-first-scope-0.1.6.txt` (case 3 of `VelaTargets.declarationFor` now walks the ancestor chain inner-first; `NOT-REQUIRED` 5 → 4 and the `scope_shadow_across_blocks_ok.vel:5 'x'` row gone). **What is still not measured: the usages window's own rendering** — no IDE was launched — which is the same limit row 8 states for the hover popup |
| 12 | rename refactoring | Python relies on PSI references; `py-plugin.xml` adds `vetoRenameCondition` and `customUsageSearcher` | implemented | `VelaLeafManipulator` in `VelaGotoDeclaration.kt`, reference from `VelaReferenceContributor` | `[registration]` `<lang.elementManipulator forClass="com.intellij.psi.PsiElement" implementationClass="…VelaLeafManipulator">`. `[harness]` **`RenameWriteback`, which renames and reads the file back from disk** (`evidence\rename-writeback-0.1.6-20260924-0445.txt`): it drives the plugin's own write path — `VelaReferenceProvider` → `VelaReference.resolve()` → `VelaReference.handleElementRename` for every usage, `VelaLeafManipulator.handleContentChange` for the declaration, in the platform's reverse-document order — over a *copy* of each real corpus file, through the plugin's own `document.replaceString(start, end, newName)` calls, and then asks the compiler about the file it produced. **782 declarations, 1,675 claimed references, 2,457 writes landed of 2,457 attempted, the file read back compiles for 782 of 782, the two write paths agree for 782 of 782, `wrong 0`**; the recorded write log *is* the write set (every range a whole identifier leaf of the right length, every replacement the new name, nothing else written), the file read back is the original with exactly those ranges replaced, and the token-by-token comparison over both texts (plugin lexer) finds no token outside the write set that changed. **The falsification is measured rather than asserted**: renaming the *declaration alone* through the same write path is refused by the compiler for 781 of the 782 — and the tool exits 3 if that count is ever 0, so a rename that silently wrote nothing could not pass. Making the rename *set* the same test (leave one reference out and the compiler must refuse) holds for 1,667 of the 1,675; the 8 that compile anyway are the same compile-silent positions row 11 counts. **What is still not measured**: the platform's rename dialog and its processor (`vetoRenameCondition`, the search-in-comments options) and the undo step — the `CommandProcessor` is a stand-in that runs the command inline — so the plugin's write is measured end to end and the IDE around it is not |
| 13 | structure view | `<lang.psiStructureViewFactory language="Python" implementationClass="com.jetbrains.python.structureView.PyStructureViewFactory" />` | implemented | `VelaStructureView.kt`, `VelaPsiStructureViewFactory.kt`, `VelaModel.kt` | `[registration]` `<lang.psiStructureViewFactory language="Vela">`. `[harness]` the symbol list it renders is `VelaModel.symbols`, measured by `SymbolDiff` over 126 files (see row 14's note and the SymbolDiff section below) |
| 14 | problems / inspections / quick fixes | **98** `<localInspection language="Python" …>` in `ce-plugin.xml` (e.g. `PyUnusedLocalInspection`, `PyTypeCheckerInspection`), and the plugin declares `Pythonid.inspectionExtension` for others to add to | implemented | `VelaAnnotator.kt` (`VelaExternalAnnotator`), `VelaDiagnostics.kt` | `[harness]` VerifyPlugin §9/§10: the compiler is run end to end — `arith_basics.vel` accepted (0 problems), `truthiness.vel` refused (1 problem, correct kind and **line read from the compiler**, not guessed), and the same diagnostic 4 lines lower moves to line 7. `[registration]` **three** `<localInspection language="Vela">` entries — `VelaImmutableAssignment`, `VelaStringConcatenation`, `VelaIntFloatMixing` — with a quick fix each: insert `mut `, rewrite to the `concat(...)` builtin, and wrap the int operand in `to_float(...)`. Until those three lines existed the verifier refused the classes, reporting all three as implementing `LocalInspectionTool` with "no registration and no class file names it", so `build-offline.ps1` was FAILING rather than passing a feature nobody could reach. `[harness]` `InspectionProbe` against a frozen compiler over 311 corpus files: **`ran 257 / skipped 54 / wrong 0`**, **zero findings on the 131 files the compiler accepts**, 13 of 13 findings walked to a refusal the compiler itself makes on that exact line, and every fix leaves `check` at exit 0. The method matters: `vm.exe check` reports only its **first** refusal, so a finding is verified by fixing it and asking again, not by looking for a diagnostic on its line. Negative control: one word changed in a rule's criterion gives `wrong 41` and exit 1. **Category, not parity: three rules against Python's 98** — the row is `implemented` because the category exists, is registered, and is measured, not because the counts match. What is not measured: no IDE was started, so `LocalQuickFix.applyFix`'s write action over a live document and the alt-Enter menu are untested |
| 15 | code formatter | `<lang.formatter language="Python" implementationClass="com.jetbrains.python.formatter.PythonFormattingModelBuilder" />`, `codeStyleSettingsProvider`, `fileIndentOptionsProvider` | implemented | `VelaFormatter.kt`, `VelaFormatRules.kt`, `VelaCodeStyle.kt` | `[harness]` `FeatureProbe` §5, over 195 files and 120,468 tokens: **18,899 gaps in the corpus contain a line feed and 0 of them can be closed by this rule set** (every such gap either keeps line breaks or demands ≥1 line feed), 0 depth/spacing length mismatches, 0 negative depths. That is the property that separates reformatting from changing the program. `[registration]` `<lang.formatter>`, `<codeStyleSettingsProvider>`, `<langCodeStyleSettingsProvider>` |
| 16 | code folding | `<lang.foldingBuilder language="Python" implementationClass="com.jetbrains.python.PythonFoldingBuilder" />` | implemented | `VelaFolding.kt` | `[harness]` `FoldDiff` over 126 files: 64 identical, 38 differing only in granularity and 24 disagreeing about content, against the **retired** brace-matching reference (`[29,103)` the whole function body vs `[44,66)` an inner block). See "the two regression detectors" below — this is a comparison against this plugin's own predecessor, not against an authority |
| 17 | commenter | `<lang.commenter language="Python" implementationClass="com.jetbrains.python.PythonCommenter" />` | implemented | `VelaCommenter.kt` | `[harness]` `FeatureProbe` §3: the prefix is `#` and **all 3,805 comment tokens** in the corpus start with it, so `Ctrl+/` writes what the lexer reads. `[registration]` `<lang.commenter>` |
| 18 | brace matcher | `<lang.braceMatcher language="Python" implementationClass="com.jetbrains.python.PyBraceMatcher" />` | implemented | `VelaBraceMatcher.kt` | `[harness]` `FeatureProbe` §2: `getPairs()` declares `{}`(structural) `()` `[]`, and **every delimiter in the corpus** (`{` 3617, `}` 3616, `(` 8271, `)` 8270, `[` 2628, `]` 2628) is one of those types; 0 unmatched. `[registration]` `<lang.braceMatcher>` |
| 19 | typed handler / Enter auto-indent | `<typedHandler implementation="com.jetbrains.python.codeInsight.PyKeywordTypedHandler" id="pyCommaAfterKwd" />` and `<typedHandler implementation="com.jetbrains.python.editor.PythonSpaceHandler" />` (4 in `ce-plugin.xml`); Enter indentation is in the formatter | implemented | `VelaTypedHandler.kt` | `[registration]` `<typedHandler implementation="…VelaTypedHandlerDelegate">` and `<enterHandlerDelegate implementation="…VelaEnterHandlerDelegate" order="first">`, both **not** language-keyed (they test the file name themselves, which is why `velaIsVelaFile` exists). `[harness]` **`PlatformEntry` §row 19** (`evidence\platform-entry-0.1.7-20260924-0520.txt`, 0.1.7) drives both handlers with an editor stood in for (a document that records every write, a caret the plugin moves, a recorded selection — the same stand-in `RenameWriteback` uses for `Document`, and for the same measured reason): **8329 positions judged, wrong 0**, `COVERAGE: ran 8329 / skipped 1107 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, closer-not-required 0, prefix-not-a-complete-program 40, indent-not-the-corpus-step 35, first-line 347, line-ends-with-brace 685) / wrong 0`. `charTyped`: the compiler's own `vm.exe lex` decides which family a position is — a real bracket is a one-character punctuation token starting exactly there, a character inside a string is covered by a string token's contents, and a `{` inside a comment or a closing quote is covered by no token at all — giving 3037 positions where exactly one closer must be written at the caret, 184 where the corpus's own closer was deleted and the text read back had to be the corpus file byte for byte, 138 of those where `vm.exe check` refuses the text without it (the falsification: a handler that wrote nothing cannot pass), and 1351 where nothing may be written (the closer is already next, the character is inside a string or a comment, or the file is not a `.vel` file). `postProcessEnter`: 723 block-open positions (the document is the file's prefix up to the `{` plus the line feed the platform wrote; the written body line, closing brace and caret are computed from the corpus's own line, and the compiler refuses the input and accepts the result for 138 of them) and 2758 positions on a new empty line above a real one, where the indentation written must be the indentation that line itself has — indentation is not semantic in Vela, so the compiler cannot judge it, and the corpus's own step was measured first (4 spaces, 3450 of 4244 positive deltas). **No defect was found in this row.** **What is not measured**: no IDE was started, so the claim is the decision (which characters, where, and what the caret/selection becomes) and not that a keystroke on screen produces it; the platform's own `EnterHandlerDelegate` chain is not run, and the indent step is `VelaFormatRules.INDENT_SIZE` rather than the reader's code-style setting, as the class's own documentation says |
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
| `implemented` | **26** | 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24, 25, 26, 27 |
| `partial` | **0** | — |
| `missing` | **0** | — |
| `refused-deliberately` | **1** | 23 debugger |
| total | **27** | `26 + 0 + 1 + 0 = 27` |

Row 5 moved from `implemented` to `partial` when its own verifier went red (`psi-tree-diff`
125 of 126, one FAIL) and moved back to `implemented` when that verifier went green —
`126 of 126`, `failed 0`, `VERDICT : PASS`, `COVERAGE: ran 126 / skipped 0 / wrong 0` — with
the FAIL's root cause fixed rather than the expectation restated. Rows 11 and 12 moved from
`partial` to `implemented` in the same way, on a measurement built for exactly the reason
they were `partial`: the registration and the *resolution* were measured and nothing had
ever renamed anything and read the file back, so `RenameWriteback` now drives the plugin's
own write path over copies of real corpus files and reads them back from disk, with the
compiler's own refusal of a declaration-only rename as the falsification (row 12's evidence
column). Rows 7, 9 and 19 were the last three `partial` rows, and their reason was the same one
axis three times: the row is about an object the *platform* instantiates, and no harness had ever
instantiated one. The round that closed them is the one that did: `PlatformEntry` calls
`fillCompletionVariants` and `handleInsert` for row 7, the handler's five steps for row 9, and
`charTyped` / `postProcessEnter` for row 19, with stand-ins only where the platform demands an
object a headless run cannot have. Row 9 in particular is measured on the two decisions it was
`partial` for — the index `setCurrentParameter` is given and the string `updateUI` draws with the
emphasis — and the 30 mis-hinted positions it was once `partial` for stay out of it: they are not
in the current run, on a corpus that is not the same either. **The measurement was red when it
first ran** (4 wrong positions in row 7, 1009 in row 9), and the three defects it found are fixed
in 0.1.7 rather than restated as an expectation — the before/after lines are in
`evidence\platform-entry-0.1.7-20260924-0520.txt` and in the `## Open defects` entry 8 below. A
row is not `implemented` while its own verifier is red; a green verifier is not by itself enough
when the capability's own surface was never driven; and a surface that *is* driven is not
`implemented` until the defects it finds are fixed or named.

**0.1.8 moves no row and changes no count.**  What it did to this file is what the round before it
promised: it fixed the limits the 0.1.7 runs *counted* and printed rather than called wrong, and
re-measured every number on the artifact it produced.  Two of those limits turned out to be hiding
defects rather than gaps, which is only visible because the same harness was run against both
plugin builds with the same frozen compiler:

* the local-binding reader (`VelaTargets.localBindingType`) moved 28 of row 7's 29 counted
  positions and all 6 of row 9's into judged positions — and on the 0.1.7 jar those 28 are
  **wrong** (`after-dot 194 judged / 28 wrong`: "the compiler declares [x, y, dot, scale]; not
  offered [x, y, dot, scale]") against **0** on 0.1.8, so the counted class was hiding a
  user-visible hole and not a measurement gap;
* the `objectsToView` half of `findElementForUpdatingParameterInfo` is now a measured path instead
  of a fossil: the new `update-rebuilt` family rebuilds the anchor leaf and requires the handler to
  find it again — **663 judged / 663 wrong** on the 0.1.7 jar against 0 on 0.1.8.

Both before/after pairs, with the raw lines from both builds, are in
`evidence\platform-entry-0.1.8-20260924-0546.txt`.  The one position that stays counted is honest
about what it is: `inner.` where `inner` is a *field* of the enclosing struct, a receiver whose
type the compiler's dump holds and no binding writes beside the name — `receiver-not-a-written-binding`,
1 position, named rather than judged.

### Rows backed by a harness vs by a registration only

| evidence behind the row | rows | which |
|---|---|---|
| `[registration]` **and** `[harness]` | **20** | 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 25 |
| `[registration]` only | **6** | 1, 8, 23, 24, 26, 27 |
| `[harness]` only, no registration | **0** | — |

**20** rows have both, **6** have a registration only, and row 23 is the
`refused-deliberately` one whose evidence is the registration of the *suppression*:
`20 + 6 + 1 = 27`. Rows 11 and 12 were in the second group and are in the first one now,
because the harness that moved them (`RenameWriteback`) drives the capability's own code —
the provider, the reference and the manipulator — rather than a reader the capability
happens to share. Rows 7 and 19 left that group this round for the same reason and by the
same standard: `PlatformEntry` instantiates the contributor and the two handlers and calls the
methods the platform calls, with stand-ins for the `Editor`, the `Document` and the PSI.

The six registration-only rows are honest omissions, not oversights, and each has its own
reason rather than a shared one: hover (8) and the PSI tree window (24) have their *decisions*
measured (the hover text by `HoverTruth`, the tree format by `ast-diff.ps1`) and the surface
that draws them is not — a green number about a *decision* is not a measurement of a popup,
which is the distinction this round had to hold for rows 7, 9 and 19 as well; the
file type (1), the settings page (26) and the New → Vela File action (27) are registrations
the verifier reads back, and the debugger (23) is a refusal. The
`FeatureProbe` harness added in an earlier round was written specifically to move rows 3, 17,
18, 15, 4 and 21 out of that group, and it did; `RenameWriteback` moved 11 and 12 out of it,
and `PlatformEntry` is the harness this round wrote to move 7 and 19 out of it, and it did.

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
omitted. Every row below is **one run of one artifact**: the fifteen harness tools in a single
`harness.ps1 -Tool All` pass on 0.1.8, and `ast-diff.ps1` / `psi-tree-diff.ps1` run separately
because they are separate scripts — all against one frozen compiler (`vm.exe` 803,840 bytes, sha256
`20a15de8…`, taken into `%TEMP%\vela-plugin-drive\`) whose hash is printed in every raw log. The
raw output of all seventeen is `idea-plugin\evidence\harness-0.1.8-20260924-0546.txt`.

| tool | exit | coverage triple | verdict |
|---|---|---|---|
| `ast-diff.ps1` | 0 | `ran 117 / skipped 12 (compiler-refused 12, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS -- 134,587 node lines identical, 0 different / 0 suspect / 0 missing (0.1.8) |
| `psi-tree-diff.ps1` | 0 | `ran 129 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0` | PASS -- 129 of 129 files replayed through the platform's own `PsiBuilderImpl` (0.1.8) |
| `GotoOracle` | 0 | `ran 28452 / skipped 24983 (crashed-file 0, compiler-refused 24973, invisible-member 10, ambiguous 0) / wrong 0` | clean, and no longer clean-by-omission (0.1.8) |
| `HintDiff` | 0 | `ran 33676 / skipped 122 (compiler-cannot-parse 95, arg-boundary-disagreement 12, compiler-refused-the-file 15, check-timed-out 0) / wrong 0` | clean -- every hint names the parameter its declaration gives, and every declared parameter has a hint (0.1.8) |
| `HintNames` | 0 | `ran 1972 / skipped 12 (threw 0, missing-corpus-file 0, check-timed-out 0, nothing-was-judged 0, no-model-entry-for-this-def 7, too-large 0, compiler-refused-the-file 5) / wrong 0` | clean, with the refused-file case counted rather than called a disagreement (0.1.8) |
| `HintShapes` | 0 | `ran 9049 / skipped 4 (parameterHints-threw 0, missing-corpus-file 0, too-large 0, too-large-for-prefix-sweep 4) / wrong 0` | clean -- 251,022 labels drawn over 9,049 prefix runs, 0 of them not a plain identifier (0.1.8) |
| `HintDupes` | 0 | `ran 1977 / skipped 0 (symbols-threw 0, missing-corpus-file 0, too-large 0) / wrong 0` | clean -- no repeated or empty parameter name (0.1.8) |
| `SymbolDiff` | 1 | `ran 89 / skipped 27 (threw 0, missing-corpus-file 0, too-large 0, type-spelling-only-difference 2, tree-reports-more-declarations 25, scan-reports-more-declarations 0) / wrong 13` | regression detector, above (0.1.8) |
| `FoldDiff` | 1 | `ran 67 / skipped 38 (threw 0, missing-corpus-file 0, too-large 0, granularity-only-difference 38) / wrong 24` | regression detector, above (0.1.8) |
| `FeatureProbe` | 0 | `ran 1591 / skipped 1 (missing-corpus-file 0, too-large 0, empty-or-whitespace-only 0, needs-an-application-instance 1) / wrong 0` | clean -- all six features that had only a registration answered as the invariant requires (0.1.8) |
| `ParamNames` | 0 | `ran 1977 / skipped 5924 (harness-threw 0, missing-corpus-file 0, file-too-large 0, not-a-callable 5924, callee-not-in-this-file-and-not-a-builtin 0, builtin-arity-only-SPEC-md-names-none 0) / wrong 0` | clean -- every parameter list names what its declaration names, or names nothing (0.1.8) |
| `HintTruth` | 0 | `ran 7471 / skipped 6825 (harness-threw 0, missing-corpus-file 0, file-too-large 0, no-argument-call 2688, callee-not-declared-in-file 4137, builtin-arity-documented-without-names 0) / wrong 0` | clean -- every label drawn is a parameter name the declaration gives for that argument (0.1.8) |
| `HoverTruth` | 0 | `ran 12128 / skipped 91 (harness-threw 0, spec-documents-the-arity-without-parameter-names 24, compiler-refused-the-file 53, nested-def-outside-the-model 12, variadic-signature-declares-no-parameter 1, builtin-signature-writes-no-return-type 1) / wrong 0` | clean -- every hovered row says what the compiler, `SPEC.md` or the absence of a declaration says (0.1.8) |
| `RenameOracle` | 0 | `ran 857 / skipped 5546 (compiler-refused 5366, too-large 0, missing-corpus-file 0, compiler-silent 29, not-attributable 151, unverified-lines 0, unverifiable-basis 0, too-many-candidates 0, new-name-refused 0, crashed 0, contradiction 0) / wrong 0` | clean -- the reference table names exactly the 2,893 uses the compiler binds and nothing else (0.1.8) |
| `InspectionProbe` | 0 | `ran 265 / skipped 53 (crashed-file 0, missing-corpus-file 0, too-large 0, compiler-did-not-run 0, check-did-not-terminate 0, fix-wrote-nothing 0, inspection-class-disagrees-with-the-rule 0, does-not-parse 53, empty-or-whitespace-only 0, excluded-by-request 0) / wrong 0` | clean -- every finding walked to a refusal the compiler itself makes on that line (0.1.8) |
| `RenameWriteback` | 0 | `ran 785 / skipped 5618 (compiler-refused-file 5366, too-large 0, missing-corpus-file 0, no-references-and-silent 28, not-attributable 139, new-name-refused 0, new-name-collides 0, too-many-references 85, crashed 0, contradiction 0) / wrong 0` | clean -- the plugin's write-back wrote its own 1,688-reference write set into the file it read back, which the compiler accepts for 785 of 785, and the control fired (0.1.8) |
| `PlatformEntry` row 7 | 0 | `ran 2117 / skipped 139 (receiver-not-a-written-binding 1, template-names-not-in-spec 138, …) / wrong 0` | clean -- the offer set is the compiler's own declarations, the insert handler writes their parameter names, and the 28 positions that were counted before 0.1.8 are judged and clean (**0.1.8**) |
| `PlatformEntry` row 9 | 0 | `ran 3359 / skipped 620 (callee-not-a-declared-def 599, struct-constructor-popup-closed 21, …) / wrong 0` | clean -- the index the popup is given names the parameter the compiler binds, the drawn string is the declaration's own, and the rebuilt-anchor path is driven, not asserted (**0.1.8**) |
| `PlatformEntry` row 19 | 0 | `ran 8329 / skipped 1107 (line-ends-with-brace 685, first-line 347, prefix-not-a-complete-program 40, indent-not-the-corpus-step 35) / wrong 0` | clean -- the closer is written exactly where the compiler's token stream says a bracket is, and Enter writes the indentation the corpus itself uses (**0.1.8**) |
| `PlatformEntry` (all three) | 0 | `ran 13805 / skipped 1866 (25 named classes) / wrong 0` | clean -- one run, three verdicts, every comparator asked to fire first (**0.1.8**) |

Two rows are **non-zero on purpose** and are not defects in the table's sense: `SymbolDiff` and
`FoldDiff` are the regression detectors for the two replacements this plugin has made (the token
scan → the parser's tree, the brace matcher → `VelaFoldings`), and their numbers are the *measured
difference* between the new reading and the retired one.  `wrong 13` and `wrong 24` are the same
counts the 0.1.5 pass reported, over a corpus that has grown (86 → 89 and 64 → 67 judged), which
is what "the difference did not move" looks like as a number.

The corpus was **not** frozen, and that is the one thing this table shares with the version it
replaces: `tests\` belongs to another track and was being added to while these runs ran, so the
same tool judges more positions than the 0.1.5 rows did — `GotoOracle` 26,077 → 28,452, `HintDiff`
31,188 → 33,676, `RenameWriteback` 782 → 785.  Those differences are the corpus, not the plugin,
and three of the four numbers they move are skips: `RenameWriteback`'s `main`-shaped skips are its
own (139 declarations whose declaration-only rename is refused for a reason about the program are
counted `not-attributable`, 85 with more than 6 claimed references are `too-many-references`, 28
have nothing to rename — each with its own rows printed in the evidence file, which is the point of
printing them).  `HintNames`'s one case is unchanged and still named:
``tests/build/check_cases/unannotated_parameter.vel line 2 `f` tree=[] model=[n]`` — a
compiler-refused file, so the tree records no parameter and the symbol model reads `n` out of the
detail text; on illegal Vela the tree's reading is the defensible one, and the case is counted
(`no-model-entry-for-this-def` 7, `compiler-refused-the-file` 5) rather than called a disagreement.

**The one change this round made to the model** -- a local binding with a written type
(`VelaTargets.localBindingType`, which `VelaNames.structTypeOf` now asks first) -- sits on the
resolution path of rows 7, 9, 10, 11 and 12, so the five tools that reach it were run **twice over
the same tree, minutes apart**: `GotoOracle`, `RenameOracle`, `RenameWriteback`, `HintDiff` and
`HoverTruth`, once against the 0.1.7 jar's classes and once against this build's, with the same
harness and the same frozen compiler.  Every number is identical tool by tool, which is what says
the quoted rows 10, 11 and 12 did not move; the raw pairs are in
`evidence\platform-entry-0.1.8-20260924-0546.txt`.

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

### 2. `HintDiff`: 30 argument positions got no parameter-name hint — gone from the 0.1.5 run (row 9 was `partial` for the undriven `VelaParameterInfoHandler`, and that is now driven: see entry 8)

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
(`arg-boundary-disagreement 125` then, `12` now), and it was never why row 9 was
`partial` — that reason was that no harness instantiated `VelaParameterInfoHandler`, so the
argument index the popup highlights and the string `updateUI` draws were unmeasured. `PlatformEntry`
drives those two now (row 9's evidence cell, and entry 8 below for the two defects it found there).
What keeps this entry is that 12 is not 0, and the class is still printed by `HintDiff` alone --
`PlatformEntry` counts the positions it cannot judge under its own names and does not re-count this one.

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
1 control correct, 0 script/verdict problems**, baseline `RESULT: PASS` on the 0.1.6 jar
(`dist\vela\lib\vela-idea-plugin.jar`, 391,263 bytes, sha256 `3ff8091c…`), published to
`build\verify\mutation-0.1.6\mutation-report-0.1.6.txt` with the version, the jar's
SHA256 and the time in its first lines, plus one verifier log per mutant — and copied, with
the command and the full raw output, into `evidence\mutation-0.1.6-20260924-0442.txt`. The
0.1.5 pair of that file is still in `evidence\mutation-0.1.5-raw.txt`; it was **re-run rather
than relabelled**, because a mutation result whose version disagrees with the descriptor is a
result about an artifact nobody can name. The known
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
published to `build\verify\negative-0.1.6\negative-report.txt` and copied, with the command
and the full raw output, into `evidence\negative-0.1.6-20260924-0444.txt`. It was re-run on
0.1.6 for the same reason the mutation set was: its mutations derive their targets from the
*current* `plugin.xml`, and a target that had disappeared would report `VOID` rather than a
stale `CAUGHT` — this run reports **0 void**, so every target still exists and every mutation
still applied. Getting there required
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

### 7. The shadowing defect in the reference table's resolver — closed, with the measurement's own limit stated

```
BEFORE (same corpus, same frozen compiler, tools byte-identical to git):
    local claimed 1256 / all claimed 2767 / MISSED 0 / WRONG-SCOPE 0 / NOT-REQUIRED 5 / wrong 0
    tests/safety/cases/scope_shadow_across_blocks_ok.vel:5 `x` (local): the table claims
      line 8 (offset 292) ... NOT-REQUIRED
AFTER  (the same pairs, the fix in):
    local claimed 1255 / all claimed 2766 / NOT-REQUIRED 4 / wrong 0, and that row is gone
```

Case 3 of `VelaTargets.declarationFor` looked for the nearest declaration *preceding* the
offset, so an inner redeclaration's own use was answered by the outer declaration: Ctrl+Click
on the inner `x` jumped to the outer one, and the outer `x`'s rename claimed the inner use.
The walk is now `chain.reversed()` — inner block first. The measurement is
`evidence\inner-first-scope-0.1.6.txt` (a track of its own; the *fix* was already committed in
`958aa85` before that round measured it, which is stated here because the file itself says so),
and it includes the falsification: revert the order and the row comes back.

**What that file does not show, kept here rather than only there**: the defect could not be
made to exit non-zero. A claimed-but-unbound offset becomes `WRONG-SCOPE` only when some other
declaration's binding set contains it, and this inner `x` is itself compiler-silent — so the
row *disappears* instead of turning red, which is a weaker signal than a red. That is the same
unprovable class rows 11 and 12 count (4 of them in `RenameOracle`'s 0.1.6 run, 8 in
`RenameWriteback`'s), and it is why both of those rows say the class is counted and printed
rather than called either way.

### 8. Three defects the platform's own entry points found, and what the numbers were before

Rows 7, 9 and 19 were the last three `partial` rows, all for one reason: no harness had ever
instantiated the platform object the row is about. `PlatformEntry` instantiated all three, and
the first full-corpus run came back red in two of them. All three defects below were found by
driving the row's own entry point, and all three are fixed in 0.1.7; the raw before/after output
is in `idea-plugin\evidence\platform-entry-0.1.7-20260924-0520.txt`, and each "before" number is
the *same tool over the same corpus with the same frozen compiler*, differing only in the plugin
build (0.1.6 vs 0.1.7).

```
ROW 7  --  the builtin table outranked the file's own declaration
0.1.6 RAW:  4 position(s) of 2089 judged are wrong
            tests/build/extern/extern_c_probe.vel: `abs` template `(n)` names [n], and the
              declaration names [x]
            (the same row for tests/probes/extern_calls_as_declared.vel,
             extern_constant_fits_declared_kind.vel, extern_declared_after_the_call.vel)
0.1.7 RAW:  COVERAGE: ran 2089 / skipped 167 (...) / wrong 0
```
A file that declares `extern c def abs(x: float)` was offered the *language's* builtin `abs(n)`
first, and the dedupe then suppressed the file's own `abs`, so the inserted call named `n` — a
parameter that declaration does not have. The rule was already written twice in this plugin
(`VelaTargets.declaredParameterNames` consults the language's table only for a name the file does
not write, and `VelaInlayHints.parameterHints` follows it); the completion did not.

```
ROW 9  --  the popup emphasised the parameter the caret was in when it opened
0.1.6 RAW:  emphasis 690 wrong, index 23 wrong of 2678 judged
            tests/llvm-shapes/str_two_params.vel:6 `f(` argument 1 is bound to `b` by the
              compiler and the popup emphasises `a` in `a, b -> None`
            tests/probes/capture_probe.vel:60 `cap(` caret at argument 1 (offset 2289) and
              setCurrentParameter was given 0
0.1.7 RAW:  COVERAGE: ran 2678 / skipped 626 (...) / wrong 0
```
`updateUI` draws from `hint.index`, and only `findElementForParameterInfo` ever set it: moving the
caret inside an open call told the platform a new index through `setCurrentParameter` and told the
drawn string nothing. And `callAt(context.offset)` reads the caret's *own line*, so on the second
line of a multi-line argument list it answered nothing and the index stayed where it opened. The
call's `(` now travels on the item, the index is counted from it on every update, and it is
written back on the item the platform hands to `updateUI`.

```
ROW 9 (residual, after the two above were fixed)
0.1.6 RAW:  draw 3 wrong, emphasis 5 wrong
            ide-demo/tour.vel:40 `moved(` argument 0 is bound to `dx` by the compiler and the
              popup emphasises `self` in `self, dx, dy -> Point`
            ide-demo/tour.vel:32 `manhattan(` argument 0 emphasises `self` and there is no
              argument there
            tests/probes/mut_struct_parameter.vel:48 `bump(` argument 0 is bound to `k` ... and
              the popup emphasises `self` in `self, k -> None`
0.1.7 RAW:  COVERAGE: ran 2678 / skipped 626 (...) / wrong 0
```
The drawn list was the declaration *as written*, including a method's leading `self`, while the
index counts the parentheses' arguments — so every method call with a receiver was one parameter
off, and a call with no argument at all still emphasised `self`. `VelaInlayHints` had already
solved this (`declared.drop(1)`); the popup had not.

This entry stays here because it is the record of a red measurement, not because anything is open:
all three are closed, and rows 7, 9 and 19 are `implemented` on the strength of the green run
above. What that round did **not** do is fix the two limits those same runs counted and printed
rather than called wrong — a receiver that is a local with a written type (`q: Vec2 = …` then
`q.`, 29 + 6 positions, `VelaNames.structTypeOf` resolving `self`, a parameter and a struct's name
only) and the `objectsToView` fallback in `findElementForUpdatingParameterInfo`, which could not
return the anchor as written because `itemsToShow` holds `ParameterHint` items and not PSI leaves.

**Both were closed in 0.1.8, and both turned out to be defects rather than limits**, which is the
reason this closure is recorded with the before/after lines instead of a sentence. Same harness,
same corpus, same frozen compiler (`vm.exe` 803,840 bytes, sha256 `20a15de8…`), the plugin build
the only difference between the two runs:

```
0.1.7 jar, PlatformEntry section row 7:
  after-dot                  194     28
  COVERAGE: ran 2117 / skipped 139 (... receiver-not-a-written-binding 1, template-names-not-in-spec 138 ...) / wrong 28
  VERDICT: 28 position(s) of 2117 judged are wrong: the offer set is not the one the compiler's own declarations describe [FAIL]
    tests/build/struct_and_methods.vel:18 `p.` (type `Vec2`): the compiler declares [x, y, dot, scale]; not offered [x, y, dot, scale]
    tests/build/struct_value_semantics.vel:8 `a.` (type `P`): the compiler declares [x]; not offered [x]
    tests/llvm-shapes/two_structs.vel:19 `p.` (type `Box`): the compiler declares [n, m, add]; not offered [n, m, add]
    ... 25 more, one per position, all of the same shape

0.1.8 jar, the same tool and the same positions:
  after-dot                  194      0
  COVERAGE: ran 2117 / skipped 139 (...) / wrong 0
  VERDICT: ... [PASS]

0.1.7 jar, PlatformEntry section row 9:
  update-rebuilt             663    663
  COVERAGE: ran 3359 / skipped 620 (...) / wrong 663
  VERDICT: 663 position(s) of 3359 judged are wrong: the popup does not name the parameter the compiler binds the caret's argument to [FAIL]
    tests/build/arrays_and_len_folding.vel:3 `len` the anchor leaf was rebuilt (the platform does that after an edit)
      and the handler found nothing, though the item it showed came back in objectsToView: the popup would not survive an edit

0.1.8 jar, the same tool:
  update-rebuilt             663      0
  COVERAGE: ran 3359 / skipped 620 (...) / wrong 0
  VERDICT: ... [PASS]
```

The row 9 half is the sharper of the two, because it is a check that could not fail before it was
written: the 0.1.7 handler read `objectsToView` as `(shown[0] as? PsiElement)` and those items are
`ParameterHint`s, so the branch was unreachable — measured, not argued, by rebuilding the anchor
leaf and watching 663 judged calls answer *nothing*. The falsification runs in the same family:
with `objectsToView` empty the handler must answer `null`, and a handler that invented an element
would be reported. The 6 local-receiver calls row 9 used to count are judged from 0.1.8 and are
clean on **both** builds, because the by-name method fallback happened to name the right method
there; the class disappeared because the *reader* resolves the receiver now, and it is not claimed
as a third wrong answer.


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
* **No IDE was launched.** No IntelliJ instance can be started in this session, so
  nothing here is evidence that the plugin loads, that a tool window appears, that a
  menu entry is where it should be, or that any of these features looks right on
  screen. The strongest evidence available is the platform-registration read-back
  (the platform's own descriptor says which attribute it reads, and `VerifyPlugin`
  reads the class back out of the jar) plus the headless differentials — and it is
  labelled as such in every row that uses it.
* `implemented` means "the platform will call this, and the decision function behind
  it was measured where a harness could reach it". It does not mean "seen working".
* Rows 7, 9 and 19 were the three rows with **no behaviour measurement at all**
  until this round: each was a registration plus compiled code, because the row is
  about an object the *platform* instantiates. `PlatformEntry` now instantiates all
  three and calls the platform's own methods, with stand-ins for the objects a
  headless run cannot have (the `Document`, the `Editor`, the PSI, and the three
  parameter-info context interfaces), and every stand-in is named in the evidence
  file. Row 8's hover *text* is measured by `HoverTruth` and its popup is not;
  the debugger row is a refusal; rows 11 and 12 were moved by `RenameWriteback`.
  What none of them has is a running IDE, and that sentence is unchanged.

  `HoverTruth` and its popup is not; row 9's parameter-name reader is measured by `HintDiff`
  and its popup is not; the debugger row is a refusal. Rows 11 and 12 had no behaviour
  measurement either until this round, and the harness built for exactly that reason
  (`RenameWriteback`) is what moved them: the table
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
