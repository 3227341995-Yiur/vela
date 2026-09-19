<#
.SYNOPSIS
    Check every Chinese translation in this repository against the English file it
    records.

.DESCRIPTION
    A Chinese file here is a translation of **one pinned revision** of **one**
    English file, and it says which one in a machine-checkable header:

        <!--
        <source label>      : README.md
        <size label>        : 31824
        <sha256 label>      : 19e33d20...
        <date label>        : 2026-09-20
        <rule label>        : ...
        -->

    This script finds every `*.zh-CN.md` under the repository root, reads the
    recorded English file name and SHA-256 out of that header, recomputes the
    English file's SHA-256, and prints one line per pair:

        ok     README.md            -> README.zh-CN.md
        STALE  STATUS.md            -> STATUS.zh-CN.md   (recorded a1b2..., now 9f8e...)

    Three things are failures, and each is named rather than skipped, because a
    checker that silently skips what it cannot parse is not a checker:

      * the English source does not exist;
      * the header is missing (no `<!--` block at the top) or unparseable (the
        block is never closed, or it carries no file name / no 64-hex SHA-256);
      * the header names a file whose stem is not this file's stem (`X.zh-CN.md`
        must record `X.md`).

    The recorded byte size is compared too: a size that disagrees is a stale
    translation even in the rare case where the hash does not.

    Exit status is 0 only when every pair is `ok`, and the run ends with a
    `RESULT:` line named after the **worst** verdict in the run, so a caller that
    reads only the last line is not misled by a mild word:

        RESULT: ok       every pair matched
        RESULT: stale    at least one recorded SHA-256 no longer matches
        RESULT: failed   at least one pair could not be checked at all (missing
                         source, missing or unparseable header, a header naming a
                         different stem, or a sweep that checked nothing)

    `stale` is a translation that fell behind; `failed` is the checker itself
    unable to do its job, which is the louder of the two.

    Skipped on purpose, and counted out loud rather than dropped in silence:
    `.work\` (scratch another round left behind), `.git\` (the object store) and
    `idea-plugin\build\` (generated build output).

.PARAMETER Only
    A wildcard matched against the translation file's name; default `*.zh-CN.md`,
    i.e. the whole repository.  Narrowing it is for checking one owner's pairs
    (`-Only README.zh-CN.md,STATUS.zh-CN.md`); everything the sweep found but did
    not check is listed with that reason, so a narrow run cannot hide a pair.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 -Only DESIGN.zh-CN.md
#>
[CmdletBinding()]
param(
    [string[]] $Only = @('*.zh-CN.md')
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$suffix = '.zh-CN.md'

# `powershell -File script.ps1 -Only a,b` hands over one string, because -File
# passes what was typed rather than binding an array; split on commas so the two
# spellings mean the same thing.  An empty list falls back to the whole sweep, so
# a typo in -Only cannot leave the checker with nothing to do.
$patterns = @()
foreach ($o in $Only) {
    foreach ($piece in ($o -split ',')) {
        if ($piece.Trim() -ne '') { $patterns += $piece.Trim() }
    }
}
if ($patterns.Count -eq 0) { $patterns = @('*.zh-CN.md') }

# Directories whose `*.zh-CN.md` files are not translations of a document in the
# tree.  Named once, printed once, so the sweep's arithmetic adds up.
$skipDirPattern = '\\(\.work|\.git)\\'
$skipBuildPattern = '\\idea-plugin\\build\\'

function Get-Text([string] $path) {
    # Read as UTF-8 explicitly: the files are written without a BOM on purpose
    # (this host reads `git log` on code page 936), so a default read can only
    # be relied on for the ASCII parts of the header.
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

function Read-Header([string] $path) {
    # Returns @{ Source = ...; Size = ...; Hash = ...; Error = ... }.
    # `Error` set means the header is missing or unparseable, and the caller
    # reports it as a failure.
    $text = Get-Text $path
    if ($text.Length -gt 0 -and [int] $text[0] -eq 0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"
    if ($lines.Count -lt 2 -or $lines[0].Trim() -ne '<!--') {
        return @{ Error = 'header missing: the file does not begin with an <!-- block' }
    }
    $end = -1
    $limit = [Math]::Min($lines.Count, 40)
    for ($i = 1; $i -lt $limit; $i++) {
        if ($lines[$i].Trim() -eq '-->') { $end = $i; break }
    }
    if ($end -lt 0) {
        return @{ Error = 'header unparseable: the <!-- block is not closed in its first 40 lines' }
    }

    $src = $null; $hash = $null; $size = $null
    for ($i = 1; $i -lt $end; $i++) {
        $line = $lines[$i]
        if ($null -eq $hash) {
            $m = [regex]::Match($line, 'SHA256\s*:\s*([0-9a-fA-F]{64})')
            if ($m.Success) { $hash = $m.Groups[1].Value.ToLower(); continue }
        }
        if ($null -eq $src) {
            $m = [regex]::Match($line, ':\s*([A-Za-z0-9_.\-]+\.md)\s*$')
            if ($m.Success) { $src = $m.Groups[1].Value; continue }
        }
        if ($null -eq $size) {
            # The byte-size line is the only remaining line whose value is bare
            # digits; the date line is not (`2026-09-20` carries dashes), and the
            # SHA-256 line was consumed above.
            $m = [regex]::Match($line, ':\s*(\d+)\s*$')
            if ($m.Success) { $size = [int64] $m.Groups[1].Value }
        }
    }

    $missing = @()
    if ($null -eq $src)  { $missing += 'no English file name' }
    if ($null -eq $hash) { $missing += 'no 64-hex SHA256' }
    if ($null -eq $size) { $missing += 'no byte size' }
    if ($missing.Count -gt 0) {
        return @{ Error = ('header unparseable: ' + ($missing -join ', ')) }
    }
    return @{ Source = $src; Size = $size; Hash = $hash; Error = $null }
}

# ---------------------------------------------------------------- the sweep

$found = @(Get-ChildItem -LiteralPath $repo -Recurse -File -Filter ('*' + $suffix) -ErrorAction SilentlyContinue |
    Sort-Object -Property FullName)
$checked = @()
$skipped = @()

foreach ($f in $found) {
    $full = $f.FullName
    if ($full -match $skipDirPattern)     { $skipped += [pscustomobject]@{ Path = $full; Why = 'under .work\ or .git\' }; continue }
    if ($full -match $skipBuildPattern)   { $skipped += [pscustomobject]@{ Path = $full; Why = 'under idea-plugin\build\' }; continue }
    $matched = $false
    foreach ($pattern in $patterns) { if ($f.Name -like $pattern) { $matched = $true; break } }
    if (-not $matched)                    { $skipped += [pscustomobject]@{ Path = $full; Why = 'not matched by -Only' }; continue }
    $checked += $f
}

Write-Host "sweep: $repo"
Write-Host ("found $($found.Count) '*$suffix' file(s): $($checked.Count) checked, $($skipped.Count) not checked")
foreach ($s in $skipped) {
    Write-Host ("  not checked: {0}  ({1})" -f $s.Path.Substring($repo.Length + 1), $s.Why)
}
Write-Host ''

# ----------------------------------------------------------------- the pairs

$okCount = 0; $staleCount = 0; $failCount = 0

foreach ($f in $checked) {
    $zhRel = $f.FullName.Substring($repo.Length + 1)
    $hdr = Read-Header $f.FullName

    if ($null -ne $hdr.Error) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   ({3})" -f 'FAIL', '?', $zhRel, $hdr.Error) -ForegroundColor Red
        continue
    }

    $srcName = $hdr.Source
    $stem = $f.Name.Substring(0, $f.Name.Length - $suffix.Length)
    if ($srcName -ne ($stem + '.md')) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   (header names {3}, but this file's stem is {4})" -f 'FAIL', $srcName, $zhRel, $srcName, $stem) -ForegroundColor Red
        continue
    }

    $srcPath = Join-Path $f.DirectoryName $srcName
    if (-not (Test-Path -LiteralPath $srcPath)) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   (english source not found beside it)" -f 'FAIL', $srcName, $zhRel) -ForegroundColor Red
        continue
    }

    $nowHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $srcPath).Hash.ToLower()
    $nowSize = (Get-Item -LiteralPath $srcPath).Length

    if ($nowHash -eq $hdr.Hash -and $nowSize -eq $hdr.Size) {
        $okCount++
        Write-Host ("{0,-7}{1,-21}-> {2}" -f 'ok', $srcName, $zhRel)
        continue
    }

    $staleCount++
    $detail = "recorded $($hdr.Hash.Substring(0, 8))..., now $($nowHash.Substring(0, 8))..."
    if ($nowSize -ne $hdr.Size) { $detail += "; recorded $($hdr.Size) bytes, now $nowSize" }
    Write-Host ("{0,-7}{1,-21}-> {2}   ({3})" -f 'STALE', $srcName, $zhRel, $detail) -ForegroundColor Red
}

# ------------------------------------------------------------------ the verdict

Write-Host ''
Write-Host ("pairs: $($checked.Count)   ok: $okCount   stale: $staleCount   failed: $failCount")

$verdictOk = $true
$hardFail = $false

if ($checked.Count -eq 0) {
    # A sweep that checked nothing is not a green sweep: it is a checker that was
    # narrowed past every file it exists to check (`-Only` matched nothing), so
    # say so instead of printing the verdict of an empty run.
    Write-Host ("FAIL   (nothing checked)  no *$suffix file matched: " + ($patterns -join ', ')) -ForegroundColor Red
    $verdictOk = $false
    $hardFail = $true
}

if ($checked.Count -ne ($okCount + $staleCount + $failCount)) {
    # The same discipline gen-shim-decls.ps1 -Check applies to its own count: if a
    # pair can vanish between the sweep and the tally, say so instead of printing a
    # smaller number.
    Write-Host ("RECONCILE FAILED: $($checked.Count) pair(s) swept, $($okCount + $staleCount + $failCount) accounted for")
    $verdictOk = $false
    $hardFail = $true
}

if ($failCount -gt 0) { $verdictOk = $false; $hardFail = $true }
if ($staleCount -gt 0) { $verdictOk = $false }

if ($verdictOk) {
    Write-Host 'RESULT: ok'
    exit 0
}
# Name the worst verdict in the run: a reader who stops at the last line must not
# be told "merely stale" when a pair could not be checked at all.
if ($hardFail) {
    Write-Host 'RESULT: failed'
    exit 1
}
Write-Host 'RESULT: stale'
exit 1
