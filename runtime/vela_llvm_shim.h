/* runtime/vela_llvm_shim.h - the scalar C surface over `llvm-c`.
 *
 * Phase 2 of the LLVM plan is: `vm.exe` carries its own code generator, the way
 * `rustc` carries LLVM, and writes a COFF object itself instead of handing C text
 * to `cl.exe`.  `selfhost/LLVM_PLAN.md` records why a shim has to exist at all:
 * **Vela cannot call `llvm-c` directly.**  `extern c` accepts only the scalar
 * signatures in `emit_ctype` (`int`->int64_t, `float`->double, `bool`->bool,
 * `str`->vela_str, `i32`->int32_t, `u8`->uint8_t, `None`->void), the language has
 * no pointer type, and `llvm-c` is entirely a pointer API -- `LLVMBuildAdd(builder,
 * lhs, rhs, name)`.  This file is the adapter: every argument and every result
 * crosses the boundary as one of those scalar types.
 *
 * ## What that constraint forced, and why the shape is what it is
 *
 * 1. **Handles are `int64`, not pointers.**  There is no pointer type on the Vela
 *    side, so an LLVM thing is named by an opaque integer.  `0` is never a valid
 *    handle and every function that returns one returns `0` on failure.
 * 2. **Everything is validated against a table before it reaches LLVM.**  A raw
 *    pointer cast to `int64` would let a caller hand LLVM garbage, and LLVM's
 *    answer to garbage is a C++ `assert` (in a build with assertions) or
 *    undefined behaviour (in one without) -- neither is a diagnosis.  The
 *    requirement for this layer is the opposite: *every* failure is a returned
 *    status plus a message.  So a handle is an index into a per-module table of
 *    real pointers, with a kind tag (type / value / block / signature / argument
 *    list), and a bad handle is `VSHIM_ERR_ARG` and a sentence.
 * 3. **A handle is never reused, and never silently retargeted.**  A compiled
 *    program produces tens of thousands of values; if a freed slot could be handed
 *    out again, a stale handle would quietly name a different instruction, which is
 *    the one failure mode worse than a crash.  The slot count only grows, a used-up
 *    signature or argument list is *marked* consumed (using it again is an error,
 *    not a surprise), and closing a module frees the whole table at once, so
 *    `program > program > program` in one process leaks nothing.
 * 4. **Signatures and argument lists are built one item at a time.**
 *    `LLVMFunctionType` and `LLVMBuildCall2` take C arrays of pointers, which the
 *    Vela side cannot build.  `vshim_sig_begin`/`vshim_sig_param`/`vshim_sig_finish`
 *    and `vshim_arglist_begin`/`vshim_arglist_add`/`vshim_call` are the same
 *    builders with the array kept on this side of the boundary.
 * 5. **Strings carry their length in and get a NUL on the way to LLVM.**  A `str`
 *    is `{ const uint8_t *data; int64_t len; }` -- it may hold a NUL and need not be
 *    NUL-terminated -- while `LLVMAddFunction` and friends want a `const char *`.
 *    So a name (and a string literal's *bytes*) arrives as a `str` and this file
 *    copies it into a bounded, NUL-terminated buffer, or refuses with a message if
 *    it does not fit.  A string *literal* keeps its exact length: the global is
 *    `[len x i8]` with no added terminator, which is what the runtime's
 *    `vela_llvm_str_lit(ptr, i64 len)` expects and what `selfhost/llvm/m1_probe.ll`
 *    writes by hand today.
 * 6. **The Vela-facing subset is scalar-only, and strings travel through a byte
 *    buffer.**  `str` is deliberately *not* a type the language will accept at an
 *    `extern c` boundary, and the reason is worth writing down where the next person
 *    will read it: a `str` is a length plus a pointer into Vela's own string region,
 *    not a `char *`.  If a `str` parameter were allowed then
 *
 *        extern c def strlen(s: str) -> int
 *
 *    would compile, and the call would pass a 16-byte `vela_str` struct where the
 *    real `strlen` wants a pointer -- silently wrong, with C converting a pointer to
 *    an integer without a word.  Allowing it to save some plumbing would reopen
 *    exactly that hole.  So: every name, every literal's bytes, every output path and
 *    every message coming back goes through the shim-owned byte buffer below, one
 *    `u8` per call.  The `str`-taking and `str`-returning functions are kept and are
 *    *correct* -- a C caller uses them (and the driver in
 *    `tools/llvm-shim-probe.ps1` proves both spellings produce identical bytes) --
 *    but the subset the compiler may declare is the scalar one, and `*_buf` is its
 *    spelling of every function that needed a string.
 *
 * ## The pieces of LLVM this layer depends on, and the ABI facts behind them
 *
 * Measured on this machine (Windows, LLVM 23.1.1, MSVC x64 ABI); the measurements
 * are in `selfhost/llvm/m1_probe.ll`, `selfhost/llvm/phase2_spike.c` and
 * `runtime/vela_llvm_runtime.c`, and they are not re-derived here:
 *
 *   * The target is initialised **before** the target machine is created.  Omitting
 *     that step produces a triple that looks right and a NULL target machine -- the
 *     first trap `phase2_spike.c` records.
 *   * The module triple comes from `LLVMGetDefaultTargetTriple()`, never a
 *     hard-coded string; the data layout is taken from the target machine
 *     (`LLVMCreateTargetDataLayout`) rather than restated, because a data layout
 *     that disagrees with the triple is a wrong answer, not a formatting choice.
 *   * `vela_str` is a 16-byte struct that the Microsoft x86-64 ABI passes **by
 *     reference** and returns **through a hidden pointer**.  `vshim_fn_sret_param`
 *     exists precisely so the emitter can declare the runtime's
 *     `void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)`; the by-value
 *     spelling looks right and does not link.
 *
 * ## The DLL is a real dependency, on purpose
 *
 * The shim links `lib\LLVM-C.lib` and, at run time, needs **`LLVM-C.dll` beside the
 * executable** (or on `PATH`) -- `bin\LLVM-C.dll` is 74,159,616 B.  That is the same
 * kind of dependency `rustc` has on its own LLVM, and the plan's step 5 makes it
 * explicit: `vm.exe` carries the code generator and the DLL sits in the compiler's
 * own directory, so nothing lands in a user's project.  Without the DLL the process
 * fails at load with a Windows error before `main`, which is why the probe script
 * copies it beside its test program instead of relying on `PATH`.
 *
 * ## Naming, and what is deliberately *not* here
 *
 * The prefix is `vshim_`, not `vela_llvm_`.  `runtime/vela_llvm_runtime.c` already
 * owns `vela_llvm_*` -- those are the symbols the *emitted program* calls -- and
 * `vm.exe` links both.  Two families that differ by one word is how this project has
 * produced its worst mistakes; `vshim_` cannot be confused with either.
 *
 * Not implemented yet, each for a reason rather than an oversight:
 *   * **Variadic calls.**  `printf`-style call sites are refused with a message
 *     rather than emitted half-right; `m1_probe.ll` needs none, and the plan's
 *     vararg rules (repeating the marker, `fpext` before a variadic call) are a
 *     small design of their own.
 *   * **Non-host triples.**  One target machine, for the host, created once.  A
 *     cross-compiling `vm.exe` is a different feature.
 *   * **Thread safety.**  One module at a time, one thread; the error slot is
 *     process-wide.  `vm.exe` is a batch compiler.
 *   * **Path encoding.**  A path handed to `vshim_emit_object` is forwarded to
 *     LLVM, which interprets it as UTF-8 and converts it to UTF-16 on Windows.  A
 *     path produced by this machine's ANSI code page (936) can therefore encode
 *     incorrectly.  This is *flagged, not solved*: it needs a measurement with a
 *     non-ASCII directory, not a guess, and the scratch directories the build
 *     actually uses are ASCII.
 *
 * Build (development time only -- the product path never compiles C):
 *
 *     cl /nologo /std:c11 /W3 /I <llvm>\include /I runtime /c runtime\vela_llvm_shim.c
 *
 * See `tools/llvm-shim-probe.ps1` for the differential proof that this API is
 * enough to build the M1 program.
 */
#ifndef VELA_LLVM_SHIM_H
#define VELA_LLVM_SHIM_H

#include <stdbool.h>
#include <stdint.h>

/* `str` is not this file's type to define.  A Vela `str` is `vela_str`, and the
 * layout -- `const uint8_t *data; int64_t len` -- has one source of truth:
 * `runtime/vela_runtime.h`, which the C backend, the emitted IR runtime and the
 * checker all already agree with.  Including it here means the shim's `str`
 * arguments are *literally* the runtime's, so a `vela_str` built by `vela_str_lit`
 * in a test driver and a `str` literal in a Vela program are the same bytes. */
#include "vela_runtime.h"

/* --------------------------------------------------------------- return codes
 *
 * Every fallible function returns one of these as an `i32`, or -- when its result
 * is a handle -- returns `0` and records the same kind of message.  `vshim_last_error`
 * is where the message lives; it is cleared at the *start* of every fallible call,
 * so a message can never be left over from an earlier failure and read as if it
 * belonged to a later success.
 */
enum {
    VSHIM_OK            = 0,  /* the call did what it says */
    VSHIM_ERR_ARG       = 1,  /* bad handle, wrong-kind handle, consumed handle, or a null argument */
    VSHIM_ERR_STATE     = 2,  /* used out of order: no `vshim_open`, a closed module, no target machine */
    VSHIM_ERR_TARGET    = 3,  /* this LLVM cannot produce code for the host triple */
    VSHIM_ERR_LLVM      = 4,  /* an `llvm-c` call returned NULL */
    VSHIM_ERR_VERIFY    = 5,  /* the module does not verify; the message is the verifier's */
    VSHIM_ERR_EMIT      = 6,  /* the object file could not be written; the message is LLVM's */
    VSHIM_ERR_TOO_LONG  = 7,  /* a name, path or IR dump exceeded a fixed cap */
    VSHIM_ERR_NO_MEMORY = 8   /* a shim allocation failed */
};

/* Fixed caps, all of them checked.  They exist because the boundary has no
 * pointers: a buffer that cannot be sized from the other side has to be bounded on
 * this side and reported honestly when it overflows. */
#define VSHIM_MESSAGE_CAP 1024    /* error message, NUL-terminated */
#define VSHIM_NAME_CAP    256     /* an LLVM name: 255 bytes + NUL */
#define VSHIM_PATH_CAP    1024    /* an output path: 1023 bytes + NUL */
#define VSHIM_IR_CAP      65536   /* the whole-module IR dump */
#define VSHIM_MAX_PARAMS  16      /* parameters in one signature */

/* --------------------------------------------------------- comparison kinds
 *
 * `vshim_build_icmp`'s predicate.  The values are this shim's, not LLVM's enum
 * order, so they stay stable across LLVM versions; they are mapped explicitly in
 * the implementation.  The two the language's own checks need are spelled out in
 * `selfhost/LLVM_PLAN.md`: the bounds check is **unsigned** (`icmp uge i64`, so a
 * negative index is caught) and overflow is decided by comparing operands, never by
 * an `nsw`/`nuw` flag. */
enum {
    VSHIM_CMP_EQ  = 0,   /* a == b */
    VSHIM_CMP_NE  = 1,   /* a != b */
    VSHIM_CMP_SLT = 2,   /* a <  b, signed   */
    VSHIM_CMP_SLE = 3,   /* a <= b, signed   */
    VSHIM_CMP_SGT = 4,   /* a >  b, signed   */
    VSHIM_CMP_SGE = 5,   /* a >= b, signed   */
    VSHIM_CMP_ULT = 6,   /* a <  b, unsigned */
    VSHIM_CMP_ULE = 7,   /* a <= b, unsigned */
    VSHIM_CMP_UGT = 8,   /* a >  b, unsigned */
    VSHIM_CMP_UGE = 9    /* a >= b, unsigned -- the bounds check */
};

/* ============================================================== lifecycle ====
 *
 * `vshim_open` must be called before any module exists, and it is the step that
 * "a helpful tutorial usually omits": without it the triple looks right and the
 * target machine is NULL.  It is idempotent, so calling it twice costs nothing.
 */

/* Initialize the host target and create the target machine (host triple,
 * `LLVMCodeGenLevelDefault`, default relocation model and code model -- what
 * `phase2_spike.c` measured).  Returns `VSHIM_OK` or `VSHIM_ERR_TARGET`. */
int32_t vshim_open(void);

/* Dispose the target machine.  Modules already created stay alive but can no
 * longer be emitted; `vshim_open` starts a fresh one.  Called at process exit, or
 * never -- a compiler that exits immediately leaks nothing that matters. */
int32_t vshim_shutdown(void);

/* ================================================================ modules ===
 *
 * One module is one LLVM context, one LLVM module and one IR builder, so the Vela
 * side never has to carry three handles to do one job.  `vshim_module_close` frees
 * all three and invalidates every handle that belonged to them.
 */

/* Open a module named by `name` (a diagnostic name; any bytes, no NUL required,
 * 255 bytes maximum).  Returns the module handle, or `0` with a message. */
int64_t vshim_module_open(vela_str name);

/* Close a module: dispose the builder, the module and the context, drop the handle
 * table.  Every other handle from that module becomes permanently invalid and says
 * so instead of dereferencing freed memory.  Returns `VSHIM_OK`, `VSHIM_ERR_ARG`
 * (not a module handle) or `VSHIM_ERR_STATE` (already closed). */
int32_t vshim_module_close(int64_t module);

/* ============================================================ diagnostics ====
 *
 * A message is not an afterthought here: it is the *only* thing that makes a
 * failure actionable, because the caller cannot inspect LLVM's own error objects.
 */

/* The message for the most recent failure, or an empty `str` if the last fallible
 * call succeeded.  The bytes live in a shim-owned buffer that is valid until the
 * next shim call; copy it if it must outlive that. */
vela_str vshim_last_error(void);

/* The triple every module is built for, as returned by
 * `LLVMGetDefaultTargetTriple` and verified by looking up a target for it.  Empty
 * before `vshim_open`. */
vela_str vshim_host_triple(void);

/* The module as LLVM IR text, for reading and diffing -- the phase-1 habit of
 * being able to *see* the IR, without shipping an `.ll` anywhere near a user's
 * source.  Truncated to `VSHIM_IR_CAP` bytes with a trailing note if the module is
 * larger than that; it is a debugging aid, not a serialization format. */
vela_str vshim_module_ir(int64_t module);

/* ================================================================== types ====
 *
 * Types are per-module (LLVM types belong to a context) and the primitives are
 * created once and reused, so calling `vshim_type_i64` in a loop is cheap and does
 * not grow the handle table.
 */

int64_t vshim_type_void(int64_t module);
int64_t vshim_type_i1(int64_t module);    /* `bool`'s IR type, and an icmp result */
int64_t vshim_type_i8(int64_t module);
int64_t vshim_type_i32(int64_t module);   /* `i32`, e.g. argc and `main`'s return */
int64_t vshim_type_i64(int64_t module);   /* `int` */
int64_t vshim_type_f64(int64_t module);   /* `float` */
int64_t vshim_type_ptr(int64_t module);   /* an opaque pointer, address space 0 */
int64_t vshim_type_str(int64_t module);   /* `{ ptr, i64 }` -- the ABI type of `str` */

/* A signature, built one parameter at a time; see design note 4 at the top of this
 * file.  `vshim_sig_finish` returns a function *type* handle, and consumes the
 * signature handle.  `vshim_sig_abandon` frees a signature that is not going to be
 * finished (an error path in the emitter).  `vshim_sig_param` fails, with a
 * message, past `VSHIM_MAX_PARAMS`. */
int64_t vshim_sig_begin(int64_t module, int64_t return_type);
int32_t vshim_sig_param(int64_t signature, int64_t parameter_type);
int64_t vshim_sig_finish(int64_t signature);
int32_t vshim_sig_abandon(int64_t signature);

/* ============================================================== functions ====
 *
 * `declare` and `define` are the same LLVM call: a function becomes a definition
 * when it gets a body -- a basic block with instructions and a terminator.  Both
 * names exist because the emitter reads better with them, and because "declare then
 * define" is the distinction that matters to a reader, not to LLVM.
 */

int64_t vshim_fn_declare(int64_t module, vela_str name, int64_t signature_type);
int64_t vshim_fn_define(int64_t module, vela_str name, int64_t signature_type);

/* Parameter `index` (0-based) of a function, as a value.  Works for a declaration
 * too, which is how a forward declaration's parameters reach a call site. */
int64_t vshim_fn_param(int64_t function, int64_t index);

/* Mark parameter `index` as `sret(<sret_type>)`, the hidden return pointer of the
 * Microsoft x86-64 ABI.  This is the shape of `vela_llvm_str_lit`:
 *
 *     declare void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)
 *
 * The parameter itself must be a pointer (the storage the callee fills in) and
 * `sret_type` is the type it points at -- `vshim_type_str` for `str`.  Returns
 * `VSHIM_OK`, `VSHIM_ERR_ARG` or `VSHIM_ERR_LLVM` (an LLVM built without the `sret`
 * attribute kind, which is a fact about the toolchain, not a caller mistake). */
int32_t vshim_fn_sret_param(int64_t function, int64_t index, int64_t sret_type);

/* ==================================================== constants and globals ===
 *
 * M1 needs exactly two of these: a string literal and the folded constant `1 + 2`.
 * The arithmetic that produced `3` is the *front end's* job -- the corpus already
 * proves folding -- so no add instruction appears in the M1 program at all.
 */

int64_t vshim_const_i64(int64_t module, int64_t value);
int64_t vshim_const_i32(int64_t module, int64_t value);
int64_t vshim_const_f64(int64_t module, double value);
int64_t vshim_const_bool(int64_t module, bool value);

/* A string literal: a private, constant, address-insignificant global array of
 * exactly `bytes.len` bytes -- no terminator added, so the length the runtime is
 * told is the length in the object.  Returns the global, whose type is a pointer;
 * with opaque pointers that value goes straight into a `ptr` parameter, which is
 * what `str_lit`'s second argument is. */
int64_t vshim_string_global(int64_t module, vela_str name, vela_str bytes);

/* ================================================= basic blocks, flow, body ===
 *
 * The builder sits at the end of a block that `vshim_pos_at_end` chose; every build
 * call appends there.  A block that already ends in a terminator is reported as a
 * caller error (`VSHIM_ERR_ARG`, naming the block) rather than being allowed to
 * produce a module that fails verification later with a message that points at
 * nothing useful.
 */

/* Append an empty block to `function`. */
int64_t vshim_block_append(int64_t module, int64_t function, vela_str name);

/* Point the builder at the end of `block`. */
int32_t vshim_pos_at_end(int64_t module, int64_t block);

int32_t vshim_build_br(int64_t module, int64_t block);
int32_t vshim_build_cond_br(int64_t module, int64_t condition, int64_t if_true, int64_t if_false);
int32_t vshim_build_ret(int64_t module, int64_t value);
int32_t vshim_build_ret_void(int64_t module);

/* ==================================================== memory and arithmetic ===
 *
 * `alloca` is not optional even for M1: `str_lit` writes its result *through* a
 * pointer, so the program needs a stack slot to hand it.  The arithmetic is here
 * because `if`/`while` need it; the *checked* arithmetic of the language is not --
 * that is `vela_llvm_add_range` and friends, calls to the runtime, and it goes
 * through `vshim_call` like any other call.
 *
 * Operands are checked (integer for `add`/`sub`/`mul`, both the same type; floating
 * for the `f*` family; a pointer for `load`/`store`) because LLVM answers a mismatch
 * with an assertion, and an assertion is not a compiler diagnostic.
 */

int64_t vshim_build_alloca(int64_t module, int64_t type, int64_t align_bytes);
int64_t vshim_build_load(int64_t module, int64_t type, int64_t pointer);
int32_t vshim_build_store(int64_t module, int64_t value, int64_t pointer);

int64_t vshim_build_add(int64_t module, int64_t left, int64_t right);
int64_t vshim_build_sub(int64_t module, int64_t left, int64_t right);
int64_t vshim_build_mul(int64_t module, int64_t left, int64_t right);

int64_t vshim_build_fadd(int64_t module, int64_t left, int64_t right);
int64_t vshim_build_fsub(int64_t module, int64_t left, int64_t right);
int64_t vshim_build_fmul(int64_t module, int64_t left, int64_t right);
int64_t vshim_build_fdiv(int64_t module, int64_t left, int64_t right);

/* `predicate` is one of the `VSHIM_CMP_*` values; the result is `i1`, which is what
 * `vshim_build_cond_br` requires. */
int64_t vshim_build_icmp(int64_t module, int32_t predicate, int64_t left, int64_t right);

/* The address of element `index` of the array `pointer` points at, where every
 * element is `element_type`.  This is `LLVMBuildGEP2`, and it is the one thing the
 * note below used to list as "not implemented yet ... arrives with whichever
 * milestone needs it": reading `a[i]` out of an array cannot be done without it, so
 * the milestone that needs it is the array milestone, and this is it.
 *
 * The index is **not** checked here and must not be: the language's bounds check is
 * a call to `vela_llvm_bounds_check` made by the emitter, so that one place decides
 * what an out-of-range index means for both back ends.  A GEP with a bad index is
 * how a checked language silently loses the check. */
int64_t vshim_build_gep(int64_t module, int64_t element_type, int64_t pointer, int64_t index);

/* A *named* struct type, created with no body and given one field at a time.
 *
 * Two calls rather than one that takes a list of field types, and that is what
 * makes a struct expressible whose IR layout can name itself: the type has to
 * exist before its own field list can mention it.  A body that is set a second
 * time is refused (`VSHIM_ERR_STATE`), because a re-laid-out type would change
 * the meaning of values already built against the first layout.
 *
 * A struct with no fields at all is legal and is what `vshim_type_ptr` is not:
 * an opaque struct is a distinct type with a distinct identity, which is what a
 * struct value's `load`/`store` pair needs in order to be one instruction.
 *
 * The name comes through the byte buffer, like every other name. */
int64_t vshim_struct_type_opaque_buf(int64_t module);
int32_t vshim_struct_set_body(int64_t module, int64_t struct_type, int64_t field_type);

/* ================================================================== calls ====
 *
 * An argument list is built one value at a time and handed to `vshim_call`, which
 * checks the arity and every argument type against the callee before LLVM sees it,
 * then consumes the list.  Arity and type mismatches are the commonest emitter bug
 * and the least pleasant LLVM failure; here they are `VSHIM_ERR_ARG` and the
 * argument number.
 *
 * `vshim_call` returns the call's value.  A `void` call still has one (the call
 * instruction), so `0` means failure, never "the call returned nothing".
 *
 * The sret call is not a special case: `vela_llvm_str_lit` is declared with
 * `vshim_fn_sret_param`, and its first argument is the `alloca`'d `str` slot.
 */

int64_t vshim_arglist_begin(int64_t module);
int32_t vshim_arglist_add(int64_t arguments, int64_t value);
int32_t vshim_arglist_abandon(int64_t arguments);
int64_t vshim_call(int64_t arguments, int64_t function);

/* ================================================================= output ====
 *
 * `vshim_emit_object` verifies first, on purpose: the verifier is the thing that
 * turns "the emitter built something malformed" into a sentence naming the
 * instruction, instead of an assertion or an object that fails at link time.
 */

/* Run LLVM's verifier over the module.  `VSHIM_OK`, or `VSHIM_ERR_VERIFY` with the
 * verifier's own message. */
int32_t vshim_verify(int64_t module);

/* Verify, then write a COFF object for the host to `path`.  The path is the
 * caller's decision and the caller's scratch directory: nothing in this layer ever
 * chooses a file name beside a user's source.  Returns `VSHIM_OK`,
 * `VSHIM_ERR_VERIFY`, `VSHIM_ERR_EMIT` or `VSHIM_ERR_TOO_LONG` (path over
 * `VSHIM_PATH_CAP`). */
int32_t vshim_emit_object(int64_t module, vela_str path);

/* ============================================================= the byte buffer
 *
 * Design note 6 at the top of this file says why this exists: the language will not
 * accept a `str` at an `extern c` boundary, because a `str` is a length plus a
 * pointer into Vela's string region and not a `char *`, and a wrong-looking-right
 * parameter there is a silently wrong program.  So strings cross as **bytes**, one
 * per call, through a buffer this layer owns.
 *
 * The protocol is always the same:
 *
 *     1. `vshim_buf_reset()`
 *     2. one `vshim_buf_byte()` per byte -- a symbol name, a literal's bytes, an
 *        output path, whatever the `*_buf` function is about to read
 *     3. the `*_buf` function, which reads the buffer as its string
 *
 * and, in the other direction, a `*_buf` function that *returns* text (a message,
 * the host triple, a module's IR) puts it in the same buffer, where
 * `vshim_buf_read_byte()` reads it back one byte at a time.
 *
 * Cost, so this is not a leap of faith: the compiler hands the shim a few hundred
 * kilobytes of symbol names and literal bytes while compiling a program like
 * itself, which at one `u8` per call is 10^5-10^6 calls.  `tools/llvm-shim-probe.ps1`
 * measures the per-byte cost on this machine and prints it, and the self-test in its
 * driver fails if the cost is ever absurd (which is what an accidental `O(n^2)` in
 * the append path would look like).
 *
 * Which functions clear the error slot: every fallible call does, as before -- but
 * the *readers* do not, so a message can still be fetched after they run.
 * `vshim_buf_reset`, `vshim_buf_len`, `vshim_buf_read_byte`, `vshim_last_error_buf`
 * and `vshim_host_triple_buf` never clear it; that is what makes the failure path
 * of a `*_buf` call recoverable at all.
 */

/* The buffer's cap.  A working set that is larger than this is refused with
 * `VSHIM_ERR_TOO_LONG` rather than being allowed to grow without bound. */
#define VSHIM_BUF_MAX ((size_t)64 * 1024 * 1024)

/* Empty the buffer.  Binary-safe and cheap: the allocation is kept, so a compiler
 * looping over thousands of names does not allocate per name.  Never clears the
 * error slot. */
int32_t vshim_buf_reset(void);

/* Append one byte.  `b` must be 0..255; anything else is `VSHIM_ERR_ARG` with a
 * message.  Returns `VSHIM_ERR_TOO_LONG` at `VSHIM_BUF_MAX`, and
 * `VSHIM_ERR_NO_MEMORY` if the buffer cannot grow. */
int32_t vshim_buf_byte(int32_t b);

/* How many bytes are in the buffer.  Never clears the error slot, so it can be
 * called while a failure is still being reported. */
int32_t vshim_buf_len(void);

/* Byte `index`, or `-1` if `index` is negative or past the end.  Never clears the
 * error slot: this is how the sentence from `vshim_last_error_buf` is read back. */
int32_t vshim_buf_read_byte(int64_t index);

/* The `*_buf` spelling of every function that took a string.  Each reads the buffer
 * as its name / bytes / path, and each is exactly the code path its `str` sibling
 * takes -- the two spellings cannot drift, because the `_buf` one calls the other
 * with a `str` built over the buffer. */

/* `vshim_module_open(buffer as the module name)`. */
int64_t vshim_module_open_buf(void);

/* `vshim_fn_declare(buffer as the function name, signature_type)`. */
int64_t vshim_fn_declare_buf(int64_t module, int64_t signature_type);

/* `vshim_fn_define(buffer as the function name, signature_type)`. */
int64_t vshim_fn_define_buf(int64_t module, int64_t signature_type);

/* The string literal whose *bytes* are the buffer, named after `index`.
 *
 * One buffer cannot carry both a literal's bytes and its name, so the name is
 * derived from `index` (`.s<index>`) and `index` is therefore the literal's
 * identity: the emitter's string table already has exactly such an index for every
 * literal.  Repeating an index with the same bytes reuses the one global; repeating
 * it with *different* bytes adds a second global under a renamed symbol rather than
 * silently handing back the first one's bytes, which would be a wrong answer.  The
 * returned handle is the identity; the name is a debugging aid. */
int64_t vshim_string_global_buf(int64_t module, int64_t index);

/* `vshim_block_append(buffer as the block name, ...)`. */
int64_t vshim_block_append_buf(int64_t module, int64_t function);

/* `vshim_emit_object(module, buffer as the path)`. */
int32_t vshim_emit_object_buf(int64_t module);

/* The host triple into the buffer (replacing its contents) instead of as a `str`. */
int32_t vshim_host_triple_buf(void);

/* The module's IR into the buffer (replacing its contents) instead of as a `str`.
 *
 * The `str` spelling is a fixed 64 KiB window that truncates, because a `str` return
 * cannot allocate; this one carries the whole module and reports `VSHIM_ERR_TOO_LONG`
 * instead of truncating, because the buffer can grow.  On failure the buffer is left
 * empty, so a caller can never read stale bytes as this call's result. */
int32_t vshim_module_ir_buf(int64_t module);

/* The message for the most recent failure into the buffer (replacing its contents),
 * where `vshim_buf_read_byte` reads it back.  Does **not** clear the error slot --
 * that is the point of it: it is how the sentence survives to be read. */
int32_t vshim_last_error_buf(void);

#endif /* VELA_LLVM_SHIM_H */
