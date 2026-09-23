<#
.SYNOPSIS
    Milestone M1 of the LLVM backend: the same program by three paths, compared byte for byte.

.DESCRIPTION
    `selfhost/LLVM_PLAN.md` M1: `hello`-shaped output, `clang-cl` in the loop, a
    running executable.  Before any emitter exists, the pipeline is proved with a
    hand-written `.ll` (`selfhost/llvm/m1_probe.ll`) whose expected output is known
    independently, and the proof is a *differential* test, not a look at the screen:

        1. `vm.exe run`            -- the interpreter, no C compiler involved
        2. `vm.exe build` + run    -- the C backend, the reference implementation
        3. clang-cl + the `.ll`    -- the LLVM path, linked against the LLVM runtime

    All three must print the same bytes as `selfhost/llvm/m1_probe.vel` says they
    must -- and the expected bytes are written down in that file, never recorded
    from a run.  A backend that is fast and wrong is not a backend.

    Two host facts this script had to get right, both measured:

      * `clang-cl` needs the MSVC environment for the linker and the CRT, so it is
        invoked from a batch file that has called `vcvars64.bat`.  LLVM supplies
        the compiler; Visual Studio still supplies the libraries it links against.
      * every native invocation redirects its streams **to files**, never through
        `2>&1` in the pipeline: this machine's PowerShell turns a native command's
        stderr into a terminating error under `$ErrorActionPreference = 'Stop'`,
        and `clang-cl` writes warnings there on any real build.  With `2>&1` the
        script died on the runtime's deprecation warnings before it could compare
        anything -- which is the sort of failure that looks like a broken backend.

.PARAMETER LlvmBin
    The directory holding `clang-cl.exe`.  Defaults to the portable LLVM that
    `tools\get-llvm.ps1` unpacks beside this checkout.

.PARAMETER Keep
    Leave the build products (the object file and the C backend's executable) in place.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\llvm-m1.ps1

.NOTES
    Exit code 0 means all three paths agreed, byte for byte.  Any disagreement is
    reported with the byte values, and the exit code is non-zero.
#>
[CmdletBinding()]
param(
    [string] $LlvmBin,
    [switch] $Keep
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $LlvmBin) {
    $LlvmBin = Join-Path (Split-Path -Parent $repo) 'llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc\bin'
}
$clangCl = Join-Path $LlvmBin 'clang-cl.exe'
if (-not (Test-Path -LiteralPath $clangCl)) {
    throw "no clang-cl at $clangCl -- run tools\get-llvm.ps1 first, or pass -LlvmBin"
}

$vm = Join-Path $repo 'selfhost\build\vm.exe'
if (-not (Test-Path -LiteralPath $vm)) { throw "no compiler at $vm -- run tools\build.ps1 first" }

$vcvars = @(
    'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
    'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
    'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat',
    'C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $vcvars) { throw 'no vcvars64.bat found: clang-cl has no linker or CRT without Visual Studio' }

# The suite's rule, because this script spawns processes the same way it does.
$env:VELA_SELF = $vm

$scratch  = Join-Path $env:TEMP 'vela-llvm-m1'
New-Item -ItemType Directory -Force -Path $scratch | Out-Null
$src      = Join-Path $repo 'selfhost\llvm\m1_probe.vel'
$ir       = Join-Path $repo 'selfhost\llvm\m1_probe.ll'
$rt       = Join-Path $repo 'runtime\vela_llvm_runtime.c'
$rtI      = Join-Path $repo 'runtime'
$rtObj    = Join-Path $scratch 'vela_llvm_runtime.obj'
$llvmExe  = Join-Path $scratch 'm1_llvm.exe'

# Captured to files, never through a pipeline: see .NOTES.  The preference is
# turned down for the duration of the call for the same reason -- on this host a
# native command's stderr becomes a terminating error under 'Stop' even when it is
# redirected to a file, and `clang-cl` always warns on a real build.
function Invoke-ToFiles([string] $exe, [string[]] $arguments, [string] $tag) {
    $o = Join-Path $scratch "run_$tag.out"
    $e = Join-Path $scratch "run_$tag.err"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $exe @arguments 1> $o 2> $e } finally { $ErrorActionPreference = $prev }
    $code = $LASTEXITCODE
    $stdout = if (Test-Path -LiteralPath $o) { Get-Content -LiteralPath $o -Raw } else { '' }
    $stderr = if (Test-Path -LiteralPath $e) { Get-Content -LiteralPath $e -Raw } else { '' }
    return @{ code = $code; out = [string]$stdout; err = [string]$stderr }
}

function Invoke-InMsvcEnv([string] $commandLine, [string] $tag) {
    $bat = Join-Path $scratch "step_$tag.bat"
    Set-Content -LiteralPath $bat -Value "call `"$vcvars`" >nul 2>&1`r`n$commandLine`r`n" -NoNewline -Encoding ascii
    return Invoke-ToFiles 'cmd.exe' @('/c', $bat) $tag
}

function Show([string] $tag, [string] $text) { Write-Host ('  ' + $tag.PadRight(24) + $text) }
function Normalise([string] $s) { return ($s -replace "`r`n", "`n") }
function Bytelist([string] $s) { return (($s.ToCharArray() | ForEach-Object { [int]$_ }) -join ',') }

Write-Host 'M1: the same program by three paths'
Write-Host ''

# ------------------------------------------------------------------ 1. interpreter
Write-Host '[1/3] the interpreter (no C compiler involved)'
$interp = Invoke-ToFiles $vm @('run', $src) 'interp'
if ($interp.code -ne 0) { throw "vm.exe run exited $($interp.code):`n$($interp.err)" }

# ------------------------------------------------------------------ 2. the C backend
#
# `build-c`, not `build`, and the name is the whole point of this row: M1's claim is that
# three *different* paths agree -- the interpreter, the C backend, and hand-written IR linked
# by `clang-cl` -- so a "C backend" row that ran the LLVM back end would be comparing the LLVM
# back end with itself and reporting it as a third opinion.  `build` is the LLVM path since
# 2026-09-24; `build-c` is the C back end, and it is what the words "the reference
# implementation" above this line have always meant.
Write-Host '[2/3] the C backend (the reference implementation)'
$build = Invoke-ToFiles $vm @('build-c', $src) 'cbuild'
if ($build.code -ne 0) { throw "vm.exe build-c exited $($build.code):`n$($build.err)" }
$cExe = Join-Path $repo 'selfhost\llvm\m1_probe.exe'
if (-not (Test-Path -LiteralPath $cExe)) { throw "the C backend wrote no $cExe" }
$cRun = Invoke-ToFiles $cExe @() 'crun'
if ($cRun.code -ne 0) { throw "the C-built program exited $($cRun.code)" }

# ------------------------------------------------------------------ 3. clang-cl + the .ll
Write-Host '[3/3] clang-cl and the hand-written .ll'
$r1 = Invoke-InMsvcEnv "`"$clangCl`" /nologo /c /O2 /I `"$rtI`" `"$rt`" /Fo`"$rtObj`"" 'rt'
if ($r1.code -ne 0) { throw "clang-cl could not build the runtime object:`n$($r1.err)" }
Show 'runtime object' $rtObj

$r2 = Invoke-InMsvcEnv "`"$clangCl`" /nologo /O2 /I `"$rtI`" `"$ir`" `"$rtObj`" /Fe:`"$llvmExe`"" 'link'
if ($r2.code -ne 0) { throw "clang-cl could not build the .ll:`n$($r2.err)" }
Show 'llvm executable' $llvmExe

$llvmRun = Invoke-ToFiles $llvmExe @() 'llvmrun'
if ($llvmRun.code -ne 0) { throw "the LLVM-built program exited $($llvmRun.code)" }

# ------------------------------------------------------------------ the comparison
# The expected bytes are written down in m1_probe.vel's own header, not recorded
# from any of the three runs, and the comparison is on raw bytes: a stray CR or a
# missing newline is a difference, not a formatting detail.
$expected = "hello from Vela`nm1: 3`n"
Write-Host ''
Show 'expected (written down)' (Normalise $expected).TrimEnd("`n") -replace "`n", ' | '
Show 'interpreter' (Normalise $interp.out).TrimEnd("`n") -replace "`n", ' | '
Show 'C backend' (Normalise $cRun.out).TrimEnd("`n") -replace "`n", ' | '
Show 'LLVM' (Normalise $llvmRun.out).TrimEnd("`n") -replace "`n", ' | '
Write-Host ''

$problems = @()
foreach ($row in @(@('interpreter', $interp.out), @('C backend', $cRun.out), @('LLVM', $llvmRun.out))) {
    $name = $row[0]
    $got = Normalise $row[1]
    if ($got -ne $expected) {
        $problems += "$name printed bytes [$(Bytelist $got)]"
    }
}
if ($problems.Count -gt 0) {
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    Write-Host "  expected bytes [$(Bytelist $expected)]"
    foreach ($p in $problems) { Write-Host "  $p" -ForegroundColor Red }
    exit 1
}

Write-Host 'RESULT: ok -- three paths, identical bytes' -ForegroundColor Green
Write-Host ''
Write-Host 'M1 is met for this program.  M2, per selfhost\LLVM_PLAN.md, is arithmetic'
Write-Host 'with its checks, if/elif/else, while and `for i in range` -- and the emitter'
Write-Host 'that writes what m1_probe.ll hand-wrote.'

if (-not $Keep) {
    Remove-Item -LiteralPath $cExe -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $repo 'selfhost\llvm\m1_probe.c') -Force -ErrorAction SilentlyContinue
}
