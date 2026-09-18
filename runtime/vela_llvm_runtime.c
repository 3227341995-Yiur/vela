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
 *   * **`run_command` and the file calls** (`read_text`, `write_text`, `env`), for
 *     the same reason: the compiler itself is built out of them.
 *   * **the entry symbol.**  The C backend emits `int main(int argc, char **argv)`;
 *     IR needs `i32 @main(i32, i8**)`, unmangled, and the three startup calls in the
 *     order shown at the top of this file.
 */
