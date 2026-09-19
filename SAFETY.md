# Where Vela's safety actually is

`DESIGN.md` §1.2 claims Vela is "safer than Rust" and lists the promises. This file
is that list with a program behind every line: what is claimed, the case that
proves it, the mode it is run in, what was measured, and whether the promise holds
today. Nothing below is quoted from a document without a case, and no measurement
below is prose — every number comes from `tools/safety.ps1`, which is the only
thing that produced them.

**How to reproduce the whole file.** No arguments, no setup:

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1
```

The harness copies the current compiler **once**, before any case runs, into
`%TEMP%\vela-safety\frozen\vm.exe` — creating the directory itself and copying
`LLVM-C.dll` beside it, which this compiler loads at startup — prints the copy's
SHA256 and byte size, and runs all 65 cases against that copy. This repository's
compiler is rebuilt while the corpus is being worked on (five times during the
session these cases were written, once in the middle of a run), so a run judged by
two different compilers would prove nothing about either; `-FrozenVm <path>` pins an
older build when a recorded number has to be reproduced.

**The compiler every number below was measured against:**

```
SHA256  AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0
size    795136 bytes       selfhost\build\vm.exe, 2026-09-20 02:52
```

- tally: **55 passed, 0 failed**, **3 xfail rows still showing their violation**,
  **7 holes CLOSED (the promise now holds)**. `RESULT: PASS` (exit 0). The closed
  rows are named in §3.1, the three that are still open in §3.2.
- one earlier build is pinned alongside for the before/after comparison, and it is
  what makes those two columns readable:
  `67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A` / `770560
  bytes` → **55 passed, 0 failed**, **10 xfail rows**, **0 CLOSED**. The same 55
  non-hole rows pass under both, which is what makes the seven that moved a
  difference about those seven rows and nothing else.
- the cases: `tests/safety/cases/*.vel` (65 programs, ASCII only).
- the expectations: `tests/safety/manifest.txt`, one row per case, written by hand
  from the spec before any of it was run.
- **two rules are enforced that no case in this corpus watches yet**: the operator
  table's unary/comparison half (`bfd70fc`) and member access (`099f6fc`). Both are
  measured in §3.1 and §3.4, including what each still does not reach. They are
  written down rather than left out, because a reader who saw only this corpus would
  not know the member rule exists at all.

A row marked `xfail` asserts that its promise is *broken*, so `tools/safety.ps1`
distinguishes three outcomes for it: **`FAIL-as-expected`** (the promise is still
broken — the hole is real), **`CLOSED`** (the promise now holds — the hole is shut,
counted in a tally column of its own), and **`BEHAVIOUR-CHANGED`** (the promise
holds but a run-time recording in that row moved; reported, not swallowed). Closing
a hole can never look like "another kind of failure" — that was the flaw in the
first version of this corpus, which reported the same `10 xfail` before and after
the operator-table fix and was therefore blind to its own subject.

## 1. The promise table

| # | what is claimed (quoted) | case | mode | measured | verdict |
|---|---|---|---|---|---|
| 1 | *"Integer arithmetic is checked for overflow, always."* (SPEC §6.5) | `arith_overflow_add`, `_sub`, `_mul`, `_division`, `_negation`, `_abs`, `_pow`, `arith_shift_negative_variable`, `arith_shift_64_variable` | native | compiled program **exit 2**, `vela: panic: integer overflow (addition)` etc.; `vm.exe run` exits 2 with the same reason | **ENFORCED TODAY** |
| 2 | *"Division by a constant zero is a compile error"* (SPEC §6.6) | `arith_div_by_const_zero`, `arith_mod_by_const_zero` | check | **exit 2**, `vela: safety error: division by constant zero`, nothing on stdout | **ENFORCED TODAY** |
| 3 | *"a runtime divisor is checked"* (SPEC §6.6) | `arith_div_by_zero_variable`, `arith_mod_by_zero_variable` | native | exit 2, `division by zero` / `remainder by zero` from both front ends | **ENFORCED TODAY** |
| 4 | float division by a constant zero (SPEC §6.6 spells the integer forms; `check.vel` refuses this one on purpose: IEEE "would hand back an infinity or a nan") | `arith_float_div_by_literal_zero` | check | exit 2, `float division by constant zero` | **ENFORCED TODAY** — the rule is a refusal, and the refusal is what the case pins |
| 5 | *"A constant index that is provably out of range is a compile error."* (SPEC §6.7) | `bounds_const_index_oob`, `_negative`, `_len2`, `_len1` | check | exit 2, `constant index 4 is out of range for Array[int, 4]` | **ENFORCED TODAY**, except on a zero-length array — see hole 6 |
| 6 | *"Indexing is checked. `a[i]` panics with file, line and reason if `i` is out of range"* (SPEC §6.4) | `bounds_runtime_index_oob`, `_negative`, `bounds_runtime_write_oob` | native | compiled: `vela: panic: index 4 out of range for array of length 4 (the compiler could not prove this index in range)`, exit 2; interpreted: `index out of range for this array`, exit 2 | **ENFORCED TODAY** |
| 7 | the same, for a zero-length array (`Array[int, 0]`) | `bounds_zero_len_array_runtime`, `bounds_zero_len_array_write` | native | exit 2, `index 0 out of range for array of length 0` | **ENFORCED TODAY** |
| 8 | *"A function may not return its own array (the checker refuses it), so an arena pointer cannot outlive its frame."* (SPEC §6.3) | `memory_return_own_array`, `memory_return_param_array` | check | exit 2, `function 'make' returns Array[int, 4]` | **ENFORCED TODAY** |
| 9 | *"`str` storage is either a literal or permanent host memory, so a returned string can never dangle."* (SPEC §6.3) | `memory_return_str_binding`, `memory_return_substr` | run-ok | compiled prints `hi` / `he`, interpreter prints the same, both exit 0 | **ENFORCED TODAY** (and the shape is still *accepted*, which is the half that would break if the rule were implemented by refusing) |
| 10 | *"`free` does not exist, so double-free and use-after-free cannot be written."* (SPEC §6.1) | `escape_no_free_builtin` | check | exit 2, `vela: type error: undeclared name 'free'` | **REFUSED BY DESIGN** — the name is not in the language at all |
| 11 | no pointers, no `unsafe` (SPEC §6.1) | `escape_no_free_builtin`, `escape_nested_function_refused`, `escape_no_closures` | check / run-ok | `free` is undeclared; a nested function is `nested functions are not supported`; a function with a local prints `1` and exits 0 | **REFUSED BY DESIGN** — there is no syntax to write `unsafe` in, so there is no case that could opt out |
| 12 | no globals (SPEC §6.2) | `escape_no_global_state` | check | exit 2, `a top-level statement must be a function or a struct` | **REFUSED BY DESIGN** |
| 13 | struct fields may not be arrays (SPEC §4) | `escape_array_struct_field_refused` | check | exit 2, `field 'a' may not be an array` | **REFUSED BY DESIGN** |
| 14 | *"an array the body writes may be read inside the same loop only at that same index expression"* (SPEC §7) | `concurrency_cross_iteration_read_refused`, `concurrency_cross_iteration_bare_index` | check | exit 2, `'parallel for' reads 'a' at an index other than the one it writes` | **ENFORCED TODAY** |
| 15 | the three legal `parallel for` shapes must still compile (SPEC §7) | `concurrency_read_any_index_legal`, `concurrency_same_index_legal`, `concurrency_linear_stride_legal` | run-ok | check accepts; `#pragma omp parallel for` present in the emitted C for all three; compiled and interpreted both print `14`, `8`, `4` | **ENFORCED TODAY** — this is the half that a too-strict rule would break |
| 16 | a `parallel for` the proof cannot place is *refused*, not emitted (SPEC §7) | `concurrency_parallel_outer_rowmajor_refused`, `concurrency_serial_outer_parallel_inner` | check | exit 2, `cannot prove that 'parallel for' writes 'out' to distinct elements` | **ENFORCED TODAY** |
| 17 | *"Bindings are immutable by default"* (SPEC §3.2) | `strict_immutability` | check | exit 2, `cannot assign to 'x': it was declared immutable` | **ENFORCED TODAY** |
| 18 | *"truthiness → conditions must be bool"* (SPEC §4) | `strict_no_truthiness` | check | exit 2, `'if' condition must be bool, got int` | **ENFORCED TODAY** |
| 19 | *"An expression statement must be a call."* (SPEC §4.1) | `strict_useless_expression_statement` | check | exit 2, `this expression statement has no effect` | **ENFORCED TODAY** |
| 20 | *"No implicit conversion between the types a rule can compare."* (SPEC §3.1) — the operator table's binary half | `strict_no_implicit_conversion` | check | exit 2, `operator '+' mixes int and float` | **ENFORCED TODAY** |
| 21 | the same promise, for **comparisons and unary operators** — the operator table's other half, which `DESIGN.md` §7.5 admitted was unchecked | `hole_cmp_bool_bool`, `hole_cmp_bool_int`, `hole_cmp_int_float`, `hole_cmp_str_int`, `hole_unary_neg_bool`, `hole_unary_neg_str`, `hole_unary_not_int` | violation | all seven **`CLOSED`** under `AD4A997B`: `check exit 2` with `operator '<' cannot be applied to bool and bool`, `operator '<' mixes bool and int`, `operator '<' mixes int and float`, `operator '<' cannot be applied to str and int`, `operator '-' cannot be applied to bool`, `operator '-' cannot be applied to str`, `operator 'not' cannot be applied to int`. Under the pinned `67ACF63B` all seven were `FAIL-as-expected` (`check exit 0, ok`, and one of them answering `False` at run time) | **ENFORCED TODAY as of build `AD4A997B…3D0`** (fix: `bfd70fc`) — the seven rows are how this promise is watched from now on |
| 22 | *"Tuples do not exist; `(1, 2)` is refused"* (SPEC §4.1) | `strict_no_tuples` | check | exit 2, `tuples are not supported` | **ENFORCED TODAY** |
| 23 | *"`elif` is not part of Vela"* (SPEC §4) | `strict_no_elif` | check | exit 2, `'elif' is not part of Vela; write 'else if'` | **ENFORCED TODAY** |
| 24 | *"An ordinary Vela call is not type-checked either"* (SPEC §3.1, stated as a deliberate gap) | `strict_call_int_into_float_param`, `strict_call_float_into_int_param` | run-ok | accepted; compiled prints `3.000000` / `3`, interpreter prints `3` / `3.500000` — **the two front ends disagree** | **NOT ENFORCED, and documented as such** — the case asserts the divergence, so neither side can drift silently |
| 25 | `to_int` / `to_float` (SPEC §3.1) | `strict_to_int_explicit`, `strict_to_float_explicit` | run-ok | both front ends print `3` / `3.000000` | **ENFORCED TODAY** |
| 26 | a `mut` struct parameter may be written through (SPEC §3.2 line 148) | `probe_mut_struct_parameter` | run-ok | compiled and interpreted both print `11` | **ENFORCED TODAY** |
| 27 | a `mut` array parameter may be written through (SPEC §3.2 line 148) | `probe_mut_array_parameter` | run-ok | compiled and interpreted both print `99` | **ENFORCED TODAY** |
| 28 | a `mut` *scalar* parameter may be written through (SPEC §3.2 line 148, "the callee may write through it") | `hole_mut_scalar_parameter` | run-ok | both front ends print **`10`** where the promise is `11` — the callee's `n += 1` is dropped | **NOT ENFORCED** — hole 1 |
| 29 | two `extern c` declarations of one name must not have conflicting prototypes (SPEC §12) | `hole_extern_conflicting_prototypes` | check | `check exit 0, ok` — the second declaration is accepted, and the call site resolves against whichever landed last | **NOT ENFORCED** — hole 5 |

## 2. What is *not* a hole

Two things look like holes and are not, and they are written down so nobody
"fixes" them:

* **`1 + 2.5` is refused, but `wants(3)` for `def wants(f: float)` is accepted.**
  SPEC §3.1 says so in as many words ("An ordinary Vela call is not type-checked
  either") and explains why the operator table and the C boundary are the two
  places a width is load-bearing. The cost is measured in entry 24 of the table: the
  emitted C widens and the interpreter does not, so the same source answers
  `3.000000` and `3`. It is a gap with a case, not a promise with a bug.
* **`a[i] = a[i - 1] + 1` is refused, and `out[i] = out[i] + 1` is accepted.**
  The rule is about which element an iteration may *read* of an array the body
  *writes* — reading the element you are about to write is what `par_iter_mut`
  gives you in Rust, and refusing it would break `bench/matmul.vel`.


## 3. Holes: what was closed, and what is still open

Everything in this section is a promise the compiler was measured to break, or was
measured to have stopped breaking. Ranked by the failure each one can produce, worst
first: **silent wrong answer**, then **wrong but loud**, then **a missed diagnostic**.
The compiler every claim was measured against is the one named at the top of this file
(`AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0`, `795136
bytes`), frozen by `tools/safety.ps1` with no arguments.

### 3.1 Closed during this session: the operator table's unary and comparison half

Seven of these cases were holes; they are now `CLOSED`, and they stay in the corpus as
the watch on the promise rather than being deleted.

**The promise:** SPEC §3.1, *"No implicit conversion between the types a rule can
compare."* `DESIGN.md` §7.5 admitted the gap in as many words — "the operator table is
checked for binary operators, not yet for unary ones or comparisons (`-x` on a string,
`"a" < 1`)" — and that sentence is now history.

**Closed by:** commit `bfd70fc` (the operator table), measured with the compiler named
above. **Rows in the `holes CLOSED` column:** `hole_cmp_bool_bool`, `hole_cmp_bool_int`,
`hole_cmp_int_float`, `hole_cmp_str_int`, `hole_unary_neg_bool`, `hole_unary_neg_str`,
`hole_unary_not_int`.

This is the pair of runs that shows the difference — same 65 cases, same 55 non-hole
rows, two builds, and these seven rows are the only thing that moved:

```
$ ... -FrozenVm %TEMP%\vela-safety\frozen-pin\vm.exe        # 67ACF63B…903D8A / 770560
hole_cmp_bool_bool                             violation  FAIL-as-expected
hole_cmp_bool_int                              violation  FAIL-as-expected
hole_cmp_int_float                             violation  FAIL-as-expected
hole_cmp_str_int                               violation  FAIL-as-expected
hole_unary_neg_bool                            violation  FAIL-as-expected
hole_unary_neg_str                             violation  FAIL-as-expected
hole_unary_not_int                             violation  FAIL-as-expected
tally           : 55 passed, 0 failed
                  xfail rows still showing their violation: 10
                  holes CLOSED (the promise now holds): 0

$ powershell -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1   # AD4A997B…73D0 / 795136
hole_cmp_bool_bool                             violation  CLOSED
hole_cmp_bool_int                              violation  CLOSED
hole_cmp_int_float                             violation  CLOSED
hole_cmp_str_int                               violation  CLOSED
hole_unary_neg_bool                            violation  CLOSED
hole_unary_neg_str                             violation  CLOSED
hole_unary_not_int                             violation  CLOSED
tally           : 55 passed, 0 failed
                  xfail rows still showing their violation: 3
                  holes CLOSED (the promise now holds): 7
```

What the checker now prints — and note that the messages are the ones this corpus's
manifest wrote by hand as the *expected* refusals, which is the whole point of having
written them down before the fix:

```
$ vm.exe check tests\safety\cases\hole_cmp_bool_int.vel
exit=2   vela: type error: operator '<' mixes bool and int

$ vm.exe check tests\safety\cases\hole_unary_not_int.vel
exit=2   vela: type error: operator 'not' cannot be applied to int
```

Before the fix (`67ACF63B…903D8A`) the same source was accepted, and then answered
differently from one front end to the other — kept here as the measurement that
produced the cases:

```
> vm.exe check hole_cmp_bool_int.vel          -> exit=0, stdout=[ok]
> vm.exe run   hole_cmp_bool_int.vel          -> exit=2, vela: panic: a bool can only be compared with == and !=
> vm.exe build hole_cmp_bool_int.vel          -> exit=0
> hole_cmp_bool_int.exe                       -> exit=0, stdout: False     (the program is `print(True < 1)`)
```

Now those rows read `check exit 2` and nothing is built:
`native not built (the checker refuses the program, which is the promise)`. The first
version of this harness built them anyway and reported `the program must compile, but
build exited 2` — a closed hole dressed up as a failure, and the bug that made the
corpus blind to this very fix.

### 3.2 Closed during this session: member access

**The promise:** SPEC §4, the struct form `struct S { field: T ... def m(self: S, ...)
-> R { ... } }` — a struct's fields and methods are the ones it declares, so `p.zzz`
and `p.dotq(q)` name nothing. Before `099f6fc` the checker did not look at member names
at all.

**Closed by:** commit `099f6fc`, measured with the same compiler
(`AD4A997B…73D0` / `795136 bytes`). **No case in this corpus watches it**, which is why
the measurement is written out here: a reader who saw only `tests/safety/` would not
know the rule exists.

```
$ vm.exe check m1_bad_field.vel        # print(p.zzz)
exit=2   vela: type error: Pt has no field 'zzz'

$ vm.exe check m2_bad_method.vel       # print(p.dotq(q))
exit=2   vela: type error: Pt has no method 'dotq'

$ vm.exe check m3_self_bad.vel         # inside the method: return self.zzz
exit=2   vela: type error: Pt has no field 'zzz'
```

The third line is the half a rule like this usually misses — `self.` inside the
struct's own body — and it is covered.

**What it does not reach.** Five surfaces, each measured with the same invocation on a
four-line probe under `%TEMP%\vela-safety\member\`. They are deliberately *not* in
`tests/safety/cases/`: they belong in a follow-up corpus with manifest rows of their
own, and adding files without rows would leave the corpus half-wired. The "what stops
it instead" column is not a claim that the shape is safe — each of these is caught by
the emitter, the C compiler or the interpreter, and **none was observed to produce a
wrong answer** — what they have in common is that **`vm.exe check` says `ok`**:

| receiver | probe | `check` | what stops the program instead |
|---|---|---|---|
| a **nested field** (`o.inner.zzz`) | `g1_nested_field` | `exit=0  ok` | `build` -> `vela: panic: this struct has no field of that name`; `run` -> exit 2, same |
| an **array element** (`a[0].zzz`) | `g2_array_elem_receiver` | `exit=0  ok` | `build` -> `vela: panic: structs are not implemented in this slice of the back end`; `run` -> exit 2, same |
| method **arity** (`p.dot(p, q, p)` on a two-parameter method) | `g4_method_arity` | `exit=0  ok` | `build` -> the host C compiler: `error C2197: 'int64_t vl_Pt__dot(vl_Pt,vl_Pt)': too many arguments for call`; `run` -> exit 2 |
| a method called on a **non-struct** (`n.foo()` for `int n`) | `g5_int_method` | `exit=0  ok` | `build` -> `vela: panic: this struct has no method of that name`; `run` -> exit 2, same |
| a method name **read as a value** (`p.sum`) | `g6_method_as_value` | `exit=0  ok` | `build` -> `vela: panic: this struct has no field of that name`; `run` -> exit 2, same |

Two corrections to the way this gap was reported to me, both in the direction of what
the binary actually does:

* the **call-result** receiver *is* caught — `make().zzz` gives `check exit 2, vela:
  type error: cannot read a field from a temporary struct` — so it does not belong in
  the list above;
* the **array-element** receiver is caught too, but by the back end's "structs are not
  implemented in this slice" refusal rather than by a member rule, which is why it is
  listed as a non-reach with that reason rather than as a silent acceptance.

`p.sum` (reading a method name as a value) is deliberate rather than an oversight: the
checker is documented as letting it through, and `g6_method_as_value` shows the back
end refusing it afterwards.

### 3.3 Still open (3), and one more shape the harness does not call a hole

These three are **not** closed by `bfd70fc` or `099f6fc`, and the harness reports all
three as `FAIL-as-expected` under the current build — re-measured for this revision,
not assumed: `bounds_zero_len_array_constant`, `hole_mut_scalar_parameter`,
`hole_extern_conflicting_prototypes`.

**Hole 1 — `mut` on a scalar parameter does not write through, and both front ends
agree on the wrong answer.**
`DESIGN.md` §7.5 already names this one; the case is
`tests/safety/cases/hole_mut_scalar_parameter.vel`. This is the widest of the three,
because a program using it compiles, runs, exits 0, and is simply not the program that
was written — and unlike the closed holes there is no second opinion: the interpreter
agrees with the wrong answer, so nothing in the toolchain objects.

```
def bump(mut n: int) -> None { n += 1 }
def main() -> None { mut x: int = 10  bump(x)  print(x) }

> vm.exe check hole_mut_scalar_parameter.vel            -> exit=0, ok
> vm.exe build hole_mut_scalar_parameter.vel && hole_mut_scalar_parameter.exe
exit=0   stdout: 10
> vm.exe run   hole_mut_scalar_parameter.vel
exit=0   stdout: 10
```

The promise is `11` (SPEC §3.2: "`mut` before a parameter means the callee may write
through it"). The emitted C shows exactly where it is lost:

```
static void vl_bump(int64_t);                     <- arguments are by value
static void vl_bump(int64_t vl_n) {               <- the callee's own copy
int64_t vl_x = 10LL;
(void)(vl_bump(vl_x));                            <- the caller's x is untouched
```

The two controls are `probe_mut_struct_parameter` (`11`, writes through) and
`probe_mut_array_parameter` (`99`, writes through), so this is specific to scalars — a
`mut` scalar parameter is an annotation the language accepts and then ignores.

**Hole 2 — a binding narrows in the compiled program and does not in the interpreter**
(`hole_narrowing_binding_i32`, `hole_narrowing_binding_u8`, mode `diverge`; those rows
PASS because the divergence *is* the assertion):

```
> vm.exe check hole_narrowing_binding_i32.vel   -> exit=0
> compiled: 705032704        (mut x: i32 = 5000000000)
> vm.exe run: 5000000000
> compiled: 44               (mut x: u8 = 300)
> vm.exe run: 300
```

SPEC §3.1 calls this out as a deliberate stopping point ("A binding is not
type-checked, and that is where the width question stops"), so the *rule* is
documented; what is not documented anywhere is that the same source answers `705032704`
compiled and `5000000000` interpreted. The two `diverge` rows assert both numbers, so
this cannot drift: if the compiler ever stops narrowing or the interpreter starts, the
case fails loudly.

**Hole 3 — a conflicting `extern c` prototype is accepted, and the last declaration
wins.**
`tests/safety/cases/hole_extern_conflicting_prototypes.vel` declares `host_add` twice
with different signatures. Nothing refuses it:

```
> vm.exe check hole_extern_conflicting_prototypes.vel
exit=0   stdout: ok
```

Give the file a call and the emitted C carries *both* declarations, with the call
resolved against the second one:

```
> vm.exe emit-c ext_conflict_call.vel
  int64_t host_add(int64_t, int64_t);
  double  host_add(double, double);
  vela_print_f64(host_add(1.0, 2.0));
> vm.exe build ext_conflict_call.vel
exit=2
  error LNK2019: unresolved external symbol host_add referenced in function main
  error LNK1120: 1 unresolved externals
  vela: panic: build: no C compiler on this host could build the emitted C
```

This is loud (wrong but loud, not silently wrong) because the C compiler catches it,
but note *where* the failure lands: at link time, in the host toolchain's words, for a
program Vela's own checker said was fine — and SPEC §12's promise ("the argument kinds
are checked against the declaration") has silently become "checked against whichever
declaration arrived last", which is not the same promise. Two declarations of one name
are the smallest possible version of a foreign-interface mistake, and the checker has
nothing to say about it.

**Hole 4 — a constant index on a zero-length array is accepted.**
SPEC §6.7: "A constant index that is provably out of range is a compile error." `a[0]`
on `Array[int, 0]` is provably out of range, and it is accepted. The reason is one line
in `selfhost/parts/check.vel` (`ck_index`):

```vela
ln: int = unpack_len(ck)
if ln <= 0 {
    return          # <-- the constant-index rule does not run for a length of 0
}
```

```
> vm.exe check bounds_zero_len_array_constant.vel
exit=0   stdout=[ok]
> vm.exe emit-c bounds_zero_len_array_constant.vel | select-string bounds_check
  vela_print_i64((vela_bounds_check((vl_ix_6 = 0LL), 0, "<file>", 3), vl_a[vl_ix_6]));
> vm.exe run bounds_zero_len_array_constant.vel        -> exit=2, index out of range for this array
> compiled program                                      -> exit=2, index 0 out of range for array of length 0
```

Ranked last because it produces no wrong answer: the run-time check fires first and both
front ends stop the program. It is a compile error that did not happen — the one thing
the constant-index rule exists for — so the "provably out of range is a compile error"
promise is narrower than the spec sentence reads.

**Hole 5 — the `parallel for` proof `DESIGN.md` §7.5 admits is incomplete: an enclosing
loop's variable is not in the interval environment.** Not counted among the three
above, because the harness reports this shape as `PASS` rather than as a violation: the
code it admits was measured to *agree* with the interpreter.

* `concurrency_read_any_index_legal` and `concurrency_same_index_legal` are the shapes
  the proof exists to keep, and they still compile *with* the pragma
  (`#pragma omp parallel for` present, `14` and `8` from both front ends).
* `concurrency_nested_outer_read_accepted` is the admitted narrowing reachable in code:
  `a[i + j]` with `i` from an *enclosing* loop. The loop is admitted and the pragma
  emitted (`emit-c` -> 1 line of `#pragma omp parallel for`). Measured 20 runs of the
  compiled program: `28` every time, the same as the interpreter. **So the narrowing is
  real in the checker and not reproducible as a wrong answer on this program** — the
  outer loop's update happens between two parallel regions, and MSVC's OpenMP static
  schedule happens to preserve the order. A case was written for the unsafe direction
  (`out[j] = a[i + j]` with the read depending on the outer index) and the compiled
  program printed `28` twenty times out of twenty; there is no measured wrong answer to
  report, and one is not invented here.
* Stating it as a missed proof rather than a race: the write side stays sound because a
  write that cannot be placed is *refused* (`concurrency_parallel_outer_rowmajor_refused`,
  `concurrency_serial_outer_parallel_inner` both refuse with `cannot prove that
  'parallel for' writes 'out' to distinct elements`), so the environment being shallow
  costs refusals, not accepted races. That is the finding: the gap is in the *admission*
  side, and it could only become a wrong answer if a future write proof leaned on an
  outer-loop interval the environment does not have.

### 3.4 The two probes `DESIGN.md` §7.5 points at

`tests/probes/mut_scalar_parameter.vel` and `mut_struct_parameter.vel` do exist
(`Get-ChildItem tests\probes -Name -Filter 'mut*'` returns both, and `check.vel` line
1346 names them). What the repository did not have is a case in any harness that would
notice the behaviour changing: they are inputs for a probe folder, not rows in a corpus,
and `DESIGN.md` §7.5 recorded the gap in prose under "what stage 4 left". This corpus
adds the three rows (`hole_mut_scalar_parameter`, `probe_mut_struct_parameter`,
`probe_mut_array_parameter`), so if hole 1 is ever closed the case stops reporting a
violation and this file gets corrected, rather than the other way round. The same is now
true of the operator table: the seven `hole_*` rows are the watch on `bfd70fc` (§3.1),
and §3.2's member measurements are what a follow-up corpus should turn into rows.

## 4. What this corpus does not cover

- **Member access has no case.** §3.2 measures the rule and its five non-reaches by
  hand; `tests/safety/` has no row for it. The probes live in
  `%TEMP%\vela-safety\member\` and are deliberately not in the repository, because a
  `.vel` file without a manifest row would leave the corpus half-wired.
- **No `parallel for` race was reproduced as a wrong answer.** §3.3's hole 5 shape is
  admitted by the checker and produced the right answer in 20/20 runs; the strongest
  honest statement is the one above, and a machine with a different OpenMP schedule or
  thread count was not available to try to break it.
- **The corpus is standalone.** It is not wired into `tests/cases.txt` and
  `tests/run_tests.vel` does not run it; `tools/safety.ps1` is the only runner. (The
  manifest wiring is the leader's call, and `tests/cases.txt` was out of bounds for this
  round.)
- **Two `extern c` declarations were the only FFI hole tried.** Nothing was tested about
  `extern c` + `parallel for`, or about a foreign call whose declaration is added after
  its call site.
- **Numbers move.** The compiler was rebuilt repeatedly during this session; the hash at
  the top is the build every number here was measured against, the pinned older build is
  what makes the closed holes visible, and `tools/safety.ps1` prints the hash of whatever
  it actually ran, with a loud note when it is neither.
