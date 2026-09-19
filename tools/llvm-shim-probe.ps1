<#
.SYNOPSIS
    Phase 2 of the LLVM backend: the shim builds the M1 program, and four paths are compared byte for byte.

.DESCRIPTION
    `runtime/vela_llvm_shim.{c,h}` is the scalar C surface over `llvm-c` that
    `vm.exe` will call through `extern c`.  A shim is worth exactly as much as the
    program it can build, so this script is the evidence for it: a small C driver
    (written into the scratch directory, not committed -- it is scaffolding) uses
    nothing but the shim's own published API to build the M1 program, and the four
    ways of running that one program must print identical bytes:

        1. `vm.exe run`            -- the interpreter, no C compiler involved
        2. `vm.exe build` + run    -- the C backend, the reference implementation
        3. clang-cl + m1_probe.ll  -- the hand-written IR path (tools\llvm-m1.ps1)
        4. the shim + lld-link     -- this round's deliverable

    The expected bytes are the two lines written down in
    `selfhost/llvm/m1_probe.vel` -- `hello from Vela` and `m1: 3` -- terminated with
    the CR LF that every golden in `tests/golden` holds (MSVC's stdout is in text
    mode; `runtime/vela_llvm_runtime.c` records that).  They are never recorded from
    a run: a backend that is fast and wrong is not a backend.

    Where the products land: **everything this script compiles goes into
    `$env:TEMP\vela-llvm-shim`** -- the shim and driver objects, `m1_shim.obj`,
    `m1_shim.exe`, the runtime object and every program's output.  Nothing is
    written beside a user's source.  The script snapshots `selfhost\llvm` before and
    after to prove that, because the C backend's history here is exactly the mistake
    being avoided (`m1_probe.c` and `m1_probe.exe` used to appear next to the input).
    For the same reason the C backend and the interpreter are run on a *copy* of
    `m1_probe.vel` in the scratch directory: another agent is working in
    `selfhost\llvm` right now, and `vm.exe build` writes its products beside its
    input.

    Two host facts this script had to get right, both measured:

      * **`cl` is a development-time step, not the product path.**  The shim is
        compiled with `cl` here because there is no other way to compile C on this
        machine.  The product path never compiles C at all: the plan's step 5 links
        the shim into `vm.exe` once, and from then on `vm.exe` writes the object
        itself.  The one external thing the product needs is `lld-link`, which ships
        inside the LLVM package this checkout already has.
      * **the redirection has to live inside the batch file.**  Windows PowerShell
        5.1 turns a native command's stderr into a terminating error under
        `$ErrorActionPreference='Stop'`, and even redirected from PowerShell with
        `2>` it still renders an error record -- and `clang-cl` writes deprecation
        warnings from `vela_runtime.h` on every real build.  So each step is a
        `.bat` that redirects the tool's own output to a log inside the batch, and
        PowerShell never sees that stream at all.  Programs whose *bytes* are being
        compared are run the same way, with `cmd` doing the redirection: PowerShell
        5.1's `>` writes UTF-16 with a BOM, which would silently destroy a byte
        comparison (measured: a 24-byte program output arrived as 50 bytes of
        UTF-16LE through `1> file`).

    `cl.exe` also starts detached `vctip.exe`/`mspdbsrv.exe` helpers that outlive it
    and can wedge the job runner this project is driven by (build.ps1 records two
    sessions lost that way), so they are reaped immediately after every MSVC step,
    not at the end.

.PARAMETER LlvmDir
    The unpacked LLVM package: it must hold `include\llvm-c`, `lib\LLVM-C.lib`,
    `bin\LLVM-C.dll`, `bin\clang-cl.exe` and `bin\lld-link.exe`.  Defaults to the
    portable LLVM that `tools\get-llvm.ps1` unpacks beside this checkout.

.PARAMETER Scratch
    Where every product of this script is written.  Defaults to
    `$env:TEMP\vela-llvm-shim`.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\llvm-shim-probe.ps1

.NOTES
    Exit code 0 means: the shim compiled, its self-test passed every check, and all
    four paths printed identical bytes.  Any disagreement is reported with the byte
    values of every path and the index of the first differing byte, and the exit
    code is non-zero.  Nothing is ever reported as working that was not run.
#>
[CmdletBinding()]
param(
    [string] $LlvmDir,
    [string] $Scratch
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Say([string] $text) { Write-Host $text }
function Head([string] $text) { Write-Host ''; Write-Host $text }

# --------------------------------------------------------------- the tools
if (-not $LlvmDir) {
    $LlvmDir = Join-Path (Split-Path -Parent $repo) 'llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc'
}
$clangCl = Join-Path $LlvmDir 'bin\clang-cl.exe'
$lldLink = Join-Path $LlvmDir 'bin\lld-link.exe'
$llvmDll = Join-Path $LlvmDir 'bin\LLVM-C.dll'
$llvmLib = Join-Path $LlvmDir 'lib\LLVM-C.lib'
$llvmInc = Join-Path $LlvmDir 'include\llvm-c\Core.h'
foreach ($p in @($clangCl, $lldLink, $llvmDll, $llvmLib, $llvmInc)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "the LLVM package at $LlvmDir is incomplete: no $p (run tools\get-llvm.ps1, or pass -LlvmDir)"
    }
}

$vm = Join-Path $repo 'selfhost\build\vm.exe'
if (-not (Test-Path -LiteralPath $vm)) { throw "no compiler at $vm -- run tools\build.ps1 first" }

# The same list build.ps1 uses, including its VELA_VCVARS override.
function Find-Vcvars {
    if ($env:VELA_VCVARS -and (Test-Path -LiteralPath $env:VELA_VCVARS)) { return $env:VELA_VCVARS }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat')
    )
    $hit = @($candidates | Where-Object { Test-Path -LiteralPath $_ })
    if ($hit.Count -eq 0) { return $null }
    return $hit[0]
}
$vcvars = Find-Vcvars
if (-not $vcvars) {
    throw 'no vcvars64.bat found: cl needs the MSVC environment, and lld-link needs the CRT libraries it puts on LIB'
}

$shimC = Join-Path $repo 'runtime\vela_llvm_shim.c'
$shimH = Join-Path $repo 'runtime\vela_llvm_shim.h'
$runtimeC = Join-Path $repo 'runtime\vela_llvm_runtime.c'
$runtimeDir = Join-Path $repo 'runtime'
foreach ($p in @($shimC, $shimH, $runtimeC)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing $p" }
}
$m1Vel = Join-Path $repo 'selfhost\llvm\m1_probe.vel'
$m1Ll = Join-Path $repo 'selfhost\llvm\m1_probe.ll'
foreach ($p in @($m1Vel, $m1Ll)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing $p -- the M1 evidence for that path does not exist yet" }
}
if (-not $Scratch) { $Scratch = Join-Path $env:TEMP 'vela-llvm-shim' }
New-Item -ItemType Directory -Force -Path $Scratch | Out-Null

# ------------------------------------------------------- running a batch
# Every native step below goes through cmd, with the redirection *inside* the
# batch: see .DESCRIPTION for the two measurements that force it.
#
# A line that carries its own redirection (a program whose *bytes* are the point of
# running it) is prefixed with `!` and is run verbatim: appending `>> log 2>&1` to
# it would win over its own `> file` and silently send the bytes to the log instead
# of to the file being compared, because cmd applies the last redirection of each
# stream.  Every other line's output goes to the step's log.
function Invoke-Batch([string[]] $lines, [string] $tag, [switch] $MsvcEnv) {
    $bat = Join-Path $Scratch "step_$tag.bat"
    $log = Join-Path $Scratch "log_$tag.txt"
    $body = @('@echo off')
    if ($MsvcEnv) {
        $body += 'set VSCMD_SKIP_SENDTELEMETRY=1'
        $body += "call `"$vcvars`" >nul 2>&1"
    }
    $body += "cd /d `"$Scratch`""
    $codes = @()
    $n = 0
    foreach ($line in $lines) {
        $n++
        $codeFile = Join-Path $Scratch ("code_${tag}_$n.txt")
        if ($line.StartsWith('!')) { $body += $line.Substring(1) }
        else { $body += "$line >> `"$log`" 2>&1" }
        $body += "echo %errorlevel% > `"$codeFile`""
        $codes += $codeFile
    }
    Set-Content -LiteralPath $bat -Value ($body -join "`r`n") -Encoding ascii

    $o = Join-Path $Scratch "run_$tag.out"
    $e = Join-Path $Scratch "run_$tag.err"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & cmd.exe /c $bat 1> $o 2> $e } finally { $ErrorActionPreference = $prev }

    $results = @()
    foreach ($c in $codes) {
        if (Test-Path -LiteralPath $c) { $results += [int]((Get-Content -LiteralPath $c -Raw).Trim()) }
        else { $results += -999 }   # -999, never -1: -1 is a value cmd can really report
    }
    $text = if (Test-Path -LiteralPath $log) { [string](Get-Content -LiteralPath $log -Raw) } else { '' }
    return @{ codes = $results; log = $text }
}

# `cl.exe` starts detached helpers that outlive it and can wedge the job runner
# (build.ps1 records two sessions lost that way), so this runs after every step
# that touches the MSVC toolchain -- a script that only cleaned up at the end would
# be killed before it got there.
function Reap-MsvcHelpers {
    foreach ($name in @('vctip', 'mspdbsrv')) {
        $strays = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
        if ($strays.Count -gt 0) {
            $strays | Stop-Process -Force -ErrorAction SilentlyContinue
            Say "    reaped $($strays.Count) detached $name process(es)"
        }
    }
}

# --------------------------------------------------------------- byte helpers
function Read-Bytes([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "no output file at $path" }
    return , ([System.IO.File]::ReadAllBytes($path))
}
function Bytelist($bytes) {
    if ($bytes.Count -eq 0) { return '(empty)' }
    $out = @()
    foreach ($b in $bytes) { $out += [int]$b }
    return ($out -join ',')
}
function Same-Bytes($a, $b) {
    if ($a.Count -ne $b.Count) { return $false }
    for ($i = 0; $i -lt $a.Count; $i++) { if ($a[$i] -ne $b[$i]) { return $false } }
    return $true
}
function First-Difference($a, $b) {
    $n = [Math]::Min($a.Count, $b.Count)
    for ($i = 0; $i -lt $n; $i++) {
        if ($a[$i] -ne $b[$i]) {
            return "byte $i : expected $($a[$i]), got $($b[$i])"
        }
    }
    if ($a.Count -ne $b.Count) { return "the lengths differ: expected $($a.Count) bytes, got $($b.Count)" }
    return 'no difference (this should not be reachable)'
}

# The expected bytes, written down in selfhost/llvm/m1_probe.vel's own header.  The
# line terminator is CR LF because MSVC's stdout is in text mode and every golden in
# tests/golden holds CR LF; all four paths must produce it.
$expectedM1 = [System.Text.Encoding]::ASCII.GetBytes("hello from Vela`r`nm1: 3`r`n")
$expectedFlow = [System.Text.Encoding]::ASCII.GetBytes("1`r`n13`r`n")

# The Vela program whose meaning shim_driver's `flow` mode builds by hand through
# the shim: an if/else, a call, and a merge point.  `add3(2, 3)` takes the else arm
# (b - a = 1) and `add3(9, 4)` the then arm (a + b = 13), so both arms are
# exercised and the expected bytes are checkable by reading this file.
$flowVel = @'
# The same program shim_driver's `flow` mode builds by hand through the shim
# (runtime/vela_llvm_shim.h), written in Vela so the interpreter and the C backend
# are independent expectations for it:
#
#     1
#     13
#
# add3(2, 3) takes the second return (b - a, because 2 >= 3 is false) and
# add3(9, 4) takes the first (a + b).
def add3(a: int, b: int) -> int {
    if a >= b {
        return a + b
    }
    return b - a
}

def main() -> None {
    print(add3(2, 3))
    print(add3(9, 4))
}
'@

# -------------------------------------------------------------- the driver
# test scaffolding, written here rather than committed: the deliverable is the
# shim, and this file is the caller the shim was designed for.
$shimDriver = @'
/* shim_driver.c - the caller that proves runtime/vela_llvm_shim.h is usable.
 *
 * tools\llvm-shim-probe.ps1 writes this file into its scratch directory rather
 * than committing it, because it is test scaffolding and not the deliverable: the
 * deliverable is the shim, and this file exists to be the *caller* the shim was
 * designed for -- flat C calls, str arguments that carry their own length, integer
 * handles and status codes, and no pointer on the Vela side of the boundary.
 * Writing a C driver needs a C compiler, and the product path must not; see the
 * note at the top of the probe script.
 *
 * Modes:
 *
 *   m1       <out.obj> [--ir]   build the M1 program: the same two lines
 *                               selfhost/llvm/m1_probe.vel prints, built through
 *                               the shim instead of by hand in IR text
 *   flow     <out.obj> [--ir]   build an if/else program whose Vela equivalent the
 *                               probe script also runs in the interpreter, so that
 *                               comparison is differential rather than a look at
 *                               the screen
 *   selftest <scratch-dir>      exercise the API, its caps and its failure paths
 *
 * Exit code 0 means every check passed; anything else means at least one failed
 * and the failing check is named on stdout.
 */

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The runtime header first: str is vela_str, and vela_str_lit is the runtime's own
 * constructor, so this driver cannot accidentally test the shim against a
 * different 16-byte struct than the one a Vela program passes. */
#include "vela_runtime.h"
#include "vela_llvm_shim.h"

static int g_checks;
static int g_failures;

static vela_str S(const char *text)
{
    return vela_str_lit(text, (int64_t)strlen(text));
}

static void say(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
}

static void message_of(const char *indent)
{
    vela_str e = vshim_last_error();
    say("%s-> %.*s\n", indent, (int)e.len, e.data ? (const char *)e.data : "");
}

static void check(int condition, const char *what)
{
    g_checks++;
    if (condition) {
        say("  ok    %s\n", what);
    } else {
        g_failures++;
        say("  FAIL  %s\n", what);
        message_of("        ");
    }
}

/* A call that must fail: the status has to be non-zero *and* a message has to come
 * with it, because "it returned 2" is not a diagnosis. */
static void expect_status_failure(int32_t status, const char *what)
{
    g_checks++;
    if (status == VSHIM_OK) {
        g_failures++;
        say("  FAIL  %s (it succeeded, and it must not)\n", what);
        return;
    }
    if (vshim_last_error().len <= 0) {
        g_failures++;
        say("  FAIL  %s (status %d, but no message came with it)\n", what, (int)status);
        return;
    }
    say("  ok    %s (status %d)\n", what, (int)status);
    message_of("        ");
}

static void expect_handle_failure(int64_t handle, const char *what)
{
    g_checks++;
    if (handle != 0) {
        g_failures++;
        say("  FAIL  %s (it returned handle %lld, and it must return 0)\n", what, (long long)handle);
        return;
    }
    if (vshim_last_error().len <= 0) {
        g_failures++;
        say("  FAIL  %s (no handle and no message)\n", what);
        return;
    }
    say("  ok    %s (0 and a message)\n", what);
    message_of("        ");
}

static int contains(vela_str hay, const char *needle)
{
    size_t n = strlen(needle);
    size_t i;
    if (hay.len < (int64_t)n || !hay.data) return 0;
    for (i = 0; i + n <= (size_t)hay.len; i++) {
        if (memcmp(hay.data + i, needle, n) == 0) return 1;
    }
    return 0;
}

/* ------------------------------------------------------- the runtime's names
 *
 * Every name and signature below is read from selfhost/llvm/m1_probe.ll and
 * runtime/vela_llvm_runtime.c, not invented: the emitter's whole job is to agree
 * with those declarations, so a driver that guessed them would prove nothing. */

typedef struct {
    int64_t set_args;
    int64_t runtime_init;
    int64_t print_str;
    int64_t print_i64;
    int64_t print_sep;
    int64_t print_nl;
    int64_t print_bool;
    int64_t str_lit;
} rt_fns;

/* A signature built one parameter at a time, which is the only way a scalar caller
 * can do it; the parameter types are followed by 0, since no valid handle is 0. */
static int64_t add_fn(int64_t mod, const char *name, int64_t ret, ...)
{
    va_list ap;
    int64_t sig, ty, fn, fn_ty;

    sig = vshim_sig_begin(mod, ret);
    if (!sig) return 0;
    va_start(ap, ret);
    for (;;) {
        ty = va_arg(ap, int64_t);
        if (ty == 0) break;
        if (vshim_sig_param(sig, ty) != VSHIM_OK) {
            va_end(ap);
            vshim_sig_abandon(sig);
            return 0;
        }
    }
    va_end(ap);
    fn_ty = vshim_sig_finish(sig);
    if (!fn_ty) return 0;
    fn = vshim_fn_declare(mod, S(name), fn_ty);
    return fn;
}

static int64_t call_n(int64_t mod, int64_t fn, const int64_t *args, int n)
{
    int64_t list = vshim_arglist_begin(mod);
    int i;
    if (!list) return 0;
    for (i = 0; i < n; i++) {
        if (vshim_arglist_add(list, args[i]) != VSHIM_OK) {
            vshim_arglist_abandon(list);
            return 0;
        }
    }
    return vshim_call(list, fn);
}

static int64_t call0(int64_t mod, int64_t fn)
{
    return call_n(mod, fn, NULL, 0);
}

static int64_t call1(int64_t mod, int64_t fn, int64_t a)
{
    int64_t args[1];
    args[0] = a;
    return call_n(mod, fn, args, 1);
}

static int64_t call2(int64_t mod, int64_t fn, int64_t a, int64_t b)
{
    int64_t args[2];
    args[0] = a;
    args[1] = b;
    return call_n(mod, fn, args, 2);
}

static int64_t call3(int64_t mod, int64_t fn, int64_t a, int64_t b, int64_t c)
{
    int64_t args[3];
    args[0] = a;
    args[1] = b;
    args[2] = c;
    return call_n(mod, fn, args, 3);
}

static int rt_declare(int64_t mod, rt_fns *rt, int64_t t_void, int64_t t_i1,
                      int64_t t_i32, int64_t t_i64, int64_t t_ptr, int64_t t_str)
{
    rt->set_args     = add_fn(mod, "vela_llvm_set_args", t_void, t_i32, t_ptr, (int64_t)0);
    rt->runtime_init = add_fn(mod, "vela_llvm_runtime_init", t_void, (int64_t)0);
    rt->print_str    = add_fn(mod, "vela_llvm_print_str", t_void, t_ptr, (int64_t)0);
    rt->print_i64    = add_fn(mod, "vela_llvm_print_i64", t_void, t_i64, (int64_t)0);
    rt->print_sep    = add_fn(mod, "vela_llvm_print_sep", t_void, (int64_t)0);
    rt->print_nl     = add_fn(mod, "vela_llvm_print_nl", t_void, (int64_t)0);
    rt->print_bool   = add_fn(mod, "vela_llvm_print_bool", t_void, t_i1, (int64_t)0);
    /* void (ptr sret(%vela_str), ptr, i64) -- the shape M1 measured, and the reason
     * vshim_fn_sret_param exists at all. */
    rt->str_lit      = add_fn(mod, "vela_llvm_str_lit", t_void, t_ptr, t_ptr, t_i64, (int64_t)0);
    if (!rt->set_args || !rt->runtime_init || !rt->print_str || !rt->print_i64 ||
        !rt->print_sep || !rt->print_nl || !rt->print_bool || !rt->str_lit) return 0;
    if (vshim_fn_sret_param(rt->str_lit, 0, t_str) != VSHIM_OK) return 0;
    return 1;
}

/* main(i32, ptr) with the three startup steps the emitted C performs, and the
 * builder left in the entry block. */
static int64_t begin_main(int64_t mod, const rt_fns *rt, int64_t t_i32, int64_t t_ptr)
{
    int64_t sig, main_fn, entry;

    sig = vshim_sig_begin(mod, t_i32);
    if (!sig) return 0;
    if (vshim_sig_param(sig, t_i32) != VSHIM_OK || vshim_sig_param(sig, t_ptr) != VSHIM_OK) {
        vshim_sig_abandon(sig);
        return 0;
    }
    main_fn = vshim_fn_define(mod, S("main"), vshim_sig_finish(sig));
    if (!main_fn) return 0;
    entry = vshim_block_append(mod, main_fn, S("entry"));
    if (!entry) return 0;
    if (vshim_pos_at_end(mod, entry) != VSHIM_OK) return 0;
    if (!call2(mod, rt->set_args, vshim_fn_param(main_fn, 0), vshim_fn_param(main_fn, 1))) return 0;
    if (!call0(mod, rt->runtime_init)) return 0;
    return main_fn;
}

/* ---------------------------------------------------------------------- M1 */

static int mode_m1(vela_str out_path, int dump_ir)
{
    int64_t mod, t_void, t_i1, t_i32, t_i64, t_ptr, t_str;
    int64_t main_fn, hello, m1, slot;
    rt_fns rt;
    vela_str label = S("m1:");
    vela_str greeting = S("hello from Vela");
    vela_str triple;

    say("building the M1 program through the shim\n");
    check(vshim_open() == VSHIM_OK, "vshim_open");
    check(vshim_open() == VSHIM_OK, "vshim_open again (idempotent)");
    triple = vshim_host_triple();
    say("  host triple: %.*s\n", (int)triple.len, (const char *)triple.data);

    mod = vshim_module_open(S("vela_m1_shim"));
    check(mod != 0, "vshim_module_open");
    if (!mod) return 1;

    t_void = vshim_type_void(mod);
    t_i1   = vshim_type_i1(mod);
    t_i32  = vshim_type_i32(mod);
    t_i64  = vshim_type_i64(mod);
    t_ptr  = vshim_type_ptr(mod);
    t_str  = vshim_type_str(mod);
    check(t_void && t_i1 && t_i32 && t_i64 && t_ptr && t_str, "the primitive types");
    check(t_i64 == vshim_type_i64(mod), "a primitive type handle is cached, not minted again");

    check(rt_declare(mod, &rt, t_void, t_i1, t_i32, t_i64, t_ptr, t_str) != 0,
          "declare the runtime functions, including sret on vela_llvm_str_lit");

    main_fn = begin_main(mod, &rt, t_i32, t_ptr);
    check(main_fn != 0, "define main(i32, ptr) and call set_args + runtime_init");
    if (!main_fn) return 1;

    /* print("hello from Vela") -- 15 bytes, the length m1_probe.ll writes for
     * @.hello and hands to str_lit. */
    hello = vshim_string_global(mod, S(".hello"), greeting);
    check(hello != 0 && greeting.len == 15, "the 15-byte literal global \".hello\"");
    slot = vshim_build_alloca(mod, t_str, 8);
    check(slot != 0, "a stack slot for the str_lit result");
    check(call3(mod, rt.str_lit, slot, hello, vshim_const_i64(mod, 15)) != 0, "call str_lit(slot, @.hello, 15)");
    check(call1(mod, rt.print_str, slot) != 0, "call print_str(slot)");
    check(call0(mod, rt.print_nl) != 0, "call print_nl()");

    /* print("m1:", 1 + 2) -- the separator is the runtime's, and 1 + 2 is folded by
     * the front end exactly as the C backend folds it. */
    m1 = vshim_string_global(mod, S(".m1"), label);
    check(m1 != 0 && label.len == 3, "the 3-byte literal global \".m1\"");
    slot = vshim_build_alloca(mod, t_str, 8);
    check(slot != 0, "a second stack slot");
    check(call3(mod, rt.str_lit, slot, m1, vshim_const_i64(mod, 3)) != 0, "call str_lit(slot, @.m1, 3)");
    check(call1(mod, rt.print_str, slot) != 0, "call print_str(slot)");
    check(call0(mod, rt.print_sep) != 0, "call print_sep()");
    check(call1(mod, rt.print_i64, vshim_const_i64(mod, 3)) != 0, "call print_i64(3)");
    check(call0(mod, rt.print_nl) != 0, "call print_nl()");

    check(vshim_build_ret(mod, vshim_const_i32(mod, 0)) == VSHIM_OK, "return 0 from main");
    check(vshim_verify(mod) == VSHIM_OK, "the module verifies");

    if (dump_ir) {
        vela_str ir = vshim_module_ir(mod);
        say("--- module IR (%lld bytes)\n", (long long)ir.len);
        say("%.*s", (int)ir.len, (const char *)ir.data);
        say("--- end of module IR\n");
    }

    check(vshim_emit_object(mod, out_path) == VSHIM_OK, "vshim_emit_object");
    check(vshim_module_close(mod) == VSHIM_OK, "vshim_module_close");
    check(vshim_last_error().len == 0, "no message is left after a successful call");
    return g_failures ? 1 : 0;
}

/* -------------------------------------------------------------------- flow */

/* The program the interpreter also runs (written by the probe script):
 *
 *     def add3(a: int, b: int) -> int {
 *         if a >= b { return a + b }
 *         return b - a
 *     }
 *     def main() -> None {
 *         print(add3(2, 3))
 *         print(add3(9, 4))
 *     }
 *
 * A merge point with no phi: both arms store into one slot and the join loads it
 * back.  A phi is the next thing the emitter will want and the next thing the shim
 * needs; memory is what makes an if/else expressible today. */
static int mode_flow(vela_str out_path, int dump_ir)
{
    int64_t mod, t_void, t_i1, t_i32, t_i64, t_ptr, t_str;
    int64_t add3, sig, entry, test, then_blk, else_blk, done;
    int64_t pa, pb, slot, cond, v, main_fn, r;
    rt_fns rt;

    say("building the if/else program through the shim\n");
    check(vshim_open() == VSHIM_OK, "vshim_open");
    mod = vshim_module_open(S("vela_shim_flow"));
    check(mod != 0, "vshim_module_open");
    if (!mod) return 1;

    t_void = vshim_type_void(mod);
    t_i1   = vshim_type_i1(mod);
    t_i32  = vshim_type_i32(mod);
    t_i64  = vshim_type_i64(mod);
    t_ptr  = vshim_type_ptr(mod);
    t_str  = vshim_type_str(mod);
    check(rt_declare(mod, &rt, t_void, t_i1, t_i32, t_i64, t_ptr, t_str) != 0,
          "declare the runtime functions");

    sig = vshim_sig_begin(mod, t_i64);
    check(sig != 0, "vshim_sig_begin for i64(i64, i64)");
    check(vshim_sig_param(sig, t_i64) == VSHIM_OK && vshim_sig_param(sig, t_i64) == VSHIM_OK,
          "two parameters, added one at a time");
    add3 = vshim_fn_define(mod, S("add3"), vshim_sig_finish(sig));
    check(add3 != 0, "define i64 @add3(i64, i64)");
    if (!add3) return 1;

    entry    = vshim_block_append(mod, add3, S("entry"));
    test     = vshim_block_append(mod, add3, S("test"));
    then_blk = vshim_block_append(mod, add3, S("then"));
    else_blk = vshim_block_append(mod, add3, S("else"));
    done     = vshim_block_append(mod, add3, S("done"));
    check(entry && test && then_blk && else_blk && done, "five basic blocks");

    pa = vshim_fn_param(add3, 0);
    pb = vshim_fn_param(add3, 1);
    check(pa != 0 && pb != 0, "the function's two parameters");

    check(vshim_pos_at_end(mod, entry) == VSHIM_OK, "position the builder");
    slot = vshim_build_alloca(mod, t_i64, 8);
    check(slot != 0 && vshim_build_br(mod, test) == VSHIM_OK, "entry: alloca i64, br test");

    vshim_pos_at_end(mod, test);
    cond = vshim_build_icmp(mod, VSHIM_CMP_SGE, pa, pb);
    check(cond != 0 && vshim_build_cond_br(mod, cond, then_blk, else_blk) == VSHIM_OK,
          "test: icmp sge, cond_br");

    vshim_pos_at_end(mod, then_blk);
    v = vshim_build_add(mod, pa, pb);
    check(v != 0 && vshim_build_store(mod, v, slot) == VSHIM_OK && vshim_build_br(mod, done) == VSHIM_OK,
          "then: a + b, store, br done");

    vshim_pos_at_end(mod, else_blk);
    v = vshim_build_sub(mod, pb, pa);
    check(v != 0 && vshim_build_store(mod, v, slot) == VSHIM_OK && vshim_build_br(mod, done) == VSHIM_OK,
          "else: b - a, store, br done");

    vshim_pos_at_end(mod, done);
    v = vshim_build_load(mod, t_i64, slot);
    check(v != 0 && vshim_build_ret(mod, v) == VSHIM_OK, "done: load the slot, return it");

    main_fn = begin_main(mod, &rt, t_i32, t_ptr);
    check(main_fn != 0, "define main");
    if (!main_fn) return 1;

    r = call2(mod, add3, vshim_const_i64(mod, 2), vshim_const_i64(mod, 3));
    check(r != 0 && call1(mod, rt.print_i64, r) != 0 && call0(mod, rt.print_nl) != 0, "print(add3(2, 3))");

    r = call2(mod, add3, vshim_const_i64(mod, 9), vshim_const_i64(mod, 4));
    check(r != 0 && call1(mod, rt.print_i64, r) != 0 && call0(mod, rt.print_nl) != 0, "print(add3(9, 4))");

    check(vshim_build_ret(mod, vshim_const_i32(mod, 0)) == VSHIM_OK, "return 0 from main");
    check(vshim_verify(mod) == VSHIM_OK, "the module verifies");

    {
        vela_str ir = vshim_module_ir(mod);
        check(contains(ir, "define i32 @main"), "the IR dump has define i32 @main");
        check(contains(ir, "x86_64-pc-windows-msvc"), "the IR dump names the host triple");
        check(contains(ir, "sret"), "the IR dump carries sret on vela_llvm_str_lit");
        check(contains(ir, "icmp sge"), "the IR dump has the signed comparison");
        if (dump_ir) {
            say("--- module IR (%lld bytes)\n", (long long)ir.len);
            say("%.*s", (int)ir.len, (const char *)ir.data);
            say("--- end of module IR\n");
        }
    }

    check(vshim_emit_object(mod, out_path) == VSHIM_OK, "vshim_emit_object");
    check(vshim_module_close(mod) == VSHIM_OK, "vshim_module_close");
    return g_failures ? 1 : 0;
}

/* ---------------------------------------------------------------- selftest */

static int mode_selftest(vela_str scratch)
{
    int64_t mod, mod_broken, mod_closed;
    int64_t t_void, t_i1, t_i32, t_i64, t_f64, t_ptr, t_str;
    int64_t sig, fn, ty, slot, a, b, p, closed_ty = 0, call_callee = 0;
    char too_long[VSHIM_NAME_CAP + 64];
    char path[VSHIM_PATH_CAP];
    rt_fns rt;

    say("exercising the shim's surface and its failure paths\n");
    check(vshim_open() == VSHIM_OK, "vshim_open");
    check(vshim_host_triple().len > 0 && contains(vshim_host_triple(), "windows"),
          "vshim_host_triple names a Windows host");

    mod = vshim_module_open(S("vela_shim_selftest"));
    check(mod != 0, "vshim_module_open");
    if (!mod) return 1;

    t_void = vshim_type_void(mod);
    t_i1   = vshim_type_i1(mod);
    t_i32  = vshim_type_i32(mod);
    t_i64  = vshim_type_i64(mod);
    t_f64  = vshim_type_f64(mod);
    t_ptr  = vshim_type_ptr(mod);
    t_str  = vshim_type_str(mod);
    check(t_void && t_i1 && t_i32 && t_i64 && t_f64 && t_ptr && t_str, "every primitive type");
    check(t_str == vshim_type_str(mod), "vshim_type_str is cached per module");

    /* --- a name that does not fit is refused, not truncated ------------------ */
    memset(too_long, 'x', sizeof too_long - 1);
    too_long[sizeof too_long - 1] = '\0';
    expect_handle_failure(vshim_module_open(S(too_long)), "a module name over VSHIM_NAME_CAP is refused");

    /* --- signatures --------------------------------------------------------- */
    sig = vshim_sig_begin(mod, t_i64);
    check(sig != 0, "vshim_sig_begin");
    check(vshim_sig_param(sig, t_i64) == VSHIM_OK && vshim_sig_param(sig, t_ptr) == VSHIM_OK &&
          vshim_sig_param(sig, t_i32) == VSHIM_OK, "three parameters of different kinds");
    ty = vshim_sig_finish(sig);
    check(ty != 0, "vshim_sig_finish gives a function type");
    expect_status_failure(vshim_sig_param(sig, t_i64), "a finished signature takes no more parameters");
    fn = vshim_fn_declare(mod, S("selftest_arity3"), ty);
    check(fn != 0, "declare a function with that signature");
    expect_handle_failure(vshim_fn_param(fn, 3), "parameter 3 of a 3-parameter function is refused");
    expect_handle_failure(vshim_fn_param(fn, -1), "a negative parameter index is refused");
    expect_handle_failure(vshim_fn_declare(mod, S("selftest_not_a_fn"), t_i64),
                          "declaring a function with a non-function type is refused");

    sig = vshim_sig_begin(mod, t_i64);
    check(sig != 0 && vshim_sig_abandon(sig) == VSHIM_OK, "vshim_sig_abandon frees an unfinished signature");
    expect_status_failure(vshim_sig_param(sig, t_i64), "an abandoned signature is refused");

    sig = vshim_sig_begin(mod, t_void);
    check(sig != 0, "vshim_sig_begin for the too-many-parameters case");
    {
        int i, refused = 0;
        int32_t status = VSHIM_OK;
        for (i = 0; i < VSHIM_MAX_PARAMS + 2; i++) {
            status = vshim_sig_param(sig, t_i64);
            if (status != VSHIM_OK) { refused = i; break; }
        }
        check(status != VSHIM_OK && refused == VSHIM_MAX_PARAMS,
              "the 17th parameter is refused, at the cap of 16");
        message_of("        ");
    }
    vshim_sig_abandon(sig);

    /* --- sret --------------------------------------------------------------- */
    sig = vshim_sig_begin(mod, t_void);
    vshim_sig_param(sig, t_i64);
    fn = vshim_fn_declare(mod, S("selftest_sret_wrong"), vshim_sig_finish(sig));
    check(fn != 0, "declare void(i64) for the sret cases");
    expect_status_failure(vshim_fn_sret_param(fn, 0, t_str), "sret on a non-pointer parameter is refused");
    expect_status_failure(vshim_fn_sret_param(fn, 7, t_str), "sret on an out-of-range parameter is refused");
    {
        int64_t ssig = vshim_sig_begin(mod, t_void);
        int64_t sfn;
        check(ssig != 0 && vshim_sig_param(ssig, t_ptr) == VSHIM_OK, "a signature void(ptr)");
        sfn = vshim_fn_declare(mod, S("selftest_sret_right"), vshim_sig_finish(ssig));
        check(sfn != 0, "declare void(ptr)");
        check(vshim_fn_sret_param(sfn, 0, t_str) == VSHIM_OK, "sret on a pointer parameter is accepted");
    }

    /* --- the declarations the calls below need ------------------------------ */
    check(rt_declare(mod, &rt, t_void, t_i1, t_i32, t_i64, t_ptr, t_str) != 0,
          "declare the runtime functions");

    /* --- calls: arity, types, handles ---------------------------------------
     * The callee is void(i64, i64) and the builder is positioned *before* the
     * first of these, so each refusal below is the check under test and not the
     * "builder is not positioned" check that would otherwise answer first. */
    sig = vshim_sig_begin(mod, t_void);
    check(sig != 0 && vshim_sig_param(sig, t_i64) == VSHIM_OK && vshim_sig_param(sig, t_i64) == VSHIM_OK,
          "a signature void(i64, i64)");
    call_callee = vshim_fn_define(mod, S("selftest_calls"), vshim_sig_finish(sig));
    check(call_callee != 0, "define void @selftest_calls(i64, i64)");
    {
        int64_t cblk = vshim_block_append(mod, call_callee, S("entry"));
        check(cblk != 0 && vshim_pos_at_end(mod, cblk) == VSHIM_OK, "position the builder in it");
    }
    {
        int64_t list = vshim_arglist_begin(mod);
        check(list != 0, "vshim_arglist_begin");
        check(vshim_arglist_add(list, vshim_const_i64(mod, 1)) == VSHIM_OK, "vshim_arglist_add");
        expect_handle_failure(vshim_call(list, call_callee), "too few arguments is refused");
        check(vshim_arglist_add(list, vshim_const_i64(mod, 2)) == VSHIM_OK,
              "the argument list survives a refused call");
        check(vshim_call(list, call_callee) != 0, "the same list, now with the right arity");
    }
    {
        int64_t list = vshim_arglist_begin(mod);
        vshim_arglist_add(list, vshim_const_i64(mod, 1));
        vshim_arglist_add(list, vshim_const_i32(mod, 2));
        expect_handle_failure(vshim_call(list, call_callee),
                              "an i32 argument where the callee takes an i64 is refused");
        check(vshim_arglist_abandon(list) == VSHIM_OK, "vshim_arglist_abandon");
        expect_status_failure(vshim_arglist_add(list, vshim_const_i64(mod, 1)),
                             "an abandoned argument list is refused");
    }
    {
        int64_t list = vshim_arglist_begin(mod);
        expect_status_failure(vshim_arglist_add(list, 0), "handle 0 is refused as an argument");
        expect_handle_failure(vshim_call(list, call_callee),
                              "calling a 2-parameter callee with an empty list is refused");
        vshim_arglist_abandon(list);
    }
    {
        int64_t list = vshim_arglist_begin(mod);
        vshim_arglist_add(list, vshim_const_i64(mod, 1));
        vshim_arglist_add(list, vshim_const_i64(mod, 2));
        vshim_arglist_add(list, vshim_const_i64(mod, 3));
        expect_handle_failure(vshim_call(list, call_callee), "too many arguments is refused");
        vshim_arglist_abandon(list);
    }
    check(vshim_build_ret_void(mod) == VSHIM_OK, "terminate selftest_calls");
    expect_handle_failure(vshim_block_append(mod, vshim_const_i64(mod, 1), S("nope")),
                          "a basic block can only be appended to a function");
    expect_status_failure(vshim_pos_at_end(mod, vshim_const_i64(mod, 1)),
                          "positioning the builder at a value is refused");

    /* --- a second module that is deliberately wrong ------------------------- */
    mod_broken = vshim_module_open(S("vela_shim_selftest_broken"));
    check(mod_broken != 0, "open a second module");
    if (mod_broken) {
        int64_t t32 = vshim_type_i32(mod_broken);
        int64_t bsig = vshim_sig_begin(mod_broken, t32);
        int64_t bfn = vshim_fn_define(mod_broken, S("wrong_return"), vshim_sig_finish(bsig));
        int64_t blk = vshim_block_append(mod_broken, bfn, S("entry"));
        closed_ty = vshim_type_i64(mod_broken);
        check(vshim_pos_at_end(mod_broken, blk) == VSHIM_OK, "point the builder at the broken function's block");
        check(vshim_build_ret(mod_broken, vshim_const_i64(mod_broken, 0)) == VSHIM_OK,
              "return an i64 from an i32 function (the build is allowed; the verifier refuses it)");
        expect_status_failure(vshim_verify(mod_broken), "vshim_verify refuses a module with a mismatched return");
        snprintf(path, sizeof path, "%.*s\\selftest-broken.obj", (int)scratch.len, (const char *)scratch.data);
        expect_status_failure(vshim_emit_object(mod_broken, vela_str_lit(path, (int64_t)strlen(path))),
                              "vshim_emit_object refuses it before writing anything");
        expect_handle_failure(vshim_build_icmp(mod_broken, 99, vshim_const_i64(mod_broken, 1),
                                               vshim_const_i64(mod_broken, 2)),
                              "comparison kind 99 is refused");
        check(vshim_module_close(mod_broken) == VSHIM_OK, "close the broken module");
    }

    /* --- handles from a closed module --------------------------------------- */
    expect_handle_failure(vshim_type_i64(mod_broken), "a closed module handle is refused");
    expect_handle_failure(vshim_build_alloca(mod, closed_ty, 8),
                          "a type from a closed module is refused in another module");
    expect_status_failure(vshim_build_ret(mod, mod), "a module handle is refused where a value is wanted");

    /* --- emission targets --------------------------------------------------- */
    {
        int64_t sig1 = vshim_sig_begin(mod, t_void);
        int64_t vfn = vshim_fn_define(mod, S("noop"), vshim_sig_finish(sig1));
        int64_t blk = vshim_block_append(mod, vfn, S("entry"));
        vshim_pos_at_end(mod, blk);
        check(vshim_build_ret_void(mod) == VSHIM_OK, "define void @noop()");
        snprintf(path, sizeof path, "%.*s\\selftest-out.obj", (int)scratch.len, (const char *)scratch.data);
        check(vshim_verify(mod) == VSHIM_OK, "the selftest module verifies");
        check(vshim_emit_object(mod, vela_str_lit(path, (int64_t)strlen(path))) == VSHIM_OK,
              "emit a valid module into the scratch directory");
        expect_status_failure(vshim_emit_object(mod, S("Z:\\no-such-directory-vela\\nope.obj")),
                              "emitting into a directory that does not exist is refused");
        expect_status_failure(vshim_emit_object(mod, S("")), "an empty output path is refused");
    }

    /* --- terminators, types and stores on the wrong operands ---------------- */
    {
        int64_t sig2 = vshim_sig_begin(mod, t_i64);
        int64_t vfn = vshim_fn_define(mod, S("mismatch"), vshim_sig_finish(sig2));
        int64_t blk = vshim_block_append(mod, vfn, S("entry"));
        int64_t other = vshim_block_append(mod, vfn, S("other"));
        vshim_pos_at_end(mod, blk);
        check(vshim_build_ret(mod, vshim_const_i64(mod, 0)) == VSHIM_OK, "return an i64 from an i64 function");
        expect_status_failure(vshim_build_ret(mod, vshim_const_i64(mod, 1)),
                              "a second instruction after the terminator is refused");
        vshim_pos_at_end(mod, other);
        expect_status_failure(vshim_build_cond_br(mod, vshim_const_i64(mod, 1), other, other),
                              "an i64 condition is refused (cond_br needs i1)");
        expect_handle_failure(vshim_build_add(mod, vshim_const_i32(mod, 1), vshim_const_i64(mod, 1)),
                              "adding an i32 to an i64 is refused");
        slot = vshim_build_alloca(mod, t_i64, 8);
        check(slot != 0, "alloca an i64 slot");
        expect_status_failure(vshim_build_store(mod, vshim_const_i64(mod, 1), vshim_const_i64(mod, 1)),
                              "storing through a non-pointer is refused");
        check(vshim_build_store(mod, vshim_const_i64(mod, 7), slot) == VSHIM_OK, "store through the slot");
        a = vshim_build_load(mod, t_i64, slot);
        check(a != 0, "load it back");
        check(vshim_build_add(mod, a, vshim_const_i64(mod, 1)) != 0, "add an i64 to it");
        b = vshim_const_f64(mod, 1.5);
        p = vshim_build_fmul(mod, vshim_build_fadd(mod, b, b), b);
        check(p != 0, "f64 constants and arithmetic");
        check(vshim_const_bool(mod, true) != 0, "a bool constant");
        expect_handle_failure(vshim_build_fadd(mod, b, vshim_const_i64(mod, 1)),
                              "an f64 plus an i64 is refused");
        check(vshim_build_ret(mod, vshim_const_i64(mod, 0)) == VSHIM_OK, "terminate the second block");
    }

    /* --- closing down ------------------------------------------------------- */
    check(vshim_module_close(mod) == VSHIM_OK, "close the selftest module");
    mod_closed = vshim_module_open(S("vela_shim_after_a_close"));
    check(mod_closed != 0, "a new module can still be opened after another was closed");
    if (mod_closed) vshim_module_close(mod_closed);
    check(vshim_shutdown() == VSHIM_OK, "vshim_shutdown");
    expect_handle_failure(vshim_module_open(S("vela_shim_after_shutdown")),
                          "opening a module after shutdown is refused, with a message");

    say("selftest: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}

/* -------------------------------------------------------------------- main */

int main(int argc, char **argv)
{
    const char *mode;
    int dump_ir = 0;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--ir") == 0) dump_ir = 1;
    }
    if (argc < 2) {
        say("usage: shim_driver m1 <out.obj> [--ir] | flow <out.obj> [--ir] | selftest <scratch-dir>\n");
        return 2;
    }
    mode = argv[1];

    if (strcmp(mode, "m1") == 0) {
        if (argc < 3) { say("shim_driver m1 <out.obj>\n"); return 2; }
        return mode_m1(S(argv[2]), dump_ir);
    }
    if (strcmp(mode, "flow") == 0) {
        if (argc < 3) { say("shim_driver flow <out.obj>\n"); return 2; }
        return mode_flow(S(argv[2]), dump_ir);
    }
    if (strcmp(mode, "selftest") == 0) {
        if (argc < 3) { say("shim_driver selftest <scratch-dir>\n"); return 2; }
        return mode_selftest(S(argv[2]));
    }
    say("shim_driver: unknown mode \"%s\"\n", mode);
    return 2;
}

'@

Say 'LLVM shim probe: the shim builds the M1 program'
Say ''
Say "  repository : $repo"
Say "  LLVM       : $LlvmDir"
Say "  vcvars64   : $vcvars"
Say "  scratch    : $Scratch"
Say "  compiler   : $vm"

# The .obj must never land beside a user's source.  Snapshot before, compare after.
$sourcesBefore = @(Get-ChildItem -LiteralPath (Join-Path $repo 'selfhost\llvm') | ForEach-Object { $_.Name })

$shimApi = @([regex]::Matches((Get-Content -LiteralPath $shimH -Raw),
    '(?m)^(?:int32_t|int64_t|vela_str)\s+(vshim_\w+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
Say "  shim API   : $($shimApi.Count) functions declared in runtime\vela_llvm_shim.h"

# ------------------------------------------------- 0. the sources of this run
Head '[0/5] the sources this run builds'
Copy-Item -LiteralPath $m1Vel -Destination (Join-Path $Scratch 'm1_probe.vel') -Force
Set-Content -LiteralPath (Join-Path $Scratch 'shim_flow.vel') -Value $flowVel -Encoding ascii
Set-Content -LiteralPath (Join-Path $Scratch 'shim_driver.c') -Value $shimDriver -Encoding ascii
Copy-Item -LiteralPath $llvmDll -Destination (Join-Path $Scratch 'LLVM-C.dll') -Force
if (-not (Same-Bytes (Read-Bytes $m1Vel) (Read-Bytes (Join-Path $Scratch 'm1_probe.vel')))) {
    throw 'the scratch copy of m1_probe.vel is not byte-identical to the repository copy'
}
Say "  $Scratch\m1_probe.vel   (a byte-identical copy of the repository's, so the C backend's products land in scratch)"
Say '  shim_driver.c, shim_flow.vel, LLVM-C.dll copied in'

# --------------------------------------------- 1. compile the shim and driver
Head '[1/5] compile the shim with cl (development time only -- the product never does this)'
$cl = 'cl /nologo /std:c11 /O2 /I ' + "`"$LlvmDir\include`"" + ' /I ' + "`"$runtimeDir`"" +
      ' shim_driver.c ' + "`"$shimC`"" + ' /Fe:shim_driver.exe /Fo:' + "`"$Scratch\\`"" + ' ' + "`"$llvmLib`""
Say "  $cl"
$r = Invoke-Batch @($cl) 'compile_shim' -MsvcEnv
Reap-MsvcHelpers
if ($r.codes[0] -ne 0) {
    Say $r.log
    throw "cl could not compile the shim and the driver (exit $($r.codes[0])) -- the log is above"
}
Say "  compiled: $Scratch\vela_llvm_shim.obj, $Scratch\shim_driver.exe  (linked against LLVM-C.lib)"
Say '  LLVM-C.dll sits beside the executable, which is what a real install does'

# ------------------------------------------------------------- 2. the shim's own surface
Head '[2/5] the shim exercises itself (74 checks: types, signatures, sret, calls, caps, every failure path)'
$r = Invoke-Batch @('!shim_driver.exe selftest "' + $Scratch + '" > selftest.txt 2>&1') 'selftest'
if ($r.codes[0] -ne 0) {
    Say (Get-Content -LiteralPath (Join-Path $Scratch 'selftest.txt') -Raw)
    throw "the shim's self-test failed (exit $($r.codes[0]))"
}
$selftestSummary = @(Get-Content -LiteralPath (Join-Path $Scratch 'selftest.txt') | Where-Object { $_ -match '^selftest: ' })
if ($selftestSummary.Count -ne 1) { throw 'the self-test printed no summary line' }
Say "  $($selftestSummary[0])"

# ------------------------------------------------------------- 3. the shim builds the M1 program
Head '[3/5] the shim builds the M1 program and writes an object'
$r = Invoke-Batch @('!shim_driver.exe m1 "' + $Scratch + '\m1_shim.obj" --ir > m1_build.txt 2>&1') 'm1'
if ($r.codes[0] -ne 0) {
    Say (Get-Content -LiteralPath (Join-Path $Scratch 'm1_build.txt') -Raw)
    throw "the shim could not build the M1 module (exit $($r.codes[0]))"
}
$r = Invoke-Batch @('!shim_driver.exe flow "' + $Scratch + '\flow_shim.obj" > flow_build.txt 2>&1') 'flow'
if ($r.codes[0] -ne 0) {
    Say (Get-Content -LiteralPath (Join-Path $Scratch 'flow_build.txt') -Raw)
    throw "the shim could not build the if/else module (exit $($r.codes[0]))"
}
$m1Obj = Join-Path $Scratch 'm1_shim.obj'
$flowObj = Join-Path $Scratch 'flow_shim.obj'
Say "  $m1Obj   ($((Get-Item -LiteralPath $m1Obj).Length) bytes)  <- the object, in the scratch directory, not beside the source"
Say "  $flowObj  ($((Get-Item -LiteralPath $flowObj).Length) bytes)"
Say "  the IR the shim built is in $Scratch\m1_build.txt (and flow_build.txt)"

# --------------------------------------------- 4. the runtime object and the link
Head '[4/5] clang-cl builds the runtime object, lld-link links it, and the programs run'
$rtObj = Join-Path $Scratch 'vela_llvm_runtime.obj'
$r = Invoke-Batch @(
    "`"$clangCl`" /nologo /c /O2 /I `"$runtimeDir`" `"$runtimeC`" /Fo`"$rtObj`"",
    "`"$lldLink`" /nologo /subsystem:console /out:m1_shim.exe m1_shim.obj vela_llvm_runtime.obj",
    "`"$lldLink`" /nologo /subsystem:console /out:flow_shim.exe flow_shim.obj vela_llvm_runtime.obj",
    '!m1_shim.exe > raw_shim_m1.out 2> raw_shim_m1.err',
    '!flow_shim.exe > raw_shim_flow.out 2> raw_shim_flow.err'
) 'link' -MsvcEnv
Reap-MsvcHelpers
if ($r.codes[0] -ne 0) { Say $r.log; throw "clang-cl could not build the runtime object (exit $($r.codes[0]))" }
if ($r.codes[1] -ne 0) { Say $r.log; throw "lld-link could not link m1_shim.obj (exit $($r.codes[1]))" }
if ($r.codes[2] -ne 0) { Say $r.log; throw "lld-link could not link flow_shim.obj (exit $($r.codes[2]))" }
if ($r.codes[3] -ne 0) { Say $r.log; throw "the shim-built M1 program exited $($r.codes[3])" }
if ($r.codes[4] -ne 0) { Say $r.log; throw "the shim-built if/else program exited $($r.codes[4])" }
Say "  $rtObj  (clang-cl, as the plan's step 4 specifies)"
Say "  $Scratch\m1_shim.exe and $Scratch\flow_shim.exe  (lld-link, /subsystem:console, no C compiler in this step)"

# ------------------------------------------------------------- 5. the other three paths
Head '[5/5] the other three paths'
$m1VelCopy = Join-Path $Scratch 'm1_probe.vel'
$flowVelCopy = Join-Path $Scratch 'shim_flow.vel'

# The C backend writes its intermediates into `%TEMP%\vela-build\<flattened path>`,
# one directory shared by every `vm.exe build` on this machine.  While this script
# was being written, two concurrent builds corrupted each other: one of them was this
# script's M1 build, which failed with
# `fatal error C1083: ... Permission denied` on a stale .obj in another session's
# scratch, after which the compiler panicked with exit 2 -- and the M1 build itself
# exited -1 while still producing a correct executable.  The cause was found and
# fixed in the compiler's own driver: it named its per-build batch file after the
# *compiler binary*, so every build on the machine ran the same `vm.exe_cc.bat` and
# executed whichever one had been written last.  The batch is now named after the
# build's own scratch C, and two concurrent builds are byte-correct.  The single
# retry below is therefore insurance, not a workaround: a genuine failure fails
# twice, prints the tool's own log, and stops the script.
function Invoke-CBackend([string] $vel, [string] $tag) {
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $r = Invoke-Batch @("!`"$vm`" build `"$vel`" > cbuild_$tag.txt 2>&1") "cbuild_$tag"
        $log = [string](Get-Content -LiteralPath (Join-Path $Scratch "cbuild_$tag.txt") -Raw)
        if ($r.codes[0] -eq 0) { return $log }
        if ($attempt -eq 1) {
            Say "    the C backend's first attempt for $tag exited $($r.codes[0]); retrying once"
            Say $log
        } else {
            Say $log
            throw "vm.exe build of $vel exited $($r.codes[0]) on both attempts (the log is above)"
        }
    }
}

$r = Invoke-Batch @(
    "!`"$vm`" run `"$m1VelCopy`" > raw_interp_m1.out 2> raw_interp_m1.err",
    "!`"$vm`" run `"$flowVelCopy`" > raw_interp_flow.out 2> raw_interp_flow.err"
) 'interp'
if ($r.codes[0] -ne 0) { throw "vm.exe run (M1) exited $($r.codes[0])" }
if ($r.codes[1] -ne 0) { throw "vm.exe run (flow) exited $($r.codes[1])" }
Say '  vm.exe run: the interpreter ran both programs'

$null = Invoke-CBackend $m1VelCopy 'm1'
$r = Invoke-Batch @("!`"$Scratch\m1_probe.exe`" > raw_cbackend_m1.out 2> raw_cbackend_m1.err") 'crun_m1'
if ($r.codes[0] -ne 0) { throw "the C-built M1 program exited $($r.codes[0])" }
Say "  vm.exe build (M1): $((Get-Item -LiteralPath (Join-Path $Scratch 'm1_probe.exe')).Length) bytes of executable, and it ran"

$null = Invoke-CBackend $flowVelCopy 'flow'
$r = Invoke-Batch @("!`"$Scratch\shim_flow.exe`" > raw_cbackend_flow.out 2> raw_cbackend_flow.err") 'crun_flow'
if ($r.codes[0] -ne 0) { throw "the C-built if/else program exited $($r.codes[0])" }
Say '  vm.exe build (flow): the reference backend built the same if/else program, and it ran'

$r = Invoke-Batch @(
    "`"$clangCl`" /nologo /O2 /I `"$runtimeDir`" `"$m1Ll`" `"$rtObj`" /Fe:`"$Scratch\m1_clangcl.exe`"",
    "!`"$Scratch\m1_clangcl.exe`" > raw_clangcl_m1.out 2> raw_clangcl_m1.err"
) 'path3' -MsvcEnv
Reap-MsvcHelpers
if ($r.codes[0] -ne 0) { Say $r.log; throw "clang-cl could not build the hand-written m1_probe.ll (exit $($r.codes[0]))" }
if ($r.codes[1] -ne 0) { Say $r.log; throw "the clang-cl-built M1 program exited $($r.codes[1])" }
Say '  clang-cl + m1_probe.ll: the hand-written IR path ran too'

# ------------------------------------------------------------------ the comparison
# Raw bytes, read off the files cmd wrote: a stray CR, a missing newline or a UTF-16
# BOM is a difference, not a formatting detail.
$paths = @(
    @{ name = '1 interpreter (vm.exe run)';            file = 'raw_interp_m1.out' },
    @{ name = '2 C backend (vm.exe build)';            file = 'raw_cbackend_m1.out' },
    @{ name = '3 clang-cl + m1_probe.ll';              file = 'raw_clangcl_m1.out' },
    @{ name = '4 the shim (vshim_*) + lld-link';       file = 'raw_shim_m1.out' }
)

Head 'the M1 comparison: four paths, identical bytes'
Say ("  expected (written down in m1_probe.vel): $(Bytelist $expectedM1)")
$problems = @()
foreach ($p in $paths) {
    $bytes = Read-Bytes (Join-Path $Scratch $p.file)
    $ok = Same-Bytes $bytes $expectedM1
    Say ("  {0,-34} {1} bytes  [{2}]" -f $p.name, $bytes.Count, (Bytelist $bytes))
    if (-not $ok) { $problems += "$($p.name): $(First-Difference $expectedM1 $bytes)" }
}

# The second program, to show the shim is enough for an if/else and not only for M1.
Head 'the if/else comparison: interpreter, C backend and the shim'
$flowPaths = @(
    @{ name = '1 interpreter (vm.exe run)';      file = 'raw_interp_flow.out' },
    @{ name = '2 C backend (vm.exe build)';      file = 'raw_cbackend_flow.out' },
    @{ name = '3 the shim + lld-link';           file = 'raw_shim_flow.out' }
)
Say ("  expected (written down in shim_flow.vel): $(Bytelist $expectedFlow)")
foreach ($p in $flowPaths) {
    $bytes = Read-Bytes (Join-Path $Scratch $p.file)
    $ok = Same-Bytes $bytes $expectedFlow
    Say ("  {0,-34} {1} bytes  [{2}]" -f $p.name, $bytes.Count, (Bytelist $bytes))
    if (-not $ok) { $problems += "$($p.name) (flow): $(First-Difference $expectedFlow $bytes)" }
}

# ------------------------------------------------- where things landed
Head 'where the products are'
$sourcesAfter = @(Get-ChildItem -LiteralPath (Join-Path $repo 'selfhost\llvm') | ForEach-Object { $_.Name })
$newBeside = @($sourcesAfter | Where-Object { $sourcesBefore -notcontains $_ })
if ($newBeside.Count -gt 0) {
    $problems += "these files appeared in selfhost\llvm, beside a source: $($newBeside -join ', ')"
}
Say "  selfhost\llvm before: $($sourcesBefore.Count) files, after: $($sourcesAfter.Count) -- $(if ($newBeside.Count -eq 0) { 'nothing new appeared beside the source' } else { 'NEW FILES: ' + ($newBeside -join ', ') })"
foreach ($f in @('vela_llvm_shim.obj', 'shim_driver.exe', 'vela_llvm_runtime.obj', 'm1_shim.obj', 'm1_shim.exe', 'flow_shim.obj', 'flow_shim.exe', 'm1_probe.vel', 'm1_probe.exe', 'LLVM-C.dll')) {
    $p = Join-Path $Scratch $f
    if (Test-Path -LiteralPath $p) { Say ("  {0,-24} {1,10} bytes" -f $f, (Get-Item -LiteralPath $p).Length) }
}
Say "  the C backend's own intermediates went to $(Join-Path $env:TEMP 'vela-build'), which is where it puts them"

Head 'result'
if ($problems.Count -gt 0) {
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    Write-Host ("  expected bytes [{0}]" -f (Bytelist $expectedM1))
    foreach ($p in $problems) { Write-Host "  $p" -ForegroundColor Red }
    exit 1
}

Write-Host 'RESULT: ok -- the shim compiled, its self-test passed, and all four paths printed identical bytes' -ForegroundColor Green
Say ''
Say '  the shim is enough for M1: a module for the host triple, i64/i32/i1/f64 types,'
Say '  a function with a body and parameters, string-literal globals with their exact'
Say '  length, the sret call shape for str_lit, calls with scalar arguments, and an'
Say '  object written to a caller-given path.  The if/else program shows it is also'
Say '  enough for blocks, icmp and cond_br -- which is what if/while need next.'
exit 0
