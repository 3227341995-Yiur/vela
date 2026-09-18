<#
.SYNOPSIS
    Take a timestamped snapshot of the Vela sources, with no version control needed.

.DESCRIPTION
    This repository has **no version control at all**, and last week that cost the
    project its entire test corpus: one hand-run cleanup deleted `tests/cases.txt`,
    the goldens and ~112 case sources, and there was nothing to restore them from.
    They came back only because session transcripts happened to hold a copy of the
    pre-port test file.  `git init` is the right long-term answer and is one line
    away; this script is for the days when nobody has got round to it.

    It archives exactly the files that are **source**, and nothing a build
    produces: the Vela sources, the corpus and its goldens, the runtime header, the
    tools, every document, and the plugin's sources and descriptor.  Build outputs
    (`*.obj`, `*.exe`, `dist\`, `idea-plugin\build\`, scratch directories) are left
    out, so a snapshot is small enough to keep dozens of.

    Two checks run after writing, because a backup nobody has opened is a backup
    nobody knows the state of:
      * the archive is re-opened and its entry count compared with what was added;
      * a short manifest of every included path and size is written beside it, so
        the contents can be diffed against the tree later without unpacking.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\snapshot.ps1
    powershell -ExecutionPolicy Bypass -File tools\snapshot.ps1 -Out D:\vela-backups

.NOTES
    Deliberately **not** inside the repository by default: a backup stored next to
    the thing it backs up is one `Remove-Item -Recurse` away from being part of the
    disaster.  The default is `..\vela-snapshots`, a sibling of the checkout.
#>
[CmdletBinding()]
param(
    [string] $Out,
    [string] $Label = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path (Split-Path -Parent $repo) 'vela-snapshots' }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$stamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$name = if ($Label) { "vela-$stamp-$Label" } else { "vela-$stamp" }
$zip = Join-Path $Out "$name.zip"

# What is source, and what is a build output.  Keeping this list explicit is the
# point: a snapshot that silently includes 180 executables is a snapshot nobody
# takes twice.
$include = @(
    'SPEC.md', 'DESIGN.md', 'README.md', 'ROADMAP.md', 'STATUS.md',
    '.gitignore', '.gitattributes',
    'runtime', 'tools', 'bench', 'examples',
    'selfhost', 'tests',
    'idea-plugin\src', 'idea-plugin\README.md', 'idea-plugin\CHANGELOG.md',
    'idea-plugin\PLUGIN_SURFACE.md', 'idea-plugin\BUILD_CHECKLIST.md',
    'idea-plugin\build-offline.ps1', 'idea-plugin\build.gradle.kts',
    'idea-plugin\settings.gradle.kts'
)
# Directories under those that are outputs rather than source.
$excludeDirs = @('build', 'dist', 'out', '.work', '.gradle', 'node_modules', 'staging', 'verify')

Write-Host "Vela snapshot"
Write-Host "  repository : $repo"
Write-Host "  archive    : $zip"

$staging = Join-Path ([IO.Path]::GetTempPath()) "vela-snap-$stamp"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

$added = New-Object System.Collections.Generic.List[string]
foreach ($rel in $include) {
    $src = Join-Path $repo $rel
    if (-not (Test-Path -LiteralPath $src)) { continue }
    $item = Get-Item -LiteralPath $src
    if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $src -Recurse -File | Where-Object {
            $parts = $_.FullName.Substring($repo.Length + 1).Split('\')
            $skip = $false
            foreach ($p in $parts) { if ($excludeDirs -contains $p) { $skip = $true; break } }
            if ($skip) { return $false }
            # build outputs, wherever they sit
            return ($_.Extension -notin @('.obj', '.exe', '.pdb', '.ilk', '.lib', '.exp', '.pyc')) -and
                   ($_.Name -notlike '_cap_*') -and ($_.Name -notlike '_rec*')
        } | ForEach-Object {
            $target = Join-Path $staging $_.FullName.Substring($repo.Length + 1)
            New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target -Force
            $added.Add($_.FullName.Substring($repo.Length + 1))
        }
    } else {
        Copy-Item -LiteralPath $src -Destination (Join-Path $staging $item.Name) -Force
        $added.Add($rel)
    }
}

if ($added.Count -eq 0) { throw 'nothing to snapshot: every included path was missing' }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -CompressionLevel Optimal
Remove-Item $staging -Recurse -Force

# --- verification: re-open the archive and compare the entry count -------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
$entries = $archive.Entries.Count
$archive.Dispose()
$ok = ($entries -ge $added.Count)
Write-Host ''
Write-Host "  files  : $($added.Count) added, $entries entries in the archive"
Write-Host "  bytes  : $((Get-Item $zip).Length) compressed"
if ($ok) { Write-Host '  check  : the archive re-opens and holds at least what was added - ok' }
else { Write-Host '  check  : FEWER entries than files added - the archive is incomplete' }

$manifest = Join-Path $Out "$name.manifest.txt"
$added | Sort-Object | ForEach-Object {
    $f = Join-Path $repo $_
    if (Test-Path -LiteralPath $f) { '{0,10} {1}' -f (Get-Item -LiteralPath $f).Length, $_ }
} | Set-Content -LiteralPath $manifest -Encoding utf8
Write-Host "  manifest: $manifest"

if (-not $ok) { exit 1 }
exit 0
