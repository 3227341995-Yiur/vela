# tools\_cl-helper-spawn.ps1 -- which detached helpers does OUR compile actually
# spawn, and is the `mspdbsrv.exe` reap therefore load-bearing?
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\_cl-helper-spawn.ps1
#
# This is a probe, not a gate: it answers one question with a measurement and
# prints it.  The prefix is `_` because it is scratch -- `workspace\.gitignore`
# ignores `/tools/_*`.
#
# WHY.  `reap_helpers` in `selfhost/parts/vm_main.vel` ran, after every C compile,
#
#     taskkill /f /im vctip.exe
#     taskkill /f /im mspdbsrv.exe
#
# and `mspdbsrv.exe` is a **shared** PDB server: one instance serves every
# `cl.exe` that asks for it, so the second line kills the PDB server another
# checkout's compile is holding open.  Measured 2026-09-24: a suite row built by
# `build-c` failed with `could not be built` and stderr truncated before the driver
# printed its own command line, three times in three runs on three different rows,
# each time while the other agent's `build.ps1` was running, and every row passed
# alone.
#
# A PDB server only exists because a compiler asked for one, and a compiler asks
# for one by writing debug information (`/Zi`, `/ZI`, `/Z7`) or by being asked for a
# PDB (`/Fd`).  This driver's command lines pass `/O2` and `/std:c11` and nothing
# about debug info -- so if nothing here spawns a `mspdbsrv`, the second
# `taskkill` had no work that was *ours* to do, and deleting it costs nothing that
# this project ever wanted.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

function Say($t) { Write-Host $t }
function Snap() {
    $rows = @()
    foreach ($n in @('mspdbsrv', 'vctip', 'cl', 'link')) {
        foreach ($p in @(Get-Process -Name $n -ErrorAction SilentlyContinue)) {
            $rows += ("{0}(pid {1})" -f $p.ProcessName, $p.Id)
        }
    }
    if ($rows.Count -eq 0) { return '(none)' }
    return ($rows -join ', ')
}

Say "Vela: which helper does one of our own C compiles spawn?  $root"
Say ("  " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

$vcv = ''
foreach ($c in @(
    (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
    (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'))) {
    if (Test-Path -LiteralPath $c) { $vcv = $c; break }
}
if (-not $vcv) { Say '  no vcvars64.bat: nothing to measure'; exit 1 }

$src = Join-Path $root 'selfhost\build\vm.c'
$obj = Join-Path $env:TEMP 'vela-cl-probe.obj'
$bat = Join-Path $env:TEMP 'vela-cl-probe.bat'
$lines = @(
    '@echo off',
    ("call `"{0}`" >nul 2>&1" -f $vcv),
    'set VSCMD_SKIP_SENDTELEMETRY=1',
    # the driver's own command line, read out of `run_cc`/`msvc_build` and the
    # build report -- `/O2 /std:c11 /utf-8`, no debug-info switch anywhere
    ("cl /nologo /std:c11 /utf-8 /O2 /I `"{0}`" /c `"{1}`" /Fo:`"{2}`" >nul 2>&1" -f (Join-Path $root 'runtime'), $src, $obj),
    'echo cl exit=%errorlevel%'
)
[System.IO.File]::WriteAllLines($bat, $lines)

Say ''
Say "before: $(Snap)"
Say ("compiling {0} ({1:N0} bytes) with the driver's own flags: /O2 /std:c11 /utf-8" -f 'selfhost\build\vm.c', (Get-Item -LiteralPath $src).Length)
& cmd.exe /c $bat 2>&1 | ForEach-Object { Say ("  | " + $_) }
Start-Sleep -Milliseconds 1500
Say "after:  $(Snap)"

Say ''
Say 'RESULT: whatever the two lines above say is the measurement; this script does not judge.'
Say '  If `after` has no mspdbsrv, the second taskkill in reap_helpers() never had'
Say '  this project''s work to do, and killing it only ever hurt somebody else.'
exit 0
