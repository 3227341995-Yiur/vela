# The LLVM backend — scope, interface, and what we will not claim

Status: this file is the contract the work is done against, written before the
code so that the hard parts are decided once instead of guessed at per agent.
**Its decisions age well; its self-descriptions do not**, so the state is here as
measurements and the rest of the file is read as a dated ledger.  Measured
2026-09-20 01:30, on the tree as it then stood:

| what | measured now |
|---|---|
| `tools\link_selfhost.vel` | `part_count() == 10`; `llvm_shim.vel` is part **6**, `emit_llvm.vel` part **7** |
| `selfhost\parts\` | **10** files on disk, the same set as the linker's list |
| `selfhost\vm.vel` (linked) | 14 645 lines / 520 297 bytes |
| the driver's new modes | `selfhost\parts\vm_main.vel` — `emit-llvm` at :1148, `build-llvm` at :1164 |
| the shim | `runtime\vela_llvm_shim.{c,h}` 62 378 / 28 203 bytes, **64** functions, **55** declared in Vela |
| the emitter | `selfhost\parts\emit_llvm.vel`, 73 064 bytes / 1631 lines |
| LLVM on this machine | 23.1.1 under `C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\` — **on disk, not on `PATH`**, and `find_lld()` walks up three directories from `exe_dir()`, which stops one short of it (`<repo>\llvm` is checked, `<repo>\..\llvm` is not), so `VELA_LLD` has to be set by hand until that is fixed |
| `vela_llvm_runtime.obj` | **exists now** — 38 819 bytes beside the compiler in `selfhost\build\` and `selfhost\` |
| `vm.exe` | 770 560 bytes (it was 634 368 before it linked `libLLVM`), and the driver advertises `emit-llvm` and `build-llvm` in its usage text |
| **what the LLVM backend compiles today** (verified by the leader, 2026-09-20) | two array-free programs — a `print` of a string and an integer expression, and a `while` loop with checked arithmetic — built three ways (**interpreter, C backend, `build-llvm`**) print **byte-identical** output (`4E7186877AA30E1E`, `D87D921C2479A85B`), all exits 0; with `PATH` stripped to `C:\Windows\System32;C:\Windows`, where `where cl`, `where clang` and `where clang-cl` all find nothing, `build-llvm` still exits 0 and the executable still prints the same bytes, and the user's directory holds only `<stem>.vel` and `<stem>.exe`. **The link line names one external program: `lld-link`.** That is the north star's ①②③, on this subset |
| **the whole `run` corpus, three ways** (measured, same day) | all **23** `run` cases in `tests/cases.txt`, each built and run three ways and compared by output hash and exit code: **4 match byte for byte** (`python_floor_semantics`, `recursion_fib`, `while_with_narrowing`, `control_flow` — so functions, calls, recursion, `while`, `if` and the checked arithmetic are right), **19 are refused with a named reason**, and **0 gave a wrong answer**. That last number is the one that matters: the backend either agrees to the byte or refuses out loud, and it has not once produced a silently different program. The 19 refusals are an enumerable work list rather than a mystery — **arrays 9** (the arena allocator is `static`, so the emitted IR has no symbol to call: `runtime/vela_runtime.h:106`), **builtin calls 4**, **struct parameters and locals 2**, **`str` comparison 1**, **in-place update of one value kind 1**, **`and`/`or` short-circuit 1** |
| **what it still refuses** | an **array** is refused with the reason, not approximated: `vela_arena_alloc` is `static` in `runtime/vela_runtime.h`, so the emitted IR has no symbol to call. `examples\hello.vel` declares one, so the shipped example does not compile on this backend yet |

The paragraphs below keep the tense they were written in, on purpose — they are
the record of what was decided before the code, and where one of them reads as
"not started" or "not here", it was true then.  **Where a claim in this file and a
measurement disagree, the measurement is right**, and the paragraph should be
corrected rather than trusted.

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

**And the C API was not just inventoried, it was run.**  `selfhost/llvm/phase2_spike.c`
is a hand-written spike in the same spirit as M1: it initializes the X86 target,
builds a module for the host triple, emits one function `main` that returns the
constant 42, and writes a COFF object with `LLVMTargetMachineEmitToFile`.  Measured
on this machine:

```
host triple: x86_64-pc-windows-msvc
wrote object: C:\...\vela-phase2\out.obj        330 B
lld-link /entry:main /subsystem:console out.obj /out:out.exe
out.exe   ->   exit code 42
```

So the whole in-process path works here: `cl` compiles the spike against
`include\llvm-c\Core.h` and links `LLVM-C.lib`; at run time `LLVM-C.dll` must be
beside the program (or on `PATH`), exactly as a Rust toolchain needs its own
libraries.  Nothing in that path writes C or IR text, and the object file went to a
scratch directory.  Phase 2 is therefore an engineering problem, not a research one.

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

### Wiring the shim into `vm.exe` — the plan, file by file

The bridge already exists in the language: `extern c` declarations are parsed, resolved,
checked and emitted today (`parser.vel:422`, `check.vel:2582`, `emit.vel:2122`), so the
compiler can call the shim directly.  What matters is the **exact C types** those
declarations produce, read out of `emit_ctype` (`emit.vel:177`) rather than assumed:

| Vela | C in the emitted program |
|---|---|
| `int` | `int64_t` |
| `float` | `double` |
| `bool` | `bool` |
| `str` | `vela_str` |
| `i32` | `int32_t` |
| `u8` | `uint8_t` |
| `None` | `void` |

So the shim's signatures are fixed by that table — with **one correction that the
first version of this plan got wrong, and it is the kind that costs a day**:

> `extern c` **refuses `str` as a parameter.**  Measured:
> `vm.exe check selfhost\parts\llvm_shim.vel` →
> `vela: type error: 'extern c' parameter 'name' has type str`.

The rule is deliberate and reasoned, not an oversight — `check.vel:2580-2598`:

> **the types are scalars**.  A `str` is a length and a pointer into Vela's own
> string region, not a `char *`, and an array is storage whose length Vela owns —
> handing either across would be inventing an ABI, and inventing an ABI is how a
> safe language stops being one.

`ck_is_extern_scalar` accepts exactly `int`, `i32`, `u8`, `float` and `bool`, for
parameters and returns alike.  The table above is still right about the C *spelling*
of each Vela type — it is the **permission** at the boundary that differs, and
`str` has none.  That matters here and nowhere else yet: the compiler has to hand
its own code generator the symbol names, the string literals and the output path of
the program it is compiling, and every one of those is a string.

**The resolution is to leave the rule alone and make the interface scalar** — decided
2026-09-19, and this replaces the resolution written here first, which was wrong.

* The first version of this plan said: allow `str` as an `extern c` **parameter**, keep
  refusing it as a return, on the grounds that a callee declared with `str` must itself
  be written against Vela's `vela_str`.  Reading `check.vel:2580` again shows why that
  reasoning does not survive contact: *"a `str` is a length and a pointer into Vela's own
  string region, **not a `char *`**"*.  With `str` parameters allowed,

      extern c def strlen(s: str) -> int

  compiles, and the call hands a 16-byte struct to something that wants a pointer.  C
  converts a pointer to an integer without a word — that is the hazard the call-site rule
  in the same file was written about — so the mistake is silent at compile time and
  undefined at run time.  The rule is not bureaucracy; it is the reason the boundary is
  safe, and the plumbing it costs is worth paying.
* So the shim exposes the strings through a **byte buffer on its own side of the
  boundary**: `vshim_buf_reset` / `vshim_buf_byte(u8)` / `vshim_buf_len` /
  `vshim_buf_read_byte`, plus a `*_buf` sibling of every function that takes a name,
  a literal or a path, and of the three that return a `str`.  One byte per call is
  ~10⁵–10⁶ calls for a program the size of the compiler, i.e. milliseconds.  Every one of
  the shim's functions then becomes expressible in Vela as it stands, no language rule
  changes, and the `strlen` hazard above cannot be written at all.
* The three `str` **returns** (`vshim_last_error`, `vshim_host_triple`, `vshim_module_ir`)
  get buffer forms too, which removes the reason the generator had to record them as
  omissions — though it keeps recording omissions, because it silently dropped three
  functions once and a count that is quietly short is how it did it.

Ledger as measured after the buffer protocol landed: the shim's header declares **64**
functions; `tools\gen-shim-decls.ps1` turns them into **55** scalar `extern c`
declarations and records **9** as explicit omissions in the file itself, and the count is
reconciled against the header on every run, so neither drift nor silent loss can pass
`-Check`.  All **55** now type-check: `vm.exe check selfhost\parts\llvm_shim.vel` prints
`ok`, exit 0 — the buffer forms removed the six that used to be refused for taking a
`str`, and the three `str` returns are the remaining omissions.
The declarations are **generated in place at `selfhost\parts\llvm_shim.vel` and registered
as part 6** of the linked compiler.  They used to be staged outside `parts\` on the theory
that a part the checker refuses would break every build; the buffer protocol made that
theory false, so the staging directory was deleted rather than kept as a second copy that
can drift.  The guard that would have caught the mistake is in `tools\build.ps1`, and it
now reads:

```
parts: 9 on disk, 9 in the linker's list, the same set
```

**Step 2 is closed** with: the guard line above; the linked compiler passing
`vm.exe check selfhost\vm.vel` (`ok`); and `tools\build.ps1` reaching
`RESULT: ok` with a byte-identical fixpoint over three generations
(`0A6A273C5328A435`, 758020 bytes each).  `tools\smoke.ps1` was green as well, so
the compiler that carries the 55 declarations still built and ran programs, and
the `.exe` was still the only file it left in a user's directory.
The numbers in that paragraph are the ones that commit measured — **9 parts,
12687 lines / 436910 bytes** — and the status block at the top of this file
carries what the tree says now, because `emit_llvm.vel` was registered as part 7
after this was written.  The guard line printed above is likewise that commit's
output.

Two consequences worth naming before step 3 leans on them.  `vm.exe` is **not** yet linked
against `LLVM-C.lib`, and it builds anyway: nothing calls the 55 yet, so the linker never
looks for them.  And the emitted-C fixpoint hash moved from `17CF78257FE7A2BD` to
`0A6A273C5328A435` while `vm.exe` stayed at 634368 bytes — declarations carry no code, so
the binary is the same size and only the C the compiler writes out changed.  Both facts are
consistent, and the second is the one to remember if a later size comparison looks odd.
The alternatives, if the rule turns out to be more load-bearing than it looks: feed
the shim bytes through `u8` scalars one at a time (slow, and the compiler would do it
for every literal), or let the shim derive its own output path from the environment
and look its own runtime symbols up by a numeric selector (removes most of the
strings, but not the string literals, which are the ones that are genuinely data).

The steps, in order, each ending with the evidence that closes it:

1. **`runtime\vela_llvm_shim.{c,h}`** — the scalar C API over `llvm-c`.  Ends when a standalone
   driver builds the M1 program through it and that output equals the other three paths
   byte for byte (`tools\llvm-shim-probe.ps1`).
2. **`selfhost\parts\llvm_shim.vel`** (new part) — the same API as `extern c def …` declarations,
   and **`tools\link_selfhost.vel`'s part list must learn the new file**, or the declarations
   never reach `selfhost/vm.vel`.  Ends when `vm.exe` still builds and the fixpoint still holds.
3. **`selfhost\parts\emit_llvm.vel`** (new part) — the emitter: the same AST walk as `emit.vel`
   with shim calls instead of C text.  In growing order: a call, a string literal and a constant
   (what M1 proves), then `print`, then arithmetic with its checks, then `if`/`while`/`for i in
   range`.  Ends when `vm.exe build-llvm examples\hello.vel` produces a running executable whose
   output equals the interpreter's and the C backend's.
4. **`selfhost\parts\vm_main.vel`** — two modes: `emit-llvm` (scratch `.ll` text, for reading and
   diffing) and `build-llvm` (shim → `.obj` in `%TEMP%\vela-build\` → `lld-link` with
   `vela_llvm_runtime.obj` → executable **beside the source and nothing else**).  `tools\smoke.ps1`
   already asserts that last property, so smoke is what passes this step.
5. **`tools\build.ps1`** — compile the shim, link it into `vm.exe` with `LLVM-C.lib`, and copy
   `LLVM-C.dll` beside `vm.exe`.  From then on the compiler carries its own code generator, as
   `rustc` carries LLVM; the DLL is the compiler's dependency in the compiler's own directory, and
   nothing lands in a user's project.
6. **`tests\run_llvm.vel`** — the differential harness the acceptance criterion calls for: for every
   `run` case in `tests\cases.txt`, the interpreter's output, the C backend's and the LLVM
   backend's must be identical byte for byte and all three must equal the recorded golden.  This is
   what turns "the new backend is correct" into a fact, and it must pass **before** `build-llvm` is
   allowed to become what `build` does.
7. **Promotion and re-proof** — `build-llvm` becomes `build`, the C backend is demoted to `build-c`
   for the differential test, and the fixpoint is re-established on the new backend: the compiler,
   compiled by itself through LLVM, emitting the same object.  That is M7, and it is claimed last.

## Interface (phase 1)

```
vm.exe emit-llvm  <file.vel>          # the module's IR text, on stdout, like emit-c
vm.exe build-llvm <file.vel> [rtdir]  # emit the object in process, then lld-link it
```

**What the driver does, read out of it on 2026-09-20** (`vm_main.vel`, mode
`build-llvm` at :1164) — this is the part the original interface note above got
wrong, because it described the phase-1 shape (hand the `.ll` text to `clang-cl`)
and phase 2 replaced that with an object built in process:

* the object comes from the shim (`vshim_emit_object_buf`), i.e. from **libLLVM
  inside `vm.exe`** — not from a program parsing IR text;
* the same module is written out **as text for a human** by `write_ll`, into the
  scratch, so a person can read what was emitted without a second tool;
* from there on **the only external program is `lld-link`**, found through the
  `VELA_LLD` environment variable or the install `tools\get-llvm.ps1` creates.  If
  it is missing the mode panics and says so, rather than falling back to a C
  compiler;
* `vela_llvm_runtime.obj` must sit **beside the compiler**, and the mode panics
  naming it when it does not (`vm_main.vel:978`).  Nothing writes that object yet:
  `tools\build.ps1` is where it belongs, which is why `build-llvm` cannot run end
  to end today.
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
   Still missing from M1's description: `build-llvm` itself.  *When this was
   written* there was no emitter, so `vm.exe build-llvm` did not exist and the M1
   evidence was produced by a PowerShell driver that handed a hand-written `.ll`
   to `clang-cl`.  Measured 2026-09-20, the emitter exists
   (`selfhost\parts\emit_llvm.vel`, 73 064 bytes / 1631 lines, registered as part
   7) and `build-llvm` is a mode in the driver (`vm_main.vel:1164`) — but it still
   cannot run end to end, because `vela_llvm_runtime.obj` is not written yet
   (`vm_main.vel:978` panics and names it).  So **M2 is not claimed here**: the M2
   list below is unchanged, and a milestone counts as closed when a differential
   run is behind it, not when an emitter compiles.
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

- Not "no dependency at all" until the linker is ours too — the `later` row of the
  phase table above, which this paragraph used to call "phase 4" while the table
  named no such phase. A number the table does not carry is not a plan.
- Not "faster than C++" — that is a measurement, not an intention, and the
  existing table already reports a loss on parallel matmul. If LLVM changes the
  numbers, `bench/RESULTS.md` records them with the backend they were built by.
- Not "parallel for works on the LLVM backend" until M6 says so with numbers.
- Not "the C backend is deprecated" — it stays the reference implementation, and
  a divergence between the two backends is a bug in the newer one until proven
  otherwise.

## What the feasibility probe found on this machine (measured, 2026-09-19)

**There is no LLVM compiler here.** *Read this as the 2026-09-19 snapshot it is,
kept because the reasoning in it is what mattered: LLVM was an install step rather
than a design problem, and `tools\get-llvm.ps1` was then written and run.  LLVM
23.1.1 is on disk now, under
`C:\Users\lu\Downloads\llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\`, and still
**not on `PATH`** — which is a fact the driver has to deal with, not a missing
install.*  That was the first fact of this workstream, and it was an install step
rather than a design problem:

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
  datalayout line in any hand-written `.ll` were **unverified placeholders** until
  a compiler existed to print them — and then they were printed, by
  `clang.exe -S -emit-llvm` on a two-line C file, which is where the M1 record
  above gets `x86_64-pc-windows-msvc19.44.35227` and the `e-m:w-p270:32:32-…`
  line.  So this paragraph is history, kept because the caution it encodes is the
  reason those strings are measured rather than remembered; the strings themselves
  are measurements now.

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

