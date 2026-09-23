# Inspections and quick fixes -- Vela IDEA plugin 0.1.5

`FEATURE_PARITY.md` item 14 (`problems / inspections / quick fixes`) was `partial` for one
reason: **no `localInspection` and no quick fix existed at all**. This round adds three
rules, each of which reports a refusal the frozen compiler itself makes on that very line,
and each of which carries a fix that makes `vm.exe check` exit 0.

Version 0.1.5 · written 2026-09-24 · git HEAD `cc8b8626` (moving: other agents commit) ·
jar `dist\vela\lib\vela-idea-plugin.jar` 390641 B
`sha256 6408725ff990f6f7fbcfc4cd4e8401fa9b842dfb7ba6aefdcc45cd511e4ec5aa` ·
oracle `3768a18097493a5639739d330f65b8ed787729c9b5c5200e323ac8aad9bb5f8a` (861696 B, frozen
first, before any of this round's work).

---

## 1. Files (all new, all still untracked in the working tree)

| file | what it is |
|---|---|
| `idea-plugin\src\main\kotlin\dev\vela\plugin\VelaInspections.kt` | the three rules as pure functions over `VelaSyntaxParser`'s tree, plus the three `LocalInspectionTool` classes |
| `idea-plugin\src\main\kotlin\dev\vela\plugin\VelaInspectionFixes.kt` | the one `LocalQuickFix` all three share |
| `idea-plugin\tools\harness\src\InspectionProbe.java` | the harness: drives the *registered classes* through the platform's `ProblemsHolder`, compares every finding with `vm.exe check`, applies the fixes, re-checks |
| `idea-plugin\tools\harness\inspection-probe.ps1` | the driver (this tool is not in `harness.ps1`'s `$TOOLS` list — see §6.2) |
| `idea-plugin\evidence\inspections-probe-0.1.5-2026-09-24T0250.txt` | primary run, raw |
| `idea-plugin\evidence\inspections-oracles-and-hang-0.1.5-2026-09-24T0250.txt` | the other three compiler builds, and the compiler hang |
| `idea-plugin\evidence\inspections-negative-control-0.1.5-2026-09-24T0250.txt` | the negative control, raw, before and after |

No existing file was touched: `plugin.xml`, `harness.ps1`, `VerifyPlugin.java`,
`VelaModel/Syntax/…`, every other agent's `.kt`/`.java`/`.md` are as they were.

---

## 2. The three rules, and the compiler line each one claims

| rule (`getShortName()`) | what it reports | the compiler's own words on that line (exit 2) | the fix |
|---|---|---|---|
| `VelaImmutableAssignment` | `x = 2` after `x: int = 1` | `vela: safety error: cannot assign to 'x': it was declared immutable` | insert `mut ` at the declaration |
| `VelaStringConcatenation` | `"a" + "b"` | `vela: type error: string concatenation is not implemented in Vela 0.1` | rewrite as `concat("a", "b")` |
| `VelaIntFloatMixing` | `1 + 2.5`, `x < y` with one `int` and one `float` | `vela: type error: operator '+' mixes int and float` (also `mixes float and int`, and `'/' is float division; got int and float`) | wrap the **int** operand: `to_float(1)` |

Each of the three is a refusal the language's own battery already asserts in
`tests\safety\cases\` (`strict_immutability.vel`, `strict_no_string_concatenation.vel`,
`strict_no_implicit_conversion.vel`), and each fix writes the form the *passing twin* of
that file uses (`mut x: …`, `concat(a, b)`, `to_float(…)` from `strict_to_float_explicit.vel`).

### The fix, before and after — raw `vm.exe check` output

Copied out of section D of the primary run (`InspectionProbe` prints it; nothing here was
typed by hand):

```
== D. one fix, before and after: raw `vm.exe check` output ==

  tests/safety/cases/strict_immutability.vel          VelaImmutableAssignment
    the rule (line 3): Vela refuses this assignment: `x` was declared immutable, and the compiler says "cannot assign to 'x': it was declared immutable"
    the fix: "Add 'mut' to the declaration of 'x'" ->
             replace [25,25) with "mut "
    -- check BEFORE the fix (exit 2):
         vela: safety error: cannot assign to 'x': it was declared immutable
         at before.vel:3
         vela: panic: the program was refused
    -- check AFTER the fix (exit 0):
         ok

  tests/safety/cases/strict_no_string_concatenation.vel  VelaStringConcatenation
    the rule (line 5): Vela has no `+` for strings: the compiler refuses "string concatenation is not implemented in Vela 0.1"
    the fix: "Join with concat(...)" ->
             replace [248,257) with "concat("a", "b")"
    -- check BEFORE the fix (exit 2):
         vela: type error: string concatenation is not implemented in Vela 0.1
         at before.vel:5
         vela: panic: the program was refused
    -- check AFTER the fix (exit 0):
         ok

  tests/safety/cases/strict_no_implicit_conversion.vel  VelaIntFloatMixing
    the rule (line 2): Vela has no implicit int/float conversion: the compiler refuses "operator '+' mixes int and float"
    the fix: "Convert the int operand with to_float(...)" ->
             replace [31,32) with "to_float(1)"
    -- check BEFORE the fix (exit 2):
         vela: type error: operator '+' mixes int and float
         at before.vel:2
         vela: panic: the program was refused
    -- check AFTER the fix (exit 0):
         ok
```

---

## 3. The measurement

```
COVERAGE: ran 257 / skipped 54 (crashed-file 0, missing-corpus-file 0, too-large 0, compiler-did-not-run 0, check-did-not-terminate 0, fix-wrote-nothing 0, inspection-class-disagrees-with-the-rule 0, does-not-parse 53, empty-or-whitespace-only 0, excluded-by-request 1) / wrong 0
VERDICT: [PASS] every finding the three registered inspections report was walked to a refusal the frozen compiler itself makes on that line, every fix removes the refusal it was offered for, and no finding lands on a file the compiler accepts -- over 257 judged file(s), wrong 0, no defect category tripped

  files the compiler accepts        : 131
  files the compiler refuses         : 126
  findings on files it ACCEPTS       : 0   <- must be 0: an inspection may not invent a refusal
  files where all fixes were applied : 10
  findings the walk could not reach   : 0
  slowest single `check`             : 30 ms (tests/safety/cases/hole_extern_conflicting_prototypes.vel)

  per rule (`verified` = the compiler refused that very line with this rule's own words):
    rule                      fired  verified corrob  unprov  disputed missed  fix-clean fix-partial on-accepted
    VelaImmutableAssignment   2      2        0       0       0        1       2         0           0
    VelaStringConcatenation   7      7        0       0       0        0       7         0           0
    VelaIntFloatMixing        4      4        0       0       0        0       4         0           0
```

* **unit**: one file for `ran` / `skipped` / `wrong` (311 corpus files over
  `tests\ examples\ ide-demo\ bench\ selfhost\parts\` + `selfhost\vela.vel`).
* **`on-accepted` is the headline number**: 0 findings on any of the 131 files the
  compiler accepts. An inspection that invents a rule shows up there first.
* **`verified` is the other half**: `vm.exe check` reports **only its first refusal**
  (measured: `tests\build\accept\string_concat_basic.vel` has three `+` on strings and the
  compiler words one of them). So a finding is *not* judged by "is there a diagnostic on
  that line" — it is judged by walking the refusal stack one fix at a time: fix the
  finding the compiler just named, ask again, and the next refusal is the next finding's
  own proof. Each step is one more `check`; `unprov` is where the walk stopped because the
  compiler's next refusal was outside these three rules (0 here).
* **`missed`** counts the refusals in one of these three families that the rules said
  nothing about — reported, never hidden. There is one:
  `tests/build/check_cases/mutate_loop_variable.vel:3 "cannot modify 'i'"`, a `for` loop
  variable, for which **no legal repair exists** (`mut` on a loop variable is not a thing),
  so the rule deliberately does not fire (§5).
* **`fix-clean` 13/13**: every file with findings reaches `exit 0` after the fixes; no
  `fix-partial` in the corpus.
* The four other compiler builds the same tool was run against (raw logs in the evidence
  file): `501b6cbb` (the tree's `build\vm.exe`), `1b2d7919` (the fixed `selfhost\vm.exe`,
  not yet promoted), and the frozen oracle before/after. All: `wrong 0`, same 13 findings.

---

## 4. The negative control — the tool is not a rubber stamp

One word of the int/float rule was corrupted, the whole measurement re-run, the word put
back. The corruption is exactly one token, in `intFloatMixing`:

```diff
-                l == "int" && r == "float" -> left
+                l == "int" && r == "int" -> left // NEGATIVE CONTROL
```

Raw, from `inspections-negative-control-0.1.5-2026-09-24T0250.txt`:

```
===== CORRUPTED -- one word wrong
    findings on files it ACCEPTS       : 314   <- must be 0: an inspection may not invent a refusal
    findings the walk could not reach   : 2688
    wrong                   : 41
  COVERAGE: ... / wrong 41
  VERDICT: [FAIL] VelaIntFloatMixing reported nothing on its canonical file tests/safety/cases/strict_no_implicit_conversion.vel; VelaIntFloatMixing: reported on a file the compiler accepts -- tests/build/arith_basics.vel:4 ... -- wrong 41 file(s): an inspection reported something the compiler did not
      VelaImmutableAssignment   2      2        0       0       0        1       2         0           0
      VelaStringConcatenation   7      7        0       0       0        0       7         0           0
      VelaIntFloatMixing        3003   1        0       2688    0        3       298       2705        314
  process exit code 1

===== RESTORED -- the same run, the one word back
    findings on files it ACCEPTS       : 0
    findings the walk could not reach   : 0
    wrong                   : 0
  VERDICT: [PASS] ... over 257 judged file(s), wrong 0, no defect category tripped
      VelaIntFloatMixing        4      4        0       0       0        0       4         0           0
  process exit code 0
```

Both halves are the answer: the corrupted rule turns every `x + 1` in the corpus into a
finding (314 false alarms, `wrong 41`, non-zero exit, and section D notices that the
canonical true positive went *missing* meanwhile), and the other two rules' numbers do not
move — the failure is attributed to the rule it belongs to and to no other. Restoring the
word restores the pass.

---

## 5. What the rules deliberately do **not** report, and why (measured, not caution)

* **assignment to an immutable parameter** — refused with the same words, but the repair is
  not the same word: `mut n: int` on a scalar parameter satisfies `check` while changing
  nothing, because the argument is a copy
  (`tests\safety\cases\hole_mut_scalar_parameter.vel`). A fix there would be a no-op the
  plugin must not teach, so parameters are out of scope for that rule.
* **assignment to an immutable array** — same message, but `mut` merely turns it into
  `cannot rebind array 'a'` (`scope_array_rebind_refused.vel`). Array-typed declarations are
  skipped.
* **`%` and `**`** — the compiler words their mismatches as `'%' is integer remainder; got
  int and float` and `'**' needs matching numeric operands, got int and float`, which does
  not say *which* side to convert. Two rules with an ambiguous fix are worse than one
  honest miss.
* **`for` loop variables** — `mutate_loop_variable.vel` is the `missed 1`. There is no
  legal way to make a loop variable assignable, so nothing is offered, and the miss is
  printed rather than papered over.
* **`str` mixed with a non-`str`** (`"a" + 1`) — a different refusal
  (`operator '+' mixes int and str`) with no repair in the language (there is no `to_str`),
  so it is not reported.

---

## 6. What the leader has to add

### 6.1 `plugin.xml` — inside the existing `<extensions defaultExtensionNs="com.intellij">`

Verified against the installed platform rather than from memory: the extension point is
`com.intellij.localInspection`, its bean is `com.intellij.codeInspection.LocalInspectionEP`
(extends `InspectionEP` extends `LanguageExtensionPoint`), and its attributes are
`language`, `implementationClass`, `shortName`, `displayName`, `groupName`, `level`,
`enabledByDefault`, `hasStaticDescription`, `runForWholeFile`, `id` (`suppressId`),
`alternativeId` — read off the bean's own `@Attribute` annotations in this IDE (262), and
copied in shape from `aopCommon.jar` and `clouds-docker-gateway.jar`, which register
inspections for their own languages.

```xml
        <!-- ======================= problems, inspections, quick fixes =======================

             Three rules, and the plugin's own rule is what chose them: each one reports a
             refusal `vm.exe check` makes, and each fix writes the form the passing twin of
             the same safety case uses.  `shortName` MUST equal what the class's
             `getShortName()` returns (it is the id the user's inspection profile and a
             `@Suppress` are stored under) -- the harness reads both back and fails if they
             differ, and VerifyPlugin fails the build if these lines are absent (a class
             implementing a contract the platform registers, with no registration, is the
             dead-class failure this plugin has already shipped three times). -->
        <localInspection language="Vela"
                         shortName="VelaImmutableAssignment"
                         displayName="Assignment to an immutable binding"
                         groupName="Vela"
                         enabledByDefault="true"
                         level="ERROR"
                         hasStaticDescription="true"
                         implementationClass="dev.vela.plugin.VelaImmutableAssignmentInspection"/>

        <localInspection language="Vela"
                         shortName="VelaStringConcatenation"
                         displayName="'+' on strings"
                         groupName="Vela"
                         enabledByDefault="true"
                         level="ERROR"
                         hasStaticDescription="true"
                         implementationClass="dev.vela.plugin.VelaStringConcatenationInspection"/>

        <localInspection language="Vela"
                         shortName="VelaIntFloatMixing"
                         displayName="int and float mixed in one operation"
                         groupName="Vela"
                         enabledByDefault="true"
                         level="ERROR"
                         hasStaticDescription="true"
                         implementationClass="dev.vela.plugin.VelaIntFloatMixingInspection"/>
```

`groupName="Vela"` (the XML attribute; the field is `groupDisplayName`) is what the settings
tree shows; `dumbAware="true"` may be added if you want them to run while indexing — they
read only the buffer's text, so they are safe to mark that way.

### 6.2 `harness.ps1` — two places

`$TOOLS`, with the other ten names:

```powershell
$TOOLS = @('GotoOracle', 'HintDiff', 'HintNames', 'HintShapes', 'HintDupes', 'SymbolDiff', 'FoldDiff',
           'FeatureProbe', 'ParamNames', 'HintTruth', 'InspectionProbe')
```

and inside the per-tool `switch ($t)` (no `default` fallthrough to worry about):

```powershell
        'InspectionProbe' {
            # The three localInspection rules and their quick fixes, against the frozen
            # compiler.  --vm is not optional here: the tool has no verdict without an
            # oracle.  --exclude names a corpus file `check` does not terminate on (see
            # the evidence file); it is printed and counted, never silent.
            $a += @('--vm', $FrozenVm)
            if ($Single) { $a += @('--single', $Single) }
        }
```

With that, `harness.ps1 -Tool InspectionProbe` runs it; `-Tool All` adds it. Run through
`harness.ps1` the classpath is `build\classes` directly (`harness.ps1` does not snapshot),
so a concurrent `build-offline.ps1` shows up as the `crashed-file` defect category — as it
did once during this round — rather than as a wrong answer. The dedicated driver
(`tools\harness\inspection-probe.ps1`) takes the build lock itself, snapshots the classes
under it, and freezes the compiler into its own directory for that reason.

### 6.3 Not adding them is already a failed build

`build-offline.ps1` today ends with `RESULT: FAIL - 3 problem(s)`, and all three are these:

```
FAIL dev.vela.plugin.VelaImmutableAssignmentInspection implements com.intellij.codeInspection.LocalInspectionTool, a contract the installed platform's own extension points register, and no registration and no class file names it other than itself [check unregisteredImplementations].  The class compiles, it ships in the jar, and nothing can call it: the feature it implements never runs and the platform reports nothing.  This is the failure this plugin has already shipped three times -- VelaSemanticAnnotator, VelaReferenceContributor and VelaParameterNameInlayHintsProvider were each finished, correct and left unreferenced until a registration line was added.
FAIL dev.vela.plugin.VelaIntFloatMixingInspection ... [the same, for the mixing rule]
FAIL dev.vela.plugin.VelaStringConcatenationInspection ... [the same, for the string rule]
```

So the descriptor line is not a formality here: VerifyPlugin already agrees the type is
right and demands the registration, which is the strongest available evidence that nothing
else is missing from the classes themselves.

---

## 7. What was not tested, and why

1. **`LocalQuickFix.applyFix` itself.** It needs a live `Application` — a real `Project`,
   `PsiDocumentManager` and a write action; this machine has no booted IDE. What *is*
   tested is everything the fix decides: the harness pulls the finding back out of the
   `LocalQuickFix` object the inspection attached (reflection on the private field), applies
   *that* finding's edits, writes the result to a file and asks `vm.exe check`. The
   remaining four lines are `PsiDocumentManager.getDocument` → `document.setText` inside a
   write command; `VelaInspectionFix` re-derives the finding from the document's *current*
   text first, so a postponed fix on a buffer that moved does nothing instead of writing at
   a stale offset.
2. **The registration and linkage.** `plugin.xml` is not this round's file; VerifyPlugin
   owns that check (it already fails on the missing lines, §6.3, and will pass once they
   are added).
3. **The GUI.** No IDE session was started, so there is no screenshot of the alt-Enter menu
   — by the project's own standard an unmeasured claim, and it is not claimed here. The
   observable parts (the class the platform would instantiate, the problems it registers on
   a real `ProblemsHolder`, the fixes it attaches) are measured.
4. **The interaction with `VelaExternalAnnotator`.** The annotator already draws the
   compiler's own message on the same line, so a user sees that squiggle *and* the
   inspection's finding; the inspection is the one carrying the fix. Nothing was changed to
   deduplicate them (`VelaAnnotator.kt` is not this round's file), and it is worth a
   decision: either leave both (an error plus a fixable inspection), or have the annotator
   skip lines a rule claims.

---

## 8. Findings that fell out of this work (not in my file scope, all reported to the leader)

1. **`vm.exe check` does not terminate on `tests\safety\cases\strict_no_inferred_binding_type.vel`**
   (`mut a = 1`, no type annotation) — reproduced on three builds (raw output in the
   evidence file). Fixed in `selfhost\vm.exe` (`1b2d7919`, not yet promoted); with that
   build the whole corpus is `ran 258 / skipped 53 / wrong 0` and the tool's
   `check-did-not-terminate` category goes 1 → 0. The harness now has a 60 s per-call
   timeout precisely because of it (the slowest legitimate `check` over the corpus is 57 ms),
   and the file is a *named* defect category, never silently dropped.
2. **A parallel `build-offline.ps1` replaced `build\classes` mid-run**, which came back as
   `NoClassDefFoundError: dev/vela/plugin/VelaSyntaxParser$Companion$WhenMappings` on six
   files — a wrong number about the plugin, not a wrong answer from it. The driver now
   snapshots `build\classes` under the lock and runs against the snapshot.
3. **The build lock leaks on a killed process.** My own driver was killed mid-run (a caller
   truncated its output) and left an empty `%TEMP%\vela-plugin-build.lock` behind; every
   later run then waited ten minutes for an owner that no longer existed. The driver now
   writes its PID into the lock and breaks a lock whose PID is gone (a lock with *no* PID —
   the plain one-liner the other agents use — is never broken automatically).
4. **`BAD_CHARACTER` has no colour key** (the leader's own `FeatureProbe` measurement:
   `BAD_CHARACTER 5 NO COLOUR KEY`, `token types with no colour: 1`). It is a real gap, but
   the fix lives in `VelaHighlighting.kt` / `VelaColorsAndFontsPage.kt`, which are not mine
   this round, so I did not touch them.

---

## 9. Reproducing

```powershell
# 1. build the plugin (writes build\ and dist\ -- take %TEMP%\vela-plugin-build.lock first)
powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1

# 2. the measurement (the driver takes the lock itself, freezes the compiler, snapshots
#    build\classes, then runs the tool)
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\harness\inspection-probe.ps1 `
    -FrozenVm %TEMP%\vela-inspection-probe\vm.exe `
    -Exclude tests/safety/cases/strict_no_inferred_binding_type.vel

# 3. compile check only, without running anything
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\harness\inspection-probe.ps1 -CompileOnly
```

`-ShowAll` prints every finding including the ones that agree; `-Single <file>` narrows the
corpus to one file; `-Refreeze` re-copies the compiler from the tree.
