# Deleting a check by proof — the design, and the reason it is the performance lever

**English** | [简体中文](ELISION_PLAN.zh-CN.md)

Status: **not started, and its first step is a prerequisite that does not exist
yet.** This is stream 1, item 3 of `ROADMAP.md` ("proof-driven check removal — *the
actual performance lever*"). It needs no LLVM. It does need **one new analysis**,
and that was established by an agent reading `parts/check.vel` instead of
implementing the plan this file first described — an earlier draft of this paragraph
claimed "the checker already proves these facts", which is **false**:

  * the interval machinery — `iv`, `lb`, the `Par` cursor — is declared as **locals
    of `ck_parallel`** and threaded through `ck_pcol`/`ck_pwrite`. No other function
    has an interval environment, so no other function can compute a range;
  * `ck_index` (`check.vel:897-936`) decides **constant indices only**: it wants a
    plain-name base and a known length, and every non-constant index returns
    undecided;
  * `ck_match_linear` (`1841-1862`) proves **injectivity, not range**, and never
    consults the array's length;
  * `ck_stmt`'s ordinary loops call `ck_block`, so inside a **normal** loop — which
    is exactly what `bench/matmul.vel`'s inner loop is — the checker computes no
    intervals at all.

`SPEC.md` §6.1's two rows for loop-variable tracking and condition narrowing are
honestly marked "designed, not built", and they are the missing prerequisite. So the
work below is ordered with the analysis first; the two bits it eventually writes are
the interface, and they are the part that never has to change.

## What is true today, measured

`bench/matmul.vel` — a naive 512×512 triple loop — compiles to C containing

    6 vela_bounds_check      10 vela_add_range      6 vela_mul_range      1 #pragma omp parallel for

and the innermost loop is where they are. `bench/RESULTS.md` records what that
costs: Vela's parallel build is **3.1× slower** than the C++ twin built with
`restrict` and OpenMP (0.0680 s against 0.0221 s — measured 2026-09-22; this
paragraph used to say "about 17% slower", which was a reading from before the
2026-09-19 emitter fix and is wrong by two orders of the gap's size), and Vela's
*serial* build is 2.4× slower than its serial C++ twin (0.5433 s against 0.2241 s),
and its own explanation of the *serial* win ("the
`restrict`-by-language-rule and the proved-away bounds checks") was measured false
and corrected — no `restrict` is ever emitted, and **no check is ever proved
away**.

So the language pays for checks it has already shown to be unnecessary, and the
claim at the top of the README ("faster than C++") is exactly on the other side
of fixing that.

Meanwhile the honest sentences are in place: `SPEC.md` §6.1 and `DESIGN.md` §3 say
that deleting a check by proof **is not implemented yet**. This work makes those
sentences describe a feature instead of a gap.

## Where the decision is recorded: two spare bits in the node's `flags` word

The first question this work has to answer is where a per-site decision can live,
and it is answered by `selfhost/AST.md` §1.1 rather than by preference. Read:

- a node is **ten words**, `nd[i*10 + k]`, and *all ten are spoken for* — 0 `kind`,
  1–4 payload, 5 `line`, 6 `flags`, 7 `nx`, 8 `e`, 9 `f`;
- therefore neither "store it in a spare node word" nor "pass a parallel array
  through every function that takes `nd`" is acceptable. The parallel array is the
  worse of the two: it changes the signature of every function in `parser.vel`,
  `resolve.vel`, `check.vel`, `eval.vel` and `emit.vel`, for a feature that only
  two of those files use;
- word 6, `flags`, currently uses **bit 1** (`mut`), **bit 2** (`declaration`),
  **bit 4** (`pure`), and bit 1 of a `for` for `parallel`. Bits 3, 5, 6, 7 and up
  are free, the word is a signed 64-bit int, and the parser already initialises it
  at allocation.

**So the decision is two bits of word 6**, and nothing else moves:

| bit | name | meaning | written by | read by |
|---|---|---|---|---|
| 8 | `NF_NO_BOUNDS` | **of the index node's word 6**: this index site's range is proved, so no `vela_bounds_check` | the interval walk, once it runs outside `parallel for` | `emit.vel`, at the indexing site |
| 16 | `NF_NO_OVERFLOW` | **of the operator node's word 6**: this arithmetic site's result interval is proved inside `int64_t`, so no `vela_add_range`/`sub`/`mul` | the same walk | `emit.vel`, at the operator |

Both bits are **per node kind**, because word 6 means different things on different
kinds — and **bit 8 is already taken on `def` nodes**: `parser.vel` writes
`xflag = 8` for an `extern c` declaration with no body (`12` = 8|4 for `extern c
pure`), which is how the resolver and the emitter recognise one. Bits 8 and 16 are
free on the nodes this feature marks (kind 31 index, kind 27 operator), which is why
they were chosen; the plan has to say *whose* word 6 it means, or the next reader
will take them for global bits.

Two bits rather than one because the proofs are different facts: an index can be
proved in range while the index *expression* still needs its own overflow check, and
collapsing them would elide a check the analysis never justified.

`AST.md` §1.1 and §9 must be updated in the same change — its own header says §9 is
the list of what the AST does not record, and it is the document a reader trusts
about the word layout. A bit nobody documented is a bit the next reader will reuse
for something else.

Per **site**, not per flag. `SPEC.md` is explicit that a check is removed only by
proof and never by a flag or an annotation, and it is right: a compilation flag
that turns checks off is the `--fast-int` lie this project already deleted once.
These bits are set one site at a time, by the analysis, and cleared nowhere else.

The shape of the change is still the smallest one available: the checker sets the
bits, the emitter reads them, and nothing else moves.  Item 1 of the order below
sets the bits and has the emitter **ignore** them, which is a change with no
behaviour at all.

## Soundness rules

1. **Elide only where the existing interval analysis proves the fact at that
   point**, including its invalidation rule: anything assigned anywhere inside a
   loop has its facts discarded at the loop's entry, so the proof cannot be stale
   across a back edge. A proof that is merely *true on this path* is not enough.
2. **One site at a time.** `a[i]` inside a loop whose iteration range proves
   `0 <= i < len(a)` loses its bounds check; the same `a[i]` in a loop whose bound
   is not proven keeps it. There is no "this function is checked/unchecked"
   switch anywhere.
3. **`parallel for` is not a special case in the range proof.** Nothing about
   OpenMP changes what a range proof means: an index proved inside a parallel body
   is proved exactly as it would be in an ordinary loop. What *is* special today is
   only the analysis's **scope**, and it matters enough to state: the interval
   environment is owned by `ck_parallel`, so it holds the loops *inside* a parallel
   body and not the ones that body is nested in (`SPEC.md` §6.1, rows "interval
   analysis on every integer expression" and "loop-variable tracking"; the same
   narrowness is named in `CHECKER_PLAN.md` and `DESIGN.md` §7.5). This paragraph
   used to say `parallel for` "is treated exactly like any other loop", which
   `SPEC.md` §6.1 contradicts. The **aliasing** rule is a separate question — it
   prevents a *race*, and it is written.
4. **Nothing is elided for a constant index that is out of range** — that is a
   refusal, not an elision, and it already works.
5. **What must never happen**: a wrong answer. An unsound elision is the worst bug
   this project can ship, because it is silent, and because every other guarantee
   is built on the check being there. The plan below is therefore ordered so that
   the elision is the *last* thing switched on, and so that each step is
   separately falsifiable.

## Order of work

Seven things have to happen, and the order among them is the argument of this
section.  The list below is numbered **1–5**: items 1, 2 and 3 are one piece of work
each, item 4 is done twice (once with the emitter **ignoring** the bits, once with it
obeying them), and item 5 is both a rewrite of the rewrite and the second lever.
The list previously read `1,2,3,4,4,5`, which is not a numbering.

1. **Extend the interval walk to ordinary loops** — loop-variable tracking and
   condition narrowing, in the same shape the parallel proof already uses, driven
   from the ordinary statement walk instead of from `ck_parallel`. This is the
   prerequisite, and it is where "faster than C++" actually lives: matmul's checks
   are all inside ordinary loops, so nothing later in this file can move the number
   without it. Acceptance: a probe that reports, per site, the interval the walk
   computed (item 3 below lists the programs), and **no change to the emitted C** —
   nothing consumes an interval yet.
2. **The checker writes the two bits; the emitter still ignores them.** No
   behaviour change at all, and the whole corpus must stay green, which proves the
   writing is harmless before anything depends on it. Only for sites item 1's walk
   actually proves: a bit set from a proof that does not exist is the unsoundness
   this whole file is arranged to avoid. `AST.md` §1.1 records both bits in the same
   change.
3. **Prove the proof.** A probe that prints, per site, "proved" or "not proved" for
   a set of adversarial programs, checked by hand:
   - `for i in range(0, len(a)) { a[i] = 0 }` → proved;
   - `while i < 10 { a[i] = 0; i += 1 }` with `a: Array[int, 10]` → proved;
   - `while i < 100 { a[i] = 0; i += 1 }` with `a: Array[int, 10]` → **not proved**
     (and at run time this must still panic);
   - `a[i + k]` with an unproven `k` → not proved;
   - `a[i - 1]` inside `for i in range(1, n)` → proved only if `n <= len(a)`;
   - anything assigned inside the loop → not proved.
4. **The emitter reads the bits.** The inner loop of `bench/matmul.vel` must
   compile to **zero** `vela_bounds_check` and **zero** `vela_add_range` calls
   while the program prints exactly the same answer, and the whole corpus stays
   green — this is where the differential evidence matters, because the corpus's
   `run` cases are compiled *and* interpreted and the interpreter still checks
   everything. **The interpreter is the reference implementation for this
   feature**: with elision on, the compiled and interpreted paths still have to
   agree byte for byte, and if they ever disagree, the elision is wrong.
5. **Measure, and report whatever it says.** `powershell -ExecutionPolicy Bypass
   -File tools\bench.ps1 -Reps 7`, with the thread sweep, and `bench/RESULTS.md`
   re-frozen with the new numbers and the date and compiler they were taken with.
   If the parallel gap does not close, the table says so and the claim stays
   unproven.

## The second lever, and why it is second

Measured 2026-09-19, on the same emitted C, all variants printing 1090512707:

| variant | seconds |
|---|---|
| as the emitter writes it | 1.058 |
| index hoisted into a temporary, arithmetic still checked | 0.550 |
| index unchecked, bounds check kept | 0.177 |
| nothing checked | 0.181 |
| the C++ twin | 0.213 |

Two things follow.  First, the emitter currently writes every index expression
**twice**, and fixing that (a separate piece of work, in the emitter) is what takes
1.058 s to 0.550 s.  Second, *after* that fix, the checked index arithmetic is
0.550 → 0.177, i.e. **~68% of what remains** — this plan's whole reason to exist,
and the difference between losing to the C++ twin and beating it.  The bounds check
is the smaller half — one row of `bench/RESULTS.md`'s ladder, worth `0.543 − 0.514 =
0.029 s`; the `~0.04 s` this sentence used to quote does not reproduce from that
ladder (see that file's arithmetic note) — so `NF_NO_OVERFLOW` on index arithmetic
is the half that pays, and `NF_NO_BOUNDS` is the smaller half.
(An earlier version of this item claimed the checks cost nothing and that this plan
was therefore worth less than it looked.  That claim came from a hand-edited variant
labelled "checks kept" that had in fact hoisted *unchecked* arithmetic, so the label
did not describe the measurement.  The table above replaced it.)

## Acceptance tests, written as cases

| case | what it proves |
|---|---|
| `bounds_not_elided` (refuse-free, native) | `while i < 100 { a[i] = 0; i += 1 }` over `Array[int, 10]` still **panics at run time**, compiled and interpreted — the proof was not there and the check remained |
| `bounds_elided_in_range` | `for i in range(0, len(a)) { a[i] = 0 }` compiles with **no** `vela_bounds_check` (checked by `dumps` on the emitted C) and prints the same answer |
| `overflow_not_elided` | a sum whose range is unproven still panics on overflow, compiled, exactly as the interpreter does |
| `elision_matches_interpreter` | every `run` case in the corpus, compiled with elision on, equals its interpreted output byte for byte |
| `matmul_inner_loop_is_clean` | `emit-c bench/matmul.vel` contains no `vela_bounds_check`/`vela_add_range` inside the innermost loop |

## What item 1 has to build, in the shape the code already uses

The prerequisite is **not a new algorithm** — the algorithm exists. It is a **change
of scope**: the interval walk has to run for ordinary loops, and the parallel proof
has to become one of its *callers* rather than the owner of its state.

From `parts/check.vel` as read:

| piece | where it lives today | what item 1 does with it |
|---|---|---|
| `iv`, `lb`, the `Par` cursor | **locals of `ck_parallel`** (~2400-2447) | hoist into a context the ordinary statement walk owns |
| `ck_iv_expr`, `ck_iv_push`, `ck_lb_push`, `ck_range_iv` | called from `ck_pcol`/`ck_pwrite` only | call them from `ck_block`'s loop handling, and from `ck_parallel` as before |
| `ck_clamp` | keeps a known end a **true** bound (±2^62) | unchanged — it is why an `Iv` with both ends known is a *proof* and not a guess |
| `ck_match_linear`, `ck_rowmajor`, `ck_linear` | the injectivity proof, a different question | untouched; they keep consuming intervals for their own purpose |

Three rules the extension must keep, because the parallel proof already keeps them
and they are the reason it is sound:

1. **A fact set inside a loop is discarded at the loop's entry** — the existing
   invalidation rule. Without it a proof is stale across a back edge, which is the
   classic way a "proved" bounds check becomes a wrong answer.
2. **Narrowing is per branch, not per function.** `if i < len(a) { a[i] }` proves the
   index in that branch only, and the walk must merge at the join toward the
   **widest** interval, never the narrowest.
3. **Nothing consumes an interval in item 1.** The emitted C must not change by a
   byte, so the 190-case corpus and the fixpoint *are* the acceptance test: a change
   to either is a bug in this step, not a feature of it.

Acceptance for item 1 is a probe that prints, per site, the interval the walk
computed, run over the adversarial programs in item 3. That probe needs somewhere
to print from, and the shape matters: a dump that fires during `check` would put
noise on every user's stderr, and a flag that enables it would be the kind of switch
`SPEC.md` refuses. So it is a **separate driver mode** — `vm.exe iv <file>`, a tenth
mode beside the nine that exist — documented as a developer tool. The probe is what
makes "proved" falsifiable; a claim of proof with nothing printing the interval is
the claim this project keeps having to retract.

## What this does not claim

It does not remove the *checks* from the language — it removes the ones the
compiler has proved unnecessary, which is what the language always said it would
do. It does not add a flag, and it must not: a user-visible switch that relaxes
safety is the thing that turns "Rust's safety design" into a slogan. And it does
not touch the interpreter, which keeps every check forever — that is what makes it
a reference to test the compiler against.
