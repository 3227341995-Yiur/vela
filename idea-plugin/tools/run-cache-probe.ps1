<#
.SYNOPSIS
    Runs the two GotoOracle measurements that are not part of the normal harness pass:
    the cache-tearing probe, and the oracle itself on an isolated cache.

.DESCRIPTION
    `harness.ps1 -Tool GotoOracle` runs the oracle over the corpus with the shared
    cache under `build\tools\harness\`.  That is the right thing for the oracle and the
    wrong thing for two measurements:

      * the *cache probe* (`--probe-cache`) is not the oracle at all: it runs 8 threads
        against one cache path to prove the write cannot tear.  It was briefly reachable
        through `harness.ps1 -CacheProbe`; that switch was removed when ownership of
        `harness.ps1` moved to another agent, so this driver is how it is run now.
        The probe picks its own path (`<cache>.probe\probe-cache.txt`) and never touches
        the shared cache, so it cannot disturb a concurrent harness pass.

      * the oracle on an *isolated* cache is how the cache's two properties are shown
        end to end: a warm run must judge the same number of references as a cold one
        (`--rebuild-oracle` first), and the cache file must load as a whole cache.

    The frozen compiler is copied from the tree with its DLL, because `vm.exe` does not
    start without `LLVM-C.dll` beside it (exit 0xC0000135 with no message).  Nothing
    here runs `tools\build.ps1` or `tools\refreeze.ps1`.

.PARAMETER Mode
    CacheProbe (default) or Oracle.

.PARAMETER RepoRoot
    The Vela checkout.  Default: the parent of this script's folder.

.PARAMETER Cache
    The cache file to use.  Default: the probe's own path for CacheProbe, and
    `build\tools\harness\goto-oracle-isolated.txt` for Oracle.

.PARAMETER Rebuild
    Oracle mode: pass `--rebuild-oracle`, ignoring whatever is in the cache.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-cache-probe.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-cache-probe.ps1 `
        -Mode Oracle -Rebuild

.NOTES
    Writes only under `idea-plugin\build\` and `%TEMP%\vela-plugin-parse\`.
#>
[CmdletBinding()]
param(
    [ValidateSet('CacheProbe', 'Oracle')][string] $Mode = 'CacheProbe',
    [string] $RepoRoot,
    [string] $Cache,
    [switch] $Rebuild
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$toolsDir   = $PSScriptRoot
$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $toolsDir '..')).Path
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$harnessSrc    = Join-Path $pluginRoot 'tools\harness\src'
$harnessOut    = Join-Path $pluginRoot 'build\tools\harness\classes'
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

# ------------------------------------------------------------------ frozen compiler
$scratch = Join-Path $env:TEMP 'vela-plugin-parse'
New-Item -ItemType Directory -Force -Path $scratch | Out-Null
$treeVm = Join-Path $RepoRoot 'selfhost\build\vm.exe'
$frozenVm = Join-Path $scratch 'vm.exe'
if (-not (Test-Path -LiteralPath $frozenVm)) {
    if (-not (Test-Path -LiteralPath $treeVm)) { Die "no compiler at $treeVm" }
    Copy-Item -LiteralPath $treeVm -Destination $frozenVm -Force
}
$treeDll = Join-Path $RepoRoot 'selfhost\LLVM-C.dll'
$frozenDll = Join-Path $scratch 'LLVM-C.dll'
if (-not (Test-Path -LiteralPath $frozenDll) -and (Test-Path -LiteralPath $treeDll)) {
    Copy-Item -LiteralPath $treeDll -Destination $frozenDll -Force
}
$frozenHash = (Get-FileHash -LiteralPath $frozenVm -Algorithm SHA256).Hash.ToLowerInvariant()
$treeHash = (Get-FileHash -LiteralPath $treeVm -Algorithm SHA256).Hash.ToLowerInvariant()

Step 'Preconditions'
Info "repo root  : $RepoRoot"
Info "JDK        : $best"
Info "frozen vm  : $frozenVm ($((Get-Item -LiteralPath $frozenVm).Length) bytes, sha256 $frozenHash)"
Info "tree vm    : $treeVm ($((Get-Item -LiteralPath $treeVm).Length) bytes, sha256 $treeHash)"
if ($frozenHash -ne $treeHash) { Info 'WARNING: the frozen compiler is NOT the tree compiler (the tree changed)' }

# ------------------------------------------------------------------ compile GotoOracle
Step 'Compile GotoOracle (and Coverage) from the tree'
New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$cp = "$harnessOut;$pluginClasses;$resourceDir;$classpath"
function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
function Write-ArgFile([string] $path, [string[]] $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]] $lines, (New-Object System.Text.UTF8Encoding($false)))
}
$javacArgs = Join-Path $harnessOut 'cache-probe.javac.args'
Write-ArgFile $javacArgs (@(
        '--release 21',
        '-encoding UTF-8',
        '-d ' + (Quoted $harnessOut),
        '-cp ' + (Quoted $cp),
        (Quoted (Join-Path $harnessSrc 'GotoOracle.java')),
        (Quoted (Join-Path $harnessSrc 'Coverage.java'))
    ))
& $javac ('@' + $javacArgs) 2>&1 | Tee-Object -FilePath (Join-Path $harnessOut 'cache-probe-javac.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed (see $harnessOut\cache-probe-javac.log)" }
Info 'compiled'

# ------------------------------------------------------------------ run
if (-not $Cache) {
    $Cache = if ($Mode -eq 'CacheProbe') {
        Join-Path $pluginRoot 'build\tools\harness\goto-oracle.txt.probe\probe-cache.txt'
    } else {
        Join-Path $pluginRoot 'build\tools\harness\goto-oracle-isolated.txt'
    }
}
Step "GotoOracle --$($Mode.ToLower() -replace 'cacheprobe','probe-cache') with cache $Cache"
$args = @('GotoOracle', $RepoRoot, '--vm', $frozenVm, '--cache', $Cache)
if ($Mode -eq 'CacheProbe') { $args += '--probe-cache' }
if ($Rebuild)               { $args += '--rebuild-oracle' }
$runArgs = Join-Path $harnessOut 'cache-probe.run.args'
Write-ArgFile $runArgs (@('-cp ' + (Quoted $cp)) + @($args | ForEach-Object { Quoted $_ }))
$log = Join-Path $harnessOut "cache-probe-$Mode.log"
& $java ('@' + $runArgs) 1> $log 2> "$log.err"
$code = $LASTEXITCODE
Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
$errText = ''
if (Test-Path -LiteralPath "$log.err") { $errText = Get-Content -LiteralPath "$log.err" -Raw }
if ($errText -and $errText.Trim().Length -gt 0) {
    Write-Host '--- stderr' -ForegroundColor DarkYellow
    Write-Host $errText.Trim()
}
Step 'Done'
Info "exit $code   log: $log"
exit $code
