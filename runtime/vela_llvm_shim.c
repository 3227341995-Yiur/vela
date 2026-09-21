/* runtime/vela_llvm_shim.c - the implementation behind runtime/vela_llvm_shim.h.
 *
 * Read the header first: it carries the design and the measured ABI facts.  What
 * follows is the mechanism, and three decisions are worth stating up here because
 * they are visible in every function below.
 *
 *   1. **Nothing here ever lets LLVM assert.**  The C API is a thin wrapper over
 *      C++ that `assert()`s on a type or arity mismatch, and an assert in a release
 *      LLVM is undefined behaviour.  A caller that can only pass scalars cannot be
 *      trusted to get every type right, so this layer checks first and reports a
 *      sentence.  That is the whole point of the shim: a *diagnosis*, not a crash.
 *   2. **`clear_error()` at entry, `fail()` at the point of failure, and exactly one
 *      `return` path per outcome.**  A message is never left over from a previous
 *      call, and code is never left behind after something went wrong.
 *   3. **No dependence on the Vela runtime being initialized.**  This file reads
 *      `vela_str.data`/`.len` and nothing else from `vela_runtime.h`: no arena, no
 *      string table, no panic path.  The shim runs inside the compiler, before any
 *      user program exists, and it must not need a user program's runtime.
 *
 * Development-time build (the product path never compiles C):
 *
 *     cl /nologo /std:c11 /W3 /I <llvm>\include /I runtime /c runtime\vela_llvm_shim.c
 *
 * At run time the binary needs `LLVM-C.dll` beside it -- see the header.
 */

#include "vela_llvm_shim.h"

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>

#include <llvm-c/Analysis.h>
#include <llvm-c/Core.h>
#include <llvm-c/Target.h>
#include <llvm-c/TargetMachine.h>

/* --------------------------------------------------------------- error slot */

static char g_error[VSHIM_MESSAGE_CAP];

/* The message buffer for `vshim_module_ir`, and the note that marks a truncated
 * dump.  Both are shim-owned static storage: the caller cannot allocate for us
 * (there is no pointer type on the Vela side), so the caps are fixed and checked. */
static char g_ir[VSHIM_IR_CAP];
#define VSHIM_IR_NOTE "\n;; [truncated: this module's IR is longer than VSHIM_IR_CAP bytes]\n"

static void clear_error(void)
{
    g_error[0] = '\0';
}

static void fail(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, VSHIM_MESSAGE_CAP, fmt, ap);
    va_end(ap);
    g_error[VSHIM_MESSAGE_CAP - 1] = '\0';
}

/* ------------------------------------------------------------- byte buffer
 *
 * The scalar spelling of every string in this API -- see design note 6 in the
 * header for why the language needs one.  One buffer for the process, like the
 * error slot: `vm.exe` compiles one program at a time, on one thread.
 *
 * `fail()` is the only way an error is recorded, so the rule about which calls
 * clear the slot is easy to keep: a function that can fail calls `clear_error()`
 * first, and the pure readers (`buf_len`, `buf_read_byte`, `last_error_buf`,
 * `host_triple_buf`, `buf_reset`) do not -- which is what lets a message be read
 * back after the call that produced it.
 */

typedef struct {
    unsigned char *data;
    size_t         len;
    size_t         cap;
} shim_buf;

static shim_buf g_buffer;

static vela_str buffer_as_str(void)
{
    vela_str s;
    s.data = (const uint8_t *)g_buffer.data;
    s.len  = (int64_t)g_buffer.len;
    return s;
}

static int32_t buffer_grow(size_t want)
{
    size_t cap;
    unsigned char *grown;

    if (want <= g_buffer.cap) return VSHIM_OK;
    if (want > VSHIM_BUF_MAX) {
        fail("the byte buffer cannot hold %llu bytes; this layer's cap is %llu",
             (unsigned long long)want, (unsigned long long)VSHIM_BUF_MAX);
        return VSHIM_ERR_TOO_LONG;
    }
    cap = g_buffer.cap ? g_buffer.cap : 4096;
    while (cap < want) cap *= 2;
    if (cap > VSHIM_BUF_MAX) cap = VSHIM_BUF_MAX;
    grown = (unsigned char *)realloc(g_buffer.data, cap);
    if (!grown) {
        fail("out of memory growing the byte buffer to %llu bytes", (unsigned long long)cap);
        return VSHIM_ERR_NO_MEMORY;
    }
    g_buffer.data = grown;
    g_buffer.cap  = cap;
    return VSHIM_OK;
}

/* Replace the contents.  On failure the buffer is left *empty*, never stale: a
 * caller must not be able to read an earlier call's bytes as this call's result. */
static int32_t buffer_set(const char *bytes, size_t len)
{
    int32_t rc = buffer_grow(len);
    if (rc != VSHIM_OK) {
        g_buffer.len = 0;
        return rc;
    }
    if (len) memcpy(g_buffer.data, bytes, len);
    g_buffer.len = len;
    return VSHIM_OK;
}

/* ------------------------------------------------------------- handle table
 *
 * A handle is `(module id << 32) | slot`, with slot 0 reserved for the module
 * handle itself.  Module ids and slot numbers are both monotonic and never reused,
 * so an old handle can never name a new object -- the failure that would be worst
 * of all, because it produces a wrong answer instead of an error.  Closing a module
 * clears its registry entry, which is what makes every handle from it report
 * "belongs to a module that has been closed".
 */

typedef enum {
    SHIM_KIND_NONE  = 0,   /* free, or consumed by the call/signature that owned it */
    SHIM_KIND_TYPE  = 1,
    SHIM_KIND_VALUE = 2,   /* function, global, constant, parameter, instruction */
    SHIM_KIND_BLOCK = 3,
    SHIM_KIND_SIG   = 4,   /* a signature being built */
    SHIM_KIND_ARGS  = 5    /* an argument list being built */
} shim_kind;

typedef struct {
    void   *ptr;
    uint8_t kind;
} shim_slot;

typedef struct shim_layout {
    LLVMTypeRef  type;
    LLVMTypeRef *fields;
    unsigned     count;
    unsigned     cap;
} shim_layout;

typedef struct shim_module {
    int64_t id;
    LLVMContextRef ctx;
    LLVMModuleRef  mod;
    LLVMBuilderRef builder;
    shim_slot     *slots;
    size_t         count;
    size_t         cap;
    /* The primitives are created once per module and their handles are cached;
     * an emitter calling vshim_type_i64() in a loop must not grow the table. */
    int64_t h_void, h_i1, h_i8, h_i32, h_i64, h_f64, h_ptr, h_str;
    /* Named struct types and the fields they have been given so far.  A body may
     * be set once and only once, and `LLVMStructSetBody` takes the *whole* field
     * list -- so the list is kept here and re-set on every field, which is what
     * lets the emitter add one field per call across a boundary that has no
     * variadic functions.  A linear scan: a module has a handful of structs, and
     * a hash table here would be more machinery than the check is worth. */
    shim_layout   *layouts;
    size_t         layout_count;
    size_t         layout_cap;
} shim_module;

/* Monotonic module ids, so a closed module's id is never handed out again.  One
 * pointer per module ever opened: a batch compiler opening ten thousand modules
 * wastes eighty kilobytes, which is the price of handles that cannot alias. */
static shim_module **g_modules;
static size_t        g_modules_cap;

static const char *kind_name(shim_kind k)
{
    switch (k) {
    case SHIM_KIND_TYPE:  return "a type";
    case SHIM_KIND_VALUE: return "a value";
    case SHIM_KIND_BLOCK: return "a basic block";
    case SHIM_KIND_SIG:   return "a signature being built";
    case SHIM_KIND_ARGS:  return "an argument list being built";
    default:              return "nothing (it has been consumed)";
    }
}

static int64_t handle_make(int64_t module_id, uint32_t slot)
{
    return ((int64_t)module_id << 32) | (int64_t)slot;
}

static int64_t handle_module_id(int64_t handle)
{
    return handle >> 32;
}

static uint32_t handle_slot(int64_t handle)
{
    return (uint32_t)(handle & 0xFFFFFFFF);
}

static shim_module *g_module(size_t index);

/* The module named by a *module handle* (slot 0), with the usual diagnostics. */
static shim_module *module_of(int64_t handle)
{
    int64_t id;
    uint32_t slot;

    if (handle <= 0) {
        fail("handle %lld is not a shim handle (a module handle is a positive integer)", (long long)handle);
        return NULL;
    }
    slot = handle_slot(handle);
    id = handle_module_id(handle);
    if (slot != 0) {
        fail("handle %lld is not a module handle: a module handle's low half is zero", (long long)handle);
        return NULL;
    }
    if (id <= 0) {
        fail("handle %lld names no module", (long long)handle);
        return NULL;
    }
    {
        shim_module *m = g_module((size_t)id - 1);
        if (!m) {
            fail("handle %lld names module %lld, which is not open (it was never opened, or vshim_module_close() already ran)",
                 (long long)handle, (long long)id);
            return NULL;
        }
        return m;
    }
}

static int64_t slot_add(shim_module *m, void *ptr, shim_kind kind)
{
    if (m->count >= m->cap) {
        size_t want = m->cap ? m->cap * 2 : 64;
        shim_slot *grown = (shim_slot *)realloc(m->slots, want * sizeof(shim_slot));
        if (!grown) {
            fail("out of memory growing the handle table of module %lld", (long long)m->id);
            return 0;
        }
        m->slots = grown;
        m->cap = want;
    }
    if (m->count >= 0xFFFFFFFFu) {
        fail("module %lld has produced more than 4294967295 handles", (long long)m->id);
        return 0;
    }
    m->count++;
    m->slots[m->count - 1].ptr  = ptr;
    m->slots[m->count - 1].kind = (uint8_t)kind;
    return handle_make(m->id, (uint32_t)m->count);
}

/* Resolve any non-module handle, checking both its lifetime and its kind.  On
 * success `*owner` (when wanted) is the module it belongs to. */
static void *lookup(int64_t handle, shim_kind want, shim_module **owner)
{
    int64_t id;
    uint32_t slot;
    shim_module *m;
    shim_slot *s;

    if (handle <= 0) {
        fail("handle %lld is not a shim handle (0 is never valid)", (long long)handle);
        return NULL;
    }
    id = handle_module_id(handle);
    slot = handle_slot(handle);
    m = g_module((size_t)id - 1);
    if (!m) {
        fail("handle %lld belongs to module %lld, which is closed or was never opened",
             (long long)handle, (long long)id);
        return NULL;
    }
    if (slot == 0) {
        fail("handle %lld is a module handle, and this call wants %s", (long long)handle, kind_name(want));
        return NULL;
    }
    if ((size_t)slot > m->count) {
        fail("handle %lld is not valid in module %lld (it has produced %llu handles so far)",
             (long long)handle, (long long)id, (unsigned long long)m->count);
        return NULL;
    }
    s = &m->slots[slot - 1];
    if (s->kind == SHIM_KIND_NONE) {
        fail("handle %lld has already been consumed", (long long)handle);
        return NULL;
    }
    if (s->kind != (uint8_t)want) {
        fail("handle %lld is %s, but this call wants %s", (long long)handle, kind_name((shim_kind)s->kind), kind_name(want));
        return NULL;
    }
    if (owner) *owner = m;
    return s->ptr;
}

static void consume(int64_t handle)
{
    shim_module *m = g_module((size_t)handle_module_id(handle) - 1);
    uint32_t slot;
    if (!m) return;
    slot = handle_slot(handle);
    if (slot == 0 || (size_t)slot > m->count) return;
    m->slots[slot - 1].kind = SHIM_KIND_NONE;
    m->slots[slot - 1].ptr  = NULL;
}

/* ------------------------------------------------------- argument buffers
 *
 * Signatures and argument lists are the two places where `llvm-c` wants a C array
 * of pointers and the Vela side can only hand over one item at a time. */

typedef struct {
    shim_module  *m;
    LLVMTypeRef   ret;
    LLVMTypeRef   params[VSHIM_MAX_PARAMS];
    unsigned      count;
} shim_sig;

typedef struct {
    shim_module  *m;
    LLVMValueRef *values;
    unsigned      count;
    unsigned      cap;
} shim_args;

/* ------------------------------------------------------------ target state */

static int                    g_native_ready;   /* the target was initialized */
static LLVMTargetMachineRef   g_target_machine; /* NULL until vshim_open() */
static char                   g_triple[VSHIM_NAME_CAP];

static shim_module *g_module(size_t index)
{
    if (index >= g_modules_cap) return NULL;
    return g_modules[index];
}

static int g_module_slot(shim_module *m)
{
    size_t i;
    for (i = 0; i < g_modules_cap; i++) {
        if (!g_modules[i]) { g_modules[i] = m; return (int)(i + 1); }
    }
    {
        size_t want = g_modules_cap ? g_modules_cap * 2 : 4;
        shim_module **grown = (shim_module **)realloc(g_modules, want * sizeof(shim_module *));
        if (!grown) {
            fail("out of memory growing the module registry");
            return 0;
        }
        memset(grown + g_modules_cap, 0, (want - g_modules_cap) * sizeof(shim_module *));
        g_modules = grown;
        g_modules_cap = want;
        g_modules[g_modules_cap - 1] = m;
        i = g_modules_cap - 1;
    }
    return (int)(i + 1);
}

/* --------------------------------------------------------------- copying
 *
 * The one place a `str` becomes a C string.  A Vela `str` may hold a NUL and is not
 * NUL-terminated, so the length decides and the NUL is added here -- and a name that
 * does not fit is a status with a message, never a truncation. */

static int32_t copy_cstr(vela_str s, char *buf, size_t cap, const char *what)
{
    if (s.len < 0) {
        fail("%s has a negative length (%lld)", what, (long long)s.len);
        return VSHIM_ERR_ARG;
    }
    if (s.len > 0 && !s.data) {
        fail("%s has length %lld but no bytes", what, (long long)s.len);
        return VSHIM_ERR_ARG;
    }
    if ((size_t)s.len >= cap) {
        fail("%s is %lld bytes long; this layer's limit is %llu bytes (the Vela side cannot allocate a buffer for us, so the cap is fixed)",
             what, (long long)s.len, (unsigned long long)(cap - 1));
        return VSHIM_ERR_TOO_LONG;
    }
    if (s.len > 0) memcpy(buf, s.data, (size_t)s.len);
    buf[s.len] = '\0';
    return VSHIM_OK;
}

/* ------------------------------------------------------------ type queries */

static int type_is_integer(LLVMTypeRef t) { return LLVMGetTypeKind(t) == LLVMIntegerTypeKind; }
static int type_is_pointer(LLVMTypeRef t) { return LLVMGetTypeKind(t) == LLVMPointerTypeKind; }
static int type_is_float(LLVMTypeRef t)
{
    LLVMTypeKind k = LLVMGetTypeKind(t);
    return k == LLVMFloatTypeKind || k == LLVMDoubleTypeKind;
}
static int value_is_function(LLVMValueRef v) { return LLVMGetValueKind(v) == LLVMFunctionValueKind; }

/* The kind an argument must have for a call, plus the arity.  Doing this here is
 * what turns "the emitter passed an i64 where the callee wants a pointer" from an
 * LLVM assertion into an argument number and a sentence. */
static int32_t check_callee(shim_module *m, LLVMValueRef fn, shim_args *a)
{
    LLVMTypeRef fnty;
    unsigned want, i;

    if (!value_is_function(fn)) {
        fail("the callee is not a function; declare one with vshim_fn_declare()/vshim_fn_define() first");
        return VSHIM_ERR_ARG;
    }
    fnty = LLVMGlobalGetValueType(fn);
    if (!fnty || LLVMGetTypeKind(fnty) != LLVMFunctionTypeKind) {
        fail("the callee has no function type");
        return VSHIM_ERR_ARG;
    }
    if (LLVMIsFunctionVarArg(fnty)) {
        fail("this callee is variadic, and variadic call sites are not supported by this layer yet");
        return VSHIM_ERR_ARG;
    }
    want = LLVMCountParams(fn);
    if (a->count != want) {
        fail("the callee takes %u arguments but %u were added to the list", want, a->count);
        return VSHIM_ERR_ARG;
    }
    for (i = 0; i < want; i++) {
        LLVMTypeRef want_ty = LLVMTypeOf(LLVMGetParam(fn, i));
        LLVMTypeRef got_ty  = LLVMTypeOf(a->values[i]);
        if (want_ty != got_ty) {
            char wbuf[64], gbuf[64];
            char *w = LLVMPrintTypeToString(want_ty);
            char *g = LLVMPrintTypeToString(got_ty);
            snprintf(wbuf, sizeof wbuf, "%s", w ? w : "?");
            snprintf(gbuf, sizeof gbuf, "%s", g ? g : "?");
            if (w) LLVMDisposeMessage(w);
            if (g) LLVMDisposeMessage(g);
            fail("argument %u: the callee takes %s but the list has %s", i, wbuf, gbuf);
            return VSHIM_ERR_ARG;
        }
    }
    (void)m;
    return VSHIM_OK;
}

/* The builder must be positioned at a block that is not already finished. */
static int32_t check_builder(shim_module *m)
{
    LLVMBasicBlockRef bb = LLVMGetInsertBlock(m->builder);
    if (!bb) {
        fail("the builder is not positioned in any block: call vshim_pos_at_end() with a block first");
        return VSHIM_ERR_STATE;
    }
    if (LLVMGetBasicBlockTerminator(bb)) {
        fail("the current block already ends with a terminator (a br, cond_br or ret); appending here would place an instruction after it -- start another block with vshim_block_append() and point the builder at it");
        return VSHIM_ERR_ARG;
    }
    return VSHIM_OK;
}

/* ================================================================ lifecycle */

int32_t vshim_open(void)
{
    char *triple = NULL;
    char *err = NULL;
    LLVMTargetRef target = NULL;

    clear_error();

    /* Idempotent: a compiler that reopens a module (or a test that calls this
     * twice) must not leak a target machine or try to initialize a target twice. */
    if (g_target_machine) return VSHIM_OK;

    if (!g_native_ready) {
        /* `phase2_spike.c`'s first lesson: without this, the triple looks right and
         * the target machine is NULL.  The native macros come from the LLVM this was
         * built against; a package with no native target says so honestly rather than
         * failing later with something that looks like a codegen bug. */
        LLVMBool bad = LLVMInitializeNativeTarget();
        bad |= LLVMInitializeNativeAsmPrinter();
        bad |= LLVMInitializeNativeAsmParser();
        if (bad) {
            fail("this LLVM was built without a native target (LLVMInitializeNativeTarget/AsmPrinter/AsmParser returned non-zero), so it cannot produce code for this host");
            return VSHIM_ERR_TARGET;
        }
        g_native_ready = 1;
    }

    triple = LLVMGetDefaultTargetTriple();
    if (!triple) {
        fail("LLVMGetDefaultTargetTriple() returned NULL");
        return VSHIM_ERR_TARGET;
    }
    if (strlen(triple) >= sizeof g_triple) {
        fail("the host triple \"%s\" is longer than %llu bytes", triple, (unsigned long long)(sizeof g_triple - 1));
        LLVMDisposeMessage(triple);
        return VSHIM_ERR_TARGET;
    }
    snprintf(g_triple, sizeof g_triple, "%s", triple);

    if (LLVMGetTargetFromTriple(triple, &target, &err) != 0) {
        fail("this LLVM has no target for the host triple \"%s\": %s", triple, err ? err : "(no message)");
        if (err) LLVMDisposeMessage(err);
        LLVMDisposeMessage(triple);
        g_triple[0] = '\0';
        return VSHIM_ERR_TARGET;
    }
    if (err) {
        LLVMDisposeMessage(err);
        err = NULL;
    }

    /* "generic" with no feature string is what the spike measured and what makes the
     * object deterministic across machines; host CPU features are a tuning decision
     * for a later round, and they can change performance but not semantics. */
    g_target_machine = LLVMCreateTargetMachine(target, triple, "generic", "",
                                               LLVMCodeGenLevelDefault,
                                               LLVMRelocDefault,
                                               LLVMCodeModelDefault);
    LLVMDisposeMessage(triple);
    if (!g_target_machine) {
        fail("LLVMCreateTargetMachine failed for the host triple \"%s\"", g_triple);
        return VSHIM_ERR_TARGET;
    }
    return VSHIM_OK;
}

int32_t vshim_shutdown(void)
{
    clear_error();
    if (g_target_machine) {
        LLVMDisposeTargetMachine(g_target_machine);
        g_target_machine = NULL;
    }
    /* LLVM has no way to un-initialize a target, and needs none: the initialization
     * is idempotent and costs no memory worth reclaiming.  It stays ready so a later
     * vshim_open() only has to make a new target machine. */
    return VSHIM_OK;
}

/* ================================================================= modules */

int64_t vshim_module_open(vela_str name)
{
    char buf[VSHIM_NAME_CAP];
    shim_module *m;
    int id;

    clear_error();

    if (!g_target_machine) {
        fail("no target machine: call vshim_open() before opening a module (and not after vshim_shutdown())");
        return 0;
    }
    if (copy_cstr(name, buf, sizeof buf, "the module name") != VSHIM_OK) return 0;

    m = (shim_module *)calloc(1, sizeof(shim_module));
    if (!m) {
        fail("out of memory allocating a module");
        return 0;
    }
    m->ctx = LLVMContextCreate();
    if (!m->ctx) {
        fail("LLVMContextCreate() returned NULL");
        free(m);
        return 0;
    }
    m->mod = LLVMModuleCreateWithNameInContext(buf[0] ? buf : "vela", m->ctx);
    if (!m->mod) {
        fail("LLVMModuleCreateWithNameInContext() returned NULL");
        LLVMContextDispose(m->ctx);
        free(m);
        return 0;
    }
    m->builder = LLVMCreateBuilderInContext(m->ctx);
    if (!m->builder) {
        fail("LLVMCreateBuilderInContext() returned NULL");
        LLVMDisposeModule(m->mod);
        LLVMContextDispose(m->ctx);
        free(m);
        return 0;
    }

    id = g_module_slot(m);
    if (id == 0) {
        LLVMDisposeBuilder(m->builder);
        LLVMDisposeModule(m->mod);
        LLVMContextDispose(m->ctx);
        free(m);
        return 0;
    }
    m->id = id;

    /* The triple is the host's, measured, never a literal; the data layout comes from
     * the target machine so the two cannot disagree. */
    LLVMSetTarget(m->mod, g_triple);
    {
        LLVMTargetDataRef td = LLVMCreateTargetDataLayout(g_target_machine);
        if (!td) {
            fail("LLVMCreateTargetDataLayout() returned NULL for the host target machine");
            g_modules[id - 1] = NULL;
            LLVMDisposeBuilder(m->builder);
            LLVMDisposeModule(m->mod);
            LLVMContextDispose(m->ctx);
            free(m);
            return 0;
        }
        LLVMSetModuleDataLayout(m->mod, td);
        LLVMDisposeTargetData(td);
    }

    return handle_make(m->id, 0);
}

int32_t vshim_module_close(int64_t module)
{
    shim_module *m;

    clear_error();
    m = module_of(module);
    if (!m) {
        /* A handle that is shaped like a module handle but names nothing open is a
         * state problem (closed twice); anything else is a bad argument. */
        return (module > 0 && handle_slot(module) == 0) ? VSHIM_ERR_STATE : VSHIM_ERR_ARG;
    }

    /* Dispose in the order LLVM requires, then invalidate the registry entry: every
     * handle that named this module now reports "closed", which is the whole reason
     * the registry entry exists. */
    LLVMDisposeBuilder(m->builder);
    LLVMDisposeModule(m->mod);
    LLVMContextDispose(m->ctx);
    {
        size_t index = (size_t)m->id - 1;
        size_t i;
        g_modules[index] = NULL;
        for (i = 0; i < m->layout_count; i++) free(m->layouts[i].fields);
        free(m->layouts);
        free(m->slots);
        free(m);
    }
    return VSHIM_OK;
}

/* ============================================================= diagnostics */

vela_str vshim_last_error(void)
{
    vela_str s;
    s.data = (const uint8_t *)g_error;
    s.len  = (int64_t)strlen(g_error);
    return s;
}

vela_str vshim_host_triple(void)
{
    vela_str s;
    s.data = (const uint8_t *)g_triple;
    s.len  = (int64_t)strlen(g_triple);
    return s;
}

vela_str vshim_module_ir(int64_t module)
{
    vela_str empty;
    shim_module *m;
    char *text;
    size_t n, room, copy;

    empty.data = (const uint8_t *)g_ir;
    empty.len  = 0;
    clear_error();
    m = module_of(module);
    if (!m) return empty;

    text = LLVMPrintModuleToString(m->mod);
    if (!text) {
        fail("LLVMPrintModuleToString() returned NULL");
        return empty;
    }
    n = strlen(text);
    if (n < VSHIM_IR_CAP - sizeof VSHIM_IR_NOTE - 1) {
        memcpy(g_ir, text, n);
        g_ir[n] = '\0';
        empty.len = (int64_t)n;
    } else {
        room = VSHIM_IR_CAP - sizeof VSHIM_IR_NOTE - 1;
        copy = room;
        memcpy(g_ir, text, copy);
        memcpy(g_ir + copy, VSHIM_IR_NOTE, sizeof VSHIM_IR_NOTE); /* the note's own NUL ends it */
        empty.len = (int64_t)(copy + sizeof VSHIM_IR_NOTE - 1);
    }
    LLVMDisposeMessage(text);
    return empty;
}

/* ===================================================================== types */

static int64_t cache_type(shim_module *m, LLVMTypeRef t, const char *what, int64_t *slot)
{
    int64_t h;
    if (!t) {
        fail("could not create the %s type", what);
        return 0;
    }
    h = slot_add(m, t, SHIM_KIND_TYPE);
    if (!h) return 0;
    *slot = h;
    return h;
}

int64_t vshim_type_void(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_void) return m->h_void;
    return cache_type(m, LLVMVoidTypeInContext(m->ctx), "void", &m->h_void);
}

int64_t vshim_type_i1(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_i1) return m->h_i1;
    return cache_type(m, LLVMInt1TypeInContext(m->ctx), "i1", &m->h_i1);
}

int64_t vshim_type_i8(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_i8) return m->h_i8;
    return cache_type(m, LLVMInt8TypeInContext(m->ctx), "i8", &m->h_i8);
}

int64_t vshim_type_i32(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_i32) return m->h_i32;
    return cache_type(m, LLVMInt32TypeInContext(m->ctx), "i32", &m->h_i32);
}

int64_t vshim_type_i64(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_i64) return m->h_i64;
    return cache_type(m, LLVMInt64TypeInContext(m->ctx), "i64", &m->h_i64);
}

int64_t vshim_type_f64(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_f64) return m->h_f64;
    return cache_type(m, LLVMDoubleTypeInContext(m->ctx), "f64", &m->h_f64);
}

int64_t vshim_type_ptr(int64_t module)
{
    shim_module *m;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_ptr) return m->h_ptr;
    return cache_type(m, LLVMPointerTypeInContext(m->ctx, 0), "ptr", &m->h_ptr);
}

int64_t vshim_type_str(int64_t module)
{
    shim_module *m;
    LLVMTypeRef elems[2];
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (m->h_str) return m->h_str;
    /* `{ ptr, i64 }`: the Vela `str` field order, read off runtime/vela_runtime.h
     * (`const uint8_t *data; int64_t len`), never restated from memory.  A literal
     * (unnamed) struct, which is what %vela_str is in m1_probe.ll. */
    elems[0] = LLVMPointerTypeInContext(m->ctx, 0);
    elems[1] = LLVMInt64TypeInContext(m->ctx);
    return cache_type(m, LLVMStructTypeInContext(m->ctx, elems, 2, 0), "str { { ptr, i64 } }", &m->h_str);
}

/* ================================================================ signatures */

int64_t vshim_sig_begin(int64_t module, int64_t return_type)
{
    shim_module *m, *owner = NULL;
    LLVMTypeRef ret;
    shim_sig *sig;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    ret = (LLVMTypeRef)lookup(return_type, SHIM_KIND_TYPE, &owner);
    if (!ret) return 0;
    if (owner != m) {
        fail("the return type belongs to another module; types cannot cross modules");
        return 0;
    }
    if (LLVMGetTypeKind(ret) == LLVMFunctionTypeKind) {
        fail("a function type cannot be a return type (or a parameter type): LLVM wants a pointer type there");
        return 0;
    }
    sig = (shim_sig *)calloc(1, sizeof(shim_sig));
    if (!sig) {
        fail("out of memory allocating a signature");
        return 0;
    }
    sig->m = m;
    sig->ret = ret;   /* calloc zeroed the parameter array; count is 0 */
    h = slot_add(m, sig, SHIM_KIND_SIG);
    if (!h) {
        free(sig);
        return 0;
    }
    return h;
}

int32_t vshim_sig_param(int64_t signature, int64_t parameter_type)
{
    shim_sig *sig;
    shim_module *owner = NULL;
    LLVMTypeRef ty;

    clear_error();
    sig = (shim_sig *)lookup(signature, SHIM_KIND_SIG, NULL);
    if (!sig) return VSHIM_ERR_ARG;
    ty = (LLVMTypeRef)lookup(parameter_type, SHIM_KIND_TYPE, &owner);
    if (!ty) return VSHIM_ERR_ARG;
    if (owner != sig->m) {
        fail("the parameter type belongs to another module; types cannot cross modules");
        return VSHIM_ERR_ARG;
    }
    if (sig->count >= VSHIM_MAX_PARAMS) {
        fail("this signature already has %d parameters, which is this layer's limit", VSHIM_MAX_PARAMS);
        return VSHIM_ERR_ARG;
    }
    if (LLVMGetTypeKind(ty) == LLVMFunctionTypeKind) {
        fail("parameter %u is a function type; a function cannot be a parameter in LLVM IR, a pointer to it can", sig->count);
        return VSHIM_ERR_ARG;
    }
    sig->params[sig->count] = ty;
    sig->count++;
    return VSHIM_OK;
}

int64_t vshim_sig_finish(int64_t signature)
{
    shim_sig *sig;
    LLVMTypeRef fn_ty;
    int64_t h;

    clear_error();
    sig = (shim_sig *)lookup(signature, SHIM_KIND_SIG, NULL);
    if (!sig) return 0;
    fn_ty = LLVMFunctionType(sig->ret, sig->params, sig->count, 0);
    if (!fn_ty) {
        fail("LLVMFunctionType() returned NULL");
        return 0;
    }
    /* Registered before the signature is freed: the type belongs to the module's
     * context, so it outlives the builder that described it. */
    h = slot_add(sig->m, fn_ty, SHIM_KIND_TYPE);
    if (!h) return 0;
    consume(signature);
    free(sig);
    return h;
}

int32_t vshim_sig_abandon(int64_t signature)
{
    shim_sig *sig;
    clear_error();
    sig = (shim_sig *)lookup(signature, SHIM_KIND_SIG, NULL);
    if (!sig) return VSHIM_ERR_ARG;
    consume(signature);
    free(sig);
    return VSHIM_OK;
}

/* ================================================================ functions */

static int64_t fn_add(int64_t module, vela_str name, int64_t signature_type)
{
    char buf[VSHIM_NAME_CAP];
    shim_module *m, *owner = NULL;
    LLVMTypeRef ty;
    LLVMValueRef fn;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    ty = (LLVMTypeRef)lookup(signature_type, SHIM_KIND_TYPE, &owner);
    if (!ty) return 0;
    if (owner != m) {
        fail("the signature belongs to another module");
        return 0;
    }
    if (LLVMGetTypeKind(ty) != LLVMFunctionTypeKind) {
        fail("the signature handle is a type, but not a function type; build one with vshim_sig_begin/vshim_sig_finish");
        return 0;
    }
    if (copy_cstr(name, buf, sizeof buf, "the function name") != VSHIM_OK) return 0;
    if (!buf[0]) {
        fail("a function needs a name");
        return 0;
    }
    fn = LLVMAddFunction(m->mod, buf, ty);
    if (!fn) {
        fail("LLVMAddFunction(\"%s\") returned NULL", buf);
        return 0;
    }
    h = slot_add(m, fn, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int64_t vshim_fn_declare(int64_t module, vela_str name, int64_t signature_type)
{
    return fn_add(module, name, signature_type);
}

int64_t vshim_fn_define(int64_t module, vela_str name, int64_t signature_type)
{
    /* A function is a definition as soon as it has a body; LLVM makes no distinction
     * at creation time, so neither does this.  The name exists for the reader. */
    return fn_add(module, name, signature_type);
}

int64_t vshim_fn_param(int64_t function, int64_t index)
{
    shim_module *m = NULL;
    LLVMValueRef fn;
    LLVMValueRef p;
    int64_t h;

    clear_error();
    fn = (LLVMValueRef)lookup(function, SHIM_KIND_VALUE, &m);
    if (!fn) return 0;
    if (!value_is_function(fn)) {
        fail("handle %lld is a value, but not a function", (long long)function);
        return 0;
    }
    if (index < 0 || index >= (int64_t)LLVMCountParams(fn)) {
        fail("parameter %lld is out of range: this function has %u parameters", (long long)index, LLVMCountParams(fn));
        return 0;
    }
    p = LLVMGetParam(fn, (unsigned)index);
    if (!p) {
        fail("LLVMGetParam() returned NULL for parameter %lld", (long long)index);
        return 0;
    }
    h = slot_add(m, p, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int32_t vshim_fn_sret_param(int64_t function, int64_t index, int64_t sret_type)
{
    shim_module *m = NULL, *owner = NULL;
    LLVMValueRef fn;
    LLVMTypeRef ty;
    LLVMTypeRef param_ty;
    unsigned kind;
    LLVMAttributeRef attr;

    clear_error();
    fn = (LLVMValueRef)lookup(function, SHIM_KIND_VALUE, &m);
    if (!fn) return VSHIM_ERR_ARG;
    if (!value_is_function(fn)) {
        fail("the sret target is a value, but not a function");
        return VSHIM_ERR_ARG;
    }
    if (index < 0 || index >= (int64_t)LLVMCountParams(fn)) {
        fail("parameter %lld is out of range: this function has %u parameters", (long long)index, LLVMCountParams(fn));
        return VSHIM_ERR_ARG;
    }
    ty = (LLVMTypeRef)lookup(sret_type, SHIM_KIND_TYPE, &owner);
    if (!ty) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the sret type belongs to another module");
        return VSHIM_ERR_ARG;
    }
    param_ty = LLVMTypeOf(LLVMGetParam(fn, (unsigned)index));
    if (!type_is_pointer(param_ty)) {
        char *printed = LLVMPrintTypeToString(param_ty);
        fail("parameter %lld has type %s, not a pointer: sret marks the hidden return pointer, so the parameter must be the storage the callee writes through (an alloca of the return type)",
             (long long)index, printed ? printed : "?");
        if (printed) LLVMDisposeMessage(printed);
        return VSHIM_ERR_ARG;
    }
    kind = LLVMGetEnumAttributeKindForName("sret", 4);
    if (!kind) {
        fail("this LLVM has no \"sret\" attribute kind (LLVMGetEnumAttributeKindForName returned 0)");
        return VSHIM_ERR_LLVM;
    }
    attr = LLVMCreateTypeAttribute(m->ctx, kind, ty);
    if (!attr) {
        fail("LLVMCreateTypeAttribute(\"sret\") returned NULL");
        return VSHIM_ERR_LLVM;
    }
    /* Attribute index 0 is the return value, so parameter i is attribute index i+1. */
    LLVMAddAttributeAtIndex(fn, (LLVMAttributeIndex)(index + 1), attr);
    return VSHIM_OK;
}

/* ======================================================= constants, globals */

int64_t vshim_const_i64(int64_t module, int64_t value)
{
    shim_module *m;
    LLVMValueRef c;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    c = LLVMConstInt(LLVMInt64TypeInContext(m->ctx), (unsigned long long)value, 0);
    if (!c) {
        fail("LLVMConstInt(i64) returned NULL");
        return 0;
    }
    return slot_add(m, c, SHIM_KIND_VALUE);
}

int64_t vshim_const_i32(int64_t module, int64_t value)
{
    shim_module *m;
    LLVMValueRef c;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (value < INT32_MIN || value > INT32_MAX) {
        fail("the value %lld does not fit in i32", (long long)value);
        return 0;
    }
    c = LLVMConstInt(LLVMInt32TypeInContext(m->ctx), (unsigned long long)(uint32_t)(int32_t)value, 0);
    if (!c) {
        fail("LLVMConstInt(i32) returned NULL");
        return 0;
    }
    return slot_add(m, c, SHIM_KIND_VALUE);
}

int64_t vshim_const_f64(int64_t module, double value)
{
    shim_module *m;
    LLVMValueRef c;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    c = LLVMConstReal(LLVMDoubleTypeInContext(m->ctx), value);
    if (!c) {
        fail("LLVMConstReal(f64) returned NULL");
        return 0;
    }
    return slot_add(m, c, SHIM_KIND_VALUE);
}

int64_t vshim_const_bool(int64_t module, bool value)
{
    shim_module *m;
    LLVMValueRef c;
    clear_error();
    m = module_of(module);
    if (!m) return 0;
    c = LLVMConstInt(LLVMInt1TypeInContext(m->ctx), value ? 1ULL : 0ULL, 0);
    if (!c) {
        fail("LLVMConstInt(i1) returned NULL");
        return 0;
    }
    return slot_add(m, c, SHIM_KIND_VALUE);
}

/* Would this global's initializer be exactly these bytes?  Used only to merge a
 * literal with an identical one already in the module; anything it cannot read
 * exactly answers "no", which is the safe direction. */
static int literal_matches(LLVMValueRef glob, const char *bytes, size_t len)
{
    LLVMValueRef init = LLVMGetInitializer(glob);
    const char *data;
    size_t got = 0;

    if (!init) return 0;
    if (!LLVMIsConstantString(init)) return 0;
    data = LLVMGetAsString(init, &got);
    if (!data) return 0;
    if (got != len) return 0;
    if (len == 0) return 1;
    return memcmp(data, bytes, len) == 0;
}

int64_t vshim_string_global(int64_t module, vela_str name, vela_str bytes)
{
    char buf[VSHIM_NAME_CAP];
    shim_module *m;
    LLVMValueRef data;
    LLVMValueRef glob;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (copy_cstr(name, buf, sizeof buf, "the global's name") != VSHIM_OK) return 0;
    if (!buf[0]) {
        fail("a string global needs a name");
        return 0;
    }
    if (bytes.len < 0) {
        fail("the string literal has a negative length (%lld)", (long long)bytes.len);
        return 0;
    }
    if (bytes.len > 0 && !bytes.data) {
        fail("the string literal has length %lld but no bytes", (long long)bytes.len);
        return 0;
    }
    /* `DontNullTerminate = 1` on purpose: the array is exactly the bytes the language
     * gave, and the length travels beside the pointer (`vela_llvm_str_lit(ptr, i64)`),
     * so a terminator would be one extra byte in the object and one more thing to get
     * wrong.  A zero-length literal is legal: `[0 x i8]`. */
    data = LLVMConstStringInContext2(m->ctx, bytes.len ? (const char *)bytes.data : "", (size_t)bytes.len, 1);
    if (!data) {
        fail("LLVMConstStringInContext2() returned NULL for the literal \"%s\"", buf);
        return 0;
    }
    /* A literal that is already in this module with the same bytes is reused rather
     * than emitted a second time: an emitter walking an AST sees the same literal
     * once per occurrence, and the C backend interns its strings for the same reason.
     * The comparison is exact -- same length, same bytes -- so this can only merge
     * indistinguishable globals.  Different bytes under one name are *not* merged:
     * LLVM renames the second global and the caller gets a handle to its own bytes,
     * because silently handing back the first one's bytes would be a wrong answer. */
    {
        LLVMValueRef existing = LLVMGetNamedGlobal(m->mod, buf);
        if (existing && literal_matches(existing, bytes.len ? (const char *)bytes.data : "", (size_t)bytes.len)) {
            h = slot_add(m, existing, SHIM_KIND_VALUE);
            if (!h) return 0;
            return h;
        }
    }
    glob = LLVMAddGlobal(m->mod, LLVMTypeOf(data), buf);
    if (!glob) {
        fail("LLVMAddGlobal(\"%s\") returned NULL", buf);
        return 0;
    }
    LLVMSetInitializer(glob, data);
    LLVMSetGlobalConstant(glob, 1);
    LLVMSetLinkage(glob, LLVMPrivateLinkage);
    LLVMSetUnnamedAddress(glob, LLVMGlobalUnnamedAddr);
    h = slot_add(m, glob, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

/* ================================================== blocks, flow, and bodies */

int64_t vshim_block_append(int64_t module, int64_t function, vela_str name)
{
    char buf[VSHIM_NAME_CAP];
    shim_module *m, *owner = NULL;
    LLVMValueRef fn;
    LLVMBasicBlockRef bb;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    fn = (LLVMValueRef)lookup(function, SHIM_KIND_VALUE, &owner);
    if (!fn) return 0;
    if (owner != m) {
        fail("the function belongs to another module");
        return 0;
    }
    if (!value_is_function(fn)) {
        fail("a basic block can only be appended to a function");
        return 0;
    }
    if (copy_cstr(name, buf, sizeof buf, "the block name") != VSHIM_OK) return 0;
    bb = LLVMAppendBasicBlockInContext(m->ctx, fn, buf);
    if (!bb) {
        fail("LLVMAppendBasicBlockInContext() returned NULL");
        return 0;
    }
    h = slot_add(m, bb, SHIM_KIND_BLOCK);
    if (!h) return 0;
    return h;
}

int32_t vshim_pos_at_end(int64_t module, int64_t block)
{
    shim_module *m, *owner = NULL;
    LLVMBasicBlockRef bb;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    bb = (LLVMBasicBlockRef)lookup(block, SHIM_KIND_BLOCK, &owner);
    if (!bb) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the block belongs to another module");
        return VSHIM_ERR_ARG;
    }
    LLVMPositionBuilderAtEnd(m->builder, bb);
    return VSHIM_OK;
}

int32_t vshim_build_br(int64_t module, int64_t block)
{
    shim_module *m, *owner = NULL;
    LLVMBasicBlockRef dest;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    dest = (LLVMBasicBlockRef)lookup(block, SHIM_KIND_BLOCK, &owner);
    if (!dest) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the destination block belongs to another module");
        return VSHIM_ERR_ARG;
    }
    rc = check_builder(m);
    if (rc != VSHIM_OK) return rc;
    if (!LLVMBuildBr(m->builder, dest)) {
        fail("LLVMBuildBr() returned NULL");
        return VSHIM_ERR_LLVM;
    }
    return VSHIM_OK;
}

int32_t vshim_build_cond_br(int64_t module, int64_t condition, int64_t if_true, int64_t if_false)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef cond;
    LLVMBasicBlockRef t, f;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    cond = (LLVMValueRef)lookup(condition, SHIM_KIND_VALUE, &owner);
    if (!cond) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the condition belongs to another module");
        return VSHIM_ERR_ARG;
    }
    if (!type_is_integer(LLVMTypeOf(cond)) || LLVMGetIntTypeWidth(LLVMTypeOf(cond)) != 1) {
        char *printed = LLVMPrintTypeToString(LLVMTypeOf(cond));
        fail("a conditional branch needs an i1 condition (an icmp result, or vshim_const_bool), and this value has type %s",
             printed ? printed : "?");
        if (printed) LLVMDisposeMessage(printed);
        return VSHIM_ERR_ARG;
    }
    t = (LLVMBasicBlockRef)lookup(if_true, SHIM_KIND_BLOCK, &owner);
    if (!t) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the \"if true\" block belongs to another module");
        return VSHIM_ERR_ARG;
    }
    f = (LLVMBasicBlockRef)lookup(if_false, SHIM_KIND_BLOCK, &owner);
    if (!f) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the \"if false\" block belongs to another module");
        return VSHIM_ERR_ARG;
    }
    rc = check_builder(m);
    if (rc != VSHIM_OK) return rc;
    if (!LLVMBuildCondBr(m->builder, cond, t, f)) {
        fail("LLVMBuildCondBr() returned NULL");
        return VSHIM_ERR_LLVM;
    }
    return VSHIM_OK;
}

int32_t vshim_build_ret(int64_t module, int64_t value)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef v;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    v = (LLVMValueRef)lookup(value, SHIM_KIND_VALUE, &owner);
    if (!v) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the returned value belongs to another module");
        return VSHIM_ERR_ARG;
    }
    if (LLVMGetTypeKind(LLVMTypeOf(v)) == LLVMVoidTypeKind) {
        fail("a void value cannot be returned; use vshim_build_ret_void()");
        return VSHIM_ERR_ARG;
    }
    rc = check_builder(m);
    if (rc != VSHIM_OK) return rc;
    if (!LLVMBuildRet(m->builder, v)) {
        fail("LLVMBuildRet() returned NULL");
        return VSHIM_ERR_LLVM;
    }
    return VSHIM_OK;
}

int32_t vshim_build_ret_void(int64_t module)
{
    shim_module *m;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    rc = check_builder(m);
    if (rc != VSHIM_OK) return rc;
    if (!LLVMBuildRetVoid(m->builder)) {
        fail("LLVMBuildRetVoid() returned NULL");
        return VSHIM_ERR_LLVM;
    }
    return VSHIM_OK;
}

/* ==================================================== memory and arithmetic */

int64_t vshim_build_alloca(int64_t module, int64_t type, int64_t align_bytes)
{
    shim_module *m, *owner = NULL;
    LLVMTypeRef ty;
    LLVMValueRef slot;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    ty = (LLVMTypeRef)lookup(type, SHIM_KIND_TYPE, &owner);
    if (!ty) return 0;
    if (owner != m) {
        fail("the type belongs to another module");
        return 0;
    }
    if (align_bytes < 0) {
        fail("an alignment cannot be negative (%lld)", (long long)align_bytes);
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    slot = LLVMBuildAlloca(m->builder, ty, "");
    if (!slot) {
        fail("LLVMBuildAlloca() returned NULL");
        return 0;
    }
    if (align_bytes > 0) LLVMSetAlignment(slot, (unsigned)align_bytes);
    h = slot_add(m, slot, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int64_t vshim_build_load(int64_t module, int64_t type, int64_t pointer)
{
    shim_module *m, *owner = NULL;
    LLVMTypeRef ty;
    LLVMValueRef ptr;
    LLVMValueRef loaded;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    ty = (LLVMTypeRef)lookup(type, SHIM_KIND_TYPE, &owner);
    if (!ty) return 0;
    if (owner != m) {
        fail("the type belongs to another module");
        return 0;
    }
    ptr = (LLVMValueRef)lookup(pointer, SHIM_KIND_VALUE, &owner);
    if (!ptr) return 0;
    if (owner != m) {
        fail("the pointer belongs to another module");
        return 0;
    }
    if (!type_is_pointer(LLVMTypeOf(ptr))) {
        fail("a load needs a pointer to load from");
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    loaded = LLVMBuildLoad2(m->builder, ty, ptr, "");
    if (!loaded) {
        fail("LLVMBuildLoad2() returned NULL");
        return 0;
    }
    h = slot_add(m, loaded, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int32_t vshim_build_store(int64_t module, int64_t value, int64_t pointer)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef v, ptr;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    v = (LLVMValueRef)lookup(value, SHIM_KIND_VALUE, &owner);
    if (!v) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the value belongs to another module");
        return VSHIM_ERR_ARG;
    }
    ptr = (LLVMValueRef)lookup(pointer, SHIM_KIND_VALUE, &owner);
    if (!ptr) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the pointer belongs to another module");
        return VSHIM_ERR_ARG;
    }
    if (!type_is_pointer(LLVMTypeOf(ptr))) {
        fail("a store needs a pointer to store through");
        return VSHIM_ERR_ARG;
    }
    rc = check_builder(m);
    if (rc != VSHIM_OK) return rc;
    if (!LLVMBuildStore(m->builder, v, ptr)) {
        fail("LLVMBuildStore() returned NULL");
        return VSHIM_ERR_LLVM;
    }
    return VSHIM_OK;
}

/* Both operands must be integers of one type: LLVM's own answer to a mismatch is an
 * assertion, and `selfhost/LLVM_PLAN.md` says the checks are never expressed with
 * `nsw`/`nuw`, so the plain builds here are exactly what a loop counter needs. */
static int64_t build_int_binop(int64_t module, int64_t left, int64_t right,
                               LLVMValueRef (*op)(LLVMBuilderRef, LLVMValueRef, LLVMValueRef, const char *),
                               const char *what)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef a, b, r;
    int64_t h;

    m = module_of(module);
    if (!m) return 0;
    a = (LLVMValueRef)lookup(left, SHIM_KIND_VALUE, &owner);
    if (!a) return 0;
    if (owner != m) {
        fail("the left operand belongs to another module");
        return 0;
    }
    b = (LLVMValueRef)lookup(right, SHIM_KIND_VALUE, &owner);
    if (!b) return 0;
    if (owner != m) {
        fail("the right operand belongs to another module");
        return 0;
    }
    if (!type_is_integer(LLVMTypeOf(a)) || !type_is_integer(LLVMTypeOf(b))) {
        fail("%s needs two integers", what);
        return 0;
    }
    if (LLVMTypeOf(a) != LLVMTypeOf(b)) {
        char *pa = LLVMPrintTypeToString(LLVMTypeOf(a));
        char *pb = LLVMPrintTypeToString(LLVMTypeOf(b));
        fail("%s needs both operands to have the same type, and these are %s and %s", what, pa ? pa : "?", pb ? pb : "?");
        if (pa) LLVMDisposeMessage(pa);
        if (pb) LLVMDisposeMessage(pb);
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    r = op(m->builder, a, b, "");
    if (!r) {
        fail("%s returned NULL", what);
        return 0;
    }
    h = slot_add(m, r, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int64_t vshim_build_add(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_int_binop(module, left, right, LLVMBuildAdd, "an integer add");
}

int64_t vshim_build_sub(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_int_binop(module, left, right, LLVMBuildSub, "an integer subtract");
}

int64_t vshim_build_mul(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_int_binop(module, left, right, LLVMBuildMul, "an integer multiply");
}

static int64_t build_float_binop(int64_t module, int64_t left, int64_t right,
                                 LLVMValueRef (*op)(LLVMBuilderRef, LLVMValueRef, LLVMValueRef, const char *),
                                 const char *what)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef a, b, r;
    int64_t h;

    m = module_of(module);
    if (!m) return 0;
    a = (LLVMValueRef)lookup(left, SHIM_KIND_VALUE, &owner);
    if (!a) return 0;
    if (owner != m) {
        fail("the left operand belongs to another module");
        return 0;
    }
    b = (LLVMValueRef)lookup(right, SHIM_KIND_VALUE, &owner);
    if (!b) return 0;
    if (owner != m) {
        fail("the right operand belongs to another module");
        return 0;
    }
    if (!type_is_float(LLVMTypeOf(a)) || !type_is_float(LLVMTypeOf(b)) || LLVMTypeOf(a) != LLVMTypeOf(b)) {
        fail("%s needs two floating-point operands of the same type", what);
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    r = op(m->builder, a, b, "");
    if (!r) {
        fail("%s returned NULL", what);
        return 0;
    }
    h = slot_add(m, r, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

int64_t vshim_build_fadd(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_float_binop(module, left, right, LLVMBuildFAdd, "a float add");
}

int64_t vshim_build_fsub(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_float_binop(module, left, right, LLVMBuildFSub, "a float subtract");
}

int64_t vshim_build_fmul(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_float_binop(module, left, right, LLVMBuildFMul, "a float multiply");
}

int64_t vshim_build_fdiv(int64_t module, int64_t left, int64_t right)
{
    clear_error();
    return build_float_binop(module, left, right, LLVMBuildFDiv, "a float divide");
}

int64_t vshim_build_icmp(int64_t module, int32_t predicate, int64_t left, int64_t right)
{
    shim_module *m, *owner = NULL;
    LLVMValueRef a, b, r;
    LLVMIntPredicate pred;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;

    switch (predicate) {
    case VSHIM_CMP_EQ:  pred = LLVMIntEQ;  break;
    case VSHIM_CMP_NE:  pred = LLVMIntNE;  break;
    case VSHIM_CMP_SLT: pred = LLVMIntSLT; break;
    case VSHIM_CMP_SLE: pred = LLVMIntSLE; break;
    case VSHIM_CMP_SGT: pred = LLVMIntSGT; break;
    case VSHIM_CMP_SGE: pred = LLVMIntSGE; break;
    case VSHIM_CMP_ULT: pred = LLVMIntULT; break;
    case VSHIM_CMP_ULE: pred = LLVMIntULE; break;
    case VSHIM_CMP_UGT: pred = LLVMIntUGT; break;
    case VSHIM_CMP_UGE: pred = LLVMIntUGE; break;
    default:
        fail("comparison kind %d is not one of the VSHIM_CMP_* values", (int)predicate);
        return 0;
    }

    a = (LLVMValueRef)lookup(left, SHIM_KIND_VALUE, &owner);
    if (!a) return 0;
    if (owner != m) {
        fail("the left operand belongs to another module");
        return 0;
    }
    b = (LLVMValueRef)lookup(right, SHIM_KIND_VALUE, &owner);
    if (!b) return 0;
    if (owner != m) {
        fail("the right operand belongs to another module");
        return 0;
    }
    if (!type_is_integer(LLVMTypeOf(a)) || LLVMTypeOf(a) != LLVMTypeOf(b)) {
        fail("a comparison needs two integers of the same type");
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    r = LLVMBuildICmp(m->builder, pred, a, b, "");
    if (!r) {
        fail("LLVMBuildICmp() returned NULL");
        return 0;
    }
    h = slot_add(m, r, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

/* The address of one element, which is what `a[i]` needs: an array in this
 * language is a pointer (the C back end emits `int64_t*`), and nothing can be read
 * out of one without `getelementptr`.
 *
 * The index is deliberately **not** bounds-checked here.  The check is a call to
 * `vela_llvm_bounds_check` that the emitter makes first, so there is exactly one
 * place -- the runtime, shared with the C back end -- that decides what an
 * out-of-range index means and what it prints.  Checking here as well would be a
 * second answer to the same question, and a `gep` with an unchecked index is how a
 * language that is checked by default quietly stops being one. */
int64_t vshim_build_gep(int64_t module, int64_t element_type, int64_t pointer,
                        int64_t index)
{
    shim_module *m, *owner = NULL;
    LLVMTypeRef ty;
    LLVMValueRef ptr, idx, r;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    ty = (LLVMTypeRef)lookup(element_type, SHIM_KIND_TYPE, &owner);
    if (!ty) return 0;
    if (owner != m) {
        fail("the element type belongs to another module");
        return 0;
    }
    ptr = (LLVMValueRef)lookup(pointer, SHIM_KIND_VALUE, &owner);
    if (!ptr) return 0;
    if (owner != m) {
        fail("the pointer belongs to another module");
        return 0;
    }
    if (!type_is_pointer(LLVMTypeOf(ptr))) {
        fail("an element address needs a pointer to index into");
        return 0;
    }
    idx = (LLVMValueRef)lookup(index, SHIM_KIND_VALUE, &owner);
    if (!idx) return 0;
    if (owner != m) {
        fail("the index belongs to another module");
        return 0;
    }
    if (!type_is_integer(LLVMTypeOf(idx))) {
        fail("an element index must be an integer");
        return 0;
    }
    if (check_builder(m) != VSHIM_OK) return 0;
    r = LLVMBuildGEP2(m->builder, ty, ptr, &idx, 1, "");
    if (!r) {
        fail("LLVMBuildGEP2() returned NULL");
        return 0;
    }
    h = slot_add(m, r, SHIM_KIND_VALUE);
    if (!h) return 0;
    return h;
}

/* ============================================================ named structs
 *
 * A Vela struct becomes one *named* LLVM struct type: the type exists first and
 * its fields are added after, which is the ordering a recursive struct needs and
 * the ordering that lets a struct value be one `load`/`store` pair instead of a
 * copy per field.  The name is not load-bearing for the object -- LLVM struct
 * names never reach the object file -- but it is what makes the IR text a person
 * reads say `%vl_Vec2` instead of `%struct.anon.7`.
 */

int64_t vshim_struct_type_opaque_buf(int64_t module)
{
    shim_module *m;
    vela_str name = buffer_as_str();
    char buf[VSHIM_NAME_CAP];
    LLVMTypeRef t;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    if (copy_cstr(name, buf, sizeof buf, "a struct type name") != VSHIM_OK) return 0;
    t = LLVMStructCreateNamed(m->ctx, buf);
    if (!t) {
        fail("LLVMStructCreateNamed() returned NULL");
        return 0;
    }
    if (m->layout_count == m->layout_cap) {
        size_t cap = m->layout_cap ? m->layout_cap * 2 : 8;
        shim_layout *grown = (shim_layout *)realloc(m->layouts,
                                                    cap * sizeof(shim_layout));
        if (!grown) {
            fail("out of memory recording a struct type");
            return 0;
        }
        m->layouts = grown;
        m->layout_cap = cap;
    }
    m->layouts[m->layout_count].type    = t;
    m->layouts[m->layout_count].fields  = NULL;
    m->layouts[m->layout_count].count   = 0;
    m->layouts[m->layout_count].cap     = 0;
    m->layout_count++;
    return slot_add(m, t, SHIM_KIND_TYPE);
}

int32_t vshim_struct_set_body(int64_t module, int64_t struct_type,
                              int64_t field_type)
{
    shim_module *m, *owner = NULL;
    shim_layout *l = NULL;
    LLVMTypeRef t, f;
    size_t i;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    t = (LLVMTypeRef)lookup(struct_type, SHIM_KIND_TYPE, &owner);
    if (!t) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the struct type belongs to another module");
        return VSHIM_ERR_ARG;
    }
    f = (LLVMTypeRef)lookup(field_type, SHIM_KIND_TYPE, &owner);
    if (!f) return VSHIM_ERR_ARG;
    if (owner != m) {
        fail("the field type belongs to another module");
        return VSHIM_ERR_ARG;
    }
    /* The struct must be one this shim created: LLVMStructSetBody on a type from
     * somewhere else is how a caller would reshape a type out from under values
     * already built against it. */
    for (i = 0; i < m->layout_count; i++) {
        if (m->layouts[i].type == t) { l = &m->layouts[i]; break; }
    }
    if (!l) {
        fail("this type is not a named struct this shim created");
        return VSHIM_ERR_ARG;
    }
    if (l->cap == l->count) {
        unsigned cap = l->cap ? l->cap * 2 : 4;
        LLVMTypeRef *grown = (LLVMTypeRef *)realloc(l->fields,
                                                    cap * sizeof(LLVMTypeRef));
        if (!grown) {
            fail("out of memory laying out a struct");
            return VSHIM_ERR_NO_MEMORY;
        }
        l->fields = grown;
        l->cap = cap;
    }
    l->fields[l->count++] = f;
    LLVMStructSetBody(t, l->fields, l->count, 0);
    return VSHIM_OK;
}

/* ==================================================================== calls */

int64_t vshim_arglist_begin(int64_t module)
{
    shim_module *m;
    shim_args *a;
    int64_t h;

    clear_error();
    m = module_of(module);
    if (!m) return 0;
    a = (shim_args *)calloc(1, sizeof(shim_args));
    if (!a) {
        fail("out of memory allocating an argument list");
        return 0;
    }
    a->m = m;
    h = slot_add(m, a, SHIM_KIND_ARGS);
    if (!h) {
        free(a);
        return 0;
    }
    return h;
}

int32_t vshim_arglist_add(int64_t arguments, int64_t value)
{
    shim_args *a;
    shim_module *owner = NULL;
    LLVMValueRef v;

    clear_error();
    a = (shim_args *)lookup(arguments, SHIM_KIND_ARGS, NULL);
    if (!a) return VSHIM_ERR_ARG;
    v = (LLVMValueRef)lookup(value, SHIM_KIND_VALUE, &owner);
    if (!v) return VSHIM_ERR_ARG;
    if (owner != a->m) {
        fail("the argument belongs to another module");
        return VSHIM_ERR_ARG;
    }
    if (LLVMGetTypeKind(LLVMTypeOf(v)) == LLVMVoidTypeKind) {
        fail("a void value cannot be passed as an argument (argument %u)", a->count);
        return VSHIM_ERR_ARG;
    }
    if (a->count == a->cap) {
        unsigned want = a->cap ? a->cap * 2 : 8;
        LLVMValueRef *grown = (LLVMValueRef *)realloc(a->values, want * sizeof(LLVMValueRef));
        if (!grown) {
            fail("out of memory growing an argument list");
            return VSHIM_ERR_NO_MEMORY;
        }
        a->values = grown;
        a->cap = want;
    }
    a->values[a->count] = v;
    a->count++;
    return VSHIM_OK;
}

int32_t vshim_arglist_abandon(int64_t arguments)
{
    shim_args *a;
    clear_error();
    a = (shim_args *)lookup(arguments, SHIM_KIND_ARGS, NULL);
    if (!a) return VSHIM_ERR_ARG;
    consume(arguments);
    free(a->values);
    free(a);
    return VSHIM_OK;
}

int64_t vshim_call(int64_t arguments, int64_t function)
{
    shim_args *a;
    shim_module *am = NULL, *fm = NULL;
    LLVMValueRef fn;
    LLVMTypeRef fn_ty;
    LLVMValueRef call;
    int64_t h;

    clear_error();
    a = (shim_args *)lookup(arguments, SHIM_KIND_ARGS, &am);
    if (!a) return 0;
    fn = (LLVMValueRef)lookup(function, SHIM_KIND_VALUE, &fm);
    if (!fn) return 0;   /* the list survives a failure, so the caller can retry */
    if (am != fm) {
        fail("the callee belongs to another module than the argument list");
        return 0;
    }
    if (check_callee(fm, fn, a) != VSHIM_OK) return 0;
    if (check_builder(fm) != VSHIM_OK) return 0;

    fn_ty = LLVMGlobalGetValueType(fn);
    call = LLVMBuildCall2(fm->builder, fn_ty, fn, a->values, a->count, "");
    if (!call) {
        fail("LLVMBuildCall2() returned NULL");
        return 0;
    }
    h = slot_add(fm, call, SHIM_KIND_VALUE);
    if (!h) return 0;
    consume(arguments);
    free(a->values);
    free(a);
    return h;
}

/* =================================================================== output */

int32_t vshim_verify(int64_t module)
{
    shim_module *m;
    char *msg = NULL;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    /* LLVMReturnStatusAction, never LLVMAbortProcessAction: the verifier's other
     * two modes print to stderr or abort, and neither is a status a caller can
     * branch on.  This one hands back a message. */
    if (LLVMVerifyModule(m->mod, LLVMReturnStatusAction, &msg) != 0) {
        fail("the module does not verify: %s", msg ? msg : "(the verifier gave no message)");
        if (msg) LLVMDisposeMessage(msg);
        return VSHIM_ERR_VERIFY;
    }
    if (msg) LLVMDisposeMessage(msg);
    return VSHIM_OK;
}

int32_t vshim_emit_object(int64_t module, vela_str path)
{
    char buf[VSHIM_PATH_CAP];
    shim_module *m;
    char *err = NULL;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) return VSHIM_ERR_ARG;
    if (!g_target_machine) {
        fail("no target machine: vshim_open() has not run, or vshim_shutdown() disposed it");
        return VSHIM_ERR_STATE;
    }
    rc = copy_cstr(path, buf, sizeof buf, "the output path");
    if (rc != VSHIM_OK) return rc;
    if (!buf[0]) {
        fail("the output path is empty");
        return VSHIM_ERR_ARG;
    }

    /* Verify *before* codegen: the verifier is what turns "the emitter built
     * something malformed" into a sentence naming the instruction, instead of an
     * assertion inside the code generator or an object that fails at link time. */
    rc = vshim_verify(module);
    if (rc != VSHIM_OK) return rc;

    if (LLVMTargetMachineEmitToFile(g_target_machine, m->mod, buf, LLVMObjectFile, &err) != 0) {
        fail("could not write the object file \"%s\": %s", buf, err ? err : "(LLVM gave no message)");
        if (err) LLVMDisposeMessage(err);
        return VSHIM_ERR_EMIT;
    }
    if (err) LLVMDisposeMessage(err);
    return VSHIM_OK;
}

/* ============================================================= the byte buffer
 *
 * Every function here is either a buffer primitive or a one-line delegation to the
 * `str` spelling with a `str` built over the buffer.  That is deliberate: the two
 * spellings then *cannot* drift, and the probe proves they produce identical
 * objects by building the same program through both.
 *
 * The four primitives are the hot path -- a symbol name costs one call per byte --
 * so they do no work beyond a bounds check, a store and the length update.
 */

int32_t vshim_buf_reset(void)
{
    /* No `clear_error()`: this cannot fail, and a caller that has just failed wants
     * to read the message back, not to lose it. */
    g_buffer.len = 0;
    return VSHIM_OK;
}

int32_t vshim_buf_byte(int32_t b)
{
    clear_error();
    if (b < 0 || b > 255) {
        fail("a buffer byte must be 0..255, and this one is %d", (int)b);
        return VSHIM_ERR_ARG;
    }
    if (g_buffer.len >= VSHIM_BUF_MAX) {
        fail("the byte buffer is full at its cap of %llu bytes", (unsigned long long)VSHIM_BUF_MAX);
        return VSHIM_ERR_TOO_LONG;
    }
    if (buffer_grow(g_buffer.len + 1) != VSHIM_OK) return VSHIM_ERR_NO_MEMORY;
    g_buffer.data[g_buffer.len++] = (unsigned char)b;
    return VSHIM_OK;
}

int32_t vshim_buf_len(void)
{
    /* No `clear_error()`: a reader. */
    return (int32_t)g_buffer.len;
}

int32_t vshim_buf_read_byte(int64_t index)
{
    /* No `clear_error()`: this is how the message from `vshim_last_error_buf` is
     * read back, so it must not destroy it. */
    if (index < 0 || (uint64_t)index >= (uint64_t)g_buffer.len) return -1;
    return (int32_t)g_buffer.data[(size_t)index];
}

int64_t vshim_module_open_buf(void)
{
    return vshim_module_open(buffer_as_str());
}

int64_t vshim_fn_declare_buf(int64_t module, int64_t signature_type)
{
    return vshim_fn_declare(module, buffer_as_str(), signature_type);
}

int64_t vshim_fn_define_buf(int64_t module, int64_t signature_type)
{
    return vshim_fn_define(module, buffer_as_str(), signature_type);
}

int64_t vshim_string_global_buf(int64_t module, int64_t index)
{
    char name[64];
    int n;

    clear_error();
    if (index < 0) {
        fail("a string literal's index cannot be negative (%lld)", (long long)index);
        return 0;
    }
    /* The name is derived from the index because one buffer cannot carry both a
     * literal's bytes and its name; the index is the literal's identity. */
    n = snprintf(name, sizeof name, ".s%lld", (long long)index);
    if (n <= 0 || (size_t)n >= sizeof name) {
        fail("could not name the literal at index %lld", (long long)index);
        return 0;
    }
    return vshim_string_global(module, vela_str_lit(name, (int64_t)n), buffer_as_str());
}

int64_t vshim_block_append_buf(int64_t module, int64_t function)
{
    return vshim_block_append(module, function, buffer_as_str());
}

int32_t vshim_emit_object_buf(int64_t module)
{
    return vshim_emit_object(module, buffer_as_str());
}

int32_t vshim_host_triple_buf(void)
{
    /* No `clear_error()`: this is a pure read of state the shim already holds, and a
     * caller may be using it to describe a failure that just happened. */
    if (buffer_set(g_triple, strlen(g_triple)) != VSHIM_OK) return VSHIM_ERR_TOO_LONG;
    return VSHIM_OK;
}

int32_t vshim_last_error_buf(void)
{
    /* No `clear_error()` -- that is the entire point of this function. */
    size_t len = strlen(g_error);
    if (buffer_set(g_error, len) != VSHIM_OK) return VSHIM_ERR_TOO_LONG;
    return VSHIM_OK;
}

int32_t vshim_module_ir_buf(int64_t module)
{
    shim_module *m;
    char *text;
    size_t len;
    int32_t rc;

    clear_error();
    m = module_of(module);
    if (!m) {
        g_buffer.len = 0;
        return VSHIM_ERR_ARG;
    }
    text = LLVMPrintModuleToString(m->mod);
    if (!text) {
        fail("LLVMPrintModuleToString() returned NULL");
        g_buffer.len = 0;
        return VSHIM_ERR_LLVM;
    }
    len = strlen(text);
    /* Unlike the `str` spelling -- a fixed 64 KiB window that truncates, because a
     * `str` return cannot allocate -- this one carries the whole module and refuses
     * rather than truncating.  `buffer_set` empties the buffer on failure, so a
     * caller can never read stale bytes as this call's result. */
    if (len > VSHIM_BUF_MAX) {
        fail("this module's IR is %llu bytes, over the byte buffer's cap of %llu",
             (unsigned long long)len, (unsigned long long)VSHIM_BUF_MAX);
        g_buffer.len = 0;
        LLVMDisposeMessage(text);
        return VSHIM_ERR_TOO_LONG;
    }
    rc = buffer_set(text, len);
    LLVMDisposeMessage(text);
    if (rc != VSHIM_OK) return rc;
    return VSHIM_OK;
}
