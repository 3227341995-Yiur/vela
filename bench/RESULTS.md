# bench/RESULTS.md — what the harness measured, and how to reproduce it

    powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7

That one command builds the harness with the compiler in the tree, builds every
variant, runs each one seven times and prints the table below.  There is no Python
in it and none anywhere in the repository: the harness is a Vela program
(`bench/run_bench.vel`), built by the compiler it is measuring.

## What these numbers are

* **Machine**: AMD Ryzen 7 9800X3D, 8 cores / 16 logical processors, ~4.7 GHz,
  Windows, MSVC `/O2` for both sides (`cl` from VS 2022 BuildTools, `/openmp` for the
  parallel variants).  One machine's numbers, taken on an otherwise idle machine.
* **Repetitions**: 7 per variant.  *median* is the headline; *best* and *worst* are
  beside it so the spread is not hidden.
* **Where the clock is read**: *inside* each program, around the timed loop — not
  process wall time, so process startup and teardown flatter neither side.
* **Every answer is checked**: the last column is the value the variant must print.
  A fast wrong answer is a failure, not a result, and the harness exits non-zero if
  any variant disagrees.
* **Compiler**: the tree at commit `a0b2078`+ (the emitter that computes each array
  index once), fixpoint `15EAE445780BAA58…`, seed `selfhost\build\vm.c` 754867 bytes,
  `selfhost\build\vm.exe` 633344 bytes.  The `.c`, the `.ll` and the object files go
  to `%TEMP%\vela-build\`; the only file these builds leave in the directory holding
  their source is the executable.

## Read this before comparing with an older copy of this file

The previous table was **deleted, not edited**.  Its Vela rows were measured while
two defects were live, and neither of them is subtle:

1. **The overflow test was vacuous.**  `vela_sub_range` and friends wrapped their
   result into an `int64_t` and compared it against `INT64_MIN..INT64_MAX` — a range
   no `int64_t` can leave — so the checked arithmetic could not fire.  A compiled
   `x = x + one()` with `x = 9223372036854775807` printed the wrapped value and
   exited 0 while the interpreter panicked.  Fixed; the corpus now covers it.
2. **The emitter wrote every array index expression twice**, once inside the bounds
   check and once inside the subscript, so the checked index arithmetic ran twice per
   element access.  Fixed on 2026-09-19 by computing the index once into a
   statement-scoped temporary; the emitted compiler shrank 850572 → 754867 bytes and
   matmul serial went 1.058 s → 0.543 s.

The old table said Vela's serial matmul *beat* both C++ serial builds.  It does not,
and the honest reading of the old number is that it was measured with checks that
could not fire.  Keeping that table beside this one would invite exactly the wrong
conclusion, so it is gone and this file is the record of what the numbers were.

## matmul 512×512 f64

    variant                    best     median      worst  answer
    C++ serial             0.218800   0.224069   0.254624  1090512707
    C++ restrict           0.218188   0.231455   0.239106  1090512707
    Vela serial            0.540116   0.543324   0.546360  1090512707
    C++ restrict+omp       0.020264   0.022092   0.029807  1090512707
    Vela parallel          0.065715   0.068042   0.076169  1090512707

Vela serial is **2.4× slower** than the C++ serial twin (0.5433 s against 0.2241 s),
and Vela parallel is **3.1× slower** than the OpenMP C++ twin (0.0680 s against
0.0221 s).  Both gaps come from one place, measured by taking the same emitted C and
removing one thing at a time:

    variant                                  seconds
    as the emitter writes it                 1.058     (before the index fix)
    index computed once, checks kept         0.543     <- this compiler
    index once, bounds check also removed    0.514     (the bounds check is ~0.04)
    index unchecked, bounds check kept       0.177     <- the arithmetic checks are ~0.37
    nothing checked                          0.181
    the C++ twin                             0.227

So the bounds checks are nearly free, and the **checked index arithmetic** —
`vela_mul_range` and `vela_add_range` on the subscript — is about 68% of what is
left.  That is the next piece of work (`selfhost/ELISION_PLAN.md`: prove the index
cannot overflow, stop emitting the calls, keep the output identical), and this table
is why it is worth doing.  Until then "faster than C++" is a goal with a measurement
behind it, not a claim.

## mandelbrot 1600×1200

    variant                    best     median      worst  answer
    C++ serial             0.084423   0.084900   0.085566  50187647
    C++ omp                0.011824   0.011950   0.012269  50187647
    Vela parallel          0.011460   0.011950   0.012703  50187647

A tie on the median, to the microsecond (0.011950 s both).  This workload is bound by
the floating-point escape iteration, where both sides compile to the same
instructions and Vela's per-iteration checks are negligible beside the loop's own
work.  Vela is ~7× faster than the serial C++ build.

## sieve n = 20×10⁶ (π(n) = 1270607)

    variant                    best     median      worst  answer
    C++ unchecked ints     0.013207   0.013413   0.014688  1270607
    Vela checked ints      0.019615   0.019710   0.020296  1270607

Vela is 1.5× slower (0.01971 s against 0.01341 s) **while keeping its checks**: the
C++ row is explicitly unchecked (`int`, no overflow test), and the sieve's inner loop
is almost nothing but an index and a comparison.  This is the row where the safety
design shows up as a number, and it is the row elision should move.

## Repeatability, and that `parallel for` is really parallel

The parallel variants are the ones that could silently be wrong, so their answers are
checked on every run, and the thread sweep below was re-taken on this compiler
(2026-09-19) — one checksum at every thread count:

    OMP_NUM_THREADS=1     0.5338 s    checksum 1090512707
    OMP_NUM_THREADS=2     0.3016 s    checksum 1090512707
    OMP_NUM_THREADS=4     0.1429 s    checksum 1090512707
    OMP_NUM_THREADS=7     0.0914 s    checksum 1090512707
    OMP_NUM_THREADS=16    0.0671 s    checksum 1090512707
    serial reference      0.5455 s    checksum 1090512707

8.0× from one thread to sixteen on an 8-core machine, with the same answer at every
count.  That curve, plus the `#pragma omp parallel for` in `bench\matmul.c`, is the
evidence that `parallel for` compiles to parallel code rather than to a serial loop
with a comment claiming otherwise.

The 25-run-per-variant answer check and the 90-run `parallel for` case check recorded
in the previous freeze were measured on the previous compiler; their property (one
distinct answer, always) is what the suite's own `run` cases assert on every run, and
re-taking the 25-run counts on this compiler is outstanding rather than assumed.

## What has been thrown away

One row, and only because it cannot be built: `VELA --fast-int`.  The flag belonged to
the deleted Python front end; this compiler always emits checked arithmetic and has no
switch to turn it off, so the row is gone rather than guessed at.  The C++ twins are
written for this repository, on the same machine, with the same optimiser and the same
algorithm, and the noisy *worst* columns are left in for exactly that reason.
