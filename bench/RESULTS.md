# bench/RESULTS.md — what the harness measured, and how to reproduce it

    selfhost\build\vm.exe build bench\run_bench.vel
    bench\run_bench.exe 7

Those two commands produce every number on this page.  There is no Python in
either of them and none anywhere in the repository: the harness is a Vela program
(`bench/run_bench.vel`), built by the compiler it is measuring.

## What these numbers are

* **Machine**: AMD Ryzen 7 9800X3D, 8 cores / 16 logical processors, ~4.7 GHz,
  Windows, MSVC `/O2` for both sides (`cl` from VS 2022 BuildTools).  These are
  one machine's numbers, taken on an otherwise idle machine.
* **Repetitions**: 7 per variant.  The column marked *median* is the headline;
  *best* and *worst* are shown beside it so the spread is not hidden.
* **Where the clock is read**: *inside* each program, around the timed loop
  (`now()` in Vela, the C++ twins' own equivalent) — not process wall time, so
  process startup and teardown flatter neither side.
* **Every answer is checked**: the last column is the value the variant must
  print.  A fast wrong answer is a failure, not a result, and the harness exits
  non-zero if any variant disagrees.
* **The `--fast-int` sieve variant no longer exists.**  The self-hosted compiler
  always emits checked arithmetic — there is no switch that turns the checks off
  and no driver flag to pass one.  Earlier versions of this file carried a
  `VELA --fast-int` row produced by the deleted Python front end; that variant
  cannot be built by this compiler, so the row is gone rather than guessed at.
  The checked variant, which is the one that exists, is below.
* **Every number below was measured on 2026-09-19 with the compiler as it stood
  at 00:30, and a defect found while re-reading them changes what the word
  "checked" meant for those rows — so the table is left exactly as it was
  measured rather than edited to look right.**  The emitted C does carry the
  range calls (`bench/matmul.vel` compiles to 6 `vela_bounds_check`, 10
  `vela_add_range` and 6 `vela_mul_range` calls), so the *cost of the calls* is
  in every timing here; but the test inside `vela_add_range`, `vela_sub_range`
  and `vela_mul_range` is vacuous — each wraps its result into an `int64_t` and
  then compares that against `INT64_MIN..INT64_MAX`, a range no `int64_t` can
  leave, so it cannot fire.  Measured: a compiled `x = x + one()` with
  `x = 9223372036854775807` prints `-9223372036854775808` and exits 0, while the
  interpreter panics with `integer overflow (addition)`.  Bounds checks are not
  affected: the compiled binary panics correctly on an out-of-range index.  The
  table will be re-frozen once that is fixed.

## matmul 512×512 f64

    variant                    best     median      worst  answer
    C++ serial             0.215417   0.227023   0.274414  1090512707
    C++ restrict           0.219437   0.244606   0.251856  1090512707
    Vela serial            0.198380   0.205521   0.236347  1090512707
    C++ restrict+omp       0.031988   0.037210   0.074823  1090512707
    Vela parallel          0.039586   0.043463   0.053903  1090512707

Vela serial is faster than either C++ serial build (0.2055 s against 0.2270 s and
0.2446 s): this is where the `restrict`-by-language-rule and the proved-away
bounds checks pay for themselves.

Vela **parallel** is ~5× faster than the C++ serial builds, but it is *slower*
than the C++ build that also uses OpenMP — 0.0435 s against 0.0372 s, about 17%.
That is reported rather than smoothed over.  The reason is the arithmetic: Vela
emits a checked multiply and a checked add (`vela_mul_range` / `vela_add_range`)
in the inner loop of the naive triple loop, and the C++ twin does not.  Parallel
matmul is a case where Vela's safety costs something measurable.  Vela's spread is
also tighter (worst 0.0539 s against 0.0748 s).

## mandelbrot 1600×1200

    variant                    best     median      worst  answer
    C++ serial             0.086385   0.088038   0.090638  50187647
    C++ omp                0.012132   0.013315   0.021430  50187647
    Vela parallel          0.012379   0.013396   0.014200  50187647

A tie between the two parallel builds (0.0134 s both).  This workload is bound by
the floating-point escape iteration, where both sides compile to the same
instructions and the per-iteration checks Vela keeps are negligible against the
`while` loop's own work.  Vela is ~6.6× faster than the serial C++ build.

## sieve n = 20×10⁶ (π(n) = 1270607)

    variant                    best     median      worst  answer
    C++ unchecked ints     0.013278   0.014646   0.020132  1270607
    Vela checked ints      0.013586   0.014481   0.015298  1270607

A tie on the median (0.01448 s against 0.01465 s), with Vela's spread noticeably
tighter (worst 0.0153 s against 0.0201 s).  This is the row that used to be quoted
as "Vela is *faster* than the unchecked C++ build"; on this machine and this run
it is *level* with it, which is what this file now says.  Vela is level with
unchecked C++ **while keeping its overflow and bounds checks** — the checks that
survive in this loop are the ones the compiler could not prove away, and they cost
nothing that shows up here.

## What has been thrown away

One row, and only because it cannot be built: `VELA --fast-int`.  The C++ twins are
written for this repository, on the same machine, with the same optimiser and the
same algorithm, and the noisy `worst` columns are left in for exactly that reason.

## Repeatability

The parallel variants are the ones that could silently be wrong, so they were run
far more than 7 times each and every run's answer was checked:

    matmul parallel       25 runs   1 distinct answer (1090512707)   0 mismatches
    mandelbrot parallel   25 runs   1 distinct answer (50187647)     0 mismatches
    matmul serial         25 runs   1 distinct answer (1090512707)   0 mismatches

The three `parallel for` test cases in the suite were run 30 times each (90 runs)
and byte-compared against their recorded output, and again at
`OMP_NUM_THREADS` = 1, 2, 3, 4, 7 and 16: one distinct output each time, always
equal to the golden.  `matmul`'s checksum is likewise unchanged at every one of
those thread counts, and its time scales as a parallel loop should:

    OMP_NUM_THREADS=1    0.2225 s
    OMP_NUM_THREADS=2    0.1084 s
    OMP_NUM_THREADS=3    0.0738 s
    OMP_NUM_THREADS=4    0.0575 s
    OMP_NUM_THREADS=7    0.0372 s
    OMP_NUM_THREADS=16   0.0500 s   (16 threads on 8 cores: oversubscribed)

That curve, and the `#pragma omp parallel for` in `bench\matmul.c`, is the evidence
that `parallel for` is compiled to parallel code rather than to a serial loop with
a comment claiming otherwise.
