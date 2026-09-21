<#
.SYNOPSIS
    Reads the Python plugin's own extension surface out of the installed PyCharm,
    so FEATURE_PARITY.md's "what the Python plugin does" column is a citation
    rather than a memory.

.DESCRIPTION
    The reference bar for this plugin is PyCharm's Python support, and the only
    authority for what that support *consists of* is the descriptor that PyCharm
    itself loads:

        <PyCharm>\plugins\python-ce\lib\python-ce.jar!META-INF\plugin.xml
        <PyCharm>\plugins\python\lib\python.jar!META-INF\plugin.xml
        <PyCharm>\plugins\python-dap\lib\python-dap.jar!META-INF\plugin.xml

    A jar is a zip and the descriptor is one entry in it, so this reads the entry
    and writes it out verbatim.  Nothing is summarised: the point is that a later
    reader can grep the extracted descriptor for the exact registration a
    FEATURE_PARITY row cites.

    `python-ce.jar` is where the *language core* lives (the Python file type, the
    parser definition, the syntax highlighter, parameter info, the structure view
    plumbing); `python.jar` is the product-level surface (inspections, run
    configurations, debugger breakpoints, type providers); `python-dap.jar` is the
    Debug Adapter Protocol client, which is how PyCharm's debugger actually talks
    to a Python process.

.PARAMETER PycharmHome
    The PyCharm installation.  Default: the newest installed PyCharm found under
    the usual JetBrains roots, else the newest IDE that has plugins\python-ce.

.PARAMETER OutDir
    Where the descriptors are written.  Default: idea-plugin\build\python-surface\
    (build\ is gitignored: these are extracts, not sources).

.PARAMETER Tag
    Print the distinct extension tags of one descriptor (ce|py|dap) to stdout.

.PARAMETER Grep
    Print every XML element of one descriptor (ce|py|dap) whose text matches this
    regular expression.  This is the mode FEATURE_PARITY.md's citations were
    gathered with.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\python-surface.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\python-surface.ps1 -Grep 'lang\.parserDefinition' -Tag ce
#>
[CmdletBinding()]
param(
    [string] $PycharmHome,
    [string] $OutDir,
    [ValidateSet('ce', 'py', 'dap')] [string] $Tag,
    [string] $Grep
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDir '..')).Path
if (-not $OutDir) { $OutDir = Join-Path $pluginRoot 'build\python-surface' }

function Die([string] $t) { Write-Host "ERROR: $t" -ForegroundColor Red; exit 2 }

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
            if (Test-Path -LiteralPath (Join-Path $d.FullName 'build.txt')) {
                if (-not $found.Contains($d.FullName)) { $found.Add($d.FullName) }
            }
        }
    }
    return $found.ToArray()
}

if (-not $PycharmHome) {
    $ides = @(Get-InstalledIdes | Sort-Object)
    $py = @($ides | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'plugins\python-ce\lib\python-ce.jar') })
    if ($py.Count -eq 0) { Die 'no installed IDE carries plugins\python-ce\lib\python-ce.jar; pass -PycharmHome' }
    $PycharmHome = $py[-1]
}
$buildTxt = Join-Path $PycharmHome 'build.txt'
if (-not (Test-Path -LiteralPath $buildTxt)) { Die "not an IDE directory (no build.txt): $PycharmHome" }

$jars = [ordered]@{
    ce  = Join-Path $PycharmHome 'plugins\python-ce\lib\python-ce.jar'
    py  = Join-Path $PycharmHome 'plugins\python\lib\python.jar'
    dap = Join-Path $PycharmHome 'plugins\python-dap\lib\python-dap.jar'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "PyCharm   : $PycharmHome"
Write-Host "build.txt : $((Get-Content -LiteralPath $buildTxt -Raw).Trim())"
Write-Host ''

$extracted = @{}
foreach ($k in $jars.Keys) {
    $jar = $jars[$k]
    if (-not (Test-Path -LiteralPath $jar)) { Write-Host "  $k : MISSING $jar" -ForegroundColor DarkYellow; continue }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/plugin.xml' } | Select-Object -First 1
        if (-not $entry) { Write-Host "  $k : no META-INF/plugin.xml inside"; continue }
        $sr = New-Object System.IO.StreamReader($entry.Open())
        $text = $sr.ReadToEnd()
        $sr.Close()
    } finally { $zip.Dispose() }
    $dest = Join-Path $OutDir "$k-plugin.xml"
    [System.IO.File]::WriteAllText($dest, $text, (New-Object System.Text.UTF8Encoding($false)))
    $extracted[$k] = $dest
    Write-Host ("  {0} : {1} bytes -> {2}" -f $k, $text.Length, $dest)
}

if ($Grep) {
    $keys = if ($Tag) { @($Tag) } else { @($extracted.Keys) }
    foreach ($k in $keys) {
        if (-not $extracted.ContainsKey($k)) { continue }
        $t = [System.IO.File]::ReadAllText($extracted[$k])
        Write-Host ''
        Write-Host "### $k  /$Grep/"
        foreach ($m in [regex]::Matches($t, "<[^<>]*$Grep[^<>]*>")) { Write-Host ("    " + $m.Value.Trim()) }
    }
} elseif ($Tag) {
    if (-not $extracted.ContainsKey($Tag)) { Die "no descriptor extracted for $Tag" }
    $t = [System.IO.File]::ReadAllText($extracted[$Tag])
    Write-Host ''
    Write-Host "### distinct extension tags in $Tag"
    foreach ($n in ([regex]::Matches($t, '<([A-Za-z_][\w.]*)[\s>]') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)) {
        Write-Host "    $n"
    }
}
exit 0
