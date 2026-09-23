<#
.SYNOPSIS
    Runs SymbolDiff or FoldDiff on their own, from the tree.

.DESCRIPTION
    `harness.ps1 -Tool SymbolDiff,FoldDiff` compiles all eleven harness sources into
    `build\tools\harness\classes` -- the same output directory a concurrent
    `harness.ps1 -Tool All` is writing into, and the same `GotoOracle.log` /
    `HintDiff.log` a concurrent pass is overwriting.  Two drivers sharing one log path
    was measured here: a run of the cache probe read back an *oracle* log because the
    other job had replaced it in between.

    So this driver compiles only the two detectors it needs, into its own output
    directory (`build\tools\detectors\classes`) and its own logs, and can be run while
    anything else is running.  It is also what makes these two tools runnable at all
    now that their `harness.ps1` plumbing is owned elsewhere.

.PARAMETER Tool
    Both (default), SymbolDiff, or FoldDiff.

.PARAMETER RepoRoot
    The Vela checkout.  Default: the parent of this script's folder.

.PARAMETER Single
    Pass `--single <file>` (a path relative to the repo root).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-detectors.ps1

.NOTES
    Writes only under `idea-plugin\build\`.
#>
[CmdletBinding()]
param(
    [ValidateSet('Both', 'SymbolDiff', 'FoldDiff')][string] $Tool = 'Both',
    [string] $RepoRoot,
    [string] $Single
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$toolsDir   = $PSScriptRoot
$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $toolsDir '..')).Path
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$harnessSrc    = Join-Path $pluginRoot 'tools\harness\src'
$out           = Join-Path $pluginRoot 'build\tools\detectors\classes'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$resourceDir   = Join-Path $pluginRoot 'src\main\resources'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Step([string] $t) { Write-Host ''; Write-Host "=== $t" -ForegroundColor Cyan }
function Info([string] $t) { Write-Host "    $t" }
function Die([string] $t)  { Write-Host "ERROR: $t" -ForegroundColor Red; exit 2 }

if (-not (Test-Path -LiteralPath $classpathFile)) { Die "missing $classpathFile - run build-offline.ps1 first" }
if (-not (Test-Path -LiteralPath $pluginClasses)) { Die "missing $pluginClasses - run build-offline.ps1 first" }

function Get-InstalledIdes {
    $roots = @(
        'D:\JetBrains', 'C:\JetBrains',
        'C:\Program Files\JetBrains', 'C:\Program Files (x86)\JetBrains',
        (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'),
        (Join-Path $env:LOCALAPPDATA 'Programs'),
        (Join-Path $env:USERPROFILE '.local\share\JetBrains\Toolbox\apps')
    )
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

New-Item -ItemType Directory -Force -Path $out | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$cp = "$out;$pluginClasses;$resourceDir;$classpath"
function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
function Write-ArgFile([string] $path, [string[]] $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]] $lines, (New-Object System.Text.UTF8Encoding($false)))
}

$tools = if ($Tool -eq 'Both') { @('SymbolDiff', 'FoldDiff') } else { @($Tool) }
Step "Compile $($tools -join ', ') (plus Coverage) from the tree"
$srcs = @($tools | ForEach-Object { Quoted (Join-Path $harnessSrc "$_.java") }) +
        @((Quoted (Join-Path $harnessSrc 'Coverage.java')))
$javacArgs = Join-Path $out 'javac.args'
Write-ArgFile $javacArgs (@(
        '--release 21', '-encoding UTF-8',
        '-d ' + (Quoted $out), '-cp ' + (Quoted $cp)
    ) + $srcs)
& $javac ('@' + $javacArgs) 2>&1 | Tee-Object -FilePath (Join-Path $out 'javac.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed (see $out\javac.log)" }
Info "compiled into $out"

$exit = 0
foreach ($t in $tools) {
    Step $t
    $a = @($t, $RepoRoot)
    if ($Single) { $a += @('--single', $Single) }
    $runArgs = Join-Path $out "$t.args"
    Write-ArgFile $runArgs (@('-cp ' + (Quoted $cp)) + @($a | ForEach-Object { Quoted $_ }))
    $log = Join-Path $out "$t.log"
    & $java ('@' + $runArgs) 1> $log 2> "$log.err"
    $code = $LASTEXITCODE
    Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
    $errText = ''
    if (Test-Path -LiteralPath "$log.err") { $errText = Get-Content -LiteralPath "$log.err" -Raw }
    if ($errText -and $errText.Trim().Length -gt 0) {
        Write-Host '--- stderr' -ForegroundColor DarkYellow
        Write-Host $errText.Trim()
    }
    Info "exit $code   log: $log"
    if ($code -ne 0) { $exit = $code }
}

Step 'Done'
Info "logs: $out"
exit $exit
