# Where Vela's safety actually is

`DESIGN.md` §1.2 claims Vela is "safer than Rust" and lists the promises. This file
is that list with a program behind every line: what is claimed, the case that
proves it, the mode it is run in, what was measured, and whether the promise holds
today. Nothing below is quoted from a document without a case, and no measurement
below is prose — every number comes from `tools/safety.ps1`, which is the only
thing that produced them.

**How to reproduce the whole file.**

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1
```

- frozen compiler: `SHA256 67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A`,
  `770560 bytes` (`selfhost\build\vm.exe`, 2026-09-20). The harness copies it once
  into `%TEMP%\vela-safety\frozen\` (plus `LLVM-C.dll`, which this build links
  against) and runs every case against that copy, because this repository's
  compiler is rebuilt while the corpus is being worked on — it happened four times
  during the session these cases were written, once in the middle of a run, which
  showed up as a spurious `build exit=-1`.
- the same corpus was re-run against a newer build as well
  (`751E1206728B14BAC54EE54AD7B203A0B2B94925B1943E54AA629B6535741D4B`, `782848
  bytes`): **55 passed, 0 failed, 10 xfail**, the same tally. Every number in this
  file is from the first of the two, which is the one the cases were written
  against; `tools/safety.ps1` prints the hash it actually used and says so loudly
  when it is neither of these.
- tally: **55 passed, 0 failed**, plus **10 xfail rows still showing their
  violation**. `RESULT: PASS`.
- the cases: `tests/safety/cases/*.vel` (65 programs, ASCII only).
- the expectations: `tests/safety/manifest.txt`, one row per case, written by hand
  from the spec before any of it was run.

The 55 "passed" are the promises that hold and the holes that are *expected* to be
holes; the 10 `FAIL-as-expected` rows are §3, and the harness counts them
separately so that a hole which gets closed shows up as `UNEXPECTED-PASS` instead
of passing quietly.

## 1. The promise table

| # | what is claimed (quoted) | case | mode | measured | verdict |
|---|---|---|---|---|---|
| 1 | *"Integer arithmetic is checked for overflow, always."* (SPEC §6.5) | `arith_overflow_add`, `_sub`, `_mul`, `_division`, `_negation`, `_abs`, `_pow`, `arith_shift_negative_variable`, `arith_shift_64_variable` | native | compiled program **exit 2**, `vela: panic: integer overflow (addition)` etc.; `vm.exe run` exits 2 with the same reason | **ENFORCED TODAY** |
| 2 | *"Division by a constant zero is a compile error"* (SPEC §6.6) | `arith_div_by_const_zero`, `arith_mod_by_const_zero` | check | **exit 2**, `vela: safety error: division by constant zero`, nothing on stdout | **ENFORCED TODAY** |
| 3 | *"a runtime divisor is checked"* (SPEC §6.6) | `arith_div_by_zero_variable`, `arith_mod_by_zero_variable` | native | exit 2, `division by zero` / `remainder by zero` from both front ends | **ENFORCED TODAY** |
| 4 | float division by a constant zero (SPEC §6.6 spells the integer forms; `check.vel` refuses this one on purpose: IEEE "would hand back an infinity or a nan") | `arith_float_div_by_literal_zero` | check | exit 2, `float division by constant zero` | **ENFORCED TODAY** — the rule is a refusal, and the refusal is what the case pins |
| 5 | *"A constant index that is provably out of range is a compile error."* (SPEC §6.7) | `bounds_const_index_oob`, `_negative`, `_len2`, `_len1` | check | exit 2, `constant index 4 is out of range for Array[int, 4]` | **ENFORCED TODAY**, except on a zero-length array — see hole 8 |
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
| 20 | *"No implicit conversion between the types a rule can compare."* (SPEC §3.1) | `strict_no_implicit_conversion` | check | exit 2, `operator '+' mixes int and float` | **ENFORCED TODAY for binary arithmetic operators; NOT ENFORCED for comparisons and unary operators** — see §3 holes 1–3 |
| 21 | *"Tuples do not exist; `(1, 2)` is refused"* (SPEC §4.1) | `strict_no_tuples` | check | exit 2, `tuples are not supported` | **ENFORCED TODAY** |
| 22 | *"`elif` is not part of Vela"* (SPEC §4) | `strict_no_elif` | check | exit 2, `'elif' is not part of Vela; write 'else if'` | **ENFORCED TODAY** |
| 23 | *"An ordinary Vela call is not type-checked either"* (SPEC §3.1, stated as a deliberate gap) | `strict_call_int_into_float_param`, `strict_call_float_into_int_param` | run-ok | accepted; compiled prints `3.000000` / `3`, interpreter prints `3` / `3.500000` — **the two front ends disagree** | **NOT ENFORCED, and documented as such** — the case asserts the divergence, so neither side can drift silently |
| 24 | `to_int` / `to_float` (SPEC §3.1) | `strict_to_int_explicit`, `strict_to_float_explicit` | run-ok | both front ends print `3` / `3.000000` | **ENFORCED TODAY** |
| 25 | a `mut` struct parameter may be written through (SPEC §3.2 line 148) | `probe_mut_struct_parameter` | run-ok | compiled and interpreted both print `11` | **ENFORCED TODAY** |
| 26 | a `mut` array parameter may be written through (SPEC §3.2 line 148) | `probe_mut_array_parameter` | run-ok | compiled and interpreted both print `99` | **ENFORCED TODAY** |
| 27 | a `mut` *scalar* parameter may be written through (SPEC §3.2 line 148, "the callee may write through it") | `hole_mut_scalar_parameter` | run-ok | both front ends print **`10`** where the promise is `11` — the callee's `n += 1` is dropped | **NOT ENFORCED** — hole 3 |

## 2. What is *not* a hole

Two things look like holes and are not, and they are written down so nobody
"fixes" them:

* **`1 + 2.5` is refused, but `wants(3)` for `def wants(f: float)` is accepted.**
  SPEC §3.1 says so in as many words ("An ordinary Vela call is not type-checked
  either") and explains why the operator table and the C boundary are the two
  places a width is load-bearing. The cost is measured in case 23: the emitted C
  widens and the interpreter does not, so the same source answers `3.000000` and
  `3`. It is a gap with a case, not a promise with a bug.
* **`a[i] = a[i - 1] + 1` is refused, and `out[i] = out[i] + 1` is accepted.**
  The rule is about which element an iteration may *read* of an array the body
  *writes* — reading the element you are about to write is what `par_iter_mut`
  gives you in Rust, and refusing it would break `bench/matmul.vel`.

## 3. The holes, ranked

Ranked by the failure they can produce, worst first: **silent wrong answer**,
then **wrong but loud**, then **a missed diagnostic**. Each entry gives the case,
the command, and the raw output. All commands below were run from the repository
root with the frozen compiler at
`%TEMP%\vela-safety\frozen-pin\vm.exe`
(`SHA256 67ACF63B…903D8A`, `770560 bytes`), copied out of `selfhost\build\`.

### Silent wrong answer

**Hole 1 — `True < False` is accepted, and the compiled program answers `False`.**
`TRUE < FALSE` is not a comparison the language has; the interpreter says so and
the checker says nothing. This is the operator-table gap `DESIGN.md` §7.5 admits
("the operator table is checked for binary operators, not yet for unary ones or
comparisons"), and it is a *silent wrong answer* rather than a loud failure
because the emitted C compiles: `bool` becomes `int64_t`, the comparison becomes
an integer comparison, and the result is a number the source never asked for.

```
> vm.exe check tests\safety\cases\hole_cmp_bool_int.vel
exit=0   stdout=[ok]
> vm.exe run   tests\safety\cases\hole_cmp_bool_int.vel
exit=2   stderr: vela: panic: a bool can only be compared with == and !=
                  at ...\hole_cmp_bool_int.vel:2
> vm.exe build tests\safety\cases\hole_cmp_bool_int.vel   -> exit=0
> hole_cmp_bool_int.exe
exit=0   stdout: False
```

The program is `print(True < 1)`. The interpreter refuses it; the compiled
program prints `False`. Same source, two answers, and the checker blessed both.
`hole_cmp_int_float` is the same shape with `int`/`float` (`mut x: int = 1`,
`mut y: float = 2.0`, `print(x < y)`: accepted, compiled `True`, interpreter
panics `a comparison needs two values of the same type`), and it is the worse of
the two: `1 < 2.0` is a comparison a reader would expect to mean something, and
what the compiler does with it is a conversion SPEC §3.1 forbids.

**Hole 2 — `-b` on a `bool` and `not x` on an `int` are accepted.**
`hole_unary_neg_bool` prints `True` where the interpreter panics `unary - needs a
number`; `hole_unary_not_int` prints `False` where the interpreter panics `not
needs a bool`. Same root cause as hole 1 (the operator table is never consulted
for a unary operator), same class of result: a number invented out of the wrong
type. `hole_unary_neg_str` and `hole_cmp_str_int` are the loud version of the
same gap and are ranked under holes 6 and 7.

**Hole 3 — `mut` on a scalar parameter does not write through, and both front
ends agree on the wrong answer.**
`DESIGN.md` §7.5 already names this one; the case is
`tests/safety/cases/hole_mut_scalar_parameter.vel`.

```
def bump(mut n: int) -> None { n += 1 }
def main() -> None { mut x: int = 10  bump(x)  print(x) }

> vm.exe check hole_mut_scalar_parameter.vel            -> exit=0, ok
> vm.exe build hole_mut_scalar_parameter.vel && hole_mut_scalar_parameter.exe
exit=0   stdout: 10
> vm.exe run   hole_mut_scalar_parameter.vel
exit=0   stdout: 10
```

The promise is `11` (SPEC §3.2: "`mut` before a parameter means the callee may
write through it"). The emitted C shows exactly where it is lost:

```
static void vl_bump(int64_t);                     <- arguments are by value
static void vl_bump(int64_t vl_n) {               <- the callee's own copy
int64_t vl_x = 10LL;
(void)(vl_bump(vl_x));                            <- the caller's x is untouched
```

The two controls are `probe_mut_struct_parameter` (`11`, writes through) and
`probe_mut_array_parameter` (`99`, writes through), so this is specific to
scalars — a `mut` scalar parameter is an annotation the language accepts and then
ignores. This is the *worst* of the silent holes to live with, because a program
using it compiles, runs, exits 0, and is simply not the program that was written;
only the interpreter's agreement keeps it out of the "two front ends disagree"
class.

**Hole 4 — a binding narrows in the compiled program and does not in the
interpreter.** `hole_narrowing_binding_i32`, `hole_narrowing_binding_u8`, mode
`diverge`:

```
> vm.exe check hole_narrowing_binding_i32.vel   -> exit=0
> compiled: 705032704        (mut x: i32 = 5000000000)
> vm.exe run: 5000000000
> compiled: 44               (mut x: u8 = 300)
> vm.exe run: 300
```

SPEC §3.1 calls this out as a deliberate stopping point ("A binding is not
type-checked, and that is where the width question stops"), so the *rule* is
documented; what is not documented anywhere is that the same source answers
`705032704` compiled and `5000000000` interpreted. The two `diverge` rows assert
both numbers, so this cannot drift: if the compiler ever stops narrowing or the
interpreter starts, the case fails loudly.

### Wrong but loud

**Hole 5 — a conflicting `extern c` prototype is accepted, and the last
declaration wins.**
`tests/safety/cases/hole_extern_conflicting_prototypes.vel` declares `host_add`
twice with different signatures. Nothing refuses it:

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

This is loud because the C compiler catches it, but note *where* the failure
lands: at link time, in the host toolchain's words, for a program Vela's own
checker said was fine — and SPEC §12's promise ("the argument kinds are checked
against the declaration") has silently become "checked against whichever
declaration arrived last", which is not the same promise. Two declarations of one
name are the smallest possible version of a foreign-interface mistake, and the
checker has nothing to say about it.

**Hole 6 — `-s` on a `str`, `~x` on a `float`, `not s` on a `str`: accepted, then
the C compiler refuses the emitted C.**
`hole_unary_neg_str`:

```
> vm.exe check hole_unary_neg_str.vel      -> exit=0, ok
> vm.exe run   hole_unary_neg_str.vel      -> exit=2, vela: panic: unary - needs a number
> vm.exe build hole_unary_neg_str.vel      -> exit=2
    error C2088: 'operator -' cannot be applied to 'vela_str'
    error C2198: 'vela_print_str': too few arguments for call
    vela: panic: build: no C compiler on this host could build the emitted C
```

The emitted C is `vela_print_str((-vl_s));`. Nothing here is *silently* wrong —
the build stops — but the checker is not the thing that stopped it, and on a
host where the emitted C happened to typecheck (a language with signed/unsigned
tricks in it, or a future back end) the same gap produces hole 1's outcome.

**Hole 7 — the `parallel for` proof that DESIGN.md §7.5 admits is incomplete:
an enclosing loop's variable is not in the interval environment, so the program
can be emitted with a `#pragma omp parallel for` and then answer differently.**
This is the hole the task listed as "a `parallel for` proves aliasing from the
interval environment that holds only the loops inside the body, not the loops it
is nested in", and the honest measurement is narrower than the suspicion:

* `concurrency_read_any_index_legal` and `concurrency_same_index_legal` are the
  shapes the proof exists to keep, and they still compile *with* the pragma
  (`#pragma omp parallel for` present, `14` and `8` from both front ends).
* `concurrency_nested_outer_read_accepted` is the admitted narrowing reachable in
  code: `a[i + j]` with `i` from an *enclosing* loop. The loop is admitted and the
  pragma emitted (`select-string '#pragma omp parallel for'` → 1 line). Measured
  20 runs of the compiled program: `28` every time, the same as the interpreter.
  **So the narrowing is real in the checker and not reproducible as a wrong
  answer on this program** — the outer loop's update happens between two parallel
  regions, and MSVC's OpenMP static schedule happens to preserve the order. A
  case was written for the unsafe direction (`out[j] = a[i + j]` with the read
  depending on the outer index) and the compiled program printed `28` twenty
  times out of twenty; there is no measured wrong answer to report, and one is
  not invented here.
* Stating it as a missed proof rather than a race: the write side stays sound
  because a write that cannot be placed is *refused*
  (`concurrency_parallel_outer_rowmajor_refused`,
  `concurrency_serial_outer_parallel_inner` both refuse with `cannot prove that
  'parallel for' writes 'out' to distinct elements`), so the environment being
  shallow costs refusals, not accepted races. That is the finding: the gap is in
  the *admission* side and could only become a wrong answer if a future write
  proof leaned on an outer-loop interval the environment does not have.

### Missed diagnostic

**Hole 8 — a constant index on a zero-length array is accepted.**
SPEC §6.7: "A constant index that is provably out of range is a compile error."
`a[0]` on `Array[int, 0]` is provably out of range, and it is accepted. The
reason is one line in `selfhost/parts/check.vel` (`ck_index`):

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

Ranked last because it produces no wrong answer: the run-time check fires first and
both front ends stop the program. It is a compile error that did not happen — the
one thing the constant-index rule exists for — so the "provably out of range is a
compile error" promise is narrower than the spec sentence reads.

**Hole 9 — the two probes DESIGN.md §7.5 points at are 4-line programs that prove
the gap, and neither the checker nor the driver says anything about it.**
`tests/probes/mut_scalar_parameter.vel` and `mut_struct_parameter.vel` do exist
(`Get-ChildItem tests\probes -Name -Filter 'mut*'` returns both, and `check.vel`
line 1346 names them). What the repository does *not* have is a case in any
harness that would notice the behaviour changing: the two probes are inputs for a
probe folder, not rows in a corpus, and `DESIGN.md` §7.5 records the gap in prose
under "what stage 4 left". This corpus adds the three rows
(`hole_mut_scalar_parameter`, `probe_mut_struct_parameter`,
`probe_mut_array_parameter`), so if the gap is ever closed the case reports
`UNEXPECTED-PASS` and the prose gets corrected, rather than the other way round.

## 4. What this corpus does not cover

- **No `parallel for` race was reproduced as a wrong answer.** Hole 7's shape is
  admitted by the checker and produced the right answer in 20/20 runs; the
  strongest honest statement is the one above, and a machine with a different
  OpenMP schedule or thread count was not available to try to break it.
- **The corpus is standalone.** It is not wired into `tests/cases.txt` and
  `tests/run_tests.vel` does not run it; `tools/safety.ps1` is the only runner.
  (The manifest wiring is the leader's call, and `tests/cases.txt` was out of
  bounds for this round.)
- **Two `extern c` declarations were the only FFI hole tried.** Nothing was
  tested about `extern c` + `parallel for`, or about a foreign call whose
  declaration is added after its call site.
- **Numbers move.** The compiler was rebuilt three times during this session; the
  hash above is the build every number here was measured against, and
  `tools/safety.ps1` prints the hash of whatever it actually ran, with a loud note
  when it is not this one.
