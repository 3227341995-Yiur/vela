<#
.SYNOPSIS
    Bump the Vela plugin's version everywhere it is written, or refuse and say why.

.DESCRIPTION
    The rule, from the language's owner: **every plugin update bumps the version**,
    and the number is written in four places that have to agree.

        src\main\resources\META-INF\plugin.xml     <version>      source of truth
                                                                 (build-offline.ps1
                                                                 reads it to name
                                                                 the dist zip)
        build.gradle.kts                           version        the Gradle path
        build-offline.ps1                          derived        nothing to edit:
                                                                 the zip name comes
                                                                 from plugin.xml
        CHANGELOG.md                               a heading      one entry per
                                                                 version

    Measured before this script existed, they disagreed: `plugin.xml` said 0.1.1
    while `build.gradle.kts` still said 0.1.0 with `sinceBuild = "242"` against the
    descriptor's 253, and `CHANGELOG.md` did not exist at all.  A version that
    drifts is worse than no version, because the zip's name is what a reader
    installs from and the changelog is what tells them what they are installing.

    So: this edits plugin.xml and build.gradle.kts, and **refuses to run at all** if
    CHANGELOG.md has no section for the new version.  Writing the entry is a
    judgement about what changed and what was measured; only a person can make it,
    and this script will not fake it.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\bump-plugin-version.ps1 -NewVersion 0.1.3
    powershell -ExecutionPolicy Bypass -File tools\bump-plugin-version.ps1 -Bump minor
#>
[CmdletBinding(DefaultParameterSetName = 'Explicit')]
param(
    [Parameter(ParameterSetName = 'Explicit', Mandatory = $true)]
    [string] $NewVersion,

    [Parameter(ParameterSetName = 'Auto', Mandatory = $true)]
    [ValidateSet('major', 'minor', 'patch')]
    [string] $Bump
)

$ErrorActionPreference = 'Stop'
# This script lives in <repo>\tools, and the plugin is <repo>\idea-plugin -- not
# <repo>\src\...: the first version of this line walked up once and looked for
# `src\main\resources\META-INF\plugin.xml` at the repository root, where it has
# never been, so every invocation died with "missing ...plugin.xml" before it
# could refuse anything.
$repoRoot = Split-Path -Parent $PSScriptRoot
$pluginRoot = Join-Path $repoRoot 'idea-plugin'
$pluginXml = Join-Path $pluginRoot 'src\main\resources\META-INF\plugin.xml'
$gradleKts = Join-Path $pluginRoot 'build.gradle.kts'
$changelog = Join-Path $pluginRoot 'CHANGELOG.md'

foreach ($f in @($pluginXml, $gradleKts, $changelog)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "missing $f" }
}

# The source of truth, and the only place the current version is read from.
$xmlText = Get-Content -LiteralPath $pluginXml -Raw
$m = [regex]::Match($xmlText, '<version>\s*([^<\s]+)\s*</version>')
if (-not $m.Success) { throw "plugin.xml has no <version> element" }
$current = $m.Groups[1].Value

if ($PSCmdlet.ParameterSetName -eq 'Auto') {
    $parts = $current.Split('.')
    if ($parts.Count -ne 3) { throw "cannot bump '$current': not major.minor.patch" }
    $major = [int]$parts[0]; $minor = [int]$parts[1]; $patch = [int]$parts[2]
    switch ($Bump) {
        'major' { $major++; $minor = 0; $patch = 0 }
        'minor' { $minor++; $patch = 0 }
        'patch' { $patch++ }
    }
    $target = "$major.$minor.$patch"
} else {
    $target = $NewVersion
}

if ($target -eq $current) {
    throw "already at $current — a version bump that does not change the number is not a bump"
}

# The changelog entry is the point of the rule, so it is required *before* anything
# is edited.  Without this check the script would happily produce a release whose
# version nobody can look up.
$log = Get-Content -LiteralPath $changelog -Raw
if ($log -notmatch [regex]::Escape("## $target")) {
    throw @"
CHANGELOG.md has no section for $target.  Write it first — an entry that says what
changed and, for each change, whether it was verified or not.  That entry is the
only thing that tells a reader what they are about to install; this script will not
write it for you because it is a judgement, not a substitution.

Add a heading like:

    ## $target — unreleased, unverified

    - <what changed>, <verified by this command / not verified: no shell>
"@
}

# Edit 1: plugin.xml.
#
# The pattern is built into a variable first, and that is not style: in
#     $a -replace 'x' + [regex]::Escape($v) + 'y', 'z'
# the comma binds *tighter* than -replace, so the pattern argument is an ARRAY
# of two elements -- the concatenated pattern and the replacement -- and
# PowerShell stringifies it as "<pattern> <replacement>", which matches nothing
# and returns the text unchanged.  The guard below then fires with the confusing
# message "the <version> line did not change", which is exactly what happened
# the first time this script was run.
$xmlPattern = '<version>\s*' + [regex]::Escape($current) + '\s*</version>'
$newXml = $xmlText -replace $xmlPattern, "<version>$target</version>"
if ($newXml -eq $xmlText) { throw "plugin.xml: the <version> line did not change (it says $current)" }
Set-Content -LiteralPath $pluginXml -Value $newXml -NoNewline -Encoding utf8

# Edit 2: build.gradle.kts.  `sinceBuild` is deliberately NOT touched: it describes
# which platforms the plugin loads on, not which release this is.
$gradleText = Get-Content -LiteralPath $gradleKts -Raw
$gm = [regex]::Match($gradleText, 'version\s*=\s*"([^"]+)"')
if (-not $gm.Success) { throw 'build.gradle.kts has no version = "..." line' }
$gradleOld = $gm.Groups[1].Value
$newGradle = $gradleText -replace ('version\s*=\s*"' + [regex]::Escape($gradleOld) + '"'), "version = `"$target`""
if ($newGradle -eq $gradleText) { throw "build.gradle.kts: the version line did not change (it says $gradleOld)" }
Set-Content -LiteralPath $gradleKts -Value $newGradle -NoNewline -Encoding utf8

Write-Host "Vela plugin version: $current -> $target"
Write-Host "  plugin.xml          <version>$target</version>          (source of truth)"
Write-Host "  build.gradle.kts    version = `"$target`"                (was $gradleOld)"
Write-Host "  build-offline.ps1   derives the zip name from plugin.xml, nothing to edit"
Write-Host "  CHANGELOG.md        section "## $target" present"
Write-Host ''
Write-Host 'Next: build the artifact and let the verifier check the four places agree:'
Write-Host '  powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1'
