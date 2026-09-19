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
      suite     tools\refreeze.ps1     record the locks, keep the written
                                       expectations, run all the cases
      bench     tools\bench.ps1        7 repetitions per variant, every answer
                                       checked (slow, and only meaningful on an
                                       idle machine, so it is opt-in)
      plugin    idea-plugin\build-offline.ps1
                                       compile the plugin and run its verifier

.PARAMETER WithBench
    Also run the benchmarks.  Off by default: they take minutes, and a benchmark
    taken while anything else is running is a number nobody should quote.

.PARAMETER WithPlugin
    Also build and verify the IDEA plugin (needs the installed IDE's kotlinc; it
    does not need the network).

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

$failed = @($results | Where-Object { $r.Ok -eq $false })
if ($failed.Count -gt 0) {
    Write-Host ("RESULT: FAIL - {0} step(s): {1}" -f $failed.Count, (($failed | ForEach-Object { $_.Step }) -join ', ')) -ForegroundColor Red
    exit 1
}
$ran = @($results | Where-Object { $null -ne $_.Ok }).Count
Write-Host ("RESULT: ok - {0} step(s) ran, all green" -f $ran) -ForegroundColor Green
