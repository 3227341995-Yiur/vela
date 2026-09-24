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
| 5 | real PSI parser | `<lang.parserDefinition language="Python" implementationClass="com.jetbrains.python.PythonParserDefinition" />`; plus `<lang.ast.factory language="Python" …PythonASTFactory />` and `<stubElementTypeHolder class="com.jetbrains.python.PyStubElementTypes" externalIdPrefix="py." />` | implemented | `VelaParserDefinition.kt`, `VelaSyntax.kt`, `VelaNodeTypes.kt` | `[harness]` `ast-diff.ps1`: **133 files identical to `vm.exe parse`, 147,346 node lines**, exact string equality line for line, `VERDICT: PASS` with 0 different / 0 suspect / 0 missing / 0 crashed and 14 files the compiler refuses (all 14 of which this parser also refuses); plus a self-test that rejects a wrong tree (`6 cross pairs, 6 detected as different, 0 missed`). `[harness]` `psi-tree-diff.ps1`: **147 of 147 files** replayed through the platform's own `PsiBuilderImpl`, 147,388 composite nodes and 399,393 leaf tokens compared, `failed 0`, `VERDICT : PASS`, `COVERAGE: ran 147 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0`. **Re-measured on 0.1.9 for SPEC.md §13** (`enum` declarations with payload variants, and `match`), which the parser now builds node for node: the pass above is 147 files where the 0.1.8 pass was 117, and 147,346 node lines where it was 134,587 — all eighteen `enum_*` corpus files are among the identical ones, and the two §13 files whose *shape* the compiler refuses (a nested pattern, `enum` used as a name) are among the 14 that both refuse. The single FAIL this row was `partial` for — `tests/build/lexer_error.vel`, an unterminated string — is fixed at its root: the scanner's closing newline claimed a character it had never read and is now zero-width at the failure offset (0.1.5 entry, item 1). **What is still not measured**: nothing drove this parser from a running IDE, so the row rests on those two headless differentials and not on an editor; and the parser deliberately reproduces only the compiler's *parse* refusals, so a file its *checker* refuses (a missing variant, a duplicated arm, an unknown variant) is a tree here and a diagnostic from `vm.exe check` |
| 6 | syntax error highlighting | `<annotator language="Python" implementationClass="com.jetbrains.python.validation.PySyntaxAnnotator" />` | implemented | `VelaParserDefinition.kt` (`PsiBuilder.error` during the replay), `VelaAnnotator.kt` | `[harness]` `psi-tree-diff.ps1` asserts that a parser problem produces an error element in the platform tree (`VELA_ERROR` / `ERROR_ELEMENT`); files where the parser reported a problem: 12 (recovery is exercised, not avoided). `[harness]` VerifyPlugin §9: problems parsed out of the compiler's own output with kind, message and line |
| 7 | code completion (incl. after `.`) | 28 `<completion.contributor language="Python" …>` in `ce-plugin.xml`; member completion after `.` comes from `PyClassMembersProvider` / `pyModuleMembersProvider` | implemented | `VelaCompletion.kt` | `[registration]` `<completion.contributor language="Vela">`. `[harness]` **`PlatformEntry` section row 7**, 0.1.10, same frozen compiler (`evidence\spec13-names-0.1.10-20260924-0806.txt`): it calls `VelaCompletionContributor.fillCompletionVariants` with the platform's own `CompletionParameters` and a recording `CompletionResultSet`, and `LookupElement.handleInsert` in a real `InsertionContext` over a real `OffsetMap` — **2288 positions judged, wrong 0** in three families (`bare-end` 149, `insert` 1945, `after-dot` 194), `COVERAGE: ran 2288 / skipped 163 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, receiver-type-not-written 0, receiver-not-a-struct 0, receiver-not-a-written-binding 1, insert-not-a-callable 13, template-names-not-in-spec 149, contributor-threw 0) / wrong 0`. `bare-end` is the fabrication axis and the keyword axis: every keyword `SPEC.md` 1.3 lists, every builtin whose section 8 row documents parameter names, and every module-level name `vm.exe parse` declares must be offered, and no offered name may be one nothing declares — and from 0.1.9 an **enum's own name** is one of those module-level names, because the harness's oracle reads `enum name=…` as a declaration like any other. **The §13 keyword measurement, with its falsification**: with `enum` and `match` reserved by the language and not known to this plugin the family stood at **139 wrong positions, one per file, each reporting `not offered: keyword enum, keyword match`**; both words are in the plugin's keyword set now and the family is clean. Removing the two words again makes the same tool report `tests/build/enum_else_arm.vel: the caret at the end of the file; not offered: keyword enum, keyword match`, `COVERAGE: ran 17 / skipped 4 (...) / wrong 1`, exit 1 — raw in the evidence. `insert` includes a **variant with a payload** from 0.1.9, which the language writes as a call (`Circle` → `Circle(radius)`) and which is judged against the variant's own field list; the 13 payload-free variants are the `insert-not-a-callable` class rather than wrong, because `Empty()` is text the compiler refuses. `after-dot` is exactly the members the dump lists, for the four receiver kinds the model resolves (`self`, a parameter, a local binding with a written type, a struct's own name — an *enum* has no members in v1, so `Shape.` offers nothing and the harness skips it as `receiver-not-a-struct`). **Three defects found by this tool and fixed**: the builtin table outranking a file's own `extern def abs` (0.1.7), a local binding with a written type getting no member completion (0.1.8), and the two missing keywords (0.1.9). **What is still not measured: the popup itself** — the recording result set does not filter by prefix, sort or draw — so this row claims what the contributor offers and what its insert handler writes, not how the popup looks |
| 8 | hover documentation | `<lang.documentationProvider language="Python" id="pythonDocumentationProvider" implementationClass="com.jetbrains.python.documentation.PythonDocumentationProvider" />` (4 entries), plus `pythonDocumentationQuickInfoProvider` | implemented | `VelaDocumentation.kt` | `[registration]` `<lang.documentationProvider language="Vela">`. `[harness]` `HoverTruth`: **11,830 row(s) judged, wrong 0**, over 311 corpus files — every struct, field, `def` and parameter hovered at its declaration and compared against the compiler's own `parse` dump (names, `mut`, types, return types, nesting) and its `lex` dump for the line number; every builtin name against SPEC.md §8; and every identifier in the token stream that neither the file nor the language declares must hover as **nothing**, which is the axis that catches invented content (an earlier defect in this area copied parameter names out of the parentheses and produced `s: s: s:`). A parameter list the model cannot read prints no names at all rather than guessing. Falsification is part of the evidence: `--demand-hover <a name that does not exist>` fails and says the demand failed, and `--swap-params` reports exactly the declaration whose parameter order it reversed. What is still not measured: a running IDE's popup — the harness calls `documentationAt(text, offset)`, which is the PSI-free entry point the popup itself calls, so the rendering is not driven |
| 9 | parameter info | `<codeInsight.parameterInfo language="Python" implementationClass="com.jetbrains.python.PyParameterInfoHandler" />`; pro adds `keywordArgumentProvider` and `Pythonid.pyBddParametersInspection` | implemented | `VelaParameterInfo.kt`, `VelaLanguage.kt` (`callAt`), `VelaTargets.kt` | `[harness]` `HintDiff` / `HintShapes` / `HintDupes` measure the declaration reader the popup shares with the inlay hints (`VelaTargets.declaredParameterNames` / `builtinParameterNames`), re-measured on 0.1.10: `HintDiff` `COVERAGE: ran 37046 / skipped 78 / wrong 0`, `HintShapes` `ran 9357 / skipped 4 / wrong 0`, `HintDupes` `ran 2099 / skipped 0 / wrong 0`. `[harness]` **`PlatformEntry` section row 9** drives the handler itself (`evidence\spec13-names-0.1.10-20260924-0806.txt`): `findElementForParameterInfo` → `showParameterInfo` → `findElementForUpdatingParameterInfo` → `updateParameterInfo` (`setCurrentParameter`) → `updateUI`, with stand-ins for the three context interfaces — **3519 positions judged, wrong 0** in four families (`find` 722, `show` 701, `update` 1395, `update-rebuilt` 701), `COVERAGE: ran 3519 / skipped 645 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, callee-not-a-declared-def 624, receiver-not-a-written-binding 0, receiver-on-a-function 0, method-call-without-receiver 0, spec-row-documents-no-names 0, plugin-claims-no-names 0, parameter-list-untrusted 0, struct-constructor-popup-closed 21, rebuilt-anchor-unavailable 0) / wrong 0`. The axes the row was `partial` for are the ones it judges: the index `setCurrentParameter` is given must name the parameter the *compiler* binds that argument to, and the string `updateUI` draws must be the declaration's own names in order with the emphasis covering exactly that argument. **From 0.1.10 a variant construction is one of those calls**: `Circle(2.0)` names a variant with a payload, `VelaNames.resolveCall` answers it, and the popup draws the payload's field names — read off the same symbol the completion's `Circle(radius)` template is built from, so the two cannot disagree about one declaration. Measured on the same corpus with both builds: the 0.1.9 plugin counted those 9 calls in `callee-not-a-declared-def` and the 0.1.10 plugin judges them, `find`/`show`/`update` each nine larger and every one correct. **Four defects fixed**: two in 0.1.7 (the drawn list and the index counting different things for a method call; the emphasis never following the caret), one in 0.1.8 (the `objectsToView` half of `findElementForUpdatingParameterInfo` being unreachable, so the popup could not survive an edit — 663 judged / 663 wrong on the 0.1.7 jar), one in 0.1.10 (no popup at all for a variant construction). **Counted rather than called wrong**: 624 positions whose callee is not a `def` this file declares and not a builtin section 8 documents (`print`, `to_float` — no oracle writes a parameter list for them), 21 struct-constructor calls the model deliberately does not open a popup for, and any position whose parameter list the item carries as null. **What is still not measured**: the platform's own `ParameterInfoControllerBase` (this tool calls the five methods it calls, in its order, with stand-ins for `CreateParameterInfoContext`, `UpdateParameterInfoContext` and `ParameterInfoUIContext`); the `objectsToView` fallback is not in that list — the `update-rebuilt` family drives it |
| 10 | go to declaration | `<gotoDeclarationHandler implementation="com.jetbrains.python.psi.impl.PyGotoDeclarationHandler" />` + `PyBreakContinueGotoProvider`; the resolution itself is `pyReferenceResolveProvider` with `PyForwardReferenceResolveProvider` | implemented | `VelaGotoDeclaration.kt`, `VelaTargets.kt` | `[harness]` `GotoOracle` (one `vm.exe check` per declaration rename, the binding set verified by the file compiling again): **30,880 references judged, 0 WRONG**, `COVERAGE: ran 30880 / skipped 27305 (crashed-file 0, compiler-refused 27287, invisible-member 18, ambiguous 0) / wrong 0` — and from 0.1.10 that count includes the §13 names, which it did not before because the tool itself had no declaration to ask the compiler about.  **The before/after, same harness, same frozen compiler, the plugin build the only difference** (`tests/build/enum_exhaustive_switch.vel`, the tool's own per-kind table): 0.1.9 jar — `variant  3 refs / 0 correct / 3 no-target / 0 wrong`; 0.1.10 jar — `variant  3 refs / 3 correct / 0 no-target / 0 wrong`.  The three are the construction `Add(2, 3)`, the arm's pattern `Add(a, b)`, and the bare `Zero`; the same shape holds for an enum's own name in a type position (`c: Shape`), which resolves to the `enum` declaration.  **A §13 limit, named and counted**: a *pattern binding's* name (`r` in `Circle(r)`) is still not resolved — the arm binds a new name positionally, so the body's uses of it are the `invisible-member` class, printed with their lines (3 on that file), and so is a *payload field's* name, which nothing in the language can write.  The shadowing case of the same resolver — Ctrl+Click on an inner `x` jumping to the outer one — is measured separately in `evidence\inner-first-scope-0.1.6.txt`: `VelaTargets.declarationFor` case 3 walks the ancestor chain inner-first, and that file states its own limit (the case could not be made to exit non-zero, because the inner `x` is itself compile-silent, so the row disappears instead of turning red) |
| 11 | find usages / references | `<lang.findUsagesProvider language="Python" implementationClass="com.jetbrains.python.findUsages.PythonFindUsagesProvider" />`; `usageTypeProvider` in `py-plugin.xml` | implemented | `VelaFindUsages.kt`, `VelaReferenceContributor` in `VelaGotoDeclaration.kt` | `[registration]` `<lang.findUsagesProvider language="Vela">`, `<psi.referenceContributor implementation="…VelaReferenceContributor">` (the attribute is `implementation`, proven in `PLUGIN_SURFACE.md`). `[harness]` **Two independent measurements of the same table, on 0.1.10 and the same frozen compiler** (`vm.exe` 867,328 bytes, sha256 `1e52032c…`). `RenameOracle` asks the compiler about one occurrence at a time — rename the declaration and every candidate *except* that one, and a refusal means the compiler binds it: **901 declarations judged, 2,998 bound uses, 0 MISSED, 0 WRONG-SCOPE, 4 claimed-but-unprovable, wrong 0**, `COVERAGE: ran 901 / skipped 6008 (compiler-refused 5800, too-large 0, missing-corpus-file 0, compiler-silent 42, not-attributable 166, unverified-lines 0, unverifiable-basis 0, too-many-candidates 0, new-name-refused 0, crashed 0, contradiction 0) / wrong 0`; the same run holds the results view's own decisions to the compiler (`canSearchAt` on every bound use and on the declaration's own name, and the label `typeAt` returns).  **From 0.1.10 it judges the §13 names too, and that is what forced the fix**: with the declaration walk taught `enum`/`variant` but the plugin still blind at a declaration's own name, every one of them came back `NOT-SEARCHABLE` — the tool's per-kind row on one file reads `0.1.9 jar: variant 3 decls / 6 bound / 0 claimed / 6 MISSED / 3 wrong declarations` against `0.1.10 jar: 3 / 6 / 6 / 0 / 0`, and the messages were `NOT-SEARCHABLE: the platform would refuse Find Usages on this declaration's own name` and, for the uses, `the usage on line 17 (offset 553) cannot be searched for from its own name`.  The fix is one line in `VelaUsageSearch.canSearchAt`: a leaf that resolves to *itself* through the reference machinery is a declaration, which is how `enum Shape` or a bare variant answers "yes, usages can be looked for here" — the token-shape rule it used before knows `struct X`, `def f`, `for i` and `name: T` and none of §13's shapes.  **`RenameWriteback`** (`evidence\spec13-names-0.1.10-20260924-0806.txt`) drives the provider **itself** over copies of real corpus files: **830 declarations, 1,779 claimed references, 2,609 writes landed of 2,609 attempted, the file read back compiles for 830 of 830**, and leaving any one reference unwritten is refused by `vm.exe check` for 1,727 of the 1,779 — the 52 that compile anyway being the compile-silent positions this row counts rather than calls wrong.  The shadowing half of the same table has its own measurement, `evidence\inner-first-scope-0.1.6.txt` (see row 10) |
| 12 | rename refactoring | Python relies on PSI references; `py-plugin.xml` adds `vetoRenameCondition` and `customUsageSearcher` | implemented | `VelaLeafManipulator` in `VelaGotoDeclaration.kt`, reference from `VelaReferenceContributor` | `[registration]` `<lang.elementManipulator forClass="com.intellij.psi.PsiElement" implementationClass="…VelaLeafManipulator">`. `[harness]` **`RenameWriteback`, which renames and reads the file back from disk** (`evidence\spec13-names-0.1.10-20260924-0806.txt`): it drives the plugin's own write path — `VelaReferenceProvider` → `VelaReference.resolve()` → `VelaReference.handleElementRename` for every usage, `VelaLeafManipulator.handleContentChange` for the declaration, in the platform's reverse-document order — over a *copy* of each real corpus file, through the plugin's own `document.replaceString(start, end, newName)` calls, and then asks the compiler about the file it produced. **830 declarations, 1,779 claimed references, 2,609 writes landed of 2,609 attempted, the file read back compiles for 830 of 830, the two write paths agree for 830 of 830, `wrong 0`**; the recorded write log *is* the write set (every range a whole identifier leaf of the right length, every replacement the new name, nothing else written), the file read back is the original with exactly those ranges replaced, and the token-by-token comparison over both texts (plugin lexer) finds no token outside the write set that changed. **The falsification is measured rather than asserted**: renaming the *declaration alone* through the same write path is refused by the compiler for **828** of the 830 — the other 2 being declarations whose rename the compiler does not require anywhere (nothing to falsify, counted and printed), and the tool exits 3 if that count is ever 0, so a rename that silently wrote nothing could not pass. Making the rename *set* the same test (leave one reference out and the compiler must refuse) holds for 1,727 of the 1,779. **From 0.1.10 the §13 names are in that number** — 23 more declarations than 0.1.9's 807, an enum and its variants among them, each renamed through the plugin's own write path and each leaving a file the compiler accepts: that is the pair the row needs, because a rename that compiles is not evidence unless the compiler can also tell a wrong one from a right one, and the 828-of-830 column is what says it can. **What is still not measured**: the platform's rename dialog (this drives the provider the dialog calls, not the dialog) |
| 13 | structure view | `<lang.psiStructureViewFactory language="Python" implementationClass="com.jetbrains.python.structureView.PyStructureViewFactory" />` | implemented | `VelaStructureView.kt`, `VelaPsiStructureViewFactory.kt`, `VelaModel.kt` | `[registration]` `<lang.psiStructureViewFactory language="Vela">`. `[harness]` the symbol list it renders is `VelaModel.symbols`, measured by `SymbolDiff` over 126 files (see row 14's note and the SymbolDiff section below) |
| 14 | problems / inspections / quick fixes | **98** `<localInspection language="Python" …>` in `ce-plugin.xml` (e.g. `PyUnusedLocalInspection`, `PyTypeCheckerInspection`), and the plugin declares `Pythonid.inspectionExtension` for others to add to | implemented | `VelaAnnotator.kt` (`VelaExternalAnnotator`), `VelaDiagnostics.kt` | `[harness]` VerifyPlugin §9/§10: the compiler is run end to end — `arith_basics.vel` accepted (0 problems), `truthiness.vel` refused (1 problem, correct kind and **line read from the compiler**, not guessed), and the same diagnostic 4 lines lower moves to line 7. `[registration]` **two** `<localInspection language="Vela">` entries — `VelaImmutableAssignment` and `VelaIntFloatMixing` — with a quick fix each: insert `mut `, and wrap the int operand in `to_float(...)`. They were **three** until 0.1.11: `VelaStringConcatenation` reported `"a" + "b"` against the compiler's `type error: string concatenation is not implemented in Vela 0.1` and rewrote it to `concat("a", "b")`, and the language learned `+` on two `str`s (`SPEC.md` §1.5, 2026-09-24), so the refusal that rule keyed on can no longer be produced and its fix would have rewritten a working program into a different one. It was retired rather than repurposed into the opposite style rule, because a finding here has to come back with a refusal the compiler *itself* makes on that line and the compiler is silent about the two spellings — which are the same runtime call (`SPEC.md` §8). The retired class, its walk and the two helpers only it asked for are gone from `VelaInspections.kt`, which carries that argument where the rule used to be. Until three such lines existed the verifier refused the classes, reporting them as implementing `LocalInspectionTool` with "no registration and no class file names it", so `build-offline.ps1` was FAILING rather than passing a feature nobody could reach. `[harness]` `InspectionProbe`, **re-run in 0.1.11 against the compiler that carries `+` on strings** (frozen `vm.exe` 870,400 bytes, sha256 `aaa0a599…`; the 0.1.5 run above compared against a different binary, so its figures are replaced rather than subtracted): **330 corpus files** — 150 the compiler accepts, 137 it refuses — **`ran 287 / skipped 43 / wrong 0`**, **zero findings on the 150 files the compiler accepts**, **7 of 7 findings walked to a refusal the compiler itself makes on that exact line**, and every fix leaves `check` at exit 0. The two survivors' per-rule rows are `VelaImmutableAssignment 3 fired / 3 verified` and `VelaIntFloatMixing 4 fired / 4 verified`; the 0.1.5 run counted `2 / 2` and `4 / 4` over a smaller corpus, and the 7 findings that left with `VelaStringConcatenation` are the 13 → 7 difference. The method matters: `vm.exe check` reports only its **first** refusal, so a finding is verified by fixing it and asking again, not by looking for a diagnostic on its line. Negative control: one word changed in a rule's criterion gives `wrong 41` and exit 1. **Category, not parity: two rules against Python's 98** — the row is `implemented` because the category exists, is registered, and is measured, not because the counts match. What is not measured: no IDE was started, so `LocalQuickFix.applyFix`'s write action over a live document and the alt-Enter menu are untested |
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
| 25 | parameter-name inlay hints | `ce-plugin.xml` registers no parameter-name inlay provider: the 2 `codeInsight.declarativeInlayProvider` entries are Ruff-ish (`group="OTHER_GROUP"`, `providerId="RuffSuppressionCodes"` / `RuffTomlCodes`). Core Python parameter hints are not in this descriptor | implemented | `VelaInlayHints.kt` | `[harness]` `HintDiff` (11,441 hints drawn, 11,441 correct, 0 wrong), `HintShapes` (every label is a plain identifier, over at most 256 prefixes of every file), `HintDupes` (0 repeated parameter names). `[registration]` `<codeInsight.inlayProvider id="dev.vela.plugin.parameterNames" isEnabledByDefault="true">`. **`[harness]` `InlayProbe` (0.1.12) is the row's second layer and the one the three above could not reach:** they judge `VelaHints.parameterHints` — the *list* — and the collector the platform calls painted that list once per PSI element, so the list's numbers were green while the editor drew `ide-demo/tour.vel`'s 7 labels 490 times each. `InlayProbe` drives `getCollectorFor(file, editor, settings, sink)`'s collector over the file's real PSI tree (the platform's own `PsiBuilderImpl`, the replay `psi-tree-diff.ps1` uses) with a recording `InlayHintsSink`, and asserts `registered == the list` however many elements the walk visits: same tool, same corpus, the only difference the plugin build — 0.1.11 `ran 5 / wrong 5`, `[FAIL]`, exit 1 (3430/149/180/564/1008 registered, i.e. exactly `hints × elements` on every file) against 0.1.12 `ran 5 / wrong 0`, `[PASS]`, exit 0 (7/1/2/3/3). It is in `harness.ps1`'s tool list now. **What the row still does not measure: no IDE was started** — the sink is a `Proxy` and the walk is the tool's implementation of `FactoryInlayHintsCollector.collect`'s `Boolean` contract, so this is what the collector *registers*, not what a running IDEA paints |
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

**0.1.9 adds no row either, and it is the round that made the plugin follow the language.**  SPEC.md section 13
landed while 0.1.8 was being measured -- `enum` and `match` became reserved words, payload variants and
`match` became syntax, and eighteen corpus files arrived with them -- and this round is the plugin
catching up: the two keywords, the six PSI node kinds the compiler prints for 搂13, and every number in
the coverage-triple table below re-measured on the result.  Two of those numbers are the ones a reader
should check first, because they are the two that were *wrong before this round*: `PlatformEntry` row
7's `bare-end` family was red for the missing keywords (139 positions, one per file, each saying `not
offered: keyword enum, keyword match`) and is `146 judged / 0 wrong` now, and `HoverTruth` was red for
an oracle that knew four declaration kinds (21 findings, exit 1) and is `13013 / 0` now.  The evidence,
including the mutation that makes the keyword axis red on demand, is
`evidence\spec13-0.1.9-20260924-0710.txt`; the `HoverTruth` red is Open-defects entry 9.

**0.1.10 adds no row and changes no count either**: it makes the 搂13 names *usable*.  The two
reference tools now enumerate `enum`/`variant` declarations, so the compiler is asked about them
for the first time -- and the plugin answers: `RenameOracle` judges 22 more declarations and 43
more uses with 0 MISSED, `RenameWriteback` drives 23 more through the plugin's own write path and
the compiler accepts the file back for every one, `GotoOracle` resolves a variant construction, an
arm's pattern and an enum's type name where it used to answer no-target.  The parameter-info
popup opens for a variant construction, a payload field is a declaration the hover answers for,
and a variant name has a colour kind of its own -- the last of which **no harness measures**, which
is said in the row rather than implied.  Before/after pairs, the per-kind tables and the two
things this round cannot do (a pattern binding's name, a payload field's name) are in
`evidence\spec13-names-0.1.10-20260924-0806.txt`; the harness bug found on the way -- `HoverTruth`
overwriting a payload's owner with `structOf`, 15 findings against a hover that was right -- is
Open-defects entry 10.

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
| `ast-diff.ps1` | 0 | `ran 138 / skipped 14 (compiler-refused 14, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS -- 148,254 node lines identical over 138 files, 0 different / 0 suspect / 0 missing (0.1.10) |
| `psi-tree-diff.ps1` | 0 | `ran 152 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0) / wrong 0` | PASS -- 152 of 152 files replayed through the platform's own `PsiBuilderImpl`, 148,291 composite nodes and 401,982 leaf tokens, 0 failed (0.1.10) |
| `GotoOracle` | 0 | `ran 30880 / skipped 27305 (crashed-file 0, compiler-refused 27287, invisible-member 18, ambiguous 0) / wrong 0` | clean -- no reference goes to a declaration the compiler does not bind it to.  From 0.1.10 a variant's construction, an arm's pattern and an enum's own name are among the judged references: `variant 3 refs / 3 correct` on the file the before/after was taken on, where 0.1.9 was `3 / 0` and answered no-target (0.1.10) |
| `HintDiff` | 0 | `ran 37046 / skipped 78 (compiler-cannot-parse 51, arg-boundary-disagreement 12, compiler-refused-the-file 15, check-timed-out 0) / wrong 0` | clean -- every hint names the parameter its declaration gives, and every declared parameter has a hint (0.1.10) |
| `HintNames` | 0 | `ran 2094 / skipped 12 (threw 0, missing-corpus-file 0, check-timed-out 0, nothing-was-judged 0, no-model-entry-for-this-def 7, too-large 0, compiler-refused-the-file 5) / wrong 0` | clean, with the refused-file case counted rather than called a disagreement (0.1.10) |
| `HintShapes` | 0 | `ran 9357 / skipped 4 (parameterHints-threw 0, missing-corpus-file 0, too-large 0, too-large-for-prefix-sweep 4) / wrong 0` | clean -- every hint label drawn is a plain identifier (0.1.10) |
| `HintDupes` | 0 | `ran 2099 / skipped 0 (symbols-threw 0, missing-corpus-file 0, too-large 0) / wrong 0` | clean -- no repeated or empty parameter name (0.1.10) |
| `SymbolDiff` | 1 | `ran 90 / skipped 44 (threw 0, missing-corpus-file 0, too-large 0, type-spelling-only-difference 2, tree-reports-more-declarations 42, scan-reports-more-declarations 0) / wrong 13` | regression detector, above -- `wrong 13` unchanged; the tree model now also declares a variant's payload fields, and the retired token scan cannot see those either (0.1.10) |
| `FoldDiff` | 1 | `ran 68 / skipped 38 (threw 0, missing-corpus-file 0, too-large 0, granularity-only-difference 38) / wrong 41` | regression detector, above -- unchanged this round: the folds moved in 0.1.9 with the §13 corpus and this round's changes are names, not blocks (0.1.10) |
| `FeatureProbe` | 0 | `ran 1621 / skipped 1 (missing-corpus-file 0, too-large 0, empty-or-whitespace-only 0, needs-an-application-instance 1) / wrong 0` | clean -- all six features that had only a registration answered as the invariant requires (0.1.10) |
| `ParamNames` | 0 | `ran 2099 / skipped 6413 (harness-threw 0, missing-corpus-file 0, file-too-large 0, not-a-callable 6413, callee-not-in-this-file-and-not-a-builtin 0, builtin-arity-only-SPEC-md-names-none 0) / wrong 0` | clean -- every parameter list names what its declaration names, or names nothing (0.1.10) |
| `HintTruth` | 0 | `ran 8215 / skipped 7660 (harness-threw 0, missing-corpus-file 0, file-too-large 0, no-argument-call 3012, callee-not-declared-in-file 4648, builtin-arity-documented-without-names 0) / wrong 0` | clean -- every label drawn is a parameter name the declaration gives for that argument (0.1.10) |
| `HoverTruth` | 0 | `ran 13028 / skipped 81 (harness-threw 0, spec-documents-the-arity-without-parameter-names 24, compiler-refused-the-file 43, nested-def-outside-the-model 12, variadic-signature-declares-no-parameter 1, builtin-signature-writes-no-return-type 1) / wrong 0` | clean -- every hovered row says what the compiler, `SPEC.md` or the absence of a declaration says.  The counted class `payload-field-not-a-model-declaration` is **gone**: a payload field is a model symbol with the variant as its owner now, so the 15 positions it held are judged and correct (**0.1.10**) |
| `RenameOracle` | 0 | `ran 901 / skipped 6008 (compiler-refused 5800, too-large 0, missing-corpus-file 0, compiler-silent 42, not-attributable 166, unverified-lines 0, unverifiable-basis 0, too-many-candidates 0, new-name-refused 0, crashed 0, contradiction 0) / wrong 0` | clean -- the reference table names exactly the 2,998 uses the compiler binds and nothing else, 0 MISSED, 0 WRONG-SCOPE, 4 unprovable.  **New in 0.1.10**: it judges the §13 declarations at all (`enum 1 1 1 0 0 0`, `variant 21 42 42 0 0 0`), and that is what caught the find-usages hole -- 22 declarations were `NOT-SEARCHABLE` until `VelaUsageSearch.canSearchAt` learned to accept a leaf that resolves to itself (**0.1.10**) |
| `InspectionProbe` | 0 | `ran 281 / skipped 43 (crashed-file 0, missing-corpus-file 0, too-large 0, compiler-did-not-run 0, check-did-not-terminate 0, fix-wrote-nothing 0, inspection-class-disagrees-with-the-rule 0, does-not-parse 43, empty-or-whitespace-only 0, excluded-by-request 0) / wrong 0` | clean -- every finding walked to a refusal the compiler itself makes on that line (0.1.10) |
| `RenameWriteback` | 0 | `ran 830 / skipped 6079 (compiler-refused-file 5800, too-large 0, missing-corpus-file 0, no-references-and-silent 40, not-attributable 153, new-name-refused 0, new-name-collides 0, too-many-references 86, crashed 0, contradiction 0) / wrong 0` | clean -- the plugin's write-back wrote its own 1,779-reference write set into the file it read back, which the compiler accepts for 830 of 830, the declaration-only rename is refused for 828 of them, and the control fired (**0.1.10**) |
| `PlatformEntry` row 7 | 0 | `ran 2288 / skipped 163 (compiler-refused-file 0, too-large 0, missing-corpus-file 0, dump-unavailable 0, lex-unavailable 0, crashed 0, receiver-type-not-written 0, receiver-not-a-struct 0, receiver-not-a-written-binding 1, insert-not-a-callable 13, template-names-not-in-spec 149, contributor-threw 0) / wrong 0` | clean -- the offer set is the compiler's own declarations, every keyword `SPEC.md` 1.3 lists is offered, and the insert handler writes the declaration's own parameter names, including a variant's payload (**0.1.10**) |
| `PlatformEntry` row 9 | 0 | `ran 3519 / skipped 645 (callee-not-a-declared-def 624, struct-constructor-popup-closed 21, rebuilt-anchor-unavailable 0) / wrong 0` | clean -- and the popup opens for a **variant construction** now: `VelaNames.resolveCall` answers the variant, the handler shows its payload fields, and the 9 such calls on this corpus moved out of `callee-not-a-declared-def` into `find`/`show`/`update`, all correct (**0.1.10**) |
| `PlatformEntry` row 19 | 0 | `ran 8909 / skipped 1210 (closer-not-required 0, prefix-not-a-complete-program 48, indent-not-the-corpus-step 38, first-line 382, line-ends-with-brace 742) / wrong 0` | clean -- the closer is written exactly where the compiler's token stream says a bracket is, and Enter writes the indentation the corpus itself uses (**0.1.10**) |
| `PlatformEntry` (all three) | 0 | `ran 14716 / skipped 2018 (25 named classes) / wrong 0` | clean -- one run, three verdicts, every comparator asked to fire first (**0.1.10**) |

**One row of that table is 0.1.11's, not 0.1.10's, and the rest are 0.1.10's runs.**  The language changed the answer for one inspection, so that row was re-run against the tree's own `vm.exe` — **870,400 bytes, sha256 `aaa0a599f3dc3ca55b19ed6ead7debe6586e10d446b38cedc5add6397c61effc`**, the build that carries `+` on two `str`s — and it now reads `ran 287 / skipped 43 (crashed-file 0, missing-corpus-file 0, too-large 0, compiler-did-not-run 0, check-did-not-terminate 0, fix-wrote-nothing 0, inspection-class-disagrees-with-the-rule 0, does-not-parse 43, empty-or-whitespace-only 0, excluded-by-request 0) / wrong 0` over 330 corpus files, with a verdict that counts **2** registered inspections: `VelaStringConcatenation` was retired when `+` on two `str`s became legal (`SPEC.md` §1.5), so its seven findings went with it (13 → 7) and the survivors read `3 fired / 3 verified` and `4 fired / 4 verified`.  The row's own oracle is a *different binary* from the one every other row above compared against, which is exactly why this paragraph exists instead of an edited number.

**0.1.10 is the round that made the §13 names *usable*, not just parseable, and every number above is one run of one artifact against the frozen `vm.exe` 867,328 bytes / `1e52032c…`** (`ast-diff.ps1` and `psi-tree-diff.ps1` were re-run the same way).  What moved, and why:

* **The two reference tools judge §13 now, and that is the change.**  `RenameOracle`'s declaration walk and `GotoOracle`'s both gained `enum`/`variant`, so the compiler is *asked* about them for the first time; the plugin's table has to answer, and it does.  Before/after on `tests/build/enum_exhaustive_switch.vel`, same harness, same frozen compiler, the plugin build the only difference: `GotoOracle variant 3 refs / 0 correct / 3 no-target` → `3 / 3 / 0`; `RenameOracle variant 3 decls / 6 bound / 0 claimed / 6 MISSED / 3 wrong` → `3 / 6 / 6 / 0 / 0`.  Corpus-wide: `RenameOracle` **901 / 2,998 uses / 0 MISSED / 0 WRONG-SCOPE / wrong 0** with its own per-kind rows (`enum 1 1 1 0 0 0`, `variant 21 42 42 0 0 0`), `GotoOracle` **30,880 judged / 0 wrong**, `RenameWriteback` **830 declarations / 1,779 references / 2,609 writes / 828 refused-on-declaration-only-rename**.
* **The find-usages hole that exposed, and its fix.**  With the walk taught §13 and the plugin still blind at a *declaration's own name*, all 22 came back `NOT-SEARCHABLE` (`wrong 22`); one line in `VelaUsageSearch.canSearchAt` — accept a leaf that resolves to itself — makes them answer, and the row is `wrong 0`.
* **The popup opens for a variant construction** (`find`/`show`/`update` +9 each on this corpus, all correct), and **a payload field is a declaration** (`HoverTruth`'s counted class of 15 is gone; the row is 13,028 judged / 81 skipped / wrong 0).
* **`SymbolDiff` and `FoldDiff` did not move this round** (`wrong 13` and `41`, unchanged): the model gained payload-field symbols, which the retired token scan cannot see either, and that is the `tree-reports-more-declarations` class it already counts.
* **Two things named rather than implied**: a pattern *binding*'s name (`r` in `Circle(r)`) is still not resolved — the arm binds it positionally and the body's uses are `GotoOracle`'s `invisible-member` class, printed with their lines; and **no harness measures the semantic-highlight colour** a variant name is drawn with, which is why that decision is written where it is made and in the row.
* **A process finding with teeth**: the 0.1.9 plugin's classes survive only in `harness.ps1`'s own per-run snapshots, because three intermediate builds of this round overwrote `dist\vela-idea-plugin-0.1.9.zip` — the version was not bumped until the end of the round, and `build-offline.ps1` names the zip after `plugin.xml`.  The before/after pairs above were run from `build\tools\harness\classes\classes-snapshot-41940`; the version is now bumped *before* the first build of a round.

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


### 9. §13 made `HoverTruth` red: its oracle knew four declaration kinds and the language grew two (FOUND AND FIXED in 0.1.9)

The plugin's hover was not wrong; the tool could not see what the language had added.  `HoverTruth`
holds every declaration the compiler's dump prints to the hover, and it locates them by scanning
the source text for `struct`, `def`, `field` and `param`.  With SPEC.md §13 in the corpus:

* the dump now prints `enum name=Shape` and `variant name=Circle`, which its reader did not name --
  so `Color`, `Red`, `Green` and `Blue` were, to the tool, **names nothing declares**, and the axis
  that requires an undeclared name to hover as *nothing* reported the true hover `enum Color` as an
  invention: **21 findings, exit 1**;
* and the dump prints a variant's payload as `field name=radius type=float`, which its reader took
  for a struct field, while its text scanner (which never left the enum body) took the *next* line
  for one -- so the two sides disagreed at declaration 0 on ten files
  (`declaration-scan-disagrees-with-the-compiler-dump 10`, a class this tool calls "a defect, not a
  limit").

The raw lines, from the run that found it:

    0.1.9, before the fix:
      tests/build/enum_else_arm.vel:10 `Red`: neither this file nor the language declares it, and the hover says `variant Red` (summary: A variant of the enum `Color`)
      tests/build/enum_else_arm.vel:9 `Color`: neither this file nor the language declares it, and the hover says `enum enum Color` (summary: An enum declared in this file)
      tests/build/enum_payload_bind.vel: the compiler's dump and this harness's scanner disagree at declaration 0: the compiler says `field radius`, the scanner says `def u…`
      COVERAGE: ran 12863 / skipped 91 (... declaration-scan-disagrees-with-the-compiler-dump 10 ...) / wrong 21
      VERDICT: 21 row(s) disagree with the declaration the compiler prints [FAIL]

    0.1.9, after the fix (the same tool, the same corpus, the same frozen compiler):
      COVERAGE: ran 13013 / skipped 96 (... payload-field-not-a-model-declaration 15 ...) / wrong 0
      VERDICT: every one of the 13013 row(s) judged says what the compiler, SPEC.md or the absence of a declaration says [PASS]

Two things were fixed and one was *decided*: both sides of the tool now read `enum` and `variant`
lines (so the declarations are judged, and their names are no longer "undeclared"), a `field` line
whose nearest enclosing declaration is a variant is classified `payload` and not `field`, and the
payload is the counted class `payload-field-not-a-model-declaration` -- 15 positions, one per
payload field in the corpus -- because the plugin's model deliberately declares **no symbol** for a
payload field: nothing in the language can name one, a match arm binds new names positionally, so a
symbol would be a declaration no reader could navigate to.  That is a limit of the hover, named and
counted, rather than a hover the row demands and the plugin does not owe.

The general lesson, which is why this entry is here rather than in a chat log: **an oracle that
enumerates declaration kinds is a claim about the language**, and when the language grows a kind the
oracle goes red before the plugin does.  The red is worth keeping and reading -- it is what made
the four tools whose enumeration predates §13 (`HoverTruth`, `SymbolDiff`, `FoldDiff`, and
`RenameOracle`'s walk) visible in this round's numbers instead of silently passing by omission.

### 10. Two findings from the §13-names round: a harness bug of mine, and an artefact that was nearly lost (FOUND AND FIXED in 0.1.10)

**The harness bug.**  `HoverTruth`'s dump reader decides a payload field's owner where it recognises
the line (`field name=radius type=float` under a `variant`), and then the reader's **common tail**
overwrites it:

    e.nested = nestedInDef(parent);
    if (e.kind.equals("field")) e.owner = e.nested ? "" : structOf(parent);   // <- "" for a payload

`structOf(parent)` walks up looking for a `struct`, and a payload's parent is a **variant**, so it
answers the empty string.  The plugin was right and the tool was wrong, in the exact shape this
project keeps meeting: **15 findings, every one of them saying "the compiler nests it under `-`"
about a hover that said `field radius: float`, owner `Circle`**:

    0.1.10, before the fix:
      tests/build/enum_payload_bind.vel:14 `radius` (field)
          owner: the compiler nests it under `-`, the hover says `Circle`
          hover: field radius: float
      COVERAGE: ran 13028 / skipped 81 (...) / wrong 15
      VERDICT: 15 declaration(s) hovered differently from what the compiler says [FAIL]

    0.1.10, after (the same tool, corpus and frozen compiler):
      COVERAGE: ran 13028 / skipped 81 (...) / wrong 0
      VERDICT: every one of the 13028 row(s) judged says what the compiler, SPEC.md or the absence of a declaration says [PASS]

The fix is an explicit `if (e.payload)` arm before the field branch, with the reason written beside
it.  It is recorded because the ordering was invisible: the branch that *set* the owner and the
branch that *overwrote* it were four lines apart and both looked right on their own.

**The artefact that was nearly lost.**  `build-offline.ps1` names the dist zip after the version in
`plugin.xml`.  This round's version was not bumped until its last step, so three intermediate builds
wrote `dist\vela-idea-plugin-0.1.9.zip` over the 0.1.9 artefact — and with it the plugin classes
that every "before" number in this round is measured against.  They survived by luck and by
`harness.ps1`'s own design: it snapshots `build\classes` to
`build\tools\harness\classes\classes-snapshot-<pid>` before each run so a concurrent build cannot
disturb it, and `classes-snapshot-41940` (written 06:37:30, `VelaTargets.class` 18,797 bytes against
the current 19,862) is that snapshot.  Every before/after pair above was re-run from it with the
*same* harness and the *same* frozen compiler, which is what makes them pairs rather than anecdotes.
The lesson is the one the version discipline was written for, and it is now observed in the other
order: **bump first, then build**.

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
