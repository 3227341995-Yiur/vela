# Vela IDEA plugin — changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

**The rule, because the user asked for it explicitly: every plugin update bumps
the version, and every bump gets an entry here.**

The version lives in one place —
`src\main\resources\META-INF\plugin.xml`'s `<version>` — and everything else is
kept in step with it:

| where | how |
|---|---|
| `src\main\resources\META-INF\plugin.xml` | `<version>` — **the source of truth** |
| `build-offline.ps1` | reads the version out of `plugin.xml` and names the dist zip from it, so it cannot drift |
| `build.gradle.kts` | `version` and `sinceBuild`, for the Gradle path, kept in step by hand |
| this file | an entry saying what changed, **and what was verified and what was not** |

That last column is not ceremony. This project's older rule is that a claim
nobody measured does not exist, and this plugin has already shipped three
features that were finished and did nothing at all — a wrong file extension, an
action registered as a group, and three extension points that were never
registered (or registered under the wrong attribute name). An entry that says
"added X, unverified" is worth more than one that says "added X".

## 0.1.10 — the four things §13 could not do: variant names resolve, the popup opens, the colour is its own, the payload is a declaration

**What changed.** 0.1.9 taught the plugin to *parse* `enum`/`match`.  This round is the other half:
the names the language gained are now names the plugin can navigate, search, rename, hover and
complete, each proved by a tool that already existed and each with the before/after measured
against the 0.1.9 build's own classes.

* **A variant name and an enum's name resolve through the reference machinery.**
  `VelaTargetKind.ENUM` / `VARIANT` and the two sites in `VelaTargets.declarationFor`: a call whose
  name is a variant (`Circle(2.0)` — and an arm's pattern `Circle(r) { … }`, which is the same
  shape), a bare enum name in a type position (`c: Shape`), and a payload-free variant written as a
  value (`Empty`).  The before/after, same harness, same frozen compiler, only the plugin build
  different, on `tests/build/enum_exhaustive_switch.vel`:

      GotoOracle 0.1.9   variant  3 refs / 0 correct / 3 no-target
      GotoOracle 0.1.10  variant  3 refs / 3 correct / 0 no-target

      RenameOracle 0.1.9   variant  3 decls / 6 bound / 0 claimed / 6 MISSED / 3 wrong declarations
      RenameOracle 0.1.10  variant  3 decls / 6 bound / 6 claimed / 0 MISSED / 0 wrong declarations

  Corpus-wide: `GotoOracle` **30,880 references judged, 0 WRONG** (`invisible-member 18`, printed);
  `RenameOracle` **901 declarations judged, 2,998 bound uses, 0 MISSED, 0 WRONG-SCOPE, wrong 0**
  with a per-kind row of its own (`enum 1 1 1 0 0 0`, `variant 21 42 42 0 0 0`); `RenameWriteback`
  drives **830 declarations** through the plugin's own write-back and the compiler refuses the
  declaration-only rename for **828** of them.  Those 22 declarations were *not judged at all*
  before this round — `RenameOracle`'s declaration walk enumerated `def`/`struct`/`field`/
  `param`/`decl`/`for` and knew nothing of §13, so "0 MISSED" was true of a table that could not
  resolve a variant.

* **Find Usages answers at an enum's or a variant's own name.**  `VelaUsageSearch.canSearchAt` now
  accepts a leaf that resolves to *itself*: the token-shape rule it used before knows `struct X`,
  `def f`, `for i` and `name: T`, and §13 writes none of those.  Measured the hard way — with the
  walk taught §13 but this line missing, `RenameOracle` reported all 22 as
  `NOT-SEARCHABLE: the platform would refuse Find Usages on this declaration's own name`, and the
  same message for the *uses* (`the usage on line 17 (offset 553) cannot be searched for from its
  own name`).  `wrong 22` before the line, `wrong 0` after.

* **Parameter info opens for a variant construction.**  `VelaNames.resolveCall` answers a variant
  with a payload, and `VelaParameterInfoHandler` shows its payload field names — read off the
  variant symbol the model built from the `variant` node's own field list, which is the *same*
  reader the completion's `Circle` → `Circle(radius)` template uses, so the two cannot disagree
  about one declaration.  `PlatformEntry` row 9 grew by exactly those calls: on the same corpus the
  0.1.9 plugin counts them in `callee-not-a-declared-def` and the 0.1.10 plugin judges them
  (`find`/`show`/`update` each +9, all correct).

* **A payload field is a declaration.**  `VelaModel.readEnumFromTree` emits a symbol per payload
  entry, owned by the variant that carries it, so the hover for `radius` is the declaration the
  compiler's dump prints (`field radius: float`, owner `Circle`) — and `HoverTruth` *judges* it now
  instead of counting it: the class `payload-field-not-a-model-declaration` (15 positions) is gone
  and the row reads **13,028 judged / 81 skipped / wrong 0**.  It went red first and the red was
  mine, in the harness: the dump reader set the payload's owner and then the common tail overwrote
  it with `structOf(parent)`, which is empty for a payload because its parent is a variant and not
  a struct — 15 findings, every one of them saying "the compiler nests it under `-`" about a hover
  that was right.

* **A variant name has a colour of its own.**  `VelaNameKind.VARIANT`, drawn with the platform's
  constant colour, for all four positions the language writes a variant in (the declaration, a
  construction, a pattern, a bare value) — the compiler's own dump is the authority that a variant
  is a variant and not a plain name.  **Nothing measures this colour**: no harness asks which key a
  name is drawn with, `FeatureProbe` checks the keys the *lexer* draws against the ones the colour
  page registers, and these semantic kinds are drawn with platform defaults and registered
  nowhere.  The decision is written where it is made and it is named here, not implied covered.

**Still not done for §13, named with its cost.**  A **pattern binding's** name (`r` in
`Circle(r)`) is not resolved: the arm binds a new name positionally and the body's uses of it are
the `invisible-member` class (`GotoOracle` 18 positions, printed with their lines).  A **payload
field's** name is not resolvable either, and cannot be — nothing in the language writes one.  Both
are counted classes, not claims.

**A process finding, because it cost this round its cleanest artefact.**  The 0.1.9 plugin classes
were nearly lost: `build-offline.ps1` names the dist zip after the version in `plugin.xml`, and the
version was not bumped until this round's last step, so three intermediate 0.1.10 builds
**overwrote `dist\vela-idea-plugin-0.1.9.zip` with work in progress**.  The before/after runs above
were saved by `harness.ps1`'s own per-run class snapshots (`build\tools\harness\classes\classes-snapshot-*`),
which are the only surviving copy of the 0.1.9 plugin; the version is now bumped *before* the first
build of a round, which is what the discipline is for.

**What this does not prove.**  No IDE was started: every harness drives the plugin's own entry
points with stand-ins.  The frozen oracle is `vm.exe` 867,328 bytes, sha256 `1e52032c…`
(`LLVM-C.dll` 74,159,616 bytes, `1286e894…`), and the corpus was again not frozen — it grew by
three files while this round ran (`template-names-not-in-spec` 146 → 149), so the position counts
are one run over the tree as it stood.  `SymbolDiff` and `FoldDiff` remain non-zero by design
(`wrong 13` and `41`), and their numbers did not move this round: what changed for them is that the
tree model now also declares a variant's payload fields.

## 0.1.9 — the plugin follows the language into §13: `enum`, `match`, and the trees they make

**What changed.** `SPEC.md` §13 landed in the compiler while 0.1.8 was being measured: `enum` and
`match` became reserved words, and the corpus gained eighteen files (`tests\build\enum_*.vel`,
`tests\probes\enum_*`) whose trees this plugin's parser had never seen.  That is the language
moving and the plugin needing to follow, and this round is the follow: the two keywords, the PSI
the compiler prints for them, and every number in `FEATURE_PARITY.md` re-measured on the result.

* **The two keywords.**  `enum` and `match` are in `VelaTokenTypes.KEYWORDS` (the lexer's keyword
  set, shared with the completion and with `VelaDeclarations`' "a keyword is not a reference") and
  in `VELA_KEYWORDS` with the compiler's own ids 24 and 25.  The measurement that says whether they
  landed is `PlatformEntry` row 7's `bare-end` family, and it was red before this round and is green
  after it: on the §13 corpus the family stood at **139 wrong positions, one per file, each
  reporting `not offered: keyword enum, keyword match`**; it is now **146 judged / 0 wrong**.  The
  check can still fail, which is the part that matters: removing the two words from the plugin's
  keyword list again makes the same family report the same line and exit 1 —
  `tests/build/enum_else_arm.vel: the caret at the end of the file; not offered: keyword enum,
  keyword match`, `COVERAGE: ran 17 / skipped 4 (...) / wrong 1`, `VERDICT: ... [FAIL]` — and the
  raw output of that mutation is in the evidence.

* **The trees.**  The parser gained `enum`/`variant`/`match`/`subject`/`arm`/`binding`, mirroring
  `parse_enumdef`, `parse_variant`, `parse_match` and `parse_arm` in `selfhost/parts/parser.vel` --
  including what the compiler's *parser* refuses and, deliberately, not what its *checker* refuses:
  a nested pattern and `enum` used as a name are refused here, while exhaustiveness, a duplicate
  arm, an unknown variant and a payload's arity are left to `vm.exe check`, because the compiler's
  front end stops before them and a parser that guessed would refuse files the compiler accepts.
  `VelaSyntaxDump` prints the six shapes exactly as `selfhost/parts/dump.vel` does
  (`enum name=…`, `variant name=… fields=N`, one `field name=… type=…` per payload entry, `match`,
  `subject`, `arm pattern=… binds=N`, one `binding name=…`, then the arm's block).  Measured, same
  frozen compiler (`vm.exe` 867,328 bytes, `1e52032c…`): **`ast-diff.ps1` — 147 files in the corpus,
  133 identical, `147,346 node lines` compared, 0 different, 0 suspect, 0 missing, 14 the compiler
  refuses (all 14 of which this parser also refuses), `COVERAGE: ran 133 / skipped 14 / wrong 0`,
  VERDICT PASS** — against 117 files and 134,587 node lines in the 0.1.8 pass.  And
  **`psi-tree-diff.ps1` — 147 of 147 files replayed through the platform's own `PsiBuilderImpl`,
  147,388 composite nodes and 399,393 leaf tokens compared, `failed 0`,
  `COVERAGE: ran 147 / skipped 0 / wrong 0`, VERDICT PASS**.

* **The plugin's own features see the new declarations.**  `VelaModel` reads an `enum` as a
  declaration with its variants as children (`VelaSymbolKind.ENUM` / `VARIANT`), so the completion
  offers the enum's own type name at module level and a variant's name where a value is expected;
  `VelaHints.callTemplate` writes a payload-carrying variant as the call the language says it is
  (`Circle` → `Circle(radius)`) and writes nothing for a payload-free one (`Empty`), because
  `Empty()` is text the compiler refuses.  `PlatformEntry`'s insert family was taught the same rule
  and judges it: **`insert` 1909 judged / 0 wrong**, with **13 `insert-not-a-callable`
  positions** — one per payload-free variant in the corpus, counted and printed rather than called
  wrong, which is the honest reading of "this name is a value, not a call".  A payload field is
  *not* a symbol of the model, deliberately: nothing in the language can name one, so a symbol for
  it would be a declaration no reader could navigate to.

* **`HoverTruth` was red on this corpus and the red was the tool's, not the plugin's.**  Its dump
  reader and its text scanner knew `struct`, `def`, `field` and `param`; with §13 in the corpus an
  enum's variants looked like names nothing declares, so the axis that requires an undeclared name
  to hover as *nothing* reported the true hover `enum Color` / `variant Red` as an invention
  (21 findings, exit 1).  Both sides of the tool read `enum`/`variant`/payload now, and the one
  thing the plugin's model deliberately does not declare — a payload field — is the counted class
  `payload-field-not-a-model-declaration` (15).  The red run, the fix and the numbers are in
  `## Open defects` entry 9 of `FEATURE_PARITY.md`.  The same blindness is why `SymbolDiff`'s
  `tree-reports-more-declarations` went 25 → 42 and `FoldDiff`'s `wrong` 24 → 41: two difference
  detectors against a retired implementation, both non-zero by design, both moved by the corpus
  rather than by a regression.

**What does not follow, named rather than implied.**  These are the §13 things this plugin still
does not do, each with what it costs in the measurements above:

* **Variant names are not resolved by go-to-declaration, find-usages or rename.**  The reference
  table (`VelaTargets.declarationFor`) resolves a call to a `def` and a bare name to a struct; a
  variant is neither, so `Circle(2.0)` and `Empty` resolve to nothing.  `GotoOracle`
  (`ran 30880 / skipped 27305 / wrong 0`) counts those as `invisible-member`: the class grew from
  10 to 18 positions with the §13 corpus, and they are printed in its log.  `RenameOracle`
  (`ran 879 / skipped 5962 / wrong 0`, 0 MISSED, 0 WRONG-SCOPE) does not judge a variant at all,
  because its declaration walk enumerates `def`/`struct`/`field`/`param`/`decl`/`for`.  That is a
  coverage limit of those two tools for the new corpus, not a claim that a variant rename works.
* **Parameter info does not open for a variant construction.**  `Circle(` names no `def`, so
  `findElementForParameterInfo` answers null; `PlatformEntry` row 9 counts those calls as
  `callee-not-a-declared-def` (623, up 24 with the corpus) and judges none of them.
* **A variant name gets no semantic-highlight colour of its own.**  It is neither a type nor a
  callable, and `VelaSemanticHighlighting` has no key for "variant"; the name is left to the
  default colour, which is said in the code where the decision is made.
* **A payload field is not a symbol**, so the hover for one is the counted class above and the
  structure view shows a variant's payload as part of its detail rather than as child nodes.

**What this does not prove.**  No IDE was started: every harness still drives the plugin's own entry
points with stand-ins.  The compiler frozen for every number above is `vm.exe` 867,328 bytes,
sha256 `1e52032c…` (`LLVM-C.dll` 74,159,616 bytes, `1286e894…`), and the corpus was not frozen
either — `tests\` belongs to another track and is still growing, so the counts are one run over the
tree as it stood.  `SymbolDiff` and `FoldDiff` stay non-zero by design: they are the regression
detectors, and their numbers are the measured difference between the current reading and the
retired one.  The plugin's parser reproduces the compiler's *parse* refusals, not its *checker*
refusals: a file the checker refuses is a tree here and a diagnostic from `vm.exe check` in the
editor, which is where the refusal reaches the user.

## 0.1.8 — the four things 0.1.7 counted but did not fix

**What changed.** The 0.1.7 round moved rows 7, 9 and 19 to `implemented` and left a list of
things it had *counted* and deliberately not fixed. This is those, in the order it named them.

* **A type-annotated local's members were unreachable.**  `q: Vec2 = Vec2(1.0, 2.0)` followed by
  `q.` completed nothing and `q.dot(` opened no popup, because the model's type reader
  (`VelaNames.structTypeOf`) resolved only `self`, parameters and struct names — and `VelaModel`
  declares structs, defs, fields, methods and parameters and **no locals at all**.  This was not a
  measurement gap: a local written with a type annotation is the most ordinary way a local is
  written in this language (`vm.exe` refuses a binding without one: `binding 'n' has no type
  annotation`), so it was a hole a user would meet on their first program.  `VelaTargets` gained
  `localBindingType`, which reads the tree's own `decl` node — the same tree every other reader
  reads — innermost block first, so a rebinding in an inner block shadows an outer name the way
  the compiler's own rule (SPEC.md 6.2) says it does.  Measured, same harness, same frozen
  compiler: `PlatformEntry` row 7's `after-dot` family goes from **166 judged / 0 wrong with 29
  `receiver-is-a-local` skipped** to **194 judged**, and on the 0.1.7 jar those 28 newly judged
  positions are **28 wrong** ("the compiler declares [x, y, dot, scale]; not offered [x, y, dot,
  scale]") against **0** on this build; row 9's 6 `receiver-is-a-local` calls are judged too and
  are clean on both builds, because the by-name method fallback happened to name the right method
  there — the class disappeared because the *reader* now resolves the receiver, and that is said
  plainly rather than claimed as a second defect.  One position does **not** become judgeable and
  the harness says why in its own class: `o.inner.bump()`, where `inner` is a *field* of the
  enclosing struct — its type is in the compiler's dump and no binding writes it beside the name,
  which is `receiver-not-a-written-binding`, not something this plugin claims to resolve.

* **The dead half of `findElementForUpdatingParameterInfo` is alive and measured.**  0.1.7 wrote
  down that its `objectsToView` fallback could never run, and left it in place.  It could not: the
  items the platform hands back are `ParameterHint`s, and the code read `(shown[0] as?
  PsiElement)` — a cast that cannot succeed.  The consequence was user-visible: after any edit the
  anchor leaf is rebuilt, nothing found it, and the popup closed.  It now looks for the item among
  `objectsToView`, checks that the offset still holds the call's own `(`, puts the hint back on the
  leaf it finds, and answers `null` when it cannot — and `PlatformEntry` measures it: the new
  `update-rebuilt` family rebuilds the anchor leaf for every judged call, requires the handler to
  find it again, and requires `null` when `objectsToView` is empty.  Measured: **663 judged, 663
  wrong on the 0.1.7 jar, 0 wrong on 0.1.8.**

* **The build tree no longer fills up, and the negative harness no longer copies the compiler's
  74 MB library once per mutant.**  `VerifyPlugin` copies `vm.exe` and its sibling DLLs into each
  plugin root's `build\verify\`; the negative test builds one root per mutant, so a real copy per
  root cost ~890 MB per version and `build\` had reached **2,876,518,684 bytes**, of which
  **2,743,905,792** was `LLVM-C.dll` under `build\verify\`.  The DLL is now a *hard link* to the
  compiler's own file — the same bytes, no second allocation — with the copy kept as the fallback
  for a scratch tree on another volume, so the control is not weakened: the mutant still runs the
  real compiler, and every check that ran before still runs.  Measured: the six trees for versions
  that are no longer current (`build\verify\negative-0.1.4`, `-0.1.5`, `-0.1.6`, `mutation-0.1.4`,
  `-0.1.5`, `-0.1.6`) were **2,774,319,999 bytes** and their deletion took `build\` from
  **2,876,518,684 to 102,231,703 bytes** — the remaining 74 MB of that being the one `LLVM-C.dll`
  copy the 0.1.8 build had already made, which the next run turns into a link.  Their
  `*-report*.txt` files were kept in `build\verify\pruned-reports\` so the record of what each of
  those sets found is not silently lost, and the 0.1.8 negative set was re-run to prove the link
  did not weaken it: `evidence\build-trees-0.1.8-20260924-0546.txt`.

* **Every number in the coverage-triple table of `FEATURE_PARITY.md` that could be re-measured was
  re-measured on this jar**, and the rows that could not be are the ones whose tools are owned by
  the other track (`ast-diff.ps1`, `psi-tree-diff.ps1`) or whose measurement is a one-off with its
  own evidence file; each of those rows now says which of the two it is instead of carrying a
  number from another version.

**What this does not prove.**  No IDE was started: `PlatformEntry` still drives the platform's own
entry points with stand-ins for the `Document`, the `Editor`, the PSI and the three parameter-info
context interfaces, and that file names every one of them.  The row 7 numbers above are one run
over the corpus as it stands, with the compiler frozen at `20a15de8…` (the tree's `vm.exe` was
rebuilt by the other track after 0.1.7's evidence, so its `b69557cf…` is *not* the oracle these
numbers were measured against, and it was rebuilt again to 978,944 bytes before this entry was
written).  A local whose binding writes no type annotates a file the compiler refuses, so the rule
for it — shadow, and answer nothing — is reasoned from the compiler's own message and from the
tree, not measured on an accepted file.  `VelaGotoDeclaration` keeps its own token-scan reader for
a binding, because it needs the binding's *range* and not only its type; the two agree on every
typed local in the corpus (`GotoOracle`, `RenameOracle` and `RenameWriteback` re-run, same
numbers), and that they agree is measured, not assumed.  **And SPEC.md moved under this round**:
§1.3 gained the keywords `enum` and `match` at 05:59 (see §13 and the fourteen new
`tests\build\enum_*.vel` files), which the plugin's own keyword table does not know yet — measured
by re-running `PlatformEntry` on one file, whose row 7 `bare-end` family then reports `not offered:
keyword enum, keyword match`.  That is the language moving ahead of the plugin and it is named
here rather than papered over; the numbers in this entry belong to the SPEC.md of 05:35-05:44.



**What changed.** Three defects, all three found by *driving* the platform object the row is
about rather than by reading it, and all three are fixes to shipped behaviour:

* **The completion offered the language's builtin instead of the file's own declaration.**
  `VelaCompletion.offer` walked `VelaModel.BUILTINS` *before* the file's own symbols, so a file
  that declares `extern c def abs(x: float)` was offered the builtin `abs(n)` first and the
  dedupe then suppressed the file's own `abs` — and accepting that entry inserted `abs(n)`, a
  parameter that declaration does not have.  The rule was already written down twice in this
  plugin (`VelaTargets.declaredParameterNames` consults the language's table only for a name the
  file does not write, and `VelaInlayHints.parameterHints` follows it); the completion did not.
  A builtin whose name the file declares as a function, method or struct is now skipped.
  Measured: 4 positions of 2089 in the corpus, `template (n) names [n], and the declaration names
  [x]`; 0 after the fix.

* **The parameter-info popup emphasised the parameter the caret was in when it opened.**  The
  popup draws from `ParameterHint.index`, and only `findElementForParameterInfo` ever set it:
  `updateParameterInfo` computed the new index, told the platform through `setCurrentParameter`,
  and told the drawn string nothing — so the highlighted parameter never moved while the caret
  did.  And the index was recomputed from `callAt(context.offset)`, which reads the caret's *own
  line*, so on the second line of a multi-line argument list (`cap(a,\n  b)`) it answered nothing
  and the index stayed where the popup opened.  The call's `(` now travels on the item, the index
  is counted from it on every update, and it is written back on the item the platform hands to
  `updateUI`.  Measured: 690 emphasis and 23 index wrong positions of 2678 before, 0 after.

* **A method call's receiver parameter was drawn and emphasised.**  The popup drew the
  declaration's parameter list *as written*, including a method's leading `self`, while the index
  counts the parentheses' arguments — so every method call with a receiver was one parameter off
  (`p.moved(10, 1)` emphasised `self` for the argument the compiler binds to `dx`) and a call with
  no argument at all still emphasised `self` (`p.manhattan()`).  `VelaInlayHints.parameterHints`
  had already solved this (`declared.drop(1)`); the popup had not.  Measured: 5 emphasis and 3
  draw wrong positions, 0 after.

**What this does not prove, and what measured it.**  `PlatformEntry` — the 15th tool under
`tools\harness\src`, wired into `harness.ps1` as `-Tool PlatformEntry` — instantiates all three of
the objects rows 7, 9 and 19 are about and calls the method the platform calls: the completion
contributor with the platform's own `CompletionParameters` and a recording `CompletionResultSet`,
its `LookupElement.handleInsert` in a real `InsertionContext` over a real `OffsetMap`; the
parameter-info handler's five steps; and `charTyped` / `postProcessEnter` with an editor stood in
for.  One run over the corpus: **13096 positions judged, 1900 skipped in 23 named classes, 0
wrong** — `ran 2089 / wrong 0` for row 7, `ran 2678 / wrong 0` for row 9, `ran 8329 / wrong 0`
for row 19 — and every comparator is asked to fire on a real corpus position before its zeros are
read (a member dropped from the answer, a name invented, the argument index moved by one, a
handler that wrote nothing, an Enter one character short), or the tool exits 3.  **No IDE was
started**: what is measured is the decision — which characters are written and where, what the
caret and selection become, what the contributor offers and writes, what the popup is given and
what it draws — with the `Document`, the `Editor`, the PSI and the three parameter-info context
interfaces stood in for, and every stand-in is named in the evidence file.  The full raw run, the
before/after numbers for all three defects, and the four defects this harness had *itself* and
fixed on the way (a caret placed inside a string literal, a shadowed closer judged as a missing
one, a one-element list "reversed" by its own control, and a constructor looked up by the wrong
arity) are in `evidence\platform-entry-0.1.7-20260924-0520.txt`.

## 0.1.6 — the first inspections, and a highlight check that can fail

**What changed.**

* **Three `localInspection` rules, with working quick fixes.** `FEATURE_PARITY.md` said of
  this feature "no `localInspection`s and no quick fixes at all", and that was true: the
  classes existed and the verifier refused them, reporting all three as implementing
  `LocalInspectionTool` with "no registration and no class file names it".  The three
  registrations exist now, and each rule reports what the compiler itself refuses on that
  line: assignment to an immutable binding (fix: insert `mut `), `"a" + "b"` (fix: the
  `concat(...)` builtin), and an int and a float mixed in one operation (fix: `to_float(...)`
  on the int side).

  Measured against a frozen compiler over 311 corpus files: `ran 257 / skipped 54 / wrong 0`,
  with **zero findings on the 131 files the compiler accepts**, and 13 of 13 findings walked
  to a refusal the compiler makes on that exact line; every fix leaves `check` at exit 0.  The
  method matters, because `vm.exe check` reports only its *first* refusal: a finding is
  verified by fixing it and asking again, not by looking for a diagnostic on its line.  The
  negative control is one word changed in a rule's criterion, which gave `wrong 41` and exit 1
  before the word was put back.

* **`FeatureProbe`'s first section can fail again.**  It reported `BAD_CHARACTER` as having no
  colour key.  The plugin was right and the probe was blind: its lookup searched thirteen
  hardcoded token constants, the lexer emits the *platform's* `TokenType.BAD_CHARACTER`, and
  the section `continue`d before calling the highlighter at all.  It now compares the keys the
  highlighter draws against the keys the colour settings page registers, in both directions.
  A renamed key still passes, and that is a fact about this plugin rather than a gap in the
  check: the settings row and the highlighter hold the *same* `TextAttributesKey` object, so a
  rename moves both sides at once.  What the check catches is a row pointing at a different
  key, or a row carrying a name of its own.

**What this does not prove.**  No IDE was started.  `LocalQuickFix.applyFix`'s last four lines
(a write action over a live document) were not driven, the alt-Enter menu was not opened, and
whether a colour resolves in the user's *scheme* is recorded as a named skip
(`needs-an-application-instance`) rather than as a pass.

## 0.1.5 — every red verifier turns green, two that could never be right become decidable

**What changed.** Two defects, both found by measurement rather than by reading, and
two verifiers that were reporting things a reader could not act on.

1. **The scanner's truncated token list claimed a character it never read.**
   `VelaSyntaxScanner.scan` keeps the tokens it scanned before a failure and closes
   the list with a newline and an EOF. The newline was `newline(pos)` — a
   **one-character-wide** token at the offset the scan stopped at. On an unterminated
   string that offset is the opening quote, so:

   * the parser's token list claimed `31..32` (the `"`) *and* `31..36` (the string's
     contents) — the same character claimed twice, by two tokens of different kinds;
   * `psi-tree-diff.ps1` decides "this leaf is past the last token the parser read" by
     comparing against the largest end among the parser's tokens; that was the
     newline's 32 rather than the string's 31, so a leaf starting at exactly 31 was
     not `>= 32` and fell into the "every non-trivia leaf must be a token the parser
     claimed" check. **`tests/build/lexer_error.vel` failed it**, 125 of 126.

   The closing newline is now zero-width at the failure offset: still a token, still a
   statement boundary for a truncated statement, and it claims no character — which is
   the truth, the scanner never read one.

2. **`FoldDiff` and `SymbolDiff` gained the criterion their own numbers lacked.**
   Both compare the shipping tree-derived implementation against this plugin's
   **retired** one (a brace matcher and a token scan). Neither the compiler nor any
   other authority defines a fold-region list or a symbol list, so "62 differ" and
   "40 differ" were not readings of a defect and were being read as 62 and 40 bugs.
   Both tools now classify every difference:

   | tool | class | what decides it | corpus |
   |---|---|---|---|
   | `FoldDiff` | **granularity only** | every region of each list lies inside a region of the other — the two agree about *what text is foldable* and differ about how finely it is cut | 38 |
   | `FoldDiff` | **content** | some region lies inside no region of the other — a real disagreement, and the failing count | 24 |
   | `SymbolDiff` | **type spelling only** | the two lists become identical once the retired scan's `[int, 786432]` spelling is normalised to the canonical `Array[int,786432]`; the same declarations, spelled differently | 2 |
   | `SymbolDiff` | **structural** | the same symbol count as the retired scan, but a kind/name/line/parent difference, or a detail difference that survives the normalisation — the failing count | 13 |
   | `SymbolDiff` | **count differs** | the two disagree about how many declarations the file has, the tree reporting more than the retired scan — counted and printed, not a failure (`tree-reports-more-declarations` 25, `scan-reports-more-declarations` 0) | 25 |

   The rule for `FoldDiff` was **measured before it was written in**, and the
   measurement is kept: `tools\probes\src\FoldShapeProbe.java`. Over the 126-file
   corpus it finds 64 identical, 38 differing in both directions, 24 in one, and
   **0** files where any region is in no other list's spans.

   Read as totals, `FoldDiff`'s 62 differences and `SymbolDiff`'s 40 are these
   classes and nothing else: 38 + 24 = 62, and 2 + 25 + 13 = 40. Only two of the
   five are **disagreements** — `FoldDiff`'s content class (24) and `SymbolDiff`'s
   same-count-different-content class (13). The other three (38 granularity only, 2
   type spelling only, 25 count differs) are counted and printed and are not
   failures. Exit codes follow that line: `FoldDiff` exits 1 only for the 24 content
   files, and `SymbolDiff` only for the 13 whose count matches but whose content
   differs.

3. **`HintNames` stopped calling a stated limit a disagreement.** On
   `tests/build/check_cases/unannotated_parameter.vel` — `def f(n)`, refused by the
   compiler — the tree correctly declares **no** `param` node (an untyped parameter is
   not a declaration this language has) while the symbol model reports `[n]`. That was
   the tool's one `wrong`. It is now counted as
   `not-judgeable-in-a-refused-file`, a printed category, because "the two sources of
   a parameter list disagree" and "the parser refuses to declare something illegal"
   must not print the same way. **A disagreement in a file that did parse stays a
   disagreement.**

4. **The cache-write claim is proved by causing it, not by reading the code — and
   proving it found a second defect.** `GotoOracle --probe-cache` runs **8 threads
   calling the real `saveCache` on one path** while a reader samples the file and
   rejects any snapshot that is not a whole cache. It checks its own instrument
   first — a deliberately cut-off line, a line with no tab, and a newline inside a
   value must all read as torn — because a verifier that cannot fail proves nothing.
   `harness.ps1 -Tool GotoOracle -CacheProbe` runs it.

   The first three runs of the probe were wrong in three different ways, and each
   mistake is written into the source so it is not repeated:

   | run | reported | what it actually was |
   |---|---|---|
   | 1 | 9,065 of 9,025 snapshots "torn" | the reader seeing the window in which `Files.move` has not yet made the path visible — an instrument fault, now its own counted outcome ("read during the move's window") |
   | 2 | 8,151 "torn" | **the probe's own fixture**: values were written as `"21,34\n99,120"`, and this cache is one entry per line, so the second half of every such value was a line with no tab. The real format is `key<TAB>comma,separated,lines` |
   | 3 | 38 of 320 saves threw, and no snapshot was torn | `java.nio.file.AccessDeniedException: probe-cache.txt.tmp -> probe-cache.txt` — **a real defect in `saveCache`** |

   The defect: on Windows a rename over an existing file fails with
   `AccessDeniedException` while another handle has the target open, and
   `saveCache` only caught `AtomicMoveNotSupportedException`. So under exactly the
   contention the `synchronized` was written for, the write threw instead of
   landing. Nothing was ever torn (the old file survived whole), but the entries in
   that map were **silently not on disk**. The atomic move is now retried, bounded,
   with backoff, and a failure that survives the retries is rethrown as itself. After
   the fix, with the same 8 threads on one path: `320` of `320` saves returned,
   `0` threw, **`0` torn snapshots out of `11,608` reads**, `0` short snapshots, and
   the final file holds all `2,000` keys every save wrote.

   This is the only defect in this release that was found by a verifier written in
   the same release.

**What this release PROVES** — every number below is from a `harness.ps1 -Tool All`
pass over this build, and the raw log is kept at
`idea-plugin\evidence\harness-0.1.5.txt`; the `SymbolDiff` / `FoldDiff`
classification in the row below was re-run afterwards, and its raw output is
`idea-plugin\evidence\detectors-0.1.5.txt`:

| tool | coverage triple | verdict |
|---|---|---|
| `ast-diff.ps1` | `ran 114 / skipped 12 (compiler-refused 12, too-large 0, missing-corpus-file 0, compiler-crashed 0) / wrong 0` | PASS |
| `psi-tree-diff.ps1` | see the evidence file | PASS, 126 of 126 |
| `GotoOracle` | see the evidence file | 0 wrong |
| `HintDiff` / `HintNames` / `HintShapes` / `HintDupes` | see the evidence file | 0 wrong |
| `SymbolDiff` / `FoldDiff` | see the evidence file | 13 structural, 24 content |
| `FeatureProbe` / `ParamNames` / `HintTruth` | see the evidence file | 0 wrong |

**What it does NOT prove, stated plainly.**

* No IDE was launched. Nothing here shows the plugin loads, that a tool window
  appears, or that any feature looks right on screen; the strongest evidence remains
  the platform-registration read-back plus these headless differentials.
* `SymbolDiff` and `FoldDiff` are still **regression detectors**. Their class names say
  what a difference is *made of*, not that the shipping side is better: where the two
  disagree about granularity or spelling, the choice is a design decision that has been
  read and judged by example, not measured against an authority.
* The `partial` rows stay `partial`. Completion, hover, find-usages, rename and the
  typed/Enter handlers still have **no behavioural measurement** — only a platform
  registration, which proves the platform will ask and nothing about the answer. The
  real-PSI-parser row's verifier is now green; the row's own status is discussed in
  `FEATURE_PARITY.md` rather than decided here.

## 0.1.4 — the verifiers are made falsifiable, and the AST window grows a second mode

**What changed, and why each one was a bug rather than a feature.**

1. **The go-to-declaration oracle was crashing on every file with an `extern`
   declaration, and the crash was invisible.** `GotoOracle.bindUseLines` answers
   `null` for "the compiler noticed nothing about renaming this declaration" and
   an empty list for "not verified", and the call site used the answer as if it
   were always a list. The enhanced-for over `null` threw
   `NullPointerException: Cannot invoke "java.util.List.iterator()"`, the per-file
   `catch (Throwable)` recorded it as "the oracle could not run", and the run
   still ended with `VERDICT: no reference goes to a declaration the compiler
   does not bind it to`. Nine files were never judged —
   `tests/probes/extern_*.vel`, `tests/build/extern/extern_c_probe.vel`,
   `selfhost/vela.vel`, `tests/run_tests.vel`, and two
   `tests/safety/cases/hole_*` files — and the verdict was a pass over whatever
   happened to survive. Fixed, and the `-` cache spelling the cached branch
   already understood is now what the null case writes.

2. **Every verifier now ends with a coverage triple, not a bare verdict.**
   `COVERAGE: ran <n> / skipped <n> (<why> <n>, …) / wrong <n>`. Every category a
   tool can skip for is **declared up front and printed even at zero**, because
   "0" and "this category was never counted" must not look the same, and
   `ran + skipped` is asserted against the corpus so a file that is neither
   compared nor categorised is a printed NOTE rather than silence. This is the
   general repair of the failure in (1): a verdict is now falsifiable by its own
   measurement.

3. **The verdicts now have exit codes.** `AstDiff`, `SymbolDiff`, `FoldDiff` and
   `HintNames` exited 0 unconditionally, so `VERDICT: FAIL` and
   `VERDICT: PASS` were indistinguishable to a build. Now: 1 = the plugin is
   wrong, 3 = the harness or the corpus is at fault, 0 = a pass. `GotoOracle`
   exits 3 for a crashed file instead of reporting a pass.

4. **A crash and a disagreement are counted apart.** `SymbolDiff` and `FoldDiff`
   counted a thrown exception as `differ`; `HintDiff` counted an argument
   position with no hint drawn as nothing at all. Both are now their own
   counters, and the missing-hint case is a finding.

5. **`AstDiff` names the manifest line for a corpus entry that points at
   nothing.** `tests/cases.txt:174` names `tests/build/lexer_error.vel`, which is
   not on disk, so the harness was FAIL with **zero** tree differences — 108 files
   identical over 119,923 node lines and one MISSING row. That is a defect of the
   corpus, which this plugin's owner does not own; it is now its own category
   (`missing-corpus-file`) with its own exit code, and it is reported rather than
   absorbed.

6. **`VelaSyntaxDump` is now reachable, so the "inert feature" check is
   satisfied honestly and not by an allowlist.** The verifier had begun failing it
   under `[check unregisteredImplementations]`: it compiled, shipped, and nothing
   in the jar could call it. It is now the **second mode of the Vela AST tool
   window** — `Live parse (editor buffer)` — which parses the text the editor is
   holding (unsaved edits included, and files that were never saved) and prints it
   in the compiler's own tree format. `vm.exe parse` can only ever describe the
   last saved bytes, so this is a capability and not a duplicate. The window has
   one view and one active mode, so the two modes cannot race for the same tree
   model; `Compiler (vm.exe parse)` is the default and the behaviour is unchanged.

7. **The oracle's cache write is safe under its own concurrency.** Eight threads
   called `Files.write` on one path every 200 new entries, so two could tear the
   file — and a torn cache is not a crash, it is a *wrong oracle* on the next run,
   because the value is a list of line numbers keyed by a declaration hash. It is
   now one writer at a time, through a temporary file and a move.

**What was measured, in this version**

- The four version locations read back at `0.1.4` (plugin.xml `<version>`,
  `build.gradle.kts` `version`, the dist zip's own name, the heading in this file).
- `build-offline.ps1` exit 0, `RESULT: PASS`, and the artifact it wrote:
  `dist\vela\lib\vela-idea-plugin.jar` **344,619 bytes** (sha256
  `db00087120f914ce7a76c49c540c69c969a2c08cc05b3186944fdf7058684415`) and
  `dist\vela-idea-plugin-0.1.4.zip` **324,186 bytes** (sha256
  `a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033`, one entry:
  `vela/lib/vela-idea-plugin.jar`).  36 Kotlin sources, 148 class files, highest
  bytecode major 65 (Java 21).
  These numbers moved twice during the round and both moves are recorded rather than
  hidden: 344,626/324,198 (sha256 `55bf14a3...`) was the build before the `isSystem`
  fix below, and 344,574/324,147 (sha256 `b43c480b...`) is the PyCharm 2025.3 build of
  the same sources. Whatever a reader finds in `dist\` should match the first pair.
- **What could NOT be verified about installability**: no IDE was launched, so
  "install this zip and it loads" is not measured here.  What *is* measured is
  everything short of that: the descriptor inside the jar is byte-identical to the
  source descriptor, every class named by `plugin.xml` is in the jar, every
  extension point id resolves to a declaration in the installed platform, the
  attribute each registration uses is the one the platform's own descriptor binds,
  all 30 registered classes are re-read out of the jar as instances of the exact
  interface the platform requires, and the class file major version is one IDEA
  2024.2+ accepts.
- The verifier's `RESULT: PASS`: 36 Kotlin sources, 145 class files, every
  registration read back against the platform's own declaration of its extension
  point, and the headless behaviour checks.
- `FEATURE_PARITY.md` is new: one row per user-visible capability, with what the
  Python plug-in does (grep out of `python-ce.jar`/`python.jar`/`python-dap.jar`
  at `PythonCore 253.28294.336`), Vela's state, the file that implements it, and
  the harness or platform registration that backs it.
- **`vm.exe debug` stays `refused-deliberately`.** It is not advertised anywhere
  in the plugin: `VelaRunConfiguration` implements
  `RunConfigurationWithSuppressedDefaultDebugAction` and the configuration is
  written to accept any profile that is not `Debug`, so IDEA shows the refusal
  instead of a button that would attach to nothing. No debugger was implemented
  and none is claimed.

**Open defects this version ships with, and the raw numbers behind them.** These are
written here, in the release entry, rather than in a note somewhere else, because a
release note that only lists what went well is the thing this project keeps paying for.

1. **`HintDiff`: 30 argument positions get no parameter-name hint, and 125 cannot be
   judged.** Raw final line:

   ```
   VERDICT: 30 hint position(s) are not right
   COVERAGE: ran 29006 / skipped 127 (compiler-cannot-parse 2,
             arg-boundary-disagreement 125) / wrong 30
   ```

   Everything that *is* drawn is right — `hints drawn 10688 / correct 10688 / WRONG 0 /
   beyond 0` — so this is not the `s: s: s:` class of defect. Every one of the 30 is an
   argument whose text is a string literal containing `'`, `(` or `)`, or a nested call:
   `ck_quoted(s, x, "', which is not declared 'pure'")`, `concat(a, " does not fit in
   int (64-bit signed)")`, `perr(kind, msg, ti_line(tk, cx))`. The suspicion is the
   character-scanning argument-range reader in `VelaInlayHints.kt`
   (`argumentRanges` / `endOfQuoted` / `matchingParen`); the declaration reader is clean
   (`HintDupes`: 0 repeated parameter names, 0 empty names). All 30 are in
   `selfhost/parts/*` and the same text again in `selfhost/vm.vel`. **Named, not
   fixed**, and it is why the parameter-hint row is `partial` in `FEATURE_PARITY.md`.

2. **`AstDiff` now PASSES, and it passes over a corpus that grew by six files.**
   Final: `126 files in the corpus / 114 match (121 856 node lines) / 0 different /
   0 suspect / 0 missing / 12 compiler refused (all 12 of which this parser also
   refused) / 0 crashed / VERDICT: PASS`, exit 0. An hour earlier the same harness was
   `FAIL` with `0 different` — the only non-match was `tests/cases.txt:174` naming
   `tests/build/lexer_error.vel`, which did not exist. Another track created that file
   during this round, which fixed the manifest and immediately exposed defect 3 below.
   The harness now reports such an entry with its manifest line and its own exit code
   (3 = corpus defect, 1 = the plugin is wrong), so the two can never be confused again.

3. **`psi-tree-diff.ps1` fails 1 of 126 files, and the file that found it was a corpus
   entry pointing at nothing.** Raw:

   ```
   tests/build/lexer_error.vel: leaf VELA_STRING at 31..38 is not a token this parser claimed: `"hello)`
   files replayed through the platform's builder : 126 of 126;  ok 125  failed 1
   VERDICT : FAIL
   COVERAGE: ran 125 / skipped 0 (missing-corpus-file 0, replay-threw 0, too-large 0)
   ```

   `tests/cases.txt:174` named `tests/build/lexer_error.vel` while the file did not exist;
   another track created it during this round and it immediately found this. On a file
   whose scanner refuses (an unterminated string), the parser keeps only the tokens
   before the failure, `VelaLexer` produces one `VELA_STRING` for the whole of `"hello)`,
   and the replay's `alignEndAfter` pulls that token into the tree — where the harness
   asserts every non-trivia leaf is a token the parser claimed. The compiler and the
   parser *agree* that the file is not legal Vela, so the plugin is right about the
   language and inconsistent with itself about which token covers an unterminated
   string. Row 5 is `partial` until that is decided. A corpus entry pointing at nothing
   was hiding a corpus entry that tests something.

4. **`since-build="253"` was false, and it was found by keeping the README's promise.**
   `build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"` failed with
   `VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'` —
   `ProcessOutputType.isSystem(Key)` exists in IntelliJ IDEA 2026.2 and not in PyCharm
   2025.3, so the sources did not compile against the platform `plugin.xml` declares.
   Fixed with an identity comparison (`outputType === ProcessOutputType.SYSTEM`), which
   exists on both platforms and cannot throw the way `ProcessOutputType.fromKey` does.
   Both builds now pass from this same tree: 253 -> jar 344 574 / zip 324 147
   (sha256 `b43c480b71e59843b85002e183cc1aa04f38b4eeb03d8926ef9d5bccda206fc2`),
   262 -> jar 344 619 / zip 324 186
   (sha256 `a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033`, the
   shipped artifact). **This is the one defect in this list that is closed.**

5. **`HintNames`: one disagreement.** `tests/build/check_cases/unannotated_parameter.vel line 2 `f`  tree=[]  model=[n]`. That file is a compiler-refused case (`def f(n)` with an unannotated parameter), so the tree records no parameter and the symbol model reads `n` out of the detail text. On illegal Vela the tree is the defensible reading, but the two sources disagree and the count says 1 rather than 0.

6. **`harness.ps1 -Tool All` could not finish, and that is why two of these numbers had never been produced.** `HintShapes` swept every 16-byte prefix of every file, which is O(size^2 / step): `selfhost/vm.vel` is about 400 KB, so one file cost ~25 000 parses of an average 200 KB document. Two separate `-Tool All` runs sat on `=== HintShapes` for over 50 minutes, which means `HintDupes`, `SymbolDiff`, `FoldDiff` and `FeatureProbe` **had never run in an `All` pass at all** - and their absence was being read as their agreement. The sweep is now bounded to at most 256 prefixes per file (`runs: 5797`, `too-large-for-prefix-sweep 4`, both printed) and an `All` pass completes in under four minutes.

7. **`SymbolDiff` 40 files differ and `FoldDiff` 62 files differ**, both against this
   plugin's own *retired* implementations rather than against an authority (there is no
   authority: the compiler prints no fold regions and no symbol list). The differences
   are the replacement — the tree model lists `extern` declarations the token scan
   missed; the tree folds a whole body from `{` to `}`, which is what PyCharm does for a
   method body. Both are enumerated file by file in `build\evidence\harness-0.1.4.txt`.
   No external authority exists to call each individual difference an improvement, and
   saying otherwise would be a claim.

**Two verifier holes were found and fixed while producing the negative proof** — both
found by the negative test rather than by reading, and both the same shape: a check that
was green because its question was weaker than it looked.

- `check smoke` used an early `return`, which aborted the whole compiler section —
  including `emitterHeaderContract`, a static comparison of two texts that needs no C
  compiler. So the one check that exists for a broken emitter/header contract never ran
  when the build actually broke. The build result is remembered now, and only the checks
  that need a built executable are skipped.
- `emitterHeaderContract` asked `header.contains(symbol)`. Renaming
  `vela_bounds_check` to `vela_bounds_check_renamed` in `runtime/vela_runtime.h` still
  "contained" the name the emitter calls, so the check printed
  `vela_bounds_check ... all defined` over a header that no longer declares it. It is a
  whole-word match now.

**What was NOT verified, in this version**

- No IDE was launched. No IntelliJ instance can be started on this machine in
  this session, so *nothing* here is evidence that the plugin loads, that a
  tool window appears, that a menu entry is where it should be, or that any of
  these features looks right on screen. The strongest available evidence is the
  platform-registration read-back plus the headless differentials, and it is
  labelled as such in `FEATURE_PARITY.md`.
- The `Live parse (editor buffer)` mode is compiled and reachable; it has **not**
  been run inside an IDE. What is verified is that its parse is the compiler's
  parse (that is `ast-diff.ps1`), not that the Swing panel renders it.
- The mutation proof is current, and it is a *different file* from the pass:
  `build\verify\mutation-0.1.4\mutation-report-0.1.4.txt` (version, jar SHA256 and
  time in its first lines, plus one verifier log per mutant) reports **13 mutants,
  12 caught, 0 holes, 1 control correct, 0 script/verdict problems** with a clean
  baseline — `classRegisteredNowhere` is genuinely caught, by
  `[check unregisteredImplementations]` reading 1,332 required types out of the
  installed platform's own descriptors. `build\verify\negative-0.1.4\negative-report.txt`
  is the older A–I set, now **12/12 caught, 0 missed, 0 void**. What is still not
  caught: a class implementing a contract no installed extension point declares and
  registered nowhere — there is no list to check that against; the count in this build
  is 0.
- **No IDE was launched**, so "install this zip and it loads" is not measured.



## 0.1.3 — built and verified; the first version that compiles

**Why there is a 0.1.3 at all.** 0.1.2's entry below says "unverified, not yet
built", and it was written that way honestly: the harness that runs commands on
this machine was dead for that whole round. The first round with a working shell
put 0.1.2's sources through `build-offline.ps1`, and they did **not** compile —
four errors, in two files, of a kind no amount of reading finds:

- `VelaFormatter.kt` — on this platform (`IU-262.9437.185`, kotlinc
  2.3.20) `ASTBlock.getSpacing` takes `Block?` as its first parameter, not
  `Block`: `VelaRootBlock` and `VelaLeafBlock` were reported both as "does not
  implement abstract member" and as "overrides nothing". Two signatures fixed.
- `VelaFindUsages.kt` — `private val wordsScanner` generated a
  `getWordsScanner()` accessor that clashes at the JVM level with the very
  interface method (`getWordsScanner`) the class overrides. The field is now
  `scannerOnce`, with the reason written at the declaration.

0.1.2 was therefore never built and never shipped; the four fixes are recorded
here rather than by editing 0.1.2's own entry, because a version's entry is what
that version was, not what it should have been.

**What was measured, in this version**

- All 32 Kotlin sources compile under the offline path (`kotlinc` from the
  installed IDE, no Gradle, no network). It is the first time anything in this
  plugin has been compiled at all.
- `jar` 256,668 bytes; `dist\vela-idea-plugin-0.1.3.zip` 240,264 bytes.
- The verifier's `RESULT: PASS` — every structural, bytecode, platform,
  registration, linkage and behavioural check (11.0 s; the full list is
  `build\logs\verify.log`, the machine-written surface is
  `build\logs\surface.txt`).
- **A verifier defect was found and fixed while getting there**, and it is worth
  naming because it is the same class of bug the verifier exists to catch: the
  `<with attribute="..." implements="...">` scan read a fixed 4000-character
  window after each `<extensionPoint>` and stopped only after 400, so the *next*
  declaration's attributes were credited to this one. `configurationType` is
  declared with `interface=` and has no `<with>` of its own, so it inherited its
  neighbour's spelling and eight of this plugin's registrations were reported as
  unbound attributes. The scan now reads only the declaration's own body — from
  the end of the tag to the first of `</extensionPoint>` and the next
  `<extensionPoint`, and nothing at all for a self-closing declaration. The eight
  are the platform's own spelling, checked against the platform's own plugins:
  `implementation=` for `configurationType`, `runConfigurationProducer`,
  `gotoDeclarationHandler`, `colorSettingsPage`, `typedHandler`,
  `enterHandlerDelegate`, `codeStyleSettingsProvider` and
  `langCodeStyleSettingsProvider` — every one of those points is registered that
  way, dozens of times, inside `D:\JetBrains\IntelliJ IDEA 2026.2.1\lib\*.jar`
  and `plugins\**\*.jar`.

**What is still not verified, and cannot be from here**: the plugin has never
been loaded into a running IDE. `ActionManager` and the extension registry need a
booted application, so folding, typing behaviour, the formatter's output and the
Run console are checked structurally, against bytecode and against the
descriptors — not by driving the editor. Installing the zip is what settles that.

## 0.1.2 — unverified, not yet built

Everything below exists as source in this tree and **nothing has been compiled or
run**: the harness that runs commands on this machine died after a build (a
detached `cl.exe` helper keeps its Windows job object non-empty), and it stayed
dead through the work. `idea-plugin/BUILD_CHECKLIST.md` holds the compile gate,
the most likely error sites, and the acceptance tests, so the first round with a
working shell can settle all of it in one pass.

**Editor mechanics — 10 Kotlin files, 11 registrations**
- Formatting: `VelaFormatRules.kt` (a pure function of the token sequence),
  `VelaFormatter.kt`, `VelaTokenScan.kt`, and code-style settings
  (`VelaCodeStyle.kt`) so the indent size is a visible setting.
- Folding (`VelaFolding.kt`), comments (`VelaCommenter.kt`), brace matching
  (`VelaBraceMatcher.kt`, `{ }` `( )` `[ ]` only), typed and enter handling
  (`VelaTypedHandler.kt`), a colour scheme page (`VelaColorSettingsPage.kt`), and
  live templates (`VelaLiveTemplates.kt` plus `resources\liveTemplates\Vela.xml`:
  `defn`, `main`, `struct`, `forr`, `whilee`, `ifel`, `parfor`).

**Navigation and insight**
- Go to declaration (`VelaGotoDeclaration.kt`), semantic colouring
  (`VelaSemanticHighlighting.kt`), parameter-name inlay hints
  (`VelaInlayHints.kt`), find usages (`VelaFindUsages.kt`, with a real
  `WordsScanner`), and the reference/rename plumbing (`VelaReferenceContributor`,
  `VelaLeafManipulator`).

**Registrations corrected**
- `psi.referenceContributor` was registered under `implementationClass`, which the
  platform ignores — the point's bean annotates its field as
  `@Attribute("implementation")`, so rename and find-usages were dead while
  Ctrl+Click worked. Fixed, with the three-way proof recorded in `plugin.xml`.
- Three classes were finished and never registered at all: the semantic annotator,
  the go-to-declaration handler, and the inlay-hints provider. All three are
  registered now, and the verifier is being taught to catch the class of bug.

**Run configuration**
- Run now interprets the file directly; no executable is produced, and the console
  prints the command line, the interpreter's output and the exit status.
- The console showed nothing at all, by construction: the configuration built its
  own `ConsoleView` and printed into that, while the Run toolwindow shows the one
  the platform creates for the handler. Output now goes through
  `notifyTextAvailable`, so the platform's console is the only console and the
  child's own stream type is carried through (diagnostics keep their error
  colour).
- **Run was the wrong shape and is now one child, not two.** It built first
  (`vm.exe build`) and then started the `.exe`, which needs a C compiler and
  MSVC's environment, writes `.c`/`.obj`/`.exe` beside the user's source, and
  inherits an include-path problem: the shipped `vm.exe` resolves its runtime
  include directory to the *relative* string `runtime`, so from the user's project
  directory — `vela\tests` — it died with
  `fatal error C1083: cannot open include file: 'vela_runtime.h'`, exit 2, and
  nothing reached the screen. `vm.exe run <file>` needs none of that. The run
  path is now: print the exact command line, start the interpreter, forward every
  byte it writes, propagate its exit status.
- The price is stated rather than hidden: what the console shows is the
  *interpreter's* output, which is slower than a compiled binary (the corpus's
  `run` cases measure both; the README puts the interpreter at ~76x on a loop MSVC
  cannot vectorise). Compiling remains `Vela.BuildAndRun`.
- A missing compiler is now reported **in the console** rather than only as a
  dialog: `checkConfiguration` still refuses a missing *file*, but the compiler is
  the run path's own business, so a run that cannot start explains itself in the
  place the user is looking.

**Known and deliberate gaps**, all named in `BUILD_CHECKLIST.md`: the Code Style
page's Spaces/Wrapping tabs are empty, the colour page lists the 14 lexer keys and
not the semantic kinds, Enter indents by a constant 4 rather than the reader's
setting, `<`/`>` are not paired (the lexer gives every operator one token type),
and postfix templates are absent with the reason written down.

## 0.1.1 — built and verified

- The file type was `extensions="vela"` only. Every Vela file in this repository
  ends in `.vel`, so the plugin did nothing at all on a real file. Now the
  descriptor reads `extensions="vel;vela"`, and `VelaFileType.EXTENSIONS` is
  `[vel, vela]` with `getDefaultExtension()` returning `vel` -- measured by
  `VerifyPlugin` section 6, which reads the attribute out of the jar's descriptor
  and the two constants out of the loaded class. (This entry said `vela;vela`
  until 0.1.4: the first suffix was wrong here while `plugin.xml`, `README.md` and
  the class itself all said `vel`. Corrected against the measurement, not against
  whichever spelling appeared first.)
- `<add-to-group group-id="Vela.BuildAndRun">` named an *action* as if it were a
  group: the platform logged
  `SEVERE ... should be instance of DefaultActionGroup` and neither action ever
  reached a menu. Replaced by one action in the platform's own `NewGroup`.
- `<parserDefinition>` named an id no installed descriptor declares (the real one
  is `lang.parserDefinition`), and `<lang.structureViewBuilder>` named a point
  that does not exist — both silent no-ops, both found by the verifier.
- Registered `runConfigurationProducer`, so IDEA's own Run menu, gutter arrow and
  re-run history offer a `.vel` file.

## 0.1.0 — built and verified

- First build: file type, syntax highlighting, diagnostics from the real compiler,
  a structure view, an AST tool window, a settings page for the compiler path, and
  a "Vela File" entry in the New menu.
