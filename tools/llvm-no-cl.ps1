#Requires -Version 5.1
<#
  llvm-no-cl.ps1 -- `build` does not start a C compiler.  Prove it by disarming one.

  WHY THIS EXISTS

  `vm.exe build` is the LLVM path: it builds the module in process through libLLVM and
  links it with `lld-link`.  "No C compiler is started" is a claim about a process that
  is *not* run, and a claim of that shape cannot be checked by reading the log: a log that
  does not mention cl.exe is also what a build that quietly found one somewhere else
  prints.  So the compiler is disarmed instead of watched.

  The trap the leader measured, which is the one that works:

      CL=/Zs      a documented cl switch meaning "syntax check only, no codegen"

  `CL` is read by cl.exe itself as extra arguments, so it needs no PATH surgery -- which
  matters, because PATH surgery is defeated here: the C path calls `vcvars64.bat`, and that
  prepends the MSVC directory, so a `cl.bat` trap placed first on PATH stops being what
  `cl` resolves to.  A trap the C path walks around proves nothing about the LLVM path.
  `CL` cannot be walked around, because it is read *inside* the compiler once it starts.

  THREE MEASUREMENTS, and two of them are controls:

    1. `build`   with CL=/Zs    exit 0, and the program prints 42
                                -> cl cannot be what built it
    2. `build-c` with CL=/Zs    exit 2 (or no executable)
                                -> the disarm really disarms.  Without this the first row
                                   would also pass for an environment where CL is ignored
    3. `build-c` without it     exit 0, and the program prints 42
                                -> the C path works when it is not disarmed, so row 2 is the
                                   trap's doing and not a broken compiler

  The program is three lines and prints a number, so "it built" and "it runs" are both
  checkable, and the number is compared rather than named here: the interpreter is the
  oracle (`vm.exe run`), which is what every other gate in this tree does.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\llvm-no-cl.ps1
    powershell -ExecutionPolicy Bypass -File tools\llvm-no-cl.ps1 -Verbose

  Exit: 0 = RESULT: ok, 1 = RESULT: failed.  No C compiler is *needed* by this script
  except to be disarmed.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
Set-Location -LiteralPath $root

function Say([string] $t) { Write-Host $t }
$failures = New-Object System.Collections.ArrayList
function Fail([string] $t) { Say ("  FAIL  " + $t); [void]$script:failures.Add($t) }
function Pass([string] $t) { Say ("  ok    " + $t) }

if (-not (Test-Path -LiteralPath $vm)) {
    Say "RESULT: failed -- no compiler at $vm"
    exit 1
}

$scratch = Join-Path $env:TEMP 'vela-no-cl'
Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

# Three lines, and it prints a number.  The interpreter is the oracle for that number.
$prog = Join-Path $scratch 'probe.vel'
[System.IO.File]::WriteAllText($prog,
    "def main() -> None {`r`n    print(42)`r`n}`r`n",
    (New-Object System.Text.UTF8Encoding($false)))

# The child is started through one batch file, and this is the fifth shape tried; the four
# others are recorded because each looked right and three of them lied:
#
#   * `cmd /c "cmd > log 2>&1"` followed by `echo exit=%errorlevel%` on the next line:
#     %errorlevel% is expanded when the line is *read*, so a two-line batch echoes the status
#     of its own first line.  Measured: every run reported the -999 sentinel;
#   * the same with `call echo %%errorlevel%%` and one log per run: correct codes, but cmd's
#     redirect leaves the handle, so the next run's `Remove-Item` on the log failed with
#     "being used by another process" -- a gate whose own bookkeeping fails is not a gate;
#   * `& cmd.exe /c ... | Out-Null` then `$LASTEXITCODE`: the pipeline makes the exit code the
#     last command's, not the compiler's;
#   * `Start-Process -UseNewEnvironment -Environment @{...}`: that parameter pair is
#     PowerShell 7.  Windows PowerShell 5.1 answers "A parameter cannot be found", and this
#     project is 5.1 only -- and `Start-Process` without it is refused outright under the
#     file sandbox this session runs in ("Access is denied", measured).
#
# So: one batch, one **code file per run** (the pattern `tools\llvm-inproc.ps1` already uses,
# for the same reason), and a fresh log name per run so nothing is ever reopened.  `CL` is set
# in the batch, for the child only; this script never runs a compiler itself.
function Run-It {
    param([string] $Verb, [string] $ClEnv, [string] $Tag)
    $exe = Join-Path $scratch 'probe.exe'
    Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
    $log = Join-Path $scratch ($Tag + '.log')
    $codeFile = Join-Path $scratch ($Tag + '.code')
    $bat = Join-Path $scratch ($Tag + '.bat')
    Remove-Item -LiteralPath $log, $codeFile -Force -ErrorAction SilentlyContinue

    $body = @('@echo off')
    if ($ClEnv) { $body += ('set CL=' + $ClEnv) }
    $body += 'set VSLANG=1033'
    $body += 'set VSCMD_SKIP_SENDTELEMETRY=1'
    $body += ('"' + $vm + '" ' + $Verb + ' "' + $prog + '" > "' + $log + '" 2>&1')
    # the code goes into its own file, written by the line *after* the compiler -- which is
    # what makes it the compiler's code
    $body += ('echo %errorlevel% > "' + $codeFile + '"')
    [System.IO.File]::WriteAllLines($bat, $body)

    & cmd.exe /c $bat | Out-Null
    Start-Sleep -Milliseconds 300

    $code = -999
    if (Test-Path -LiteralPath $codeFile) {
        $code = [int]((Get-Content -LiteralPath $codeFile -Raw).Trim())
    }
    $text = ''
    if (Test-Path -LiteralPath $log) { $text = [string](Get-Content -LiteralPath $log -Raw) }
    $ran = ''
    if (Test-Path -LiteralPath $exe) {
        $ran = (& cmd.exe /c ('"' + $exe + '" 2>&1') | Out-String).Trim()
    }
    return [pscustomobject]@{ Code = $code; Ran = $ran; Log = $text; Exe = (Test-Path -LiteralPath $exe) }
}

Say "Vela: does the LLVM path start a C compiler? - $root"
Say ("  compiler: {0} bytes" -f (Get-Item -LiteralPath $vm).Length)

Say ''
Say '== the oracle: the interpreter'
& cmd.exe /c ('"' + $vm + '" run "' + $prog + '" > "' + (Join-Path $scratch 'interp.out') + '" 2>&1') | Out-Null
$want = ''
if (Test-Path -LiteralPath (Join-Path $scratch 'interp.out')) {
    $want = ([System.IO.File]::ReadAllText((Join-Path $scratch 'interp.out'))).Trim()
}
if ($want -eq '42') {
    Pass 'the interpreter prints 42'
} else {
    Fail ("the interpreter printed '" + $want + "', so the probe program is not what this script is about")
}

Say ''
Say '== 1. build, with cl disarmed through CL=/Zs'
$r1 = Run-It -Verb 'build' -ClEnv '/Zs' -Tag 'build-disarmed'
Say ("  exit {0}, executable {1}, printed '{2}'" -f $r1.Code, $r1.Exe, $r1.Ran)
if ($r1.Code -eq 0 -and $r1.Exe -and $r1.Ran -eq $want) {
    Pass 'it built and ran with CL=/Zs set, so cl cannot be what built it'
} else {
    Fail ("build with CL=/Zs: exit " + $r1.Code + ", executable=" + $r1.Exe + ", printed '" + $r1.Ran + "'")
    foreach ($l in ($r1.Log -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 6)) { Say ("      | " + $l.Trim()) }
}

Say ''
Say '== 2. build-c, with the same disarm (the control for row 1)'
$r2 = Run-It -Verb 'build-c' -ClEnv '/Zs' -Tag 'buildc-disarmed'
Say ("  exit {0}, executable {1}, printed '{2}'" -f $r2.Code, $r2.Exe, $r2.Ran)
if ($r2.Code -ne 0 -or -not $r2.Exe) {
    Pass 'the C path is disarmed by CL=/Zs, so row 1 is not passing because CL is ignored'
} else {
    Fail 'build-c succeeded with CL=/Zs, so this script cannot tell a disarmed cl from a missing one'
}

Say ''
Say '== 3. build-c, without it (the control for row 2)'
$r3 = Run-It -Verb 'build-c' -ClEnv '' -Tag 'buildc-armed'
Say ("  exit {0}, executable {1}, printed '{2}'" -f $r3.Code, $r3.Exe, $r3.Ran)
if ($r3.Code -eq 0 -and $r3.Exe -and $r3.Ran -eq $want) {
    Pass 'the C path works when it is not disarmed, so row 2 is the trap and not a broken compiler'
} else {
    Fail ("build-c without CL: exit " + $r3.Code + ", executable=" + $r3.Exe + ", printed '" + $r3.Ran + "'")
}

Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue

Say ''
if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok -- build runs with cl disarmed, and the disarm is proven live'
exit 0
