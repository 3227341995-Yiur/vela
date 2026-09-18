<#
.SYNOPSIS
    Build and run the benchmark harness, with the one environment variable it needs.

.DESCRIPTION
    `bench/run_bench.vel` is a Vela program that measures Vela against its C++
    twins.  Two things have to happen before it can print a number, and both are
    easy to get wrong by hand:

      1. it has to be **built by the compiler it is measuring** — so the numbers
         describe the current back end, not one from an hour ago;
      2. `VELA_SELF` has to be an **absolute path**, because the harness spawns
         `vm.exe build` through `cmd.exe`, which splits a relative forward-slash
         path at the first `/` and answers `'selfhost' is not recognized`.  The
         failure then reads as "the Vela side did not build", which looks like a
         compiler defect and is not one.  Measured on 2026-09-19: `run_bench.exe 7`
         died that way while the same build typed by hand succeeded.

    The C++ side is built by the harness itself through `vcvars64.bat`, so a
    machine without VS Build Tools fails loudly there rather than silently here.

.PARAMETER Reps
    Repetitions per variant.  The harness reports median, best and worst.

.PARAMETER SkipBuild
    Do not rebuild `bench/run_bench.exe` first.  Only for re-running the same
    binary deliberately; the harness's own numbers then describe whatever back end
    built it, which is not necessarily the current one.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\bench.ps1
    powershell -ExecutionPolicy Bypass -File tools\bench.ps1 -Reps 3

.NOTES
    Numbers from this harness are only worth quoting from an **otherwise idle**
    machine: a 2 GB LLVM unpack on the same disk made two suite cases fail to build
    in the same session, and a benchmark is more sensitive than a correctness run.
#>
[CmdletBinding()]
param(
    [int] $Reps = 7,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $repo 'selfhost\build\vm.exe'
$harness = Join-Path $repo 'bench\run_bench.exe'

if (-not (Test-Path -LiteralPath $vm)) { throw "no compiler at $vm — run tools\build.ps1 first" }

# The suite's rule, applied to the harness: an absolute path, or the spawn fails
# with a message about the wrong thing.
$env:VELA_SELF = $vm

Push-Location $repo
try {
    if (-not $SkipBuild) {
        Write-Host "building the harness with the current compiler"
        & $vm build 'bench\run_bench.vel'
        if ($LASTEXITCODE -ne 0) { throw "could not build bench\run_bench.vel (exit $LASTEXITCODE)" }
    }
    if (-not (Test-Path -LiteralPath $harness)) { throw "no harness at $harness" }

    Write-Host "running $Reps repetition(s) per variant"
    Write-Host ''
    & $harness $Reps
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) { throw "the harness exited $code — a variant disagreed on its answer, or a build failed" }
Write-Host ''
Write-Host 'The numbers above are the ones to copy into bench\RESULTS.md, with the machine line.'
