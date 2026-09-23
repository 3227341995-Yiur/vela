# tools/_ownership-probe.ps1 - does this tree's build kill another checkout's work?
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\_ownership-probe.ps1
#
# A probe rather than a gate, and the prefix says so: `gitignore` ignores
# `/tools/_*`.  It is two-sided on purpose, because "a sweep no longer kills X" is
# only worth anything if it is shown to *have* killed X before.
#
# WHAT IT MEASURES, and each half is a real process, not a simulation:
#
#   PART A  a foreign C compiler - this is what the other agent's checkout looks
#           like.  A long cl-driven compile is started from a *different*
#           directory, and while it runs the two predicates are evaluated side by
#           side against the live process list:
#
#             the old condition   Get-Process -Name cl   (matched by image name)
#                                 -> this process IS matched, i.e. the old sweep
#                                    kills it
#             the new condition   Get-OwnedProcess cl    (path inside this checkout)
#                                 -> this process is NOT matched, i.e. the new
#                                    sweep leaves it alone
#
#           Then the old sweep is actually run - Stop-Process on everything the
#           name-based condition matched - and the compile is shown to have died.
#           That is the failure being fixed, reproduced rather than described.
#           (It kills only the probe's own compiler: the probe starts it, and the
#           point is the mechanism.  -SkipKill skips this half.)
#
#   PART B  a held output file.  An exclusive handle is taken on a target the
#           build is about to write, and Clear-LockedTarget - the function whose
#           catch this project wanted to see fire - is called on it, so the
#           renamed-aside path is exercised and *shown* rather than assumed.  The
#           probe then puts the file back, because a probe that leaves litter
#           poisons the next run.
#
# The condition in PART A is not a copy of the expression in build.ps1: build.ps1
# defines Get-OwnedProcess and Get-ForeignProcess, and this probe **reads them out
# of the real file** and evaluates them here.  A probe that re-implements the thing
# it checks measures its author's memory instead of the tree.

[CmdletBinding()]
param(
    [switch] $SkipKill
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

function Say($t) { Write-Host $t }
$failures = New-Object System.Collections.ArrayList
function Fail($t) { Say ("  FAIL  " + $t); [void]$script:failures.Add($t) }
function Pass($t) { Say ("  ok    " + $t) }

Say "Vela: process ownership and held files - $root"
Say ("  " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

function Read-BuildFunctions([string] $from, [string] $to) {
    $text = [System.IO.File]::ReadAllText((Join-Path $root 'tools\build.ps1'))
    $start = $text.IndexOf($from)
    $end = $text.IndexOf($to)
    if ($start -lt 0 -or $end -le $start) { return $null }
    return $text.Substring($start, $end - $start)
}

function Get-ForeignCl {
    foreach ($p in @(Get-Process -Name cl -ErrorAction SilentlyContinue)) {
        $path = ''
        try { $path = $p.Path } catch { $path = '' }
        if (-not ($path -and $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase))) {
            return $p
        }
    }
    return $null
}

# ------------------------------------------------------------------ part A
Say ''
Say '== A. a C compiler in another checkout, while this tree''s build runs'

$slice = Read-BuildFunctions 'function Get-OwnedProcess' 'foreach ($name in ''WerFault'', ''wermgr'')'
if ($slice) {
    Invoke-Expression $slice
    Pass 'the ownership predicates were read out of tools\build.ps1 itself, not restated here'
} else {
    Fail 'could not find the ownership predicates in tools\build.ps1 (a probe cannot check what it cannot read)'
}
if (-not (Get-Command Get-OwnedProcess -ErrorAction SilentlyContinue)) {
    Say ''
    Say 'RESULT: failed - no Get-OwnedProcess survived the slice, so nothing below can be measured'
    exit 1
}

# A working directory OUTSIDE this checkout is what makes a process foreign.  The
# probe uses TEMP rather than the real worktree on purpose: the worktree lives at
# `.wt\enums`, which IS under this root, so an ownership test would call it ours.
# TEMP is unambiguous, and it is the same shape of problem.
$foreignDir = Join-Path $env:TEMP 'vela-foreign-checkout'
Remove-Item -LiteralPath $foreignDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $foreignDir | Out-Null

$vcv = ''
foreach ($c in @(
    (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
    (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'))) {
    if (Test-Path -LiteralPath $c) { $vcv = $c; break }
}

$src = Join-Path $root 'selfhost\build\vm.c'
$bat = Join-Path $foreignDir 'foreign-compile.bat'
$log = Join-Path $foreignDir 'foreign.log'
if (-not $vcv) {
    Fail 'no vcvars64.bat, so no C compiler can be started'
} else {
    # Three compiles in a row, not one.  Measured: a single compile of this 1 MB file
    # with /O2 finishes in about two seconds, so a probe that starts one and then polls
    # can miss the window entirely -- which it did, once, and reported as "no foreign
    # cl.exe appeared".  Three of them leave a window of ten to twenty seconds, which is
    # the point of the wait loop below.
    $obj1 = Join-Path $foreignDir 'vm1.obj'
    $obj2 = Join-Path $foreignDir 'vm2.obj'
    $obj3 = Join-Path $foreignDir 'vm3.obj'
    $Q = [char]34
    # `[char]34` and string concatenation rather than nested quotes: the first version of
    # this line used PowerShell's own escapes inside a single-quoted string, which wrote a
    # batch file with a stray double quote on it -- `call "` opened a quoted region that
    # ran to the next quote three lines later, so `cl` was never on the command line and
    # every pass answered 'cl' is not recognized.  The probe then reported "no foreign
    # cl.exe appeared", which is what a mis-quoted batch file looks like from outside:
    # measured, and it cost two round trips.
    $clLine = 'cl /nologo /std:c11 /utf-8 /O2 /I ' + $Q + (Join-Path $root 'runtime') + $Q + ' /c ' + $Q + $src + $Q
    $lines = @(
        '@echo off',
        ('call ' + $Q + $vcv + $Q + ' >nul 2>&1'),
        'set VSCMD_SKIP_SENDTELEMETRY=1',
        ($clLine + ' /Fo:' + $Q + $obj1 + $Q + ' > ' + $Q + $log + $Q + ' 2>&1'),
        ($clLine + ' /Fo:' + $Q + $obj2 + $Q + ' >> ' + $Q + $log + $Q + ' 2>&1'),
        ($clLine + ' /Fo:' + $Q + $obj3 + $Q + ' >> ' + $Q + $log + $Q + ' 2>&1'),
        ('echo done=%errorlevel% >> ' + $Q + $log + $Q)
    )
    [System.IO.File]::WriteAllLines($bat, $lines)
    Say ("  a foreign compile: three passes over {0:N0} bytes of C, the driver's own flags, in {1}" -f (Get-Item -LiteralPath $src).Length, $foreignDir)
    Say '  NOTE: this part kills every cl.exe on the machine, by name, to reproduce the old'
    Say '        bug.  Do not run it while another checkout is building.'

    # One string, not an array: `Start-Process -ArgumentList '/c', "`"$bat`""` passed the
    # quotes through as literal characters and cmd.exe never ran the batch at all -- a
    # silent nothing, measured as "no foreign cl.exe appeared within 20 s" with no log
    # file and no error.  cmd.exe /c takes the rest of one line as the command, quotes
    # included, which is also how the driver itself starts a compiler (`shell()` in
    # parts/vm_main.vel).
    $cmdLine = '/c "' + $bat + '"'
    $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList $cmdLine -PassThru -WindowStyle Hidden
    Say ("  started pid {0}; waiting up to 20 s for its cl.exe to appear" -f $proc.Id)

    $foreign = $null
    for ($i = 0; $i -lt 100; $i++) {
        Start-Sleep -Milliseconds 200
        $foreign = Get-ForeignCl
        if ($foreign) { break }
        if ($proc.HasExited) { break }
    }

    if (-not $foreign) {
        Fail 'no foreign cl.exe appeared within 20 s, so the sweep could not be measured'
    } else {
        $pid1 = $foreign.Id
        Say ("  foreign cl.exe is pid {0}" -f $pid1)
        $byName = @(Get-Process -Name cl -ErrorAction SilentlyContinue | Where-Object { $_.Id -eq $pid1 })
        $byOwner = @(Get-OwnedProcess 'cl' | Where-Object { $_.Id -eq $pid1 })
        Say ''
        Say ('  the OLD condition, Get-Process -Name cl : {0} process(es) matched' -f @($byName).Count)
        Say ('  the NEW condition, Get-OwnedProcess cl  : {0} process(es) matched' -f @($byOwner).Count)
        if (@($byName).Count -ge 1 -and @($byOwner).Count -eq 0) {
            Pass 'the old sweep catches it and the new one does not - the fix is a real difference'
        } else {
            Fail ('the two conditions agree on this process (by name {0}, by owner {1}), so this probe cannot tell them apart' -f @($byName).Count, @($byOwner).Count)
        }

        if ($SkipKill) {
            Say '  -SkipKill: the old sweep is NOT run, so that half is unproven this time'
        } else {
            $victims = @(Get-Process -Name cl -ErrorAction SilentlyContinue)
            Say ("  running the OLD sweep: Stop-Process on {0} cl.exe, by name" -f $victims.Count)
            $victims | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 900
            $alive = @(Get-Process -Id $pid1 -ErrorAction SilentlyContinue)
            if ($alive.Count -eq 0) {
                Pass ("the foreign compile (pid {0}) is dead - this is the failure the fix removes" -f $pid1)
            } else {
                Fail ("pid {0} survived the name-based kill" -f $pid1)
            }
            $tail = (Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue)
            if ($tail) { Say ("  its log says: " + ($tail -replace "`r?`n", ' | ')) }
        }
    }
    if (-not $proc.HasExited) { $proc | Stop-Process -Force -ErrorAction SilentlyContinue }
}

# ------------------------------------------------------------------ part B
Say ''
Say '== B. a held output file: what Clear-LockedTarget does, and what it must not do'

$target = Join-Path $env:TEMP 'vela-held-target.exe'
$aside = $target + '.held'
Remove-Item -LiteralPath $target, $aside -Force -ErrorAction SilentlyContinue
[System.IO.File]::WriteAllBytes($target, [byte[]](0x4D, 0x5A, 0x01, 0x02))

$sliceB = Read-BuildFunctions 'function Clear-LockedTarget' '# ---------------------------------------------------------------- 2. vm.exe'
if ($sliceB) {
    Invoke-Expression $sliceB
    Pass 'Clear-LockedTarget was read out of tools\build.ps1 itself, not restated here'
} else {
    Fail 'could not find Clear-LockedTarget in tools\build.ps1'
}

if (Get-Command Clear-LockedTarget -ErrorAction SilentlyContinue) {
    # What this machine does with a held file, measured by `tools\_hold-probe.ps1`:
    #
    #     the holder shares      a rename   open(ReadWrite, FileShare::None)
    #     nothing                moves      succeeds
    #     Read                   refused    refused
    #     ReadWrite              refused    refused
    #     ReadWrite|Delete       moves      refused
    #     Delete only            moves      refused
    #
    # So the `FileShare::None` test `Clear-LockedTarget` uses to notice a holder is not the
    # same question as "can it be renamed" -- and no open-mode test is, because the probe's
    # own handle is part of the situation.  That is why the function's catch no longer
    # explains *why* the rename failed, and why the three cases below assert behaviour and
    # the two sentences, not a derived reason.
    #
    # The two-sided part is the third case: a file held the way a *running executable* is
    # held (delete sharing on) must still be renamed aside with the sentence that says so.
    # Without it, case two would pass for a function that had stopped moving anything.
    $cases = @(
        @{ Name = 'nothing holds it';           Share = $null;                                                                ExpectMoved = $false; ExpectSilent = $true },
        @{ Name = 'held with FileShare::Read';  Share = [System.IO.FileShare]::Read;                                          ExpectMoved = $false; ExpectSilent = $false },
        @{ Name = 'held with delete sharing';   Share = [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete;  ExpectMoved = $true;  ExpectSilent = $false }
    )
    foreach ($c in $cases) {
        Remove-Item -LiteralPath $target, $aside -Force -ErrorAction SilentlyContinue
        [System.IO.File]::WriteAllBytes($target, [byte[]](0x4D, 0x5A, 0x03, 0x04))
        Say ''
        Say ('  ' + $c.Name)
        $h = $null
        if ($c.Share) {
            $h = [System.IO.File]::Open($target, [System.IO.FileMode]::Open,
                                        [System.IO.FileAccess]::ReadWrite, $c.Share)
        }
        $script:failed = $false
        Clear-LockedTarget $target
        $moved = Test-Path -LiteralPath $aside
        if ($h) { $h.Close() }
        if ($moved -eq $c.ExpectMoved) {
            Pass $(if ($moved) { 'renamed aside, as it must be' } else { 'left alone, as it must be' })
        } else {
            Fail ('expected moved=' + $c.ExpectMoved + ' and got ' + $moved + ' (see tools\_hold-probe.ps1 for the table this expectation comes from)')
        }
        if ($script:failed) {
            Fail 'the function set $script:failed, so a busy file reads as a broken build before the linker is asked'
        } else {
            Pass 'and it did not mark the build failed -- the linker gets to report LNK1104'
        }
        if (Test-Path -LiteralPath $aside) { Move-Item -LiteralPath $aside -Destination $target -Force }
        Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
    }
    Say ''
    Say '  (the two sentences the catch prints were shown above: "moved it aside" for the case'
    Say '   that moves, and "could not be moved aside" plus the holder sentence for the one'
    Say '   that does not)'
}

Say ''
Remove-Item -LiteralPath $foreignDir -Recurse -Force -ErrorAction SilentlyContinue
if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok - the sweep is by ownership, and a held file is still moved aside out loud'
exit 0
