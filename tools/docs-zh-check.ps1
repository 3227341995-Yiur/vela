<#
.SYNOPSIS
    Check every Chinese translation in this repository against the English file it
    records.

.DESCRIPTION
    A Chinese file here is a translation of **one pinned revision** of **one**
    English file, and it says which one in a machine-checkable header.

    Every bilingual pair in this repository carries the same four-line preamble,
    and the checker enforces it rather than merely tolerating it, because the
    whole point of it is what a reader sees first on GitHub: the **title**:

        line 1  # <title>                       <- the title, always
        line 2  (blank)
        line 3  **English** | [<zh-label>](X.zh-CN.md)   <- or, in X.zh-CN.md:
        line 3  [English](X.md) | **<zh-label>**

    `<zh-label>` is the four Chinese characters that name the language.  They are
    spelled here as a placeholder on purpose: this script is UTF-8 with no BOM, and
    Windows PowerShell 5.1 reads a script file as ANSI unless it has one, so a
    literal Chinese word in this file would arrive as mojibake and silently match
    nothing.  The script builds the label from code points instead.
        line 4  (blank)
        line 5  <!--                          <- provenance, Chinese files only
                <source label>      : X.md
                <size label>        : 31824
                <sha256 label>      : 19e33d20...
                <date label>        : 2026-09-20
                <rule label>        : ...
                -->
        then    <body>

    The provenance block lives *below* the switcher on purpose: above it, the
    first thing a reader sees is an invisible comment and the document looks
    untitled.

    This script finds every `*.zh-CN.md` under the repository root, reads the
    recorded English file name and SHA-256 out of that header, recomputes the
    English file's SHA-256, and prints one line per pair:

        ok     README.md            -> README.zh-CN.md
        STALE  STATUS.md            -> STATUS.zh-CN.md   (recorded a1b2..., now 9f8e...)

    Four things are failures, and each is named rather than skipped, because a
    checker that silently skips what it cannot parse is not a checker:

      * the English source does not exist;
      * the layout is wrong: line 1 is not a `# ` title of the English file, line 3
        is not that file's language switcher, or a Chinese file's line 1 is not its
        own `# ` title;
      * the header is missing (no `<!--` block below the switcher) or unparseable
        (the block is never closed, or it carries no file name / no 64-hex SHA-256);
      * the header names a file whose stem is not this file's stem (`X.zh-CN.md`
        must record `X.md`).

    A fifth check is a **denylist** of retracted claim strings (see
    `$retractedClaims` below): a `*.zh-CN.md` that still states a figure the
    English file has explicitly withdrawn, as though it were current, is a failure.

    The recorded byte size is compared too: a size that disagrees is a stale
    translation even in the rare case where the hash does not.

    ## What `ok` does NOT mean — read this before trusting a green run

    **A green pair means one thing: the English source has not changed since this
    translation was recorded.** It does NOT mean the translation is faithful. This
    script compares a hash and a byte count of the *English* file; it never reads
    the Chinese body for meaning, and no number of hashes can make it do so.

    That distinction has already bitten this repository once, which is why it is
    written here rather than left implied: a round refreshed the recorded SHA-256
    on a Chinese file whose body still asserted the withdrawn `~17%` parallel
    figure and the withdrawn claim that serial matmul wins — figures the English
    had retracted — and every pair went green while the Chinese went on stating a
    withdrawn measurement as current truth. Re-pinning is the *cheap* half of
    keeping a translation honest, and on its own it launders a stale body into a
    green line.

    The denylist above is the machine guard for exactly that class of drift, and it
    is deliberately narrow: it lists whole retracted **strings**, not numbers and not
    a numeric-multiset rule (a multiset rule was measured as noise — Chinese prose
    legitimately carries different digit groupings from English). A Chinese file
    that *quotes* a retraction in order to record it is fine, because the guard
    looks for the withdrawn claim as the file's own assertion, which is what the
    patterns below spell.

    The rest is a human reading the pair. The hash tells you whether that reading has
    to happen again; it does not tell you what the reading will find.

    Exit status is 0 only when every pair is `ok`, and the run ends with a
    `RESULT:` line named after the **worst** verdict in the run, so a caller that
    reads only the last line is not misled by a mild word:

        RESULT: ok       every pair matched
        RESULT: stale    at least one recorded SHA-256 no longer matches
        RESULT: failed   at least one pair could not be checked at all (missing
                         source, missing or unparseable header, a header naming a
                         different stem, a retracted claim stated as current, or a
                         sweep that checked nothing)

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

.PARAMETER ListLayout
    Print line 1 and line 3 of every bilingual pair (both sides) and stop.  This
    is the reader-facing half of the check made visible: it is what the owner
    looked at when the title was missing on GitHub, and it prints the pair
    arithmetic too, so a bilingual document whose twin was never written shows up
    here rather than at the moment someone notices it on the web.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 -Only DESIGN.zh-CN.md

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 -ListLayout
#>
[CmdletBinding()]
param(
    [string[]] $Only = @('*.zh-CN.md'),
    [switch] $ListLayout
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
#
# `.wt\` belongs here because a `git worktree` of this repository lives *inside* it
# (`git worktree add .wt\enums`), and a worktree is another agent's tree in the middle
# of being edited.  Measured 2026-09-24: with two worktrees present this sweep found 51
# pairs where the tree itself has 34, and a twin an agent had not re-pinned yet would
# have turned *this* gate red for work that was not in the commit being judged.  The
# gate is supposed to judge the tree it is run in; a verdict that depends on another
# tree's uncommitted state is not a verdict.
$skipDirPattern = '\\(\.work|\.git|\.wt)\\'
$skipBuildPattern = '\\idea-plugin\\build\\'

# The Chinese label for the language, built from code points because this script
# is read as ANSI by Windows PowerShell 5.1 (see the note in .DESCRIPTION).  Every
# layout comparison below uses this, never a literal.
$zhLabel = ([char]0x7B80) + ([char]0x4F53) + ([char]0x4E2D) + ([char]0x6587)

# --------------------------------------------------------- retracted claims
# Whole strings the English side has explicitly withdrawn, which a Chinese file
# must not still be asserting.  This is deliberately NOT a number rule: Chinese
# prose legitimately regroups digits, so a numeric multiset check was measured as
# noise.  Each entry is a *claim*, spelled the way a stale translation spelled it.
#
# What is NOT listed, on purpose: the *retraction notes themselves*.  A sentence
# that says "this used to claim ~17% slower, which was wrong" is the record of the
# correction and must stay; and `~0.04 s` is not listed at all, because a note
# recording that it was withdrawn from `bench/RESULTS.md`'s ladder is legitimate
# prose.  The guard is for a file that states the withdrawn figure as *today's
# measurement*, so only the bare assertions are listed.  Built from code points,
# for the same ANSI reason as $zhLabel.
$retractedClaims = @(
    @{ Name = 'parallel matmul "~17% behind" (retracted: 3.1x slower, 0.0680 vs 0.0221)'
       Pattern = ([char]0x843D) + ([char]0x540E) + ([char]0x7EA6) + ' 17%' }
    @{ Name = 'serial matmul stated as faster than C++ (retracted: 2.4x slower, 0.5433 vs 0.2241)'
       Pattern = ([char]0x4E32) + ([char]0x884C) + ' matmul ' + ([char]0x6BD4) + ([char]0x4EFB) + ([char]0x4F55) + ([char]0x4E00) + ([char]0x79CD) + ' C++ ' + ([char]0x4E32) + ([char]0x884C) + ([char]0x6784) + ([char]0x5EFA) + ([char]0x90FD) + ([char]0x5FEB) }
)

function Get-Text([string] $path) {
    # Read as UTF-8 explicitly: the files are written without a BOM on purpose
    # (this host reads `git log` on code page 936), so a default read can only
    # be relied on for the ASCII parts of the header.
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    if ($text.Length -gt 0 -and [int] $text[0] -eq 0xFEFF) { $text = $text.Substring(1) }
    return $text
}

function Wanted-Switcher([string] $stem, [bool] $chinese) {
    # The exact line 3 this repository's convention requires.  Compared with
    # -ceq (ordinal), because a switcher is machine-readable punctuation, not
    # prose, and a case-insensitive compare would accept `**english**`.
    if ($chinese) { return ('[English](' + $stem + '.md) | **' + $zhLabel + '**') }
    return ('**English** | [' + $zhLabel + '](' + $stem + '.zh-CN.md)')
}

function Get-Short([string] $s) {
    $s = $s.Trim()
    if ($s.Length -gt 60) { return $s.Substring(0, 60) + '...' }
    return $s
}

function Check-Layout([string] $path, [string] $stem, [bool] $chinese) {
    # Returns $null when the four-line preamble is what this repository requires,
    # or a one-line reason.  This is a failure, not a warning: the preamble is the
    # only thing a reader sees before deciding whether to open the document.
    $lines = (Get-Text $path) -split "`r?`n"
    if ($lines.Count -lt 5) { return "layout: only $($lines.Count) line(s), expected a title, a switcher and a body" }
    if (-not $lines[0].StartsWith('# ')) {
        return "layout: line 1 is not a '# ' title (found: $((Get-Short $lines[0])))"
    }
    if ($lines[1].Trim() -ne '') { return 'layout: line 2 is not blank' }
    $want = Wanted-Switcher $stem $chinese
    if ($lines[2].TrimEnd() -cne $want) {
        return "layout: line 3 is not this file's language switcher (found: $((Get-Short $lines[2])); wanted: $((Get-Short $want)))"
    }
    if ($lines[3].Trim() -ne '') { return 'layout: line 4 is not blank' }
    return $null
}

function Read-Header([string] $path) {
    # Returns @{ Source = ...; Size = ...; Hash = ...; Error = ... }.
    # `Error` set means the header is missing or unparseable, and the caller
    # reports it as a failure.
    $text = Get-Text $path
    $lines = $text -split "`r?`n"

    # The provenance block sits BELOW the title and the switcher: line 1 is the
    # title, line 3 is the switcher, line 4 is blank, so `<!--` is line 5.  Look
    # in the first few lines rather than insisting on line 5 -- but do not go
    # hunting through the whole document, or a file whose header is missing would
    # be "checked" against some later comment.
    $start = -1
    $scan = [Math]::Min($lines.Count, 12)
    for ($i = 0; $i -lt $scan; $i++) {
        if ($lines[$i].Trim() -eq '<!--') { $start = $i; break }
    }
    if ($start -lt 0) {
        return @{ Error = 'header missing: no <!-- block in the first 12 lines' }
    }
    $end = -1
    $limit = [Math]::Min($lines.Count, $start + 40)
    for ($i = $start + 1; $i -lt $limit; $i++) {
        if ($lines[$i].Trim() -eq '-->') { $end = $i; break }
    }
    if ($end -lt 0) {
        return @{ Error = 'header unparseable: the <!-- block is not closed in its first 40 lines' }
    }

    $src = $null; $hash = $null; $size = $null
    for ($i = $start + 1; $i -lt $end; $i++) {
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
    if ($full -match $skipDirPattern)     { $skipped += [pscustomobject]@{ Path = $full; Why = 'under .work\, .git\ or .wt\' }; continue }
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

# ------------------------------------------------------- the layout, listed
if ($ListLayout) {
    # Every `*.md` that has a twin, plus every `*.md` that does not.  Printed as
    # line 1 and line 3 so a reader can see the thing the owner was looking at.
    $allMd = @(Get-ChildItem -LiteralPath $repo -Recurse -File -Filter '*.md' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch $skipDirPattern -and $_.FullName -notmatch $skipBuildPattern } |
        Sort-Object -Property FullName)
    $pairsFound = 0; $orphans = 0; $badLine1 = 0
    foreach ($f in $allMd) {
        $rel = $f.FullName.Substring($repo.Length + 1)
        $isZh = $f.Name.EndsWith($suffix)
        $stem = if ($isZh) { $f.Name.Substring(0, $f.Name.Length - $suffix.Length) } else { [System.IO.Path]::GetFileNameWithoutExtension($f.Name) }
        $twinName = if ($isZh) { $stem + '.md' } else { $stem + $suffix }
        $twin = Join-Path $f.DirectoryName $twinName
        $lines = (Get-Text $f.FullName) -split "`r?`n"
        $one = if ($lines.Count -ge 1) { $lines[0] } else { '' }
        $three = if ($lines.Count -ge 3) { $lines[2] } else { '(no line 3)' }
        $ok1 = $one.StartsWith('# ')
        if (-not $ok1) { $badLine1++ }
        if (Test-Path -LiteralPath $twin) { $pairsFound++ } else { $orphans++ }
        Write-Host ("{0} {1,-40}" -f $(if ($ok1) { 'L1 ok ' } else { 'L1 BAD' }), $rel)
        Write-Host ("        line 1: {0}" -f (Get-Short $one))
        Write-Host ("        line 3: {0}{1}" -f (Get-Short $three), $(if (Test-Path -LiteralPath $twin) { '' } else { '   <- no twin on disk: ' + $twinName }))
    }
    Write-Host ''
    Write-Host ("md files: $($allMd.Count)   with a twin: $pairsFound   without a twin: $orphans   line 1 not a '# ' title: $badLine1")
    if ($badLine1 -gt 0) { Write-Host 'RESULT: failed'; exit 1 }
    Write-Host 'RESULT: ok'
    exit 0
}

# ----------------------------------------------------------------- the pairs

$okCount = 0; $staleCount = 0; $failCount = 0

foreach ($f in $checked) {
    $zhRel = $f.FullName.Substring($repo.Length + 1)
    $stem = $f.Name.Substring(0, $f.Name.Length - $suffix.Length)
    $srcName = $stem + '.md'
    $srcPath = Join-Path $f.DirectoryName $srcName

    # --- the preamble, checked before anything else -------------------------
    # The Chinese file: line 1 is its own title, line 3 its switcher.
    $layoutError = Check-Layout $f.FullName $stem $true
    if ($null -ne $layoutError) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   ({3})" -f 'FAIL', $srcName, $zhRel, $layoutError) -ForegroundColor Red
        continue
    }
    if (-not (Test-Path -LiteralPath $srcPath)) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   (english source not found beside it)" -f 'FAIL', $srcName, $zhRel) -ForegroundColor Red
        continue
    }
    # The English file: line 1 is its title, line 3 its switcher pointing back.
    $layoutError = Check-Layout $srcPath $stem $false
    if ($null -ne $layoutError) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   (english side -- {3})" -f 'FAIL', $srcName, $zhRel, $layoutError) -ForegroundColor Red
        continue
    }

    # --- the recorded provenance -------------------------------------------
    $hdr = Read-Header $f.FullName

    if ($null -ne $hdr.Error) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   ({3})" -f 'FAIL', '?', $zhRel, $hdr.Error) -ForegroundColor Red
        continue
    }

    $srcName = $hdr.Source
    if ($srcName -ne ($stem + '.md')) {
        $failCount++
        Write-Host ("{0,-7}{1,-21}-> {2}   (header names {3}, but this file's stem is {4})" -f 'FAIL', $srcName, $zhRel, $srcName, $stem) -ForegroundColor Red
        continue
    }

    $srcPath = Join-Path $f.DirectoryName $srcName

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

# ------------------------------------------------- a retracted claim, restated
# Runs over every Chinese file in the sweep, including ones whose recorded hash is
# current: a green pin says the source has not moved, not that the body is still
# true.  This is the guard for that gap.
$retractedCount = 0
$zhSweep = @(Get-ChildItem -LiteralPath $repo -Recurse -File -Filter ('*' + $suffix) -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $skipDirPattern -and $_.FullName -notmatch $skipBuildPattern })
foreach ($f in $zhSweep) {
    $zhRel = $f.FullName.Substring($repo.Length + 1)
    $lines = (Get-Text $f.FullName) -split "`r?`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($rc in $retractedClaims) {
            if ($lines[$i].Contains($rc.Pattern)) {
                $retractedCount++
                Write-Host ("{0,-7}{1,-21}-> {2}:{3}   (retracted claim still stated: {4})" -f 'FAIL', '?', $zhRel, ($i + 1), $rc.Name) -ForegroundColor Red
            }
        }
    }
}

# ------------------------------------------------------------------ the verdict

Write-Host ''
# A retracted claim is a failure, so it is added to the count the summary reports:
# a run whose summary said `failed: 0` while a FAIL line was on the screen would be
# the same "a checker that cannot fail" defect one layer up.
Write-Host ("pairs: $($checked.Count)   ok: $okCount   stale: $staleCount   failed: $($failCount + $retractedCount)")
if ($retractedCount -gt 0) {
    Write-Host ("retracted claims still stated as current in a *$suffix file: $retractedCount")
}

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
if ($retractedCount -gt 0) { $verdictOk = $false; $hardFail = $true }

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
