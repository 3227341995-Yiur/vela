#Requires -Version 5.1
<#
  threeway.ps1 -- the interpreter, the LLVM back end and the C back end, on one program.

  WHY THIS EXISTS

  `STATUS.md` says it in its own words: the three-way differential over the corpus is "not
  done as a tool on that date".  What the repository has instead is two *pairs* -- a `run` row
  compares the LLVM back end with the interpreter, and a `run-c` row compares the C back end
  with the interpreter -- and the third comparison, LLVM against C, is nobody's job.  A
  program can be green twice while the two compiled back ends disagree, and that is not
  hypothetical: `nested_fields` printed four lines on the interpreter and on C while the LLVM
  back end refused it over a packed kind, and `Array[S, N]` printed `1 1 / 1` on C before its
  element type was spelled from the packed kind.

  WHICH SIDES ARE CLAIMED: the corpus row says so, and this tool believes it

      run            llvm + interpreter
      run-c          c + interpreter
      native         c, compiled only -- the interpreter refuses such a program on purpose
      native-c       c, compiled only
      interpret      interpreter only

  A side a program does not claim is neither run nor judged.  `native` rows exist because the
  interpreter cannot reach a C library, so calling its refusal a defect there would be this
  tool measuring its own misunderstanding -- the mistake `llvm-column` made once by copying a
  single file and reporting seven modules as back-end refusals.

  WHAT IT JUDGES, among the sides a program claims

      MATCH                every claimed side that ran printed the same bytes
      DIVERGE              two claimed sides ran and printed different bytes -- a failure,
                           because one of them is answering a different question
      REFUSED-unrecorded   a claimed side refused and no ledger records it -- a failure: the
                           refusal is work nobody wrote down

  THE LEDGERS ARE TWO-SIDED, LIKE `llvm-gaps.txt`

      tests\llvm-gaps.txt              LLVM refusals that are gaps
      tests\llvm-refusals.txt          LLVM refusals that are permanent by design
      tests\c-refusals.txt             C refusals that are recorded
      tests\threeway-divergences.txt   divergences accepted for now, with the reason

  An entry that stops being refused FAILS the run, so closing a gap gets recorded by deleting
  its line instead of quietly becoming a hole.
#>
[CmdletBinding()]
param(
    [string] $Vm
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Vm) { $Vm = Join-Path $root 'selfhost\build\vm.exe' }

$casesFile = Join-Path $root 'tests\cases.txt'
$scratch = Join-Path $env:TEMP 'vela-threeway'
$llvmGapsFile = Join-Path $root 'tests\llvm-gaps.txt'
$llvmRefFile = Join-Path $root 'tests\llvm-refusals.txt'
$cRefFile = Join-Path $root 'tests\c-refusals.txt'
$divFile = Join-Path $root 'tests\threeway-divergences.txt'

$failures = New-Object System.Collections.ArrayList
$counts = @{ match = 0; diverge = 0; unexpected = 0; stale = 0; missing = 0; idle = 0 }

function Say([string] $s) { Write-Host $s }

function Read-Ledger([string] $path) {
    $table = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $table }
    foreach ($line in (Get-Content -LiteralPath $path)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $parts = $t -split '\|', 2
        if ($parts.Count -ne 2) { continue }
        $table[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $table
}

function Run-One([string] $exe, [string] $argLine, [string] $tag) {
    if (-not (Test-Path -LiteralPath $scratch)) { New-Item -ItemType Directory -Force -Path $scratch | Out-Null }
    $out = Join-Path $scratch ($tag + '.out')
    $err = Join-Path $scratch ($tag + '.err')
    Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue
    $cmd = 'cd /d "' + $root + '" && "' + $exe + '" ' + $argLine + ' > "' + $out + '" 2> "' + $err + '"'
    & cmd.exe /c $cmd | Out-Null
    $code = $LASTEXITCODE
    $bytes = if (Test-Path -LiteralPath $out) { [System.IO.File]::ReadAllBytes($out) } else { [byte[]]@() }
    $errText = if (Test-Path -LiteralPath $err) { [System.IO.File]::ReadAllText($err) } else { '' }
    return [pscustomobject]@{ Code = $code; Bytes = $bytes; Err = $errText }
}

$gaps = Read-Ledger $llvmGapsFile
$refusals = Read-Ledger $llvmRefFile
$cs = Read-Ledger $cRefFile
$diverge = Read-Ledger $divFile

$claims = @{}
$paths = @{}
foreach ($line in (Get-Content -LiteralPath $casesFile)) {
    if ($line -notmatch '^\s*(run|run-c|native|native-c|interpret)\s+(\S+)\s+(\S+)\s*$') { continue }
    $mode = $Matches[1]; $name = $Matches[2]; $path = $Matches[3]
    if (-not $claims.ContainsKey($name)) {
        $claims[$name] = @{ interp = $false; llvm = $false; c = $false }
        $paths[$name] = $path
    }
    switch ($mode) {
        'run'       { $claims[$name].llvm = $true;   $claims[$name].interp = $true }
        'run-c'     { $claims[$name].c = $true;      $claims[$name].interp = $true }
        'native'    { $claims[$name].llvm = $true }   # compiled by the LLVM path, never interpreted
        'native-c'  { $claims[$name].c = $true }    # compiled by the C path, never interpreted
        'interpret' { $claims[$name].interp = $true }
    }
}

Say ''
Say ("== three sides over {0} program(s) from tests\cases.txt" -f $claims.Count)
Say ("ledger: {0} LLVM gap(s), {1} permanent LLVM refusal(s), {2} C refusal(s), {3} accepted divergence(s)" -f `
     $gaps.Count, $refusals.Count, $cs.Count, $diverge.Count)
Say ''

$seen = @{}
foreach ($name in ($claims.Keys | Sort-Object)) {
    $src = Join-Path $root ($paths[$name] -replace '/', '\')
    if (-not (Test-Path -LiteralPath $src)) {
        Say ("  {0,-34} MISSING-SOURCE {1}" -f $name, $paths[$name])
        $failures.Add("$name`: $($paths[$name]) does not exist") | Out-Null
        $counts.missing++
        continue
    }
    $claim = $claims[$name]
    $problems = New-Object System.Collections.ArrayList
    $refused = @()
    $outputs = @()
    $ran = @()

    if ($claim.interp) {
        $r = Run-One $Vm ('run "' + $src + '"') ($name + '-i')
        if ($r.Code -eq 0) { $outputs += ,@('interpreter', $r.Bytes); $ran += 'interp' }
        else {
            $line1 = (($r.Err -split "`r?`n") | Where-Object { $_ -ne '' } | Select-Object -First 1)
            $problems.Add('the interpreter refused, and this row claims it: ' + $line1) | Out-Null
        }
    }

    if ($claim.llvm) {
        $b = Run-One $Vm ('build "' + $src + '"') ($name + '-lb')
        $exe = [System.IO.Path]::ChangeExtension($src, '.exe')
        $r = if ($b.Code -eq 0 -and (Test-Path -LiteralPath $exe)) { Run-One $exe '' ($name + '-l') } else { $null }
        Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
        if ($null -ne $r -and $r.Code -eq 0) { $outputs += ,@('llvm', $r.Bytes); $ran += 'llvm' }
        else {
            $line1 = (($b.Err -split "`r?`n") | Where-Object { $_ -match 'vela:' } | Select-Object -First 1)
            $rec = if ($gaps.ContainsKey($name)) { $gaps[$name] } elseif ($refusals.ContainsKey($name)) { $refusals[$name] } else { $null }
            if ($null -ne $rec) {
                $seen[$name + '|llvm'] = $true
                $refused += 'llvm'
                if ($null -ne $line1 -and -not $line1.Contains($rec)) {
                    $problems.Add('llvm refused, but not with the recorded text: ' + $line1) | Out-Null
                }
            } else {
                $problems.Add('llvm refused and no ledger records it: ' + $line1) | Out-Null
                $counts.unexpected++
            }
        }
    }

    if ($claim.c) {
        $b = Run-One $Vm ('build-c "' + $src + '"') ($name + '-cb')
        $exe = [System.IO.Path]::ChangeExtension($src, '.exe')
        $r = if ($b.Code -eq 0 -and (Test-Path -LiteralPath $exe)) { Run-One $exe '' ($name + '-c') } else { $null }
        Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ([System.IO.Path]::ChangeExtension($src, '.obj')) -Force -ErrorAction SilentlyContinue
        if ($null -ne $r -and $r.Code -eq 0) { $outputs += ,@('c', $r.Bytes); $ran += 'c' }
        else {
            $line1 = (($b.Err -split "`r?`n") | Where-Object { $_ -match 'vela:' } | Select-Object -First 1)
            if ($cs.ContainsKey($name)) {
                $seen[$name + '|c'] = $true
                $refused += 'c'
                if ($null -ne $line1 -and -not $line1.Contains($cs[$name])) {
                    $problems.Add('c refused, but not with the recorded text: ' + $line1) | Out-Null
                }
            } else {
                $problems.Add('c refused and no ledger records it: ' + $line1) | Out-Null
                $counts.unexpected++
            }
        }
    }

    if ($outputs.Count -ge 2) {
        $a64 = [System.Convert]::ToBase64String($outputs[0][1])
        foreach ($o in $outputs) {
            if ([System.Convert]::ToBase64String($o[1]) -eq $a64) { continue }
            if ($diverge.ContainsKey($name)) { break }
            $asText = { param($b) ([System.Text.Encoding]::UTF8.GetString($b) -replace "`r?`n", ' | ') }
            $problems.Add(('DIVERGE: {0} says [{1}] and {2} says [{3}]' -f `
                $outputs[0][0], (& $asText $outputs[0][1]), $o[0], (& $asText $o[1]))) | Out-Null
            $counts.diverge++
            break
        }
    }

    if ($problems.Count -eq 0) {
        if ($ran.Count -eq 0) {
            $counts.idle++
            Say ("  {0,-34} nothing ran (every claimed side is recorded as refusing)" -f $name)
        } else {
            $note = if ($refused.Count -gt 0) { ' (refused: ' + ($refused -join ',') + ')' } else { '' }
            Say ("  {0,-34} {1}{2}" -f $name, ($ran -join '+'), $note)
            $counts.match++
        }
    } else {
        Say ("  {0,-34} FAIL" -f $name)
        foreach ($x in $problems) { Say ("      | " + $x); $failures.Add("$name`: $x") | Out-Null }
    }
}

foreach ($name in ($gaps.Keys + $refusals.Keys)) {
    if (-not $claims.ContainsKey($name)) { continue }
    if ($claims[$name].llvm -and -not $seen.ContainsKey($name + '|llvm')) {
        Say ("  {0,-34} STALE: on an LLVM ledger and the LLVM back end no longer refuses it" -f $name)
        $failures.Add("$name is recorded as an LLVM refusal and is not refused any more") | Out-Null
        $counts.stale++
    }
}
foreach ($name in $cs.Keys) {
    if (-not $claims.ContainsKey($name)) { continue }
    if ($claims[$name].c -and -not $seen.ContainsKey($name + '|c')) {
        Say ("  {0,-34} STALE: on the C ledger and the C back end no longer refuses it" -f $name)
        $failures.Add("$name is recorded as a C refusal and is not refused any more") | Out-Null
        $counts.stale++
    }
}

Say ''
Say ("COVERAGE: matched {0} / diverged {1} / unrecorded refusals {2} / stale {3} / missing {4} / nothing ran {5}" -f `
     $counts.match, $counts.diverge, $counts.unexpected, $counts.stale, $counts.missing, $counts.idle)
Say ''
if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok -- every claimed side agrees, or is refused by a ledger entry'
exit 0
