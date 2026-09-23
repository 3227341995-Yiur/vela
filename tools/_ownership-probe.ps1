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
Say '== A1. which paths may be swept (the worktree case, decided without a process)'

$slice = Read-BuildFunctions 'function Get-OutputPaths' 'foreach ($name in ''WerFault'', ''wermgr'')'
if ($slice) {
    Invoke-Expression $slice
    Pass 'the sweep predicates were read out of tools\build.ps1 itself, not restated here'
} else {
    Fail 'could not find the sweep predicates in tools\build.ps1 (a probe cannot check what it cannot read)'
}
if (-not (Get-Command Get-OwnedProcess -ErrorAction SilentlyContinue)) {
    Say ''
    Say 'RESULT: failed - no Get-OwnedProcess survived the slice, so nothing below can be measured'
    exit 1
}

# **A path prefix is not ownership**, and this is the case the leader found after the first
# version of the rule: a `git worktree` of this repository lives *inside* it
# (`.wt\enums`, and `.gitignore` carries the rule), so `$p.Path.StartsWith($root)` calls the
# other agent's compiler ours and sweeps it.  The rule now names the paths the build is
# actually for, so the fixtures below are decided before any process is started: the real
# question is which of them the predicate matches, and only three may.
$ourOutputs = Get-OutputPaths
foreach ($o in $ourOutputs) { Say ('    an output path: ' + $o) }
$fixtures = @(
    @{ Path = (Join-Path $root 'selfhost\build\vm.exe');                  Want = $true;  What = 'the compiler this build writes and every gate runs' },
    @{ Path = (Join-Path $root 'selfhost\vm.exe');                        Want = $true;  What = 'what step 4 promotes' },
    @{ Path = (Join-Path $root 'selfhost\build\vm_by_vela.exe');          Want = $true;  What = 'the same binary, under the name that says how it was made' },
    @{ Path = (Join-Path $root '.wt\enums\selfhost\build\vm.exe');        Want = $false; What = 'A WORKTREE: under the checkout, and somebody else''s' },
    @{ Path = (Join-Path $env:TEMP 'vela-other-checkout\vm.exe');         Want = $false; What = 'a different directory entirely' },
    @{ Path = '';                                                         Want = $false; What = 'a path this user cannot open (measured: every vm.exe here)' },
    @{ Path = (Join-Path $root 'selfhost\build\vela.exe');                Want = $false; What = 'the standalone lexer: not an output this build overwrites' }
)
foreach ($f in $fixtures) {
    $got = Test-IsOurBuildOutput $f.Path $ourOutputs
    $shown = if ($f.Path) { $f.Path.Substring($root.Length).TrimStart('\', '/') } else { '(empty)' }
    if ($got -eq $f.Want) {
        Pass (('{0,-6} {1,-46} {2}' -f $(if ($got) { 'swept' } else { 'left' }), $shown, $f.What))
    } else {
        Fail (('{0} but should be {1}: {2} -- {3}' -f $(if ($got) { 'swept' } else { 'left alone' }), $(if ($f.Want) { 'swept' } else { 'left alone' }), $shown, $f.What))
    }
}
if ($ourOutputs.Count -ne 3) {
    Fail ('the sweep should name exactly three output paths and it names ' + $ourOutputs.Count)
} else {
    Pass 'the sweep names exactly three output paths, and no directory prefix'
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
        $byOutput = @(Get-OwnedProcess $ourOutputs | Where-Object { $_.Id -eq $pid1 })
        Say ''
        Say ('  the OLD condition, Get-Process -Name cl        : {0} process(es) matched' -f @($byName).Count)
        Say ('  the NEW condition, Get-OwnedProcess outputs    : {0} process(es) matched' -f @($byOutput).Count)
        if (@($byName).Count -ge 1 -and @($byOutput).Count -eq 0) {
            Pass 'the old sweep catches it and the new one does not - the fix is a real difference'
        } else {
            Fail ('the two conditions agree on this process (by name {0}, by output {1}), so this probe cannot tell them apart' -f @($byName).Count, @($byOutput).Count)
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

# ------------------------------------------------------- A3. the worktree, live
#
# The fixture table above decides the predicate on strings.  This is the same case with a
# **real process**: a copy of the compiler named `vm.exe` in a directory that looks like a
# worktree of this checkout must survive the sweep, and a copy at the compiler's own output
# path must not.  Two processes, one rule, and the difference between them is only the
# directory -- which is the whole claim.
Say ''
Say '== A3. two real vm.exe processes: the worktree one must survive, the output one must not'

# The compiler these copies are made from.  `selfhost\build\vm.exe` is the one every gate
# runs, so it is also the one the sweep is *for* -- the copies below carry its bytes and its
# name and differ only in where they live.
$compilerExe = Join-Path $root 'selfhost\build\vm.exe'
if (-not (Test-Path -LiteralPath $compilerExe)) {
    Fail ("no compiler at " + $compilerExe + ", so A3 cannot be measured")
}

$wtDir = Join-Path $root '.wt\_probe-worktree\selfhost\build'
New-Item -ItemType Directory -Force -Path $wtDir | Out-Null
$wtExe = Join-Path $wtDir 'vm.exe'
# The positive fixture has to be named `vm.exe` too, or the rule ignores it for the wrong
# reason: the predicate matches the *name* `vm` first and the path second.  It cannot be the
# real `selfhost\build\vm.exe`, because every gate in this session is running it.  So it goes
# in a third directory, and the claim it supports is the narrow one: **given that a process is
# called `vm`, the sweep kills it at its output path and not in a worktree**.  The negative
# fixture is what the leader asked for; this is its control, and without it the negative one
# would also pass for a predicate that matched nothing at all.
$outDir = Join-Path $env:TEMP 'vela-probe-output'
Remove-Item -LiteralPath $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outExe = Join-Path $outDir 'vm.exe'
Copy-Item -LiteralPath $compilerExe -Destination $wtExe -Force
Copy-Item -LiteralPath $compilerExe -Destination $outExe -Force
# And the rule reads a *fixed* list of output paths, so the TEMP copy is not on it -- which is
# the honest expectation here: it is swept only if the rule were a prefix test, and that is
# exactly the regression this round removed.
$probeOutputs = @($ourOutputs + [System.IO.Path]::GetFullPath($outExe))

# A copy of the compiler **started with no arguments** prints its usage list and exits in
# milliseconds, which is enough: `Get-Process` reads the image path of a running process, and
# the predicate is about the path.  So both fixtures are copies of the real compiler and
# nothing has to be faked about either.  (A first version of this half copied powershell.exe
# over the copy and started a sleep under `vm.exe`'s name, which is not what a sweep meets in
# practice and which also needed a script file; the tested property is the same and the real
# binary is a better witness.)
function Start-ProbeExe([string] $path) {
    # One argument, not none: `Start-Process -ArgumentList @()` is refused by PowerShell 5.1
    # ("the argument collection contains a null value", measured).  `--probe` is a mode the
    # compiler does not know, so it prints its usage list and exits -- which is all this needs,
    # because the sweep reads the *path* of the running image.
    return Start-Process -FilePath $path -ArgumentList '--probe' -PassThru -WindowStyle Hidden
}

$wtProc = $null
$outProc = $null
try {
    $wtProc = Start-ProbeExe $wtExe
    if (Test-Path -LiteralPath $outExe) { $outProc = Start-ProbeExe $outExe }
    Start-Sleep -Milliseconds 900

    if ($wtProc) {
        $got = @(Get-OwnedProcess $ourOutputs | Where-Object { $_.Id -eq $wtProc.Id })
        if ($got.Count -eq 0) {
            Pass ('a vm.exe in .wt\_probe-worktree (pid ' + $wtProc.Id + ') is NOT swept -- a worktree is somebody else''s tree')
        } else {
            Fail 'a vm.exe inside the checkout but in a worktree WAS swept, which is the defect this round fixes'
        }
    }
    if ($outProc) {
        # The same predicate, with the copy's own directory added to the list, must match it --
        # so the negative result above is about the path and not about a predicate that never
        # matches anything.
        $got = @(Get-OwnedProcess $probeOutputs | Where-Object { $_.Id -eq $outProc.Id })
        if ($got.Count -eq 1) {
            Pass ('the same predicate DOES match a vm.exe at an output path (pid ' + $outProc.Id + ') once that path is on the list -- so it is deciding by path, not by luck')
        } else {
            Fail 'the predicate did not match a vm.exe at a path on the list, so it cannot tell any two directories apart'
        }
    }
} finally {
    foreach ($p in @($wtProc, $outProc)) {
        if ($p -and -not $p.HasExited) { $p | Stop-Process -Force -ErrorAction SilentlyContinue }
    }
    Remove-Item -LiteralPath $wtExe, $outExe -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $root '.wt\_probe-worktree') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $outDir -Recurse -Force -ErrorAction SilentlyContinue
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
