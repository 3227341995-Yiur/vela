# tools/build.ps1 — build and verify the whole self-hosted toolchain, with no
# Python anywhere in the loop.
#
# What has to exist for Vela to exist: a C compiler, one C file
# (selfhost/build/vm.c), and runtime/vela_runtime.h.  Not a Python program.
# Everything below the first step is done by the Vela compiler itself, including
# its own rebuild.
#
#     .\tools\build.ps1                                   # bootstrap + rebuild everything
#     .\tools\build.ps1 -Suites                           # ... and run tests/run_tests.vel
#     .\tools\build.ps1 -Record -Suites                    # ... re-freezing the goldens first
#     .\tools\build.ps1 -Force                            # rebuild vm.exe from the seed C
#
# Steps: 1 the LLVM objects (the compiler's own code generator) · 2 vm.exe from
# the seed C, linked against them · 3 link the parts (Vela linker) · 4 the
# compiler compiles itself, through its own LLVM back end · 5 the standalone lexer
# · 6 the fixpoint: three generations of the emitted C, asked for rather than
# picked up · 7 the Vela test suite (only with -Suites or -Record) · 8
# `LLVM-C.dll` and the runtime object beside every `vm.exe` the build wrote,
# because from step 2 on the compiler is linked against libLLVM and will not load
# without that DLL.  (The detached `vctip.exe` reaping happens at the very end of
# the script and in the compiler's own driver; `VSCMD_SKIP_SENDTELEMETRY=1` does not
# stop it.  Nothing here is killed by image name any more -- see the sweep at the
# top of this file for why a name-based kill made a gate's verdict depend on what a
# different checkout was doing.)
#
# **Which mode each step drives, and why it is written down here.**  `vm.exe
# build` is the LLVM path since the LLVM back end was promoted: it builds the
# module in process through libLLVM, writes the object itself, and calls only
# `lld-link`.  `vm.exe build-c` is the C back end's driver, and the C back end
# stays in the tree as the reference implementation.
#
#   step 3  `build`     the linker is an ordinary Vela program
#   step 4  `build`     the compiler, built the way a user's program is: LLVM in
#                       process, `lld-link` outside, no C compiler anywhere
#   step 5  `build`     the standalone lexer, likewise
#   step 6  `emit-c`    both generations are asked for the C, because no build
#                       mode leaves it as a side effect any more
#   step 7  `build`     the test suite is an ordinary Vela program
#
# So the chain is Rust-shaped: the seed C is stage 0 and the one thing a C
# compiler is asked for, and everything a person builds with `build` leaves a
# native executable and no other language's file.  `tools\selfhost-llvm.ps1` is
# the gate that holds this end of it, by doing step 4 with `PATH` stripped of every
# C compiler.
#
# Steps 1 and 2 are the plan's step 5 (`selfhost/LLVM_PLAN.md`): from here on
# `vm.exe` carries its own code generator, the way `rustc` carries LLVM.
# `runtime\vela_llvm_shim.c` is the scalar surface over `llvm-c` that
# `selfhost\parts\llvm_shim.vel` declares, and `runtime\vela_llvm_runtime.c`
# re-exports the runtime the *emitted* program links against.  Both are built
# here and nowhere else: `vm.exe` links the shim into itself through the
# extra-link argument (`vm.exe build-c FILE RTDIR EXTRA`, see `msvc_build` in
# parts/vm_main.vel, which only this script ever passes), and `vm.exe build`
# links the runtime object into every program it builds.
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

# Leftovers first, and **by ownership**, which this block did not do until
# 2026-09-24 and which cost another agent two false failures.
#
# Before `panic()` stopped calling `abort()`, every program the compiler *refused*
# was a real Windows crash (0xC0000409), which hands the process to Windows Error
# Reporting — and a crash handler that never exits holds the job object it was
# started in, so the shell that ran it cannot start anything afterwards.  Killing
# *those two* is best-effort and harmless: `WerFault`/`wermgr` are machine-global
# crash handlers, not per-checkout, so a sweep by name cannot take anything from
# anyone (it can only take a crash report they wanted).
#
# `vm`, `cl` and `link` are a different question, and the old code answered it
# wrong.  It killed every process with those names **on the machine**, by bare
# image name.  In a tree with more than one working directory -- a git worktree for
# a second agent is exactly that, and so is an IDE's language server -- that means:
#
#   * another agent's in-flight `cl` dies mid-compile, and the failure arrives as
#     `could not be built` with stderr truncated *before the driver prints its own
#     command line*, which is what a compiler killed by a signal looks like and not
#     what a compiler error looks like.  Measured: a `run-c` row failed that way
#     three times in three suite runs, on three different rows, while the other
#     agent's `build.ps1` was running, and every one of them passed alone;
#   * another agent's running `vm.exe` dies -- including one holding
#     `selfhost\build\vm.exe` open, which is precisely the hazard
#     `Clear-LockedTarget` below exists for, and one that may be in the middle of
#     emitting a fixpoint.
#
# So a gate's verdict depended on what a *different* agent happened to be doing,
# and a gate that lies is worse than no gate.  The rule now, in two halves, and the
# second half is the one this round added because the first was not enough:
#
#   1. **A path prefix is not ownership.**  `$p.Path.StartsWith($root)` looked right and
#      is wrong for the case this session creates: a `git worktree` of this repository
#      lives *inside* it (`.wt\enums`, and `.gitignore` carries the rule), so the other
#      agent's compiler is under `$root` and a prefix test calls it ours.  Measured by
#      `tools\_ownership-probe.ps1`, which starts a `vm.exe`-named process there and
#      requires the sweep to leave it alone.
#   2. **So the sweep names the paths it is for.**  A sweep is not for everything that
#      can hold a file; it is for the *output files this build is about to write*, and
#      those are three: `selfhost\build\vm.exe` (the compiler the tests and the gates
#      run), `selfhost\vm.exe` (what step 4 promotes), and `selfhost\build\vm_by_vela.exe`
#      (the same binary under the name that says how it was made).  Nothing else in this
#      repository is written over, so nothing else needs a process killed for.  That is
#      also why the names `cl` and `link` are gone from the sweep entirely: their own
#      paths are always under `C:\Program Files (x86)\Microsoft Visual Studio\...`, so
#      with a path test they could never match -- the sweep never killed a `cl` of ours,
#      and saying so is better than a loop that looks like it might.
#
# **And what cannot be identified is left alone.**  `Path` is empty for a process this
# user cannot open (measured: every `vm.exe` in the list, on this machine).  The old
# failure mode was killing too much, so the new default is to leave what cannot be named,
# and to *name* what was left.  A held output file that survives is not a lost cause: the
# link fails with LNK1104, and `Clear-LockedTarget` below is the half that tries to move
# the file aside first.  Identity decides what may be killed; the lock decides what may be
# renamed aside.
function Get-OutputPaths {
    # Resolved to full paths once, so the comparison below is between two absolute paths and
    # not between one absolute and one spelled with forward slashes.
    $out = @()
    foreach ($p in @('selfhost\build\vm.exe', 'selfhost\vm.exe', 'selfhost\build\vm_by_vela.exe')) {
        $out += [System.IO.Path]::GetFullPath((Join-Path $root $p))
    }
    return $out
}

function Test-IsOurBuildOutput([string] $path, [string[]] $outputs) {
    if (-not $path) { return $false }
    $full = ''
    try { $full = [System.IO.Path]::GetFullPath($path) } catch { return $false }
    foreach ($o in $outputs) {
        if ($full.Equals($o, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Get-OwnedProcess {
    param([string[]] $Outputs)
    $mine = @()
    foreach ($p in @(Get-Process -Name 'vm' -ErrorAction SilentlyContinue)) {
        $path = ''
        try { $path = $p.Path } catch { $path = '' }
        if (Test-IsOurBuildOutput $path $Outputs) {
            $mine += $p
        }
    }
    return $mine
}

function Get-UnsweptProcess {
    # Everything named `vm` that the sweep is NOT going to touch, so a reader can see what
    # was left -- including the worktree case and the cannot-be-identified case.
    param([string[]] $Outputs)
    $theirs = @()
    foreach ($p in @(Get-Process -Name 'vm', 'cl', 'link', 'vctip', 'mspdbsrv' -ErrorAction SilentlyContinue)) {
        $path = ''
        try { $path = $p.Path } catch { $path = '' }
        if (-not (Test-IsOurBuildOutput $path $Outputs)) {
            $theirs += [pscustomobject]@{ Name = $p.ProcessName; Id = $p.Id; Path = $path }
        }
    }
    return $theirs
}

foreach ($name in 'WerFault', 'wermgr') {
    try { Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue } catch { }
}
$ourOutputs = Get-OutputPaths
$foreign = Get-UnsweptProcess $ourOutputs
$owned = Get-OwnedProcess $ourOutputs
if ($owned.Count -gt 0) {
    $owned | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "  swept $($owned.Count) process(es) holding this build's own output paths"
}
if ($foreign.Count -gt 0) {
    Write-Host "  left alone: $($foreign.Count) process(es) that are not this build's output -- a sweep"
    Write-Host "  by image name would have killed them, and their agent's build with them:"
    foreach ($f in ($foreign | Select-Object -First 6)) {
        Write-Host ("      " + $f.Name + "  pid=" + $f.Id + "  " + $(if ($f.Path) { $f.Path } else { '(path not openable)' }))
    }
    if ($foreign.Count -gt 6) { Write-Host ("      ... and " + ($foreign.Count - 6) + " more") }
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
$buildDir = Join-Path $root 'selfhost\build'

# ------------------------------------------------------- the LLVM dependency
#
# `selfhost/LLVM_PLAN.md` step 5, in one place: where the LLVM package is, the
# two objects built from it, and the extra-link string that ties them into the
# compiler.  `tools\get-llvm.ps1` unpacks a portable LLVM *beside* this checkout
# (not inside it, which is why the default is one directory up), and the version
# is pinned here rather than searched for: an IR emitter is version-sensitive,
# and `LLVM_PLAN.md` asks for the version to be asserted rather than assumed.
function Find-LlvmDir {
    if ($env:VELA_LLVM) { return $env:VELA_LLVM }
    return (Join-Path (Split-Path -Parent $root) 'llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc')
}

$llvmDir  = Find-LlvmDir
$llvmLib  = Join-Path $llvmDir 'lib\LLVM-C.lib'
$llvmDll  = Join-Path $llvmDir 'bin\LLVM-C.dll'
$llvmLld  = Join-Path $llvmDir 'bin\lld-link.exe'
$llvmInc  = Join-Path $llvmDir 'include'
$shimC    = Join-Path $runtime 'vela_llvm_shim.c'
$rtC      = Join-Path $runtime 'vela_llvm_runtime.c'
$shimObj  = Join-Path $buildDir 'vela_llvm_shim.obj'
$rtObj    = Join-Path $buildDir 'vela_llvm_runtime.obj'

# The extra-link argument, handed to `vm.exe build-c` as its 5th word.  The 4th
# word is the runtime directory, and it has to be passed *because* PowerShell
# 5.1 drops an empty argument entirely: measured, `& pwsh -File t.ps1 a '' b`
# gives the child `a b` -- two arguments -- so an empty 4th word cannot reach
# the driver at all.  The string is quoted here, ready to be pasted into the C
# compiler's command line, which is exactly what `msvc_build` does with it.
$extraLink  = '"' + $shimObj + '" "' + $llvmLib + '"'
$runtimeArg = $runtime

# Every step below starts `vm.exe`, and the LLVM path (`build`/`build-llvm`) has to look for the
# *same* LLVM package this script found.  `find_lld` in parts/vm_main.vel walks
# up from the compiler's own directory and finds `<checkout>\..\llvm\...` on its
# own (measured: with `VELA_LLD` unset, the LLVM path links and the program runs),
# so this is not what makes it work -- it is what makes every step agree.  A
# build that finds LLVM one way and a compiler that finds it another way is two
# answers to one question, and the second one is always the one that is wrong.
$env:VELA_LLVM = $llvmDir
$env:VELA_LLD  = $llvmLld

# One command line handed to `cl`, in a batch file: cmd.exe strips the first and
# last quote of whatever it is given and every path here is quoted, so a command
# line with several quoted paths cannot survive `& cmd /c "..."`.
function Invoke-Cl([string[]] $lines) {
    $bat = Join-Path $buildDir '_llvm.ps1.bat'
    $body = @()
    $vcv4 = Find-Vcvars
    if ($vcv4) { $body += "call `"$vcv4`" >nul 2>&1" }
    $body += 'set VSCMD_SKIP_SENDTELEMETRY=1'
    $body += $lines
    [System.IO.File]::WriteAllText($bat, ($body -join "`r`n") + "`r`n")
    & cmd.exe /c $bat 2>&1 | Out-String | ForEach-Object { if ($_ -ne '') { Say "    $_" } }
    return $LASTEXITCODE
}

# The mode this step builds through, and the reason it is asked rather than
# assumed.
#
# **`build` — the LLVM path — is what builds the compiler here**, and that is the
# point of the promotion: the seed C is stage 0, stage 1 is built out of that
# seed by a C compiler at step 2, and everything a person builds from here on is
# pure.  Step 4 hands the compiler to its own LLVM back end, which writes the
# object itself and calls only `lld-link`; **no C compiler runs in this step at
# all**, and `tools\selfhost-llvm.ps1` is the gate that holds this end of the
# chain by doing the same thing with `PATH` stripped of every C compiler.
#
# What is *not* on this path any more is the emitted C.  `build-c` runs the C back
# end and writes `selfhost\vm.vel.c`, which is where the seeds in earlier
# revisions of this file came from; `build` writes an object and no C, and the
# fixpoint at step 6 therefore asks generation 2 for the C explicitly (`emit-c`)
# instead of picking up a side effect.  Step 4 leaves one file behind, the
# executable, and the fixpoint's C is generated where it is compared — which is
# strictly better than reading a file whose writer has moved.
#
# The extra-link argument belongs to `build-c` and is not passed on the LLVM path:
# it asks the emitted module whether the program calls the shim and links
# `vela_llvm_shim.obj` and `LLVM-C.lib` when it does (`needs_shim` in
# parts/vm_main.vel).
#
# HOW THE QUESTION IS PUT.  Not "does `build` exit 0" -- it did, for six
# generations, while `build` was the C path, and a probe that cannot tell those two
# compilers apart answers 'llvm' for both.  The discriminator is `build-c` itself,
# because **only a compiler that has the flip knows the name at all**: a mode it
# does not recognise is `unknown mode`, printed by `usage()`, exit non-zero.  So a
# clean `build-c` is the whole answer, and it costs one small build.
#
#   'llvm'       the compiler knows `build-c`, so its `build` is the LLVM path:
#                step 4 is one word
#   'build+arg'  it predates the flip but knows the extra link, so its `build` *is*
#                the C path and takes the 5th word.  Reached exactly once, on the
#                first build after the flip
#   'build+CL'   older still: the same inputs go through `CL`, which the C compiler
#                reads as extra arguments, so a driver that ignores a 5th word
#                still gets them
function Get-CompilerBuildMode([string] $vm) {
    $probe = Join-Path $buildDir '_extra_probe.vel'
    [System.IO.File]::WriteAllText($probe,
        "def main() -> None {`r`n    print(`"probe`")`r`n}`r`n")
    $bat = Join-Path $buildDir '_extra_probe.bat'
    $out = Join-Path $buildDir '_extra_probe.txt'

    $body = @('@echo off')
    $vcv5 = Find-Vcvars
    if ($vcv5) { $body += "call `"$vcv5`" >nul 2>&1" }
    $body += 'set VSCMD_SKIP_SENDTELEMETRY=1'
    $body += "`"$vm`" build-c `"$probe`" > `"$out`" 2>&1"
    [System.IO.File]::WriteAllText($bat, ($body -join "`r`n") + "`r`n")
    Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue
    & cmd.exe /c $bat | Out-Null
    $rc = $LASTEXITCODE
    if (Test-Path -LiteralPath $out) {
        Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue
    }
    if ($rc -eq 0) { return 'llvm' }

    # Not the flipped compiler.  Which of the two C-era shapes is it?  The 5th word
    # is the question, and it is put the way it always was: `build` prints the
    # command line it is about to run -- to stderr -- so a build whose 5th word is a
    # marker says exactly one thing: whether the marker reached that command line.
    # The build itself fails (the marker is not a file), and nothing is being built
    # here, only asked.
    #
    # It goes through a batch file with the output redirected into a file, and that
    # is not tidiness: with `2>&1 | Out-String` PowerShell wraps the compiler's
    # stderr in a `NativeCommandError` record that *echoes the offending source
    # line*, so the marker string came back out of the probe whether the driver had
    # passed it along or not.  Measured on the first run of this script: the probe
    # answered "yes" against a compiler that ignores the argument entirely.  cmd
    # does the redirecting inside the batch and PowerShell never sees that stream.
    $body = @('@echo off')
    if ($vcv5) { $body += "call `"$vcv5`" >nul 2>&1" }
    $body += 'set VSCMD_SKIP_SENDTELEMETRY=1'
    $body += "`"$vm`" build `"$probe`" `"$runtimeArg`" VELA_EXTRA_LINK_PROBE > `"$out`" 2>&1"
    [System.IO.File]::WriteAllText($bat, ($body -join "`r`n") + "`r`n")
    Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue
    & cmd.exe /c $bat | Out-Null
    $text = ''
    if (Test-Path -LiteralPath $out) {
        $text = [System.IO.File]::ReadAllText($out)
        Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue
    }
    if ($text.Contains('VELA_EXTRA_LINK_PROBE')) { return 'build+arg' }
    return 'build+CL'
}

function Build-SeedCompiler {
    # The floor of the bootstrapping: one C file, one C compiler, one header.
    # A batch file rather than a command line, because cmd.exe strips the first
    # and last quote of whatever it is handed and every path here is quoted.
    #
    # `$extraLink` is here from the first generation on, and it has to be: the
    # seed C this script promotes at step 4 *calls the shim* (the driver's
    # `build` mode does), so the next bootstrap has to link it.  On the
    # very first run the checked-in seed C does not call anything in it yet, and
    # the object is simply unused.
    if (-not (Test-Path -LiteralPath $seedC)) {
        Say "    no $seedC - nothing to bootstrap from"
        return 1
    }
    $code = Invoke-Cl @(
        "cd /d `"$(Split-Path -Parent $seedC)`"",
        "cl /nologo /std:c11 /O2 /I `"$runtime`" `"$seedC`" /Fe:`"$seedExe`" /Fo:`"$(Join-Path $root 'selfhost\build\vm_boot.obj')`" $extraLink"
    )
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

# ------------------------------------------------------- 1. the LLVM objects
#
# `selfhost/LLVM_PLAN.md` step 5.  Two objects, built once, here:
#
#   vela_llvm_shim.obj     the scalar surface over `llvm-c`.  `vm.exe` links it
#                          into *itself*, which is what makes the compiler carry
#                          its own code generator; it is passed through the
#                          extra-link argument at steps 2 and 4.
#   vela_llvm_runtime.obj  the runtime the *emitted program* calls.  `vm.exe
#                          build links it into every program it builds,
#                          and it is copied beside every vm.exe at step 8.
#
# Neither is ever built by a user's build: `vm.exe build x.vel` compiles C, and
# `vm.exe build x.vel` writes an object itself and calls a linker.  This
# script is the only thing here that compiles a C file other than the seed.
$llvmMissing = @()
foreach ($p in @($llvmLib, $llvmDll, $llvmLld, (Join-Path $llvmInc 'llvm-c\Core.h'), $shimC, $rtC)) {
    if (-not (Test-Path -LiteralPath $p)) { $llvmMissing += $p }
}
Head '1/8  the LLVM package the compiler is linked against'
Say "    llvm       : $llvmDir"
if ($llvmMissing.Count -gt 0) {
    foreach ($m in $llvmMissing) { Say "    !! missing: $m" }
    Say '    run tools\get-llvm.ps1, or set VELA_LLVM to an unpacked LLVM 23.x tree'
    Say ''
    Say 'build FAILED at step 1: the compiler cannot carry a code generator without it'
    Write-Report
    exit 1
}
Step '1/8  compile the shim and the runtime (development time only)' {
    Invoke-Cl @(
        "cl /nologo /std:c11 /O2 /I `"$llvmInc`" /I `"$runtime`" /c `"$shimC`" /Fo:`"$shimObj`"",
        "cl /nologo /std:c11 /O2 /I `"$llvmInc`" /I `"$runtime`" /c `"$rtC`" /Fo:`"$rtObj`""
    )
} | Out-Null
if ($failed) { Say ''; Say 'build FAILED at step 1'; Write-Report; exit 1 }
Say "    $shimObj  ($((Get-Item -LiteralPath $shimObj).Length) bytes)"
Say "    $rtObj  ($((Get-Item -LiteralPath $rtObj).Length) bytes)"
Say "    extra link : $extraLink"

# The DLL and the runtime object have to sit beside every vm.exe this build
# writes, and the first one is written by step 2 -- which then *runs* that
# vm.exe at step 3, and a vm.exe linked against LLVM-C.lib does not load at all
# without the DLL.  So it is placed immediately after each vm.exe is written.
#
# `LLVM-C.dll` is 74 MB and is deliberately not in the repository (see
# .gitignore): it is the same kind of dependency `rustc` has on its own libLLVM,
# and step 8 refuses to finish unless it is in place.
function Place-LlvmRuntime([string] $vmPath) {
    if (-not (Test-Path -LiteralPath $vmPath)) { return }
    $dir = Split-Path -Parent $vmPath
    Copy-Item -LiteralPath $llvmDll -Destination (Join-Path $dir 'LLVM-C.dll') -Force
    # `selfhost\build\vm.exe` lives in the directory the runtime object is
    # *built* in, so this copy would be a file onto itself.  Copy-Item answers
    # that with an IOException (measured: `WriteError ... CopyError`), so the
    # no-op case is skipped by comparing the resolved paths.
    $dst = Join-Path $dir 'vela_llvm_runtime.obj'
    if ([System.IO.Path]::GetFullPath($dst) -ne [System.IO.Path]::GetFullPath($rtObj)) {
        Copy-Item -LiteralPath $rtObj -Destination $dst -Force
    }
}

# A build cannot overwrite an executable that is running, and in this tree something is almost
# always running `selfhost\build\vm.exe`: the gates spawn it once per corpus file, and
# `GotoOracle` alone runs `vm.exe check` about 27,000 times per pass.  The link output is then
# held open and the build dies two steps before the promotion with
#
#     LINK : fatal error LNK1104: cannot open file "...\selfhost\build\vm.exe"
#
# which reads as a broken build rather than as a busy file.  Measured 2026-09-24: the compiler
# agent renamed the held binary aside and a new `vm.exe` existed again within the second, with
# 24 stray `vm` processes in the list -- so this is a spawn rate, not one stray process.
#
# Renaming the held file aside costs nothing: the processes already running it keep the old
# inode, and the link writes a fresh path.  It used to be done by hand, which cost one retry
# per build; now the build does it, and says so.  (`/selfhost/build/*.held` is ignored, which
# matters because the file it leaves behind is 867 KB of binary and cannot even be deleted
# while a process still holds it.)
#
# **When the rename itself fails, the build does not.**  That was wrong until 2026-09-24 and
# the measurement came from `tools\_ownership-probe.ps1`, which takes an exclusive handle and
# calls this function: the rename is refused, and the old code set `$failed` -- so a *busy*
# file was reported as a failed build before the linker was asked anything, which is the same
# mistake this function was written to fix, one level down.  The file is then held the way
# the linker will report it, and the linker is what reports it.
#
# **And the catch does not try to explain why.**  Two versions of a "can this be renamed?"
# helper were written here and both were wrong in the direction that flatters -- each printed
# "the file can be renamed" for a file it had just failed to rename -- so the question was
# measured instead.  `tools\_hold-probe.ps1` prints the table with the disagreement count, and
# the answer is that **no open-mode test reports a rename**: a rename needs the *holder* to
# share `FileShare::Delete`, and this function's own probe handle is part of the situation --
# a `FileShare::None` handle makes the file unrenamable by itself, a `Delete`-only holder
# lets the rename through while refusing a write-open, and a `ReadWrite|Delete` holder lets
# it through while refusing a read-write request.  So the sentence is the exception's own,
# and the verdict belongs to the linker: `LNK1104: cannot open file`, with the path in it.
function Clear-LockedTarget([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    $held = $false
    try {
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open,
                                     [System.IO.FileAccess]::ReadWrite,
                                     [System.IO.FileShare]::None)
        $fs.Close()
    } catch {
        $held = $true
    }
    if (-not $held) { return }
    $aside = "$path.held"
    Remove-Item -LiteralPath $aside -Force -ErrorAction SilentlyContinue
    try {
        Move-Item -LiteralPath $path -Destination $aside -Force -ErrorAction Stop
        Say ("    {0} is held open by a running process; moved it aside to {1} so the link can write a fresh one" -f (Split-Path $path -Leaf), (Split-Path $aside -Leaf))
    } catch {
        Say ("    !! {0} is held open and could not be moved aside: {1}" -f $path, $_.Exception.Message)
        Say '       (a holder that does not share delete access makes a file unrenamable by'
        Say '        anybody, so the linker is what reports this one -- a LNK1104 naming it)'
    }
}

# ---------------------------------------------------------------- 2. vm.exe
#
# Built from the seed C when it is missing or stale, otherwise left alone: a
# compiler is not rebuildable by itself until after this step, which is exactly
# why the seed C is checked in.
Clear-LockedTarget $seedExe
$needSeed = $Force -or -not (Test-Path -LiteralPath $seedExe)
if (-not $needSeed) {
    $needSeed = (Get-Item -LiteralPath $seedC).LastWriteTime -gt (Get-Item -LiteralPath $seedExe).LastWriteTime
}
if ($needSeed) {
    Step '2/8  bootstrap vm.exe from selfhost\build\vm.c' { Build-SeedCompiler } | Out-Null
} else {
    Head '2/8  vm.exe is current'
    Say '    (the seed C is older than the binary; -Force rebuilds anyway)'
}
if ($failed) { Say ''; Say 'build FAILED at step 2'; Write-Report; exit 1 }
if (-not (Test-Path -LiteralPath $vmExe)) { Say 'no vm.exe after step 2'; Write-Report; exit 1 }
Place-LlvmRuntime (Join-Path $root $vmExe)
Say "    LLVM-C.dll and vela_llvm_runtime.obj are beside $vmExe"

# ---------------------------------------------------------------- 3. link
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
    Say '=== 3/8  link the parts (selfhost/vela.vel + the 9 parts)'
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
if ($failed) { Say ''; Say 'build FAILED at step 3'; Write-Report; exit 1 }

# ---------------------------------------------------------------- 4. compiler
#
# `vm.exe build selfhost/vm.vel` is the compiler compiling the compiler, through
# its own LLVM back end: the module is built in process through libLLVM, the object
# is written by the compiler, and `lld-link` links it.  **No C compiler runs in
# this step.**  What comes out is then promoted, and all three promotions matter:
#
#   (nothing)       -> selfhost\vm.c                  no longer written here; see
#                                                     step 6, where the fixpoint
#                                                     is asked for the C instead
#   selfhost/vm.exe -> selfhost\build\vm.exe          *the compiler in the tree*
#   selfhost/vm.exe -> ...\vm_by_vela.exe             the same binary, under the
#                                                     name that says how it was made
#
# The middle line was missing until an OpenMP change to the emitter had no effect
# on anything: the tests and the benchmarks run selfhost\build\vm.exe, and without
# this it stayed the binary built from the *previous* source — one generation
# behind the tree, quietly.  A build that leaves the compiler behind the source is
# a build whose results are about the wrong program.
#
# The promoted compiler is an LLVM-built one, and that is the change this whole
# workstream was for: the cl-built binary (stage 1, out of the seed C at step 2)
# stops being the compiler the tests, the benchmarks and the IDE measure.  The
# seed C is stage 0; `tools\selfhost-llvm.ps1` is the gate that holds the other end
# of the chain, by building the same source through `build` with no C compiler on
# `PATH` at all and requiring the result to work.
#
# The source the compiler is built from, named once and used by both this step and
# the fixpoint below, because the *spelling* of this path is embedded in the emitted
# C: every `vela_bounds_check(..., "selfhost/vm.vel", 14)` carries it, so two builds
# that spell it differently produce two different files that mean the same thing.
# Measured 2026-09-19: `selfhost/vm.vel` emits 754867 bytes, `selfhost\vm.vel` emits
# 758641 — same line count, same program, different literals.
$selfSource = 'selfhost/vm.vel'

# Where a driver puts the intermediates, for the one step that needs to know:
# `build_scratch`/`flat_name` in `parts/vm_main.vel` replace every `:`, `\` or `/`
# in the path **as given** with `_`, so this source's scratch directory is
# `%TEMP%\vela-build\selfhost_vm.vel`, and the `.ll` and the `.obj` of a `build`
# land in it.
#
# Nothing here reads a file out of it any more, and that is deliberate.  This
# block used to name `selfhost_vm.vel.c` inside it, which step 4 promoted into
# `selfhost\vm.c` -- and it was written because a stale `vm.vel.c` left over from
# an older driver made the "fixpoint" compare generation 2 against an artefact of
# an earlier generation.  `build` writes no C, so the *whole* shape of that hazard
# is gone: every C this step compares is generated by the compiler being measured,
# inside this run (step 6).  `$scratchDir` survives only as a sentence in that
# step's failure message, so the reader of a red fixpoint is told where to look for
# what the driver did write.
$scratchDir = Join-Path $env:TEMP 'vela-build'

# The extra-link inputs, handed to a compiler whose `build` is still the C path.
#
# On the normal path step 4 passes nothing extra, because `build` is the LLVM path
# and finds what the program needs from the module it emitted.  These two branches
# exist for the *one* generation that straddles the flip: the compiler that
# performs the first self-build after `build` became the LLVM path is the previous
# generation, whose `build` is still the C one, and whose C for the compiler calls
# `vshim_*` without linking anything:
#
#     selfhost_vm.vel.obj : error LNK2019: unresolved external symbol vshim_open
#                           referenced in function vl_ll_open_module
#
# so it has to be told.  `build+arg` says it as the 5th word; `build+CL` says it
# through `CL`, which the C compiler reads as extra arguments, for a driver older
# than the 5th word.  Measured for the `CL` shape: with the variable set to a path
# that does not exist the build fails with
#
#     LINK : fatal error LNK1181: cannot open input file 'Z:\...obj'
#
# which is cl reading it through the old driver's batch file.  One generation
# later the probe answers 'llvm' and neither branch is taken again.
$buildMode = Get-CompilerBuildMode $vmExe
if ($buildMode -eq 'llvm') {
    Say '    this compiler`s `build` is the LLVM path: step 4 starts no C compiler'
    Clear-LockedTarget (Join-Path $root 'selfhost\vm.exe')
    Step '4/8  build the compiler with the compiler (LLVM in process; lld-link, no C compiler)' { & $vmExe build $selfSource } | Out-Null
} elseif ($buildMode -eq 'build+arg') {
    Say '    this compiler predates the flip: its `build` *is* the C path, and it'
    Say '    takes the extra-link argument, so that is what this step runs'
    Clear-LockedTarget (Join-Path $root 'selfhost\vm.exe')
    Step '4/8  build the compiler with the compiler (this generation`s `build` = the C back end)' { & $vmExe build $selfSource $runtimeArg $extraLink } | Out-Null
} else {
    Say '    this compiler predates both the flip and the extra-link argument: the same'
    Say '    inputs go through `CL`, which the C compiler reads as extra arguments'
    $env:CL = $extraLink
    Clear-LockedTarget (Join-Path $root 'selfhost\vm.exe')
    Step '4/8  build the compiler with the compiler (this generation`s `build` = the C back end)' { & $vmExe build $selfSource $runtimeArg } | Out-Null
    Remove-Item Env:\CL -ErrorAction SilentlyContinue
}
if (-not $failed) {
    Head '      promote what the compiler built'
    $gen2Exe = Join-Path $root 'selfhost\vm.exe'
    # Nothing is copied into `selfhost\vm.c` here any more, and the reason is
    # worth one sentence: step 4 is a `build`, and no build mode leaves the
    # emitted C anywhere -- the language's own rule is that a build must not leave
    # another language's file in the directory holding the program, so the object
    # and the IR go to the scratch and the executable is the only product.  The C
    # the fixpoint needs is asked for at step 6, by the compiler that is being
    # measured, and `selfhost\vm.c` becomes a *comparison* of that against the
    # seed rather than a promoted copy of it.  This block used to copy
    # `%TEMP%\vela-build\selfhost_vm.vel.c` here, and the failure it was written
    # for -- "the driver wrote no ...c, so the fixpoint has nothing fresh to
    # compare" -- is now the shape of the whole step, so it is stated at step 6
    # instead of being a branch that can silently not be taken.
    $pairs = @(
        @($gen2Exe, (Join-Path $root 'selfhost\build\vm.exe')),
        @($gen2Exe, (Join-Path $root 'selfhost\build\vm_by_vela.exe'))
    )
    foreach ($pair in $pairs) {
        if (Test-Path -LiteralPath $pair[0]) {
            # The copy can fail while the destination is held open -- a running `vm.exe`
            # is exactly that -- and `Copy-Item`'s failure is NON-TERMINATING, so this
            # loop used to leave `$failed` unset and then print the DESTINATION's size,
            # which is the stale file's size.  Measured 2026-09-24: a run wrote
            # `build\vm_by_vela.exe` (868352 bytes, 02:21:47) and did not write
            # `build\vm.exe` (still 867840 bytes, 02:07:04), printed a plausible line for
            # both, and ended `RESULT: ok`.  That is why a tree one generation behind the
            # source stayed invisible for half an hour while every gate measured the old
            # compiler.  The size printed is the SOURCE's, so a mismatch with the
            # destination would be visible even if this catch were ever removed.
            try {
                Copy-Item -LiteralPath $pair[0] -Destination $pair[1] -Force -ErrorAction Stop
                Say "    $($pair[1].Replace($root + '\', '')) <- $($pair[0].Replace($root + '\', '')) ($((Get-Item -LiteralPath $pair[0]).Length) bytes)"
            } catch {
                Say "    !! could not write $($pair[1].Replace($root + '\', '')): $($_.Exception.Message)"
                Say "       (a process may be holding it open -- a running vm.exe does exactly that)"
                $failed = $true
            }
        } else {
            Say "    !! missing $($pair[0])"
            $failed = $true
        }
    }
    # the promoted binaries are `vm.exe`s too, and a vm.exe without LLVM-C.dll
    # beside it does not load at all - so it goes beside each one as it lands
    foreach ($p in @((Join-Path $root 'selfhost\vm.exe'),
                     (Join-Path $root 'selfhost\build\vm.exe'),
                     (Join-Path $root 'selfhost\build\vm_by_vela.exe'))) {
        Place-LlvmRuntime $p
    }
    Say '    LLVM-C.dll and vela_llvm_runtime.obj are beside every vm.exe above'
}
if ($failed) { Say ''; Say 'build FAILED at step 4'; Write-Report; exit 1 }

# ---------------------------------------------------------------- 5. lexer
Step '5/8  build the standalone lexer (selfhost/vela.vel)' { & $vmExe build selfhost/vela.vel } | Out-Null
if (Test-Path -LiteralPath (Join-Path $root 'selfhost\vela.exe')) {
    Copy-Item -LiteralPath (Join-Path $root 'selfhost\vela.exe') -Destination (Join-Path $root 'selfhost\build\vela.exe') -Force
}

# ---------------------------------------------------------------- 6. fixpoint
#
# The property the whole chain rests on, checked here as well as in the test
# suite: **the compiler must write the C it was built from**, and the compiler it
# built must write that same C again.  Nothing else in this script needs a test
# corpus, a second front end or a Python interpreter — one C file, a C compiler
# for stage 1, and the compiler checking itself.
#
# WHAT CHANGED WITH THE FLIP, and why this step is longer than it was.  Every
# generation here used to arrive with its C as a side effect: `build` wrote
# `%TEMP%\vela-build\selfhost_vm.vel.c` because it *was* the C driver, and step 4
# promoted that file into `selfhost\vm.c`.  `build` is the LLVM path now and
# writes no C, so there is no side effect to pick up, and a check that reads a
# file nothing writes is not a check — this file has already paid for that lesson
# once (see `$scratchC`'s history: a "fixpoint" that compared generation 2 against
# an artefact of an earlier generation).  So the C is **asked for**, by the
# compiler under test, at the moment it is compared, and nothing is promoted:
#
#   selfhost\build\vm.c   the seed.  Tracked, and the floor of the bootstrap: it
#                         is what a machine with no Vela at all compiles, and it
#                         is the C that generation 1 emitted when it was last
#                         regenerated.  Nothing in this build writes it any more
#                         -- step 4 no longer has a C to promote -- so it stays
#                         the record of the moment it was frozen, which is what a
#                         seed is
#   selfhost\vm.c         the C the *promoted* compiler emits now, written here
#                         and diffed against the seed.  It is a comparison, not a
#                         promotion: if it differs, the seed is stale and this
#                         step says so instead of quietly overwriting it
#   _fixpoint_gen2.c      the C generation 2 emits, which must equal the other
#                         two: the compiler reproduces itself
#
# The seed is the one legitimate exception.  A change to the emitter that does not
# change what it emits for the compiler's own source leaves it exactly as it was;
# a change that does leaves `selfhost\vm.c` different, and the honest response is
# to look at the difference and refreeze the seed deliberately (copy
# `selfhost\vm.c` over `selfhost\build\vm.c`, which is what "the compiler's own
# output rather than a fossil" means) rather than to have the build do it on the
# way past.
#
# (Until stage 4 this step ran the Python differential suites: stage 0 was the
# second opinion that certified the test goldens at the moment they were frozen.
# Stage 0 is deleted, so that step is gone — the goldens and this fixpoint are
# the whole of the evidence now, and `tests/golden/` records what they certify.)
$gen2Exe = Join-Path $root 'selfhost\vm.exe'
$gen1C   = Join-Path $root 'selfhost\vm.c'
$gen2Out = Join-Path $root 'selfhost\build\_fixpoint_gen2.c'
Remove-Item -LiteralPath $gen1C, $gen2Out -Force -ErrorAction SilentlyContinue

if (-not (Test-Path -LiteralPath $gen2Exe)) {
    Head '6/8  fixpoint skipped: no generation 2 binary'
    $failed = $true
} else {
    Step '6/8  fixpoint: generation 2 re-emits the compiler, both as C' {
        & cmd.exe /c "`"$gen2Exe`" emit-c $selfSource > `"$gen2Out`" 2> nul"
    } | Out-Null
    if (-not $failed) {
        Step '      and the promoted compiler emits it too' {
            & cmd.exe /c "`"$(Join-Path $root 'selfhost\build\vm.exe')`" emit-c $selfSource > `"$gen1C`" 2> nul"
        } | Out-Null
    }
    if (-not $failed) {
        Head '      does generation 2 write what the seed holds?'
        if (-not (Test-Path -LiteralPath $gen1C) -or (Get-Item -LiteralPath $gen1C).Length -eq 0) {
            Say "    !! the promoted compiler emitted no C, so the fixpoint has nothing"
            Say "       fresh to compare.  The scratch with the last emitted files is"
            Say "       $scratchDir; a fresh file there is NOT evidence that this step ran."
            $failed = $true
        } elseif (-not (Test-Path -LiteralPath $gen2Out) -or (Get-Item -LiteralPath $gen2Out).Length -eq 0) {
            Say "    !! generation 2 emitted no C into $gen2Out"
            $failed = $true
        } else {
            $hSeed = (Get-FileHash -LiteralPath $seedC -Algorithm SHA256).Hash
            $hGen1 = (Get-FileHash -LiteralPath $gen1C  -Algorithm SHA256).Hash
            $hGen2 = (Get-FileHash -LiteralPath $gen2Out -Algorithm SHA256).Hash
            Say "    seed  (selfhost\build\vm.c) : $($hSeed.Substring(0,16))  $((Get-Item -LiteralPath $seedC).Length) bytes"
            Say "    gen 1 (selfhost\vm.c)       : $($hGen1.Substring(0,16))  $((Get-Item -LiteralPath $gen1C).Length) bytes"
            Say "    gen 2 (emitted by itself)   : $($hGen2.Substring(0,16))  $((Get-Item -LiteralPath $gen2Out).Length) bytes"
            if ($hGen1 -ne $hGen2) {
                Say '    !! generation 2 does not write the C the promoted compiler wrote:'
                Say '       the compiler does not reproduce itself'
                $failed = $true
            } elseif ($hSeed -ne $hGen1) {
                Say '    !! generation 2 reproduces itself, but neither writes the checked-in seed:'
                Say '       the emitter changed, so selfhost\build\vm.c is stale.  Read the diff,'
                Say '       then refreeze it deliberately:'
                Say "           copy selfhost\vm.c selfhost\build\vm.c"
                $failed = $true
            } else {
                Say '    byte-identical: the compiler reproduces itself, and it is the seed'
            }
        }
    }
}

# ---------------------------------------------------------------- 7. suite
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
    Step '7/8  build the test suite (tests/run_tests.vel)' { & $vmExe build tests/run_tests.vel } | Out-Null
    if (-not $failed) {
        if ($Record) { Step '      record the goldens' { & $suiteExe record } | Out-Null }
        if ($Suites) { Step '      run the suite'      { & $suiteExe } | Out-Null }
    }
} else {
    Head '7/8  test suite not requested (-Suites)'
}

# ------------------------------- 8/8  the code generator beside every vm.exe
#
# The compiler now carries libLLVM inside it, and `LLVM-C.dll` is a *load-time*
# dependency of the binary: without it beside the executable, Windows refuses to
# start `vm.exe` at all -- before `main`, with an error that says nothing about
# LLVM.  So this is not a tidiness check; it is the difference between a
# compiler that runs and one that does not.  Every `vm.exe` this build wrote is
# listed here explicitly (they are `selfhost\build\vm.exe`, the promoted
# `selfhost\vm.exe`, and `vm_by_vela.exe`), and a missing file is named.
#
# `vela_llvm_runtime.obj` is what the LLVM path links into every program
# it builds, and it is looked for beside the compiler (`find_llvm_rt` in
# parts/vm_main.vel), so it sits with the DLL.
Head '8/8  the code generator is beside every vm.exe'
$vmBins = @(
    (Join-Path $root 'selfhost\build\vm.exe'),
    (Join-Path $root 'selfhost\vm.exe'),
    (Join-Path $root 'selfhost\build\vm_by_vela.exe')
)
foreach ($b in $vmBins) {
    if (-not (Test-Path -LiteralPath $b)) {
        Say "    !! no $b"
        $failed = $true
        continue
    }
    $dir = Split-Path -Parent $b
    foreach ($need in @('LLVM-C.dll', 'vela_llvm_runtime.obj')) {
        $f = Join-Path $dir $need
        if (Test-Path -LiteralPath $f) {
            Say "    $($b.Replace($root + '\', '')) + $need ($((Get-Item -LiteralPath $f).Length) bytes)"
        } else {
            Say "    !! $b needs $f, and it is not there"
            $failed = $true
        }
    }
}

# ---------------------------------------- 9/9  leave no detached helpers behind
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
#
# **And it is machine-global when the helpers are, which only `vctip` now is.**
# `mspdbsrv.exe` is a *shared* PDB server -- one instance serves every `cl.exe`
# that asks for it -- so a sweep that kills it by image name kills the PDB server
# another checkout's compile is holding open, and their build then dies with a
# message that names neither this script nor a PDB server.  Measured 2026-09-24: a
# suite row built by `build-c` failed with `could not be built` and stderr truncated
# before the driver printed its own command line, three times on three different
# rows, each time while another checkout's build was running; every row passed
# alone.  This project's own builds never ask for a PDB server in the first place
# (`tools\_cl-helper-spawn.ps1` measures it: the driver's `/O2 /std:c11 /utf-8`
# command line leaves `vctip` and no `mspdbsrv`), so `mspdbsrv` is not swept here by
# anybody -- and the compiler driver's own `reap_helpers` is where that was fixed,
# with the same measurement behind it.
foreach ($name in @('vctip')) {
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
