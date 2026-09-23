<#
.SYNOPSIS
    Compiles and runs ONE harness source file against the plugin's built classes.

.DESCRIPTION
    `harness.ps1` compiles all eleven harness sources and runs a fixed set of them;
    `psi-tree-diff.ps1` compiles exactly two.  Neither is usable for a one-off
    diagnostic probe, and adding a probe to a driver's hard-coded list to debug a
    single file is how a debug print ends up in a verdict tool.

    So this is the small driver: name a source file under `tools\harness\src`, and it
    compiles that one file (plus `Coverage.java`, which every harness uses) into
    `build\tools\harness\probe-classes` and runs it with the same classpath and the
    same @argfile mechanism the other drivers use -- the classpath is 32 KB over 429
    jars under a path containing spaces, which does not fit on a command line.

    Nothing here is a verdict: a probe's output is evidence for a person to read.

.PARAMETER Probe
    The class name, i.e. the file name without `.java`, under `tools\harness\src`.

.PARAMETER ProbeArgs
    The probe's own arguments as one comma-separated string, e.g.
    `-ProbeArgs C:\repo,tests/build/lexer_error.vel`.  Empty means none.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\run-probe.ps1 `
        -Probe PsiLeafProbe -ProbeArgs C:\repo,tests/build/lexer_error.vel

.NOTES
    Writes only under `idea-plugin\build\`.  Neither the compiler nor the plugin's
    own build scripts are invoked.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Probe,
    # The probe's own arguments, passed as ONE comma-separated string and split here.
    #
    # This is not decoration.  `powershell -File script.ps1 -ProbeArgs a,b` passes the
    # single token `a,b` through, so binding `[string[]]` directly produced a one-element
    # array holding `a,b` and the probe then looked for a file named `a,b` -- measured,
    # twice, once with a quoted array and once with a repeated parameter (which is
    # rejected outright).  Splitting a string this powershell always delivers intact is
    # the version that works under `powershell -File` from PowerShell 5.1.
    [string] $ProbeArgs = ''
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$toolsDir   = $PSScriptRoot
$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $toolsDir '..')).Path
# The diagnostics live in `tools\probes\src`, deliberately NOT in `tools\harness\src`:
# every driver in this plugin compiles `harness\src\*.java` wholesale, so a probe kept
# there is compiled by drivers that have nothing to do with it, and a compile error in
# a diagnostic looks like a failure of the verdict tool that swept it up.  The probes
# read the plugin's classes and measure; they decide nothing.
$harnessSrc = Join-Path $pluginRoot 'tools\probes\src'
$harnessOut = Join-Path $pluginRoot 'build\tools\probes\classes'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$resourceDir   = Join-Path $pluginRoot 'src\main\resources'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Die([string] $t) { Write-Host "ERROR: $t" -ForegroundColor Red; exit 2 }

$source = Join-Path $harnessSrc "$Probe.java"
if (-not (Test-Path -LiteralPath $source)) { Die "no such probe source: $source" }
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

New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$cp = "$harnessOut;$pluginClasses;$resourceDir;$classpath"
function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
function Write-ArgFile([string] $path, [string[]] $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]] $lines, (New-Object System.Text.UTF8Encoding($false)))
}

$javacArgs = Join-Path $harnessOut "$Probe.javac.args"
Write-ArgFile $javacArgs (@(
        '--release 21',
        '-encoding UTF-8',
        '-d ' + (Quoted $harnessOut),
        '-cp ' + (Quoted $cp),
        (Quoted $source)
    ))
& $javac ('@' + $javacArgs)
if ($LASTEXITCODE -ne 0) { Die "javac failed for $Probe" }

$runArgs = Join-Path $harnessOut "$Probe.run.args"
$split = @()
if ($ProbeArgs -and $ProbeArgs.Trim().Length -gt 0) {
    $split = @($ProbeArgs.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
}
Write-ArgFile $runArgs (@('-cp ' + (Quoted $cp)) + @($Probe) + @($split | ForEach-Object { Quoted $_ }))
& $java ('@' + $runArgs)
exit $LASTEXITCODE
