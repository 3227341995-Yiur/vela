# The parameter-name bug: three mechanisms, measured before and after

Round: plugin P0, `idea-plugin/`.  Nothing committed (`git add/commit/push` deliberately not run).

---

## 1. The negative result first: the reported `s: s: s: ` is NOT drawn by the hint

The complaint was a *hint* that repeats one name in front of every argument.  On the
build the owner is running, the hint path does not do that, and it did not before this
round either:

`idea-plugin/build/tools/harness/classes/HintShapes.log` (before, 2026-09-24 00:33) and
`idea-plugin/build/repro/shapes-after-fix.txt` (after) both print, for 30 hand-written
shapes including the refused declaration:

```
---- THE REPORTED SHAPE: three unannotated params all named s
     (no hints drawn)
---- params with NO type annotations
     (no hints drawn)
---- three same-letter args
     at 109  `t: `   context: ` print(g(s, s, s))\n}\n`
     at 112  `u: `   context: `int(g(s, s, s))\n}\n`
```

Shapes tried (all 30 in `HintShapes --shapes`; the ones aimed at this report):
`def f(s, t, u) -> int` unannotated; `def f(s, s, s) -> int` unannotated; one-argument
unannotated; three same-letter *arguments* against `def g(s: int, t: int, u: int)`;
`print(s, s, s)`; a call before its declaration; a user function shadowing a builtin;
half-typed, unclosed; a half-typed declaration; multi-line parameter lists; methods;
`extern c def`; array and `mut` parameters.  None draws a repeated label.
`HintProbe` on `build/repro/refused.vel` (after) prints an empty hint table and
`hints drawn from the wrong name : 0`.
The corpus agrees (`HintDupes`, before): `callables asked: 1745   with a repeated
parameter name: 0   with an empty name: 0`.

**What I could not reproduce: the repeated label as a hint.**  The reason is in the
code: `VelaTargets.declaredParameterNames` (the hint's only source of names) returns
**null** when the declaration's parameter list cannot be read, and null draws nothing.
That fix was already in place and works.

**The same shape survives in a path no hint harness looks at — completion.**  That is
what this round fixed.

---

## 2. Defect A (the reported shape, live): a parameter list read from `sym.detail`

`idea-plugin/src/main/kotlin/dev/vela/plugin/VelaCompletion.kt:221` `symbolParameters`
took `VelaSymbol.detail` — documented at `VelaModel.kt` as "the signature *as written*",
i.e. source text — cut it between the first `(` and the first `)`, and split it on
commas.  For a declaration the compiler refuses, that text is the parameter list the
parser never read:

    def f(s, s, s) -> int { return 1 }
    def main() -> None { print(f(1, 2, 3)) }

### The oracle, from the compiler (not from the plugin)

```
> selfhost\build\vm.exe parse idea-plugin\build\repro\refused.vel
vela: syntax error: parameter 's' has no type annotation
  at line 1
vela: panic: the front end stopped after an error
> selfhost\build\vm.exe check idea-plugin\build\repro\refused.vel
vela: syntax error: parameter 's' has no type annotation
```
The compiler refuses the file and its tree holds **no** `param` node for `f`, so no
declaration gives the name `s` for any argument.

### BEFORE (`idea-plugin/build/repro/paramnames-before-refused.txt`)

```
---- idea-plugin/build/repro/refused.vel  FUNCTION `f` line 1
     detail  : f(s, s, s) -> None
     names   : [s, s, s]
     template: (s, s, s)[1,8)
     oracle  : (cannot be read)   from f(s, s, s)
== findings: a name list the declaration does not give ==
  idea-plugin/build/repro/refused.vel: `f` (line 1) answers the parameter names [s, s, s] although its declaration's parameter list cannot be read: `f(s, s, s)`
      completion would insert `(s, s, s)[1,8)`
  a symbol whose detail is text, not a parsed parameter list `f(s: the same name twice, s: the same name twice) -> int`
      parameterNames -> [s, s]   callTemplate -> (s, s)[1,5)   BROKEN (a name was taken from detail text)
VERDICT: 1 name list(s) disagree with their declaration, 1 invariant(s) broken
exit 1
```
`(s, s, s)` is what completion would have written into the user's own file.

### The fix (structural, not a stronger string check)

* `VelaSymbol` gained `parameters: List<String>?` — the names **read from a
  declaration** — and `symbolParameters` is now `sym.parameters ?: emptyList()`.
  It no longer looks at `detail` at all.
* New `VelaSignatures.kt` holds the two sanctioned sources, and nothing else:
  `declaredParameterNames(tree, text, def)` (the tree, with the null-trust rule) and
  `parameterNamesInSignature(name, signature)` (a signature, checked to *begin* with
  `name(`, so prose that mentions a parenthesis answers null).
* `VelaModel` fills `parameters` from the tree in `readCallableFromTree`; the legacy
  token scan (`referenceSymbols`, what `HintDupes --old` measures) keeps its own
  text-derived list on purpose, so that measurement still measures 0.1.3.

### AFTER (`idea-plugin/build/repro/paramnames-after-refused.txt`, exit 0)

```
---- idea-plugin/build/repro/refused.vel  FUNCTION `f` line 1
     detail  : f(s, s, s) -> None
     names   : []
     template: ()[1,1)
     oracle  : (cannot be read)   from f(s, s, s)
== findings: a name list the declaration does not give ==
  (none)
  a symbol whose detail is text, not a parsed parameter list `f(s: the same name twice, s: the same name twice) -> int`
      parameterNames -> []   callTemplate -> ()[1,1)   ok (no name is taken from the detail text)
COVERAGE: ran 2 / skipped 0 (...) / wrong 0
INVARIANTS: declared 71 / broken 0 / not checkable in this build 0
VERDICT: every parameter list either names what its declaration names, or names nothing
```
Completion now inserts `()` — honest — instead of `(s, s, s)`.

### No regression on a normal declaration (`paramnames-after-normal.txt`)

```
---- idea-plugin/build/repro/normal.vel  FUNCTION `f` line 1
     detail  : f(a: int, b: int) -> int
     names   : [a, b]
     template: (a, b)[1,5)
     oracle  : [a, b]   from f(a: int, b: int)
```

---

## 3. Defect B (live, user-visible): a builtin's parameter list read from prose

`VelaTargets.builtinParameterNames` found the parameter list with `description.indexOf('(')`
over the **prose description**, and `VelaNames.resolveCall` likewise built the builtin's
signature and return type out of that prose (`substringBefore(" — ")`,
`returnTypeOf(description)`).  It worked only because all 36 entries happen to begin with
their signature.

*Fix:* `VelaModel.BUILTINS` is now `List<VelaBuiltin>` with three **declared fields** —
`name`, `signature`, `prose` — and `signature` is the only place a parameter list or a
return type is read from (`VelaTargets.signatureParameterNames`,
`VelaSignatures.returnTypeInSignature`).  `VelaTargets.signatureParameterNames` is public
so a harness can feed it prose.

*Permanent check* (runs on every `ParamNames` invocation, printed whether or not the
corpus has anything to say):

```
builtin table entries without a declared signature field : 0 (must be 0)
builtin descriptions that answer parameter names : 0 (must be 0)
builtin signatures that disagree with the names reported : 0 (must be 0)
```

Feeding the *prose* field to the reader is the operation that must yield null; on the
pre-fix table that question could not even be asked (the two fields were one string),
which is why the before-run prints `not checkable in this build` and the summary line now
carries that axis:

```
COVERAGE: ran 2 / skipped 0 (...) / wrong 0
  ^ the line above counts CALLABLES.  The invariant checks are a second axis:
INVARIANTS: declared 71 / broken 0 / not checkable in this build 0
```

*Two names aligned to the language's own document* (`SPEC.md` §8, the only declaration a
builtin has): `substr(s: str, from: int, to: int)` → `substr(s: str, a: int, b: int)` and
`write_text(path: str, body: str)` → `write_text(path: str, s: str)`, so that
`substr("abc", 0, 2)` draws `a: `/`b: ` and `write_text(p, b)` draws `s: `, which is what
`SPEC.md` names them.  This is a judgement call: the plugin's table was self-consistent,
and the change is one field per entry.  `ParamNames` compares all 11 SPEC-documented
builtins against the table on every run (0 disagreements).

---

## 4. Defect C (live): a newline ended a call's argument list

`VelaInlayHints.argumentRanges` treated `'\n'` as "the statement ended" and jumped to the
closing paren, dropping every argument written after the first newline.  SPEC.md §2 says
newlines are insignificant inside `(` and `[`, and `matchingParen` — which decided where
the list ends — never stopped at a newline: the two readers disagreed.

`HintDiff` (expected names from `vm.exe parse`, the compiler as oracle) measured the
consequence, same tool, same corpus, before → after:

| line | before (`build/tools/harness/classes/HintDiff.log`, 09-22 02:40) | after (`build/repro/hintdiff-after-fix.txt`) |
|---|---|---|
| hints drawn | 10688 | **11052** |
| hint names WRONG | 0 | 0 |
| declared params with no hint | **30** | **0** |
| arg-boundary disagreements | 125 | 12 |
| undeclared callee / drew a hint | 1103 / 0 | 1143 / 0 |
| verdict | 30 hint position(s) are not right | every hint names the parameter the compiler declares for that argument, and every declared parameter has a hint |
| coverage | `ran 29006 / skipped 127 / wrong 30` | `ran 29926 / skipped 14 (compiler-cannot-parse 2, arg-boundary-disagreement 12) / wrong 0` |

The 30 were all of that shape, e.g. `selfhost/parts/parser.vel:547 perr argument 3 is
`line`, argument written `pline`, but no hint was drawn at all` — the argument is written
on the continuation line of `perr(cx, concat(concat(concat("parameter '", interned(pnm)),
"'"), " has no type annotation"), pline)`.

Minimal repro `idea-plugin/build/repro/multiline.vel` (which `vm.exe check` accepts and
whose tree holds `param name=a/b/c` and a three-argument call):

```
def g(a: int, b: int, c: int) -> int { return a }
def main() -> None {
    print(g(1,
             2,
             3))
}
```
before: only `a: ` at the `1` (the `2` and `3` after the newline lost).
after (`build/repro/hinttruth-multiline.txt`):

```
---- idea-plugin/build/repro/multiline.vel  `(1,\n             2,\n             3)`
     drawn  : [`a: `, `b: `, `c: `]
     oracle : [a, b, c]
COVERAGE: ran 1 / skipped 1 (callee-not-declared-in-file 1) / wrong 0
```

*Fix:* the `'\n' -> i = closeParen` branch is gone; a `#` comment still ends at its line
and a comma inside one is not a separator.

---

## 5. The corpus sweeps

Corpus: `tests/`, `examples/`, `bench/`, `ide-demo/`, `selfhost/parts/`, `selfhost/vela.vel`
(195 files).  Two tools, two oracles, both non-plugin:

* `ParamNames` — the completion path (`VelaHints.parameterNames` / `callTemplate`), judged
  against `DeclReader` (a declaration reader written in the harness) and `SPEC.md` §8.
* `HintTruth` — the drawn labels (`VelaHints.parameterHints`), judged the same way.

```
ParamNames, corpus      : COVERAGE: ran 1771 / skipped 5594 (harness-threw 0, missing-corpus-file 0,
                          file-too-large 0, not-a-callable 5594, callee-not-in-this-file-and-not-a-builtin 0,
                          builtin-arity-only-SPEC-md-names-none 0) / wrong 0
                          INVARIANTS: declared 71 / broken 0 / not checkable in this build 0
ParamNames, half-typed  : COVERAGE: ran 18745 / skipped 79019 (not-a-callable 79019) / wrong 0
 (parser.vel, every 64-byte prefix; 85 informational `detail is display text, no name taken from it`)
HintTruth,  corpus      : COVERAGE: ran 6735 / skipped 5992 (no-argument-call 2389,
                          callee-not-declared-in-file 3603) / wrong 0
HintDiff,   corpus      : COVERAGE: ran 29926 / skipped 14 (compiler-cannot-parse 2,
                          arg-boundary-disagreement 12) / wrong 0
```

Every skip is named.  `not-a-callable` (5594) is the symbols list's fields, structs and
parameters, which declare no parameter list; `no-argument-call` (2389) is a call with
nothing between its parentheses; `callee-not-declared-in-file` (3603) is a callee neither
this file nor `SPEC.md` §8 declares; `builtin-arity-only-SPEC-md-names-none` is a builtin
whose SPEC row documents the arity without names (`pow`, `to_float`, `min_int`, `emit_*`)
— nothing is claimed about those, which is why it is a separate category and not folded
into `wrong`.

There is no `s: s: s: ` in the corpus for the same reason there is none in the shape
battery: the corpus is `check`-clean, so the *untrusted declaration* branch cannot occur
in it.  That branch is measured by `build/repro/refused.vel` instead, and that file is
part of the evidence rather than of the corpus.

---

## 6. Commands to reproduce all of it

```powershell
cd C:\Users\lu\Downloads\vela
powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1            # compile the plugin
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 `
    -Class ParamNames -Rest --single,idea-plugin/build/repro/refused.vel,--dump   # defect A, before/after axis
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 `
    -Class ParamNames -Rest --single,idea-plugin/build/repro/normal.vel           # no regression
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 `
    -Class ParamNames -Rest --single,selfhost/parts/parser.vel,--truncate,64      # half-typed
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 -Class ParamNames   # corpus
powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 -Class HintTruth    # corpus
powershell -ExecutionPolicy Bypass -File idea-plugin\harness.ps1 -Tool HintDiff               # compiler oracle
powershell -ExecutionPolicy Bypass -File idea-plugin\harness.ps1 -Tool HintShapes -Shapes     # shapes
selfhost\build\vm.exe parse idea-plugin\build\repro\refused.vel                # the oracle for defect A
selfhost\build\vm.exe parse idea-plugin\build\repro\multiline.vel              # the oracle for defect C
```
`ParamNames` and `HintTruth` are now in `harness.ps1`'s `$TOOLS`, so `-Tool All` runs them
(their own driver is the new `tools\run-harness.ps1`, which `harness.ps1` did not have for
the classes that are not on its list).

---

## 7. Files changed

```
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaSignatures.kt      NEW: the two sanctioned sources
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaModel.kt          VelaSymbol.parameters, VelaBuiltin, fills the field
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaCompletion.kt     symbolParameters: fields, not text
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaTargets.kt        both readers delegate to VelaSignatures
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaNames.kt          builtin symbol from the signature field
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaInlayHints.kt     argumentRanges: a newline no longer ends the list
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaDocumentation.kt  VelaBuiltin instead of Pair
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaCompletion.kt     builtin loop over VelaBuiltin
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaFindUsages.kt     .name instead of .first
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaGotoDeclaration.kt .name instead of .first
idea-plugin/src/main/kotlin/dev/vela/plugin/VelaSemanticHighlighting.kt .name instead of destructuring
idea-plugin/tools/harness/src/DeclReader.java                     NEW: the oracle (declaration text + SPEC.md)
idea-plugin/tools/harness/src/ParamNames.java                     NEW: the completion path + the invariants
idea-plugin/tools/harness/src/HintTruth.java                      NEW: the drawn labels vs the oracle
idea-plugin/tools/harness/src/HintDiff.java                       its builtin mirror reads the signature field
idea-plugin/tools/run-harness.ps1                                 NEW: runs one harness class
idea-plugin/harness.ps1                                           ParamNames and HintTruth added to $TOOLS
```

## 8. What is not done

* No commit, per the brief.
* `HintNames`, `SymbolDiff`, `FoldDiff`, `GotoOracle`, `FeatureProbe` were not re-run after
  the change; only `HintDiff`, `HintShapes`, `HintProbe`, `ParamNames` and `HintTruth` were.
* The builtin *names* (`substr` → `a`/`b`, `write_text` → `s`) follow `SPEC.md` §8 over the
  plugin's former wording (`from`/`to`, `body`).  If the leader prefers the plugin's wording,
  it is one field per entry and `ParamNames` will report the disagreement as a named line.
