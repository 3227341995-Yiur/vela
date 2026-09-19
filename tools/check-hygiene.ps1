# tools\check-hygiene.ps1 -- refuse a checkout that is about to push something
# enormous, or that has already started tracking one.
#
#     powershell -ExecutionPolicy Bypass -File tools\check-hygiene.ps1 [-MaxMB 5]
#
# Why this exists, measured: `tools\build.ps1` copies `LLVM-C.dll` (74,159,616 B)
# beside the compiler, because the compiler is what needs it at run time — that is
# the plan's step 4 and it is deliberate.  What was *not* deliberate is that
# `.gitignore` covered `*.obj`, `*.exe`, `*.lib`, `*.pdb` and `*.ilk` and had **no
# rule for `*.dll`**, so the moment that copy appeared `git status` showed it as
# `?? selfhost/build/LLVM-C.dll` and one `git add -A` would have committed it.  A
# file that size is in the history of every clone from then on, forever, and
# deleting it afterwards does not remove it.  The repository is public.
#
# So the rule is checked, not remembered.  Both halves ask git rather than
# guessing at patterns, because a pattern that looks right and does not match is
# exactly how the hole got here:
#
#   * no **untracked** file over the limit that git does not already ignore;
#   * no **tracked** file over the limit at all — a tracked one is a mistake that
#     is already in history, and no ignore rule can undo that.
#
# Exit 0 with `RESULT: ok`, or 1 with every offending path named and sized.

param([int] $MaxMB = 5)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$limit = [int64]$MaxMB * 1MB

function Say($t) { Write-Host $t }

Write-Host "Vela hygiene check -- $root"
Write-Host ("  limit: {0} MB" -f $MaxMB)

$git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
if (-not $git) { $git = 'C:\Program Files\Git\cmd\git.exe' }
if (-not (Test-Path -LiteralPath $git)) {
    Say '  no git.exe found: this check cannot answer its question, which is not the same as passing it'
    Say 'RESULT: no git'
    exit 2
}

$bad = New-Object System.Collections.Generic.List[string]

# ------------------------------------------------------- 1. untracked and big
Say ''
Say '[1/2] untracked files over the limit that git does not ignore'
$untracked = & $git status --porcelain --untracked-files=all 2>$null | Where-Object { $_ -match '^\?\? ' }
$checked = 0
foreach ($line in $untracked) {
    $rel = $line.Substring(3).Trim('"')
    if (-not (Test-Path -LiteralPath $rel -PathType Leaf)) { continue }
    $len = (Get-Item -LiteralPath $rel).Length
    if ($len -le $limit) { continue }
    $checked += 1
    & $git check-ignore -q -- $rel 2>$null
    if ($LASTEXITCODE -eq 0) {
        Say ("      ignored, so it cannot be committed by accident: " + $rel)
    } else {
        Say ("  FAIL  {0,12:N0} B  not ignored: {1}" -f $len, $rel)
        $bad.Add(("{0,12:N0} B untracked and NOT ignored: {1}" -f $len, $rel))
    }
}
if ($checked -eq 0) { Say '      none' }

# --------------------------------------------------------- 2. tracked and big
Say ''
Say '[2/2] tracked files over the limit'
$bigTracked = & $git ls-tree -r -l HEAD 2>$null | ForEach-Object {
    $p = $_ -split '\s+', 5
    [pscustomobject]@{ Size = [int]$p[3]; Path = $p[4] }
} | Where-Object { $_.Size -gt $limit }
if ($bigTracked) {
    foreach ($t in $bigTracked) {
        Say ("  FAIL  {0,12:N0} B  tracked: {1}" -f $t.Size, $t.Path)
        $bad.Add(("{0,12:N0} B tracked in HEAD: {1}" -f $t.Size, $t.Path))
    }
} else {
    Say '      none'
}

Say ''
Say '============================================================'
foreach ($b in $bad) { Say ('  ' + $b) }
if ($bad.Count -eq 0) {
    Say 'RESULT: ok'
    exit 0
}
Say ("RESULT: {0} file(s) that must not be committed" -f $bad.Count)
exit 1
