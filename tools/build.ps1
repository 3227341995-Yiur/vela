# tools/build.ps1 — build and verify the whole self-hosted toolchain, with no
# Python anywhere in the loop.
#
# What has to exist for Vela to exist: a C compiler, one C file
# (selfhost/build/vm.c), and runtime/vela_runtime.h.  Not a Python program.
# Everything below the first step is done by the Vela compiler itself, including
# its own rebuild: `vm.exe build selfhost/vm.vel` compiles the compiler with the
# compiler, driving cl.exe through its own build driver.
#
#     .\tools\build.ps1                                   # bootstrap + rebuild everything
#     .\tools\build.ps1 -Suites                           # ... and run tests/run_tests.vel
#     .\tools\build.ps1 -Record -Suites                    # ... re-freezing the goldens first
#     .\tools\build.ps1 -Force                            # rebuild vm.exe from the seed C
#
# Steps: 1 vm.exe from the seed C · 2 link the parts (Vela linker) · 3 the
# compiler compiles itself · 4 the standalone lexer · 5 the fixpoint: the
# compiler it built writes the same C again · 6 the Vela test suite (only with
# -Suites or -Record) · 7 reap the detached `vctip.exe`/`mspdbsrv.exe` that
# `cl.exe` leaves behind, because while one is alive the harness that runs this
# script refuses to run anything else — see the note at step 7 for the
# measurement, and note that `VSCMD_SKIP_SENDTELEMETRY=1` does not prevent it.
#
# No Python anywhere in this file, or in anything it runs.  Stage 0 was deleted
# once the goldens in tests/golden/ had been certified against it, and this
# script is the whole of the toolchain that remains: a C compiler, one C file,
# runtime/vela_runtime.h, and Vela.
#
# Every step's output and exit code lands in the report file (default
# ..\vela-build-report.txt), because a build that cannot be read afterwards is a
# build nobody can argue with.  Run with -Quiet to keep the console short.
#
# Exits non-zero as soon as a step fails.  Nothing outside the repository is
# written, no registry key is touched, and nothing is deleted that the build did
# not create.

[CmdletBinding()]
param(
    [switch] $Suites,
    [switch] $Record,
    [switch] $Quiet,
    [switch] $Force,
    [string] $Report = ""
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot            # the repository root
Set-Location -LiteralPath $root

# Leftovers first.  Before panic() stopped calling abort(), every program the
# compiler *refused* was a real Windows crash (0xC0000409), which hands the
# process to Windows Error Reporting — and a crash handler that never exits holds
# the job object it was started in, so the shell that ran it cannot start
# anything afterwards.  Killing them is best-effort and harmless when absent.
foreach ($name in 'WerFault', 'wermgr') {
    try { Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue } catch { }
}
foreach ($name in 'vm', 'cl', 'link') {
    try { Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue } catch { }
}

if (-not $Report) { $Report = Join-Path (Split-Path -Parent $root) 'vela-build-report.txt' }
$transcript = New-Object System.Collections.Generic.List[string]
$failed = $false

function Say([string] $text) {
    $transcript.Add($text)
    if (-not $Quiet) { Write-Host $text }
}

# Written as UTF-8 explicitly.  `Set-Content` uses the machine's ANSI codepage,
# and cl.exe talks about codepages in Chinese on a Chinese Windows install, so
# the report came out as bytes no UTF-8 reader could open — which is a small
# thing until the report is the only evidence of a build on a machine you cannot
# run one on.
function Write-Report {
    [System.IO.File]::WriteAllLines(
        $Report, $transcript, (New-Object System.Text.UTF8Encoding($false)))
}

function Head([string] $text) {
    Say ''
    Say "=== $text"
}

# Run one command, keep its output, and remember its exit code.  stderr is
# captured too: the compiler writes its diagnostics there, and a diagnostic that
# does not reach the report is a diagnostic nobody sees.
function Step([string] $label, [scriptblock] $body) {
    Head $label
    $out = & $body 2>&1 | Out-String
    $code = $LASTEXITCODE
    foreach ($line in ($out -split "`r?`n")) {
        if ($line -ne '') { Say "    $line" }
    }
    Say "    -> exit $code"
    if ($code -ne 0) {
        $script:failed = $true
        Say "    !! $label failed"
    }
    return $code
}

function Find-Vcvars {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat')
    )
    if ($env:VELA_VCVARS -and (Test-Path -LiteralPath $env:VELA_VCVARS)) { return $env:VELA_VCVARS }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    return ''
}

$seedC   = Join-Path $root 'selfhost\build\vm.c'
$seedExe = Join-Path $root 'selfhost\build\vm.exe'
$runtime = Join-Path $root 'runtime'
$vmExe   = 'selfhost\build\vm.exe'

function Build-SeedCompiler {
    # The floor of the bootstrapping: one C file, one C compiler, one header.
    # A batch file rather than a command line, because cmd.exe strips the first
    # and last quote of whatever it is handed and every path here is quoted.
    if (-not (Test-Path -LiteralPath $seedC)) {
        Say "    no $seedC - nothing to bootstrap from"
        return 1
    }
    $vcv = Find-Vcvars
    $lines = @()
    if ($vcv) { $lines += "call `"$vcv`" >nul 2>&1" }
    $lines += "cd /d `"$(Split-Path -Parent $seedC)`""
    $lines += "cl /nologo /std:c11 /O2 /I `"$runtime`" `"$seedC`" /Fe:`"$seedExe`" /Fo:`"$(Join-Path $root 'selfhost\build\vm_boot.obj')`""
    $bat = Join-Path $root 'selfhost\build\_build.ps1.bat'
    [System.IO.File]::WriteAllText($bat, ($lines -join "`r`n") + "`r`n")
    & cmd.exe /c $bat 2>&1 | Out-String | ForEach-Object { if ($_ -ne '') { Say "    $_" } }
    $code = $LASTEXITCODE
    Say "    -> cl exit $code"
    if ($code -ne 0) { $script:failed = $true }
    return $code
}

Say "Vela toolchain build"
Say "  repository : $root"
Say "  compiler   : $vmExe"
Say "  report     : $Report"
Say "  started    : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$vcv = Find-Vcvars
Say "  vcvars64   : $(if ($vcv) { $vcv } else { 'NOT FOUND' })"

# ---------------------------------------------------------------- 1. vm.exe
#
# Built from the seed C when it is missing or stale, otherwise left alone: a
# compiler is not rebuildable by itself until after this step, which is exactly
# why the seed C is checked in.
$needSeed = $Force -or -not (Test-Path -LiteralPath $seedExe)
if (-not $needSeed) {
    $needSeed = (Get-Item -LiteralPath $seedC).LastWriteTime -gt (Get-Item -LiteralPath $seedExe).LastWriteTime
}
if ($needSeed) {
    Step '1/6  bootstrap vm.exe from selfhost\build\vm.c' { Build-SeedCompiler } | Out-Null
} else {
    Head '1/6  vm.exe is current'
    Say '    (the seed C is older than the binary; -Force rebuilds anyway)'
}
if ($failed) { Say ''; Say 'build FAILED at step 1'; Write-Report; exit 1 }
if (-not (Test-Path -LiteralPath $vmExe)) { Say 'no vm.exe after step 1'; Write-Report; exit 1 }

# ---------------------------------------------------------------- 2. link
#
# The linked compiler is generated by a Vela program, run by the compiler.  The
# old file is kept aside first and restored if the new one does not lex: a
# linker that can silently eat the compiler's source is worse than no linker.
#
# The linker is *built* and then run, not interpreted.  The interpreter's `str`
# is a handle into a fixed table — that is what makes string equality cheap and
# pointers unnecessary — so a program that assembles a 338 KB text out of a
# thousand concatenations runs that table out ("vela: panic: string table
# full"), while the same program compiled has no table at all.  A build tool is
# compiled; that is the whole difference, and it is one `vm.exe build` away.
$vmVel = Join-Path $root 'selfhost\vm.vel'
$backup = Join-Path $root 'selfhost\vm.vel.before-link'
$linker = Join-Path $root 'tools\link_selfhost.exe'
$before = 0
if (Test-Path -LiteralPath $vmVel) {
    Copy-Item -LiteralPath $vmVel -Destination $backup -Force
    $before = [int]((& $vmExe count $vmVel 2>$null | Out-String).Trim() -replace '\D', '')
    Say ''
    Say '=== 2/6  link the parts (selfhost/vela.vel + the 8 parts)'
    Say "    tokens in the linked compiler before re-linking: $before"
}
Step '    build the linker' { & $vmExe build tools/link_selfhost.vel } | Out-Null
if (-not $failed) {
    Step '    run the linker' { & $linker } | Out-Null
}

# Every part on disk must be in the linker's list, and nothing in the list may be
# missing from disk.  This is not hypothetical: the plan for the LLVM backend adds
# `selfhost\parts\llvm_shim.vel` (the `extern c` declarations that let the compiler
# call its own code generator) and `emit_llvm.vel`, and a part that exists but is
# never listed is **silently absent** from `selfhost/vm.vel` — the declarations
# simply are not there, the build succeeds, and the failure surfaces later as an
# undefined function in a file the author never thinks to look at.  A check that
# costs a millisecond here is worth the afternoon it saves there.
if (-not $failed) {
    $partsDir = Join-Path $root 'selfhost\parts'
    $linkerSource = Join-Path $root 'tools\link_selfhost.vel'
    $onDisk = @(Get-ChildItem -LiteralPath $partsDir -Filter *.vel -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Name } | Sort-Object)
    $linkerText = Get-Content -LiteralPath $linkerSource -Raw
    $listed = @([regex]::Matches($linkerText, 'return\s+"([a-z0-9_]+\.vel)"') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    $notListed = @($onDisk | Where-Object { $listed -notcontains $_ })
    $notOnDisk = @($listed | Where-Object { $onDisk -notcontains $_ })
    if ($notListed.Count -eq 0 -and $notOnDisk.Count -eq 0) {
        Say "    parts: $($onDisk.Count) on disk, $($listed.Count) in the linker's list, the same set"
    } else {
        if ($notListed.Count -gt 0) {
            Say "    !! in selfhost\parts\ but not in tools\link_selfhost.vel's part_name(): $(($notListed) -join ', ')"
            Say '       a part that is not listed is not in selfhost/vm.vel at all'
        }
        if ($notOnDisk.Count -gt 0) {
            Say "    !! in the linker's list but not on disk: $(($notOnDisk) -join ', ')"
        }
        $failed = $true
    }
}
if (-not $failed -and $before -gt 0) {
    $afterRaw = (& $vmExe count $vmVel 2>&1 | Out-String).Trim()
    $after = [int]($afterRaw -replace '\D', '')
    $drift = $after - $before
    if ($drift -eq 0) {
        Say "    tokens: $after (unchanged: the parts are as they were)"
    } else {
        Say "    tokens: $before -> $after  (drift ${drift}: the parts changed, which is"
        Say '            the normal reason for this number to move)'
    }
    # The invariant that matters is not the count, it is that what came out is a
    # program: a linker that ate a part, spliced one twice, or cut the lexer in
    # the wrong place produces something the checker refuses, and that is caught
    # here — before the fixpoint spends a minute on it.
    Step '    the linked compiler must still be a valid program' { & $vmExe check $vmVel } | Out-Null
    if ($failed) {
        Say '    !! the linked compiler is not a valid program - restoring the previous'
        Say '       selfhost/vm.vel (edit the parts, then re-link)'
        Copy-Item -LiteralPath $backup -Destination $vmVel -Force
    }
}
if ($failed) { Say ''; Say 'build FAILED at step 2'; Write-Report; exit 1 }

# ---------------------------------------------------------------- 3. compiler
#
# `vm.exe build selfhost/vm.vel` is the compiler compiling the compiler, through
# its own build driver: emit the C, hand it to cl.exe, keep the binary.
#
# What comes out is then promoted, and all three promotions matter:
#
#   selfhost/vm.c   -> selfhost\build\vm.c            the seed: the one C file a
#                                                     machine with no Vela at all
#                                                     needs, kept as the compiler
#                                                     own output rather than a fossil
#   selfhost/vm.exe -> selfhost\build\vm.exe          *the compiler in the tree*
#   selfhost/vm.exe -> ...\vm_by_vela.exe             the same binary, under the
#                                                     name that says how it was made
#
# The middle line was missing until an OpenMP change to the emitter had no effect
# on anything: the tests and the benchmarks run selfhost\build\vm.exe, and without
# this it stayed the binary built from the *previous* source — one generation
# behind the tree, quietly.  A build that leaves the compiler behind the source is
# a build whose results are about the wrong program.
# The source the compiler is built from, named once and used by both this step and
# the fixpoint below, because the *spelling* of this path is embedded in the emitted
# C: every `vela_bounds_check(..., "selfhost/vm.vel", 14)` carries it, so two builds
# that spell it differently produce two different files that mean the same thing.
# Measured 2026-09-19: `selfhost/vm.vel` emits 754867 bytes, `selfhost\vm.vel` emits
# 758641 — same line count, same program, different literals.
$selfSource = 'selfhost/vm.vel'

# Where the driver puts the emitted C, by its own rule: `build_scratch`/`flat_name`
# in `parts/vm_main.vel` replace every `:`, `\` or `/` in the path **as given** with
# `_` and append `.c`, so this source lands in `selfhost_vm.vel.c`.
#
# This step used to look for `vm.vel.c`, which the driver stopped writing when the
# scratch key became the whole path (it used to be the base name, which collided for
# two same-named sources in different directories).  Measured: `vm.vel.c` was still on
# disk from 03:05:47 — written by the older driver — while the current one was writing
# `selfhost_vm.vel.c`, so the "fixpoint" compared generation 2 against an artefact of
# an earlier generation, and the seed it promoted was that same stale file.  A check
# that reads a file nothing writes is not a check.
$scratchDir = Join-Path $env:TEMP 'vela-build'
$scratchC = Join-Path $scratchDir (($selfSource -replace '[:\\/]', '_') + '.c')
Remove-Item -LiteralPath $scratchC -Force -ErrorAction SilentlyContinue

Step '3/6  build the compiler with the compiler' { & $vmExe build $selfSource } | Out-Null
if (-not $failed) {
    Head '      promote what the compiler wrote'
    $gen2C = Join-Path $root 'selfhost\vm.c'
    $gen2Exe = Join-Path $root 'selfhost\vm.exe'
    # `build` no longer writes the emitted C beside the source: the language's own
    # rule is that a build must not leave another language's file in the directory
    # holding the program, so the C and the object file go to the scratch.  The
    # executable stays beside the source, which is why this step's binary was already
    # correct while this file was not.
    if (Test-Path -LiteralPath $scratchC) {
        Copy-Item -LiteralPath $scratchC -Destination $gen2C -Force
        Say "    selfhost\vm.c <- vela-build\$($scratchC | Split-Path -Leaf) ($((Get-Item -LiteralPath $gen2C).Length) bytes)"
    } else {
        Say "    !! the driver wrote no $scratchC, so the fixpoint has nothing fresh to compare"
        Say "       the newest emitted files in $scratchDir are:"
        Get-ChildItem -LiteralPath $scratchDir -Filter *.c -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 5 |
            ForEach-Object { Say ("         " + $_.Name) }
        $failed = $true
    }
    $pairs = @(
        @($gen2C, $seedC),
        @($gen2Exe, (Join-Path $root 'selfhost\build\vm.exe')),
        @($gen2Exe, (Join-Path $root 'selfhost\build\vm_by_vela.exe'))
    )
    foreach ($pair in $pairs) {
        if (Test-Path -LiteralPath $pair[0]) {
            Copy-Item -LiteralPath $pair[0] -Destination $pair[1] -Force
            Say "    $($pair[1].Replace($root + '\', '')) <- $($pair[0].Replace($root + '\', '')) ($((Get-Item -LiteralPath $pair[1]).Length) bytes)"
        } else {
            Say "    !! missing $($pair[0])"
            $failed = $true
        }
    }
}
if ($failed) { Say ''; Say 'build FAILED at step 3'; Write-Report; exit 1 }

# ---------------------------------------------------------------- 4. lexer
Step '4/6  build the standalone lexer (selfhost/vela.vel)' { & $vmExe build selfhost/vela.vel } | Out-Null
if (Test-Path -LiteralPath (Join-Path $root 'selfhost\vela.exe')) {
    Copy-Item -LiteralPath (Join-Path $root 'selfhost\vela.exe') -Destination (Join-Path $root 'selfhost\build\vela.exe') -Force
}

# ---------------------------------------------------------------- 5. fixpoint
#
# The property stage 4 rests on, checked here as well as in the test suite: the
# compiler must write the C it was built from, and the compiler it built must
# write that same C again.  Nothing else in this script needs a test corpus, a
# second front end or a Python interpreter — one C file, a C compiler, and the
# compiler checking itself twice.
#
# (Until stage 4 this step ran the Python differential suites: stage 0 was the
# second opinion that certified the test goldens at the moment they were frozen.
# Stage 0 is deleted, so that step is gone — the goldens and this fixpoint are
# the whole of the evidence now, and `tests/golden/` records what they certify.)
$gen2Exe = Join-Path $root 'selfhost\vm.exe'
$gen2C   = Join-Path $root 'selfhost\vm.c'
$gen2Out = Join-Path $root 'selfhost\build\_fixpoint_gen2.c'

if (-not (Test-Path -LiteralPath $gen2Exe)) {
    Head '5/6  fixpoint skipped: no generation 2 binary'
    $failed = $true
} else {
    Step '5/6  fixpoint: generation 2 re-emits the compiler' {
        & cmd.exe /c "`"$gen2Exe`" emit-c $selfSource > `"$gen2Out`" 2> nul"
    } | Out-Null
    if (-not $failed) {
        Head '      does generation 2 write what generation 1 wrote?'
        $hSeed = (Get-FileHash -LiteralPath $seedC -Algorithm SHA256).Hash
        $hGen1 = (Get-FileHash -LiteralPath $gen2C  -Algorithm SHA256).Hash
        $hGen2 = (Get-FileHash -LiteralPath $gen2Out -Algorithm SHA256).Hash
        Say "    seed  (selfhost\build\vm.c) : $($hSeed.Substring(0,16))  $((Get-Item -LiteralPath $seedC).Length) bytes"
        Say "    gen 1 (selfhost\vm.c)       : $($hGen1.Substring(0,16))  $((Get-Item -LiteralPath $gen2C).Length) bytes"
        Say "    gen 2 (emitted by itself)   : $($hGen2.Substring(0,16))  $((Get-Item -LiteralPath $gen2Out).Length) bytes"
        if ($hSeed -ne $hGen1 -or $hGen1 -ne $hGen2) {
            Say '    !! the C moved between generations: this compiler does not reproduce itself'
            $failed = $true
        } else {
            Say '    byte-identical: the compiler reproduces itself'
        }
    }
}

# ---------------------------------------------------------------- 6. suite
$suiteExe = Join-Path $root 'tests\run_tests.exe'
if ($Suites -or $Record) {
    # `tests/run_tests.vel` finds the compiler through `VELA_SELF` and now *requires*
    # it.  The relative fallback it used to have does not survive cmd.exe's quoting:
    # the path is written with forward slashes, `shell()` adds an extra pair of
    # quotes for cmd's sake, cmd splits at the first `/`, and the compiler is never
    # started — which surfaced as thirty "could not be built" failures whose stated
    # reason was the driver's own harmless first line.  Measured: unset, 30 cases
    # failed; set, 5.  `tools\refreeze.ps1` sets it for the same reason.
    $env:VELA_SELF = $vmExe
    Step '6/6  build the test suite (tests/run_tests.vel)' { & $vmExe build tests/run_tests.vel } | Out-Null
    if (-not $failed) {
        if ($Record) { Step '      record the goldens' { & $suiteExe record } | Out-Null }
        if ($Suites) { Step '      run the suite'      { & $suiteExe } | Out-Null }
    }
} else {
    Head '6/6  test suite not requested (-Suites)'
}

# ---------------------------------------- 7/7  leave no detached helpers behind
# `cl.exe` starts `vctip.exe` (its telemetry client) and `mspdbsrv.exe` (a PDB
# server) as *detached* processes.  They outlive the build by minutes or hours and
# inherit the handles of whatever started them.  In this project that has twice
# killed an entire session: the harness tracks children in a Windows job object
# and refuses to complete while that job is not empty, so after one build every
# later command failed with
#
#     Error: subprocess-local: Windows Job runner exited with exit code 1
#     before proving its managed range empty
#
# with `vctip.exe` still alive in the process table.  `VSCMD_SKIP_SENDTELEMETRY=1`
# was tried first and **does not stop it** — measured: a build with the switch set
# still left a `vctip`, which this cleanup then reported.  So the reaping happens
# in the compiler driver itself, immediately after the C compiler exits
# (`reap_helpers` in `selfhost\parts\vm_main.vel`), because a script that drives
# the suite cannot: it is killed before it reaches its own cleanup.  This block is
# the second line of defence, for binaries built before that change and for any
# other tool in the chain that spawns a helper.
foreach ($name in @('vctip', 'mspdbsrv')) {
    $strays = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
    if ($strays.Count -gt 0) {
        $strays | Stop-Process -Force -ErrorAction SilentlyContinue
        Say "    cleaned up $($strays.Count) detached $name process(es)"
    }
}

Say ''
Say "finished: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
if ($failed) {
    Say 'RESULT: FAILED'
    Write-Report
    exit 1
}
Say 'RESULT: ok'
Write-Report
exit 0
