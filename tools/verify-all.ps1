<#
.SYNOPSIS
    Run every check this project has, in order, and report one verdict.

.DESCRIPTION
    `ROADMAP.md` asks for "one command that runs everything — build, suite,
    benchmarks, differential tests and a report — so that 'is it green' is never a
    matter of opinion".  This is that command.

    Each step runs in its own PowerShell process, writes its full output to a log
    under `.work\verify-all\`, and reports three things: the exit code, the
    wall-clock time, and the step's own verdict line.  Nothing is summarised away —
    when a step fails, the failing step's log is named and its last lines are
    printed.

    The steps, in the order that makes a failure cheap to diagnose:

      build     tools\build.ps1        bootstrap, link, self-compile, promote,
                                       and the byte-identical fixpoint
      smoke     tools\smoke.ps1        is the tree alive: build, run, build from
                                       outside the repository, the user's directory
                                       staying clean, compiled == interpreted
      llvm-inproc tools\llvm-inproc.ps1
                                       the north star: build-llvm runs with no C
                                       compiler on PATH at all, and the only program
                                       it starts is the linker.  Witnessed by a
                                       sabotage PATH whose trap is proved to work
                                       first, and by the recorded link command
      shim-decls tools\gen-shim-decls.ps1 -Check
                                       the compiler's code generator is reached
                                       through generated `extern c` declarations;
                                       this is the check that they are still what
                                       the C header says
      suite     tools\refreeze.ps1     record the locks, keep the written
                                       expectations, run all the cases
      bench     tools\bench.ps1        7 repetitions per variant, every answer
                                       checked (slow, and only meaningful on an
                                       idle machine, so it is opt-in)
      plugin    idea-plugin\build-offline.ps1
                                       compile the plugin and run its verifier
      mutation  idea-plugin\mutation-test.ps1
                                       break the plugin on purpose, one defect at a
                                       time, and require the verifier to catch each
                                       (opt-in: one plugin build per mutant)

.PARAMETER WithBench
    Also run the benchmarks.  Off by default: they take minutes, and a benchmark
    taken while anything else is running is a number nobody should quote.

.PARAMETER WithPlugin
    Also build and verify the IDEA plugin (needs the installed IDE's kotlinc; it
    does not need the network).

.PARAMETER WithMutation
    Also run the plugin's mutation test.  Asks for a mutant and fails loudly if the
    script does not exist, because "the verifier cannot fail" is the one claim this
    project has never been able to make and would like to.

.PARAMETER SkipSuite
    Skip the test suite.  For iterating on a build problem, when the suite's ten
    minutes are known to be wasted.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\verify-all.ps1
    powershell -ExecutionPolicy Bypass -File tools\verify-all.ps1 -WithBench -WithPlugin

.NOTES
    Exit code 0 means every step that ran reported success.  A skipped step is
    reported as skipped, never as passed — the difference matters, and this project
    has already paid once for a check that read a file nothing wrote.
#>
[CmdletBinding()]
param(
    [switch] $WithBench,
    [switch] $WithPlugin,
    [switch] $WithMutation,
    [switch] $SkipSuite
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repo '.work\verify-all'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$results = New-Object System.Collections.ArrayList

# Native output goes to files, not through a pipeline: on this host a native
# command's stderr becomes a terminating error under 'Stop', and every one of these
# scripts drives `cl.exe`, `kotlinc` or `java`, all of which warn on stderr.
function Invoke-Step {
    param(
        [string] $Name,
        [string] $Script,
        [string[]] $Arguments = @(),
        [string] $SuccessPattern
    )

    Write-Host ''
    Write-Host ("=== {0}" -f $Name) -ForegroundColor Cyan
    Write-Host ("    {0} {1}" -f $Script, ($Arguments -join ' '))

    $out = Join-Path $logDir "$Name.out"
    $err = Join-Path $logDir "$Name.err"
    $started = Get-Date
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Push-Location $repo
        & powershell.exe -ExecutionPolicy Bypass -File (Join-Path $repo $Script) @Arguments 1> $out 2> $err
        $code = $LASTEXITCODE
        Pop-Location
    } finally {
        $ErrorActionPreference = $prev
    }
    $seconds = [int]((Get-Date) - $started).TotalSeconds

    $text = ''
    if (Test-Path -LiteralPath $out) { $text += Get-Content -LiteralPath $out -Raw }
    if (Test-Path -LiteralPath $err) { $text += "`n" + (Get-Content -LiteralPath $err -Raw) }

    $verdictLine = ''
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match 'RESULT:') { $verdictLine = $line.Trim() }
    }
    $ok = ($code -eq 0)
    if ($ok -and $SuccessPattern -and ($text -notmatch $SuccessPattern)) {
        $ok = $false
        $verdictLine = "exit 0 but no '$SuccessPattern' in the output"
    }

    $result = [pscustomobject]@{
        Step    = $Name
        Ok      = $ok
        Code    = $code
        Seconds = $seconds
        Verdict = $verdictLine
        Out     = $out
        Err     = $err
    }
    [void]$results.Add($result)

    if ($result.Ok) {
        Write-Host ("    ok   {0,5}s  {1}" -f $seconds, $verdictLine) -ForegroundColor Green
    } else {
        Write-Host ("    FAIL {0,5}s  exit {1}  {2}" -f $seconds, $code, $verdictLine) -ForegroundColor Red
        Write-Host "    full output: $out" -ForegroundColor Red
        Write-Host '    last lines:' -ForegroundColor Red
        ($text -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 12) |
            ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    }
}

Write-Host ("Vela verify-all  --  {0}" -f $repo)
Write-Host ("  {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ("  logs: {0}" -f $logDir)

Invoke-Step -Name 'build'  -Script 'tools\build.ps1'    -SuccessPattern 'RESULT: ok'
Invoke-Step -Name 'smoke'  -Script 'tools\smoke.ps1'    -SuccessPattern 'RESULT: ok'

# The north star, as a check rather than as a memory: `build-llvm` produces a
# native executable through libLLVM *inside this process*, and the only program
# it starts on the outside is the linker.
#
# `tools\llvm-inproc.ps1` proves that the way a claim of absence has to be
# proved -- it puts a restricted PATH in front of the build holding sabotage
# `cl.bat`, `clang.bat` and `clang-cl.bat` that each write a marker file and exit
# 97, so a build that reached for a C compiler would leave a file behind; and it
# first proves the trap works, because "no marker appeared" is also exactly what
# an empty directory produces.
#
# It has existed for several sessions and *nothing called it*: the strongest
# claim this project makes was verified only when somebody happened to remember
# the script's name.  A check that never runs is how a false green is born, so it
# is a step here, after the build it depends on.
$llvmRuntimeObj = Join-Path $repo 'selfhost\build\vela_llvm_runtime.obj'
if (Test-Path -LiteralPath $llvmRuntimeObj) {
    Invoke-Step -Name 'llvm-inproc' -Script 'tools\llvm-inproc.ps1' -SuccessPattern 'RESULT: ok'
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'llvm-inproc'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (no selfhost\build\vela_llvm_runtime.obj -- the build did not get that far)'; Out = ''; Err = ''
    })
}

# The compiler's own code generator is reached through `extern c` declarations that
# are generated from the C header, because an interface written twice by hand drifts.
# The generated file is only honest if it is still what the header says, so the check
# runs here rather than in someone's memory.  It is skipped, loudly, when the shim
# does not exist yet.
$shimHeader = Join-Path $repo 'runtime\vela_llvm_shim.h'
if (Test-Path -LiteralPath $shimHeader) {
    Invoke-Step -Name 'shim-decls' -Script 'tools\gen-shim-decls.ps1' -Arguments @('-Check') -SuccessPattern 'in step:'
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'shim-decls'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (no runtime\vela_llvm_shim.h yet)'; Out = ''; Err = ''
    })
}

if (-not $SkipSuite) {
    Invoke-Step -Name 'suite' -Script 'tools\refreeze.ps1' -SuccessPattern 'RESULT: green'
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'suite'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (-SkipSuite)'; Out = ''; Err = ''
    })
}
if ($WithBench) {
    Invoke-Step -Name 'bench' -Script 'tools\bench.ps1' -Arguments @('-Reps', '7') -SuccessPattern 'every variant printed the answer it must'
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'bench'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (add -WithBench; a benchmark needs an idle machine)'; Out = ''; Err = ''
    })
}
if ($WithPlugin) {
    Invoke-Step -Name 'plugin' -Script 'idea-plugin\build-offline.ps1' -SuccessPattern 'RESULT: PASS'
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'plugin'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (add -WithPlugin)'; Out = ''; Err = ''
    })
}

# A verifier is only a verifier if it has been shown to fail.  The mutation test
# breaks the plugin on purpose, one defect at a time, and requires the verifier to
# catch each one; opt-in because it builds the plugin once per mutant.
$mutation = Join-Path $repo 'idea-plugin\mutation-test.ps1'
if ($WithMutation) {
    if (Test-Path -LiteralPath $mutation) {
        Invoke-Step -Name 'mutation' -Script 'idea-plugin\mutation-test.ps1' -SuccessPattern 'RESULT:'
    } else {
        [void]$results.Add([pscustomobject]@{
            Step = 'mutation'; Ok = $false; Code = 1; Seconds = 0;
            Verdict = "asked for -WithMutation but $mutation does not exist"; Out = ''; Err = ''
        })
    }
} else {
    [void]$results.Add([pscustomobject]@{
        Step = 'mutation'; Ok = $null; Code = 0; Seconds = 0;
        Verdict = 'skipped (add -WithMutation: proves the verifier can fail)'; Out = ''; Err = ''
    })
}

Write-Host ''
Write-Host '===================================================== summary'
foreach ($r in $results) {
    $state = if ($null -eq $r.Ok) { 'skip' } elseif ($r.Ok) { 'ok  ' } else { 'FAIL' }
    Write-Host ("  {0}  {1,-7} {2,5}s  {3}" -f $state, $r.Step, $r.Seconds, $r.Verdict)
}
Write-Host ''
Write-Host '  not covered by this command, and not claimed to be:'
Write-Host '    * the plugin has never been loaded into a running IDE (needs a booted application)'
Write-Host '    * the three-path differential for the LLVM backend (tests\run_llvm.vel) does not exist yet'
Write-Host ''

# `$_`, not a named variable: this line first read `$r.Ok -eq $false`, where `$r` is
# the *outer* foreach's variable from the summary loop above and is therefore always
# `$null` inside the Where-Object script block.  `$null -eq $false` is false, so no
# step was ever counted as failed and the gate printed "RESULT: ok - 4 step(s) ran,
# all green" while the suite had just reported two failing cases.  Measured on the
# first real run, 2026-09-19.  A gate that cannot fail is the same defect this file
# exists to catch, so the bug is worth the words.
$failed = @($results | Where-Object { $_.Ok -eq $false })
if ($failed.Count -gt 0) {
    Write-Host ("RESULT: FAIL - {0} step(s): {1}" -f $failed.Count, (($failed | ForEach-Object { $_.Step }) -join ', ')) -ForegroundColor Red
    exit 1
}
$ran = @($results | Where-Object { $null -ne $_.Ok }).Count
Write-Host ("RESULT: ok - {0} step(s) ran, all green" -f $ran) -ForegroundColor Green
