# Vela — the north star, and the road to it

The goal, as the language's owner states it:

> Vela is an independent, systems-level, high-level programming language. It is
> pure-bred: it does not borrow its toolchain from another language. It should be
> **faster than C++**, it should have **Rust's safety design**, and it should have
> **Python's syntax with strict semantics** — with the **complete feature set of a
> high-level language**.

This file turns that into work with acceptance criteria, because every clause in
it is either measurable or it is a slogan. Two constraints shape the whole order:

1. **The compiler is written in Vela, and must keep compiling itself.** Every
   feature here has to be implemented *in the language*, by a compiler that does
   not itself have that feature yet. That makes the order load-bearing: a feature
   that simplifies the compiler should come before one that the compiler would
   have to implement blind.
2. **A claim that is not measured does not exist.** The project already paid for
   this lesson repeatedly — a `--fast-int` flag documented but absent, bounds and
   overflow checks documented as proof-elided while every check was still
   emitted, an `restrict` clause that was never emitted at all, two registrations
   that silently did nothing. Each item below names the command that decides it.

## Where the four claims stand, measured

| claim | today | the gap |
|---|---|---|
| **faster than C++** | serial matmul wins (~0.2055 s vs 0.2270 s); parallel matmul **loses ~17%**; mandelbrot and sieve tie | the loss is the checked arithmetic still *in the inner loop*: `bench/matmul.vel` compiles to 6 `vela_bounds_check`, 10 `vela_add_range`, 6 `vela_mul_range` calls. Nothing is proven away yet — the proofs exist in the checker's design, not in the emitter |
| **Rust's safety design** | no pointers, no `unsafe`, no `free`, arena-only, indexing checked, integer arithmetic checked, and the `parallel for` aliasing rule is written: the cross-iteration read is refused, `out[i] = a[i] * 2` and `a[i] = a[i] + 1` stay legal. **The five cases that would prove it are in `tests/cases.txt` and their goldens are not recorded yet** (`tests/cases.txt` says so at the block itself), so the rule is *written*, not yet *proved by a run* | the checks are not yet *proven away* where a proof exists, and there is no concurrency beyond `parallel for` |
| **Python's syntax, strict semantics** | brace-based, statically typed, no truthiness, `/` refuses integers, string `+` refused, explicit `mut`, scope rules | the type system still has holes: a `str` bound to `int` is accepted by `check`; an undeclared type name is caught by an emitter panic instead of a diagnostic |
| **pure-bred / independent** | the compiler's source is Vela; the linker is Vela; there is no Python and no C++ anywhere; `selfhost_fixpoint` passes | **`build` still needs `cl.exe`**: C is the code-generation backend. That is the remaining host-language dependence, and the LLVM workstream is what removes it |

## Stream 1 — the backend, and "faster than C++"

Ordered, each step verifiable on its own.

1. **LLVM phase 1**: `vm.exe emit-llvm f.vel` writes IR text; `clang-cl`/`llc`
   assembles and links it. Removes MSVC. Acceptance: `hello.vel` built by both
   backends prints the same bytes; a corpus `run` case built by LLVM equals the
   C-built one and the interpreter.
2. **LLVM phase 2**: `runtime/vela_llvm_shim.c` (scalar interface over `llvm-c`)
   linked into `vm.exe`, which writes `.obj` itself. Acceptance: no external
   compiler; only a linker.
3. **Proof-driven check removal** — *the actual performance lever*, and it does
   not need LLVM at all. The checker's interval analysis already exists; what is
   missing is the emitter consuming it. Acceptance, measurable today:
   `vm.exe emit-c bench/matmul.vel` contains **zero** `vela_bounds_check` and
   `vela_add_range` calls inside the inner loop, *the program prints the same
   answer*, and the parallel comparison against C++'s `restrict+omp` build moves
   from −17% to a win or a tie. If elision does not move the number, say so.
4. **`parallel for` on the LLVM backend**, via the OpenMP runtime ABI or our own
   thread pool. Until then the LLVM backend refuses it with a message, because a
   loop that says it is parallel and runs serially is the one thing this project
   must never ship.
5. **The fixpoint on the new backend**: the compiler, built through LLVM, emits
   byte-identical C to the compiler built through MSVC. That is the milestone that
   makes "pure-bred with a real backend" a fact.

## Stream 2 — the language's feature set

The order is chosen so each feature makes the *next* one cheaper for a compiler
written in Vela.

1. **Enums with payloads + `match`.** First because the compiler itself needs it:
   its node kinds are integers (`k == 31` appears throughout `check.vel`), and an
   exhaustive `match` over a real enum removes a whole class of silent bug.
   Acceptance: the AST node-kind dispatch in `parts/*.vel` is rewritten as enums
   and the fixpoint still passes.
2. **Modules and imports.** Today a multi-file program is a build-time
   concatenation (`tools/link_selfhost.vel`). Real modules unlock libraries,
   incremental builds and cross-file IDE navigation, and they let the compiler's
   own parts stop being spliced text. Acceptance: a two-file program compiles;
   the self-hosting build no longer needs the linker; the IDE resolves a name
   defined in another file.
3. **Error handling without exceptions**: `Result[T, E]` and `?`, so a fallible
   function has a *type* that says so. Needs 1. Acceptance: a corpus case where
   an error propagates and is handled, with no panic and no exit status.
4. **Generics (monomorphised)**, which the standard library needs before it can
   exist: `Vec[T]`, `Map[K, V]`. Acceptance: the containers work, and the
   compiler's own source uses at least one of them.
5. **Closures / first-class functions**, with an explicit, documented capture
   rule — the arena means an escaping closure is a dangling frame, so a closure
   that outlives its frame must be refused **by the type system**, not by a
   crash. Acceptance: a function taking a function parameter works; an escaping
   closure is a compile error naming the reason.
6. **A standard library**: `Vec`, `Map`, `str` methods, formatting, and the file
   and process calls the compiler already needs, all written in Vela. The
   compiler is the first customer, as it has been for everything else.
7. **Iteration and slicing**: `for x in xs`, ranges as values, slices.
8. **Traits/interfaces**, if and when generics need constraints to be usable.
9. **Direct execution, and the interactive form of it.** The owner has stated this
   twice, and it is worth writing as a requirement rather than a preference: a Vela
   program must be *runnable* without producing any other language's file and
   without first compiling an executable.  `vm.exe run file.vel` already satisfies
   exactly that — it is the interpreter: no C, no `.ll`, no `.exe`, nothing written
   beside the program — and every `run` case in the corpus is executed *both* ways
   for that reason.  What does **not** exist is the interactive form: a REPL, where
   a line is typed and evaluated.  It needs the one host capability this language
   deliberately lacks — reading standard input (`SPEC.md` §8 lists the host surface
   and there is no `read_line`; the debugger had to use command files for the same
   reason), which makes it a *language* change rather than a driver one, so it
   belongs here and not in the CLI section.  Acceptance: `vm.exe repl` evaluates
   typed input at a prompt; `run` still needs nothing but `vm.exe`; and the
   README's sentences about running "directly" name the interpreter explicitly
   instead of leaving a reader to assume the compiled path.

Deliberately **not** on this list, to keep the language pure-bred and small: a GC
(the arena plus value semantics is the design), `null` (it does not exist and
should not), macros as text substitution, and a C++ dependency of any kind.

## Stream 3 — safety, completed and *proved*

1. **Close the checker's holes** so `check` is as strong as `build`: binding
   type comparison, call-argument comparison, an undeclared type name refused by
   the checker. Acceptance: `check` and `build` give the same verdict and the
   same message on all four measured holes.
2. **Finish the `parallel for` aliasing rule** and prove its five verdicts: the
   cross-iteration read is refused, `out[i] = a[i] * 2` stays legal and still
   emits `#pragma omp`, `a[i] = a[i] + 1` stays legal.
3. **A safety corpus**: adversarial cases (races, aliasing, overflow, bounds,
   lifetime) whose goldens record the *diagnostic*, so a regression is a refusal
   that stopped happening.
4. **Three-way differential testing** (C backend, LLVM backend, interpreter) over
   every `run` case, byte for byte, as a permanent suite member.

## Stream 4 — the tooling around it, which is what makes it usable

1. **Version control.** This repository has none, and it has already lost a test
   corpus to a single `del`, recovered only because session transcripts happened
   to hold a copy of the old test file. `git init` and a first commit cost
   nothing and remove the single largest risk to everything above. **This is the
   first thing to do when a shell works again.**
2. **The IDEA plugin** (independent stream, already in progress): syntax,
   diagnostics, completion, hover, parameter info, goto, rename, structure, the
   AST window, run configuration, formatting, folding, comments, templates, and a
   colour settings page — with every registration verified against the platform's
   own declaration, because three features in this plugin have already shipped
   dead for want of one line.  Its Run button **interprets** (`vm.exe run <file>`):
   no build step, no executable written beside the program, no C toolchain, and the
   command line, the program's output and the exit status all go to the console.
   That is the same requirement as item 9 of stream 2, seen from the editor.
3. **A debugger**: the compiler's `debug` mode exists in the sources; until it is
   promoted and has a protocol the IDE can drive, Debug stays honestly refused
   rather than faked.
4. **One command that runs everything**: build, suite, benchmarks, differential
   tests, and a report — so "is it green" is never a matter of opinion.

## The emitter's index duplication — the measured cause of a lost claim

Measured 2026-09-19, with the runtime's bounds and overflow checks finally working
and the benchmark harness finally runnable (`tools\bench.ps1`):

| variant, all of them printing the same answer | seconds (best / median) |
|---|---|
| serial matmul 512², Vela as the emitter writes it | 1.058 / 1.066 |
| the same emitted C, index hoisted into one temporary | 0.550 |
| the same, with the bounds check also removed | 0.514 |
| index unchecked, bounds check kept | 0.177 |
| nothing checked | 0.181 |
| the C++ twin | 0.212 / 0.213 |

The emitter writes every array index expression twice — once inside
`vela_bounds_check(...)` and once inside the subscript — and the index expression
is itself `vela_mul_range` + `vela_add_range`, so the checked arithmetic runs twice
per element access and MSVC /O2 does not merge the two copies.  That is worth
0.51 s of the 1.06 s.

**The table is a correction, and worth reading as one.**  The first version of this
section put the hand-edited variant at 0.176 s and concluded that the checks cost
nothing — but that variant had hoisted *unchecked* index arithmetic while keeping
only the bounds check, so its label ("index hoisted, checks kept") did not describe
what it measured, and the conclusion was false.  Isolating the same cost a second
time gave the five-row split above.  Three consequences, in order of importance:

1. **The fix is the emitter computing each index once** (a temporary, the way the
   hand-edited C above does).  Acceptance: the benchmark's Vela serial matmul at
   **0.6 s or better** — expected to land at ~0.55 s, so the bar is a floor and not
   a goal — with its answer unchanged, `tools\refreeze.ps1` still `RESULT: green` at
   190/190, the fixpoint still byte-identical, and an out-of-bounds probe still
   panicking with the same message and line.
2. **After that fix, the checked index arithmetic is the largest remaining cost**:
   0.550 s down to 0.177 s if the `vela_mul_range`/`vela_add_range` calls on the
   index go away, which is ~68% of what is left and the difference between losing to
   the C++ twin and beating it.  That is what `selfhost/ELISION_PLAN.md` is for, and
   this table is its justification — the plan is worth doing *after* step 1, not
   instead of it.
3. **The bounds check itself is cheap** (~0.04 s), so the `NF_NO_BOUNDS` half of the
   elision plan is the smaller half; `NF_NO_OVERFLOW` is where the time is.
4. **A claim is only as good as the last measurement.**  This row of the README said
   "wins on serial matmul" for a day while the emitted code was, in fact, six times
   slower than the hand-written variant.  The harness is now one command, so the
   number is cheap to re-take; take it before quoting it.

## What each claim will be judged by

| claim | the command that decides it |
|---|---|
| faster than C++ | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` — the table in `bench\RESULTS.md`, with the answer each variant must print.  **Currently false on matmul, with a measured cause**: the C emitter writes every array index expression twice (§ "The emitter's index duplication" below).  The claim is decided by the numbers after that is fixed, not before |
| Rust's safety design | the safety corpus: every case's recorded diagnostic, plus the three-way differential |
| Python's syntax, strict semantics | `vm.exe check` on the four measured holes, plus the corpus's 55 refusal cases |
| complete high-level features | streams 2.1–2.8, each with the acceptance line above |
| pure-bred | `vm.exe build selfhost\vm.vel` with no `cl.exe` anywhere on `PATH` |
