/* ---------------------------------------------------------------------------
 * Phase-2 feasibility spike: **can this machine build machine code in-process?**
 *
 * The north star for Vela says the product must be its own: `vm.exe build x.vel`
 * should write an executable and leave nothing else behind, with no visible C
 * compiler in the loop -- the way Rust uses LLVM internally.  The plan's phase 2
 * is therefore a pure-C shim over the `llvm-c` C API, linked into `vm.exe`, which
 * writes an object file itself.
 *
 * That plan is worth exactly as much as the answer to one question, and this file
 * is the question: *does `llvm-c` link and run here at all?*  It is deliberately
 * tiny and deliberately hand-written, for the same reason M1 was: prove a pipeline
 * with something whose answer you already know (`main` returns 42) before writing
 * an emitter on top of it.
 *
 * What it does, using nothing but the C API:
 *
 *   1. a module for x86_64-pc-windows-msvc,
 *   2. one function `main` that returns the constant 42,
 *   3. a target machine for the host, and `LLVMTargetMachineEmitToFile` writing a
 *      real COFF object to a path given on the command line.
 *
 * Then the object is linked (with `lld-link`, which ships beside this LLVM) and
 * run: exit code 42 means the whole in-process path works, and phase 2 is an
 * engineering problem rather than a research one.
 *
 * Compiled with `cl /std:c11` against `include\` and `lib\LLVM-C.lib` from the
 * portable LLVM in `tools\get-llvm.ps1`'s output directory.
 * ------------------------------------------------------------------------- */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <llvm-c/Core.h>
#include <llvm-c/Target.h>
#include <llvm-c/TargetMachine.h>

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: phase2_spike <out.obj>\n");
        return 1;
    }
    const char *out = argv[1];

    /* Every target has to be initialized before a TargetMachine can be made; this
     * is the step a "helpful" tutorial usually omits, and omitting it produces a
     * triple that looks right and a target machine that is NULL. */
    LLVMInitializeX86TargetInfo();
    LLVMInitializeX86Target();
    LLVMInitializeX86TargetMC();
    LLVMInitializeX86AsmPrinter();
    LLVMInitializeX86AsmParser();

    LLVMContextRef ctx = LLVMContextCreate();
    LLVMModuleRef mod = LLVMModuleCreateWithNameInContext("vela_phase2_spike", ctx);

    char *triple = LLVMGetDefaultTargetTriple();
    LLVMSetTarget(mod, triple);
    printf("host triple: %s\n", triple);

    LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
    LLVMTypeRef fnTy = LLVMFunctionType(i32, NULL, 0, 0);
    LLVMValueRef fn = LLVMAddFunction(mod, "main", fnTy);
    LLVMBasicBlockRef entry = LLVMAppendBasicBlockInContext(ctx, fn, "entry");
    LLVMBuilderRef b = LLVMCreateBuilderInContext(ctx);
    LLVMPositionBuilderAtEnd(b, entry);
    LLVMBuildRet(b, LLVMConstInt(i32, 42, 0));

    char *err = NULL;
    LLVMTargetRef target = NULL;
    if (LLVMGetTargetFromTriple(triple, &target, &err) != 0) {
        fprintf(stderr, "no target for %s: %s\n", triple, err ? err : "?");
        return 1;
    }

    LLVMTargetMachineRef tm = LLVMCreateTargetMachine(
        target, triple, "generic", "",
        LLVMCodeGenLevelDefault, LLVMRelocDefault, LLVMCodeModelDefault);
    if (!tm) {
        fprintf(stderr, "could not create a target machine\n");
        return 1;
    }

    if (LLVMTargetMachineEmitToFile(tm, mod, out, LLVMObjectFile, &err) != 0) {
        fprintf(stderr, "could not write %s: %s\n", out, err ? err : "?");
        return 1;
    }
    printf("wrote object: %s\n", out);

    LLVMDisposeTargetMachine(tm);
    LLVMDisposeBuilder(b);
    LLVMDisposeModule(mod);
    LLVMContextDispose(ctx);
    LLVMDisposeMessage(triple);
    return 0;
}
