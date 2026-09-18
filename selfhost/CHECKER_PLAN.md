# Porting the semantic checker to Vela — implementation checklist

> **Status: ported.**  `selfhost/parts/check.vel` carries the verdicts in this
> plan.  The evidence that certified the port was `tests/diff_check.py`, which put
> every program *and every reject case* in the tree to both checkers while stage 0
> was still present: **70/70 agree, 42 with the message byte for byte, 0 verdict
> differences**.  That harness, its oracle `vela/checker.py`, and the Python front
> end they belonged to were all deleted in stage 4, so **the agreement cannot be
> re-run and is not reproducible from this tree** — the line numbers quoted in this
> document point into a file that no longer exists.  What holds the verdicts now is
> `tests/golden/<name>.err`, recorded while both checkers were present.  What
> landed after this document was written is
> the `parallel for` proof and the interval arithmetic it needs (`Iv`,
> `ck_iv_*`, `ck_linear`, `ck_match_linear`, `ck_rowmajor`, the `ck_p*` walk),
> the operator table (`ck_arith`/`ck_etype`, which is what makes "no implicit
> conversion" a rule), the integer-literal overflow (in the lexer, where the
> digits still exist), `float division by constant zero` (a flag on the literal,
> because the value lives in a pool the check pass is not given), the augassign
> spelling of "cannot modify", and the **diagnostic shape** — `vela: <kind>:
> <message>`, location on the next line — without which a tool cannot tell a
> syntax error from a safety error.  The narrowings that remain are listed in
> `DESIGN.md` §7.5; the three worth naming here are that the interval
> environment holds the loops *inside* a parallel body and not the ones it is
> nested in, that an impure method is only caught when its receiver is a plain
> name, and that `ck_etype` answers "unknown" for the result of a call.

Source of truth: `vela/checker.py` (1637 lines, read in full), `vela/analysis.py`
(251), `vela/types.py` (205), `vela/codegen.py` (765), `vela/nodes.py` (234),
`vela/parser.py` (660), `vela/errors.py`, plus `SPEC.md` §3–§9 and `DESIGN.md`
§3, §7.1–§7.4. Line numbers below are the file's real 1-based lines.

**Every `vela/…` path above, and every `…:NNN` citation below, refers to the
Python front end as it stood when this plan was written. Stage 4 deleted that
tree, so the citations cannot be followed any more — they are kept because they
are the record of what each rule was read from, and `selfhost/parts/check.vel` is
the implementation they became.**

Scope: this is a **specification**, not code. Message texts are quoted verbatim
(f-string placeholders shown as `{...}`); every one has to be reproduced byte for
byte — it used to be `tests/run_tests.py`'s substring matches that enforced that,
and it is now `tests/golden/<name>.err`.

---

## 0. Executive summary

* The checker walks the module **twice**: phase 1 builds the type/signature
  tables (`collect_structs`, `collect_signatures`), phase 2 type-checks every
  function body (`check_function`). Phase 1 errors therefore take priority over
  any body error, and a body error in the first function in source order
  pre-empts a body error in a later one.
* Only **the first error is ever reported** — every rule raises immediately
  (`checker.py:182-186`, `errors.py:25-35`). `ide/services.py:379-404` turns that
  single exception into a one-element diagnostic list. The Vela port inherits
  this; it is *not* a multi-diagnostic checker and there is no recovery/poison
  path in practice (the `ErrorT` machinery at `checker.py:670-671`, `1215-1216`,
  `1137` is dead code, since `check_expr` only returns `ErrorT` after an error
  would already have been raised).
* **126 distinct refusal sites: exactly 93 `TypeErr` ("type error") and exactly
  33 `SafetyErr` ("safety error")** — counted from the raise sites, and the
  inventory in §1 has the same 126 entries, numbered 1-59 and 76-145 — the gap
  at 60-75 exists only so that the parallel rules appear after the body checks
  in execution order, in §1.12. The two `range() step must not be zero` sites
  are distinct rules 57/58. Both are hard errors; there are **no warnings
  anywhere** in
  the checker. The error *kind* string is part of the rendered message
  (`filename:line:col: type error: …` / `… safety error: …`), so `SafetyErr`
  vs `TypeErr` choice must be preserved exactly — `tests/run_tests.py` compares
  substrings of `ex.render()`, and `run_ide_tests.py` asserts on `kind`.
* The checker also **annotates the AST**, and that is the whole interface to the
  back end (§4): type per expression, `need_bounds`, `need_overflow`,
  `const_value`, `array_size`, plus six counters on `Program`.

---

## 1. Rule inventory

Ordering below follows execution. Phase 1 check order is exactly: per top-level
statement in source order → `collect_structs` (all structs, then all their
fields) → `collect_signatures` (all free defs, then all methods). Phase 2 then
walks `mod.body` in source order: a `FuncDef` is checked where it appears, while
a `StructDef` contributes **only its methods** — so **every free function body is
checked before any method body**, whatever their order in the file
(`checker.py:345-364`). Finally the `main`-entry requirements.

Legend: **T** = `TypeErr` (type error), **S** = `SafetyErr` (safety error).

### 1.1 Types and type syntax (`resolve_type`, `checker.py:366-389`)

`TYPE_NAMES` = `int i32 u8 float bool str None` (`checker.py:51-54`).

| # | rule | message | kind | site |
|---|---|---|---|---|
| 1 | `Array` must have a bracketed element type | `Array needs an element type: Array[T, N]` | T | 371 |
| 2 | `Array` must have a compile-time length | `Array needs a compile-time length: Array[T, N]` | T | 373 |
| 3 | `Array` length must be > 0 | `Array length must be positive, got {te.size}` | T | 376 |
| 4 | `Array` length must be ≤ 2^40 | `Array length {te.size} is absurdly large` | T | 378 |
| 5 | array element type may not be `None`/error | `Array element type may not be {elem}` | T | 381 |
| 6 | a builtin scalar name may not take type arguments | `'{te.name}' does not take type arguments` | T | 386 |
| 7 | unknown type name | `unknown type '{te.name}'` | T | 387 |
| 8 | `Array[T]` with no size is rejected by (2) even though the parser accepts it (`parser.py:438-445`) | — | — | — |

Note: a *struct* name wins over a builtin name only if it is not in
`TYPE_NAMES` (checker 367 precedes 383), and `collect_structs` runs before
signatures, so a struct named `int` is unreachable.

### 1.2 Struct declarations (`collect_structs`, 391-418)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 9 | no two structs with the same name | `duplicate struct '{st.name}'` | T | 397 |
| 10 | a field may not be `None`/error-typed | `field '{f.name}' may not be {ft}` | T | 407 |
| 11 | a field may not be an array | `field '{f.name}' may not be an array` | T | 409 |

Hint on (11): *"a struct is copied by value, and an array's storage belongs to
the function that declared it; keep arrays in local variables"*.
Side effects: `st.resolved_fields: List[(name, VType)]`, `st.vtype = StructT`,
`StructT.defn = st` (`types.py:128-130`). Duplicate fields are **not** a checker
rule — they are a parser error (`parser.py:262-265`, `duplicate field '…' in
struct …`), and `tests/run_tests.py` `duplicate_field` therefore never reaches
the checker. Struct bodies containing non-field/non-method statements, and a
struct with no fields, are also parser errors (`parser.py:270-275`).

### 1.3 Function and method signatures (`collect_signatures`, `_make_sig`, 420-466)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 12 | no two free functions with the same name | `duplicate function '{st.name}'` | T | 426 |
| 13 | no two methods of one struct with the same name | `duplicate method '{m.name}' in struct {st.name}` | T | 435-436 |
| 14 | a method name may not equal a free function name | `method '{m.name}' collides with a free function` | T | 438-439 |
| 15 | no duplicate parameter names | `duplicate parameter '{p.name}'` | T | 447 |
| 16 | every parameter type must resolve (and not be `None`) | `parameter '{p.name}' has an invalid type` | T | 451 |
| 17 | a method's first parameter must be named `self` | `method '{fn.name}' must take 'self' as its first parameter` | T | 457-459 |
| 18 | that `self` must be typed as the owning struct | `'self' must be typed {struct_name}, got {params[0][1]}` | T | 462-463 |

Hint on (17): `write: def {fn.name}(self: {struct_name}, ...) -> T:`.
Per-parameter `p.vtype` is written here; `fn.ret_vtype` is written at 464.
Because (14) is checked while walking structs in source order, a *method* can
shadow a free function only if the free function was registered earlier in the
loop.

### 1.4 `main` entry requirements (`run`, 355-363) — last thing checked

| # | rule | message | kind | site |
|---|---|---|---|---|
| 19 | a top-level `def main` must exist | `no 'main' function` | T | 356 |
| 20 | `main` takes no parameters | `'main' must take no parameters` | T | 361 |
| 21 | `main` must be declared `-> None` | `'main' must be declared '-> None'` | T | 363 |

(19) is raised only after every body has been checked; (20)/(21) point at
`mod.body[0]` — i.e. **the first top-level statement**, not at `main` itself
(a deliberate quirk; do not "fix" it during the port if byte-identical
diagnostics matter). Hint on (19): *"every Vela program needs 'def main() ->
None:'"*.

### 1.5 Per-function entry checks (`check_function`, 470-506)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 22 | a function may not return an `Array` type at all | `function '{fn.name}' returns {sig.ret}` | S | 479-480 |
| 23 | every control-flow path must return when `ret != None` | `function '{fn.name}' may finish without returning {sig.ret}` | T | 495-497 |
| 24 | a `-> None` function may not `return e` | `this function is declared '-> None' but returns a value` | T | 502-503 |

Hint on (22): *"an array lives in the callee's arena frame and would dangle on
return; pass an output array in as a 'mut' parameter instead"* (this is what
`return_own_array` checks for the substring "would dangle").
Hint on (23): *"Vela requires a return on every control-flow path"*.

`always_returns` (519-529) succeeds on: a `Return` anywhere in the statement
list (even after other statements), an `If` **with a non-empty `orelse`** whose
both branches always return, and a `while True` whose body (recursively, through
`If` bodies only — `_contains_break`, 535-542) contains no `break`. It does
**not** consider `for` loops, `break` inside a nested `while`, or `pass`. Note
(24) is a *second* pass over the same body (`walk_returns`, 508-517, descends
into `If`/`While`/`ForRange`/`ForIter` bodies but not into nested `if` `orelse`
of a `for`), so a `-> None` function with an early `return 3` reports (23)'s
cousin only after the whole body type-checked.

Scopes: one `push()` per function, parameters declared as `is_param=True`
(`is_mut` from the annotation) at `fn.line`; `pop()` at the end. `current_ret`,
`current_fn`, `in_pure`, `current_struct` are set/cleared here (473-476,
504-506).

### 1.6 Statements (`check_stmt`, 550-602)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 25 | an expression statement must be a `Call` | `this expression statement has no effect` | T | 559-560 |
| 26 | `if` condition must be `bool` | `'if' condition must be bool, got {ct}` | T | 567-568 |
| 27 | `while` condition must be `bool` | `'while' condition must be bool, got {ct}` | T | 579-580 |
| 28 | nested `def` is refused | `nested functions are not supported` | T | 594-595 |
| 29 | `struct` inside a block is refused | `struct definitions must be at the top level` | T | 600 |
| 30 | unknown statement node kind | `unsupported statement {type(s).__name__}` | T | 602 |

Hints: (25) *"only a call can be used as a statement here; assign the value, or
write 'x += 1' rather than 'x + 1'"*; (26) *"Vela has no truthiness: write 'if
x != 0:'"*; (27) *"Vela has no truthiness: write 'while i < n:'"*; (28) *"Vela
has no closures; hoist it to the top level and pass what it needs as
parameters"*.
`Break`/`Continue`/`Pass` are accepted with **no checker rule at all** —
`break`/`continue` outside a loop is a parser error (`parser.py:190-198`).
(30) is unreachable through the current parser (its node set is closed).
`ExprStmt.vtype` is set (557).

### 1.7 `return` (`check_return`, 604-618)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 31 | bare `return` in a function declared to return a type | `this function must return {self.current_ret}` | T | 607-608 |
| 32 | `return e` in a `-> None` function | `this function is declared '-> None', so it cannot return a value` | T | 611-614 |
| 33 | returned expression type must equal the declared return type | `returning {t}, but the function is declared -> {self.current_ret}` | T | 617-618 |

Hint on (32): *"change the signature to '-> T' if it really produces one"*.
(33) calls `check_expr(value, expected=current_ret)` first, so a *literal*
return may be silently re-typed (§2.3) and only the non-adapting mismatch
reaches (33); a mismatched literal that does not fit reports `expected {T}, got
{t}` from rule 81 instead.

### 1.8 Declarations and scope (`check_bind`, 631-715; `declare` 208-215)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 34 | invalid assignment target node | `invalid assignment target` | T | 629 |
| 35 | a new binding needs a type or an initialiser | `'{name}' has no type and no initialiser` | T | 639 |
| 36 | only arrays may be declared without an initialiser | `'{name}' is declared without an initialiser` | T | 644-646 |
| 37 | an array literal may not be longer than the declared array | `'{name}' is declared {declared} but the literal has {len(elems)} elements` | T | 659-661 |
| 38 | a binding may not have type `None` | `'{name}' cannot have type None` | T | 669 |
| 39 | initialiser type must equal the declared type | `'{name}' is declared {declared} but initialised with {vt}` | T | 674-676 |
| 40 | no re-declaration of a name already in scope | `'{name}' is already declared in this scope` | T | 686-688 |
| 41 | (unreachable) a `mut` re-annotation of an existing name | `'{name}' is already declared` | T | 704 |
| 42 | re-declaring with a different type | `'{name}' has type {existing.type}, not {declared}` | T | 709-710 |
| 43 | assignment to an existing binding must match its type | `cannot assign {t} to '{name}' of type {existing.type}` | T | 712-713 |
| 44 | **safety**: two bindings of one name in one scope | `'{info.name}' is already declared in this scope` | S | 211-214 |
| 45 | **safety**: assigning to an immutable binding | `cannot assign to '{name}': it was declared immutable` | S | 690-694 |
| 46 | **safety**: an array name may never be re-pointed | `cannot rebind array '{name}'` | S | 699-702 |

Hints: (36) *"only arrays may be declared bare; they are zero-filled. Scalars
must be initialised."*; (40) *"Vela has no implicit shadowing"*; (44) *"Vela
forbids rebinding a name to a different meaning in one scope; use a new name"*;
(45) *"declare it as 'mut {name}: {existing.type} = ...' if you really mean to
change it"*; (46) *"arrays have value semantics in Vela but are not rebindable;
copy element by element with a loop"*.

Detail that matters for the port:

* New binding: if `declared_type` is present it is resolved (rule 1-7 errors
  fire here); if the value is a `ListLit` and the declared type is an `ArrayT`,
  each element is checked with `expected=declared.elem`, rule 37 applies, and
  `s.value.pad_to = declared.size` is recorded (662) — **`pad_to` is written but
  never read by `codegen.py`**; the short-literal zero-fill happens implicitly
  through the arena. When the literal is shorter than the array the *whole*
  literal keeps type `Array[elem, len(elems)]` in `check_listlit` but is
  overwritten with the declared type at 663, so the back end sees the declared
  type.
* Otherwise `vt = check_expr(s.value, expected=declared)`; `s.vtype = vt`;
  rule 39 compares them.
* `is_local_array = isinstance(vt, ArrayT) and s.is_mut` (677-679) — this is
  the flag `verify_parallel` depends on, so **a non-`mut` array, a parameter
  array and an array field are all un-writable in parallel**.
* Existing binding: `is_decl` (syntax forced a new binding) → rule 40; not
  `mut` → Rule 45; array type → rule 46; `is_mut and declared_type` → rule 41
  (unreachable after 40); then RHS check against the *existing* type (43), then
  optional re-annotation comparison (42). Note that a plain `x = 3` on an
  existing `mut x` reaches rule 43 with `expected=existing.type`, which is
  where int-literal adaptation applies.
* `declare()` raises rule 44, not rule 40 — the two different texts matter.
  A declaration of a name that exists in an *enclosing* block is legal
  (shadowing across blocks is allowed; only same-scope rebinding is refused).

### 1.9 Index store and field store (731-750)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 47 | only an array can be indexed | `cannot index into {bt}` | T | 735 |
| 48 | a struct has no fields | `{bt} has no fields` | T | 746 |
| 49 | **safety**: array element write through a non-`mut` name | `cannot write to elements of '{base.id}': the array is not mutable` | S | 757-760 |
| 50 | **safety**: struct field write through a non-`mut` name | `cannot modify '{base.id}': it is not mutable` | S | 761-763 |

Hints: (49) `declare it as 'mut {base.id}: Array[...]'`; (50) `declare it as 'mut
{base.id}: {bt} = ...' or take it as a 'mut' parameter'`.
`_require_writable` (752-767) only fires when the base is a `Name` found in
scope; `self.field = …` recurses through `Attribute` to the `self` name, so a
non-`mut self` method cannot assign a field (rule 50). An index store also
runs `_check_index` (rules 57/58) and sets `s.vtype = bt.elem` (740).

### 1.10 Augmented assignment (`check_augassign`, 769-814)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 51 | `x += 1` on an undefined name | `'{target.id}' is not defined` | T | 774 |
| 52 | `x += 1` where `x` is not `mut` | `cannot modify '{target.id}': it was declared immutable` | S | 776-779 |
| 53 | indexed target must be an array | `cannot index into {bt}` | T | 784 |
| 54 | attribute target must be a struct | `{bt} has no fields` | T | 792 |
| 55 | unknown augmented target | `invalid augmented assignment target` | T | 796 |

Hint on (52): `use 'mut {target.id} = ...'`.
The RHS is checked with `expected=t` and the operator routed through the *same*
`_arith_result` (rules 97-106), so `a += f` on an `int` reports
`operator '+=' mixes …`? **No** — the operator string passed is the base op
(`s.op` is already stripped of `=` by the parser, `parser.py:416`), so the
message reads `operator '+' mixes int and float`. For a `Name` target the
integer interval is then updated via `plan_op_check` (805-812) for `+ - *`, and
invalidated otherwise. For `Index`/`Attribute` targets no interval update and
**no `need_overflow` word is ever written** — `codegen.augassign` uses
`getattr(s, "need_overflow", True)`, so a checked add is always emitted there
and no counter is incremented on either side (`codegen.py:554-556`). This
asymmetry must be reproduced: **`elided_overflow` + `emitted_overflow` does not
equal the number of augmented assignments in the program.**

### 1.11 `for` loops (`range_interval` 818-841, `check_for_range` 843-876)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 56 | start and stop must be `int` | `range() bounds must be int` | T | 847 |
| 57 | a constant step of zero is a compile error | `range() step must not be zero` | S | 853 |
| 58 | (duplicate path) step zero, also reachable via `range_interval` | `range() step must not be zero` | S | 835 |
| 59 | a constant bound outside int64 is a compile error | `range bound overflows int` | S | 862 |

Order inside 843-876: `start` then `stop` checked with `expected=INT` (a
mismatch surfaces as rule 81 `expected int, got …`, *not* as 56); step checked
with `expected=INT` (849); step const-folded, `s.step_const` = the constant or
`None` (851-856); then, for each of start/stop/step that is present, `const_int`
range check (59). Then the loop variable's interval is computed, the scope is
pushed with `loop_entry`, and the variable is declared
`VarInfo(target, INT, is_mut=False)` (870) — **that is what makes
`mutate_loop_variable` ("declared immutable") fire**. Body is checked inside
that scope, `parallel` is verified inside that same scope (874), then `pop()`.
`end_of_line`: `for` iterables other than `range` are a parser error; the
checker's `check_for_iter` (878-883) is unreachable, but if reached it reports
`iterating over a {it} is not implemented in Vela 0.1` (T) with hint *"use 'for
i in range(0, len(x)):' and index explicitly"*.

`range_interval` (the tracked interval for the loop variable): with no step, if
both ends are known and `stop <= start` the loop never runs → `[lo, lo]`;
otherwise `[start, stop-1]`; if only `start` is exact → `[start, +inf]`; else
unknown. With a step, both start and step must be exact: zero → rule 58,
`last = stop-1` (or `+1` for negative step), empty range → `[lo, lo]`, else
`[lo, max(lo, last)]`; a non-exact stop → unknown. So a loop variable is
**never** given a lower bound alone unless its start is a compile-time constant.

### 1.12 `parallel for` restrictions (`verify_parallel` 887-933)

Entry order inside `verify_parallel`: save/replace `self.loop_bounds` with
`{target: (start, stop, step)}`, build the env, collect writes, restore
`loop_bounds`, group writes by base name, then apply the rules below **in the
order of `by_base` iteration (insertion order = first-write order) and, per
base, first the "writes nothing" rule only if there are no writes at all.**

(The rule numbers here are 130-145, not 60-75: this block is checked *after*
the whole body has been type-checked, so it is the last thing the checker does
per function, and the numbering follows execution order. There are no rules
60-75 — the inventory has 126 entries numbered 1-59 and 76-145.)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 130 | a parallel body that writes nothing | `'parallel for' has no effect: the body writes nothing` | S | 911-913 |
| 131 | every written base must be a local `mut` array | `'parallel for' writes to '{base}', which is not a mutable array local to this function` | S | 918-921 |
| 132 | one base, one index expression | `'parallel for' writes to '{base}' at {len(keys)} different index expressions, so two iterations could land on the same element` | S | 925-929 |
| 133 | that index must be provably injective | `cannot prove that 'parallel for' writes '{base}' to distinct elements` | S | 1032-1034 |
| 134 | an indexed write may not go through a nested expression | `'parallel for' may only write to a local array, not through a nested expression` | S | 940-942 |
| 135 | indexed aug-assign must be a bare array name | `'parallel for' may only write to a local array` | S | 960-961 |
| 136 | assignment to a non-local name | `'parallel for' assigns to '{st.target.id}', which is shared across every iteration` | S | 948-952 |
| 137 | aug-assign to a non-local name | `'parallel for' accumulates into '{st.target.id}', which is shared across iterations` | S | 966-970 |
| 138 | any other assignment target | `'parallel for' may only write to array elements` | S | 954-955 |
| 139 | any other aug-assign target | `'parallel for' may not write this target` | S | 972-973 |
| 140 | nested parallel loop | `nested 'parallel for' is not supported in Vela 0.1` | S | 979-980 |
| 141 | a non-range `for` in the body | `'parallel for' bodies may not contain a non-range 'for'` | S | 994-995 |
| 142 | `return` in the body | `'parallel for' bodies may not 'return'` | S | 999 |
| 143 | unknown statement in the body | `unsupported statement in 'parallel for': {type(st).__name__}` | S | 1003-1005 |
| 144 | call to `print` in the body | `'print' inside a 'parallel for' would interleave output unpredictably` | S | 1012-1014 |
| 145 | call to a non-`pure` user function or method in the body | `call to impure function '{sig.name}' inside 'parallel for'` | S | 1021-1025 |

Hints: (130) *"remove the 'parallel' keyword"*; (131) *"only a locally
declared 'mut' array can be written in parallel: a parameter could alias
another array"*; (132) *"write each element once, through one index
expression"*; (133) *"the index must look like  c * i + j  where c is a
compile-time constant strictly larger than the range of  j, or like  i * N + j
where j runs over range(0, N)"*; (136) *"write results into a local array
instead, or run the loop sequentially"*; (137) *"a per-iteration accumulator
must be declared inside the loop body"*; (145) *"mark it 'pure def
{sig.name}(...)' if it has no side effects"*.

Structural facts the port must reproduce (`_collect_writes`, 935-1005):

* a write is `(base_name, Index node, snapshot of env)`; only
  `Index`/`Name`/`Attribute` targets exist, and `Attribute` falls into rules
  138/139;
* a **plain** `a[i]` scan descends into `If` (both arms, same env), `ForRange`
  (non-parallel only; adds the inner loop's variable→bounds to `loop_bounds`
  and to the env, then recurses) and **`While`** (same env, no narrowing) —
  `While` is deliberately allowed so a Mandelbrot escape loop remains
  parallelisable;
* `_parallel_rhs_ok` (1007-1027) recursively requires purity of every call in an
  assignment RHS and in every `ExprStmt`. It resolves user functions via
  `_resolve_call_target` and methods via `e.func.value.vtype` (the receiver
  must already have been type-checked — it has, because the body was checked
  before this pass). **Bug to preserve or fix deliberately**: for a *builtin*
  call, `_resolve_call_target` (1448-1466) returns a synthetic `FuncSig` with
  `is_pure=True` for **every** entry of `BUILTIN_SIGS` (1459-1462) — the third
  tuple element (`pure`) is never consulted — so `now()`, `read_text()`,
  `write_text()`, `intern()`, `panic()`, `emit_*` and `warn_*` are all accepted
  inside `parallel for`, even though `check_call` refuses them inside a `pure`
  function (rule 118). `print` is caught only by name via the `kind == "print"`
  branch (1011-1014) and `len()` falls through with `sig = None`. Also note the
  method branch at 1015-1019 re-resolves the signature, so the earlier
  `sig = self.prog.method_sigs.get(...)` assignment is redundant but harmless.
* `self.loop_bounds` is a **new dict per parallel loop**, so a nested
  *sequential* loop's bounds are visible to `_match_rowmajor` only while the
  enclosing parallel body is being collected. Nothing clears an inner loop's
  entry after it is scanned, so two nested loops re-using one variable name
  (`j` in two sibling loops) leave the later entry in place.
* `_loop_bound_for` (1103-1105) is **dead code**.

### 1.13 Index reads and bounds counting (`_check_index`, 1109-1130)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 76 | index must be `int` | `array index must be int, got {itype}` | T | 1111 |
| 77 | a constant index outside `[0, size)` | `constant index {cv} is out of range for {bt}` | S | 1117-1120 |

Hint on (77): `valid indices are 0..{bt.size - 1}`.
Side effects, in this order: `node.array_size = bt.size`, `node.vtype =
bt.elem`, then either the constant path (77 → `need_bounds = False`,
program counter `elided_bounds += 1`, 1121-1123) or the interval path
(`iv.within(bt.size)` → elided, else `need_bounds = True` and `emitted_bounds
+= 1`, 1124-1130). `within` is `lo >= 0 and hi <= size - 1` (`analysis.py:60-62`).
Callers pass `expected=INT` at the *write* sites only — `check_index_store`
(737) and the indexed branch of `check_augassign` (786) — while a plain read
(1180) and the `_check_expr` fast path pass nothing, so a `u8` index in a read
reports rule 76 rather than rule 81's generic message. Every
index node — read or write target — passes through here exactly once, so the
bound counters are exactly "number of index operations".

### 1.14 Expressions: dispatch and literal limits (`check_expr` 1134-1145, `_check_expr` 1147-1195)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 78 | integer literal must fit signed 64-bit | `integer literal {e.value} does not fit in int (64-bit signed)` | S | 1150-1152 |
| 79 | slices do not exist | `slices are not implemented in Vela 0.1` | T | 1190-1194 |
| 80 | unknown expression node | `unsupported expression {type(e).__name__}` | T | 1195 |
| 81 | `expected`/actual mismatch (after literal adaptation) | `expected {expected}, got {t}` | T | 1144 |

Hint on (79): *"this is a real part of the design (a slice is a borrowed view
with a static lifetime); it is not in the first release"*.
`check_expr` is the **only** place `e.vtype` is assigned for a general
expression (1136), and the only place literal adaptation runs (1137-1143).
Node-type dispatch order: `IntLit` → `FloatLit` → `StrLit` → `BoolLit` →
`NoneLit` → `ListLit` → `Name` → `BinOp` → `UnaryOp` → `BoolOp` → `Compare` →
`Call` → `Index` → `Attribute` → `SliceExpr` → error.
`NoneLit` returns the singleton `NoneT`; `NONE == NONE` by structural equality
(`types.py:64-68`), never by identity.

### 1.15 Names (`check_name`, 1197-1209)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 82 | a struct name used as a value | `'{e.id}' is a struct; it must be called to build a value: {e.id}(...)` | T | 1201-1203 |
| 83 | undefined name | `'{e.id}' is not defined` | T | 1204 |

Side effects: `is_array_param`, `is_struct_param`, `var_is_mut`,
`var_is_local_array` are written on the `Name` node (1205-1208) — the back end
reads the first two (`codegen.py:273, 285, 290`) and the other two are currently
unused. Rule 82 fires only when the name is not a local/parameter but *is* a
struct.

### 1.16 Array literals (`check_listlit`, 1211-1219)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 84 | empty literal has no element type | `empty array literal has no element type` | T | 1213 |
| 85 | elements after the first must match the first element's type | `expected {first}, got {t}` | T | 1218 via 1144 |
| 86 | an array literal used where an array is not expected | `expected {expected}, got {ArrayT}` / `cannot assign …` | T | 1144/712 |

The result type is `ArrayT(first, len(elems))` (1219) — note it is the *literal*
length, not the declared length. `[` `]` with no elements is also a parser error
(`parser.py:650-654`), so 84 is unreachable today.

### 1.17 Fields and places (`_field_type` 1221-1225, `_require_place` 1227-1235)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 87 | unknown field name | `{bt.name} has no field '{attr}'` | T | 1225 |
| 88 | field access needs place-shaped base | `cannot {what} a temporary struct` | T | 1232-1234 |
| 89 | non-struct base for `.` | `{bt} has no attribute '{e.attr}'` | T | 1186 |

Rule 88's `{what}` is either `"read a field from"` (1187) or `"call a method on"`
(1594); hint: *"bind the struct to a variable first: 'p = Vec2(1.0, 2.0)' then
use p.x"*. A *place* is a `Name`, `Index` or `Attribute` only — so
`P(1).x`, `f().x` and `(a + b).x` are refused. Note the field read path also
calls `_require_place` (1187) while `check_field_store` (742-750) does **not**:
a store to a temporary is impossible because the parser already refuses a
`Call` target (`parser.py:424-427`).

### 1.18 Unary, boolean and comparison operators

`check_unary` (1237-1251):

| # | rule | message | kind | site |
|---|---|---|---|---|
| 90 | `not` needs `bool` | `'not' needs bool, got {t}` | T | 1241 |
| 91 | `~` needs an integer | `'~' needs an integer, got {t}` | T | 1245 |
| 92 | unary `-`/`+` needs a number | `unary '{e.op}' needs a number, got {t}` | T | 1249 |
| 93 | unknown unary operator | `unsupported unary operator '{e.op}'` | T | 1251 |

`check_boolop` (1253-1260):

| # | rule | message | kind | site |
|---|---|---|---|---|
| 94 | `and`/`or` need `bool` on both sides | `'{e.op}' needs bool on both sides, got {lt} and {rt}` | T | 1257-1258 |

Hint: *"Vela has no truthiness and no value-returning 'and'"*; result is `BOOL`
(1260). Note `and`/`or` are strictly binary in the AST (`parser.py:454-470`
builds left-assoc chains of two-operand nodes).

`check_compare` (1262-1294):

| # | rule | message | kind | site |
|---|---|---|---|---|
| 95 | ordering operators need two numbers | `'{e.op}' needs numbers, got {lt} and {rt}` | T | 1273 |
| 96 | the two sides must have the same type | `cannot compare {lt} with {rt}` | T | 1275-1278 |

Hint: *"Vela never converts numbers implicitly; use to_float() or to_int()
explicitly"*; result is `BOOL` (1279). Note ordering is attempted *before* the
equality/identical-type check, and that `==`/`!=` on two `str` values is
allowed (same type); `==` on `bool` allowed; `==` across `int`/`i32` refused
(97).

### 1.19 Arithmetic and the operator table (`_arith_result`, 1296-1344)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 97 | no string concatenation | `string concatenation is not implemented in Vela 0.1` | T | 1300-1304 |
| 98 | both operands must be scalars | `operator '{op}' cannot be applied to {lt} and {rt}` | T | 1306-1307 |
| 99 | `+ - *` must not mix types | `operator '{op}' mixes {lt} and {rt}` | T | 1310-1313 |
| 100 | `/` is float-only | `'/' is float division; got {lt} and {rt}` | T | 1319-1322 |
| 101 | `//` is int-only | `'//' is integer division; got {lt} and {rt}` | T | 1326 |
| 102 | `%` is int-only | `'%' is integer remainder; got {lt} and {rt}` | T | 1330 |
| 103 | `<<`/`>>` need ints (message uses the operator) | `'{op}' needs integers, got {lt} and {rt}` | T | 1334 |
| 104 | `&`/`|`/`^` need ints (same text) | `'{op}' needs integers, got {lt} and {rt}` | T | 1338 |
| 105 | `**` needs matching numeric operands | `'**' needs matching numeric operands, got {lt} and {rt}` | T | 1342-1343 |
| 106 | fallback for an operator not in the table | `operator '{op}' is not defined for {lt} and {rt}` | T | 1344 |

Hints: (97) *"a returned concat could point into a released arena frame, so 0.1
ships literals only rather than shipping a dangling-str bug"*; (99) *"Vela never
converts numbers implicitly; write to_float(x) or to_int(x)"*; (100) *"use '//'
for integer division — Vela refuses to make '/' silently return a float for ints
the way Python does"*.
Result-type summary: `+ - *` → the (equal) operand type if `is_numeric`; `/` →
`F64`; `//`, `%`, `<<`, `>>`, `& | ^` → the left operand's type (both must be
`is_int`; note this means `u8 // u8` is `u8`, `i32 << int` is `i32`); `**` → the
common type if `is_numeric`. **`bool` is not numeric**, so `True + True` is
refused by (99)/(98). `str` operators: `+` refused by (97); `*`, `<` etc. refused
by (98).

Operand types come from `check_expr` with **no** `expected`, so `1 + 1.0`
reports (99) `operator '+' mixes int and float`, while `1 + x` where
`x: float` also reports (99) — matching the two REJECT cases
`implicit_int_to_float`/`float_to_int_variable`, which assert on "mixes".

### 1.20 Binop checks after typing (`check_binop`, 1346-1375)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 107 | integer `+ - * **` may overflow (proof, §3) | — | — | 1351-1352, 1377-1410 |
| 108 | division/modulo by a constant zero | `division by constant zero` | S | 1358 |
| 109 | float division by a literal `0.0` | `float division by constant zero` | S | 1361-1365 |
| 110 | constant shift amount outside 0..63 | `shift by constant {sh} is out of range 0..63` | S | 1369-1370 |

Hint on (109): *"IEEE would give inf or nan here, which is almost never what the
code meant; divide by a runtime value if you genuinely want inf"*.
Side effects per operator:

* `+ - *` on an integer result type → `plan_op_check` writes
  `e.need_overflow` and bumps one counter. `**` is included in the
  "`result.is_int` and op not in `<<`/`>>`" condition at 1351, so `2 ** 3`
  does get a `need_overflow` word via `plan_op_check`, whose `else` branch
  returns `Interval.unknown()` **without** writing the flag (1402-1403) —
  meaning **`2 ** 3` leaves `need_overflow` unset and bumps no counter**;
  `codegen.py:218-221` emits `vela_pow_int` unconditionally, whose runtime
  panics on a negative exponent (`runtime/vela_runtime.h:217`). This is a real
  hole in the Python checker's accounting; say so, do not silently "fix" it
  when comparing counters.
* `/ // %` → the constant-zero rules (108)/(109); `// %` additionally compute
  `e.need_divzero = not (biv.known and (biv.lo > 0 or biv.hi < 0))` (1372-1374).
* `<< >>` → `e.need_shift_check = (const amount is None)` (1371); a constant in
  range sets `False`, an out-of-range constant raises 110.
* Binary shifts are **excluded** from overflow checking, but `vela_shl_int` /
  `vela_shr_int` still panic at runtime for a non-constant amount out of 0..63.

### 1.21 Calls (`_resolve_call_target` 1448-1470, `check_call` 1472-1587)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 111 | callee must be a name or an attribute | `only named functions can be called` | T | 1479 |
| 112 | `print` of an array | `cannot print an {t}` | T | 1487 |
| 113 | `print` of `None`/error/struct | `cannot print {t}` | T | 1490 |
| 114 | `print` inside a `pure` function | `a 'pure' function may not print` | T | 1494 |
| 115 | `len()` arity | `len() takes 1 argument, got {len(e.args)}` | T | 1499 |
| 116 | `len()` of something that is not an array or `str` | `len() needs an array or a str, got {t}` | T | 1509-1512 |
| 117 | builtin arity | `{name}() takes {len(ptypes)} argument(s), got {len(e.args)}` | T | 1522-1524 |
| 118 | impure builtin inside a `pure` function | `a 'pure' function may not call '{name}', which touches the host` | T | 1528-1532 |
| 119 | user function arity | `'{name}' takes {len(sig.params)} argument(s), got {len(e.args)}` | T | 1540-1542 |
| 120 | impure user function inside a `pure` function | `a 'pure' function may not call '{name}', which is not declared 'pure'` | T | 1550-1553 |
| 121 | constructor arity | `{name}(...) takes {len(fields)} field value(s), got {len(e.args)}` | T | 1560-1564 |
| 122 | unknown callee | `'{name}' is not a function` | T | 1573 |
| 123 | `mut` argument must be a variable | `parameter '{pname}' is 'mut', so the argument must be a mutable variable, not a computed expression` | S | 1585-1587 |
| 124 | `mut` argument must be a `mut` variable | `parameter '{pname}' is 'mut' but '{a.id}' is immutable` | S | 1579-1583 |

Hints: (112) *"print individual elements"*; (116) *"an array's length is part of
its type, so len() is folded to a constant at compile time"*; (118) *"a pure
function must be reproducible and free of side effects, so it may not do I/O"*;
(121) *"fields in declaration order: " + `", ".join(f"{n}: {t}" for n, t in
fields)`*; (124) *"declare '{a.id}' as 'mut' so the mutation is visible at the
call site"*.

Dispatch order in `check_call` (matters for which message a misfiled call gets):

1. `Attribute` callee → `check_method_call` (§1.22). Anything else than a
   `Name` → 111.
2. `print` → each argument `check_expr(a)` with **no expectation**; Array → 112;
   `NoneT`/`ErrorT`/`StructT` → 113; then `e.target = "builtin:print"`,
   `e.vtype = NONE`; `in_pure` → 114. (So `print(x)` where `x` is a *function
   name* reports 83 `'x' is not defined`, and printing an `Array` reports 112
   before 113's categories.)
3. `len` → arity 115; `Str` → `target = "builtin:len_str"`, `const_value` =
   decoded byte length of a `StrLit` or `None`, `vtype = INT` (**no**
   `folded_len_calls` increment, and `len` of a non-literal `str` leaves
   `const_value = None`, which `codegen.py:300-304` turns into
   `((int64_t)(expr).len)`); `ArrayT` → `target = "builtin:len"`,
   `const_value = t.size`, `folded_len_calls += 1`; otherwise 116.
4. `BUILTIN_SIGS` (56-95) → arity 117; each argument `check_expr(a,
   expected=ptype)` — so `sqrt(1)` reports `expected float, got int` (rule 81),
   **not** a conversion; then 118 if `in_pure and not pure`; `target =
   "builtin:{name}"`, `vtype = ret`.
5. user signature → arity 119; args with `expected=ptype`, and for a `mut`
   parameter `_require_mut_arg` (123/124); `target = "user:{name}"`,
   `e.sig = sig`, `vtype = sig.ret`; then 120.
6. struct name → constructor: fields from `StructT.defn.resolved_fields`, arity
   121, args with `expected=ftype`, `target = "ctor:{name}"`, `vtype = st`.
7. otherwise → 122. (1571-1572 is a dead no-op branch.)

Builtin signature table to reproduce verbatim (`checker.py:56-97`), all with
positional-only synthetic parameter names `_p0, _p1, …`:

* pure: `to_float([int])->float`, `to_int([float])->int`, `sqrt([float])->float`,
  `fabs([float])->float`, `abs([int])->int`, `floor([float])->float`,
  `pow([float,float])->float`, `min_int([int,int])->int`,
  `max_int([int,int])->int`, `min_float([float,float])->float`,
  `max_float([float,float])->float`, `bytes_at([str,int])->int`,
  `substr([str,int,int])->str`, `argc([])->int`, `arg([int])->str`,
  `interned([int])->str`, `unescape([str])->str`
* impure (refused inside `pure`): `now([])->float`, `read_text([str])->str`,
  `write_text([str,str])->bool`, `emit_int([int])->None`,
  `emit_float([float])->None`, `emit_str([str])->None`, `emit_nl([])->None`,
  `intern([str])->int`, `panic([str])->None`, `warn_str([str])->None`,
  `warn_int([int])->None`, `warn_nl([])->None`
* special: `print` (variadic, impure), `len` (compile-time).

Non-obvious consequences to preserve: there is **no `abs` for `float`**
(`abs(1.5)` reports `expected int, got float`); `min_int(1.0, 2.0)` reports
`expected int, got float`; `to_int(3)` reports `expected float, got int`;
`arg(-1)` is *not* checked; `bytes_at`'s index is not bounds-checked by the
checker (the runtime does it, `runtime/vela_runtime.h:365`).

### 1.22 Method calls (`check_method_call`, 1589-1617)

| # | rule | message | kind | site |
|---|---|---|---|---|
| 125 | `.m()` on a non-struct | `{bt} has no method '{attr.attr}'` | T | 1593 |
| 126 | unknown method on a struct | `{bt.name} has no method '{attr.attr}'` | T | 1597 |
| 127 | method arity (excluding `self`) | `{bt.name}.{attr.attr}() takes {len(expected_args)} argument(s), got {len(e.args)}` | T | 1601-1603 |
| 128 | impure method inside a `pure` function | `a 'pure' function may not call '{bt.name}.{attr.attr}'` | T | 1615-1616 |
| 129 | `mut self` requires a mutable receiver | rule 124's text, with `pname = "self"` | S | 1608-1609 |

Order: receiver is type-checked first (so an undefined receiver reports 83),
then `_require_place` (88), then 125/126/127, then arguments (with
`expected=ptype` and `mut` rules), then `mut self` (129), then side effects
`e.target = "method:{Struct}.{name}"`, `e.sig`, `e.vtype = sig.ret`,
`e.receiver_type = bt`. `expected_args = sig.params[1:]`, so `self` never counts
against arity. There is no rule preventing a method from being called on a
*field* of a non-struct or on an array element of structs — `_require_place`
accepts `Index` and `Attribute`, and `codegen.receiver_addr` takes the address
of that place.

### 1.23 Counters and side-effect summary

| counter | incremented | when |
|---|---|---|
| `folded_len_calls` | 1516 | `len(array)` only |
| `elided_bounds` | 1122, 1127 | per index node proven in range (constant or interval) |
| `emitted_bounds` | 1130 | per index node not proven |
| `elided_overflow` | 1406 | per integer `+ - *` (binop or `Name` augassign) proven non-overflowing |
| `emitted_overflow` | 1409 | per such operation not proven |
| `parallel_loops` | 933 | per `parallel for` that passed §1.12 |

---

## 2. The strictness rules

"Stricter than Python" rules, with where each is enforced and what has **no**
enforcement anywhere.

| rule | enforced by | site | notes |
|---|---|---|---|
| no truthiness (`if n`, `while s`) | checker | 564-569, 576-581 | `ct != BOOL`; also `and`/`or`/`not`/ordering operands |
| no implicit numeric conversion | checker | 99 (mixes), 95/96 (compare), 81 (`expected`) | the only conversion is the int-literal adaptation |
| int-literal adaptation (the one exception) | checker | 1137-1143, 1281-1294 | see below |
| immutable by default; no rebind | checker | 45, 52, 44 | safety errors |
| scalars must be initialised | checker | 36 | arrays may be bare and are zero-filled |
| no array rebinding | checker | 46 | value semantics but no aliasing |
| struct fields may not be arrays | checker | 11 | merged with copy semantics |
| no chained comparison | **parser** | `parser.py:485-491` | `chained comparison is not allowed`; SPEC §4.1 |
| no adjacent string literals | **lexer** | `lexer.py:214-220` | `adjacent string literals are not allowed` |
| no string `+` | checker | 97 | message says "not implemented" |
| expression statement must be a call | checker | 25 | not a parser rule |
| `elif` refused | **parser** | `parser.py:284-288` | `'elif' is not part of Vela; write 'else if'` |
| tuples refused | **parser** | `parser.py:629-634` | `tuples are not supported` |
| adjacent string literals refused | **lexer** | `lexer.py:214-220` | `adjacent string literals are not allowed`, hint *"Vela has no implicit string concatenation (it is a quiet footgun in Python when a comma is missing); use the + operator, which 0.1 does not have yet, or one literal"* — note the hint's own inconsistency: `+` on two `str` is rule 97 |
| nested functions refused | checker | rule 28 | grammar accepts them; only the checker objects |
| slices refused | checker | rule 79 | parser builds `SliceExpr` (`parser.py:586`) |
| `break`/`continue` outside a loop | **parser** | `parser.py:190-198` | via `loop_depth` |
| unannotated parameter / missing return type | **parser** | `parser.py:219-223`, `239-243` | `no type annotation` / `no return type annotation` |
| a `mut` binding needs an initialiser | **parser** | `parser.py:379-382` | |
| struct body shape (fields only, ≥1 field) | **parser** | `parser.py:270-275` | |
| assignment target shape | **parser** + checker | `parser.py:421-427`, checker rules 34/55 | parser rejects literals/calls; checker re-checks |
| empty array literal | **parser** | `parser.py:650-654` | checker rule 84 unreachable |
| `parallel` needs an explicit `range(...)` | **parser** | `parser.py:345-350` | `'parallel for' requires an explicit range(...) so the compiler can prove the iteration space` |
| statement separation by newline | **parser** | `parser.py:106-122` | |
| blocks are braces, never `:` | **parser** | `parser.py:124-129` | `expected '{' to open a block` |
| every `pure` must precede `def`, `parallel` must precede `for` | **parser** | `parser.py:155-159`, `176-180` | also `d_flag`/`dump.vel:352` |
| `return` with no value in a typed function | checker | rule 31 | |
| **no `unsafe`, no pointers, no `free`, no FFI** | *nothing to enforce* | — | no syntax exists; `extern/import/from/as` are parser errors (`parser.py:148-153`) |
| **integer width conversion (`u8`→`int`)** | **nothing** — a gap, not a rule | — | SPEC §3.1; `DESIGN.md:302-307`. `mut n: int = comp[i]` on `Array[u8,…]` is refused (rules 39/43), and there is no conversion function, so `Array[u8]` is effectively unusable in a scalar context. In *C* it widens implicitly, so compiled and interpreted behaviour differ; the self-hosted subset refuses `Array[u8]` outright (`selfhost/parts/eval.vel`, `DESIGN.md:365-367`) |
| **`float` truthiness/`bool` arithmetic** | checker | rules 98/99 | `bool` is not `is_numeric`; one of the few places SPEC is stricter in code than in prose |
| purity of a **builtin** inside `parallel for` | **nothing** | — | `_resolve_call_target` marks every builtin pure (1459-1462) — see rule 145's note |
| module/multi-file (`import`) | **parser** | `parser.py:148-153` | reserved keyword, refused with a reason |

**Every one of the 126 inventory rules is a hard error — no rule in
`checker.py` produces a warning, and no `severity: warning` path exists in
`errors.py` or `ide/services.py`.** The only distinction is `TypeErr` vs
`SafetyErr`, which changes only the `kind` word in the rendered message.

### 2.1 Int-literal adaptation — the only implicit conversion (get this exactly right)

It runs in exactly two places, both required:

1. `check_expr(e, expected)` (1137-1143): if `t != expected`, both `t` and
   `expected` are integer scalars, `e` is an `IntLit` and the literal fits
   `INT_RANGE[expected]`, then `e.vtype = expected` and `expected` is returned.
   **A literal out of the target's range is not adapted** and falls through to
   rule 81 (`expected u8, got int`).
2. `_adapt_literal_pair` during comparison (1268-1269, 1281-1294): the *left*
   literal adapts to the right type first, then the right literal to the left,
   each only when it fits. So `comp[i] == 0` works for `Array[u8,…]`, and
   `0 == x`/`x == 0` both normalise to the same pair. The adapted operand's
   `vtype` is rewritten, which is what makes the emitted C cast-free.

It is **not** applied at: `_check_index`'s read path (1180 passes no
expectation — an `IntLit` index stays `INT`, which is fine), binop operands
(1166-1167, always `INT`), `check_listlit`'s first element (1214, no
expectation → `[1,2,3]` declared as `Array[u8, 3]` gets each element adapted
separately and each must fit), and `print` arguments (1485).

`INT_RANGE` (`checker.py:45-49`): `int` ±2^63, `i32` −2^31..2^31−1, `u8` 0..255.
There is **no** `f64` entry, and `FloatLit` never adapts.

### 2.2 Strictness that the *parser* leaves to the checker

`AST.md` §9 lists exactly these, and they are worth stating because they are
what the Vela port must add on top of the existing self-hosted parser:

* arity and argument types of calls (no callee-kind word, no link from a call to
  a `def`) → rules 111-122, 125-127;
* binding and mutability (`undeclared = 1`, `1 = 2` parse) → rules 34, 40, 45,
  51-55, 83;
* array length/index typing (`a[i]` carries no element type) → rules 47, 76, 77;
* pure/`parallel` semantics (flag exists on the `def`, nothing records which
  functions a body calls) → rules 134-145, 114, 118, 120, 128;
* everything numeric, because **no expression carries a type** → rules 78, 81,
  90-110;
* `len()` folding and the interval machinery → §3.

---

## 3. Proof machinery

### 3.1 `analysis.py` — intervals

`Interval` is `(lo, hi)` with `None` = unknown, `CLAMP = 1 << 62` to bound
arithmetic (`analysis.py:23-27`). Operations: `exact(v)`, `unknown()`,
`known`, `is_exact()`, `width`, `within(size)` = `known and lo >= 0 and hi <=
size-1`; `add`, `sub` (one-sided: an unknown side yields an unknown side),
`mul` (both known required, four products), `neg`, `floordiv` (unknown if the
divisor's interval straddles 0; uses Python `//` = floor), `intersect`, `join`.

`eval_interval(expr, env)` (`analysis.py:118-165`) covers:
`IntLit` → exact; `BoolLit` → exact 0/1; `Name` → `env` or unknown; unary
`-` → `neg`, unary `+` → identity, `~` → `neg(add(x, 1))` (**note: `~x = -x-1`,
correct, but built by two steps**); binop `+ - *` → the corresponding op, `/`
and `//` → `floordiv` (**so `/` is treated as floor division here, even though
the checker only allows `/` on floats — for floats the interval is meaningless
but harmless**), `%` → `[0, min(r.hi, b.hi-1)]` when the divisor's interval is
strictly positive, else unknown; `**` → exact only when both operands are exact,
the exponent is in 0..40 and `|base| < 2^24`; `Call` → exact only via
`getattr(expr, "const_value", None)` (that is how folded `len()` enters the
analysis); **everything else unknown** (so `Compare`, `BoolOp`, `Index`,
`Attribute`, `ListLit`, `StrLit` are all unknown).

`const_int(expr)` (`168-175`): exact interval with an empty env, plus a special
case for unary `+`. This is what makes `a[3]`, `a[0 - 1]` and `1 << 99`
compile-time decisions, and it **does** see through `+`/`-`/`*`/`//`/`%`/`**` on
literals and through folded `len()`.

`linear_form(expr, var)` (`196-250`) → `(c, rest)` or `None`, for `c*var + rest`
with `c` a compile-time int:

* `IntLit` → `(0, lit)`; `Name` → `(1, None)` if it is `var`, else `(0, node)`;
* unary `+`/`-` → recurse, negating both parts (`rest` becomes `-rest`);
* `+`/`-` → combine `c`s, combine `rest`s (a `None` rest means constant 0);
* `*` → **only** if at most one side depends on `var`; if the `var`-side is
  `lc*var + lr` and the other side is *not* a compile-time int, return `None`
  (so `var * n` with runtime `n` is not linear); the constant is multiplied into
  `rest`;
* everything else → `None`. `var * var`, calls, indices, attributes → `None`.

### 3.2 What the checker records, and when a proof succeeds

Two scopes' worth of state (`scopes`, `interval_scopes`, `dirty_scopes`,
`checker.py:190-241`):

* `push(intervals)` / `pop()` — leaving a block marks every name the block
  dirtied as `unknown` in the **nearest enclosing scope that knows it**, and
  stops at the first such scope (199-203). This is the rule that keeps branch
  and back-edge facts from leaking.
* `set_interval` writes into the top scope and, if the name lives in an outer
  scope, records it in `dirty_scopes[top]` (223-239).
* `assigned_names(stmts)` (245-265) collects every `Name` target of a plain
  `Assign` and `AugAssign` anywhere inside a statement list, recursing into
  `body`/`orelse` — **it deliberately does not look at array elements or
  fields**, so `a[i] = …` never invalidates `i`.
* `loop_entry(stmts, cond, extra)` (267-279): start every assigned name at
  unknown, then *narrow* from the loop condition (positive polarity) by
  intersecting, then apply `extra` (the loop variable interval). This is the
  invalidation that `DESIGN.md:127-132` calls the single most important rule.
* `narrow_from(cond, positive)` (298-341) only uses facts that are certain:
  `A and B` (positive) → both; `A or B` (negative) → both negated; `not X` →
  `X` with flipped polarity; a `Compare` whose one side is a `Name` and other is
  a `const_int` → `Interval(None, k-1)` / `(None, k)` / `(k+1, None)` /
  `(k, None)` / `exact(k)` for `< <= > >= ==`; `!=` and any non-constant
  comparison give nothing; a flipped comparison (`k < x`) flips the operator.
  The result is intersected with the name's current interval.
* `track_interval(name, t, value)` (717-729): only for integer scalars — arrays,
  floats, bools, strings and structs are never tracked (so `bool` and `float`
  loops get no narrowing); a *bare* array declaration records `exact(0)` (the
  zero-fill), otherwise the interval of the initialiser, **downgraded to unknown
  if it is not known**.

**Bounds elision (`_check_index`).** A check on `a[i]` is omitted exactly when
(1) `i` is a compile-time constant in `[0, size)` — rule 77 turns an
out-of-range constant into a compile-time error instead — or (2)
`eval_interval(i, interval_env()).within(size)`. Anything else emits
`need_bounds = True` and the back end emits `vela_bounds_check` with the array
size and the current line (`codegen.py:259-268, 505-521, 531-546`).
`need_bounds` is a *per-index-node* word.

**Overflow elision (`plan_op_check`, 1383-1410).** For `op ∈ {+,-,*}` on an
integer scalar type `t` with `INT_RANGE[t] = (lo, hi)`:

* `+`: needs `_add_no_up(a,b,hi) and _add_no_down(a,b,lo)`.
  `_add_no_up`: if `a.hi` is unknown, require `b.hi is not None and b.hi <= 0`;
  if `b.hi` is unknown, require `a.hi <= 0`; else `a.hi + b.hi <= hi`.
  `_add_no_down`: true if `b.lo >= 0`, or `a.lo >= 0`, else both lower bounds
  known and `a.lo + b.lo >= lo`.
* `-`: `_sub_no_up` true if `b.lo >= 0`, else both known and `a.hi - b.lo <= hi`;
  `_sub_no_down` true if `a.lo >= 0 and b.hi <= 0`, else both known and
  `a.lo - b.hi >= lo`.
* `*`: requires the product interval to be `known` and inside `[lo, hi]`.
* the result interval recorded for the destination (`iv`) is `A.add/sub/mul`
  **regardless of whether the check was elided**.
* any other operator (`**`, shifts, etc.) returns `unknown()` **without writing
  `need_overflow`** — see the `**` hole in §1.20.

The interesting case the design is built around: `it + 1` with `it <= 99` and an
unknown lower bound is elided, because `b.lo = 1 >= 0` discharges the lower
side. `while_with_narrowing` and Mandelbrot's `it` depend on it.

**Where intervals are updated.** Only: new bindings (`track_interval`), plain
assignments to an existing name (`track_interval`), plain `Name` augmented
assignment for `+ - *` (`plan_op_check`'s result), and loop entry
(`loop_entry`). Everything else that writes a name calls
`invalidate_interval` (814 for other augassign ops) — and array-element/field
writes invalidate nothing, because `assigned_names` does not see them.

`program.elided_bounds` counts index **nodes** whose check was proved
unnecessary (either the constant path or the interval path) — one per `a[i]`,
read or write; `emitted_bounds` counts index nodes that keep their check.
`elided_overflow` / `emitted_overflow` count integer `+`/`-`/`*` operations
(binary expressions **and** `Name`-target augmented assignments);
`folded_len_calls` counts `len(array)` only (`len(strliteral)` is folded too but
is not counted); `parallel_loops` counts verified `parallel for` statements.
`driver.py:97-105` and `ide/services.py:360-376` expose exactly these six
numbers, and `format_stats` prints `bounds checks : E proved away, K kept
(P% elided)` with `P = round(100*E/(E+K))`.

### 3.3 `verify_parallel` — the race-freedom proof

Requirements, in order, with the refusal each one produces (messages in §1.12):

1. **The loop is a `range(...)` loop** — enforced by the *parser*
   (`parser.py:345-350`), not the checker.
2. **The body writes something.** No writes → rule 130.
3. **Every written base is a local `mut` array.** `lookup(base).is_local_array`
   must hold; `is_local_array` is set only when a *binding* has an array type and
   `s.is_mut` (677-679). A parameter (`a: Array[…]`), an immutable local
   (`a: Array[int,4] = [0]`), and a struct field therefore all fail → rule 131.
   Notice `a` declared `mut` in an **enclosing function** still passes the
   ownership test if it is in scope — the rule is "local to this function", not
   "local to this loop".
4. **One index expression per base.** `{expr_key(index)}` must be a single key
   → rule 132. `expr_key` (101-132) is a *structural* string key over literals,
   names, both operands of every binary/unary/boolop/compare, call callee +
   args, index value + index, attribute base + name, and list elements; anything
   else becomes `?<NodeName>`. So `a[i]` and `a[(i)]` are the same key but
   `a[i]` and `a[i+0]` are two keys → refused. **Scalars that share a base name
   are absent here:** only indexed writes are grouped, so `tmp[i] = …` and
   `tmp[j] = …` are one base with two keys.
5. **A nested `parallel for` is refused** (rule 140) and a non-range `for` is
   refused (rule 141). A **`while` is allowed** and recursed into (986-992).
6. **No `return`** (rule 142), no unknown statement (rule 143); `break`,
   `continue` and `pass` are permitted.
7. **Scalar writes must be private to the iteration.** A `Name` target that
   `self.lookup` can resolve is shared → rules 66/67; a name that does *not*
   resolve (declared inside the body) is private and passes — its write is
   simply not collected and never indexed. Indexed writes whose base is not a
   bare `Name` → rules 64/65.
8. **Every call reachable from a write's RHS or from an `ExprStmt` must be
   provably pure** — `print` → rule 144; a user function or method with
   `is_pure == False` → rule 145. Recursion covers `left right operand value
   index func iterable cond args elems` (`_children`, 1621-1633) and does **not**
   descend into `attr.value`, so an impure call hidden in a method receiver
   expression (`a[f()].m()` on a `mut` receiver) is missed; and builtin purity is
   never consulted at all (§1.12, rule 145's note).
9. **Every indexed write must be provably injective over the iteration space**
   → rule 133. Two proofs are attempted, `_match_rowmajor` first
   (`_match_linear`, 1039-1059):
   * **row-major**: `expr` must be `(var * K) + rest` or `(K * var) + rest`
     (or the bare `var * K`, `_split_mul_add` 1086-1101), `rest` must be a bare
     `Name`, and that name must be in `self.loop_bounds` with `const_int(start)
     == 0` and either no step or a constant step; then `K` must be *structurally
     equal* (`expr_key`) to the enclosing loop's `stop` (or `stop * step` when a
     step is present) — 1061-1083. That is the `i * N + j` case: `(i, j)` are the
     base-`N` digits of the index, so distinct iterations cannot collide
     whatever `N`'s runtime value is.
   * **stride**: `linear_form(expr, var) = (c, rest)` with `c != 0`; if `rest`
     is present its interval must be `known`, and the proof needs
     `abs(c) > width(rest)` — **strictly greater** (1051-1059). `rest` absent
     means width 0, so any `c != 0` passes (a plain `a[i]`).
   * anything else → rule 133, with the hint naming both accepted shapes.
   The initialisation `self.loop_bounds = {s.target: (start, stop, step)}` (894)
   is replaced wholesale per parallel loop and restored afterwards; the
   **interval env used for `rest` is the snapshot taken at the write site**
   (`dict(env)` per write, 943/962), so narrowing that happened earlier in the
   body is visible.

`parallel_loops += 1` happens only after every check passed (933), which is why
`driver.py` can use it to decide whether to add `/openmp` or `-fopenmp`
(`driver.py:119, 135`). The self-hosted emitter deliberately does **not** emit
the pragma and runs `parallel for` sequentially (`emit.vel:1396`,
`DESIGN.md:381-383`).

---

## 4. Data the back end needs

### 4.1 The exact contract

`codegen.py` reads these attributes (all optional — every read is
`getattr(..., default)`), and that is the **complete** interface:

| attribute | written by checker | read by codegen | meaning |
|---|---|---|---|
| `expr.vtype` | `check_expr` 1136, targets at 649/663/715/740/750/801 | `ct()`, `expr()`, `dispatch_print`, `binop` (`lt`), `unary` (`operand.vtype`), `compare` (`left.vtype`), `_apply_op` | the type of every expression |
| `Index.node.need_bounds` | 1121/1126/1129 | 261, 508, 536 | emit `vela_bounds_check` or not |
| `Index.node.array_size` | 1112 | 264, 513, 541 | the length passed to the check |
| `BinOp.need_overflow` | 1405/1408 | 197 | `vela_{add,sub,mul}_range` vs a bare C operator |
| `BinOp.need_divzero` | 1374 | 209, 214 | `vela_floor_{div,mod}` vs the unchecked variants |
| `BinOp.need_shift_check` | 1371 | 224 | `vela_{shl,shr}_int` vs `<<`/`>>` |
| `AugAssign.need_overflow` | **never written** | 555 (`getattr(..., True)`) | always a checked op |
| `Call.const_value` | 1504/1513 | 299, 301 | the folded `len` value |
| `Call.target` | 1491/1502/1514/1533/1547/1567/1610 | 295-323 | `builtin:print`, `builtin:len`, `builtin:len_str`, `builtin:NAME`, `user:NAME`, `ctor:Struct`, `method:Struct.name` |
| `Call.sig` | 1548/1611 | — (`_call_args` uses `prog.sigs`, 311/316) | diagnosed signature |
| `Call.receiver_type` | 1613 | — | unused |
| `Name.is_struct_param` | 1206 | 273, 285, 290 | receiver is `T*`, access is `->` |
| `Name.is_array_param` | 1205 | — | unused by stage 0 |
| `Name.var_is_mut`, `var_is_local_array` | 1207/1208 | — | unused by stage 0 |
| `ForRange.step_const` | 854/856 | 599 | literal bound + `++`/`--`/`+= k` |
| `StrLit/ListLit.pad_to` | 662 | — | unused; the zero-fill is implicit |
| `StructDef.resolved_fields` | 416 | 692, 699, 700, 705 | field names/types in declaration order |
| `StructDef.vtype`, `FuncDef.ret_vtype`, `Param.vtype`, `StructField.vtype` | 414/417, 464, 452, 452 | `prog.structs`, `sig.params[i][1]`, `sd.resolved_fields` | |
| `Program.sigs`, `method_sigs`, `struct_defs`, `funcs`, `structs` | 354, 427/440, 418, 399 | 311/316, 713-727 | the resolved program |
| `Program.{elided,emitted}_bounds`, `.{elided,emitted}_overflow`, `parallel_loops`, `folded_len_calls` | 1122-1130, 1406-1409, 933, 1516 | `driver.py`, `ide/services.py` | statistics |

An emitter must therefore read **exactly three booleans** to decide "keep the
check": `need_bounds` (default **False** — absence means no check),
`need_overflow` (default **True** — absence means a check), `need_divzero`
(default **True**), `need_shift_check` (default **True**). The asymmetric
defaults matter: a missing `need_bounds` means *elided*, a missing
`need_overflow` means *emitted*.

### 4.2 What that means in the self-hosted pool

The current self-hosted emitter has **none** of this: it re-derives a coarse
`K_*` kind by walking the expression tree at emission time
(`emit.vel:1520-1560`, `expr_packed`/`expr_kind`), takes array lengths from the
*declaring type record* (`unpack_len`, `emit.vel:1497`), and emits **every**
bounds and overflow check on purpose (`emit.vel:23-26, 1720-1728`). The port
needs somewhere to put per-node proof data:

* node stride is **10 ints** (`AST.md` §1, `parser.vel:11, 95`), words 0-9 all
  spoken for except word 4 (`d`), which the parser documents as
  *"uninitialised dead space … free for an interpreter to use"* (`AST.md` §9.11,
  `parser.vel:13`) — used by `if` (else head) and `for` (step), so it is *not*
  free on those two kinds; and only one word is on offer, versus the four
  independent flags above;
* word 6 (`flags`) already carries `mut`/`declaration`/`pure`/`parallel`
  (`AST.md` §1.1) and is written by the parser, so reusing its high bits is
  possible but must not collide with `dump.vel:87`'s `d_flag` output, which
  `tests/diff_ast.py` holds byte-identical to stage 0;
* therefore the realistic shape is a **side table** — a Vela-side array indexed
  by node number, one entry per proof fact (`need_bounds`, `need_overflow`,
  `need_divzero`, `need_shift_check`, static type id, `const_value`,
  `array_size`, plus scope/binding info), allocated at the capacity of the node
  pool (65 536 nodes, `AST.md` §1: 655 360 ints total, stride 10). Vela has
  one-dimensional arrays and no heap objects, so several parallel arrays (or one
  array with a manual stride) are the only options — exactly the `mem:
  Array[int, 3479552]` trick `resolve.vel`/`eval.vel`/`emit.vel` already use for
  symbols, frames and field tables;
* **no node word or side slot carries a type today** (`AST.md` §9.1, §9.10), so
  "which static type does this expression have" must be either a new side table
  or a re-computation identical to `expr_packed`'s walk. The checker itself
  cannot re-derive it cheaply: it needs the *declared* type of an arbitrary
  name, which lives in the resolver's symbol table (`resolve.vel:392-514`), not
  in the pool;
* the `expected` type parameter that drives rules 81, 85 and the literal
  adaptation has **no home in the current AST at all** — it is a value passed
  down a recursive `check_expr`, which is fine in Vela (extra `mut` parameters
  are the idiom, `DESIGN.md:283-286`) but means the adaptation decision must be
  recorded where the emitter can find it, since the emitter visits nodes in a
  different order (`emit_expr` emits operands first and needs the *operand's*
  type to choose an operator, `codegen.py`'s `binop` reads `e.left.vtype`).

### 4.3 Statistics the port must reproduce

Six integers, no more; `driver.py:97-105`, `ide/services.py:363-376` and
`__main__.py:72-76` all read them by these names, and `vela ide`'s status bar
prints `bounds 12/12 proved away`. Nothing else in the Python implementation
reads `Program`.

---

## 5. Port order

Smallest first, each slice independently testable. **Read §5.6 before starting
slice 3** — it changes the sequencing constraint.

### Slice 0 — prepare the carrier (no rules yet)

Add the side tables for per-node facts and a static-type representation
(small ints: the five scalars, `str`, `None`, `Array`, `Struct`, plus a struct
id / array length / elem type table built from the type records). Populate
nothing. Verify with `tests/diff_ast.py` and `tests/diff_lexer.py` (must stay
"identical"), and `tests/diff_emit.py` + `tests/diff_run.py` (must stay green)
to prove the new arrays are inert.

### Slice 1 — symbol resolution, scopes and `main`

Rules: 40, 44, 34, 83, 82, 19, 20, 21, plus declaration binding records
(name → type, `is_mut`, `is_param`, `is_local_array`) reusing `resolve.vel`'s
`bind_name`/`declare_local`/`add_function`/`add_struct` scaffolding
(`resolve.vel:250-514`).
Depends on `types.py` facilities: nothing exotic — `VarInfo` and `StructT.name`
identity only.
Verified by: `tests/run_tests.py` (`no_main`, `uninitialised_scalar`,
`nested_function`, `duplicate_field`), and `tests/diff_run.py`'s
`both-refuse` column, which **currently reports a DIFF for every reject case the
interpreter happily runs**; this slice is the first that moves that number.

### Slice 2 — types, literals, operators (no proofs)

Rules: 1-7 (type syntax), 78, 81, 90-106, 60ish `check_expr` dispatch, 84/85,
33, 31/32/24, 26/27, 94, 95/96, 99-101, 25, 28, 79, 87, 88, 89, 24.
Depends on `types.py`: the `Scalar` family and `is_numeric`/`is_int`/`is_float`/
`is_bool`, `ArrayT.elem/size`, `Str`/`NoneT` equality, `INT_RANGE`,
`TYPE_NAMES`, and the int-literal adaptation (`_adapt_literal_pair`).
Verified by: `tests/run_tests.py` reject cases `implicit_int_to_float`,
`float_to_int_variable`, `truthiness`, `truthiness_string`,
`useless_expression_statement`, `string_concat_not_implemented`,
`slice_not_implemented`, `struct_requires_place`, `literal_overflow`,
`value_from_void_function`, `missing_return`, `missing_return_annotation`,
`unannotated_parameter`, `chained_comparison` (parser — assert it stays
rejected), `adjacent_string_literals` (parser), and every `run` case must keep
running (`diff_run.py` must not regress to "interpreter refuses by its own
limits").

### Slice 3 — calls, signatures, structs and methods

Rules: 12-18, 22, 111-129, 41-43, 39, 36, 37, 45, 46, 48-50, 51-55, 9-11, 29, 86.
Depends on `types.py`: `FuncSig` (name/params/ret/is_pure/struct_name/defn),
`StructT.defn`, `resolved_fields`, `is_copyable` (unused today, 203-205),
`cname`/`ctype` (unused by the checker).
Verified by: `run_tests.py` `immutable_rebind`, `immutable_array_element`,
`array_rebind`, `array_struct_field`, `struct_and_methods` (run case),
`struct_value_semantics` (run case), `pure_function_impure_call`, `mutate_loop_variable`,
plus `tests/run_ide_tests.py` for hover/signature/completion if the port feeds
the IDE.

### Slice 4 — the interval engine, bounds elision

Rules: 57/59/56 (range), 76/77, and a faithful port of `analysis.py`
(`Interval`, `add/sub/mul/neg/floordiv/intersect/join`, `eval_interval`,
`const_int`, `linear_form`), `narrow_from`, `assigned_names`, `loop_entry`,
`set_interval`/`dirty_scopes`/`pop`, `track_interval`, `_check_index`.
Depends on `types.py`: only `Scalar.is_int` (tracking) and `ArrayT.size/elem`.
This is the first slice that changes emitted C **and** the counters.
Verified by: `tests/run_tests.py` `arrays_and_len_folding`, `array_zero_filled`,
`while_with_narrowing`, `const_index_out_of_range`, `negative_const_index`,
`divide_by_mutable_zero_checks_at_runtime`; and by the six statistics in
`python -m vela check` for the corpus programs (record the stage-0 numbers
first — they are the regression baseline).

### Slice 5 — overflow, division and shift proofs

Rules: 107-110, `plan_op_check` and the four one-sided helpers, the `Iterval`
update path in `check_augassign` (805-814), `step_const`, `need_divzero`,
`need_shift_check`, `folded_len_calls`.
Verified by: `run_tests.py` `int_width_literals`, `arith_basics`,
`division_by_constant_zero`, `divide_float_by_literal_zero`,
`shift_out_of_range`, `literal_overflow`; counters again.

### Slice 6 — `parallel for`

Rules: 130-145, 133's two proofs, `expr_key`, `loop_bounds`, `_collect_writes`,
`_parallel_rhs_ok`, `_match_linear`/`_match_rowmajor`/`_split_mul_add`.
Depends on: `ExprKey` structural equality (a string-key equivalent over the
pool), the *already type-checked* receiver of a method call, and the
`is_local_array` binding flag from Slice 1/3.
Verified by: `run_tests.py` `parallel_for_correct`, `parallel_nested_2d`,
`pure_call_inside_parallel` (must *keep* compiling), and reject cases
`parallel_shared_accumulator`, `parallel_unprovable_index`,
`parallel_writes_parameter`, `parallel_impure_call`, `parallel_print`;
`bench/matmul.vel` and `bench/mandelbrot.vel` through
`tests/diff_run.py --bench` (they exercise narrowing + row-major together).

### Slice 7 — parity harness

Add a Vela-side `check` subcommand (or a side-effect-free `check-only` mode) that
prints the same six counters, and a differential test analogous to the existing
ones: the Vela checker's verdict (accept/reject + first message) must equal
stage 0's on every `.vel` file in the repository — the same shape as
`diff_lexer.py`/`diff_ast.py`, and the only honest way to close the port.

### 5.1 Which rules depend on which `types.py` facilities

| `types.py` facility | rules that need it |
|---|---|
| `Scalar` + `is_int`/`is_float`/`is_bool`/`is_numeric`/`bits` | 78, 81, 90-110, interval tracking, `INT_RANGE`, the `int_bounds` choice in codegen |
| `INT/I32/U8` identity (not just `.name`) | adaptation (1137-1143, 1281-1294) and rule 99's "mixes" (identity comparison `lt != rt`) |
| `F64` | `/` (100), `**` (105), `to_float`/`to_int`/`sqrt`/... argument expectations |
| `BOOL` | conditions (26/27), `not` (90), `and`/`or` (94), `bool` is not numeric |
| `NoneT` / `NONE` | 22, 23, 31, 32, 38, 113, return-type comparison |
| `Str` / `STR` | 6 (`len_str`), 97, 95/96 for `==`, 116, 113, `bytes_at`/`substr`/`unescape` args |
| `ArrayT(elem, size)` + structural `__eq__` | 1-5, 11, 22, 36, 37, 39, 43, 47, 57-59's bound checks, 76/77, 84/85, 116, `len` folding |
| `StructT(name, defn)` + `resolved_fields` | 9-11, 22, 48-50, 87-89, 121, 125-128 |
| `FuncSig(params[(name, type, is_mut)], ret, is_pure, struct_name, defn)` | 12-18, 22, 23, 119/120, 123/124, 125-129 |
| `ErrorT` / `ERROR` | only as dead-code poison (1137, 1215, 670) — a port may drop it |
| `cname` / `ctype` / `is_copyable` | **the checker does not use them** (only codegen/types-internal) |
| `VType.name` (`__str__`) | every interpolated message — `Array[…]`, `struct` names, `str`, `None`, `<error>` must spell identically |

### 5.2 Node-pool words the self-hosted AST does not carry

From `AST.md` §1.1, §2, §6, §9: `kind 0` / `a 1` / `b 2` / `c 3` / `d 4` /
`line 5` / `flags 6` / `nx 7` / `e 8` / `f 9`, stride 10.

* **expression types** — absent (gap 1); needed by rules 33, 39, 43, 81, 85, 90-110,
  116, 121, 125-127 and by the emitter's operator choice;
* **declared types** — present only on `param` (word 2), `def` return (word 2)
  and declaration (word 3) / struct-field (`kind 6` in a `kind 4` body, word 3),
  with `name/elem/size` records only (gap 4) — so `Array` element/length come
  free but there is no structural type identity to compare (Python compares
  `ArrayT.__eq__` and `StructT.__eq__`, i.e. by value);
* **index/element linkage** — absent (gap 4): a `kind 31` node knows neither the
  array's length nor its element type, so rule 76/77 and `array_size` need the
  resolver to attach them;
* **binding/scope** — absent (gap 6, 7): no scope id, no local slot, no
  local/param/field/function distinction, no `is_mut` enforcement. `resolve.vel`
  already builds a symbol table at run time (`SYM_LOCAL`, `NT_SYM`,
  `resolve.vel:359-514`), so the checker should consume/own that table rather
  than invent a second one;
* **method ownership** — absent (gap 3): methods are plain `kind 2` nodes with
  no owner word, and name handles are content-addressed so two structs' `def
  get` are *equal*; the `(struct, name)` map must be built by the checker
  (`resolve.vel:298-348` already records owner in the field table
  `FT_OWNER`, `emit.vel:47-57`);
* **callee kind** — absent (gap 9): rules 111-122 need "is this `len`, `print`, a
  `BUILTIN_SIGS` name, a struct ctor, a user function, or a method?", and the
  parser stores no callee-kind word;
* **`pure` on the body** — the flag exists on `def` (word 6 bit 4,
  `parser.vel:531`) but nothing records which functions a body calls, so rules
  114/118/120/128 and 75 need their own call walk (which `_parallel_rhs_ok`
  already is);
* **`pad_to` / `step_const` / `need_*` / `const_value` / `array_size` /
  `resolved_fields` / `receiver_type` / `ret_vtype` / `is_*_param`** — all live
  Python attributes with no pool slot; word 4 is the only nominally free word
  and is already used by `if` (else head) and `for` (step);
* **column and end position** — only a statement line exists (gap 13); every
  message must be emitted with the line of the statement/expression node, and
  the `col` the Python checker reports comes from the specific node it raised on
  (`self.err`/`self.safety` use `node.line, node.col`). **The self-hosted pool
  has no column at all**, so a byte-identical `file:line:col:` prefix is *not*
  reachable without adding a column word or an error-position side table — this
  is the single largest fidelity gap for the port, and it is worth deciding
  explicitly whether the goal is "same verdict and same message text" or
  "byte-identical rendered diagnostic".

### 5.3 Reusing what the interpreter already does

`resolve.vel` already performs a subset of Slice 1/3 in the *interpreter* — it
binds names, records struct fields in declaration order, records methods against
their owning struct, refuses a wrong arity (`check_call`, `resolve.vel:359-392`,
comment at 140), and refuses nested arrays / `Array[u8]` — but at *run* time,
one program path at a time, and with its own panic-based reporting
(`resolve.vel:35, 49`, `"the interpreted program was refused"`). The port's job
is to do this once, before execution, and to report in the compiler's format
rather than panicking. Anything `resolve.vel` computes (field tables, method
ownership, arity) should be promoted into the checker rather than duplicated.

### 5.4 Test corpora that already exist

* `tests/run_tests.py` — 48 reject cases (one, `duplicate_field`, expects an
  *empty* expected substring, i.e. "any VelaError"; one,
  `divide_by_mutable_zero_checks_at_runtime`, must **compile** and panic at
  runtime with "division by zero", which is the `need_divzero` path of Slice 5).
* `tests/diff_emit.py` — corpus = `tests/build/*.vel` + `examples/hello.vel`,
  minus `ide_http`/`vm`/`vela`; it compares **run output**, not C text, and
  treats a self-limit refusal (`SELF_LIMITS`, `diff_emit.py:49-50`) as a
  failure, not a skip.
* `tests/diff_run.py` — same corpus (plus `tests/interp/*.vel` if present, plus
  `bench/*.vel` with `--bench`); a program stage 0 refuses must be refused by
  the interpreter with a *non-self-limit* message (`diff_run.py:143-148`), so
  Slice 1 onward directly improves this suite's score, and a self-limit refusal
  counts as a DIFF.
* `tests/diff_selfhost.py` — byte-identical C between `vm.exe` and
  `vm_by_vela.exe`, plus `--fixpoint` for generation 3.
* `tests/diff_ast.py` / `diff_lexer.py` — must stay "identical" after every
  slice; any new node word changes `dump.vel`'s output and breaks them.

### 5.5 Which rules are *not* reachable from the existing corpora

Worth adding tests for, because the port can silently miss them: rules 4, 5, 8,
14, 17, 18, 41, 42, 54, 55, 58, 84, 91, 92, 93, 97 (only partially covered),
103, 104, 105, 106, 109, 113, 115, 117, 118, 121, 122, 123, 129, 135, 139, 141,
142, 143. Several are genuinely unreachable (30, 41, 42's `is_mut` branch, 58's
duplicate path, 84, 90-93's "unsupported" branch, 111's `Attribute` path
pre-empting it, 122's dead branch) and should be *documented* as such rather
than tested.

### 5.6 The sequencing constraint the existing suites impose

> **Corrected after the fact (see the note at the end of this section).** The
> constraint as first stated here was wrong about which two compilers
> `diff_selfhost.py` compares. The analysis of *what elision changes* is right and
> worth keeping; the conclusion is not.

`tests/diff_selfhost.py` requires two builds of the **same Vela source** to emit
byte-identical C. Today the self-hosted emitter emits every check unconditionally
(`emit.vel:23-26`, `1718-1728`), while stage 0's emitter omits what the checker
proved away (`codegen.py:197, 261`). Wiring proofs into `emit.vel` therefore makes
the self-hosted emitter's C differ from **stage 0's** for any corpus program with
an elidable check — `parallel_for_correct` (`out[i]` over `range(0, 100)` on
`Array[int, 100]`), `parallel_nested_2d`, `arrays_and_len_folding`,
`while_with_narrowing`, `arith_basics`, `int_width_literals` and the benchmark
programs all qualify.

**But `diff_selfhost.py` does not compare against stage 0.** `compare_corpus`
calls `emit_c(VM, ...)` and `emit_c(other, ...)` where `other` is
`vm_by_vela.exe`, and `fixpoint` compares generation 1 against generation 3 — every
one of those is a *self-hosted* emitter running the same source. Elision applies
to all of them equally and deterministically, so byte-identity holds before and
after the change. The suites that *do* compare against stage 0 are `diff_emit.py`
(meaning: stdout of the compiled program) and `diff_run.py` (the interpreter),
and neither compares C text.

What the change really moves:

* `diff_selfhost.py` stays green — **provided** the elision decision is a pure
  function of the resolved module, identical in every build. It is a gate on
  determinism, not on check policy.
* The self-hosted compiler's **own** C is now emitted with elision, so a wrong
  proof would miscompile the compiler itself rather than merely a test program.
  The proofs must therefore err towards emitting checks, and `diff_emit.py` plus
  the counters (slice 7) are the gate that says so.
* Slice 4 remains the first slice that changes emitted C and the six counters, so
  it should be landed together with the stage-0 counter baseline recorded in §4.3.

So: build verdicts first, wire elision in with the counter baseline, and do not
gate the port on C-text parity with stage 0 — that parity was never claimed and is
not what stage 3 promises.

### 5.7 Suggested slice-to-suite mapping, compactly

| slice | primary gate | secondary gate |
|---|---|---|
| 0 | `diff_ast.py`, `diff_lexer.py` identical | `diff_emit.py` green |
| 1 | `run_tests.py -k main` / `-k uninitialised` / `-k nested` | `diff_run.py` both-refuse count rises |
| 2 | `run_tests.py` (all reject cases) | `diff_run.py` run cases still match |
| 3 | `run_tests.py -k struct` / `-k immutable` / `-k pure` | `run_ide_tests.py` if the IDE is fed |
| 4 | `run_tests.py -k array` / `-k index` / `-k narrowing`; stage-0 counter baseline | `diff_run.py`, `diff_emit.py` |
| 5 | `run_tests.py -k zero` / `-k shift` / `-k overflow`; counters | `diff_run.py` |
| 6 | `run_tests.py -k parallel`; `bench/*.vel` via `diff_run.py --bench` | `diff_emit.py` |
| 7 | new verdict-parity suite over every `.vel` in the tree | `diff_selfhost.py` (see §5.6) |

---

## 6. Unverifiable / uncertain points (stated rather than guessed)

* **`2 ** 3` leaves `need_overflow` unset** and bumps no counter (§1.20) —
  verified by reading `plan_op_check`'s `else` branch (1402-1403) and
  `codegen.py:218-221`; the *intent* is clearly a checked `vela_pow_int`, and
  the runtime does panic on a negative exponent
  (`runtime/vela_runtime.h:217`), but the flag/counter path is a hole.
* **`a[i] += 1` counts a bounds check but no overflow check** and
  `AugAssign.need_overflow` is never written (§1.10) — read from code, not
  executed.
* **Builtin purity inside `parallel for`** is never enforced, contrary to
  SPEC §7's "`print`, `read_text` and friends are refused" (§1.12). The
  `print`-by-name check is the only one that fires.
* **`_parallel_rhs_ok` does not descend into `attr.value`** (`_children`,
  1621-1633) — a call in a method receiver expression is skipped. Undercut by
  the previous point in practice.
* **`_loop_bound_for` (1103-1105) is dead code**; `check_for_iter` (878-883) and
  the `kind == "method"` branch of `_resolve_call_target` (1468-1469) are
  effectively dead too.
* **`pad_to`, `Call.receiver_type`, `Name.is_array_param`,
  `Name.var_is_mut`, `Name.var_is_local_array`, `Call.sig`, `FuncDef.ret_vtype`,
  `Param.vtype`, `StructField.vtype`, `types.is_copyable`, `types.cname`** are
  written by the checker and read by nothing in the current back end. A port may
  drop them, but it should record the decision, because the IDE or a future
  emitter may want them.
* Whether a **byte-identical rendered diagnostic** (`file:line:col: <kind>:
  <msg>` + caret + hint) is a goal is not stated anywhere I read; the pools have
  no column (`AST.md` §9.13). §5.2 records the decision point.
* `analysis.floordiv` is used for `/` in `eval_interval` even though `/` is
  float-only at the checker level; harmless today, but a Vela port that reuses
  one interval routine for both must keep the same behaviour to reproduce the
  counters exactly.
* `tests/run_tests.py`'s `duplicate_field` case expects an empty substring, so
  it passes on *any* `VelaError`; it does not pin the checker at all.
* I did not run any suite (this session produced a document only); every claim
  above is from reading the sources named at the top, not from observed output.
