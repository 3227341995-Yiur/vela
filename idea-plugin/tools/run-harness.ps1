<#
.SYNOPSIS
    Compiles the tracked harness sources and runs ONE of them against the plugin
    classes in `build\classes`.

.DESCRIPTION
    `harness.ps1` drives the eight named differentials and takes the repo root for
    them; it has no way to run a class that is not on its list (HintProbe,
    ProbeGoto, ProbeParams and HintTruth are all tracked but had no driver, so the
    only way to run one was to re-type the 32 KB argfile dance by hand).  This is
    that driver: same JDK discovery, same @argfile (the classpath does not fit on a
    command line), same classpath root for the plugin's resources.

    Nothing here needs the compiler.

.PARAMETER Class
    The harness class to run, e.g. HintTruth or ProbeParams.

.PARAMETER Rest
    Arguments for the class.  The repo root is prepended unless -NoRepoRoot.

.PARAMETER RepoRoot
    The repo root to pass to the class.  Default: the parent of this script's folder.

.PARAMETER NoRepoRoot
    Do not prepend the repo root.

.PARAMETER NoCompile
    Reuse the classes already in build\tools\harness\classes.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-harness.ps1 -Class HintTruth -Rest --single,idea-plugin/build/repro/multi.vel -- -Dump
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Class,
    [string[]] $Rest = @(),
    [string] $RepoRoot,
    [switch] $NoRepoRoot,
    [switch] $NoCompile
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
$pluginRoot = $scriptDir | Split-Path -Parent
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDir '..\..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$harnessSrc    = Join-Path $scriptDir 'harness\src'
$harnessOut    = Join-Path $pluginRoot 'build\tools\harness\classes'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$resourceDir   = Join-Path $pluginRoot 'src\main\resources'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Die([string] $text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

function Get-InstalledIdes {
    $roots = @('D:\JetBrains', 'C:\JetBrains', 'C:\Program Files\JetBrains',
               (Join-Path $env:LOCALAPPDATA 'Programs'),
               (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'))
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($d in (Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue)) {
            if ((Test-Path -LiteralPath (Join-Path $d.FullName 'jbr\bin\java.exe')) -and
                (Test-Path -LiteralPath (Join-Path $d.FullName 'build.txt'))) {
                if (-not $found.Contains($d.FullName)) { $found.Add($d.FullName) }
            }
        }
    }
    return $found.ToArray()
}

$ides = @(Get-InstalledIdes | Sort-Object)
if ($ides.Count -eq 0) { Die 'no JetBrains IDE found; the JDK is the IDE''s' }
$idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' })
$best = if ($idea.Count -gt 0) { $idea[-1] } else { $ides[-1] }
$java  = Join-Path $best 'jbr\bin\java.exe'
$javac = Join-Path $best 'jbr\bin\javac.exe'
foreach ($t in @($java, $javac)) { if (-not (Test-Path -LiteralPath $t)) { Die "missing: $t" } }
if (-not (Test-Path -LiteralPath $classpathFile)) { Die "missing $classpathFile; run build-offline.ps1 first" }
if (-not (Test-Path -LiteralPath $pluginClasses)) { Die "missing $pluginClasses; run build-offline.ps1 first" }

New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$cp = "$harnessOut;$pluginClasses;$resourceDir;$classpath"

function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
function Write-ArgFile([string] $path, [string[]] $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]] $lines, (New-Object System.Text.UTF8Encoding($false)))
}

if (-not $NoCompile) {
    $sources = @(Get-ChildItem -LiteralPath $harnessSrc -Filter '*.java' | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) { Die "no *.java under $harnessSrc" }
    $javacArgsFile = Join-Path $harnessOut 'javac.args'
    Write-ArgFile $javacArgsFile (@('--release 21', '-encoding UTF-8', '-d ' + (Quoted $harnessOut),
            '-cp ' + (Quoted $cp)) + @($sources | ForEach-Object { Quoted $_ }))
    $javacLog = Join-Path $harnessOut 'javac.log'
    # Written to files rather than piped: this shell turns a native program's stderr
    # into a terminating error when it is captured in a pipeline.
    & $javac ('@' + $javacArgsFile) 1> $javacLog 2> "$javacLog.err"
    $code = $LASTEXITCODE
    Get-Content -LiteralPath $javacLog -ErrorAction SilentlyContinue | Write-Host
    if (Test-Path -LiteralPath "$javacLog.err") {
        $errText = Get-Content -LiteralPath "$javacLog.err" -Raw
        if ($errText -and $errText.Trim().Length -gt 0) { Write-Host $errText.Trim() -ForegroundColor DarkYellow }
    }
    if ($code -ne 0) { Die "javac failed (see $javacLog)" }
    Write-Host "compiled $($sources.Count) source file(s) -> $harnessOut"
}

# `-File` hands a `[string[]]` parameter one literal string, so a caller using
# `-Rest --single,x.vel,--dump` arrives here as a single element: split it the way it
# was written rather than passing a path with a comma in it to the class.
if ($Rest.Count -eq 1 -and $Rest[0] -match ',') { $Rest = @($Rest[0] -split ',') }

$args2 = @()
if (-not $NoRepoRoot) { $args2 += $RepoRoot }
$args2 += $Rest
$runArgsFile = Join-Path $harnessOut "$Class.args"
Write-ArgFile $runArgsFile (@('-cp ' + (Quoted $cp)) + @($Class) + @($args2 | ForEach-Object { Quoted $_ }))
$log = Join-Path $harnessOut "$Class.direct.log"
& $java ('@' + $runArgsFile) 1> $log 2> "$log.err"
$code = $LASTEXITCODE
Get-Content -LiteralPath $log -ErrorAction SilentlyContinue | Write-Host
if (Test-Path -LiteralPath "$log.err") {
    $errText = Get-Content -LiteralPath "$log.err" -Raw
    if ($errText -and $errText.Trim().Length -gt 0) {
        Write-Host '--- stderr' -ForegroundColor DarkYellow
        Write-Host $errText.Trim()
    }
}
Write-Host "exit $code   log: $log"
exit $code
