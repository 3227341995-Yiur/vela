/* runtime/vela_llvm_runtime.c — the runtime the *native* backend links against.
 *
 * The C backend does not need this file.  It emits C that `#include`s
 * `runtime/vela_runtime.h`, so every check, every boundary test and both arenas
 * are inlined into the program it builds.  The LLVM backend cannot do that: it
 * emits IR, IR cannot include a header, and every function in that header is
 * `static inline` — which means a translation unit that includes it gets a private
 * copy and **no symbol to link against**.  So the same semantics have to exist as
 * real symbols, and that is this file: the header's own functions, re-exposed under
 * names the emitted IR can call.
 *
 *     clang-cl /c runtime\vela_llvm_runtime.c /Fo<dir>\vela_llvm_runtime.obj
 *
 * It adds no second implementation of anything.  A check that exists in one
 * backend and not the other is exactly the divergence the differential test hunts
 * for, so there is one implementation — the header's — and both backends call it.
 *
 * ## Every name in this file was read, not remembered
 *
 * Each symbol below was taken from **the C backend's own output**, which is the
 * strongest evidence available: those names appear in generated C that compiles,
 * links and passes the corpus today, so the emitter already depends on their exact
 * spelling.  Two files were read to collect them:
 *
 *     tests/build/arith_basics.c   i64 / f64 / sep / nl, add/sub/mul range,
 *                                  floor_div, floor_mod, set_args, runtime_init
 *     tests/build/strings.c        str_lit, print_str, print_bool, str_eq,
 *                                  and the entry sequence itself
 *
 * The first draft of this file contained `vela_str_from_bytes` — invented rather
 * than read.  That is the mistake this project keeps paying for, in a place where
 * it costs a link error, so the rule here is: if the spelling was not seen, it is
 * not written.
 *
 * Two rules inherited from the header, which this file must not break:
 *
 *   1. **Never `abort()`.**  A failing check calls `vela_panic`, which prints to
 *      stderr and exits with `VELA_PANIC_STATUS` (2).  `abort()` raises a Windows
 *      Error Reporting dialog and turns a batch of refused programs into a pile of
 *      crash handlers; the header records that measurement.
 *   2. **`stderr` is not a global under UCRT.**  It is a macro for
 *      `__acrt_iob_func(2)`, which is why IR that writes a panic message must
 *      declare and call that function instead of declaring `@stderr` the way a
 *      MinGW-targeted emitter would.  This file may use `stderr` directly, because
 *      it is compiled as C exactly like the header is.
 */

#include "vela_runtime.h"

/* `bool` is used below (`vela_llvm_print_bool`, `vela_llvm_str_eq`) and in C it is
 * not a keyword: `cl /std:c11` happens to get it from its own headers, and clang
 * does not.  Measured the first time this file met clang-cl, which is the whole
 * point of building it with the compiler the LLVM backend will use:
 *
 *     vela_llvm_runtime.c(144,27): error: unknown type name 'bool'
 *
 * The header it includes is C, so this file is C, and C's `bool` comes from here. */
#include <stdbool.h>

/* ------------------------------------------------------------------- startup
 *
 * The entry sequence, read off the emitted C and reproduced here because the native
 * backend has to perform the same three steps in the same order:
 *
 *     int main(int argc, char **argv) {
 *         vela_set_args(argc, argv);
 *         vela_runtime_init();
 *         vl_main();
 *         return 0;
 *     }
 *
 * `vela_runtime_init` is the one that matters: it reserves the arena and the
 * persistent string region, so the first string literal or array in the program
 * faults without it.  A native `main` that skips it dies immediately and
 * unhelpfully. */

void vela_llvm_set_args(int argc, char **argv)
{
    vela_set_args(argc, argv);
}

void vela_llvm_runtime_init(void)
{
    vela_runtime_init();
}

/* ------------------------------------------------------- bounds and arithmetic
 *
 * Argument order is the C backend's, because an order that differs between two
 * backends is a wrong answer nobody would catch by reading one of them:
 *
 *     vela_bounds_check(idx, len, file, line)     -> void
 *     vela_add_range(a, b, lo, hi, file, line)    -> int64_t   (sub, mul likewise)
 *     vela_floor_div(a, b, file, line)            -> int64_t   (never C's `/`)
 *
 * `vela_bounds_check` compares **unsigned** (`(uint64_t)idx >= (uint64_t)len`)
 * precisely so that a negative index is caught; the IR for the same test is
 * `icmp uge i64` and must not be "simplified" to a signed compare.  `lo`/`hi` are
 * the front end's interval for the result, and the helpers decide overflow from
 * the operands before adding — a `sub nsw` in the IR would be the same mistake at
 * a different level, because the operand test is what makes the check sound.
 */

void vela_llvm_bounds_check(int64_t idx, int64_t len, const char *file, int line)
{
    vela_bounds_check(idx, len, file, line);
}

int64_t vela_llvm_add_range(int64_t a, int64_t b, int64_t lo, int64_t hi,
                            const char *file, int line)
{
    return vela_add_range(a, b, lo, hi, file, line);
}

/* The narrow-store check, under names the emitted IR can call: the same
 * `vela_fit_u8`/`vela_fit_i32` the C back end writes into its own output, so a
 * store that cannot represent its value stops all three paths with one sentence
 * and one exit status (SAFETY.md §3, `hole_narrowing_binding_i32`). */
int64_t vela_llvm_fit_u8(int64_t value, const char *file, int line)
{
    return vela_fit_u8(value, file, line);
}

int64_t vela_llvm_fit_i32(int64_t value, const char *file, int line)
{
    return vela_fit_i32(value, file, line);
}

/* The two integer helpers the emitted IR had no symbol for, because the header
 * declares them `static inline` and IR cannot call a static.  They are the *same*
 * functions the C back end's output calls (`vela_pow_int`, `vela_abs_int`), so
 * `x ** y` and `abs(x)` mean one thing on both paths -- including the overflow
 * checks they carry, which is the reason a raw `mul`/`neg` instruction was not an
 * acceptable substitute (measured: `abs(-INT64_MIN)` panics "integer overflow
 * (negation)" in the C back end, and `emit_llvm.vel` used to refuse the builtin
 * rather than emit an unchecked instruction). */
int64_t vela_llvm_pow_int(int64_t base, int64_t exp, const char *file, int line)
{
    return vela_pow_int(base, exp, file, line);
}

int64_t vela_llvm_abs_int(int64_t a, const char *file, int line)
{
    return vela_abs_int(a, file, line);
}

/* `<<` and `>>`: the header's own checked shifts (`vela_shl_int` refuses a count outside
 * 0..63, which is why the raw instruction was not an acceptable substitute). */
int64_t vela_llvm_shl_int(int64_t a, int64_t b, const char *file, int line)
{
    return vela_shl_int(a, b, file, line);
}

int64_t vela_llvm_shr_int(int64_t a, int64_t b, const char *file, int line)
{
    return vela_shr_int(a, b, file, line);
}

/* --------------------------------------------------------------- the host surface
 *
 * The builtins a *compiler* cannot do without, and the reason the LLVM path could not
 * build `selfhost/vm.vel` any further: the C back end writes `vela_emit_str(...)`,
 * `vela_warn_str(...)`, `vela_intern(...)` and friends into its output, and those are
 * `static` in `runtime/vela_runtime.h`, so an IR call needs a name.  Each is the
 * header's own function under a `vela_llvm_` name -- the same wrapper rule as every
 * other function in this file, and the reason the numbers a program prints are the
 * same whichever back end built it.
 *
 * A `vela_str` parameter is written *by value* here and passed by the IR as a pointer,
 * which is not a mismatch: a 16-byte struct crosses the Microsoft ABI by reference, so
 * this file's `vela_str s` and the IR's `ptr %s` are the same argument (see
 * `vela_llvm_concat` above, where the header's comment says so at length). */
void vela_llvm_emit_str(vela_str s)  { vela_emit_str(s); }
void vela_llvm_emit_int(int64_t v)   { vela_emit_int(v); }
void vela_llvm_emit_float(double v)  { vela_emit_float(v); }
void vela_llvm_emit_nl(void)         { vela_emit_nl(); }

void vela_llvm_warn_str(vela_str s)  { vela_warn_str(s); }
void vela_llvm_warn_int(int64_t v)   { vela_warn_int(v); }
void vela_llvm_warn_nl(void)         { vela_warn_nl(); }

int64_t vela_llvm_intern(vela_str s) { return vela_intern(s); }

vela_str vela_llvm_interned(int64_t h, const char *file, int line)
{
    return vela_interned(h, file, line);
}

vela_str vela_llvm_unescape(vela_str s) { return vela_unescape(s); }

double vela_llvm_now(void) { return vela_now(); }

vela_str vela_llvm_env(vela_str name) { return vela_env(name); }

int64_t vela_llvm_sub_range(int64_t a, int64_t b, int64_t lo, int64_t hi,
                            const char *file, int line)
{
    return vela_sub_range(a, b, lo, hi, file, line);
}

int64_t vela_llvm_mul_range(int64_t a, int64_t b, int64_t lo, int64_t hi,
                            const char *file, int line)
{
    return vela_mul_range(a, b, lo, hi, file, line);
}

int64_t vela_llvm_floor_div(int64_t a, int64_t b, const char *file, int line)
{
    return vela_floor_div(a, b, file, line);
}

int64_t vela_llvm_floor_mod(int64_t a, int64_t b, const char *file, int line)
{
    return vela_floor_mod(a, b, file, line);
}

/* ------------------------------------------------------------------ output
 *
 * Printing is a **byte** contract, not a formatting preference: the corpus
 * compares a program's stdout with its golden byte for byte.  `vela_print_f64` is
 * what defines "print a float" in this language, and MSVC's stdout is in text mode,
 * so a newline arrives as CR LF — which is why every golden in `tests/golden` holds
 * CR LF and why an emitter that formats its own floats would pass every test until
 * it silently diverged on the one value where its format and this one disagree.
 *
 * `vela_print_sep` is the space *between* the arguments of one `print`, and it is
 * separate from `print_nl` because those are two different decisions the language
 * made.
 */

void vela_llvm_print_i64(int64_t v)   { vela_print_i64(v); }
void vela_llvm_print_f64(double v)    { vela_print_f64(v); }
void vela_llvm_print_str(vela_str s)  { vela_print_str(s); }
void vela_llvm_print_bool(bool b)     { vela_print_bool(b); }
void vela_llvm_print_sep(void)        { vela_print_sep(); }
void vela_llvm_print_nl(void)         { vela_print_nl(); }

/* ------------------------------------------------------------------ strings
 *
 * `str` is a `{ data, len }` pair in **permanent memory** — literals, the string
 * table, and the results of `concat`/`read_text` live in a second arena sized by
 * `VELA_PERSIST_MB`, not in the frame arena — which is why a returned string can
 * never dangle and why nothing here needs a lifetime rule. */

vela_str vela_llvm_str_lit(const char *bytes, int64_t len)
{
    return vela_str_lit(bytes, len);
}

vela_str vela_llvm_str_empty(void)
{
    return vela_str_empty();
}

int64_t vela_llvm_str_len(vela_str s)
{
    return s.len;
}

bool vela_llvm_str_eq(vela_str a, vela_str b)
{
    return vela_str_eq(a, b);
}

/* A slice, not a copy — safe for the same reason: nothing it can point at moves. */
vela_str vela_llvm_substr(vela_str s, int64_t a, int64_t b)
{
    return vela_substr(s, a, b);
}

/* A *named* builtin rather than an operator: `a + b` on two strings is refused by
 * the language, and the capability exists anyway because a compiler that has to
 * name files, command lines and messages cannot be written without it. */
vela_str vela_llvm_concat(vela_str a, vela_str b)
{
    return vela_concat(a, b);
}

/* ---------------------------------------------------------- checked indexing
 *
 * Keeping these as functions rather than emitting the comparison inline is
 * deliberate: the failure message, the exit status and the panic path are the
 * language's, and only one place should decide what an out-of-range index means. */

int64_t vela_llvm_bytes_at(vela_str s, int64_t i, const char *file, int line)
{
    return vela_bytes_at(s, i, file, line);
}

vela_str vela_llvm_arg(int64_t i, const char *file, int line)
{
    return vela_arg(i, file, line);
}

int64_t vela_llvm_argc(void)
{
    return vela_argc();
}

/* -------------------------------------------------------------------- panic */

void vela_llvm_panic(const char *msg, const char *file, int line)
{
    vela_panic(msg, file, line);
}

void vela_llvm_panic_str(vela_str msg)
{
    vela_panic_str(msg);
}

/* ------------------------------------------------------------------ arrays
 *
 * `vela_arena_alloc` is `static` in `runtime/vela_runtime.h` (`vela_runtime.h:125`),
 * so an object emitted by the LLVM back end has no symbol to call for an array --
 * and that one declaration was the whole reason `examples/hello.vel` could not be
 * built by that back end.  The arena is not an implementation detail of the C back
 * end: it *is* what a Vela array is (always 16-byte aligned, always zeroed,
 * released when the frame that asked for it exits), and both back ends have to
 * allocate from the same one or "an array's elements are zero" would be two
 * answers.
 *
 * So this is the same wrapper as every other function in this file: the header's
 * own function, under a name the emitted IR can call.  The signature is a pointer
 * return and a `size_t` argument, which is exactly why the *shim* could not carry
 * it: `extern c` has no pointer type, so `vm.exe` declares it as `ptr(i64)` in the
 * module it builds and never passes it across its own boundary.
 *
 * The size is `elements * sizeof(element)`, computed by the emitter -- the same
 * arithmetic the C back end writes -- so a program's arena use is identical on
 * both back ends. */
void *vela_llvm_arena_alloc(int64_t n)
{
    return vela_arena_alloc((size_t)n);
}

/* ------------------------------------------------------------ the host surface
 *
 * `write_text`, `read_text` and `run_command` are the three builtins a test
 * runner written in Vela cannot do without, and this is the same wrapper as every
 * other function here: the header's own, under a name the emitted IR can call.
 * They were the last three names missing from this file, and the reason is worth
 * one sentence, because it is *not* that they are hard: they were listed under
 * "what is still open" here, which was true when the first program that needed
 * them (`tests/probes/host_roundtrip.vel`) had no emitter support at all.
 *
 * The two shapes are the ABI's, not a choice: a function that returns a `str`
 * returns a 16-byte struct through a hidden pointer, and a function that takes one
 * takes it by reference.  `vela_llvm_str_lit` above is the same shape, and the
 * emitter declares all three the same way.
 *
 * `vela_write_text` answers an `int` in the header and a `bool` in the language
 * (`builtin_kind`, emit.vel:724), which is why the cast is here rather than at a
 * call site: the conversion from "the header's answer" to "the language's answer"
 * belongs in exactly one place. */
vela_str vela_llvm_read_text(vela_str path)
{
    return vela_read_text(path);
}

bool vela_llvm_write_text(vela_str path, vela_str body)
{
    return (bool)vela_write_text(path, body);
}

int64_t vela_llvm_run_command(vela_str cmd)
{
    return vela_run_command(cmd);
}

/* ------------------------------------------------------------ what is still open
 *
 * The real work of phase 1 is the emitter, not this file.  What this file cannot
 * settle, and the emitter must decide by measuring rather than by preference:
 *
 *   * **structs returned by value.**  `vela_str` is a struct and several functions
 *     above return one; the emitter has to declare the same signature.  This header
 *     is the authority on the layout, never a restatement of it in a plan.
 *   * **the string table and `intern`.**  A Vela-written lexer keeps strings in a
 *     fixed-size integer array and names them with `intern`/`interned`; the native
 *     backend needs those too, and the table is a static array inside the header,
 *     so a wrapper is needed before any program uses them.
 *   * **the entry symbol.**  The C backend emits `int main(int argc, char **argv)`;
 *     IR needs `i32 @main(i32, i8**)`, unmangled, and the three startup calls in the
 *     order shown at the top of this file.
 */
