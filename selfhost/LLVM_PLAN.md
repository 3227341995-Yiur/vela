# The LLVM backend — scope, interface, and what we will not claim

Status: **phase 1 not started**. This file is the contract the work is done
against, written before the code so that the hard parts are decided once instead
of guessed at per agent.

## Why this exists at all

`vm.exe build` today emits C and hands it to `cl.exe`. Everything that matters
about that is good — C is a portable, well-optimised, universally present
backend, and the whole corpus is verified through it — except one thing: a Vela
program cannot be built on a machine that has no C compiler, and the language is
supposed to be self-contained. The interpreter (`vm.exe run`) already makes Vela
runnable with nothing else installed; this work makes it *buildable* natively
without MSVC specifically.

Two consequences of Vela's own design shape the whole plan, and both are
measured facts rather than opinions:

1. **Vela cannot call the `llvm-c` API directly.** `extern c` accepts only
   scalar-to-scalar signatures (SPEC §12 — the language has no pointer type), and
   `llvm-c` is a pointer API (`LLVMBuildAdd(builder, lhs, rhs, name)`). So phase 1
   emits LLVM **IR text** — the same kind of work the C backend already does —
   and phase 2 goes through a C shim that exposes a scalar-only surface.
2. **The safety semantics live in `runtime/vela_runtime.h`.** Bounds checks,
   checked arithmetic, the panic convention and the arena are all there, and the
   C backend's emitted code calls into it by name. The LLVM backend must call the
   *same* functions, so that "Vela is checked arithmetic by default" has one
   implementation rather than two that drift.

## Phases, and what each one actually buys

| phase | what it delivers | what is still needed on the machine |
|---|---|---|
| **1** | `vm.exe emit-llvm f.vel` writes `f.ll` **into the build scratch, never beside the source**; `clang-cl` (or `llc` + a linker) turns it into `f.exe` | an LLVM toolchain binary. **`clang` is itself a C compiler**, so this phase replaces MSVC with LLVM; it does *not* remove the C-compiler dependency |
| **2** | `runtime/vela_llvm_shim.c` exposes a scalar interface over `llvm-c`; `libLLVM` is linked into `vm.exe`, which then writes **an object file** itself — no C text, no IR text, nothing in the user's directory | a linker (`lld-link`, which ships with the LLVM package we already have) |
| **later** | our own COFF/PE writer, so linking needs nothing external | nothing |

**Phase 2 is not speculative: the pieces were inventoried on this machine**
(2026-09-19, LLVM 23.1.1 as unpacked by `tools\get-llvm.ps1`):

| what | where | size |
|---|---|---|
| the C API headers | `include\llvm-c\Core.h`, `BitWriter.h`, `Target.h`, … | — |
| the import library | `lib\LLVM-C.lib` | 299,868 B |
| the shared library | `bin\LLVM-C.dll` | 74,159,616 B |
| the linker | `bin\lld-link.exe` | — |

So the shipping path can be: **LLVM-C inside the process** builds the module, writes
`%TEMP%\vela-build\<flattened>\<stem>.obj`, and `lld-link` links that with
`vela_llvm_runtime.obj` into `<stem>.exe` **beside the source — and that is the only
file the user ever sees**, which is the north star's actual requirement.  No MSVC, no
`cl.exe`, no `clang.exe`, no `.c`, no `.ll` anywhere the user looks.  The one
remaining external thing is a linker, which is exactly what Rust needs too.

Phase 1 stays in the plan for what it is worth: it proved the IR shapes, the module
header and the MSVC struct ABI with hand-written IR (M1) and it is the honest way to
build an emitter incrementally, because a text `.ll` can be read and diffed.  But it
must **not** ship a `.ll` beside a user's source; the C backend already moved its
intermediates into `%TEMP%` for this reason, and phase 1 follows the same rule.

The C backend **stays**. It is the only backend the corpus currently verifies
end to end, and it stays verified: every phase above is additive, and `build`
keeps working exactly as it does today until a phase is finished and measured.

## Interface (phase 1)

```
vm.exe emit-llvm  <file.vel>          # writes <stem>.ll beside the source, like emit-c
vm.exe build-llvm <file.vel> [rtobj]  # emit-llvm, then clang-cl the .ll + the runtime object
```

- The runtime object is built once, and by the LLVM-side driver:
  `clang-cl /c runtime/vela_llvm_runtime.c /Fo<dir>\vela_llvm_runtime.obj`.
- Output paths follow the C backend's rule, which is: **the executable lands beside
  the source and nothing else does**.  The `.ll` and the `.obj` go to
  `%TEMP%\vela-build\<flattened path of the source>\`, the same scratch the C backend
  already uses, and for the same reason — the north star for this project is that
  `vm.exe build x.vel` leaves `x.exe` and no other language's file in the user's
  directory.  The C backend's history here is worth remembering twice: it used to
  depend on being run from the repository root (every failure that produced was a
  user wondering why their program would not build), and it used to write `.c` and
  `.obj` next to the user's source, which is the thing phase 1 must not reintroduce.
- `parallel for` on the LLVM backend is **refused with a message naming the
  reason**, not silently serialised and not silently dropped. LLVM IR has no
  OpenMP; emitting the runtime ABI (`__kmpc_fork_call`) is real work and is
  phase-1-out-of-scope. A loop that says it is parallel and runs serially is the
  one failure mode this project has already promised never to ship.

## The runtime contract

`runtime/vela_llvm_runtime.c` is new and does one thing: it `#include`s
`vela_runtime.h` and exposes **non-static** wrappers with the names the emitted IR
calls, so the checked arithmetic, the bounds check, the arena and the panic
convention have a single source of truth shared with the C backend.

The names are the C backend's own — `vela_bounds_check`, `vela_add_range`,
`vela_sub_range`, `vela_mul_range`, `vela_print_i64`, `vela_print_f64`,
`vela_print_str`, `vela_print_nl`, `vela_panic`, the string-table functions, the
arena functions. Reusing them is deliberate: a difference in *name* between the
two backends is a difference nobody would notice, and the differential test below
compares behaviour, not vocabulary.

**Never `abort()`.** A failing check calls `vela_panic`, which prints to stderr
and exits with `VELA_PANIC_STATUS` (2). `abort()` raises a Windows Error
Reporting dialog, and an earlier version of this runtime that used it wedged the
harness's job runner for two rounds. That is measured, not theoretical.

**Printing.** `vela_print_f64` must reproduce the C backend's formatting exactly,
because the corpus compares bytes. Whatever the C library does for `%g`-style
output is the observable contract; the LLVM side calls the same runtime function
rather than formatting on its own.

## Types and layout (to be pinned by measurement, not memory)

| Vela | IR | note |
|---|---|---|
| `int` | `i64` | signed; overflow handled by the range helpers |
| `float` | `double` | |
| `bool` | `i1`, stored as `i8` | |
| `str` | the existing `vela_str` struct | layout read from the runtime header, not restated |
| `Array[T, N]` | `[N x T]` alloca/global in the frame | bounds always checked unless a proof removes it |
| `struct` | LLVM struct, field order as declared | |

The target triple, data layout string, and the calling convention for `printf`
are **measured from the installed toolchain** and written into this file once
known; IR text is version-sensitive, so the LLVM version in use is pinned here
and asserted by the build script rather than assumed.

## The differential test — this is the acceptance criterion

The corpus already proves that a program's compiled output equals its interpreted
output. The LLVM backend adds a third path, and the rule is:

> for every `run` and `native` case in `tests/cases.txt`, the **C-built** output,
> the **LLVM-built** output and the **interpreter's** output must be identical,
> byte for byte, and every one of them must equal the recorded golden.

That harness is a Vela program (`tests/run_llvm.vel`, no Python), and it runs the
same case list the suite does. A backend that is fast and wrong is not a backend.

## Milestones, each one measured before the next starts

1. **M1 — MET (2026-09-19).**  `hello`-shaped output, `clang-cl` in the loop, a
   running executable, and the proof is differential rather than a look at the
   screen: `tools\llvm-m1.ps1` runs one program three ways — the interpreter
   (`vm.exe run`), the C backend (`vm.exe build` + run), and hand-written IR
   (`selfhost/llvm/m1_probe.ll`, linked by `clang-cl` against
   `runtime/vela_llvm_runtime.c`) — and requires all three to print identical
   bytes.  `RESULT: ok -- three paths, identical bytes`, exit 0.
   Two things were settled by measurement rather than memory, and both would have
   been wrong if assumed:
   - the module header, read out of `clang.exe -S -emit-llvm` on a two-line C file:
     `target datalayout = "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"`,
     `target triple = "x86_64-pc-windows-msvc19.44.35227"`;
   - the ABI for `vela_str`, read out of clang's IR for a C caller: the Microsoft
     x86-64 ABI passes a 16-byte struct **by reference** and returns it through a
     **hidden pointer**, so the declarations are
     `declare void @vela_llvm_print_str(ptr)` and
     `declare void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)` — not the
     by-value spellings that look right and do not link.
   The slice also found the first real portability defect in the LLVM runtime:
   `runtime/vela_llvm_runtime.c` used `bool` in C without `<stdbool.h>`, which MSVC's
   C mode tolerates and clang does not (`error: unknown type name 'bool'`).
   Still missing from M1's description: `build-llvm` itself.  There is no emitter
   yet, so `vm.exe build-llvm` does not exist and the M1 evidence is produced by a
   PowerShell driver that hands a hand-written `.ll` to `clang-cl`.  Writing the
   emitter is M2's work, and the M2 list below is unchanged.
2. **M2** — arithmetic with checks, `if`/`elif`/`else`, `while`, `for i in range`.
   Evidence: differential runs including an overflow case that must panic with the
   same message on both backends.
3. **M3** — functions, calls, parameters, returns, recursion (`recursion_fib`).
4. **M4** — structs, methods, fixed-size arrays, indexing with bounds checks.
5. **M5** — strings: literals, `concat`, `len`, `substr`, `bytes_at`, the string
   table, and the `-`-free arithmetic rules.
6. **M6** — the whole `run` corpus green on both backends, plus the benchmarks
   built by LLVM and compared against the C-built numbers.
7. **M7** — the fixpoint on the LLVM backend: the compiler, compiled through
   LLVM, emits the C it emitted before, byte for byte. This is the milestone that
   makes "self-hosting with an LLVM backend" a fact rather than a hope, and it is
   the last one for a reason.

## What we will not claim

- Not "no dependency at all" until the linker is ours too (phase 4).
- Not "faster than C++" — that is a measurement, not an intention, and the
  existing table already reports a loss on parallel matmul. If LLVM changes the
  numbers, `bench/RESULTS.md` records them with the backend they were built by.
- Not "parallel for works on the LLVM backend" until M6 says so with numbers.
- Not "the C backend is deprecated" — it stays the reference implementation, and
  a divergence between the two backends is a bug in the newer one until proven
  otherwise.

## What the feasibility probe found on this machine (measured, 2026-09-19)

**There is no LLVM compiler here.** That is the first fact of this workstream, and
it is an install step rather than a design problem:

- Visual Studio 2022 Build Tools lives at
  `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools` — the `(x86)`
  matters, and a scan of `Program Files` alone would wrongly conclude there is no
  Visual Studio at all. Its `VC\Tools\Llvm\` tree contains **only**
  `clang-format.exe` and `clang-tidy.exe` (LLVM 19.1.5) plus runtime DLLs: no
  `clang.exe`, no `clang-cl.exe`, no `llc`, no `opt`, no `lld-link`, no `llvm-as`.
- `vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Llvm.Clang
  -property installationPath` returns **empty** — the Clang *compiler* component
  is not installed, so the scaffolding is present and the compiler is not.
- `C:\Program Files\LLVM` does not exist, no `libLLVM*.dll` is reachable,
  `Get-Command clang,clang-cl,llc,opt,lld-link,llvm-as` finds nothing, and neither
  chocolatey, scoop, msys64 nor a winget package provides one. `winget` itself
  fails on every invocation including `winget --version` (exit `-1978335231`,
  `0x8A150001`), HTTPS to llvm.org and github.com closes the connection, and the
  session is not elevated — so nothing can be fetched from inside it either.
- The C toolchain is intact: `cl.exe` under
  `VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64`, the path `tools\build.ps1` already
  resolves.

The cheap route, when it is someone's decision to make: **add
`Microsoft.VisualStudio.Component.VC.Llvm.Clang` to the existing Build Tools
instance.** `clang-cl` then reuses the MSVC linker and CRT already installed — no
`lld`, no second runtime, no separate tree. It needs administrator rights and a
working network.

Two corrections to this plan, both from the probe:

1. Phase 1's "an LLVM toolchain binary" means **an install step first**, not a
   configuration step.
2. "Replaces MSVC with LLVM" is true of **`cl.exe` only**. The Visual Studio
   installation has to stay, because `clang-cl` takes its CRT and linker from it.
   The dependency that disappears is the C compiler binary, not the installation.

### Facts the emitter will need, found by reading the runtime

- **Under UCRT, `stderr` is not a global symbol.** It is a macro for
  `__acrt_iob_func(2)`, so panic IR must
  `declare i8* @__acrt_iob_func(i32)` and call it with `2`. Declaring `@stderr`
  (the MinGW habit) will not link — and this sits on the path of every panic
  message.
- `vela_bounds_check` is an **unsigned** compare, `(uint64_t)idx >= (uint64_t)len`,
  precisely so that a negative index is caught: in IR, `icmp uge i64`.
- The overflow test is `b > 0 && a > INT64_MAX - b`, and that `sub` must **not**
  be `nsw`: when `b <= 0` the subtraction overflows, `nsw` makes the result
  poison, and the `and` only appears to rescue it. A plain `add i64` (no `nsw`,
  no `nuw`) already matches the runtime's `(uint64_t)a + (uint64_t)b` trick — so
  **the emitter must never express a check with `nsw`/`nuw`.**
- Varargs: the call site must **repeat** the marker —
  `call i32 (i8*, ...) @printf(i8* <fmt>, i64 <arg>)` — integers need `%lld`-style
  formats with `i64`, a `float` must be `fpext`-ed before a variadic call, and
  `main` is `i32 @main()` unmangled.
- `vela_str` is `{ i8* data; i64 len }` and the arena aligns to 16 bytes: read
  both from `runtime/vela_runtime.h` rather than restating them.
- Float formatting is a **byte** contract: `%.6f` today, and the trap is that `%f`
  coincides with it — an emitter that formats its own floats passes every test
  until it silently diverges.
- The tools that *are* present report LLVM 19.1.5, so expect 19.x conventions
  (opaque pointers are normal; `i8*` is still accepted). The target triple and the
  datalayout line in any hand-written `.ll` are **unverified placeholders** until
  a compiler exists to print them with `clang -print-target-triple` and
  `-S -emit-llvm`.

### Operational warning, which matters more than it looks

The harness on this machine dies after a build — every later command fails with
`Windows Job runner exited with exit code 1 before proving its managed range
empty` — and the probe's own death correlates exactly with a command that spawned
`cl.exe` and `link.exe` through `vcvars64.bat`, while commands in the same batch
that did not touch the MSVC toolchain succeeded. **Correlation, not proof**, but
two consequences follow: every Clang invocation must go through the same hygiene
as the C backend (the driver now sets `VSCMD_SKIP_SENDTELEMETRY=1`, and
`tools\build.ps1` plus `tools\smoke.ps1` clean up `vctip`/`mspdbsrv` afterwards),
and an experiment that calls a compiler directly should expect to need a host
restart after it.

