# STATUS — what is verified, what is written, and what is in the way

**English** | [简体中文](STATUS.zh-CN.md)

A snapshot of a working session, not a substitute for running the command it
names.  Every "verified" line below was measured on this machine; every "written"
line is source that has not been through the tool it needs, and says so.

## 0.1 Current state, 2026-09-24 — read this first; everything below is the 2026-09-20 boundary

The dated block in §0 and the tables in §2 are the **2026-09-20** session
snapshot.  They are kept because that is the record of that boundary, and one line
in them is now actively misleading: §0 says the gate is *red* at `187 passed, 3
failed of 190`.  It is green.  Today's state, measured by running each command
rather than by reading this file:

| what | command | today |
|---|---|---|
| the suite | `tools\refreeze.ps1` | `RESULT: green` — **196 passed, 0 failed (of 196 cases, 251 captures)**.  Six `refuse` cases with hand-written goldens were added after this file was written; that is where 190 → 196 comes from |
| the three-way differential | `powershell -ExecutionPolicy Bypass -File _llvmdiff.ps1` | **now a tool, not a hand-run**: `cases: 23  match: 14  REFUSED: 9  DIVERGE: 0`.  The nine are the 7 deliberate `parallel for` refusals plus `struct` parameter and `struct` local.  §0's "7 match, 16 refused" is two rounds of closure ago |
| self-hosting | `tools\build.ps1` | `RESULT: ok`, and the fixpoint is judged by the **C**, not by `.exe` bytes: `selfhost\build\vm.c` == `selfhost\vm.c` == `selfhost\build\_fixpoint_gen2.c`, `CF2F0B76…`, 944 784 bytes.  Two builds of one source produce different `.exe` hashes (PE timestamps), so the emitted C is the artefact to compare |
| the checker | `vm.exe check` on the six exhausted hole cases | six holes closed, each refused with the documented wording.  Of the 70 safety rows the two still open are recorded rather than hidden: `hole_mut_scalar_parameter` (`SPEC.md` §3.2 says this implementation does not keep that promise for a scalar) and `hole_narrowing_binding_{u8,i32}`, the **diverge** rows where the interpreter and the compiled paths answer differently (`300` against `44`).  That divergence is now decided: all three paths will check representability at the narrowing store and refuse with one message, rather than one of them silently keeping a wider value |
| the IDEA plugin | `idea-plugin\build-offline.ps1` | **0.1.11**: `dist\vela-idea-plugin-0.1.11.zip` 375 184 B, `RESULT: PASS` (`build\logs\verify.log`: **162 OK lines, 0 FAIL**), 111 concrete top-level classes, **0 dead**.  The capability ledger is `idea-plugin\FEATURE_PARITY.md`: 27 rows, **26 `implemented`, 1 `refused-deliberately`, 0 `partial`, 0 missing**, and the parameter-name defect the owner reported is closed with before/after evidence in `idea-plugin\evidence\`.  One rule *left* in 0.1.11 rather than arriving: `VelaStringConcatenation` was retired because `+` on two `str`s became legal (`SPEC.md` §1.5) and a finding here has to come back with a refusal the compiler itself makes — its seven findings are the 13 → 7 in `inspection-probe.ps1`'s report, re-run against the compiler that carries the operator (`vm.exe` 870 400 B, `aaa0a599…`) |
| the benchmarks | `tools\bench.ps1 -Reps 7` | re-measured and recorded in `bench/RESULTS.md` in its own dated section: serial matmul **2.42× slower**, parallel **3.49× by best-of-7 / 2.80× by median**, mandelbrot a tie, sieve 1.50× slower.  **"Faster than C++" is still not established**, and §4's ladders remain the argument for the only lever that could change that |

**Changed since this block was written, and it is the change the whole LLVM
workstream was for: `build` no longer sends C to `cl.exe`.**  `vm.exe build x.vel`
builds the module in process through `libLLVM` — linked into the compiler, the way
`rustc` carries LLVM — writes the object itself, and calls one external program,
`lld-link`.  North star ① holds on **the default path** now, and
`tools\llvm-no-cl.ps1` is that claim as a gate: it disarms the C compiler with
`CL=/Zs` (read by `cl.exe` itself, so no PATH trick can be walked around by
`vcvars64.bat`) and requires `build` to work, `build-c` to fail, and `build-c` to
work when it is not disarmed.  The C back end is not gone: it is the reference
implementation and it is reached by name, `build-c`, which is how the seven
`parallel for` rows are built and how the fixpoint's emitted C is produced.

What the language is still missing: modules/`import`, `Result`/`?`, generics,
closures, nested functions, string concatenation, slices and iteration/traits — eight
features, with string concatenation being worked on in the tree right now.  **Enums with
payloads and `match` is no longer on that list** (landed 2026-09-24): the interpreter
builds a variant and runs an arm, the C back end lowers a value to a tagged union and an
exhaustive `match` to a `switch` with no `default`, the checker judges exhaustiveness
(naming the variant that is missing), a duplicate arm, an `else` that is not last and a
subject that is not an enum, and the LLVM back end refuses the whole construct **by name**
— five rows of `tests\llvm-refusals.txt`, which is the two-sided ledger.  `enum` and
`match` are reserved words now (`SPEC.md` 1.3), and that is a language decision rather than
a lexer detail, because `match = 1` and `match(x)` are both legal programs and there is no
way to guess between them.  The compiler could not parse a source that uses them until its
own pools were raised — nodes 65,536 → 131,072 and tokens 131,072 → 262,144 — and that
raise had to be its own commit, because the old seed compiler cannot parse a source that
needs more than 65,536 nodes.

## 0. Where this session left the tree (2026-09-20, 02:1x)

**And one thing about the gate itself, because it was broken.**  `tools\build.ps1`
line 374 had lost the newline between `| Out-Null` and the `if` that follows, so
PowerShell read the pipeline as a command named `Out-Nullif` and swallowed **both**
the step that builds the linker and the step that runs it.  `selfhost\vm.vel` was
therefore never regenerated from `selfhost\parts\*.vel`, which made the fixpoint a
comparison of a file with itself: `RESULT: ok` and "the compiler reproduces itself"
were printed, and they were **true and vacuous** — no part edit since that byte was
lost could reach `vm.exe` by way of `build.ps1`.  Found by an agent that tried to
make a part change land and watched it not land; the missing byte is repaired
(`Out-Nullif` count 0, the file parses with no errors, and the diff is one line).
The lesson is the one this repository keeps re-learning: **a check that cannot fail
is not a check**, and the fixpoint is only evidence when the step it compares
against actually ran.  Anything measured through `build.ps1` between the commit that
introduced the broken line and the repair should be re-run.

Written because this session ran to its round limit with the work unfinished, and
the next person needs the boundary rather than the story.  Everything here was
measured; where it is a snapshot of a moving tree, it says so.

**The north star, step by step.**  `vm.exe build-llvm x.vel` builds a program with
**one external program, `lld-link`** — verified with `PATH` stripped to
`C:\Windows\System32;C:\Windows`, where `where cl`, `where clang` and
`where clang-cl` all find nothing, exit 0, the executable runs and prints the same
bytes as the interpreter and the C backend.  The user's directory holds
`x.vel` and `x.exe` and nothing else.  So ①②③ of the acceptance line hold **on the
subset this backend compiles**, and not beyond it:

| steps ⑤⑥ | state |
|---|---|
| ⑤ the three-way differential over the whole corpus | **not done as a tool on that date**; the leader ran it by hand twice.  **Run 1: 4 match, 19 refused.  Run 2: 7 match, 16 refused.  `DIVERGE 0` in both** — the backend has never yet printed something different from the other two. The 16 refusals are an enumerable list: `parallel for` 6 (**refused by design**: LLVM IR has no OpenMP), builtins other than `print`/`len`-of-array 3, struct parameter or local 2, `len`/compare of `str` 2, in-place update 1, `and`/`or` 1.  **It is a tool now** — `tools\llvm-column.ps1`, over two corpora, with a two-sided refusal ledger — and its verdict is `match 42   refused-by-design 7/7   open gaps 0/0   DIVERGE 0` |
| ⑥ `build-llvm` replaces `build` | **DONE since this row was written, and the row is kept because it is the record of the day it was not.**  `build-llvm` is now a synonym for `build`, and `build` is the LLVM path: the C back end is reached by name, `build-c`.  The seven programs the LLVM back end refuses by design are `run-c` rows in `tests\cases.txt`, each with a comment saying why, and `tools\llvm-no-cl.ps1` proves the default path starts no C compiler by disarming one (`CL=/Zs`) |
| ④ `vm.exe` carries its own code generator | **landed**: `vm.exe` grew from 634 368 to 782 848 bytes and now links `libLLVM`. Its cost is measured: `LLVM-C.dll` is a **load-time** dependency, and a `vm.exe` copied anywhere without it **does not start** — exit `0xC0000135`, before `main`, with nothing that names LLVM. `tools\smoke.ps1` asserts the DLL and the runtime object sit beside the compiler |

**The gate is red, and the cause is known to the line.**  Last full run, against a
frozen copy of the compiler so that it could not race the build: **187 passed, 3
failed of 190** (190/0 was its state before this session's LLVM work).

- `vm_main lex output changed` / `vm_main parse output changed` — the sources
  changed (`emit_llvm.vel` joined the parts, so the linked compiler grew).  This is
  `tools\refreeze.ps1`'s normal work: **re-record the digest locks**, not a defect.
- `selfhost_fixpoint` — **a real integration failure, and the cheap kind.** The
  compiler's own source now calls the shim (`ll_new`, `ll_do_build_llvm`,
  `ll_emit_ir`, `ll_fail`), and the suite's fixpoint case builds it with a plain
  `vm.exe build selfhost/vm.vel`, which does not link the shim: **49 unresolved
  `vshim_*` symbols**.  Measured elsewhere, in `%TEMP%`, so as not to touch the
  repository: passing the shim object and `LLVM-C.lib` **as one extra-link string**
  makes the same build succeed — exit 0, `built … (with OpenMP)`, and the compiler
  it produces runs programs.  (Passing them as *two* arguments silently drops
  `LLVM-C.lib` and reports 79 unresolved `LLVM*` symbols instead — the trap is
  worth knowing.)  The fix belongs in the fixpoint case (or in how the runner is
  invoked), and the compiler agent has it.

**The plugin is not usable for development yet, and the gaps are named.**
`VelaGotoDeclaration.kt` resolves four symbol kinds — function, struct, method,
parameter.  **A struct field, a local binding and a `for i in range` loop variable
have no target at all**, which is what "goto is very limited" meant.  The parameter
hint has a defect too: a call can render `s: s: s:`, and both render sites are
`VelaInlayHints.kt:151` and `VelaParameterInfo.kt:112` via `symbolParameters`
(`VelaCompletion.kt:220`).  The standing rule for both: **a hint is right or it is
absent, never wrong** — and the agent owes a completeness table (references →
declared parameter/declaration, verified against `vm.exe parse`) before and after.

**The safety question now has a document and a hole list.**  `SAFETY.md` carries
the promise table with three verdicts (`ENFORCED TODAY` / `NOT ENFORCED` /
`REFUSED BY DESIGN`), a "what is not a hole" section, the holes **ranked by whether
they produce a silent wrong answer, a loud failure or only a missed diagnostic**,
and what the corpus does not cover.  Spot-checked by the leader, outside the
author's report: 5 of 5 sampled `hole_*` cases are *accepted* by `vm.exe check`
(the hole is real) and 4 of 4 sampled promise cases are refused (the promise
holds).  **The holes are recorded and ranked; none is fixed.**

**Two engineering facts added this session, both from a real measurement.**
`tools\check-hygiene.ps1` asks git whether anything over 5 MB could be committed
(it caught `LLVM-C.dll`, 74,159,616 B, one `git add -A` from a public repository)
and names the 222,478,848 B of ignored copies now sitting in the checkout.
`tools\docs-zh-check.ps1` names the translation that went stale.  The
reader-facing documents are bilingual in pairs, each carrying a language switcher
on its first line in the shape GitHub reads — **and how many pairs there are is
deliberately not written here**, because a count in prose is a number that expires
the next time someone translates a file (this sentence said "five" for about an
hour).  The checker's own `found N` is the number, and it is one command away.

**What the next session should do, in order.**  ① make the fixpoint case pass the
compiler's own link inputs, then `tools\refreeze.ps1` + the suite, and get the tree
back to **190/0** — nothing else is worth doing while it is red.  ② The plugin:
goto to fields, locals and loop variables, and the parameter hint, each with a
headless differential that has been shown failing first.  ③ The emitter: the 16
refusals, in the order that unblocks cases (builtins, structs, `str`, in-place
update, `and`/`or`), with `DIVERGE` staying 0.  ④ Only then ⑤⑥.

## 1. What was in the way, and is not any more

For most of the previous round the session could not run **any** command: every
attempt died with

```
Error: subprocess-local: Windows Job runner exited with exit code 1
before proving its managed range empty
```

The mechanism, measured: `cl.exe` starts a **detached** `vctip.exe` (its telemetry
client) that outlives the build by minutes or hours, and the harness tracks
children in a Windows **job object** that refuses to complete while the job is not
empty.  One build was enough to wedge the session, and only a host restart or that
process exiting cleared it.

**Fixed at the source, in the repository, and the fix is live**: `reap_helpers()`
in `selfhost/parts/vm_main.vel` kills the helper immediately after each `cl`
invocation, which is the only place that can — a script driving the suite is
killed before it reaches its own cleanup.  `tools/build.ps1` step 7 reaps again at
the end.  This round ran dozens of builds, including 190-case suite runs, with no
wedge.

## 2. Verified, with the commands that verified them

**Read this table as the state at the commit that produced it, not as today's.**
§0 is today's.  Three rows below have moved since they were written, and they are
kept rather than edited because each was true when its command was run: the
fixpoint hash (`vm.exe` now links `libLLVM` and is 782 848 bytes, not 634 368), the
suite tally (187 passed, 3 failed at the last full run — §0 names the cause), and
the declaration row's byte count.  The digest-lock row is the one whose *values*
are expected to move every time a part changes, which is what `tools\refreeze.ps1`
is for.

| what | command | result |
|---|---|---|
| the toolchain builds and promotes itself | `powershell -ExecutionPolicy Bypass -File tools\build.ps1` | `RESULT: ok`; fixpoint `0A6A273C5328A435`, 758020 bytes, seed / gen-1 / gen-2 **byte-identical** — and the check now reads a file the driver actually writes (see §5).  (The hash moved off `15EAE445780BAA58` because the compiler now carries the code generator's declarations; see the next row) |
| **the compiler carries its own code generator's declarations** | `tools\build.ps1` (its parts guard), then `vm.exe check` | `parts: 9 on disk, 9 in the linker's list, the same set`; the linked compiler is 12687 lines / 436910 bytes and passes `vm.exe check selfhost\vm.vel` (`ok`, exit 0); the generated `selfhost\parts\llvm_shim.vel` passes `vm.exe check` too (`ok`, exit 0), which is the thing that was in doubt when the 55 declarations were staged outside `parts\`.  `tools\gen-shim-decls.ps1 -Check` still reconciles its count against the header: `matches runtime\vela_llvm_shim.h (64 declarations)`, exit 0.  `vm.exe` stayed at 634368 bytes because declarations carry no code |
| the shim API, proven four ways rather than asserted | `powershell -ExecutionPolicy Bypass -File tools\llvm-shim-probe.ps1` | `RESULT: ok`: the M1 program runs identically through the interpreter, the C backend, a hand-written `.ll` under `clang-cl`, and the shim + `lld-link` — all four produce the same 24 bytes, and the flow program (5 blocks, `icmp sge`, `cond_br`, alloca/store/load) produces the same 7.  The failure path exits 1 and names the byte that differed, so the proof can fail |
| the tree is alive, and portable | `powershell -ExecutionPolicy Bypass -File tools\smoke.ps1` | `RESULT: ok` — build+run, **build from a directory outside the repository**, and compiled == interpreted, including a panic |
| the suite, all of it | `tools\refreeze.ps1` then `tests\run_tests.exe` (with `VELA_SELF` absolute) | **190 passed, 0 failed (of 190 cases, 245 captures)**, exit 0 |
| the `parallel for` aliasing rule | `vm.exe check tests\probes\parallel_alias_*.vel` | the cross-iteration read is **refused**; the four legal shapes still emit `#pragma omp` |
| the compiler runs a program with no C compiler | `vm.exe run x.vel` | the interpreter needs no C, no `.ll`, no other language — the carrier binary is all it needs |
| the IDEA plugin | `powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1` | **`RESULT: PASS`**, exit 0: 32 Kotlin sources compile, `dist\vela-idea-plugin-0.1.3.zip` (240277 B), every structural/bytecode/platform/registration/linkage/behavioural check green, version discipline agreeing in four places |
| LLVM/Clang for the native backend | `powershell -ExecutionPolicy Bypass -File tools\get-llvm.ps1` | installed, **`clang version 23.1.1`**, `llc.exe` and `lld-link.exe` present, at `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\bin\` |
| LLVM backend, milestone M1 | `powershell -ExecutionPolicy Bypass -File tools\llvm-m1.ps1` | **met**: one program by three paths — interpreter, C backend, and hand-written LLVM IR linked by `clang-cl` — all print **identical bytes** (`RESULT: ok`, exit 0).  The datalayout, the triple and the MSVC struct ABI were read out of clang's own output, not remembered |
| the benchmarks | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` | frozen in `bench/RESULTS.md` against this compiler: serial matmul **0.543 s** against the C++ twin's **0.224 s** (2.4×, was 5× before the emitter fix), parallel matmul 0.068 s against 0.022 s (3.1×, was 6.6×), sieve 1.5× against an explicitly *unchecked* C++ row, mandelbrot a tie to the microsecond (0.011950 s both), and `parallel for` still really parallel (8.0× from 1 to 16 threads, one checksum at every count).  "Faster than C++" is still not established; see §4 |

## 3. Written, and not verified

- **The LLVM backend**: `selfhost/LLVM_PLAN.md` (phases, the differential test,
  milestones M1–M7), `runtime/vela_llvm_runtime.c`, whose every symbol was read out
  of the C backend's own emitted output rather than remembered, and
  `selfhost/llvm/m1_probe.{ll,vel}` + `tools\llvm-m1.ps1` (M1, **verified** — §2).
  No line of an LLVM **emitter** exists yet, deliberately: the pipeline is proved
  with a hand-written `.ll` first, because a fast backend that is wrong is not a
  backend.  Until the emitter exists, `vm.exe build-llvm` does not exist either, and
  the only route to a native binary is still the C backend.
- **Language features**: `selfhost/ENUMS_PLAN.md` (enums + `match` first) and
  `selfhost/ELISION_PLAN.md` (the two reserved flag bits: 8 × `NF_NO_BOUNDS` on
  index nodes, 16 × `NF_NO_OVERFLOW` on operator nodes of word 6).  Nothing is
  written behind those bits until the analysis that justifies them exists — the
  first version of that plan asserted an interval analysis that did not exist, and
  was rewritten after the premise was checked.  Today's measurement changes that
  plan's priority but not its rule: the checks turn out to cost almost nothing, so
  elision is worth less than the emitter defect in §4.
- **Tools written this round and exercised at least once**: `tools\refreeze.ps1`
  (verified, green), `tools\smoke.ps1` (verified), `tools\bump-plugin-version.ps1`
  (used for 0.1.3; it had two defects, both fixed — see §5), `tools\get-llvm.ps1`
  (used, worked), `tools\llvm-m1.ps1` (verified, M1), `tools\bench.ps1` (used and
  fixed — see §5), `tools\snapshot.ps1` (still unexercised).

## 4. Known gaps, named rather than discovered later

- The plugin has **never been loaded into a running IDE**: `ActionManager` and the
  extension registry need a booted application, so folding, typing, formatter
  output and the Run console are checked structurally, against bytecode and
  against the platform's own descriptors — not by driving the editor.
- The interpreted run path is slower than the compiled one (~76× on a loop MSVC
  cannot vectorise); the plugin now runs programs by interpretation, and
  `Vela.BuildAndRun` compiles when a C compiler is present.  The numbers are in
  `bench/RESULTS.md` and the plugin's console says which path it took.
- `bench/RESULTS.md` predates the runtime's subtraction fix and is being rewritten
  around today's numbers; until then its table is history, not a measurement.  The
  numbers that matter are in README's claim table and in §4 below.
- **"Faster than C++" is not established.**  Re-measured today, with the runtime's
  checks finally working: serial matmul 512² **1.058 s** against the C++ twin's
  **0.213 s** (5×), parallel matmul 0.128 s against 0.019 s (6.6×), sieve 0.0196 s
  against 0.0129 s, mandelbrot a tie.  The cause is measured, not guessed, and the
  measurement has a corrected version worth reading twice.  **Read the numbers
  above as the pre-fix state**, which is why they are kept: what they became is
  serial matmul 0.543 s against 0.224 s (2.4×), parallel matmul 0.068 s against
  0.022 s, sieve 0.0197 s against 0.0134 s — `bench/RESULTS.md`, and §2.  The
  table below is here because it is the argument for the second lever, not because
  it is current.

  | variant of the same emitted C (all printing 1090512707) | seconds |
  |---|---|
  | as the compiler emits it today | 1.058 |
  | index hoisted into a temporary, arithmetic still checked | 0.550 |
  | the same, bounds check also removed | 0.514 |
  | index unchecked, bounds check kept | 0.177 |
  | nothing checked | 0.181 |
  | the C++ twin | 0.213 |

  So there are **two** levers, in order: the emitter wrote every index expression
  **twice** (worth 0.51 s — halving the program's time; **done**, 1.058 s → 0.543 s),
  and after that the checked index arithmetic (`vela_mul_range` + `vela_add_range`) is
  ~0.37 s, which is ~68% of what remains.  The bounds check itself is worth `0.543 −
  0.514 = 0.029 s` — one row of that ladder, and the `~0.04 s` this paragraph used to
  quote does not reproduce from it (see `bench/RESULTS.md`'s arithmetic note).  The
  second lever is exactly what `selfhost/ELISION_PLAN.md` is for, and this table is
  its justification — it is the work that decides the "faster than C++" claim.

  **Correction, because the first version of this paragraph was wrong.**  It said
  "the checks cost almost nothing, the duplication is the whole story", quoting a
  hand-edited variant that was labelled "index computed once, checks kept" but had
  hoisted *unchecked* arithmetic while keeping only the bounds check.  The label did
  not describe the variant, and the conclusion drawn from it was therefore false.
  A second measurement, taken independently while isolating the same cost, produced
  the table above and the true split.  Labels on experiments are load-bearing.
- **Version control exists now.**  The repository had none until this session — which
  mattered, because three test sources had already been lost and could not be
  recovered.  `git.exe` is on this machine, the tree is committed (see `git log`),
  and `.gitignore` was written so that a fresh checkout keeps all 65 corpus sources
  while ignoring every generated file except the two the build needs to start
  (`selfhost\build\vm.c` and `selfhost\build\vm.exe`).

## 5. Defects found by running things, this round

Every one of these was invisible to reading:

- **`build` can report failure for a build that succeeded, and leaves the executable
  behind when it does.**  Measured with `PATH` stripped to `C:\Windows\System32;C:\Windows`
  (so `cl` is reachable only through the absolute `vcvars64.bat`) in a fresh directory:
  `vm.exe build hello.vel` exits **2** with `vela: build: the C compiler refused
  <…>\hello.vel.c` and `vela: panic: build: no C compiler on this host could build the
  emitted C`, while `hello.exe` (150016 B) **is written and runs correctly**
  (`hello from Vela` / `sum 0..99 = 4950`, exit 0).  The identical sequence typed into a
  `.bat` by hand — `call vcvars64.bat`, `set VSLANG=1033`, the same `cl` line — returns
  `ERRORLEVEL=0` at every step and produces the same executable, so the *compiler* is
  not at fault; the driver's verdict is.  The shape is visible in `vm_main.vel`: the
  exit code decides at line 722-729 and the artifact is only checked afterwards, at
  line 730, so a non-zero code with a fresh executable on disk panics before the check
  that would have said otherwise — leaving the user with a working `hello.exe` beside a
  message insisting nothing was built.  Fix direction, to be applied when the build path
  is not in use by other work: **delete the target before invoking the C compiler**
  (Vela has no `stat`, so "the file exists" only proves freshness if nothing was there
  before), then let the artifact decide and the exit code explain.  Deliberately not
  fixed yet: two agents were mid-run and the build path is load-bearing.
- **`vm.exe debug` is a working protocol debugger, and "it does nothing" was an
  artefact of how it was run — corrected 2026-09-21.**  The usage line lists
  `lex|count|parse|nodes|emit-c|run|check|debug|build` and documents the real syntax,
  `vela debug <file.vel> <cmddir> [arg...]`, with events on stdout and commands in
  `<cmddir>/cmd.NNN` (`DESIGN.md` §10).  Measured 2026-09-21 against the compiler built
  into the working tree that day — **which is a mid-round build and is deliberately not
  pinned here**, because the LLVM track owns the build slot this round and rebuilt
  `selfhost\build\vm.exe` several times while this was being written (the binary is
  ignored, not committed, and `selfhost\parts\emit_llvm.vel` is modified relative to
  HEAD `4ddfabc`; a byte count and hash from such a build identify a file nobody can go
  back to, which is the defect §5 records against `bench/RESULTS.md` — the numbers below
  are the measurement, and the pin belongs to the round's frozen compiler):
  `vm.exe debug examples\hello.vel` exits 2 with **0 bytes on stdout** and the usage
  block plus `vela: panic: debug needs a command directory: vela debug FILE <cmddir>`
  on **stderr**; `vm.exe debug examples\hello.vel <cmddir>` exits 0, prints `ready` on
  stderr, and waits for `cmd.NNN` files; given no `cmd.NNN` at all it logs `no command
  file from the driver; running to the end` and the program runs to completion (144
  bytes on stdout was measured that way) — so the stdout size depends on how much of the
  program the driver lets run, not on the mode.  Independent measurements of the stderr
  size differ between generations (893 bytes and 2352 bytes on two builds), which is
  itself the reason this bullet names no binary.  The earlier
  claim in this bullet — "does nothing", "no output at all", "exits 2" — came from
  invoking the mode *without* its command directory and redirecting **stdout only**, so
  both halves were measurement artefacts rather than properties of the compiler.  What
  the IDEA plugin may still say is only that it does not offer a Debug button, which is
  about the plugin, not about the compiler.

- `runtime/vela_runtime.h` was missing `vela_bounds_check`, so **every compiled
  program** failed to link; and `vela_sub_overflows` was wrong, so every compiled
  subtraction panicked — `1 - 5` included.
- `find_runtime`/`has_runtime` resolved the runtime include directory to the
  repository root, so a build from any other directory died with
  `C1083: cannot open include file: 'vela_runtime.h'`.
- The emitted-C scratch directory was keyed by file *name*, so two same-named
  sources in different directories collided; now keyed by flattened full path.
- `VelaFormatter.kt`: on this platform `ASTBlock.getSpacing` takes `Block?`, not
  `Block` — four compile errors.
- `VelaFindUsages.kt`: `private val wordsScanner` generated a `getWordsScanner()`
  accessor that clashes at the JVM level with the interface method it overrides.
- The plugin verifier (`idea-plugin/build/tools/src/VerifyPlugin.java`) credited a declaration's
  *neighbours* with `<with>` attributes because it scanned a fixed 4000-character
  window; eight registrations written the way JetBrains' own bundled plugins write
  them were reported as unbound.  It now reads only the declaration's own body.
- `tools/bump-plugin-version.ps1` looked for `plugin.xml` at the repository root
  (it is under `idea-plugin\`), and its version substitution matched nothing
  because in `$a -replace 'x' + $v + 'y', 'z'` the comma binds tighter than
  `-replace`, turning the pattern into an array.  Both fixed and used for 0.1.3.
- `runtime/vela_llvm_runtime.c` used `bool` in C without `<stdbool.h>`: MSVC's C
  mode tolerates it, clang does not (`error: unknown type name 'bool'`).  Found by
  the M1 probe, which is exactly why M1 exists before an emitter.
- The C emitter wrote **every array index expression twice** — the defect behind the
  lost "faster than C++" claim, measured at 5–6.6× on the matmul benchmarks and
  quantified by controlled experiment (see §4).  **Fixed**: the index goes into a
  statement-scoped temporary and is computed once; serial matmul 1.058 s → 0.543 s,
  the emitted compiler 850572 → 754867 bytes, no check removed.  The fix's first cut
  had a race — the temporary was declared at function scope, so inside `parallel for`
  all threads shared one variable and one thread's index subscripted another's store;
  `tests/probes/parallel_alias_nested_rowmajor.vel` failed and printed `1 1`.  The
  declaration is now per statement, in the innermost block, which OpenMP makes private.
- **`tools\build.ps1`'s fixpoint step was reading a file nothing writes.**  It looked
  for `%TEMP%\vela-build\vm.vel.c` and copied it over `selfhost\vm.c` as the seed, but
  the driver stopped writing that name when the scratch key became the *whole* source
  path (`build_scratch`/`flat_name` → `selfhost_vm.vel.c`).  A stale `vm.vel.c` from
  03:05:47 was still on disk, so the step kept promoting an artefact of an earlier
  generation and the "byte-identical fixpoint" it reported was measured against that
  artefact.  The name is now computed from the driver's own rule, the file is deleted
  before the build, a missing file is a failure with the scratch listing printed, and
  the source path is spelled once (`selfhost/vm.vel`) because the spelling is embedded
  in every emitted file/line literal — `selfhost\vm.vel` emits 758641 bytes against
  `selfhost/vm.vel`'s 754867 for the same program.
- The benchmark harness itself could not run: `bench/run_bench.vel` spawned
  `selfhost/build/vm.exe` with forward slashes and no absolute path, so cmd.exe
  split at the first `/`, the compiler never started, and the failure read as "the
  Vela side did not build".  It now resolves the compiler from `VELA_SELF` and
  panics with that explanation instead, and `tools/bench.ps1` sets the variable.
- The docs claimed a Python front end, a `--fast-int` flag, a browser IDE and a
  `restrict` keyword.  None of them existed; that round produced 19 corrections.

## 6. Environment traps, each measured

- **`VELA_SELF` must be set for the suite**, to an absolute path.  The relative
  fallback is written with forward slashes, the suite wraps commands in an extra
  pair of quotes for cmd.exe, cmd splits at the first `/`, and the reported reason
  is the driver's harmless first line.  Measured: unset, 30 cases failed; set, 0.
- **`C:\Users\lu\Downloads` is the writable root** for anything this session runs.
- **`curl.exe` and .NET cannot do HTTPS here** (TLS interception: schannel
  `SEC_E_NO_CREDENTIALS`, .NET `基础连接已经关闭`).  `node --use-system-ca` works;
  it fetched LLVM's 489740247-byte archive when curl could not.
- **`javap` does not exist**; the verifier's `ClassSig`/`MethodFlags` reflection
  tools are the substitute.
- **Two `vm.exe` builds running anywhere on the machine used to destroy each other,
  and this — not disk contention — is what made the suite flaky for two rounds.**
  The driver's per-build diagnostics were named after *the compiler binary*, so every
  build wrote the same `…\selfhost\build\vm.exe_cc.bat` and ran it: process A executed
  the batch file process B had just written, so A compiled B's C file and the two
  fought over B's object.  Caught in the act, with the wrong C named in the message:

  ```
  cl … "…\dsh-AYfS5n\vela-build\tools_link_selfhost.vel.c"   <- what A asked for
  C__Users_lu_…_dsh-ZYih20_vela-llvm-shim_m1_probe.vel.c     <- what cl actually compiled
  …m1_probe.vel.c : fatal error C1083: cannot open compiler generated file:
      "…m1_probe.vel.obj"  Permission denied
  vela: panic: build: no C compiler on this host could build the emitted C
  ```

  Two builds of different sources, started together, reproduced it every time
  (`a.err` and `b.err` both naming `_b.vel.obj`); a single build never did.  The
  driver now names those files after the build's own scratch C
  (`<scratch>/<flat>.vel.c_cc.bat`), which also puts them where the rest of the
  intermediates go instead of beside the compiler.  Verified: the same two concurrent
  builds both print `built …  (with OpenMP)` and exit 0, and `selfhost\build\` no
  longer collects `*_cc.bat` clutter.  The fixpoint is re-established at
  `57D6650FC7824D50`, 754954 bytes, seed / gen-1 / gen-2 byte-identical.
- The earlier explanation in this file — "heavy concurrent I/O makes `cl.exe` flaky",
  inferred while a 2 GB LLVM archive was being unpacked — was **wrong**, and it is
  left here as the correction rather than deleted.  The flakiness was deterministic
  given two concurrent builds; the archive was a coincidence of timing, and a wrong
  mechanism in the record is worse than no mechanism.
- **`tools\verify-all.ps1` could not fail.**  Its first real run printed
  `RESULT: ok - 4 step(s) ran, all green` while the suite step had just reported two
  failing cases: the failure filter read `$r.Ok -eq $false`, where `$r` is the
  summary loop's variable and always `$null` inside a `Where-Object` block, and
  `$null -eq $false` is false.  Fixed to `$_.Ok` and verified against a synthetic
  results list: the old expression finds 0 failures, the fixed one finds 1 and names
  the step.  A gate that cannot fail is the defect the gate exists to catch.
