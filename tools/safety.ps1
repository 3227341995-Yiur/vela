# ---------------------------------------------------------------------------
# tools/safety.ps1 — Vela's safety-promise harness.
#
# `DESIGN.md` §1.2 claims Vela is "safer than Rust" and lists the promises;
# `SPEC.md` §4/§6/§7 says which programs must be refused, which must panic at run
# time and which must keep compiling.  This script is how those sentences become
# something a person can run.
#
#   pwsh -File tools\safety.ps1                 # every case (no arguments needed)
#   pwsh -File tools\safety.ps1 -Only bounds_*  # cases whose name matches
#   pwsh -File tools\safety.ps1 -Skip hole_*,probe_*
#   pwsh -File tools\safety.ps1 -NoXfail        # only the promises it keeps
#   pwsh -File tools\safety.ps1 -List           # the plan, without running it
#   pwsh -File tools\safety.ps1 -FrozenVm <path>   # reproduce a recorded run
#
# On this machine the interpreter is Windows PowerShell 5.1, so `powershell
# -NoProfile -ExecutionPolicy Bypass -File tools\safety.ps1` is the invocation;
# everything here stays inside 5.1 and CLR 4.0 (no `pwsh`, `ArgumentList` or
# `?-ternary`).
#
# WHAT IT MEASURES AGAINST, AND WHY THAT MATTERS
#
# With no arguments the harness copies the compiler ONCE, before any case runs,
# into `%TEMP%\vela-safety\frozen\vm.exe` (creating the directory itself, and
# copying `LLVM-C.dll` beside it), and every case runs against that copy.  The
# copy's SHA256 and byte size are printed first and again at the end.  The reason
# is not tidiness: this repository's compiler is rebuilt while the corpus is being
# worked on (it happened five times in the session these cases were written, and
# once mid-run — a build that overwrote `vm.exe` under this script made a case
# report `build exit=-1`), so a run whose cases were judged by two different
# compilers proves nothing about either.  The copy is the record, and `-FrozenVm`
# pins an older one when a recorded number has to be reproduced.
#
# (A bug lived here, and is worth naming because it broke exactly the promise this
# header makes: `$FrozenVm` was defaulted *before* the "is it pinned" test, so the
# copy branch was dead code and `-FrozenVm` was silently required, while the header
# still said a no-argument run freezes a copy.  Both modes are now exercised:
# deleting `frozen\` and running with no arguments copies and prints
# `copied once from ...`, and `-FrozenVm <path>` still uses that file in place.)
#
# `vm.exe` needs its own directory back: the build of 2026-09-20 links against
# `LLVM-C.dll`, so the DLL is copied beside the frozen compiler too.  Without it
# every case dies with exit -1073741515 (0xC0000135, "DLL not found") — measured,
# and how this harness first failed after that rebuild.
#
# The hash this harness was written and last run against, for comparison:
#
#     SHA256  AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0
#     size    795136 bytes   (selfhost\build\vm.exe, 2026-09-20 02:52)
#
# The measurements in `SAFETY.md` were taken against that build, with two earlier
# builds pinned beside it for the before/after comparison (`-FrozenVm`):
#
#     67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A / 770560
#       — before the operator-table fix: the seven `hole_cmp_*` / `hole_unary_*`
#         rows are holes, the tally says `10 xfail rows showing their violation`
#       AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0 / 795136
#       — after it: the same seven rows read `CLOSED`, the tally says
#         `3 xfail rows ... / 7 holes CLOSED`
#
# A different hash is not an error — another agent may have rebuilt the compiler —
# but it means the numbers recorded in `SAFETY.md` were measured against a
# different binary, and the harness says so loudly when that happens.
#
# WHAT A CASE IS
#
# `tests/safety/cases/<name>.vel` plus one row in `tests/safety/manifest.txt`: the
# mode, the exit code, the substring the diagnostic must contain, and the stdout it
# must print.  Those rows were written by hand from the spec; the script never
# records what the compiler did, it only compares.  A row whose `xfail` column says
# `yes` is a *hole*: the case must show the violation, and it is tallied separately
# so the harness can be green while the hole stays visible.
#
# THE TRAPS THIS SCRIPT IS WRITTEN AROUND (all of them already paid for here)
#
#   * `Select-String` has no `-Recurse` in PS 5.1 — so no `Select-String` at all.
#   * `${name}:` is a scope reference, not a label — so no `${var}:` spellings.
#   * a native program's exit code arrives as an unsigned int64; `exit 2` shows up
#     as 4294967296 unless it is converted, and both front ends use exit 2.
#   * `$ErrorActionPreference = 'Stop'` turns a native program's stderr into a
#     terminating error, so every native run is captured through `cmd /c ... 1> 2>`
#     into files and read back, and the capture files have unique names because
#     cl.exe hands its handles to detached helper processes (tests/run_tests.vel
#     documents the same trap from the other side).
#   * `1> file` from PowerShell re-encodes as UTF-16 — so the script writes its own
#     files with .NET and never redirects PowerShell output into one.
#
# EXIT CODE: 0 when every non-xfail case passed and no xfail case lacked its
# violation; 1 otherwise.  The tally and the `RESULT:` line are printed either way.
# ---------------------------------------------------------------------------

param(
    [string[]]$Only = @(),
    [string[]]$Skip = @(),
    # Run only the rows that are supposed to pass, so the `N passed, 0 failed`
    # line is about the promises the compiler does keep.  Every xfail row is a hole
    # in SAFETY.md §3 and is reported separately by a full run.
    [switch]$NoXfail,
    [switch]$List,
    [string]$CaseDir,
    [string]$Manifest,
    [string]$Root,
    # Use an already-frozen compiler instead of copying `selfhost\build\vm.exe`
    # again.  Two uses: pinning a specific build in a session where the compiler is
    # being rebuilt (which is this repository's normal state), and reproducing a
    # run whose hash is quoted in SAFETY.md.
    [string]$FrozenVm
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# `powershell -File tools\safety.ps1 -Only a,b` hands the filter through as one
# string when it is quoted and as several arguments when it is not, and with two or
# more of them the switch parses as positional junk (measured: `-Only a,b,c` ran
# zero cases and still printed RESULT: PASS).  Splitting each element on commas
# accepts every spelling, `-Only a,b`, `-Only 'a','b'` and `-Only a b`.
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_.Trim().Length -gt 0 })
$Skip = @($Skip | ForEach-Object { $_ -split ',' } | Where-Object { $_.Trim().Length -gt 0 })

# ------------------------------------------------------------------ locations
if (-not $Root) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}
if (-not $CaseDir)  { $CaseDir  = Join-Path $Root 'tests\safety\cases' }
if (-not $Manifest) { $Manifest = Join-Path $Root 'tests\safety\manifest.txt' }

$ScratchRoot = Join-Path $env:TEMP 'vela-safety'
$FrozenDir   = Join-Path $ScratchRoot 'frozen'
$RunDir      = Join-Path $ScratchRoot 'run'
$SourceVm    = Join-Path $Root 'selfhost\build\vm.exe'
$SourceDll   = Join-Path $Root 'selfhost\build\LLVM-C.dll'
# `-FrozenVm` is a *pin*: when the caller names one, that file is used where it
# lies.  When it is not named there is no path at all yet — the default below is
# only a comment on where the copy will go, and the earlier bug here was exactly
# this: assigning `$DefaultVm` into `$FrozenVm` before the "is it pinned" test made
# `$FrozenVm` non-empty, so the copy branch became dead code and `-FrozenVm` was
# silently required despite the header promising a no-argument run.
$pinned = ($FrozenVm -ne '' -and $null -ne $FrozenVm)

# --------------------------------------------------------- freeze the compiler
New-Item -ItemType Directory -Force -Path $FrozenDir, $RunDir | Out-Null

if ($pinned) {
    # An explicit -FrozenVm is used as it stands: it is not copied, and its own
    # directory is where its LLVM-C.dll has to be.  Reproducing a recorded run is
    # the point, so the hash is reported and the reference-hash check below decides
    # whether it is a build this script knows.
    if (-not (Test-Path $FrozenVm)) {
        Write-Host ('RESULT: -FrozenVm {0} does not exist' -f $FrozenVm)
        exit 1
    }
    $FrozenDir = Split-Path -Parent $FrozenVm
    $frozenHow = 'pinned by -FrozenVm (used in place, nothing copied)'
}
else {
    if (-not (Test-Path $SourceVm)) {
        Write-Host ('RESULT: no compiler at {0} — build it first' -f $SourceVm)
        exit 1
    }
    $FrozenVm = Join-Path $FrozenDir 'vm.exe'
    Copy-Item $SourceVm $FrozenVm -Force
    $frozenHow = ('copied once from {0}' -f $SourceVm)
}
$hash = (Get-FileHash $FrozenVm -Algorithm SHA256).Hash
$size = (Get-Item $FrozenVm).Length

# The frozen copy needs the compiler's own DLL beside it.  A `vm.exe` built on
# 2026-09-20 links against `LLVM-C.dll` (74 MB), so a copy of `vm.exe` alone in
# %TEMP% dies with exit -1073741515 (0xC0000135, "DLL not found") before it reads
# anything — measured, and how this harness first failed after that rebuild.  The
# DLL's size and timestamp are printed with the hash so a rebuilt DLL is visible;
# it is not hashed, because hashing 74 MB on every invocation is not what this
# script is for, and the compiler's own hash already anchors the run.
$dllBeside = Join-Path $FrozenDir 'LLVM-C.dll'
if (-not $pinned -and (Test-Path $SourceDll)) {
    Copy-Item $SourceDll $dllBeside -Force
}
if (Test-Path $dllBeside) {
    $dllInfo = Get-Item $dllBeside
    $dllNote = ('{0} bytes, {1:yyyy-MM-dd HH:mm:ss}' -f $dllInfo.Length, $dllInfo.LastWriteTime)
}
elseif ($pinned -and (Test-Path $SourceDll)) {
    $dllNote = 'absent beside the pinned compiler — it may fail to start (exit -1073741515)'
}
else {
    $dllNote = 'absent (this compiler does not need one)'
}

Write-Host '==========================================================================='
Write-Host 'vela safety harness — tools/safety.ps1'
Write-Host '==========================================================================='
Write-Host ('frozen compiler : {0}' -f $FrozenVm)
Write-Host ('                  SHA256 {0}' -f $hash)
Write-Host ('                  size   {0} bytes' -f $size)
Write-Host ('                  {0}' -f $frozenHow)
Write-Host ('frozen runtime  : LLVM-C.dll beside it, {0}' -f $dllNote)
Write-Host ('reference hash  : SHA256 AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0 / 795136 bytes')
if ($hash -ne 'AD4A997B132728449D91D789E5CFC26A8A82CAAD466B41F78AC3C5041FBE73D0') {
    Write-Host 'NOTE: this is not the compiler SAFETY.md''s numbers were measured against.'
    Write-Host '      The corpus is still valid; the recorded results in SAFETY.md are not.'
}
Write-Host ('cases           : {0}' -f $CaseDir)
Write-Host ('manifest        : {0}' -f $Manifest)
Write-Host ''

# ---------------------------------------------------------- native plumbing
# Every native program is run through cmd.exe with both streams redirected into
# files; the files are read back with .NET so nothing is re-encoded.
$script:RunSerial = 0

# Runs one program and returns a hashtable of the result.
#
# Three ways of doing this are wrong here, and two were measured:
#
#   * `cmd /c "<exe>" check "<file>" 1>o 2>e` *with PowerShell's own argument
#     handling* — cmd.exe strips the first and the last quote character of the line
#     it is given, so the program name loses a quote and every case comes back with
#     `The filename, directory name, or volume label syntax is incorrect.` and exit
#     1.  Nothing to do with the paths, which are all valid: it is the quoting rule
#     cited in tests/run_tests.vel's `shell()` and in selfhost/parts/vm_main.vel.
#     The fix is the extra pair of quotes below (`cmd /c ""<exe>" check "<file>""`).
#   * redirecting PowerShell's own `*>` into a file — re-encodes as UTF-16.
#   * `ProcessStartInfo.ArgumentList` — .NET 4.x has no such property, and this
#     repository's PowerShell is 5.1 on CLR 4.0.30319.
#
# So the command line is built by hand, one pair of quotes around a path, and the
# capture goes through files written by .NET (never by PowerShell redirection).
# The exit code comes back signed by itself, which also retires the unsigned-int64
# trap: `exit 2` does not arrive as 4294967296 through Process.ExitCode.
function Get-CapturePath([string]$tag, [string]$ext) {
    $script:RunSerial++
    return (Join-Path $RunDir ('cap_{0}_{1}{2}' -f $script:RunSerial, $tag, $ext))
}

function Invoke-Captured([string]$exe, [string[]]$arguments, [string]$tag) {
    $o = Get-CapturePath $tag '.out'
    $e = Get-CapturePath $tag '.err'
    [System.IO.File]::WriteAllText($o, '!')
    [System.IO.File]::WriteAllText($e, '!')
    $line = 'cmd /c ' + ('"' + ('"{0}" {1} 1>"{2}" 2>"{3}"' -f $exe, ($arguments -join ' '), $o, $e) + '"')
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = $line
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    if (-not $proc.WaitForExit(600000)) {
        try { $proc.Kill() } catch { }
        return @{ code = -9999; stdout = ''; stderr = ('TIMEOUT after 600s: ' + $exe + ' ' + ($arguments -join ' ')); tag = $tag }
    }
    $code = $proc.ExitCode
    $proc.Dispose()
    $stdout = ''
    $stderr = ''
    for ($try = 0; $try -lt 20; $try++) {
        try {
            $stdout = [System.IO.File]::ReadAllText($o)
            $stderr = [System.IO.File]::ReadAllText($e)
            break
        }
        catch {
            # Another build's cl.exe (or its detached vctip.exe / mspdbsrv.exe)
            # still holds this capture.  tests/run_tests.vel paid for this trap from
            # the other side and writes a unique capture name for every step; a
            # unique name is not enough on its own, because the holder is a
            # *different* process that inherited the handle.  Waiting is cheap and
            # the alternative is a false failure: measured, `build exit=-1` and
            # `the process cannot access the file` appeared once each in two full
            # runs and never reproduced in isolation.
            Start-Sleep -Milliseconds 150
        }
    }
    if ($stdout -eq '!') { $stdout = '' }
    if ($stderr -eq '!') { $stderr = '' }
    return @{ code = $code; stdout = $stdout; stderr = $stderr; tag = $tag }
}

# The compiler's own output on stderr ends with a `vela: panic: ...` line of its
# own; only the first block is the diagnostic a case is about.
function Get-FirstBlock([string]$text) {
    $lines = $text -split "`r?`n"
    $kept = @()
    foreach ($ln in $lines) {
        if ($kept.Count -gt 0 -and $ln -match '^vela: panic: (the program was refused|the front end stopped|the interpreted program stopped|build:)') {
            break
        }
        $kept += $ln
    }
    return ($kept -join "`n").Trim()
}

function Invoke-Vm([string]$mode, [string]$file) {
    return Invoke-Captured $FrozenVm @($mode, $file) 'vm'
}

# `vm.exe build` writes the executable beside the source; the .vel is copied into
# the scratch first, so the repository never collects a compile product.
$script:Built = @{}
function Invoke-Build([string]$caseFile) {
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($caseFile)
    if ($script:Built.ContainsKey($stem)) { return $script:Built[$stem] }
    $scratchVel = Join-Path $RunDir ($stem + '.vel')
    Copy-Item $caseFile $scratchVel -Force
    $scratchExe = Join-Path $RunDir ($stem + '.exe')
    Remove-Item $scratchExe -ErrorAction SilentlyContinue
    $r = Invoke-Captured $FrozenVm @('build', $scratchVel) 'build'
    $r['exe'] = $scratchExe
    $r['vel'] = $scratchVel
    $script:Built[$stem] = $r
    return $r
}

function Invoke-Program([string]$exe) {
    return Invoke-Captured $exe @() 'prog'
}

function Invoke-EmitC([string]$caseFile) {
    return Invoke-Vm 'emit-c' $caseFile
}

# ------------------------------------------------------------ manifest parsing
function Read-Manifest([string]$path) {
    $rows = @()
    $n = 0
    foreach ($raw in [System.IO.File]::ReadAllLines($path)) {
        $n++
        $line = $raw
        $hash = $line.IndexOf('#')
        if ($hash -ge 0) { $line = $line.Substring(0, $hash) }
        $line = $line.Trim()
        if ($line.Length -eq 0) { continue }
        $parts = $line.Split('|')
        # 15 fields and 16 `|`-separated cells, because every row ends with a
        # trailing `|` for its (usually empty) note column.
        if ($parts.Count -lt 15) {
            Write-Host ('manifest line {0} has {1} columns, needs 15 or 16 — skipped' -f $n, $parts.Count)
            continue
        }
        $f = @()
        foreach ($p in $parts) { $f += $p.Trim() }
        while ($f.Count -lt 16) { $f += '' }
        $rows += [pscustomobject]@{
            line       = $n
            name       = $f[0]
            file       = $f[1]
            mode       = $f[2]
            checkExit  = $f[3]
            checkMsg   = $f[4]
            runExit    = $f[5]
            runMsg     = $f[6]
            nativeExit = $f[7]
            nativeMsg  = $f[8]
            nativeOut  = $f[9]
            runOut     = $f[10]
            pragma     = $f[11]
            xfail      = $f[12]
            promise    = $f[13]
            note       = (($f[14..($f.Count - 1)]) -join '|').Trim(' ', '|')
        }
    }
    return $rows
}

# ------------------------------------------------------------------ assertions
# Every check appends a one-line reason when it fails, so the FAIL dump says what
# was expected without the reader having to open the manifest.
function Add-Failure([System.Collections.ArrayList]$problems, [string]$text) {
    $null = $problems.Add($text)
}

function Test-Contains([string]$haystack, [string]$needle) {
    if ($needle.Length -eq 0) { return $true }
    return ($haystack.IndexOf($needle, [System.StringComparison]::Ordinal) -ge 0)
}

function Test-OneLine([string]$actual, [string]$want) {
    $a = $actual.Trim()
    return ($a -eq $want)
}

$rows = Read-Manifest $Manifest
if ($rows.Count -eq 0) {
    Write-Host ('no cases in {0}' -f $Manifest)
    Write-Host 'RESULT: FAIL (empty manifest)'
    exit 1
}

# ---------------------------------------------------------------- the cases
$passed = 0
$failed = 0
$xfailed = 0          # `violation` rows whose rule is still broken (holes open)
$xfailedOther = 0     # non-violation xfail rows, assertion failing as recorded
$closed = 0           # `violation` rows whose rule now holds, in the recorded words
$deliberate = 0       # `deliberate` rows, accepted as documented
$behaviourChanged = 0
$failures = @()
$listed = 0

foreach ($row in $rows) {
    if ($Only.Count -gt 0) {
        $match = $false
        foreach ($p in $Only) { if ($row.name -like $p) { $match = $true } }
        if (-not $match) { continue }
    }
    if ($Skip.Count -gt 0) {
        $skip = $false
        foreach ($p in $Skip) { if ($row.name -like $p) { $skip = $true } }
        if ($skip) { continue }
    }
    if ($NoXfail -and $row.xfail -eq 'yes') { continue }

    $caseFile = Join-Path $CaseDir $row.file
    if (-not (Test-Path $caseFile)) {
        Write-Host ('{0,-46} {1,-9} MISSING FILE {2}' -f $row.name, $row.mode, $caseFile)
        $failed++
        $failures += $row
        continue
    }

    if ($List) {
        $listed++
        Write-Host ('{0,-46} {1,-9} {2}{3}' -f $row.name, $row.mode, $row.promise,
            $(if ($row.xfail -eq 'yes') { '   [xfail]' } else { '' }))
        continue
    }

    $problems = New-Object System.Collections.ArrayList
    $actual = ''
    $cExit = [int]$row.checkExit
    $rExit = [int]$row.runExit
    $nExit = [int]$row.nativeExit

    # A build's stderr is the driver's own narration (`vela: build: call "...cl..."`)
    # on success and the diagnostic on failure, so it is only quoted when the build
    # did not succeed: 65 rows of compiler command lines bury the one that matters.
    $buildNote = ''
    # Per-row state, reset here so no case can inherit another's: `$phaseObserved`
    # and `$recordChanged` are the split between the promise assertion and the
    # run-time recording, and a stale value would poison a verdict.
    $recordChanged = $false
    $phaseObserved = $null
    $runPhase = $null
    $nativePhase = $null

    switch ($row.mode) {

        'check' {
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0} stdout="{1}" stderr={2}' -f $c.code, $c.stdout.Trim(), (Get-FirstBlock $c.stderr))
            if ($c.code -ne $cExit) { Add-Failure $problems ('expected check exit {0}, got {1}' -f $cExit, $c.code) }
            if (-not (Test-Contains $c.stderr $row.checkMsg)) { Add-Failure $problems ('check stderr lacks "{0}"' -f $row.checkMsg) }
            if ($c.stdout.Trim().Length -ne 0) { Add-Failure $problems 'wrote to stdout while being refused' }
        }

        'ok' {
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0} stdout="{1}" stderr={2}' -f $c.code, $c.stdout.Trim(), (Get-FirstBlock $c.stderr))
            if ($c.code -ne $cExit) { Add-Failure $problems ('expected check exit {0}, got {1}' -f $cExit, $c.code) }
            if ($c.stdout.Trim() -ne 'ok') { Add-Failure $problems ('expected stdout "ok", got "{0}"' -f $c.stdout.Trim()) }
            if ($c.stderr.Trim().Length -ne 0) { Add-Failure $problems ('expected empty stderr, got "{0}"' -f (Get-FirstBlock $c.stderr)) }
        }

        'native' {
            $b = Invoke-Build $caseFile
            $actual = ('build exit={0}' -f $b.code)
            if ($b.code -ne 0) {
                Add-Failure $problems ('the program must compile, but build exited {0}' -f $b.code)
                $buildNote = (Get-FirstBlock $b.stderr)
            }
            elseif (-not (Test-Path $b.exe)) {
                Add-Failure $problems 'build reported success but wrote no executable'
            }
            else {
                $p = Invoke-Program $b.exe
                $actual += (' | program exit={0} stderr={1}' -f $p.code, (Get-FirstBlock $p.stderr))
                if ($p.code -ne $nExit) { Add-Failure $problems ('expected the compiled program to exit {0}, got {1}' -f $nExit, $p.code) }
                if (-not (Test-Contains $p.stderr $row.nativeMsg)) { Add-Failure $problems ('compiled stderr lacks "{0}"' -f $row.nativeMsg) }
            }
            $i = Invoke-Vm 'run' $caseFile
            $actual += (' || run exit={0} stderr={1}' -f $i.code, (Get-FirstBlock $i.stderr))
            if ($i.code -ne $rExit) { Add-Failure $problems ('expected run to exit {0}, got {1}' -f $rExit, $i.code) }
            if (-not (Test-Contains $i.stderr $row.runMsg)) { Add-Failure $problems ('run stderr lacks "{0}"' -f $row.runMsg) }
        }

        'run-ok' {
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0}' -f $c.code)
            if ($c.code -ne $cExit) {
                Add-Failure $problems ('check must accept it, but exited {0}: {1}' -f $c.code, (Get-FirstBlock $c.stderr))
            }
            if ($row.pragma -eq 'yes') {
                $ec = Invoke-EmitC $caseFile
                $hasPragma = Test-Contains $ec.stdout '#pragma omp parallel for'
                $actual += (' | emit-c pragma={0}' -f $hasPragma)
                if (-not $hasPragma) { Add-Failure $problems 'the emitted C carries no "#pragma omp parallel for"' }
            }
            $b = Invoke-Build $caseFile
            if ($b.code -ne 0) {
                Add-Failure $problems ('the program must compile, but build exited {0}' -f $b.code)
                $buildNote = (Get-FirstBlock $b.stderr)
            }
            elseif (-not (Test-Path $b.exe)) {
                Add-Failure $problems 'build reported success but wrote no executable'
            }
            else {
                $p = Invoke-Program $b.exe
                $actual += (' | program exit={0} stdout="{1}"' -f $p.code, $p.stdout.Trim())
                if ($p.code -ne $nExit) { Add-Failure $problems ('expected the compiled program to exit {0}, got {1}' -f $nExit, $p.code) }
                if (-not (Test-OneLine $p.stdout $row.nativeOut)) { Add-Failure $problems ('expected the compiled program to print "{0}", got "{1}"' -f $row.nativeOut, $p.stdout.Trim()) }
            }
            $i = Invoke-Vm 'run' $caseFile
            $actual += (' || run exit={0} stdout="{1}"' -f $i.code, $i.stdout.Trim())
            if ($i.code -ne $rExit) { Add-Failure $problems ('expected run to exit {0}, got {1}: {2}' -f $rExit, $i.code, (Get-FirstBlock $i.stderr)) }
            if (-not (Test-OneLine $i.stdout $row.runOut)) { Add-Failure $problems ('expected run to print "{0}", got "{1}"' -f $row.runOut, $i.stdout.Trim()) }
        }

        'violation' {
            # A hole, and BOTH directions have to be able to fail.
            #
            # The promise columns (`check_exit`, `check_msg`) are the ASSERTION: the
            # rule requires `check` to refuse the program *with that message*.  Three
            # outcomes, and the middle one is why this is written the way it is:
            #
            #   check refuses, message identical  -> CLOSED (first-class in the tally)
            #   check refuses, message DIFFERENT  -> FAIL  (the promise holds for a
            #       reason nobody recorded, which is exactly the kind of change that
            #       must be looked at; call it "closed" and it is a check that cannot
            #       fail — the defect this repository keeps finding)
            #   check accepts                     -> FAIL-as-expected (the hole is open)
            #
            # Whatever either front end does with a program the checker should have
            # refused is a RECORD, printed with the row and never a verdict: it is the
            # pre-fix behaviour by construction, so once the checker refuses the
            # program there is no runtime left to record.
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0} stdout="{1}" stderr={2}' -f $c.code, $c.stdout.Trim(), (Get-FirstBlock $c.stderr))
            $exitOk = ($c.code -eq $cExit)
            $msgOk = ($row.checkMsg.Length -eq 0) -or (Test-Contains $c.stderr $row.checkMsg)
            $stdoutOk = ($c.stdout.Trim().Length -eq 0)
            # Two different facts, and the verdict keys off the right one:
            # `refusedAsRequired` is "the checker did what the rule demands" (that is
            # what decides whether the hole is shut), and `checkOk` adds "in the words
            # this row recorded".  Collapsing them was this mode's last bug: a refused
            # program whose message had changed was classified as "still open" instead
            # of "closed for an unrecorded reason".
            $refusedAsRequired = ($exitOk -and $stdoutOk)
            $checkOk = ($refusedAsRequired -and $msgOk)
            if ($checkOk) {
                # The promise holds: nothing is built, because `build` would only run
                # the same checker again and fail at exit 2 — the checker *working*.
                # Building it here was this function's first bug; it turned every
                # closed hole into `the program must compile, but build exited 2`.
            }
            elseif ($refusedAsRequired) {
                Add-Failure $problems ('the promise is kept for a reason this row does not record: check exit {0} as expected, but stderr lacks "{1}"' -f $cExit, $row.checkMsg)
            }
            else {
                Add-Failure $problems ('the promise is not kept: expected check exit {0}, got {1}' -f $cExit, $c.code)
                if (-not $msgOk) { Add-Failure $problems ('check stderr lacks "{0}"' -f $row.checkMsg) }
                if (-not $stdoutOk) { Add-Failure $problems 'wrote to stdout while being refused' }
            }

            $i = Invoke-Vm 'run' $caseFile
            $recRun = ('run exit={0} stdout="{1}"{2}' -f $i.code, $i.stdout.Trim(),
                $(if ((Get-FirstBlock $i.stderr).Length -gt 0) { ' stderr=' + (Get-FirstBlock $i.stderr) } else { '' }))
            $recNative = 'not built (the checker refuses the program, which is the promise)'
            $nativePhase = $null
            if ($refusedAsRequired) {
                # nothing to record beyond the interpreter's answer
            }
            else {
                $b = Invoke-Build $caseFile
                if ($b.code -ne 0) {
                    $recNative = if ($b.code -eq 2) { 'build refused (the checker refuses the program)' } else { ('build exit={0}' -f $b.code) }
                    if ($nExit -eq 0) {
                        Add-Failure $problems ('the program must compile, but build exited {0}' -f $b.code)
                        $buildNote = (Get-FirstBlock $b.stderr)
                    }
                }
                elseif (-not (Test-Path $b.exe)) {
                    Add-Failure $problems 'build reported success but wrote no executable'
                }
                else {
                    $p = Invoke-Program $b.exe
                    $recNative = ('program exit={0} stdout="{1}"{2}' -f $p.code, $p.stdout.Trim(),
                        $(if ((Get-FirstBlock $p.stderr).Length -gt 0) { ' stderr=' + (Get-FirstBlock $p.stderr) } else { '' }))
                    $nativePhase = @($p.code, $p.stdout.Trim(), $p.stderr)
                    if ($nExit -eq 0) {
                        if ($p.code -ne 0) { Add-Failure $problems ('the program must compile, but it exited {0}' -f $p.code) }
                        if ($row.nativeOut.Length -gt 0 -and -not (Test-OneLine $p.stdout $row.nativeOut)) {
                            Add-Failure $problems ('expected the compiled program to print "{0}", got "{1}"' -f $row.nativeOut, $p.stdout.Trim())
                        }
                    }
                    else {
                        if ($p.code -ne $nExit) { Add-Failure $problems ('expected the compiled program to exit {0}, got {1}' -f $nExit, $p.code) }
                        if ($row.nativeMsg.Length -gt 0 -and -not (Test-Contains $p.stderr $row.nativeMsg)) {
                            Add-Failure $problems ('compiled stderr lacks "{0}"' -f $row.nativeMsg)
                        }
                    }
                }
            }
            $runPhase = @($i.code, $i.stdout.Trim(), $i.stderr)
            # "Did the recording still describe reality?" — the exact exit code and
            # stdout when the row recorded one.  Either answer is only ever a note:
            # once the checker refuses the program there is no run of it left to
            # observe, so the recording describes the pre-fix behaviour by design and
            # its disappearance is the fix, not a failure.
            $runtimeMatches = ($i.code -eq $rExit)
            if ($runtimeMatches -and $row.runOut.Length -gt 0) { $runtimeMatches = (Test-OneLine $i.stdout $row.runOut) }
            if ($checkOk -and $nativePhase -ne $null) {
                if ($nativePhase[0] -ne $nExit) { $runtimeMatches = $false }
                elseif ($row.nativeOut.Length -gt 0 -and -not (Test-OneLine $nativePhase[1] $row.nativeOut)) { $runtimeMatches = $false }
            }
            if ($refusedAsRequired -and -not $runtimeMatches) { $recordChanged = $true }
            $phaseObserved = $refusedAsRequired
            $actual = ('check exit={0} stdout="{1}"{2} || run exit={3} stdout="{4}" || native {5}' -f `
                $c.code, $c.stdout.Trim(),
                $(if ((Get-FirstBlock $c.stderr).Length -gt 0) { ' stderr=' + (Get-FirstBlock $c.stderr) } else { '' }),
                $i.code, $i.stdout.Trim(), $recNative)
        }

        'diverge' {
            # The two front ends must answer *differently*, and each answer is
            # asserted — this is a documented gap in a promise, not a hole in it
            # (SPEC.md §3.1 "A binding is not type-checked"), so it is not an xfail
            # row: it fails loudly if the two ever agree again, whether that is
            # because the compiler stopped narrowing or the interpreter started.
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0}' -f $c.code)
            if ($c.code -ne $cExit) { Add-Failure $problems ('check must accept it, but exited {0}: {1}' -f $c.code, (Get-FirstBlock $c.stderr)) }
            $b = Invoke-Build $caseFile
            if ($b.code -ne 0 -or -not (Test-Path $b.exe)) {
                Add-Failure $problems ('the program must compile, but build exited {0}' -f $b.code)
                $buildNote = (Get-FirstBlock $b.stderr)
            }
            else {
                $p = Invoke-Program $b.exe
                $actual += (' | program exit={0} stdout="{1}"' -f $p.code, $p.stdout.Trim())
                if (-not (Test-OneLine $p.stdout $row.nativeOut)) { Add-Failure $problems ('expected the compiled program to print "{0}", got "{1}"' -f $row.nativeOut, $p.stdout.Trim()) }
            }
            $i = Invoke-Vm 'run' $caseFile
            $actual += (' || run exit={0} stdout="{1}"' -f $i.code, $i.stdout.Trim())
            if (-not (Test-OneLine $i.stdout $row.runOut)) { Add-Failure $problems ('expected run to print "{0}", got "{1}"' -f $row.runOut, $i.stdout.Trim()) }
            if ($row.nativeOut -eq $row.runOut) { Add-Failure $problems 'a diverge row must expect two different answers' }
        }

        'deliberate' {
            # A row for a *deliberate* gap in the rule — the promise is that `check`
            # ACCEPTS the program, and that the back end refuses it afterwards.  The
            # two directions both matter, and the second is the reason this mode
            # exists: if the checker starts refusing it, the documented decision has
            # changed and somebody has to look at the file that says so.  A mode that
            # only ever reported DELIBERATE could not notice that.
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0} stdout="{1}" stderr={2}' -f $c.code, $c.stdout.Trim(), (Get-FirstBlock $c.stderr))
            $checkOk = ($c.code -eq $cExit) -and ($c.stdout.Trim() -eq 'ok') -and ($c.stderr.Trim().Length -eq 0)
            if ($checkOk) {
                # Accepted, as the row says it should be: now the back end has to
                # refuse it, or the "deliberate gap" is really a silent acceptance.
                $b = Invoke-Build $caseFile
                if ($b.code -ne 0) {
                    $actual += (' || build exit={0} (the back end refuses it){1}' -f $b.code,
                        $(if ((Get-FirstBlock $b.stderr).Length -gt 0) { ' ' + (Get-FirstBlock $b.stderr) } else { '' }))
                    if ($b.code -ne 2 -and $nExit -eq 0) {
                        Add-Failure $problems ('the row says the back end refuses it, but build exited {0} for another reason' -f $b.code)
                    }
                }
                elseif (-not (Test-Path $b.exe)) {
                    Add-Failure $problems 'build reported success but wrote no executable'
                }
                else {
                    $p = Invoke-Program $b.exe
                    $actual += (' || program exit={0} stdout="{1}"' -f $p.code, $p.stdout.Trim())
                    if ($p.code -eq 0) {
                        Add-Failure $problems 'the row says the back end refuses this program, but the compiled program exited 0'
                    }
                }
            }
            else {
                # The row's promise is that `check` ACCEPTS.  Whether it refused
                # (`exit 2`) or accepted but not cleanly (exit 0 with a warning, say),
                # the documented decision no longer holds as written, and the file
                # that states it has to be re-read.
                Add-Failure $problems ('a DELIBERATE row is no longer accepted: the row requires check exit {0} and stdout "ok", but check gave exit {1} stdout "{2}"{3} — the documented decision has changed, so the file that states it has to change too' -f `
                    $cExit, $c.code, $c.stdout.Trim(),
                    $(if ((Get-FirstBlock $c.stderr).Length -gt 0) { ' stderr=' + (Get-FirstBlock $c.stderr) } else { '' }))
            }
            $i = Invoke-Vm 'run' $caseFile
            $actual += (' || run exit={0} stdout="{1}"' -f $i.code, $i.stdout.Trim())
        }

        default {
            Add-Failure $problems ('unknown mode "{0}"' -f $row.mode)
        }
    }

    # The verdict.  Three kinds of row, and each has to be able to fail in BOTH
    # directions — a check that cannot fail is the defect this repository keeps
    # finding, and the first version of this harness had one:
    #
    #   xfail      FAIL-as-expected (the promise is broken, as recorded)
    #              CLOSED (the promise holds *and says so in the recorded words*)
    #              FAIL  (anything else, including a refusal worded differently:
    #                     "closed" on a message nobody recorded is a silent pass)
    #   deliberate DELIBERATE (check accepts, as the decision documents)
    #              FAIL  (check refuses: the decision changed, someone must look)
    #   ordinary   PASS / FAIL / BEHAVIOUR-CHANGED on the promise itself
    $isXfail = ($row.xfail -eq 'yes')
    # Only a `violation` row has the closed/open distinction: its assertion is "the
    # rule is kept", which can be observed either way.  The other xfail rows state a
    # plain assertion that is simply *expected to fail* (`hole_mut_scalar_parameter`
    # promises 11 and the compiler prints 10), and dressing those up as
    # `FAIL-as-expected` would be the very thing this rewrite is about — a check that
    # cannot fail.  They are ordinary rows that happen to be failing; the `xfail`
    # column only keeps them out of the exit code.
    $isHoleRow = ($isXfail -and $row.mode -eq 'violation')
    $isDeliberate = ($row.mode -eq 'deliberate')
    $verdictProblems = @($problems)
    if ($isHoleRow) { $verdictProblems = @($problems | Where-Object { $_ -notlike 'runtime-record-changed*' }) }
    $violation = ($verdictProblems.Count -gt 0)
    $state = if ($violation) { 'FAIL' } else { 'PASS' }
    if ($isDeliberate) {
        if ($violation) { $state = 'FAIL'; $failed++; $failures += $row }
        else { $state = 'DELIBERATE'; $deliberate++ }
    }
    elseif ($isHoleRow) {
        if ($violation) {
            if ($phaseObserved -eq $false) {
                # The promise is still broken: the hole is open, which is what the
                # row records.  Counted apart from a real failure, and NOT counted as
                # a pass — the tally says how many holes are still open.
                $state = 'FAIL-as-expected'
                $xfailed++
            }
            else {
                # The promise holds, but not in the words the row wrote down.
                $state = 'FAIL'
                $failed++
                $failures += $row
            }
        }
        else { $state = 'CLOSED'; $closed++ }
    }
    elseif ($recordChanged) {
        # Not a hole row and the run-time answer moved: the promise assertion still
        # holds, so this cannot be a silent pass, and it is not a promise failure
        # either.  Loud, and in its own column.
        $state = 'BEHAVIOUR-CHANGED'
        $behaviourChanged++
        $failures += $row
    }
    elseif ($isXfail) {
        # A non-violation xfail row: the assertion is expected to fail, so a failure
        # is the recorded state and a clean pass is news.  Both are reported, neither
        # in the exit code, and the tally counts the recorded failures.
        if ($violation) { $state = 'FAIL-expected'; $xfailedOther++ }
        else { $state = 'UNEXPECTED-PASS'; $xfailMissing++ }
    }
    else {
        if ($violation) { $failed++ } else { $passed++ }
        if ($violation) { $failures += $row }
    }

    Write-Host ('{0,-46} {1,-10} {2}' -f $row.name, $row.mode, $state)
    Write-Host ('    actual: {0}' -f $actual)
    if ($buildNote.Length -gt 0) {
        foreach ($ln in ($buildNote -split "`r?`n")) { if ($ln.Trim().Length -gt 0) { Write-Host ('    build : {0}' -f $ln.Trim()) } }
    }
    if ($violation) {
        foreach ($p in $verdictProblems) { Write-Host ('    reason: {0}' -f $p) }
    }
    if ($recordChanged) {
        $which = if ($isXfail) { 'the promise now holds, so this is the post-fix behaviour' } else { 'the run-time answer moved' }
        Write-Host ('    note   : the run-time recording no longer matches — {0}; the promise assertion is unaffected' -f $which)
    }
    if ($row.promise.Length -gt 0 -or $row.note.Length -gt 0) {
        Write-Host ('    promise: {0}{1}' -f $row.promise, $(if ($row.note.Length -gt 0) { '  --  ' + $row.note } else { '' }))
    }
}

if ($List) {
    Write-Host ''
    Write-Host ('{0} of {1} cases listed (--Only/--Skip/--NoXfail applied)' -f $listed, $rows.Count)
    exit 0
}

# -------------------------------------------------------------------- tally
$total = $passed + $failed
Write-Host ''
Write-Host '---------------------------------------------------------------------------'
Write-Host ('frozen compiler : SHA256 {0} / {1} bytes' -f $hash, $size)
Write-Host ('tally           : {0} passed, {1} failed' -f $passed, $failed)
Write-Host ('                  violations still open (FAIL-as-expected): {0}' -f $xfailed)
Write-Host ('                  violations now closed (CLOSED):            {0}' -f $closed)
Write-Host ('                  other xfail rows failing as recorded:      {0}' -f $xfailedOther)
Write-Host ('                  deliberate gaps documented (DELIBERATE):   {0}' -f $deliberate)
if ($behaviourChanged -gt 0) {
    Write-Host ('                  rows whose run-time recording moved: {0}' -f $behaviourChanged)
}
if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host 'cases to look at:'
    foreach ($row in $failures) { Write-Host ('  {0}  ({1})' -f $row.name, $row.mode) }
}
$bad = ($failed -gt 0 -or $behaviourChanged -gt 0)
if ($bad) { Write-Host 'RESULT: FAIL' } else { Write-Host 'RESULT: PASS' }
exit $(if ($bad) { 1 } else { 0 })
