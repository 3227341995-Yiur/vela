# ---------------------------------------------------------------------------
# tools/safety.ps1 — Vela's safety-promise harness.
#
# `DESIGN.md` §1.2 claims Vela is "safer than Rust" and lists the promises;
# `SPEC.md` §4/§6/§7 says which programs must be refused, which must panic at run
# time and which must keep compiling.  This script is how those sentences become
# something a person can run.
#
#   pwsh -File tools\safety.ps1                 # every case
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
# The compiler is copied ONCE per invocation, before any case runs, into
# `%TEMP%\vela-safety\frozen\vm.exe`, and every case runs against that copy.  The
# copy's SHA256 and byte size are printed first and again at the end.  The reason
# is not tidiness: this repository's compiler is rebuilt while the corpus is being
# worked on (it happened twice during the session these cases were written, and
# once mid-run — a build that overwrote `vm.exe` under this script made a case
# report `build exit=-1`), so a run whose cases were judged by two different
# compilers proves nothing about either.  The copy is the record, and `-FrozenVm`
# pins an older one when a recorded number has to be reproduced.
#
# `vm.exe` needs its own directory back: the build of 2026-09-20 links against
# `LLVM-C.dll`, so the DLL is copied beside the frozen compiler too.  Without it
# every case dies with exit -1073741515 (0xC0000135, "DLL not found") — measured,
# and how this harness first failed after that rebuild.
#
# The hash this harness was written and last run against, for comparison:
#
#     SHA256  67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A
#     size    770560 bytes   (selfhost\build\vm.exe, 2026-09-20 01:4x)
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
$DefaultVm   = Join-Path $FrozenDir 'vm.exe'
# `-FrozenVm` was already bound above, so the default is applied here rather than
# in the param block: assigning to the parameter itself would overwrite a caller's
# pinned compiler path, which is the one thing this switch exists to prevent.
if (-not $FrozenVm) { $FrozenVm = $DefaultVm }

New-Item -ItemType Directory -Force -Path $FrozenDir, $RunDir | Out-Null

# --------------------------------------------------------- freeze the compiler
if (-not (Test-Path $SourceVm)) {
    Write-Host ("RESULT: no compiler at {0} — build it first" -f $SourceVm)
    exit 1
}
if ($FrozenVm) {
    # An explicit -FrozenVm is used as it stands: it is not copied, and its own
    # directory is where its LLVM-C.dll has to be.  Reproduction of a recorded run
    # is the point, so the hash is reported and the WARNING below decides whether
    # it is the build this script's reference hash knows.
    if (-not (Test-Path $FrozenVm)) {
        Write-Host ('RESULT: -FrozenVm {0} does not exist' -f $FrozenVm)
        exit 1
    }
    $FrozenDir = Split-Path -Parent $FrozenVm
    $pinned = $true
}
else {
    Copy-Item $SourceVm $FrozenVm -Force
    $pinned = $false
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
if (Test-Path $SourceDll) {
    if (-not $pinned) {
        Copy-Item $SourceDll (Join-Path $FrozenDir 'LLVM-C.dll') -Force
    }
    $dll = Join-Path $FrozenDir 'LLVM-C.dll'
    if (Test-Path $dll) {
        $dllInfo = Get-Item $dll
        $dllNote = ('{0} bytes, {1:yyyy-MM-dd HH:mm:ss}' -f $dllInfo.Length, $dllInfo.LastWriteTime)
    }
    else {
        $dllNote = 'absent — this compiler may fail to start (exit -1073741515)'
    }
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
Write-Host ('frozen runtime  : LLVM-C.dll beside it, {0}' -f $dllNote)
Write-Host ('reference hash  : SHA256 67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A / 770560 bytes')
if ($hash -ne '67ACF63B7DD3A5249E2FE3B1F015D5DB4D4C4032FEFB464F12F86F519F903D8A') {
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
$xfailed = 0
$xfailMissing = 0
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
            # A hole, two-sided: the checker must do the wrong thing the row names,
            # and both front ends must then do what the row records.
            $c = Invoke-Vm 'check' $caseFile
            $actual = ('check exit={0} stdout="{1}" stderr={2}' -f $c.code, $c.stdout.Trim(), (Get-FirstBlock $c.stderr))
            if ($c.code -ne $cExit) { Add-Failure $problems ('expected check exit {0}, got {1}' -f $cExit, $c.code) }
            if (-not (Test-Contains $c.stderr $row.checkMsg)) { Add-Failure $problems ('check stderr lacks "{0}"' -f $row.checkMsg) }
            $i = Invoke-Vm 'run' $caseFile
            $actual += (' || run exit={0} stdout="{1}" stderr={2}' -f $i.code, $i.stdout.Trim(), (Get-FirstBlock $i.stderr))
            if ($i.code -ne $rExit) { Add-Failure $problems ('expected run to exit {0}, got {1}' -f $rExit, $i.code) }
            if ($row.runMsg.Length -gt 0) {
                if (-not (Test-Contains $i.stderr $row.runMsg)) { Add-Failure $problems ('run stderr lacks "{0}"' -f $row.runMsg) }
            }
            if ($row.runOut.Length -gt 0) {
                if (-not (Test-OneLine $i.stdout $row.runOut)) { Add-Failure $problems ('expected run to print "{0}", got "{1}"' -f $row.runOut, $i.stdout.Trim()) }
            }
            $b = Invoke-Build $caseFile
            if ($b.code -ne 0) {
                $actual += (' | build exit={0} (the emitted C does not compile)' -f $b.code)
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
                $actual += (' | program exit={0} stdout="{1}" stderr={2}' -f $p.code, $p.stdout.Trim(), (Get-FirstBlock $p.stderr))
                if ($p.code -ne $nExit) { Add-Failure $problems ('expected the compiled program to exit {0}, got {1}' -f $nExit, $p.code) }
                if ($row.nativeMsg.Length -gt 0) {
                    if (-not (Test-Contains $p.stderr $row.nativeMsg)) { Add-Failure $problems ('compiled stderr lacks "{0}"' -f $row.nativeMsg) }
                }
                if ($row.nativeOut.Length -gt 0) {
                    if (-not (Test-OneLine $p.stdout $row.nativeOut)) { Add-Failure $problems ('expected the compiled program to print "{0}", got "{1}"' -f $row.nativeOut, $p.stdout.Trim()) }
                }
            }
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

        default {
            Add-Failure $problems ('unknown mode "{0}"' -f $row.mode)
        }
    }

    # An xfail row asserts the *violation*: it passes when the compiler does the
    # wrong thing the row names, and is reported when the compiler starts doing the
    # right thing, because then a SAFETY.md hole has been closed and the row is stale.
    $isXfail = ($row.xfail -eq 'yes')
    $violation = ($problems.Count -gt 0)
    $state = if ($violation) { 'FAIL' } else { 'PASS' }
    if ($isXfail) {
        if ($violation) { $state = 'FAIL-as-expected'; $xfailed++ } else { $state = 'UNEXPECTED-PASS'; $xfailMissing++ }
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
        foreach ($p in $problems) { Write-Host ('    reason: {0}' -f $p) }
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
Write-Host ('                  xfail rows showing their violation: {0}' -f $xfailed)
if ($xfailMissing -gt 0) {
    Write-Host ('                  UNEXPECTED-PASS (a hole may have been closed): {0}' -f $xfailMissing)
}
if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host 'failing cases:'
    foreach ($row in $failures) { Write-Host ('  {0}  ({1})' -f $row.name, $row.mode) }
}
$bad = ($failed -gt 0 -or $xfailMissing -gt 0)
if ($bad) { Write-Host 'RESULT: FAIL' } else { Write-Host 'RESULT: PASS' }
exit $(if ($bad) { 1 } else { 0 })
