; ---------------------------------------------------------------------------
; Vela, LLVM backend, milestone M1: the pipeline, proved with hand-written IR.
;
; No emitter exists yet, on purpose.  `selfhost/LLVM_PLAN.md` says why: a fast
; backend that is wrong is not a backend, so the toolchain gets proved with an
; `.ll` written by hand, whose expected output is known independently, before
; anything generates IR.
;
; Everything below that is a convention rather than a choice was MEASURED on this
; machine (LLVM 23.1.1, `clang version 23.1.1`, target x86_64-pc-windows-msvc),
; not remembered:
;
;   * the module header, from `clang.exe --target=x86_64-pc-windows-msvc
;     -S -emit-llvm -O2` on a two-line C file:
;
;       target datalayout = "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-
;                            i128:128-f80:128-n8:16:32:64-S128"
;       target triple    = "x86_64-pc-windows-msvc19.44.35227"
;
;   * the ABI for `vela_str`, from clang's own IR for a C file that calls the
;     runtime.  It is the Microsoft x86-64 ABI, and it is not the System V one:
;     a 16-byte struct is passed **by reference** and returned through a hidden
;     pointer, so the declarations below are
;
;       declare void @vela_llvm_print_str(ptr)
;       declare void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)
;
;     and not `..._print_str(%vela_str)` / `...str_lit(...) -> %vela_str`.  Both
;     spellings *look* right; only one of them links and runs.  This is exactly
;     the class of assumption the plan exists to catch.
;
; The program prints the same two lines as `m1_probe.vel` beside it, which the
; interpreter and the C backend also produce; `tools\llvm-m1.ps1` compares all
; three byte for byte.  That comparison, not this file, is the evidence.
; ---------------------------------------------------------------------------

target datalayout = "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-pc-windows-msvc"

%vela_str = type { ptr, i64 }

@.hello = private unnamed_addr constant [15 x i8] c"hello from Vela"
@.m1    = private unnamed_addr constant [3 x i8] c"m1:"

declare void @vela_llvm_set_args(i32, ptr)
declare void @vela_llvm_runtime_init()
declare void @vela_llvm_print_str(ptr)
declare void @vela_llvm_print_sep()
declare void @vela_llvm_print_i64(i64)
declare void @vela_llvm_print_nl()
declare void @vela_llvm_str_lit(ptr sret(%vela_str), ptr, i64)

define i32 @main(i32 %argc, ptr %argv) {
entry:
  call void @vela_llvm_set_args(i32 %argc, ptr %argv)
  call void @vela_llvm_runtime_init()

  ; print("hello from Vela")
  %line = alloca %vela_str, align 8
  call void @vela_llvm_str_lit(ptr sret(%vela_str) %line, ptr @.hello, i64 15)
  call void @vela_llvm_print_str(ptr %line)
  call void @vela_llvm_print_nl()

  ; print("m1:", 1 + 2)  -- the separator is the runtime's, exactly as in the C backend
  %label = alloca %vela_str, align 8
  call void @vela_llvm_str_lit(ptr sret(%vela_str) %label, ptr @.m1, i64 3)
  call void @vela_llvm_print_str(ptr %label)
  call void @vela_llvm_print_sep()
  call void @vela_llvm_print_i64(i64 3)
  call void @vela_llvm_print_nl()

  ret i32 0
}
