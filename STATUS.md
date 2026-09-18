# STATUS — what is verified, what is written, and what is in the way

A snapshot of a working session, not a substitute for running the command it
names.  Every "verified" line below was measured on this machine; every "written"
line is source that has not been through the tool it needs, and says so.

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

| what | command | result |
|---|---|---|
| the toolchain builds and promotes itself | `powershell -ExecutionPolicy Bypass -File tools\build.ps1` | `RESULT: ok`; fixpoint `6B6C0522CAD4DCE1`, 834810 bytes, seed/gen-1/gen-2 **byte-identical** |
| the tree is alive, and portable | `powershell -ExecutionPolicy Bypass -File tools\smoke.ps1` | `RESULT: ok` — build+run, **build from a directory outside the repository**, and compiled == interpreted, including a panic |
| the suite, all of it | `tools\refreeze.ps1` then `tests\run_tests.exe` (with `VELA_SELF` absolute) | **190 passed, 0 failed (of 190 cases, 245 captures)**, exit 0 |
| the `parallel for` aliasing rule | `vm.exe check tests\probes\parallel_alias_*.vel` | the cross-iteration read is **refused**; the four legal shapes still emit `#pragma omp` |
| the compiler runs a program with no C compiler | `vm.exe run x.vel` | the interpreter needs no C, no `.ll`, no other language — the carrier binary is all it needs |
| the IDEA plugin | `powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1` | **`RESULT: PASS`**, exit 0: 32 Kotlin sources compile, `dist\vela-idea-plugin-0.1.3.zip` (240277 B), every structural/bytecode/platform/registration/linkage/behavioural check green, version discipline agreeing in four places |
| LLVM/Clang for the native backend | `powershell -ExecutionPolicy Bypass -File tools\get-llvm.ps1` | installed, **`clang version 23.1.1`**, `llc.exe` and `lld-link.exe` present, at `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\bin\` |
| LLVM backend, milestone M1 | `powershell -ExecutionPolicy Bypass -File tools\llvm-m1.ps1` | **met**: one program by three paths — interpreter, C backend, and hand-written LLVM IR linked by `clang-cl` — all print **identical bytes** (`RESULT: ok`, exit 0).  The datalayout, the triple and the MSVC struct ABI were read out of clang's own output, not remembered |
| the benchmarks | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` | **the "faster than C++" claim is currently false and now has a measured cause**: serial matmul loses 5×, parallel matmul 6.6×, sieve 1.5×, mandelbrot ties.  See §4 — the emitter writes every index expression twice |

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
  measurement has a corrected version worth reading twice:

  | variant of the same emitted C (all printing 1090512707) | seconds |
  |---|---|
  | as the compiler emits it today | 1.058 |
  | index hoisted into a temporary, arithmetic still checked | 0.550 |
  | the same, bounds check also removed | 0.514 |
  | index unchecked, bounds check kept | 0.177 |
  | nothing checked | 0.181 |
  | the C++ twin | 0.213 |

  So there are **two** levers, in order: the emitter writes every index expression
  **twice** (worth 0.51 s — halving the program's time), and after that the checked
  index arithmetic (`vela_mul_range` + `vela_add_range`) is ~0.37 s, which is ~68%
  of what remains.  The bounds check itself is ~0.04 s.  The first lever is an
  emitter fix and was in progress at the time of writing; the second is exactly what
  `selfhost/ELISION_PLAN.md` is for, and this table is its justification.

  **Correction, because the first version of this paragraph was wrong.**  It said
  "the checks cost almost nothing, the duplication is the whole story", quoting a
  hand-edited variant that was labelled "index computed once, checks kept" but had
  hoisted *unchecked* arithmetic while keeping only the bounds check.  The label did
  not describe the variant, and the conclusion drawn from it was therefore false.
  A second measurement, taken independently while isolating the same cost, produced
  the table above and the true split.  Labels on experiments are load-bearing.
- The repository still has **no version control**.  `git.exe` exists on this
  machine; nothing has been committed because nothing has been initialised.

## 5. Defects found by running things, this round

Every one of these was invisible to reading:

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
- The C emitter writes **every array index expression twice** — the defect behind
  the lost "faster than C++" claim, measured at 5–6.6× on the matmul benchmarks and
  quantified by controlled experiment (see §4).
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
- **Heavy concurrent I/O makes `cl.exe` builds flaky.**  A `refreeze` run reported
  two cases as "could not be built" while a 2 GB LLVM archive was being unpacked on
  the same disk; both built, and a clean re-run reported 190/0.  Correlation, not a
  proven mechanism — but when a build fails oddly, check what else is on the disk
  before believing the compiler.
