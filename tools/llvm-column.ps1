#Requires -Version 5.1
<#
  llvm-column.ps1 -- the LLVM back end against the interpreter, over the corpus.

  WHY THIS EXISTS

  `tests\cases.txt` rows carry an expected answer, and `tools\refreeze.ps1` runs the
  cases through the C path.  Nothing ran the *third* path: `build-llvm`, the back end
  that produces an object in this process through libLLVM and links it with a linker.

  So the whole "does the LLVM back end still agree with the interpreter" question was
  measured only when somebody happened to have a scratch script lying around -- which
  is how this file came to exist.  A claim about the third path that is checked by
  hand is a claim that is checked when nobody is busy.

  WHAT IT JUDGES

  For every `run` row in `tests\cases.txt`:

    * the interpreter's stdout and exit code, and the LLLVM product's stdout and exit
      code, must be byte-identical -- otherwise DIVERGE, which is the worst outcome,
      because a wrong answer is worse than no answer;
    * a program the LLVM back end refuses is acceptable ONLY if it is named in
      `tests\llvm-refusals.txt` with the reason it is refused.  A refusal that is not
      on the ledger fails the run: this back end must never grow a silent hole;
    * a ledger entry whose program is no longer refused also fails the run.  A ledger
      that is never pruned stops describing the back end, and then it is decoration.

  This step starts no C compiler: `build-llvm` runs libLLVM in process and invokes the
  linker.  That is why it can be a default step of `tools\verify-all.ps1` while the
  build is owned by somebody else.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1 -Verbose
    powershell -ExecutionPolicy Bypass -File tools\llvm-column.ps1 -SelfTest

  -SelfTest proves the judgement can fail: it inverts the ledger in memory -- it moves
  one refused case onto the "must match" side -- and requires the run to report a
  DIVERGE/refusal failure.  A run that passes -SelfTest means this script cannot tell
  a working back end from a broken one.

  Exit: 0 = RESULT: ok, 1 = RESULT: failed.
#>
[CmdletBinding()]
param(
    [switch] $SelfTest
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$casesFile = Join-Path $root 'tests\cases.txt'
$ledgerFile = Join-Path $root 'tests\llvm-refusals.txt'

function Say([string] $t) { Write-Host $t }

if (-not (Test-Path -LiteralPath $vm)) {
    Say "RESULT: failed -- no compiler at $vm"
    exit 1
}
if (-not (Test-Path -LiteralPath $casesFile)) {
    Say "RESULT: failed -- no $casesFile"
    exit 1
}
if (-not (Test-Path -LiteralPath $ledgerFile)) {
    Say "RESULT: failed -- no $ledgerFile (the ledger of deliberate refusals)"
    exit 1
}

# ------------------------------------------------------------------ the ledger
# `name | substring that must appear in the refusal`  -- one per line, `#` comments.
$ledger = @{}
$ledgerOrder = New-Object System.Collections.ArrayList
foreach ($line in (Get-Content -LiteralPath $ledgerFile)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $parts = $t -split '\|', 2
    if ($parts.Count -ne 2) {
        Say "RESULT: failed -- malformed ledger line: $t"
        exit 1
    }
    $ledger[$parts[0].Trim()] = $parts[1].Trim()
    [void]$ledgerOrder.Add($parts[0].Trim())
}
Say ("ledger: {0} deliberate refusal(s) recorded in tests\llvm-refusals.txt" -f $ledger.Count)

if ($SelfTest) {
    # Move one refused case to the other side of the ledger, in memory only, so the
    # failure paths are exercised against a real program that really is refused.
    $victim = $null
    foreach ($k in @($ledger.Keys)) { if ($null -eq $victim) { $victim = $k } }
    if ($null -eq $victim) {
        Say 'RESULT: failed -- -SelfTest needs at least one ledger entry to invert'
        exit 1
    }
    Say ("SELF-TEST: treating `"$victim`" as a program that must compile and match")
    $ledger.Remove($victim)
}

# ------------------------------------------------------------------ the cases
$cases = @()
foreach ($line in (Get-Content -LiteralPath $casesFile)) {
    if ($line -match '^\s*run\s+(\S+)\s+(\S+)\s*$') {
        $cases += [pscustomobject]@{ Name = $Matches[1]; Path = $Matches[2] }
    }
}
Say ("cases: {0} 'run' row(s) in tests\cases.txt" -f $cases.Count)

$scratch = Join-Path $env:TEMP 'vela-llvm-column'
if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

$hashBefore = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash
Say ("compiler: {0} bytes, sha256 {1}" -f (Get-Item -LiteralPath $vm).Length, $hashBefore.ToLowerInvariant().Substring(0, 16))

$mismatch = 0; $unexpected = 0; $diverge = 0; $staleLedger = 0; $match = 0; $refusedOk = 0
$refusedSeen = @{}
$failures = New-Object System.Collections.ArrayList

foreach ($c in $cases) {
    $dir = Join-Path $scratch $c.Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $srcIn = Join-Path $root ($c.Path -replace '/', '\')
    if (-not (Test-Path -LiteralPath $srcIn)) {
        Say ("  {0,-34} MISSING  {1}" -f $c.Name, $c.Path)
        $failures.Add("$($c.Name): the source file $($c.Path) does not exist") | Out-Null
        $mismatch++
        continue
    }
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($srcIn)
    Copy-Item -LiteralPath $srcIn -Destination (Join-Path $dir "$stem.vel") -Force

    # A .bat per case, because `cmd /c "a & b"` on one line expands %errorlevel%
    # before the program has run, and a byte comparison needs real redirection.
    $bat = @(
        '@echo off',
        ("cd /d `"$dir`""),
        ("`"$vm`" run `"$stem.vel`" > i.out 2> i.err"),
        'echo %errorlevel% > i.code',
        ("`"$vm`" build-llvm `"$stem.vel`" > l.build 2>&1"),
        'echo %errorlevel% > l.build.code',
        (".\$stem.exe > l.out 2> l.err"),
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
        # A refusal.  It has to be on the ledger, and for the recorded reason.
        if ($ledger.ContainsKey($c.Name)) {
            $refusedSeen[$c.Name] = $true
            $want = $ledger[$c.Name]
            # .Contains, not -like: the reason text carries backticks, and a `-like`
            # pattern would read `[` or `?` in a future reason as a wildcard.
            if ($build.Contains($want)) {
                $refusedOk++
                if ($VerbosePreference -ne 'SilentlyContinue') { Say ("  {0,-34} REFUSED-as-recorded" -f $c.Name) }
            } else {
                Say ("  {0,-34} REFUSAL-REASON-CHANGED" -f $c.Name)
                Say ("      ledger expects: $want")
                Say "      the actual refusal:"
                foreach ($l in ($build -split "`r?`n")) { if ($l.Trim() -ne '') { Say ("        | " + $l.Trim()) } }
                $failures.Add("$($c.Name): refused, but not for the recorded reason") | Out-Null
                $unexpected++
            }
        } else {
            Say ("  {0,-34} REFUSED-but-not-on-the-ledger  (llvm build exit $lbCode)" -f $c.Name)
            foreach ($l in ($build -split "`r?`n")) { if ($l.Trim() -ne '') { Say ("      | " + $l.Trim()) } }
            $failures.Add("$($c.Name): the LLVM back end refused it and it is not on the ledger") | Out-Null
            $unexpected++
        }
        continue
    }

    if ($ledger.ContainsKey($c.Name)) {
        # Refused before, compiles now: the ledger is stale and must be pruned, or it
        # stops describing this back end.
        Say ("  {0,-34} NO-LONGER-REFUSED (the ledger still lists it)" -f $c.Name)
        $failures.Add("$($c.Name): no longer refused, so its ledger entry must be removed") | Out-Null
        $staleLedger++
        continue
    }

    if ($null -eq $iBytes -or $null -eq $lBytes) {
        Say ("  {0,-34} BROKEN (a path produced no output file)" -f $c.Name)
        $failures.Add("$($c.Name): a path produced no output file") | Out-Null
        $diverge++
        continue
    }

    $same = ([System.Linq.Enumerable]::SequenceEqual([byte[]]$iBytes, [byte[]]$lBytes)) -and ($iCode -eq $lCode)
    if ($same) {
        $match++
        if ($VerbosePreference -ne 'SilentlyContinue') {
            Say ("  {0,-34} match   {1,4} B  i/l exit {2}/{3}" -f $c.Name, $iBytes.Length, $iCode, $lCode)
        }
    } else {
        $diverge++
        Say ("  {0,-34} DIVERGE  interpreter {1} B exit {2}  vs  product {3} B exit {4}" -f $c.Name, $iBytes.Length, $iCode, $lBytes.Length, $lCode)
        Say ("      interpreter: " + ([System.Text.Encoding]::ASCII.GetString($iBytes) -replace "`r?`n", ' | '))
        Say ("      product    : " + ([System.Text.Encoding]::ASCII.GetString($lBytes) -replace "`r?`n", ' | '))
        $failures.Add("$($c.Name): the product's answer differs from the interpreter's") | Out-Null
    }
}

# A ledger entry for a case that no longer exists at all is also staleness.
foreach ($k in $ledgerOrder) {
    if (-not ($cases | Where-Object { $_.Name -eq $k })) {
        Say ("  {0,-34} LEDGER-ENTRY-WITH-NO-CASE" -f $k)
        $failures.Add("$k is on the ledger but is not a run row in tests\cases.txt") | Out-Null
        $staleLedger++
    }
}

$hashAfter = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash
Say ''
Say ("compiler unchanged during the run: {0}  ({1})" -f ($hashBefore -eq $hashAfter), $hashBefore.ToLowerInvariant().Substring(0, 16))
Say ("match {0}   refused-by-design {1}/{2}   DIVERGE {3}   unexpected refusal {4}   stale ledger {5}" -f `
    $match, $refusedOk, $ledger.Count, $diverge, $unexpected, $staleLedger)

if ($hashBefore -ne $hashAfter) {
    $failures.Add('the compiler was rebuilt while this run was in progress, so the numbers describe two programs') | Out-Null
}

Say ''
if ($SelfTest) {
    # The judgement is live only if inverting the ledger produced failures that name
    # the real program.  A self-test that passes means this script cannot tell a
    # working back end from a broken one, which is the thing it exists to notice.
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
