# bench/RESULTS.md — what the harness measured, and how to reproduce it

**English** | [简体中文](RESULTS.zh-CN.md)

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

> **Provenance caveat, added 2026-09-22: the commit named above is not reachable,
> and the revision this block actually describes is `622c686`.**
>
> `git merge-base --is-ancestor a0b2078 HEAD` exits 1, and `git rev-list --all`
> does not contain it — no ref on this machine reaches it.  What lists it is the
> **reflog**, not `--all`: measured 2026-09-22, `git log --all --oneline` matches
> **nothing** for `a0b2078`, while `git log --reflog --oneline` prints `a0b2078
> Correct a wrong performance conclusion, and run the phase-2 spike` (that commit
> is dated 2026-09-19 03:48:09 +0800, and its parent is the reachable `9299967`).
> The reflog is exactly how a reader comes to treat it as history: it is a
> surviving record of a rewritten line of development, not a commit anything can
> be built from.  (An earlier version of this caveat said `git log --all` lists
> it, which is false; the mechanism is stated here by the command that reproduces
> it, because a caveat that explains an error with a command that does not is the
> same defect in a new place.)  So the heading above this block cannot be used the
> way a pin is used: the four quantities it quotes are measurements of a revision
> you cannot check out.
>
> They can, however, all be tied to a commit that *is* reachable — `622c686`
> ("The emitter computes each array index once: 1.058 s -> 0.543 s on matmul"),
> which is an ancestor of `HEAD`:
>
> | the block says | where the same value is recorded |
> |---|---|
> | fixpoint `15EAE445780BAA58…` | `622c686:STATUS.md:34` — "fixpoint `15EAE445780BAA58`, 754867 bytes, seed / gen-1 / gen-2 **byte-identical**" |
> | `vm.c` 754867 bytes | `git cat-file -s 622c686:selfhost/build/vm.c` = **754867** |
> | `vm.exe` 633344 bytes | `git cat-file -s 622c686:selfhost/build/vm.exe` = **633344** |
> | (today, for contrast) | `vm.c` 903676 bytes, `vm.exe` 799232 bytes, fixpoint `0AE26E832296100AC6040B7206697A8A6AA01B99B6254435833BCB7C0E067C58`, all measured on the working tree 2026-09-22 |
>
> Read the block as **the dated record of the revision it describes**, with
> `622c686` as the commit to check it against — not as a pin on `HEAD`, which is
> what the `+` was doing.

## Re-measured 2026-09-22, on the committed compiler

The block above is the record of the revision it names.  This is the same harness run
again against the compiler that is actually committed today, so a reader can tell the two
apart without checking anything out:

* **Compiler**: `selfhost\build\vm.exe` 809,984 bytes, SHA256
  `EA303CF7934918845AFC0A2C62B5BDA5DB3C113E96D68FF8635B11D84A86CC9E`.  The fixpoint gate
  checks C, not the executable: seed / generation 1 / generation 2 were all
  `49629EADB28978FB…`, 921,504 bytes.  Measured while doing this: the `.exe` is **not**
  bit-reproducible across link runs — the same sources have linked both to `8756F256…` and
  to `EA303CF7…` — so the emitted C is the artefact to compare, and a binary hash
  identifies a build rather than a source revision.
* **Command**: `powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 7`.

      variant                    best     median      worst  answer
      C++ serial             0.219428   0.224054   0.241723  1090512707
      C++ restrict           0.227140   0.232425   0.236801  1090512707
      Vela serial            0.542594   0.543302   0.554915  1090512707
      C++ restrict+omp       0.020099   0.029377   0.031453  1090512707
      Vela parallel          0.070122   0.082397   0.085824  1090512707

      C++ serial             0.084126   0.084618   0.084938  50187647    mandelbrot 1600×1200
      C++ omp                0.010849   0.011710   0.014986  50187647
      Vela parallel          0.011098   0.011805   0.012285  50187647

      C++ unchecked ints     0.013066   0.013152   0.013445  1270607     sieve n = 20×10⁶
      Vela checked ints      0.019544   0.019680   0.019747  1270607

  Every variant printed the answer it must, so the rows are comparable.

* **Serial matmul**: `0.543302` against C++'s `0.224054` = **2.42× slower** — the same gap
  the block above records, now on a compiler two revisions later.
* **Parallel matmul**: **3.49× slower by best-of-7** (`0.070122` against `0.020099`) and
  **2.80× by median** (`0.082397` against `0.029377`).  The single figure "3.1×" this file
  used to quote is a ratio that moves by a quarter depending on which statistic is read,
  so both are written down here instead of one.
* **mandelbrot 1600×1200**: `0.011805` against `0.011710` = **a tie**, as recorded.
* **sieve n = 20×10⁶**: `0.019680` against `0.013152` = **1.50× slower**.

**"Faster than C++" is not established on any row**, and this is now the third independent
run saying so.  The cost has one place: Vela's inner loop keeps its checked index
arithmetic, and every row prints the answer it must, so the faster variants are not faster
by being wrong.  Removing that arithmetic by proof is the only lever this project has
(`selfhost/ELISION_PLAN.md`), and until it moves these numbers the claim stays
unestablished.

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
    index once, bounds check also removed    0.514
    index unchecked, bounds check kept       0.177     <- the arithmetic checks are ~0.37
    nothing checked                          0.181
    the C++ twin                             0.227

> **Arithmetic note, added 2026-09-22: two numbers in this ladder were not
> internally consistent, and both are now named.**
>
> * The `0.514` row used to be annotated "(the bounds check is ~0.04)".  Strictly
>   inside this ladder the bounds check is `0.543 − 0.514 = **0.029**`, and the
>   `0.04` figure does not reproduce from any subtraction of the rows above — it is
>   a reading carried in from the 2026-09-19 hand-edited variants (matmul section:
>   `0.543` against `0.514`), and the "~0.04" annotation has been dropped rather
>   than guessed at.  What `0.04` was measuring is not recoverable from this
>   document, so it is not re-asserted here.
> * "the C++ twin `0.227`" disagrees with this file's own twin rows, which read
>   `0.224069` (serial) and `0.231455` (restrict) on the median, and it is the
>   *serial* twin that the comparisons above use everywhere else.  The `0.227` is
>   left as printed because it is part of the dated measurement, not a derived
>   value; the figures a reader should compare against are the table's own.
> * The `~68%` below is arithmetic over this ladder and does check out:
>   `0.543 − 0.177 = 0.366`, and `0.366 / 0.543 = 0.674`.

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
