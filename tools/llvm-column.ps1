#Requires -Version 5.1
<#
  llvm-column.ps1 -- the LLVM back end against the interpreter, over two corpora.

  WHY THIS EXISTS

  `tests\cases.txt` rows carry an expected answer, and `tools\refreeze.ps1` runs the
  cases through the C path.  Nothing ran the *third* path: `build-llvm`, the back end
  that produces an object in this process through libLLVM and links it with a linker.
  So "does the LLVM back end still agree with the interpreter" was measured only when
  somebody happened to have a scratch script lying around -- which is where this file
  came from.  A claim about the third path that is checked by hand is a claim that is
  checked when nobody is busy.

  TWO CORPORA, AND WHY

  The 23 `run` rows of `tests\cases.txt` turned out to be narrow in a way that
  mattered: not one of them passes a `str` to a function, and the LLVM back end refuses
  exactly that.  A run over that corpus reported "no non-deliberate refusals", which
  was true of the corpus and false of the language.  So there is a second corpus --
  `tests\llvm-shapes\`, ten ordinary program shapes -- judged the same way, with its own
  ledger of the gaps that are still open.

  The two ledgers mean different things and are kept apart on purpose:

    * tests\llvm-refusals.txt  refusals that are PERMANENT AND INTENDED.  An entry
      there is supposed to stay; a program that stops being refused fails the run,
      because an unpruned ledger stops describing the back end.
    * tests\llvm-gaps.txt      refusals that are GAPS -- ordinary programs the
      interpreter and the C path compile today.  Every entry is work owed, and a gap
      that closes also fails the run, so the good news gets recorded by deleting the
      line rather than surviving as a claim that the back end cannot do what it can.

  For every program, in both corpora: the interpreter's stdout and exit code and the
  LLVM product's must be byte-identical.  DIVERGE is the worst outcome, because a wrong
  answer is worse than no answer.

  This step starts no C compiler: `build-llvm` runs libLLVM in process and invokes the
  linker.  That is why it can be a default step of `tools\verify-all.ps1` while the
  build slot is owned by somebody else.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1 -Verbose
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1 -SelfTest

  -SelfTest inverts the refusals ledger in memory and requires the run to fail.  A run
  that passes -SelfTest means this script cannot tell a working back end from a broken
  one.  Exit: 0 = RESULT: ok, 1 = RESULT: failed.
#>
[CmdletBinding()]
param(
    [switch] $SelfTest
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$casesFile = Join-Path $root 'tests\cases.txt'
$refusalLedgerFile = Join-Path $root 'tests\llvm-refusals.txt'
$gapLedgerFile = Join-Path $root 'tests\llvm-gaps.txt'
$shapeDir = Join-Path $root 'tests\llvm-shapes'

function Say([string] $t) { Write-Host $t }

foreach ($f in @($vm, $casesFile, $refusalLedgerFile, $gapLedgerFile)) {
    if (-not (Test-Path -LiteralPath $f)) {
        Say "RESULT: failed -- missing $f"
        exit 1
    }
}

# ------------------------------------------------------------------ the ledgers
function Read-Ledger([string] $path) {
    $table = @{}
    $order = New-Object System.Collections.ArrayList
    foreach ($line in (Get-Content -LiteralPath $path)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $parts = $t -split '\|', 2
        if ($parts.Count -ne 2) {
            Say "RESULT: failed -- malformed ledger line in $path`: $t"
            exit 1
        }
        $k = $parts[0].Trim()
        $table[$k] = $parts[1].Trim()
        [void]$order.Add($k)
    }
    return [pscustomobject]@{ Table = $table; Order = $order }
}

$refusals = Read-Ledger $refusalLedgerFile
$gaps = Read-Ledger $gapLedgerFile
Say ("ledger: {0} permanent refusal(s), {1} open gap(s)" -f $refusals.Table.Count, $gaps.Table.Count)

if ($SelfTest) {
    $victim = $null
    foreach ($k in @($refusals.Table.Keys)) { if ($null -eq $victim) { $victim = $k } }
    if ($null -eq $victim) {
        Say 'RESULT: failed -- -SelfTest needs at least one ledger entry to invert'
        exit 1
    }
    Say ("SELF-TEST: treating `"$victim`" as a program that must compile and match")
    $refusals.Table.Remove($victim)
}

$scratch = Join-Path $env:TEMP 'vela-llvm-column'
if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

$hashBefore = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash
Say ("compiler: {0} bytes, sha256 {1}" -f (Get-Item -LiteralPath $vm).Length, $hashBefore.ToLowerInvariant().Substring(0, 16))

$failures = New-Object System.Collections.ArrayList
$counts = @{ match = 0; refusedOk = 0; gapOpen = 0; unexpected = 0; diverge = 0; stale = 0; missing = 0 }

function Judge-One {
    param(
        [string] $Name,
        [string] $Source,
        [hashtable] $Ledger,
        [bool] $IsGapLedger,
        [string] $ScratchRoot
    )
    $dir = Join-Path $ScratchRoot $Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if (-not (Test-Path -LiteralPath $Source)) {
        Say ("  {0,-22} MISSING-SOURCE" -f $Name)
        $script:failures.Add("$Name`: the program $Source does not exist") | Out-Null
        $script:counts.missing++
        return
    }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $dir 'prog.vel') -Force

    # A .bat per program: `cmd /c "a & b"` on one line expands %errorlevel% before the
    # program has run, and a byte comparison needs real redirection.
    $bat = @(
        '@echo off',
        ("cd /d `"$dir`""),
        ("`"$vm`" run prog.vel > i.out 2> i.err"),
        'echo %errorlevel% > i.code',
        ("`"$vm`" build-llvm prog.vel > l.build 2>&1"),
        'echo %errorlevel% > l.build.code',
        '.\prog.exe > l.out 2> l.err',
        'echo %errorlevel% > l.code'
    )
    [System.IO.File]::WriteAllText((Join-Path $dir 'run.bat'), ($bat -join "`r`n") + "`r`n")
    & cmd /c (Join-Path $dir 'run.bat') | Out-Null

    $iBytes = $null; if (Test-Path -LiteralPath (Join-Path $dir 'i.out')) { $iBytes = [System.IO.File]::ReadAllBytes((Join-Path $dir 'i.out')) }
    $lBytes = $null; if (Test-Path -LiteralPath (Join-Path $dir 'l.out')) { $lBytes = [System.IO.File]::ReadAllBytes((Join-Path $dir 'l.out')) }
    $iCode = -999; if (Test-Path -LiteralPath (Join-Path $dir 'i.code')) { $iCode = [int]((Get-Content -LiteralPath (Join-Path $dir 'i.code') -Raw).Trim()) }
    $lCode = -999; if (Test-Path -LiteralPath (Join-Path $dir 'l.code')) { $lCode = [int]((Get-Content -LiteralPath (Join-Path $dir 'l.code') -Raw).Trim()) }
    $lbCode = -999; if (Test-Path -LiteralPath (Join-Path $dir 'l.build.code')) { $lbCode = [int]((Get-Content -LiteralPath (Join-Path $dir 'l.build.code') -Raw).Trim()) }
    $build = ''; if (Test-Path -LiteralPath (Join-Path $dir 'l.build')) { $build = [string](Get-Content -LiteralPath (Join-Path $dir 'l.build') -Raw) }

    if ($lbCode -ne 0) {
        if (-not $Ledger.ContainsKey($Name)) {
            Say ("  {0,-22} REFUSED-and-not-on-a-ledger  (llvm build exit {1})" -f $Name, $lbCode)
            foreach ($l in ($build -split "`r?`n")) { if ($l.Trim() -ne '') { Say ("      | " + $l.Trim()) } }
            $script:failures.Add("$Name`: the LLVM back end refused it and no ledger records it") | Out-Null
            $script:counts.unexpected++
            return
        }
        # .Contains, not -like: a reason carries backticks, and `-like` would read a
        # `[` or `?` in a future reason as a wildcard.
        if (-not $build.Contains($Ledger[$Name])) {
            Say ("  {0,-22} REFUSAL-REASON-CHANGED" -f $Name)
            Say ("      ledger expects: " + $Ledger[$Name])
            foreach ($l in ($build -split "`r?`n")) { if ($l.Trim() -ne '') { Say ("      | " + $l.Trim()) } }
            $script:failures.Add("$Name`: refused, but not for the recorded reason") | Out-Null
            $script:counts.unexpected++
            return
        }
        if ($IsGapLedger) {
            $script:counts.gapOpen++
            if ($VerbosePreference -ne 'SilentlyContinue') { Say ("  {0,-22} gap-still-open" -f $Name) }
        } else {
            $script:counts.refusedOk++
            if ($VerbosePreference -ne 'SilentlyContinue') { Say ("  {0,-22} refused-as-recorded" -f $Name) }
        }
        return
    }

    # It compiled where a ledger says it is refused.
    if ($Ledger.ContainsKey($Name)) {
        if ($IsGapLedger) {
            Say ("  {0,-22} GAP-CLOSED -- this program compiles now" -f $Name)
            $script:failures.Add("$Name`: no longer refused, so delete its line from tests\llvm-gaps.txt -- that is the record of the progress") | Out-Null
        } else {
            Say ("  {0,-22} NO-LONGER-REFUSED (the ledger still lists it)" -f $Name)
            $script:failures.Add("$Name`: no longer refused, so its ledger entry must be removed") | Out-Null
        }
        $script:counts.stale++
        return
    }

    if ($null -eq $iBytes -or $null -eq $lBytes) {
        Say ("  {0,-22} BROKEN (a path produced no output file)" -f $Name)
        $script:failures.Add("$Name`: a path produced no output file") | Out-Null
        $script:counts.diverge++
        return
    }

    $same = ([System.Linq.Enumerable]::SequenceEqual([byte[]]$iBytes, [byte[]]$lBytes)) -and ($iCode -eq $lCode)
    if ($same) {
        $script:counts.match++
        if ($VerbosePreference -ne 'SilentlyContinue') {
            Say ("  {0,-22} match   {1,4} B  i/l exit {2}/{3}" -f $Name, $iBytes.Length, $iCode, $lCode)
        }
    } else {
        $script:counts.diverge++
        Say ("  {0,-22} DIVERGE  interpreter {1} B exit {2}  vs  product {3} B exit {4}" -f $Name, $iBytes.Length, $iCode, $lBytes.Length, $lCode)
        Say ("      interpreter: " + ([System.Text.Encoding]::ASCII.GetString($iBytes) -replace "`r?`n", ' | '))
        Say ("      product    : " + ([System.Text.Encoding]::ASCII.GetString($lBytes) -replace "`r?`n", ' | '))
        $script:failures.Add("$Name`: the product's answer differs from the interpreter's") | Out-Null
    }
}

# --------------------------------------------------- corpus one: tests\cases.txt
$cases = @()
foreach ($line in (Get-Content -LiteralPath $casesFile)) {
    if ($line -match '^\s*run\s+(\S+)\s+(\S+)\s*$') {
        $cases += [pscustomobject]@{ Name = $Matches[1]; Path = $Matches[2] }
    }
}
Say ''
Say ("== the corpus: {0} 'run' row(s) from tests\cases.txt" -f $cases.Count)
foreach ($c in $cases) {
    Judge-One -Name $c.Name -Source (Join-Path $root ($c.Path -replace '/', '\')) `
              -Ledger $refusals.Table -IsGapLedger $false -ScratchRoot (Join-Path $scratch 'corpus')
}
foreach ($k in $refusals.Order) {
    if (-not ($cases | Where-Object { $_.Name -eq $k })) {
        Say ("  {0,-22} LEDGER-ENTRY-WITH-NO-CASE" -f $k)
        $failures.Add("$k is on tests\llvm-refusals.txt but is not a run row in tests\cases.txt") | Out-Null
        $counts.stale++
    }
}

# --------------------------------------------- corpus two: tests\llvm-shapes\
$shapes = @(Get-ChildItem -LiteralPath $shapeDir -Filter '*.vel' -File -ErrorAction SilentlyContinue)
Say ''
Say ("== the shapes: {0} program(s) from tests\llvm-shapes\" -f $shapes.Count)
foreach ($s in $shapes) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($s.Name)
    Judge-One -Name $name -Source $s.FullName -Ledger $gaps.Table -IsGapLedger $true `
              -ScratchRoot (Join-Path $scratch 'shapes')
}
foreach ($k in $gaps.Order) {
    if (-not ($shapes | Where-Object { [System.IO.Path]::GetFileNameWithoutExtension($_.Name) -eq $k })) {
        Say ("  {0,-22} GAP-ENTRY-WITH-NO-PROGRAM" -f $k)
        $failures.Add("$k is on tests\llvm-gaps.txt but tests\llvm-shapes\$k.vel does not exist") | Out-Null
        $counts.stale++
    }
}

$hashAfter = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash
Say ''
Say ("compiler unchanged during the run: {0}  ({1})" -f ($hashBefore -eq $hashAfter), $hashBefore.ToLowerInvariant().Substring(0, 16))
Say ("match {0}   refused-by-design {1}/{2}   open gaps {3}/{4}   DIVERGE {5}   unexpected refusal {6}   stale ledger {7}   missing {8}" -f `
    $counts.match, $counts.refusedOk, $refusals.Table.Count, $counts.gapOpen, $gaps.Table.Count,
    $counts.diverge, $counts.unexpected, $counts.stale, $counts.missing)

if ($hashBefore -ne $hashAfter) {
    $failures.Add('the compiler was rebuilt while this run was in progress, so the numbers describe two programs') | Out-Null
}
# An exit code of -1 from `build-llvm` is what a build that was interrupted by a
# rebuild-under-it looks like here; the hash check above cannot see a rebuild that
# produced byte-identical content.  Say so rather than letting it read as a refusal.
$odd = @($counts.unexpected + $counts.diverge)

Say ''
if ($SelfTest) {
    Say ("SELF-TEST: failures with the ledger inverted: {0}" -f $failures.Count)
    foreach ($f in $failures) { Say ("  - " + $f) }
    Say ''
    if ($failures.Count -gt 0) {
        Say 'SELFTEST RESULT: the judgement is live (inverting the ledger failed the run)'
        exit 0
    }
    Say 'SELFTEST RESULT: BLIND -- inverting the ledger changed nothing'
    exit 1
}

if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok'
exit 0
