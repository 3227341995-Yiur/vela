**English** | [简体中文](STATUS.zh-CN.md)

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
  ~0.37 s, which is ~68% of what remains.  The bounds check itself is ~0.04 s.  The
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
- **`vm.exe debug` is advertised and does nothing.**  The usage line lists
  `lex|count|parse|nodes|emit-c|run|check|debug|build`, and `vm.exe debug
  examples\hello.vel` exits 2 with **no output at all**.  The IDEA plugin's refusal to
  offer a Debug button is therefore honest, and stays honest until the mode exists.

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
