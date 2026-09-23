# Vela — the north star, and the road to it

**English** | [简体中文](ROADMAP.zh-CN.md)

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

**The rows below were last corrected 2026-09-22.** Three of them disagreed with
`bench/RESULTS.md` before that: the parallel row still said "−17% behind" where
the file measured 3.1× slower, Stream 4.1 still said this repository had no
version control, and the two LLVM phases were written as outstanding work that
had in fact landed. Every number in the table is the one `bench/RESULTS.md`
carries, or it says which commit it came from instead.

| claim | today | the gap |
|---|---|---|
| **faster than C++** | **not established on either matmul row, and the loss is measured:** serial matmul 512² **0.5433 s against the C++ twin's 0.2241 s (2.4× slower)**, parallel matmul **0.0680 s against 0.0221 s (3.1× slower)**, mandelbrot a tie to the microsecond, sieve 1.5× slower against an explicitly *unchecked* C++ row — `bench/RESULTS.md` | the loss is the checked arithmetic still *in the inner loop*: `vm.exe emit-c bench/matmul.vel` emits 6 `vela_bounds_check`, 7 `vela_add_range` and 3 `vela_mul_range` calls (measured 2026-09-22 on the working tree's compiler, which is not the pinned revision of `bench/RESULTS.md`; the earlier counts quoted here were 6/10/6). **No check is proved away anywhere** — what the interval machinery proves today is confined to `parallel for`, and the two ordinary-loop rows of `SPEC.md` §6.1 are honestly marked "designed, not built" |
| **Rust's safety design** | no pointers, no `unsafe`, no `free`, arena-only, indexing checked, integer arithmetic checked, and the `parallel for` aliasing rule is **complete**: the cross-iteration read is refused, `out[i] = a[i] * 2` and `a[i] = a[i] + 1` stay legal. All five cases that prove it are in `tests/cases.txt` with their recorded outputs — `parallel_alias_cross_iteration` is a `refuse` case whose `.err` holds the message; the four legal shapes are `run` cases whose `.out` files hold their answers — so the rule is written *and* there are five recorded verdicts behind it | two different things are still owed, and this cell used to name only one of them: the **proof machinery** that removes a check is not built (the checks are not yet *proven away* where a proof exists), and there is no concurrency beyond `parallel for`. Reading "finish the aliasing rule" as if the rule were unwritten is wrong — it is the check-removal proof that is unfinished |
| **Python's syntax, strict semantics** | brace-based, statically typed, no truthiness, `/` refuses integers, string `+` refused, explicit `mut`, scope rules | the type system still has holes: a `str` bound to `int` is accepted by `check`; an undeclared type name is caught by an emitter panic instead of a diagnostic |
| **pure-bred / independent** | the compiler's source is Vela; the linker is Vela; there is no Python and no C++ anywhere; `selfhost_fixpoint` passes | **met since 2026-09-24: `build` no longer needs `cl.exe`.** `vm.exe build x.vel` builds the module in process through `libLLVM` — linked into the compiler, the way `rustc` carries LLVM — and calls one external program, `lld-link`. `tools\llvm-no-cl.ps1` is the gate: it disarms the C compiler with `CL=/Zs` (which `cl.exe` reads itself, so no `cl.bat` on `PATH` can be walked around by `vcvars64.bat`), requires `build` to work anyway, requires `build-c` to fail under it, and requires `build-c` to work without it. The C back end stays as the reference implementation and is reached by name, `build-c`; `tools\build.ps1` step 4 promotes the compiler the LLVM path built, so the seed C is stage 0 and everything a person builds with `build` is pure.  What is still owed under this row is the *later* phase of the dependency, not the C compiler: `lld-link` is still an external program, and the phase table's `later` row (our own COFF/PE writer) is what would remove it |

## Stream 1 — the backend, and "faster than C++"

Ordered, each step verifiable on its own.

1. **LLVM phase 1 — landed, measured 2026-09-22.** Removes MSVC.
   `vm.exe emit-llvm f.vel` writes IR text (`examples/hello.vel` emits a module
   with its target datalayout and triple, exit 0), and `clang-cl`/`llc` assembles
   and links it; the M1 probe shows the interpreted, C-built and LLVM-linked
   program printing the same bytes (`tools\llvm-m1.ps1`, `RESULT: ok`). The
   acceptance below is met for the programs this backend compiles — 7 of the 23
   `run` cases, the other 16 refused by name rather than miscompiled: `hello.vel`
   built by both backends prints the same bytes; a corpus `run` case built by LLVM
   equals the C-built one and the interpreter. Acceptance: what is still owed is
   the rest of the corpus, not the mechanism.
2. **LLVM phase 2 — landed, measured 2026-09-22.** `runtime/vela_llvm_shim.c`
   (scalar interface over `llvm-c`) is linked into `vm.exe`, which writes the
   `.obj` itself: `vm.exe build examples\hello.vel` prints `built hello.exe
   (LLVM in-process; lld-link, no C compiler)` and exits 0, and the executable
   runs, so the host dependency is a linker and nothing else. Acceptance:
   no external compiler; only a linker. Met — and it has since been *promoted*:
   this command was spelled `build-llvm` when the row was written, `build` is the
   same path now, `build-llvm` is a synonym kept for the gates and the documents,
   and the C back end is reached by name as `build-c`.
3. **Proof-driven check removal** — *the actual performance lever*, and it does
   not need LLVM at all. The checker's interval analysis already exists; what is
   missing is the emitter consuming it. Acceptance, measurable today:
   `vm.exe emit-c bench/matmul.vel` contains **zero** `vela_bounds_check` and
   `vela_add_range` calls inside the inner loop, *the program prints the same
   answer*, and the parallel comparison against C++'s `restrict+omp` build moves
   from **3.1× slower** (measured 2026-09-22: 0.0680 s against 0.0221 s,
   `bench/RESULTS.md`) to a win or a tie. If elision does not move the number, say
   so. **The bar this bullet used to quote — "from −17%" — is no longer the
   measurement**, and the two figures disagree because they were taken on
   different sides of the 2026-09-19 emitter fix.
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

1. **Enums with payloads + `match`.** *Landed 2026-09-24*: the interpreter, the checker
   and the C back end have it, the LLVM back end refuses it by name (five rows of
   `tests\llvm-refusals.txt`), and `enum`/`match` are reserved words (`SPEC.md` 1.3).
   **The acceptance criterion this item set for itself is not met**: the AST node-kind
   dispatch in `parts/*.vel` was *not* rewritten as enums, so the compiler still dispatches
   on integers (`k == 31` appears throughout `check.vel`).  The feature exists and the
   compiler does not use it yet, and that self-use is what would have paid the ergonomics
   dividend this item promised.  It had to come after a pool raise (nodes 65,536 →
   131,072, tokens 131,072 → 262,144) that the linked compiler had outgrown — the raise is
   why a source using the feature could be parsed at all.
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
2. **Finish the `parallel for` aliasing rule's evidence** and record its five
   verdicts as cases: the cross-iteration read is refused, `out[i] = a[i] * 2`
   stays legal and still emits `#pragma omp`, `a[i] = a[i] + 1` stays legal.
   (The rule itself is already written — see the claim table above and `SPEC.md`
   §7. The old wording, "finish the aliasing rule", read as though the rule were
   incomplete, which is not what `SPEC.md` says.)
3. **A safety corpus**: adversarial cases (races, aliasing, overflow, bounds,
   lifetime) whose goldens record the *diagnostic*, so a regression is a refusal
   that stopped happening.
4. **Three-way differential testing** (C backend, LLVM backend, interpreter) over
   every `run` case, byte for byte, as a permanent suite member.

## Stream 4 — the tooling around it, which is what makes it usable

1. **Version control — done, and this bullet is the record of why it came first.**
   When it was written the repository had none, and it had already lost a test
   corpus to a single `del`, recovered only because session transcripts happened
   to hold a copy of the old test file. `git init` and a first commit cost nothing
   and removed the single largest risk to everything above. It is now `main` at 41
   commits with `origin` on GitHub (`git rev-list --count HEAD`, measured
   2026-09-22), so the risk this bullet named is closed; leaving "This repository
   has none" in the list would have been a plan that had been carried out while
   still reading as unstarted.
2. **The IDEA plugin** (independent stream, already in progress): syntax,
   diagnostics, completion, hover, parameter info, goto, rename, structure, the
   AST window, run configuration, formatting, folding, comments, templates, and a
   colour settings page — with every registration verified against the platform's
   own declaration, because three features in this plugin have already shipped
   dead for want of one line.  Its Run button **interprets** (`vm.exe run <file>`):
   no build step, no executable written beside the program, no C toolchain, and the
   command line, the program's output and the exit status all go to the console.
   That is the same requirement as item 9 of stream 2, seen from the editor.
3. **A debugger**: the protocol debugger exists and works — `vela debug <file.vel>
   <cmddir> [arg...]`, events on stdout and commands in `<cmddir>/cmd.NNN`
   (`DESIGN.md` §10, measured 2026-09-22), and the earlier "`vm.exe debug` is
   advertised and does nothing" was an artefact of running it without its command
   directory (`STATUS.md` §5). What is still owed is the IDE side: until the plugin
   can drive that protocol, Debug stays honestly refused in the editor rather than
   faked.
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
time gave the five-row split above.  Four consequences, ordered by how much they
change what happens next — the count here used to read "three" over a list of
four, which is the kind of arithmetic a reader cannot check without counting:

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
3. **The bounds check itself is cheap** (removing it is one row of the ladder in
   `bench/RESULTS.md`, worth `0.543 − 0.514 = **0.029 s**`; the `~0.04 s` this
   bullet used to quote does not reproduce from that ladder, and it has been
   dropped rather than re-guessed), so the `NF_NO_BOUNDS` half of the
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
| pure-bred | `vm.exe build selfhost\vm.vel` with no `cl.exe` anywhere on `PATH`, plus `tools\llvm-no-cl.ps1` for the same claim with the compiler disarmed rather than absent |
