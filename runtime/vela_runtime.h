/* vela_runtime.h — Vela 0.1 runtime.
 *
 * Design rules this file obeys, because they are what make Vela's safety
 * claims true rather than aspirational:
 *
 *   1. No operation here has undefined behaviour.  Even the "fast" wrapping
 *      integer helpers go through uint64_t, so overflow is defined and
 *      two's-complement, never UB.  There is no `-fwrapv` needed and no
 *      compiler-dependent behaviour.
 *   2. All heap memory comes from one arena.  There is no free(), so there is
 *      no double-free and no use-after-free.  A function's frame is released
 *      only on that function's exit, and no Vela function may return its own
 *      array (the checker rejects it), so an arena pointer cannot outlive its
 *      frame.
 *   3. A failed check aborts with a message that names the file, line and
 *      reason.  Vela never "continues anyway".
 */
#ifndef VELA_RUNTIME_H
#define VELA_RUNTIME_H

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

/* The two big regions below are *reserved*, not committed, on Windows: a program that
 * uses a kilobyte of arena should cost a kilobyte, and a committed 256 MB arena is
 * 256 MB of working set before `main` runs (measured: 793 MB resident for a GUI demo
 * whose own data is a few hundred kilobytes). */
#if defined(_WIN32)
#include <windows.h>
#define VELA_LAZY_REGIONS 1
#endif
#include <string.h>
#include <ctype.h>
#include <math.h>
#include <time.h>

#if defined(_MSC_VER)
#  include <intrin.h>
#endif

/* ------------------------------------------------------------------ strings */

/* A Vela str carries its length.  There is no NUL terminator to overrun and
 * no way to produce a pointer into the middle of one. */
typedef struct {
    const uint8_t *data;
    int64_t        len;
} vela_str;

/* -------------------------------------------------------------------- panic */

/* The status a Vela program leaves behind when a check fails.
 *
 * `abort()` was the first choice here and it was wrong twice over.  A compiler
 * that refuses a program is a tool reporting a verdict, not a process crashing:
 * on Windows `abort()` raises 0xC0000409, which is an *NTSTATUS*, so a script
 * comparing the exit code sees -1073740791 instead of a number it can test --
 * and it hands the process to Windows Error Reporting, so one batch of rejected
 * test cases turns into a pile of crash handlers instead of output.  A failing
 * check is a normal, expected, entirely representable outcome, and it exits
 * like one. */
#define VELA_PANIC_STATUS 2

static void vela_panic(const char *msg, const char *file, int line)
{
    fflush(stdout);
    fprintf(stderr, "\nvela: panic: %s\n  at %s:%d\n", msg, file, line);
    fflush(stderr);
    exit(VELA_PANIC_STATUS);
}

static void vela_bounds_fail(int64_t idx, int64_t len, const char *file, int line)
{
    char buf[160];
    snprintf(buf, sizeof buf,
             "index %lld out of range for array of length %lld "
             "(the compiler could not prove this index in range)",
             (long long)idx, (long long)len);
    vela_panic(buf, file, line);
}

/* Every indexing site the compiler could not prove in range calls this, and it
 * must keep this name: the emitter inside the *current* compiler binary writes
 *
 *     (vela_bounds_check(i, len, file, line), a[i])
 *
 * — a comma expression whose left operand is the check — so removing or
 * renaming the function here changes one side of an interface whose other side
 * only moves when the toolchain is rebuilt and promoted.  Measured cost of
 * getting that wrong: `LNK2019: unresolved external symbol vela_bounds_check`
 * for every program in the repository, including `examples\hello.vel`.
 *
 * The comparison is done in unsigned arithmetic so that a negative index is
 * caught as well, and the failure itself is `vela_bounds_fail`, which is where
 * the message lives. */
static inline void vela_bounds_check(int64_t idx, int64_t len,
                                     const char *file, int line)
{
    if ((uint64_t)idx >= (uint64_t)len) vela_bounds_fail(idx, len, file, line);
}

/* -------------------------------------------------------------- arena alloc */

typedef struct {
    uint8_t *base;
    size_t   cap;
    size_t   used;
    size_t   committed;   /* how much of `base` is committed; lazy regions only */
} vela_arena;

/* Commits at least `need` bytes, in 1 MB steps, and reports whether `need` is
 * reachable.  On a platform without lazy regions everything is committed already. */
static int vela_arena_commit(vela_arena *a, size_t need)
{
#if defined(VELA_LAZY_REGIONS)
    size_t want;
    if (need <= a->committed) return 1;
    want = (need + ((size_t)1 << 20) - 1) & ~(((size_t)1 << 20) - 1);
    if (want > a->cap) want = a->cap;
    if (!VirtualAlloc((uint8_t *)a->base + a->committed, want - a->committed,
                      MEM_COMMIT, PAGE_READWRITE))
        return 0;
    a->committed = want;
    return 1;
#else
    (void)a;
    (void)need;
    return 1;
#endif
}

static vela_arena vela_global_arena;
static int        vela_arena_ready = 0;

static void vela_arena_init(size_t cap)
{
    if (vela_arena_ready) return;
    if (cap == 0) {
        const char *e = getenv("VELA_ARENA_MB");
        long mb = e ? atol(e) : 256;
        if (mb <= 0) mb = 256;
        cap = (size_t)mb << 20;
    }
#if defined(VELA_LAZY_REGIONS)
    vela_global_arena.base = (uint8_t *)VirtualAlloc(NULL, cap, MEM_RESERVE,
                                                     PAGE_NOACCESS);
#else
    vela_global_arena.base = (uint8_t *)malloc(cap);
#endif
    if (!vela_global_arena.base)
        vela_panic("could not reserve the arena", "<runtime>", 0);
    vela_global_arena.cap  = cap;
    vela_global_arena.used = 0;
    vela_global_arena.committed = 0;
    vela_arena_ready = 1;
}

/* Always 16-byte aligned and always zeroed: "allocated but undefined" does not
 * exist in Vela, which is why a bare array declaration is safe. */
static void *vela_arena_alloc(size_t n)
{
    size_t off = (vela_global_arena.used + 15u) & ~(size_t)15u;
    if (off + n > vela_global_arena.cap) {
        char buf[160];
        snprintf(buf, sizeof buf,
                 "arena exhausted (asked for %llu bytes, %llu of %llu used); "
                 "raise it with VELA_ARENA_MB",
                 (unsigned long long)n,
                 (unsigned long long)vela_global_arena.used,
                 (unsigned long long)vela_global_arena.cap);
        vela_panic(buf, "<runtime>", 0);
    }
    if (!vela_arena_commit(&vela_global_arena, off + n))
        vela_panic("could not commit the arena", "<runtime>", 0);
    void *p = vela_global_arena.base + off;
    memset(p, 0, n);
    vela_global_arena.used = off + n;
    return p;
}

static size_t vela_arena_mark(void)
{
    return vela_global_arena.used;
}

static void vela_arena_release(size_t mark)
{
    if (mark <= vela_global_arena.used) vela_global_arena.used = mark;
}

/* --------------------------------------------------- checked integer math
 *
 * Two different tests meet in these helpers, and keeping them apart is the whole
 * of the correctness here:
 *
 *   * the *intrinsic* one — did the operation wrap?  Asked of the operands,
 *     before the result exists, and exactly: a positive `b` wraps iff
 *     `a > INT64_MAX - b`, a negative one iff `a < INT64_MIN - b`.  Neither
 *     limit expression can overflow because `b`'s sign is known.
 *   * the *proven* one — is the result outside the interval the front end could
 *     prove?  The emitter passes that interval in `lo`/`hi`, and a large `b` can
 *     wrap and still land inside it, which is why both tests exist.  Once the
 *     wrap has been ruled out, `(uint64_t)r` *is* the mathematical result, so
 *     this is a plain compare and nothing can wrap on the way.
 *
 * The original code had only the second test, and with the full range at every
 * call site it was a test no value could fail: `INT64_MAX + 1` wrapped and was
 * returned as an ordinary number — by both `/Od` and `/O2` builds, so it was
 * never an optimizer story, and `vela_bounds_check` was never the mechanism
 * either.  Testing the *operands* is the fix.
 *
 * The names `vela_bounds_check`, `vela_add_range`, `vela_sub_range` and
 * `vela_mul_range` are an interface, not a choice: the compiler binary currently
 * in `selfhost\build\vm.exe` was built from an emitter that calls exactly these,
 * and it stays in place until a rebuild is promoted.  Changing a name here
 * changes one side of that contract and makes every program in the tree
 * unlinkable — `LNK2019: unresolved external symbol vela_bounds_check` for
 * `examples/hello.vel` itself, which is how that mistake was measured once
 * already.  Fix the logic inside them; never rename them from this side. */
static inline int64_t vela_wrapped_too_high(int64_t a, int64_t b) {
    if (b > 0) {
        if (a > (INT64_MAX - b)) {
            return (int64_t)1;
        }
    }
    return (int64_t)0;
}

static inline int64_t vela_wrapped_too_low(int64_t a, int64_t b) {
    /* `a + b < INT64_MIN`.  `b` is negative in this branch, so `INT64_MIN - b`
     * is INT64_MIN plus a magnitude of at most INT64_MAX: it cannot overflow,
     * and the comparison is the whole test.
     *
     * The unsigned form this used to compute was **not equivalent** and refused
     * every ordinary subtraction: `1 - 5` reached it as `a=1, b=-5`, computed
     * `5 < (uint64_t)1 - (uint64_t)INT64_MAX` — that is 5 against 2^63 + 2 — and
     * panicked with "integer overflow (subtraction)".  Measured before the fix:
     * all six of `1 - 5`, `five() - 1`, `0 - five()`, `len(s) - 1`,
     * `bytes_at(s, len(s) - 1)` and a counted-down loop panicked in the compiled
     * binary while the interpreter printed -4, 4, -5, 2, 99 and 2/1/0. */
    if (b < 0) {
        if (a < INT64_MIN - b) {
            return (int64_t)1;
        }
    }
    return (int64_t)0;
}

/* `a - b` has no negation in it on purpose.  Negating `b` first and reusing the
 * addition test is wrong for `b == INT64_MIN` (whose negation is itself), and the
 * two conditions below never compute an intermediate that can overflow:
 * `INT64_MIN + b` with `b > 0` and `INT64_MAX + b` with `b < 0` both stay in
 * range. */
static inline int64_t vela_sub_overflows(int64_t a, int64_t b) {
    if (b > 0) {
        if (a < INT64_MIN + b) {
            return (int64_t)1;
        }
    }
    if (b < 0) {
        if (a > INT64_MAX + b) {
            return (int64_t)1;
        }
    }
    return (int64_t)0;
}

static inline int64_t vela_out_of_range(uint64_t r, int64_t lo, int64_t hi) {
    if ((int64_t)r < lo) {
        return (int64_t)1;
    }
    if ((int64_t)r > hi) {
        return (int64_t)1;
    }
    return (int64_t)0;
}

static inline int64_t vela_add_range(int64_t a, int64_t b,
                                     int64_t lo, int64_t hi,
                                     const char *f, int l)
{
    if (vela_wrapped_too_high(a, b) == 1 || vela_wrapped_too_low(a, b) == 1) {
        vela_panic("integer overflow (addition)", f, l);
    }
    uint64_t r = (uint64_t)a + (uint64_t)b;       /* exact: nothing wrapped */
    if (vela_out_of_range(r, lo, hi) == 1) {
        vela_panic("integer overflow (addition)", f, l);
    }
    return (int64_t)r;
}

static inline int64_t vela_sub_range(int64_t a, int64_t b,
                                     int64_t lo, int64_t hi,
                                     const char *f, int l)
{
    if (vela_sub_overflows(a, b) == 1) {
        vela_panic("integer overflow (subtraction)", f, l);
    }
    uint64_t r = (uint64_t)a - (uint64_t)b;       /* exact: nothing wrapped */
    if (vela_out_of_range(r, lo, hi) == 1) {
        vela_panic("integer overflow (subtraction)", f, l);
    }
    return (int64_t)r;
}

static inline int64_t vela_mul_range(int64_t a, int64_t b,
                                     int64_t lo, int64_t hi,
                                     const char *f, int l)
{
#if defined(_MSC_VER) && defined(_M_X64)
    /* `_mul128` is the exact test: a product whose top half is not the sign
     * extension of the bottom half did not fit, and nothing outside the range is
     * ever refused. */
    int64_t hi64;
    int64_t r = _mul128(a, b, &hi64);
    if (hi64 != (r >> 63)) vela_panic("integer overflow (multiply)", f, l);
#else
    int64_t r = (int64_t)((uint64_t)a * (uint64_t)b);
    if (a != 0 && (r / a) != b) vela_panic("integer overflow (multiply)", f, l);
#endif
    /* The caller's interval, checked the same way the sum's is: only a product
     * that is provably outside it is refused, so `a * b` with `a == 1` or
     * `b == 0` — where the bound arithmetic wraps to garbage — is never called
     * out.  `lo / a` and `hi / a` are exact enough to compare against because
     * the product either crosses the bound or it does not. */
    if (a > 0) {
        if (b > 0) {
            if (b > hi / a) {
                if ((uint64_t)((uint64_t)b * (uint64_t)a) > (uint64_t)hi) {
                    vela_panic("integer overflow (multiply)", f, l);
                }
            }
        } else {
            if (b < lo / a) {
                if ((uint64_t)0 - (uint64_t)((uint64_t)b * (uint64_t)a) >
                    (uint64_t)0 - (uint64_t)lo) {
                    vela_panic("integer overflow (multiply)", f, l);
                }
            }
        }
    } else if (a < 0) {
        int64_t q_lo = lo / a;
        int64_t q_hi = hi / a;
        if (q_lo > q_hi) {
            int64_t sw = q_lo;
            q_lo = q_hi;
            q_hi = sw;
        }
        if (b > q_hi) {
            if ((uint64_t)0 - (uint64_t)((uint64_t)a * (uint64_t)b) >
                (uint64_t)0 - (uint64_t)lo) {
                vela_panic("integer overflow (multiply)", f, l);
            }
        } else if (b < q_lo) {
            if ((uint64_t)((uint64_t)a * (uint64_t)b) > (uint64_t)hi) {
                vela_panic("integer overflow (multiply)", f, l);
            }
        }
    }
    return (int64_t)((uint64_t)a * (uint64_t)b);
}

static inline int64_t vela_div_int(int64_t a, int64_t b, const char *f, int l)
{
    if (b == 0) vela_panic("division by zero", f, l);
    if (a == INT64_MIN && b == -1) vela_panic("integer overflow (division)", f, l);
    return a / b;
}

/* Vela's '//' and '%' follow Python's floor semantics, not C's truncation, so
 * that porting Python code cannot silently change meaning.  When the compiler
 * can prove the divisor is non-zero (the usual case for a literal like 2), the
 * check disappears and only the sign fix-up remains. */
static inline int64_t vela_floor_div_u(int64_t a, int64_t b)
{
    int64_t q = a / b, r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) q--;
    return q;
}

static inline int64_t vela_floor_mod_u(int64_t a, int64_t b)
{
    int64_t r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) r += b;
    return r;
}

static inline int64_t vela_floor_div(int64_t a, int64_t b, const char *f, int l)
{
    if (b == 0) vela_panic("division by zero", f, l);
    if (a == INT64_MIN && b == -1) vela_panic("integer overflow (division)", f, l);
    return vela_floor_div_u(a, b);
}

static inline int64_t vela_floor_mod(int64_t a, int64_t b, const char *f, int l)
{
    if (b == 0) vela_panic("remainder by zero", f, l);
    return vela_floor_mod_u(a, b);
}

static inline int64_t vela_neg_int(int64_t a, const char *f, int l)
{
    if (a == INT64_MIN) vela_panic("integer overflow (negation)", f, l);
    return -a;
}

static inline int64_t vela_abs_int(int64_t a, const char *f, int l)
{
    return a < 0 ? vela_neg_int(a, f, l) : a;
}

static inline int64_t vela_pow_int(int64_t base, int64_t exp,
                                   const char *f, int l)
{
    if (exp < 0) vela_panic("negative exponent for integer **", f, l);
    int64_t result = 1;
    int64_t b = base, e = exp;
    while (e > 0) {
        if (e & 1) result = vela_mul_range(result, b, INT64_MIN, INT64_MAX, f, l);
        e >>= 1;
        if (e) b = vela_mul_range(b, b, INT64_MIN, INT64_MAX, f, l);
    }
    return result;
}

static inline int64_t vela_shl_int(int64_t a, int64_t b, const char *f, int l)
{
    if (b < 0 || b > 63) vela_panic("shift count out of range 0..63", f, l);
    return (int64_t)((uint64_t)a << (unsigned)b);
}

static inline int64_t vela_shr_int(int64_t a, int64_t b, const char *f, int l)
{
    if (b < 0 || b > 63) vela_panic("shift count out of range 0..63", f, l);
    return a >> (int)b;
}

/* A value going into a slot narrower than the arithmetic that produced it (SPEC
 * 3.1: a binding is deliberately not type-checked, so `mut x: i32 = big` with a
 * `big` wider than an `i32` is a legal program).  What it must not be is *two*
 * programs: unchecked, C's implicit conversion kept 705032704 and the interpreter
 * kept 5000000000 for the same source, which SAFETY.md §3 recorded as
 * `hole_narrowing_binding_i32`.  So the store is checked, at run time, with the
 * message the checker uses for the case it can see (`ck_narrow_store`), and the
 * language's answer is the refusal rather than a guess at the widening.
 *
 * The value that fits is untouched: this is not a conversion, it is the refusal
 * to pretend one happened.  `int64_t` in and out so the caller's own narrowing
 * store still does the truncation C already did for the in-range case. */
static void vela_narrow_fail(int64_t v, const char *what, const char *f, int l)
{
    char buf[160];
    snprintf(buf, sizeof buf, "%lld does not fit in %s", (long long)v, what);
    vela_panic(buf, f, l);
}

static inline int64_t vela_fit_u8(int64_t v, const char *f, int l)
{
    if (v < 0 || v > 255) vela_narrow_fail(v, "u8", f, l);
    return v;
}

static inline int64_t vela_fit_i32(int64_t v, const char *f, int l)
{
    if (v < -2147483648LL || v > 2147483647LL) vela_narrow_fail(v, "i32", f, l);
    return v;
}

/* "Fast" mode: still defined behaviour, still two's-complement, just not
 * checked.  This is NOT an escape hatch to undefined behaviour the way Rust's
 * `unsafe` or C's signed overflow are. */
static inline int64_t vela_wrap_add(int64_t a, int64_t b)
{ return (int64_t)((uint64_t)a + (uint64_t)b); }

static inline int64_t vela_wrap_sub(int64_t a, int64_t b)
{ return (int64_t)((uint64_t)a - (uint64_t)b); }

static inline int64_t vela_wrap_mul(int64_t a, int64_t b)
{ return (int64_t)((uint64_t)a * (uint64_t)b); }

/* ------------------------------------------------------------------- printing */

static void vela_print_i64(int64_t v)  { printf("%lld", (long long)v); }
static void vela_print_f64(double v)   { printf("%.6f", v); }
static void vela_print_bool(int v)     { fputs(v ? "True" : "False", stdout); }
static void vela_print_str(vela_str s) { fwrite(s.data, 1, (size_t)s.len, stdout); }
static void vela_print_sep(void)       { fputc(' ', stdout); }
static void vela_print_nl(void)        { fputc('\n', stdout); }

/* Unseparated output, so a Vela program can implement its own formatting —
 * this is what the self-hosted interpreter uses to print a value exactly the
 * way the compiled form of the same program would. */
static void vela_emit_str(vela_str s)  { fwrite(s.data, 1, (size_t)s.len, stdout); }
static void vela_emit_int(int64_t v)   { printf("%lld", (long long)v); }
static void vela_emit_float(double v)  { printf("%.6f", v); }
static void vela_emit_nl(void)         { fputc('\n', stdout); }

/* Diagnostics go to stderr, so a compiler written in Vela can complain about
 * a program without corrupting that program's output. */
static void vela_warn_str(vela_str s)  { fflush(stdout); fwrite(s.data, 1, (size_t)s.len, stderr); }
static void vela_warn_int(int64_t v)   { fflush(stdout); fprintf(stderr, "%lld", (long long)v); }
static void vela_warn_nl(void)         { fflush(stdout); fputc('\n', stderr); fflush(stderr); }

static int vela_str_eq(vela_str a, vela_str b)
{
    if (a.len != b.len) return 0;
    if (a.len == 0) return 1;
    return memcmp(a.data, b.data, (size_t)a.len) == 0;
}

/* Wall-clock seconds, so a Vela program can time itself the way a C++ program
 * uses <chrono>.  Uses timespec_get, which is C11 and needs no platform
 * headers, so the runtime stays portable. */
static double vela_now(void)
{
    struct timespec ts;
    timespec_get(&ts, TIME_UTC);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

static inline vela_str vela_str_lit(const char *p, int64_t n)
{
    vela_str s; s.data = (const uint8_t *)p; s.len = n; return s;
}

/* -------------------------------------------------------------- host I/O
 *
 * A language that intends to compile itself has to be able to read its own
 * source, so Vela exposes host I/O as four checked primitives instead of a
 * FILE type: read a whole file, write a whole file, look at a byte, take a
 * slice.  There are no handles to leak, no buffers to size, and no ownership
 * question to get wrong.
 *
 * Strings from the host are allocated in a *permanent* arena that frame
 * release never touches.  That is what makes `str` safely returnable and
 * `substr` safely non-owning: a str is either a static literal or permanent
 * memory, so no Vela string can dangle. */

static vela_arena vela_persist_region;
static int         vela_persist_ready = 0;

static void *vela_persist_alloc(size_t n)
{
    size_t off;
    if (!vela_persist_ready) {
        const char *e = getenv("VELA_PERSIST_MB");
        long mb = e ? atol(e) : 512;
        if (mb <= 0) mb = 512;
#if defined(VELA_LAZY_REGIONS)
        vela_persist_region.base = (uint8_t *)VirtualAlloc(NULL, (size_t)mb << 20,
                                                           MEM_RESERVE,
                                                           PAGE_NOACCESS);
#else
        vela_persist_region.base = (uint8_t *)malloc((size_t)mb << 20);
#endif
        if (!vela_persist_region.base)
            vela_panic("could not reserve the string region", "<runtime>", 0);
        vela_persist_region.cap  = (size_t)mb << 20;
        vela_persist_region.used = 0;
        vela_persist_region.committed = 0;
        vela_persist_ready = 1;
    }
    off = (vela_persist_region.used + 15u) & ~(size_t)15u;
    if (off + n > vela_persist_region.cap) {
        char buf[160];
        snprintf(buf, sizeof buf,
                 "string region exhausted (%llu of %llu bytes used); raise it "
                 "with VELA_PERSIST_MB",
                 (unsigned long long)vela_persist_region.used,
                 (unsigned long long)vela_persist_region.cap);
        vela_panic(buf, "<runtime>", 0);
    }
    if (!vela_arena_commit(&vela_persist_region, off + n))
        vela_panic("could not commit the string region", "<runtime>", 0);
    void *p = vela_persist_region.base + off;
    memset(p, 0, n);
    vela_persist_region.used = off + n;
    return p;
}

static vela_str vela_str_empty(void)
{
    vela_str s; s.data = NULL; s.len = 0; return s;
}

static int    vela_argc_static = 0;
static char **vela_argv_static = NULL;

static void vela_set_args(int argc, char **argv)
{ vela_argc_static = argc; vela_argv_static = argv; }

static int64_t vela_argc(void) { return (int64_t)vela_argc_static; }

static vela_str vela_arg(int64_t i, const char *file, int line)
{
    if (i < 0 || i >= (int64_t)vela_argc_static)
        vela_bounds_fail(i, (int64_t)vela_argc_static, file, line);
    return vela_str_lit(vela_argv_static[i], (int64_t)strlen(vela_argv_static[i]));
}

static int64_t vela_bytes_at(vela_str s, int64_t i, const char *file, int line)
{
    if (i < 0 || i >= s.len) vela_bounds_fail(i, s.len, file, line);
    return (int64_t)s.data[i];
}

/* One byte of a string, as a one-byte `str`.  Vela has no character type: a
 * character has always been a one-byte string (that is what `char_at` returns and
 * what `substr` produces), so `s[i]` produces exactly that and the two spellings
 * of the same operation cannot drift apart.  Out of range is a panic, not an empty
 * string: an index the program got wrong is a bug, and the array path next to it
 * has always behaved this way. */
static vela_str vela_str_at(vela_str s, int64_t i, const char *file, int line)
{
    if (i < 0 || i >= s.len)
        vela_panic("index out of range for this string", file, line);
    return vela_str_lit((const char *)s.data + i, 1);
}

/* `to_str(v)` for an int and for a bool.  A `str` needs no conversion, so the
 * emitters pass one straight through and these two are the whole of the builtin.
 * The text lands in the permanent region like every other str, so it can be
 * concatenated, compared and returned; `vela_print_i64` writes to stdout instead,
 * which is why this cannot be the same function.
 *
 * A *float* is deliberately absent: Vela's one float formatting rule is
 * `str.to_str_f(x, decimals)`, where the caller names the precision.  `%f` here
 * would be a second rule, and the interpreter would have to reproduce that
 * rounding in Vela to stay equal to it -- a difference waiting to be found. */
static vela_str vela_int_text(int64_t v)
{
    char buf[32];
    int n = snprintf(buf, sizeof buf, "%lld", (long long)v);
    uint8_t *mem;
    if (n <= 0) return vela_str_empty();
    mem = (uint8_t *)vela_persist_alloc((size_t)n + 1);
    memcpy(mem, buf, (size_t)n);
    return vela_str_lit((const char *)mem, (int64_t)n);
}

/* "True"/"False" and not "true"/"false": `print` spells a bool that way
 * (`vela_print_bool`), and `to_str` has to agree with `print` about every value it
 * can be given or the language has two spellings of one value. */
static vela_str vela_bool_text(int64_t b)
{
    return b ? vela_str_lit("True", 4) : vela_str_lit("False", 5);
}

/* A slice, not a copy: safe because every str lives in static or permanent
 * memory, so a slice can never outlive what it points at. */
static vela_str vela_substr(vela_str s, int64_t a, int64_t b)
{
    if (a < 0) a = 0;
    if (b > s.len) b = s.len;
    if (b < a) b = a;
    return vela_str_lit((const char *)s.data + a, b - a);
}

/* Concatenation, in the one place it is implemented.  `a + b` on two strings and
 * the `concat(a, b)` builtin are one operation and both arrive here: the two back
 * ends lower `+` to this exact call, so there is no second implementation to
 * drift from this one.  The name stays because the *cost* stays — this allocates
 * in the permanent region below, which is why a `parallel for` may not concatenate
 * at all (the checker refuses it by name, in both spellings, for this reason).
 *
 * The result lives in the permanent region, exactly like a literal or the
 * result of read_text, so nothing here can dangle and nothing has to be freed. */
static vela_str vela_concat(vela_str a, vela_str b)
{
    uint8_t *mem;
    if (a.len < 0 || b.len < 0) return vela_str_empty();
    mem = (uint8_t *)vela_persist_alloc((size_t)a.len + (size_t)b.len + 1);
    if (a.len > 0) memcpy(mem, a.data, (size_t)a.len);
    if (b.len > 0) memcpy(mem + a.len, b.data, (size_t)b.len);
    return vela_str_lit((const char *)mem, a.len + b.len);
}

/* A NUL-terminated copy of a Vela string, for a C function that takes a
 * `const char *`.  `extern c` spells a `str` parameter as `const char *`, and every
 * such argument is wrapped in this call by the emitters: a `str` is a byte range,
 * and the byte after it belongs to whatever else lives in that buffer. */
static const char *vela_cstr(vela_str s)
{
    uint8_t *mem;
    if (s.len < 0) return "";
    mem = (uint8_t *)vela_persist_alloc((size_t)s.len + 1);
    if (s.len > 0) memcpy(mem, s.data, (size_t)s.len);
    mem[s.len] = 0;
    return (const char *)mem;
}

#define VELA_READ_MAX ((size_t)256 << 20)   /* a source file, not a database */

static vela_str vela_read_text(vela_str path)
{
    char buf[4096];
    FILE *f;
    long n;
    uint8_t *mem;
    if (path.len <= 0 || path.len >= (int64_t)sizeof buf) return vela_str_empty();
    memcpy(buf, path.data, (size_t)path.len);
    buf[path.len] = '\0';
    f = fopen(buf, "rb");
    if (!f) return vela_str_empty();
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return vela_str_empty(); }
    n = ftell(f);
    if (n <= 0 || (size_t)n > VELA_READ_MAX) { fclose(f); return vela_str_empty(); }
    rewind(f);
    mem = (uint8_t *)vela_persist_alloc((size_t)n + 1);
    if (fread(mem, 1, (size_t)n, f) != (size_t)n) {
        fclose(f);
        return vela_str_empty();
    }
    fclose(f);
    return vela_str_lit((const char *)mem, (int64_t)n);
}

static int vela_write_text(vela_str path, vela_str body)
{
    char buf[4096];
    FILE *f;
    if (path.len <= 0 || path.len >= (int64_t)sizeof buf) return 0;
    memcpy(buf, path.data, (size_t)path.len);
    buf[path.len] = '\0';
    f = fopen(buf, "wb");
    if (!f) return 0;
    if (body.len > 0 && fwrite(body.data, 1, (size_t)body.len, f) != (size_t)body.len) {
        fclose(f);
        return 0;
    }
    return fclose(f) == 0;
}

/* ---------------------------------------------------------------- the filesystem
 *
 * Three questions the standard library asks the host, each one a name in and a
 * listing or a status out, so that `lib/fs.vel` can be ordinary Vela on top of them.
 *
 * **`const char *` and not `vela_str`**, and that is the whole of why the first
 * version of this block silently answered 0: an `extern c` declaration in Vela writes
 * a `str` parameter as `const char *` and wraps every argument in `vela_cstr`, so a
 * header function taking `vela_str` is called with eight bytes where it reads sixteen
 * -- the length field is somebody else's stack, the length guard fails, and the
 * function answers 0 without ever asking the host.  These take the C string the
 * caller actually passes.
 */
#if defined(_WIN32)
/* The six Win32 calls this file needs, declared here rather than by including
 * <windows.h> -- and that is not tidiness.  This header is textually included in EVERY
 * program the C back end builds, and windows.h brings its own prototypes for the very
 * functions a Vela program may declare `extern c`: measured, after this file started
 * including it, `gui/app.vel` lost its `build-c` with "redefinition; different basic
 * type" on GetSystemMetrics, CreateWindowExA, ShowWindow and IsWindow.  The top of this
 * file keeps its own include -- the arena needs MEM_COMMIT -- so the point here is the
 * one thing that matters: a Vela program's own declarations must not collide with a
 * header it did not ask for.  Hence local prototypes, and no windows.h in this block. */
typedef struct vela_find_data {
    uint32_t attributes;
    uint32_t ft_lo1, ft_hi1, ft_lo2, ft_hi2, ft_lo3, ft_hi3;
    uint32_t size_hi, size_lo;
    uint32_t reserved0, reserved1;
    char name[260];
    char alt_name[14];
} vela_find_data;

__declspec(dllimport) void *FindFirstFileA(const char *pattern, vela_find_data *data);
__declspec(dllimport) int FindNextFileA(void *h, vela_find_data *data);
__declspec(dllimport) int FindClose(void *h);
__declspec(dllimport) int RemoveDirectoryA(const char *path);
__declspec(dllimport) int DeleteFileA(const char *path);
__declspec(dllimport) int MoveFileExA(const char *from, const char *to, uint32_t flags);

/* The entries of `dir`, one per line, `.` and `..` left out, each line ending with a
 * newline so that `lines` reads it with no special case for the last entry.  A
 * directory that does not open answers "" -- the same shape `read_text` uses for a
 * missing file, so `lib/fs.vel` says "either it is empty or it is not there" in one
 * place instead of pretending the runtime distinguishes them. */
static vela_str vela_list_dir(const char *dir)
{
    char pattern[4096];
    vela_find_data fd;
    HANDLE h;
    size_t used = 0;
    size_t n;
    int64_t total = 0;
    char *big;
    if (dir == NULL || *dir == '\0') return vela_str_empty();
    n = strlen(dir);
    if (n + 3 >= sizeof pattern) return vela_str_empty();
    memcpy(pattern, dir, n);
    pattern[n] = '\\';
    pattern[n + 1] = '*';
    pattern[n + 2] = '\0';
    h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return vela_str_empty();
    do {
        size_t nl = strlen(fd.name);
        if (nl == 1 && fd.name[0] == '.') continue;
        if (nl == 2 && fd.name[0] == '.' && fd.name[1] == '.') continue;
        total += (int64_t)nl + 1;
    } while (FindNextFileA(h, &fd));
    FindClose(h);
    if (total <= 0) return vela_str_empty();
    big = (char *)vela_persist_alloc((size_t)total + 1);
    h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return vela_str_empty();
    do {
        size_t nl = strlen(fd.name);
        if (nl == 1 && fd.name[0] == '.') continue;
        if (nl == 2 && fd.name[0] == '.' && fd.name[1] == '.') continue;
        memcpy(big + used, fd.name, nl);
        used += nl;
        big[used] = '\n';
        used += 1;
    } while (FindNextFileA(h, &fd));
    FindClose(h);
    big[used] = '\0';
    return vela_str_lit((const char *)big, (int64_t)used);
}

/* 1 when the entry is gone.  A directory is removed only when it is empty: a
 * recursive delete is a policy with consequences, and it belongs to the program that
 * wants it. */
static int64_t vela_file_remove(const char *path)
{
    if (path == NULL || *path == '\0') return 0;
    if (RemoveDirectoryA(path)) return 1;
    return DeleteFileA(path) ? 1 : 0;
}

/* 1 when the rename happened.  `MoveFileExA` with no flags refuses to overwrite, which
 * is the behaviour a Vela program wants by default: a rename that silently replaced a
 * file would be data loss with a friendly return value. */
static int64_t vela_file_rename(const char *from, const char *to)
{
    if (from == NULL || to == NULL) return 0;
    if (*from == '\0' || *to == '\0') return 0;
    return MoveFileExA(from, to, 0) != 0 ? 1 : 0;
}

#else

/* The same three answers on a host without the Win32 find API.  They answer "nothing"
 * and "no", and `lib/fs.vel` documents that a listing is a host question: a stub that
 * pretended to succeed would be the silent failure this project keeps refusing. */
static vela_str vela_list_dir(const char *dir) { (void)dir; return vela_str_empty(); }
static int64_t vela_file_remove(const char *path) { (void)path; return 0; }
static int64_t vela_file_rename(const char *from, const char *to)
{ (void)from; (void)to; return 0; }

#endif

/* The same three under the names the emitted IR calls.  A program built by the C back
 * end includes this header, so these `static` ones are its definitions; a program built
 * through LLVM links `vela_llvm_runtime.obj`, which exports the same names.  One
 * spelling, two link routes -- the rule the compiler's own shim follows. */
#ifndef VELA_NO_LLVM_WRAPPERS
static vela_str vela_llvm_list_dir(const char *dir) { return vela_list_dir(dir); }
static int64_t vela_llvm_file_remove(const char *path) { return vela_file_remove(path); }
static int64_t vela_llvm_file_rename(const char *from, const char *to)
{ return vela_file_rename(from, to); }
#endif

/* ---------------------------------------------------------------- raw memory
 *
 * The four questions a program needs to touch memory it allocated itself: get a block,
 * give it back, read a 64-bit word, write one.  Everything here takes and answers
 * `int64_t` on purpose -- a Vela `int` is 64 bits and an `extern c` declaration can only
 * promise a scalar -- so the pointer travels as an integer the program cannot dereference
 * by accident, and `lib/mem.vel` is the only place that turns it into a value.
 *
 * These are wrappers and not the CRT's own names, for a reason that cost a build to learn:
 * declaring `extern c def malloc(n: int) -> int` in a Vela program collides with
 * <stdlib.h>'s prototype in the generated C --
 *
 *     error C2040: 'malloc': 'int64_t (int64_t)' differs in levels of indirection
 *     from 'void *(size_t)'
 *
 * -- because the header is included in every program.  A Vela-shaped name cannot collide
 * with anything, and the casts happen here, once, instead of in every program that wants
 * to read a word of memory.
 */
#ifndef VELA_NO_MEM_WRAPPERS
static int64_t vela_alloc(int64_t n)
{
    void *p;
    if (n <= 0) return 0;
    p = malloc((size_t)n);
    return (int64_t)(intptr_t)p;
}

/* Answers 0 and does nothing for a null pointer: freeing null is legal in C, and a Vela
 * program that loses track of a block should not lose the process with it. */
static int64_t vela_release(int64_t p)
{
    if (p != 0) free((void *)(intptr_t)p);
    return 0;
}

static int64_t vela_load_i64(int64_t p)
{
    if (p == 0) return 0;
    return *(int64_t *)(intptr_t)p;
}

/* 1 when the word was written, 0 for a null pointer. */
static int64_t vela_store_i64(int64_t p, int64_t v)
{
    if (p == 0) return 0;
    *(int64_t *)(intptr_t)p = v;
    return 1;
}

/* A byte, for the buffers a socket or a file format hands over: reading one word at a
 * time out of a byte stream is the shape a parser wants, and it needs the byte. */
static int64_t vela_load_u8(int64_t p)
{
    if (p == 0) return 0;
    return (int64_t)(*(unsigned char *)(intptr_t)p);
}

static int64_t vela_store_u8(int64_t p, int64_t v)
{
    if (p == 0) return 0;
    *(unsigned char *)(intptr_t)p = (unsigned char)(v & 0xFF);
    return 1;
}

/* `memmove` and not `memcpy`: overlapping copies are the caller's business, and a
 * segfault is a worse answer than the right one.  Returns the destination. */
static int64_t vela_copy(int64_t dst, int64_t src, int64_t n)
{
    if (dst == 0 || src == 0 || n <= 0) return 0;
    memmove((void *)(intptr_t)dst, (void *)(intptr_t)src, (size_t)n);
    return dst;
}
#endif

/* One host question: what is this named environment variable set to, if
 * anything?  A build driver has to know which host it is on and which C
 * compiler the user wants, and the answer to both is in the environment.
 * Unset and empty are the same answer here — the empty string — because Vela
 * has no `None` to give back and a false "it was set to nothing" distinction
 * would be worse than losing it. */
static vela_str vela_env(vela_str name)
{
    char buf[256];
    const char *v;
    if (name.len <= 0 || name.len >= (int64_t)sizeof buf) return vela_str_empty();
    memcpy(buf, name.data, (size_t)name.len);
    buf[name.len] = '\0';
    v = getenv(buf);
    if (!v) return vela_str_empty();
    /* the environment block outlives every call, so this is permanent memory
     * in the same sense a literal is: nothing here can dangle */
    return vela_str_lit(v, (int64_t)strlen(v));
}

/* The one host capability that is not about data: run a command and report
 * whether it worked.  It exists for exactly one reason — a self-hosting
 * compiler that has just written C has to be able to hand that C to a C
 * compiler, and Vela has no way to start a process (DESIGN 9.4).  Without it,
 * `vm.exe build file.vel` would be impossible without a Python driver around
 * the compiler, which is the thing stage 4 deletes.
 *
 * What it is not: not FFI, not a handle, not a pointer, not a library loader.
 * It hands back one int and keeps no state, the command's own output goes
 * straight to this process's stdout/stderr uncaptured, and 0 means the command
 * reported success.  Safe Rust has `std::process::Command`; this is the same
 * kind of surface, reduced to its smallest useful shape. */
static int64_t vela_run_command(vela_str cmd)
{
    char buf[4096];
    int rc;
    if (cmd.len <= 0 || cmd.len >= (int64_t)sizeof buf) return -1;
    memcpy(buf, cmd.data, (size_t)cmd.len);
    buf[cmd.len] = '\0';
    fflush(stdout);
    fflush(stderr);
    rc = system(buf);
    fflush(stdout);
    return (int64_t)rc;
}

/* ------------------------------------------------------------ string table
 *
 * Vela has no pointers, no generics and no heap objects, so a Vela program
 * that must keep strings in a fixed-size integer array — a lexer, say — needs
 * a way to name a string with an int.  That is all this table is: intern()
 * turns a string into a stable handle, interned() turns the handle back.
 *
 * The table is content-addressed, so equal strings share a handle and handle
 * equality is string equality.  Handles last for the life of the program. */

#define VELA_INTERN_CAP 8192
static vela_str vela_intern_tab[VELA_INTERN_CAP];
static int64_t  vela_intern_n = 0;

static int64_t vela_intern(vela_str s)
{
    int64_t i;
    uint8_t *mem;
    for (i = 0; i < vela_intern_n; i++)
        if (vela_str_eq(vela_intern_tab[i], s)) return i;
    if (vela_intern_n >= VELA_INTERN_CAP)
        vela_panic("string table full", "<runtime>", 0);
    mem = (uint8_t *)vela_persist_alloc((size_t)s.len + 1);
    if (s.len > 0) memcpy(mem, s.data, (size_t)s.len);
    vela_intern_tab[vela_intern_n] = vela_str_lit((const char *)mem, s.len);
    vela_intern_n += 1;
    return vela_intern_n - 1;
}

static vela_str vela_interned(int64_t h, const char *file, int line)
{
    if (h < 0 || h >= vela_intern_n)
        vela_bounds_fail(h, vela_intern_n, file, line);
    return vela_intern_tab[h];
}

static int vela_hexval(uint8_t c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if ((c | 32) >= 'a' && (c | 32) <= 'f') return (c | 32) - 'a' + 10;
    return -1;
}

/* One pass over a literal, writing the bytes it denotes.  This exists so that
 * a Vela-written lexer does not have to build strings itself: it hands the raw
 * literal over and gets the real text back. */
static vela_str vela_unescape(vela_str s)
{
    uint8_t *mem = (uint8_t *)vela_persist_alloc((size_t)s.len + 1);
    int64_t i = 0, o = 0;
    while (i < s.len) {
        uint8_t c = s.data[i];
        if (c != '\\' || i + 1 >= s.len) { mem[o++] = c; i++; continue; }
        i++;
        c = s.data[i++];
        switch (c) {
        case 'n': mem[o++] = '\n'; break;
        case 't': mem[o++] = '\t'; break;
        case 'r': mem[o++] = '\r'; break;
        case '0': mem[o++] = '\0'; break;
        case 'a': mem[o++] = '\a'; break;
        case 'b': mem[o++] = '\b'; break;
        case 'f': mem[o++] = '\f'; break;
        case 'v': mem[o++] = '\v'; break;
        case '\\': mem[o++] = '\\'; break;
        case '"': mem[o++] = '"'; break;
        case '\'': mem[o++] = '\''; break;
        case 'x': {
            int v = 0, k = 0;
            while (k < 2 && i < s.len) {
                int d = vela_hexval(s.data[i]);
                if (d < 0) break;
                v = v * 16 + d; i++; k++;
            }
            mem[o++] = (uint8_t)v;
            break;
        }
        case 'u': {
            int v = 0, k = 0;
            while (k < 4 && i < s.len) {
                int d = vela_hexval(s.data[i]);
                if (d < 0) break;
                v = v * 16 + d; i++; k++;
            }
            if (v < 0x80) {
                mem[o++] = (uint8_t)v;
            } else if (v < 0x800) {
                mem[o++] = (uint8_t)(0xC0 | (v >> 6));
                mem[o++] = (uint8_t)(0x80 | (v & 0x3F));
            } else {
                mem[o++] = (uint8_t)(0xE0 | (v >> 12));
                mem[o++] = (uint8_t)(0x80 | ((v >> 6) & 0x3F));
                mem[o++] = (uint8_t)(0x80 | (v & 0x3F));
            }
            break;
        }
        default: mem[o++] = c; break;
        }
    }
    return vela_str_lit((const char *)mem, o);
}

/* The same exit the runtime itself uses for a failed check, callable from a
 * Vela program: a program that cannot continue says so and stops.
 *
 * Both panic paths leave with VELA_PANIC_STATUS and deliberately do not crash:
 * the test suite refuses hundreds of programs in a row, and every one of those
 * refusals is an expected verdict about a program, not a fault in this one. */
static void vela_panic_str(vela_str msg)
{
    fflush(stdout);
    fputs("\nvela: panic: ", stderr);
    if (msg.len > 0) fwrite(msg.data, 1, (size_t)msg.len, stderr);
    fputc('\n', stderr);
    fflush(stderr);
    exit(VELA_PANIC_STATUS);
}

/* --------------------------------------------------------------- bootstrap */

static void vela_runtime_init(void)
{
    vela_arena_init(0);
}

/* ------------------------------------------------------------- threads */
/* Real OS threads, and one lock primitive, because that is the smallest set a
 * program can build a thread pool from.
 *
 * The entry point is a Vela **function value** (a `(int) -> None`), and the `int`
 * it is handed is the caller's own argument -- typically the address of a block
 * from `lib\mem.vel`, which is how two threads share anything at all: this language
 * has no globals and no closures, so the shared state is memory both of them were
 * given the address of.
 *
 * These functions are the first in this header that are **not `static`**, and the
 * reason is the second translation unit: `runtime\vela_llvm_runtime.c` includes this
 * header to get one implementation of everything, and the LLVM back end links that
 * object file and calls these symbols by name.  A `static` definition would be
 * invisible to it and the link would fail with an unresolved symbol.  One program is
 * one translation unit, so a non-static definition here is still defined exactly
 * once per binary.
 *
 * The trampoline is not optional: `CreateThread`'s entry is `DWORD WINAPI (LPVOID)`
 * and a Vela function of type `(int) -> None` is `void (*)(int64_t)`.  Casting one
 * to the other is undefined behaviour in the ABI (a different calling convention),
 * so the thread is started at a C function of the right shape which then calls the
 * Vela one.
 *
 * `join` waits for the thread and releases the handle.  A handle is the OS's and is
 * never reused, so joining twice is a refusal rather than a silent success: the
 * second `WaitForSingleObject` on a closed handle is exactly what that would be. */
typedef struct vela_thread_start {
    int64_t fn;
    int64_t arg;
} vela_thread_start;

static DWORD WINAPI vela_thread_body(LPVOID p)
{
    vela_thread_start *s = (vela_thread_start *)p;
    int64_t fn = s->fn;
    int64_t arg = s->arg;
    free(s);
    ((void (*)(int64_t))(intptr_t)fn)(arg);
    return 0;
}

/* 0 when the OS refused to start a thread. */
static int64_t vela_impl_thread_spawn(int64_t fn, int64_t arg)
{
    vela_thread_start *s;
    HANDLE h;
    if (fn == 0) return 0;
    s = (vela_thread_start *)malloc(sizeof(vela_thread_start));
    if (s == NULL) return 0;
    s->fn = fn;
    s->arg = arg;
    h = CreateThread(NULL, 0, vela_thread_body, s, 0, NULL);
    if (h == NULL) {
        free(s);
        return 0;
    }
    return (int64_t)(intptr_t)h;
}

/* 1 when the thread was waited for, 0 for a handle this call could not use. */
static int64_t vela_impl_thread_join(int64_t handle)
{
    HANDLE h = (HANDLE)(intptr_t)handle;
    if (h == NULL) return 0;
    if (WaitForSingleObject(h, INFINITE) != WAIT_OBJECT_0) return 0;
    CloseHandle(h);
    return 1;
}

/* A lock, as an opaque handle.  A CRITICAL_SECTION rather than a mutex object: the
 * same exclusion for a fraction of the cost when there is no contention, and a
 * program that needs the price paid for contention is not one this runtime can make
 * a better call about. */
static int64_t vela_impl_lock_new(void)
{
    CRITICAL_SECTION *cs = (CRITICAL_SECTION *)malloc(sizeof(CRITICAL_SECTION));
    if (cs == NULL) return 0;
    InitializeCriticalSection(cs);
    return (int64_t)(intptr_t)cs;
}

static int64_t vela_impl_lock_enter(int64_t lock)
{
    if (lock == 0) return 0;
    EnterCriticalSection((CRITICAL_SECTION *)(intptr_t)lock);
    return 1;
}

static int64_t vela_impl_lock_leave(int64_t lock)
{
    if (lock == 0) return 0;
    LeaveCriticalSection((CRITICAL_SECTION *)(intptr_t)lock);
    return 1;
}

/* 1 when the lock was released.  A lock still held by another thread is the caller's
 * bug, not this function's, so it is not waited for: a program that frees a lock it
 * does not own is exactly the kind of mistake a refusal cannot fix. */
static int64_t vela_impl_lock_free(int64_t lock)
{
    CRITICAL_SECTION *cs = (CRITICAL_SECTION *)(intptr_t)lock;
    if (cs == NULL) return 0;
    DeleteCriticalSection(cs);
    free(cs);
    return 1;
}

/* --------------------------------------------------------------- futures */
/* `async f(x)` and `await h`: one thread, one result word, one join.
 *
 * The record is the same shape `lib/async.vel` writes by hand, and it lives here so
 * that the two keywords need no library: `async` hands the runtime a function value
 * and its one argument, `await` joins the thread and answers the result.
 *
 * The handle a program carries is the *record*, not the OS handle: joining closes the
 * OS thread and keeps the record readable until `await` reads it, and freeing it is
 * `await`'s job because that is the read that consumes the future. */
typedef struct vela_future_rec {
    int64_t fn;
    int64_t arg;
    int64_t result;
    int64_t handle;
} vela_future_rec;

static DWORD WINAPI vela_future_body(LPVOID p)
{
    vela_future_rec *f = (vela_future_rec *)p;
    int64_t fn = f->fn;
    int64_t arg = f->arg;
    f->result = ((int64_t (*)(int64_t))(intptr_t)fn)(arg);
    return 0;
}


/* The argument record an `async def` with parameters needs.
 *
 * The runtime's thread entry carries exactly one word, and `async f(a, b)` has more than
 * one argument.  So the call site copies them into a heap record whose word 0 is the
 * *function's address* and whose words 1.. are the arguments, then starts the thread on
 * `vela_dispN` -- one dispatcher per arity, which casts the address back to the right
 * signature and calls it.  The dispatcher frees the record, because it is the code that
 * knows the record exists.
 *
 * Written as dispatchers rather than as a wrapper per async function because both back
 * ends can then call the very same runtime entry: the C back end emits `vela_argsN(...)`
 * and the LLVM back end builds the same record with `vela_args_new`/`vela_args_set`. */
static int64_t *vela_impl_args_new(int64_t n)
{
    int64_t *p;
    if (n < 0 || n > 62) return NULL;
    p = (int64_t *)calloc((size_t)(n + 1), sizeof(int64_t));
    return p;
}

static void vela_impl_args_set(int64_t rec, int64_t i, int64_t v)
{
    int64_t *p = (int64_t *)(intptr_t)rec;
    /* -1 is the recorded function's own address (word 0); 0.. are its arguments. */
    if (p == NULL || i < -1 || i > 62) return;
    p[i + 1] = v;
}

/* One call builds the record, so a call site stays an *expression* in both back ends. */
static int64_t vela_args0(int64_t fn)
{
    int64_t r = (int64_t)(intptr_t)vela_impl_args_new(0);
    vela_impl_args_set(r, -1, fn);
    return r;
}

static int64_t vela_args1(int64_t fn, int64_t a)
{
    int64_t r = (int64_t)(intptr_t)vela_impl_args_new(1);
    vela_impl_args_set(r, -1, fn);
    vela_impl_args_set(r, 0, a);
    return r;
}

static int64_t vela_args2(int64_t fn, int64_t a, int64_t b)
{
    int64_t r = (int64_t)(intptr_t)vela_impl_args_new(2);
    vela_impl_args_set(r, -1, fn);
    vela_impl_args_set(r, 0, a);
    vela_impl_args_set(r, 1, b);
    return r;
}

static int64_t vela_args3(int64_t fn, int64_t a, int64_t b, int64_t c)
{
    int64_t r = (int64_t)(intptr_t)vela_impl_args_new(3);
    vela_impl_args_set(r, -1, fn);
    vela_impl_args_set(r, 0, a);
    vela_impl_args_set(r, 1, b);
    vela_impl_args_set(r, 2, c);
    return r;
}

static int64_t vela_args4(int64_t fn, int64_t a, int64_t b, int64_t c, int64_t d)
{
    int64_t r = (int64_t)(intptr_t)vela_impl_args_new(4);
    vela_impl_args_set(r, -1, fn);
    vela_impl_args_set(r, 0, a);
    vela_impl_args_set(r, 1, b);
    vela_impl_args_set(r, 2, c);
    vela_impl_args_set(r, 3, d);
    return r;
}

static void vela_impl_args_free(int64_t p)
{
    if (p != 0) free((int64_t *)(intptr_t)p);
}

/* The thread entries, one per arity.  Each reads the function's address and the
 * arguments out of the record, frees it, and calls -- in that order, so the record is
 * never read after it is freed. */
static int64_t vela_impl_disp0(int64_t p)
{
    int64_t *a = (int64_t *)(intptr_t)p;
    int64_t fn = a[0];
    free(a);
    return ((int64_t (*)(void))(intptr_t)fn)();
}

static int64_t vela_impl_disp1(int64_t p)
{
    int64_t *a = (int64_t *)(intptr_t)p;
    int64_t fn = a[0];
    int64_t x0 = a[1];
    free(a);
    return ((int64_t (*)(int64_t))(intptr_t)fn)(x0);
}

static int64_t vela_impl_disp2(int64_t p)
{
    int64_t *a = (int64_t *)(intptr_t)p;
    int64_t fn = a[0];
    int64_t x0 = a[1];
    int64_t x1 = a[2];
    free(a);
    return ((int64_t (*)(int64_t, int64_t))(intptr_t)fn)(x0, x1);
}

static int64_t vela_impl_disp3(int64_t p)
{
    int64_t *a = (int64_t *)(intptr_t)p;
    int64_t fn = a[0];
    int64_t x0 = a[1];
    int64_t x1 = a[2];
    int64_t x2 = a[3];
    free(a);
    return ((int64_t (*)(int64_t, int64_t, int64_t))(intptr_t)fn)(x0, x1, x2);
}

static int64_t vela_impl_disp4(int64_t p)
{
    int64_t *a = (int64_t *)(intptr_t)p;
    int64_t fn = a[0];
    int64_t x0 = a[1];
    int64_t x1 = a[2];
    int64_t x2 = a[3];
    int64_t x3 = a[4];
    free(a);
    return ((int64_t (*)(int64_t, int64_t, int64_t, int64_t))(intptr_t)fn)(x0, x1, x2, x3);
}

static int64_t vela_impl_future_start(int64_t fn, int64_t arg)
{
    vela_future_rec *f;
    HANDLE h;
    if (fn == 0) return 0;
    f = (vela_future_rec *)malloc(sizeof(vela_future_rec));
    if (f == NULL) return 0;
    f->fn = fn;
    f->arg = arg;
    f->result = 0;
    f->handle = 0;
    h = CreateThread(NULL, 0, vela_future_body, f, 0, NULL);
    if (h == NULL) {
        free(f);
        return 0;
    }
    f->handle = (int64_t)(intptr_t)h;
    return (int64_t)(intptr_t)f;
}

static int64_t vela_impl_future_wait(int64_t rec)
{
    vela_future_rec *f = (vela_future_rec *)(intptr_t)rec;
    int64_t v;
    if (f == NULL) return 0;
    if (f->handle != 0) {
        if (WaitForSingleObject((HANDLE)(intptr_t)f->handle, INFINITE) !=
            WAIT_OBJECT_0) {
            return 0;
        }
        CloseHandle((HANDLE)(intptr_t)f->handle);
        f->handle = 0;
    }
    v = f->result;
    free(f);
    return v;
}

/* How many threads the machine can run at once, which is the only number a pool
 * should size itself by. */
static int64_t vela_impl_cpu_count(void)
{
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (int64_t)si.dwNumberOfProcessors;
}

/* The two shapes this block takes, and why it takes both rather than one.
 *
 * The implementations above are `static` because this header is textually included
 * in every program the C back end builds *and* in `runtime\vela_llvm_runtime.c`, and
 * a non-static definition in both is LNK2005 -- measured, the first time these were
 * written without `static`: seven duplicate-symbol errors from the stage-0 link.
 *
 * So the implementation is static, and each path exposes it:
 *
 *   * the C back end's program includes this header and calls `vela_thread_spawn`,
 *     a thin `static` wrapper defined below (unless the other path is being built);
 *   * `runtime\vela_llvm_runtime.c` defines `VELA_NO_THREAD_WRAPPERS` (the wrappers
 *     below then vanish from that translation unit) and defines the same seven names
 *     as ordinary exported functions that call the static implementation.
 *
 * This is the arrangement the memory block above already uses
 * (`VELA_NO_MEM_WRAPPERS`), for the same reason: one implementation, two spellings of
 * the boundary. */
#ifndef VELA_NO_THREAD_WRAPPERS
static int64_t vela_thread_spawn(int64_t fn, int64_t arg)
{
    return vela_impl_thread_spawn(fn, arg);
}

static int64_t vela_thread_join(int64_t handle)
{
    return vela_impl_thread_join(handle);
}

static int64_t vela_lock_new(void) { return vela_impl_lock_new(); }
static int64_t vela_lock_enter(int64_t lock) { return vela_impl_lock_enter(lock); }
static int64_t vela_lock_leave(int64_t lock) { return vela_impl_lock_leave(lock); }
static int64_t vela_lock_free(int64_t lock) { return vela_impl_lock_free(lock); }
static int64_t vela_cpu_count(void) { return vela_impl_cpu_count(); }
static int64_t vela_future_start(int64_t fn, int64_t arg)
{
    return vela_impl_future_start(fn, arg);
}
static int64_t vela_future_wait(int64_t rec) { return vela_impl_future_wait(rec); }
static int64_t vela_args_new(int64_t n)
{
    return (int64_t)(intptr_t)vela_impl_args_new(n);
}
static void vela_args_free(int64_t p) { vela_impl_args_free(p); }
static int64_t vela_disp0(int64_t p) { return vela_impl_disp0(p); }
static int64_t vela_disp1(int64_t p) { return vela_impl_disp1(p); }
static int64_t vela_disp2(int64_t p) { return vela_impl_disp2(p); }
static int64_t vela_disp3(int64_t p) { return vela_impl_disp3(p); }
static int64_t vela_disp4(int64_t p) { return vela_impl_disp4(p); }
#endif /* VELA_NO_THREAD_WRAPPERS */

#endif /* VELA_RUNTIME_H */
