# Vela

A systems language with **Python-shaped syntax, C-shaped performance, and a
safety story that has no escape hatch**: no `unsafe`, no raw pointers, no manual
`free`, no FFI, ever.

```vela
struct Vec2 {
    x: float
    y: float

    def dot(self: Vec2, o: Vec2) -> float {
        return self.x * o.x + self.y * o.y
    }
}

pure def fib(n: int) -> int {
    if n < 2 {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

def main() -> None {
    mut total: int = 0
    for i in range(0, 15) {
        total += fib(i)
    }

    mut a: Array[float, 262144] = [0.0]
    mut b: Array[float, 262144] = [0.0]
    mut c: Array[float, 262144] = [0.0]
    for i in range(0, 262144) {
        a[i] = to_float(i % 7) * 0.5 + 1.0
        b[i] = to_float(i % 11) * 0.25 + 2.0
    }

    parallel for i in range(0, 512) {           # accepted only when race-freedom is proved
        for j in range(0, 512) {
            mut s: float = 0.0
            for k in range(0, 512) {
                s += a[i * 512 + k] * b[k * 512 + j]
            }
            c[i * 512 + j] = s
        }
    }
    print(total, to_int(c[0]))
}
```

## What is measured today, and what is still a promise

This project has paid several times for a documented claim that was not true: a
`--fast-int` flag that did not exist, bounds and overflow checks described as
proof-elided while every check was still emitted, a `restrict` clause that was
never emitted at all, and three plugin features that shipped doing nothing for
want of one registration line. So the four claims this language is built on are
listed here with the command that decides each one and with the number measured
most recently. `ROADMAP.md` holds the work that closes the gaps, and each item
there names its own acceptance test.

| claim | the command | measured |
|---|---|---|
| **faster than C++** | `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7` | **not established yet, and the gap is now measured in one place.**  After the emitter fix of 2026-09-19 (each array index computed once instead of twice), serial matmul 512² is **2.4x slower** than its C++ twin (0.5433 s against 0.2241 s, `bench\RESULTS.md`), parallel matmul 3.1x slower (0.0680 s against 0.0221 s), sieve 1.5x slower *while keeping its checks* against an explicitly unchecked C++ row (0.0197 s against 0.0134 s), and mandelbrot ties to the microsecond (0.011950 s both).  The whole remaining matmul gap is the **checked index arithmetic** — `vela_mul_range`/`vela_add_range` on the subscript are ~68% of the post-fix time, while the bounds check is ~0.04 s — so the number that decides this claim is the one `selfhost/ELISION_PLAN.md` will produce, not this row |
| **Rust's safety design** | the corpus's refusal cases, `tools\smoke.ps1`, `vm.exe check tests\probes\parallel_alias_*.vel` | no pointers, no `unsafe`, no `free`, arena-only; bounds and overflow checks fire in compiled code *and* in the interpreter, with the same message; a `parallel for` body that writes an array may read it only at its own index — **proved by a run**: the cross-iteration read is refused (`'parallel for' reads 'a' at an index other than the one it writes …`) while the four legal shapes compile and still emit `#pragma omp` |
| **Python's syntax, strict semantics** | `vm.exe check` | conditions must be `bool`, `//` and `%` are floor, string `+` is refused, a value-producing statement is refused — but a `str` bound to an `int` is still accepted by `check`, and an undeclared type name is caught by an emitter panic rather than by a diagnostic |
| **pure-bred** | `vm.exe build selfhost\vm.vel` with no `cl.exe` on `PATH` | the compiler's source is Vela, the linker is Vela, there is no Python and no C++ anywhere, and `selfhost_fixpoint` passes — but **`build` still needs a C compiler**: C is the code-generation backend. `run` needs nothing (`vm.exe run` is a built-in interpreter), and the LLVM backend that removes the C compiler from `build` is **started, not finished**: its M1 is met (the same program, through the interpreter, the C backend, and hand-written LLVM IR linked by `clang-cl`, prints identical bytes byte for byte — `tools\llvm-m1.ps1`), and the emitter that would write that IR does not exist yet |

Three defects were found by reading the tree against these claims rather than by
trusting them, and all three are fixed: the compiler could not link any program
because the runtime header had lost `vela_bounds_check`; every subtraction in
compiled code falsely panicked because the overflow test was not equivalent to
`a - b < INT64_MIN`; and `build` resolved its include directory one level too
high, so it worked only when run from the repository root — which is why the IDE
plugin's Run button produced nothing.

Two tools answer most "is it alive" questions in one command each. **Both are
written and have not yet been run**, because the harness that runs commands on
this machine was wedged by a detached `cl.exe` helper for the last six rounds;
neither is claimed as verified:

```bat
powershell -ExecutionPolicy Bypass -File tools\smoke.ps1      :: build+run, build from elsewhere, compiled == interpreted
powershell -ExecutionPolicy Bypass -File tools\refreeze.ps1   :: re-record the digests, keep the expectations, run the suite
powershell -ExecutionPolicy Bypass -File tools\build.ps1      :: the whole toolchain, six steps
```

## Where Vela stands from Python

| | Python | Vela |
|---|---|---|
| structure | indentation | **braces** (`{ }`), indentation is never syntax |
| declarations | inferred, untyped | every parameter, return type and non-array binding is annotated |
| conversion | implicit and everywhere | **no implicit conversion**, except an integer literal that fits the other operand's type |
| truthiness | `if x:` | conditions must be `bool`; write `if x != 0` |
| variables | always mutable | immutable by default, `mut` to opt in |
| statements | any expression | a statement that is not a call and produces a value is **refused** (`x + 1` doing nothing is a bug) |
| `a < b < c` | legal, often a bug | rejected |
| `a // b`, `a % b` | floor semantics | floor semantics — deliberately *not* C's truncation |
| string `+` | implicit concatenation | refused; two literals side by side are refused too |
| `"a" "b"` | accidental concatenation | error |
| one-line `if` | allowed | braces make it explicit either way |

## Where Vela stands from Rust

Rust's safety comes from an ownership system plus an `unsafe` escape hatch.
Vela has fewer moving parts and **no hatch at all**:

* all memory comes from one **arena** — `free` does not exist, so a
  use-after-free or double-free cannot be written;
* no function may **return its own array**, so an arena pointer can never
  outlive its frame; `str` storage is either a literal or permanent;
* array indexing is **checked**, and a check disappears only when the compiler
  *proves* the index in range (interval analysis + loop-variable tracking +
  condition narrowing);
* integer arithmetic is **checked** for overflow, and a check disappears only on
  proof. There is no switch that turns the checking off: the compiler emits the
  checks, and the language has no wrapping mode to ask for;
* `divide by constant zero` and `constant index out of range` are compile
  errors, not run-time surprises;
* `parallel for` is accepted only when the compiler proves the iterations are
  race-free (injective index, no shared writes, no impure calls).

The proofs are visible in the C the compiler writes, which is the only report it
gives — there is no banner, and no Python anywhere in this tree:

```
$ selfhost\build\vm.exe emit-c tests\build\parallel_for_correct.vel
    ...
    #pragma omp parallel for          <- race-freedom was proved, so this is real OpenMP
    ...
    vela_bounds_check(...)            <- and two indexes it could not prove kept their check
```

The compiler's own 760 KB of emitted C carries 2215 `vela_add_range`, 1751
`vela_mul_range` and 952 `vela_bounds_check` calls: it is built with the checks on.

## Performance

Same algorithms, same machine, MSVC `/O2`, **median of 7 runs**, time measured
inside each program, and every variant's answer checked for agreement.
Reproduce with `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7`.
Full table, spread and method: `bench/RESULTS.md`.

One machine: AMD Ryzen 7 9800X3D, 8 cores / 16 logical processors, ~4.7 GHz, on an
otherwise idle machine. Different CPU, different numbers.

| benchmark | C++ | Vela |
|---|---|---|
| matmul 512², serial | 0.2270 s (`restrict`: 0.2446) | **0.2055 s** |
| matmul 512², parallel | **0.0372 s** (restrict + OpenMP) | 0.0435 s |
| mandelbrot 1600×1200, parallel | 0.0133 s (OpenMP) | 0.0134 s |
| sieve n = 2×10⁷, fully checked ints | 0.0146 s (unchecked C++) | 0.0145 s |

All three groups verified their answers (`matmul` checksum 1090512707,
mandelbrot 50187647, sieve π(2×10⁷) = 1270607, and the same value for every
variant in a group).

Where Vela *loses*, it says so: parallel matmul is ~17% behind the C++ build that
also uses OpenMP, because Vela keeps a checked multiply and a checked add in the
inner loop that the C++ twin does not. The sieve row is a tie rather than a win,
and the mandelbrot row is a tie. Vela's win is the serial matmul, by ~10% over the
C++ serial build.

The `--fast-int` sieve variant no longer exists: the self-hosted compiler always
emits checked arithmetic, so there is no unchecked Vela build to quote. The row
that used to appear here was produced by the deleted Python front end.

Vela gets there by handing machine-level work to a mature optimiser (the C11
back end) while supplying the facts a C++ compiler has to guess:

* every array parameter is `restrict`, because the language has no aliasing —
  this is a language rule, not an annotation someone can forget;
* hot code allocates from an arena: no `malloc`, no `free` in the loop;
* `#pragma omp parallel for` is emitted only where the checker **proved** the
  iterations race-free — and it is now emitted as real parallel code rather than
  as a serial loop with a comment saying it was deliberate;
* overflow and bounds checks that a C++ version would have to keep (or drop
  unsafely) are removed by proof. `sieve` is the evidence for the arithmetic of
  it: Vela stays fully checked and still matches the build that dropped the
  checks.

Honest caveats: these are three micro-benchmarks with C++ twins written for
this repository, on one machine, and results can flip on a different CPU or a
memory-bound workload. Where Vela does *not* win, it is reported, and that is
three of the four rows above: mandelbrot and the sieve are ties, and parallel
matmul is a loss. The parallel-variant timings also spread noticeably run to run:
worst/best is 2.3× for the C++ OpenMP build and 1.4× for Vela's —
`bench/RESULTS.md` shows best and worst beside every median, so treat the parallel
column as indicative and the serial column as solid.

## Getting started

Everything below is the self-hosted compiler.  There is no Python in this
repository, and no Python in any command on this page.

```bat
selfhost\build\vm.exe run   examples\hello.vel   :: interpret it (no C compiler)
selfhost\build\vm.exe build examples\hello.vel   :: compile it with MSVC/gcc and run
selfhost\build\vm.exe check examples\hello.vel   :: front end only: diagnostics + proofs
selfhost\build\vm.exe emit-c examples\hello.vel  :: show the generated C11
selfhost\build\vm.exe lex   bench\matmul.vel     :: the token stream
selfhost\build\vm.exe parse bench\matmul.vel     :: the syntax tree

powershell -File tools\build.ps1 -Suites          :: rebuild everything and test it
tests\run_tests.exe                              :: the test suite on its own
tests\run_tests.exe record                        :: re-freeze the goldens
tests\run_tests.exe struct                        :: only cases whose name matches
```

`tools/build.ps1` is the whole toolchain: bootstrap `vm.exe` from the checked-in
C, link the compiler's parts with a linker written in Vela, have the compiler
compile itself, and check the fixpoint — the compiler it built must write the
same C again.  Its transcript lands in `..\vela-build-report.txt`.

The test suite is `tests/run_tests.vel` — a Vela program, run by Vela, holding
the corpus (source files plus `tests/golden/`) to what it must do: what a program
prints when compiled *and* when interpreted, the diagnostic each refused program
must produce, a digest of every token stream, syntax tree and body of emitted C,
and the compiler's self-compilation.

## The IDE is an IntelliJ plugin

A browser editor was built here once and **deleted**: a language wants the editor
people already use — its navigation, its refactoring, its debugging — and a
home-made web IDE is a toy beside that.  So is a toolchain that needs Python to
assemble itself; stage 0 went with it.

[`idea-plugin/`](idea-plugin/) is the replacement, and it is built for the IDE on
this machine without Gradle and without a network (the IDE's own JBR and bundled
Kotlin compiler do it; `idea-plugin/build-offline.ps1`):

| what you get | how it works |
|---|---|
| `.vel` and `.vela` files are Vela | the file type claims both; it claimed only `.vela` at first, which meant the plugin did nothing at all on the files this repository is made of |
| syntax highlighting | a lexer written to the same rules as `selfhost/vela.vel` |
| **errors and warnings while you type** | `VelaExternalAnnotator` runs the compiler and draws exactly what it says.  The check is of the *buffer*, not the saved file, so a squiggle is never about the previous version of your program |
| completion, hover, parameter info | `VelaModel` reads names with the plugin's own lexer (declarations, fields, parameters, builtins); the compiler is still the only thing asked about *meaning* |
| Structure view | the same declarations as a tree, navigating to their lines |
| **Vela AST** tool window | the compiler's own `vm.exe parse` dump, indented tree and all.  It runs when you switch files or press Refresh — never per keystroke |
| Run / Debug a file | IDEA's **own** `Run 'fib.vel'` and `Debug 'fib.vel'`, produced by a `RunConfigurationProducer` — so the Run menu, the gutter arrow, the Run window, the re-run history and the stop button are the platform's, not a private copy of them. The configuration **interprets** the file (`vm.exe run`), printing the command line, the program's output and its exit status into the platform's console: no build step, no executable beside the source, no C toolchain. Debug is *refused* until Vela has a debugger to hand it to (see below) rather than pretending with a run |
| New → **Vela File** | creates a `.vel` file containing a legal program, from the same New menu as Java Class and Python File |

One thing is deliberately **absent** from that table: there is no "Vela" submenu in
the context menus, and no custom Check action.  An earlier version had both, and
they were ceremony — "Check" duplicated the live diagnostics, which are the
compiler's own words drawn as you type, and "Run with Vela" duplicated IDEA's Run
*worse* (a console instead of the Run window, no re-run history, no gutter arrow,
no Debug).  A language plugin should add what the editor cannot already do, not a
second place to look for what it can.

One registration detail is worth knowing if you edit `plugin.xml`: a wrong
extension-point id is a **silent no-op**, not an error.  The plugin loads, the
entry does nothing, and the feature never appears — the first version shipped
`extensions="vela"`, `<parserDefinition>` (the id is
`com.intellij.lang.parserDefinition`) and a `lang.structureViewBuilder` that does
not exist in any of the IDE's 1987 jars.  `idea-plugin/build-offline.ps1` is a
verifier rather than a build script: it checks that the file type claims `vel`,
that every `add-to-group` names a real group, that every class `plugin.xml` names
loads and links against the installed platform, and it runs the
compiler/parse/diagnostics path end to end.  Its last recorded verdict is
`RESULT: PASS` — every structural, bytecode, platform, registration, linkage and
behavioural check green against the 262 platform, for **0.1.3**, which is the
release that carries the formatter with indentation settings, folding, comments,
brace matching, typed and enter handling, the colour-scheme page, live templates,
go to declaration, rename, find usages, semantic colouring and parameter-name inlay
hints.  0.1.2 was the version that had never been compiled: the first compile of it
found four errors in two files, listed in `idea-plugin/CHANGELOG.md`.  Read
`idea-plugin/PLUGIN_SURFACE.md` for the extension-point table and
`idea-plugin/BUILD_CHECKLIST.md` for the compile gate and what is still unverified.
Debug stays deliberately refused rather than faked: the compiler has a `debug` mode
in its sources and no promoted binary that answers it, so the run configuration
suppresses the Debug action until that is true.

What it still does not do: completion is name-level, not type-directed (Vela has no
local type annotations to read); parameter info draws the signature as plain text,
because the 253/262 `ParameterInfoHandler` interface has no per-parameter rich
documentation left to supply; the AST window reads the file on disk, so it
reflects what you have saved; `<` and `>` cannot be brace-matched, because the
lexer gives every operator one token type and a pair would match `+` with `<`; and
there are no postfix templates, for the reason written into the template file.

The `since-build="253"` claim was checked, not assumed, **for the 0.1.1 source
set**: those 18 sources were compiled against **both** the installed IntelliJ
IDEA 2026.2 (build 262) and the installed PyCharm 2025.3 (build 253), and every
platform class and member they name exists in both.  The 0.1.2 sources that
`idea-plugin/CHANGELOG.md` lists have not been through that compile, so the claim
is currently established for the artifact in `dist/`, not for this tree's working
set — `BUILD_CHECKLIST.md` §1 is the gate that re-establishes it.  What no compile
proves either way is runtime behaviour in either IDE: see
`idea-plugin/BUILD_CHECKLIST.md` for the compile gate and the acceptance tests,
`idea-plugin/PLUGIN_SURFACE.md` for every registration and the declaration it was
read from, and `idea-plugin/CHANGELOG.md` for what each release was verified by.

## Running the suite by hand, and the trap that cost thirty failures

```bat
tests\run_tests.exe          :: everything
tests\run_tests.exe fast     :: skip the fixpoint case
tests\run_tests.exe record   :: re-freeze the goldens
```

**Set `VELA_SELF` to an absolute path before running it.**  `tests/run_tests.vel`
finds the compiler through that variable and otherwise falls back to the relative
`selfhost/build/vm.exe` — forward slashes — while the suite wraps each command in
an extra pair of quotes for cmd.exe's sake.  cmd.exe then splits that path at the
first `/` and answers `'selfhost' is not recognized as an internal or external
command`; the suite reports the *captured stderr* as the reason, which is the
driver's own harmless first line, so every case looks like a compiler failure.
Measured: unset, 30 cases failed "could not be built"; set, 5.
`tools\refreeze.ps1` sets it for exactly this reason and `tools\build.ps1` always
has.

Two more things about this tree, both measured rather than guessed:

* **A build leaves a detached `vctip.exe` behind**, and this harness's Windows job
  object refuses to complete while it is alive — a session that runs a build-heavy
  command can die and need its host restarted.  The driver now reaps the helper
  immediately after the C compiler exits, and `tools\build.ps1` / `tools\smoke.ps1`
  clean up as well.
* **`vm.exe build` writes its intermediate C and object files under the system temp
  directory** (`%TEMP%\vela-build\<source name>`), so the directory that holds a
  program gains exactly one new file: the executable.  C is this implementation's
  code-generation detail, never an artifact of a Vela program.

## Self-hosting

A bootstrap always starts somewhere (Rust began in OCaml, Go in C, Swift in
C++), and Vela is explicit about the stages:

| stage | what it is | status |
|---|---|---|
| **0** | `vela/` — lexer, parser, checker, C11 emitter in Python | **deleted in stage 4.** The Python front end, its IDE and its differential suites are gone; `Get-ChildItem -Recurse -Include *.py` returns nothing. Everything below is what replaced it — and the sentence that used to stand here ("working; 60 tests") described a tree that no longer exists |
| **1** | `selfhost/vela.vel` — Vela's **lexer written in Vela** | working; token-for-token identical to stage 0 on every file in this repo, including its own source |
| **2** | **parser** in Vela, linked into `selfhost/vm.vel` | working: syntax-tree output identical to stage 0 on every `.vel` file, including its own source |
| **2.5** | **interpreter** in Vela (`selfhost/parts/resolve.vel`, `eval.vel`) | working: `vm.exe run file.vel` executes a program with no Python and no C compiler in the loop, and every program in the corpus prints exactly what the compiled twin prints |
| **3** | C11 emitter in Vela (`selfhost/parts/emit.vel`) and its own driver: `vm.exe emit-c file.vel`, `vm.exe build file.vel` | working: the C it writes means the same thing as stage 0's did on every program in the corpus; it **compiles itself** (`vm.exe build selfhost/vm.vel` → `vm_by_vela.exe`, byte-identical C, fixed point when it compiles the compiler again); and `vm.exe build` **drives the host's C compiler itself**, so one binary is the whole toolchain |
| **4** | the **checker** in Vela (`selfhost/parts/check.vel`), then stage 0 **out of the tree** | **done**: `selfhost/parts/check.vel` carries every verdict stage 0 had — the last differential run put every program *and every reject case* in the tree to both checkers and got **70/70 agreement, 42 of them byte for byte, 0 verdict differences**.  With that certified, the test goldens were frozen and **the Python front end was deleted**: `vela/` (lexer, parser, checker, emitter, CLI), the browser IDE, the Python build tools (`rebuild.py`, `bootstrap.py`, `link_selfhost.py`), the Python test suites and the frozen indentation front end in `tools/legacy/`.  `check` runs before every `emit-c`, `run` and `build`, so the surviving front end refuses what it must instead of emitting C for it |
| **5** | foreign functions: declared `extern c` bindings to **C** libraries | **working, in the small shape**: the declaration *is* the C prototype (no header, no library name, and the declared name is the symbol the back end calls), the types are scalars (`int`/`i32`/`u8`/`float`/`bool`, and `-> None` for a C `void`), and `pure` is the user's word for a C function with no side effects.  The checker refuses where it cannot describe the boundary honestly, at the declaration (`'extern c' parameter 's' has type str`; an array and a struct too — which is why there are no foreign methods) **and at the call site**: an argument crosses only as the kind the parameter declares, with the one exception of an int constant that fits, so `abs(1.5)` and `abs(int_value)` are `vela: type error` at the line of the call rather than a conversion the C compiler would make silently; arity is the resolver's verdict, and the interpreter has no foreign call at all (`refuse-interp` asserts it).  What is deliberately left out — `extern c "lib" { ... }` blocks, `cstr`, `(T*, N)` arrays, `extern struct`, variadics, callbacks, unsigned wider than `u8` — is `DESIGN.md` §9.5 |

Two things about *how* this tree is built, because they are the answer to
"what has to exist for Vela to exist":

```
> powershell -File tools\build.ps1 -Suites
=== 1/6  bootstrap vm.exe from selfhost\build\vm.c          -> exit 0
=== 2/6  link the parts      tokens 74115 -> 74115           -> exit 0
=== 3/6  build the compiler with the compiler                -> exit 0
=== 4/6  build the standalone lexer                          -> exit 0
=== 5/6  fixpoint: does generation 2 write what generation 1 wrote?
    seed  (selfhost\build\vm.c) : 669CCA3EA85504DA  741204 bytes
    gen 1 (selfhost\vm.c)       : 669CCA3EA85504DA  741204 bytes
    gen 2 (emitted by itself)   : 669CCA3EA85504DA  741204 bytes
    byte-identical: the compiler reproduces itself
=== 6/6  the test suite      180 passed, 0 failed (of 180)   -> exit 0
RESULT: ok
```

`tools/build.ps1` is the ordinary path, and it is the *only* path: step 1 is
`cl.exe` on `selfhost/build/vm.c`, which is C11 that Vela emitted for the
compiler — one C file and `runtime/vela_runtime.h` are the floor.  From there
everything is done by the compiler itself, including linking its own source: the
linker (`tools/link_selfhost.vel`) is a Vela program, built and then run, and
step 2 checks that what came out is still a program the checker accepts — a
linker that ate a part or spliced one twice would be caught before the fixpoint
spends a minute on it.

The fixpoint in step 5 is the point of the whole exercise: generation 1 writes the
C for `selfhost/vm.vel`, generation 2 is built from it, and generation 2 writes
that same file again — same SHA-256, all three.  The byte count and the first
sixteen hex digits are printed by the step itself (`tools\build.ps1` §5, the
`seed`/`gen 1`/`gen 2` lines) and are deliberately not restated here: a number
copied into a document goes stale the next time a part changes, and this document
has paid for that already. A back end that was subtly wrong about its own source
would drift here rather than pass.

Measurably, today — every one of these is a case in `tests/run_tests.vel`, and
the suite is green. The case-by-case tally is not quoted here: `tests/cases.txt`
is the manifest and the suite prints its own counts, so quoting a number that
goes stale every time the corpus changes would be exactly the kind of sentence
this section exists to avoid.

```
$ tests\run_tests.exe
  ok    recursion_fib            (compiled + interpreted)
  ok    string_escapes           (compiled + interpreted)
  ok    truthiness               (refused: the diagnostic is byte-exact)
  ok    extern_c_probe           (compiled)
  ok    extern_c_probe_interp    (refused by the interpreter, as promised)
  ok    vm                       (lex/parse/emit-c digests)
  ok    selfhost_fixpoint        (generation 2 reproduces the C byte for byte)

$ selfhost/build/vm.exe run tests/build/recursion_fib.vel
986
```

The interpreter is what makes Vela self-hosting in the sense that matters: it
executes a program whose meaning is fixed by the language, not by a C compiler.
It is a tree-walker, so it is slower than the compiled form — on a 10-million
iteration loop that MSVC cannot vectorize (a data-dependent branch per
iteration), the compiled binary takes 9.1 ms and the interpreter 695 ms, i.e.
about 76x, with identical output.

Vela 0.1 has no module system (`import` is reserved), so a multi-file Vela
program is a build-time concatenation: `tools/link_selfhost.vel` splices the
lexer's front half (`selfhost/vela.vel`, up to its driver section) with
`selfhost/parts/{vm_state,parser,resolve,check,eval,emit,dump,vm_main}.vel` into
`selfhost/vm.vel`, which is what the compiler is handed. That is the same trick
C plays with `#include`, and it disappears the day Vela grows modules. The linker
is written in Vela and *built* before it runs, because the interpreter's `str` is
a handle into a fixed table and a program that assembles a 338 KB file out of a
thousand concatenations exhausts it — a limitation of the interpreter's value
representation, not of the compiler.

```bat
selfhost\build\vm.exe build tools\link_selfhost.vel  :: build the linker
tools\link_selfhost.exe                              :: regenerate selfhost/vm.vel
tools\link_selfhost.exe --check                      :: is it up to date?
selfhost\build\vm.exe build selfhost\vm.vel          :: the compiler compiles itself
selfhost\build\vm.exe parse bench\matmul.vel         :: the tree, from Vela
selfhost\build\vm.exe lex   selfhost\vm.vel          :: the tokens (74115 of them)
selfhost\build\vm.exe run   tests\build\recursion_fib.vel
```

**The compiler compiles itself.** The C11 back end is complete enough that the
program in `selfhost/vm.vel` is a program it can translate, and the build script
checks the consequence — the C written by the compiler it built is the C that
built it:

```bat
selfhost\build\vm.exe build selfhost\vm.vel   :: -> selfhost\vm.c + selfhost\vm.exe
selfhost\vm.exe emit-c selfhost\vm.vel        :: must equal selfhost\vm.c, byte for byte
```

That is not hypothetical. The first self-hosted build compiled, ran, and printed
`\` wherever a newline belonged: the emitter wrote the **raw source text** of a
string literal (escapes still escaped) while claiming the length of its **decoded
value**, so every literal carrying an escape lost its last byte. Both facts had to
be the same fact — the value and the count of its bytes — and `string_escapes` in
`tests/cases.txt` fails if they stop being.

Every self-hosted stage is written using only the language's own safe surface: no
pointers, no unsafe, nothing the checker refuses. The compiler is built with the
overflow checks **on** — the flag that used to turn them off (`--fast-int`) does
not exist in the self-hosted driver, and its own emitted C is the evidence: 2215
`vela_add_range`, 1751 `vela_mul_range` and 952 `vela_bounds_check` calls.  Stage 0
had such a flag, for a reason that no longer applies: the *interpreter* implements
the interpreted program's checks itself, the way `runtime/vela_runtime.h` does for
compiled code, and that needs the host's own `+`/`-`/`*` to be defined and total
rather than stopping the compiler with a panic about a line of its own source.

## Layout

```
runtime/         vela_runtime.h — arena, checked arithmetic, strings, host I/O
selfhost/        vela.vel (lexer in Vela), parts/ (state, parser, resolver,
                 checker, evaluator, emitter, dumper, driver), vm.vel (linked)
selfhost/build/  vm.c (the seed the compiler bootstraps from), vm.exe, vela.exe,
                 vm_by_vela.exe (the compiler compiled by the compiler)
examples/        hello.vel
bench/           three benchmarks, their C++ twins, RESULTS.md
tests/           run_tests.vel (the suite, in Vela), cases.txt (the corpus),
                 golden/ (what each case must print, refuse with, or produce),
                 build/ (the corpus sources and the build outputs)
tools/           build.ps1 (bootstrap + rebuild + fixpoint + suite),
                 link_selfhost.vel (the linker, in Vela)
```

`SPEC.md` is the language definition; `DESIGN.md` explains why it is built the
way it is.

**What is no longer here, and why.** Stage 0 — `vela/` in Python: lexer, parser,
checker, C11 emitter and command line — was deleted in stage 4, together with the
browser IDE (`vela/ide/`), the Python build tools (`rebuild.py`, `bootstrap.py`,
`link_selfhost.py`, `build_selfhost_by_vela.py`, the formatter), the seven Python
differential suites and the frozen indentation-based front end in `tools/legacy/`.
They were deleted *after* the last thing only they could do: certify the test
goldens.  While both front ends were in the tree, every program in the corpus was
put to both, and the run that froze `tests/golden/` came out **70/70 agree, 42 of
them byte for byte, 0 verdict differences**.  From then on the goldens and the
fixpoint are the evidence, and no command in this repository needs an interpreter
it did not have to write.
