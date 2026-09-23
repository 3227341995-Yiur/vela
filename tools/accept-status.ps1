#Requires -Version 5.1
<#
  accept-status.ps1 -- the acceptance corpus for features that do not exist yet, run.

  WHY THIS EXISTS

  `tests\accept\` holds 48 programs written for the nine features Vela does not have: one per
  shape, each recording what the compiler says today and what the program must print on the day
  the feature lands.  They were written, moved there, and **run by nothing**.  `tests\cases.txt`
  has no line containing `enum`, and no script under `tools\` mentioned the directory.  That is
  the defect this project keeps finding in its own work -- a proof that exists and never runs --
  and it was found by auditing the enums plan rather than by a test going red.

  They cannot go into `tests\cases.txt`: the suite would be red until all nine features land.
  The useful thing to assert today is *stability*: every one of these programs is refused, with
  this exact diagnostic, and it stays that way until somebody changes it on purpose.

  WHAT IT JUDGES, per program under tests\accept\

    REFUSED   `check` exits non-zero; the first `vela:` line of stderr is recorded
    ACCEPTED  `check` exits 0 -- NEWS: either the feature landed, or a rule stopped refusing
    HANG      `check` did not return within the budget -- never acceptable, and always a
              failure: a compiler that does not terminate takes the suite and the IDE with it

  Then it compares each program against `tests\accept\BASELINE.txt`.  A difference is a failure
  even when it looks like progress, because the change is the event that matters: the day enums
  work, this tool goes red, and the program moves into `tests\cases.txt` with a golden.  That is
  the same two-sided ledger `tests\llvm-gaps.txt` uses, for the same reason -- a record that is
  never updated stops describing the thing it records.

  THE TIMEOUT IS NOT DECORATION

  `tests\safety\cases\strict_no_inferred_binding_type.vel` hangs `vm.exe check` on the compiler
  in this tree (2026-09-24), and a hung compiler process holds `selfhost\build\vm.exe` open, so
  the next `build.ps1` dies at step 2 with `LNK1104: cannot open file`.  One non-terminating
  program in a corpus therefore wedges the build that would have replaced the compiler.  So each
  program runs through `Start-Process` with a bounded wait and, on timeout, `Stop-Process -Force`
  -- verified to leave no process behind, which a killed PowerShell job does not do.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\accept-status.ps1            # judge
    powershell -ExecutionPolicy Bypass -File tools\accept-status.ps1 -Record    # write the baseline
    powershell -ExecutionPolicy Bypass -File tools\accept-status.ps1 -SelfTest  # prove HANG fires

  Exit: 0 = RESULT: ok, 1 = RESULT: failed.
#>
[CmdletBinding()]
param(
    [switch] $Record,
    [switch] $SelfTest,
    [int] $BudgetSeconds = 20
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$corpus = Join-Path $root 'tests\accept'
$baseline = Join-Path $corpus 'BASELINE.txt'

function Say([string] $t) { Write-Host $t }

if (-not (Test-Path -LiteralPath $vm)) { Say "RESULT: failed -- no compiler at $vm"; exit 1 }
if (-not (Test-Path -LiteralPath $corpus)) { Say "RESULT: failed -- no $corpus"; exit 1 }

$vmHash = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash
Say ("compiler : {0} bytes, sha256 {1}" -f (Get-Item -LiteralPath $vm).Length, $vmHash.ToLowerInvariant().Substring(0, 16))

# Two passes, and the first one exists so the second cannot leak a process.  `Start-Process`
# without redirection is the only form this sandbox allows, and it is also the one that gives a
# PID to kill; but its `ExitCode` reads back empty in Windows PowerShell 5.1 even after
# `WaitForExit(ms)` returns true -- which made the first version of this file call every
# refusal ACCEPTED, because the empty value defaulted to 0 and forty-eight programs that
# printed `vela: type error:` were recorded as fine.  So pass 1 decides only whether the
# program TERMINATES, and pass 2 -- through `cmd` with real file redirection, the same way
# `run_tests.vel` does it -- supplies both the exit code and the diagnostic.
function Get-Outcome {
    param([string] $Path, [string] $Name, [int] $Budget)
    $proc = $null
    try {
        $proc = Start-Process -FilePath $vm -ArgumentList @('check', $Path) -PassThru -NoNewWindow -ErrorAction Stop
    } catch {
        return @{ Kind = 'START-FAILED'; Detail = $_.Exception.Message }
    }
    $exited = $false
    try { $exited = $proc.WaitForExit($Budget * 1000) } catch { $exited = $false }
    # Pass 1's child inherits the console, so a refusal prints its diagnostic here even though
    # it is read from a file in pass 2.  That is the price of the only Start-Process form this
    # sandbox allows, and it is deliberate: the alternative -- a shell around the child -- loses
    # the PID, and a child that is not killed holds the compiler open (see HANG below).
    if (-not $exited) {
        # The whole point of this branch.  Kill the CHILD, not a shell around it.  A killed
        # PowerShell job leaves the grandchild running, and a running `vm.exe` holds
        # `selfhost\build\vm.exe` open, which makes the next build fail at step 2 with
        # LNK1104 -- measured on 2026-09-24, the hard way.
        try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop } catch { }
        Start-Sleep -Milliseconds 200
        $still = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
        return @{ Kind = 'HANG'; Detail = "did not return within $Budget s" + $(if ($still) { ' (and could not be killed)' } else { '' }) }
    }

    # Pass 2: the exit code and the diagnostic, from a real child process with file
    # redirection.  `cmd /c` also keeps native stderr out of PowerShell's error stream, where
    # it arrives as an ErrorRecord and prints noise that is not the program's output.
    $outFile = Join-Path $env:TEMP ("acc-{0}.out" -f $Name)
    $errFile = Join-Path $env:TEMP ("acc-{0}.err" -f $Name)
    Remove-Item -LiteralPath $outFile, $errFile -Force -ErrorAction SilentlyContinue
    & cmd /c ("`"{0}`" check `"{1}`" 1> `"{2}`" 2> `"{3}`"" -f $vm, $Path, $outFile, $errFile) | Out-Null
    $code = $LASTEXITCODE
    $errText = ''
    if (Test-Path -LiteralPath $errFile) { $errText = [System.IO.File]::ReadAllText($errFile) }
    $first = ''
    foreach ($line in ($errText -split "`r?`n")) {
        if ($line.StartsWith('vela: ')) { $first = $line.Trim(); break }
    }
    if ($code -ne 0) { return @{ Kind = 'REFUSED'; Detail = $first } }
    return @{ Kind = 'ACCEPTED'; Detail = $first }
}

# ---------------------------------------------------------------- the corpus
$files = @(Get-ChildItem -LiteralPath $corpus -Recurse -Filter '*.vel' -File | Sort-Object FullName)
Say ("corpus   : {0} program(s) under tests\accept" -f $files.Count)

if ($SelfTest) {
    # This self-test used to point at `tests\safety\cases\strict_no_inferred_binding_type.vel`,
    # the program that hung `vm.exe check` on 2026-09-24 -- and it stopped being a valid
    # one the moment that defect was fixed and the fix reached the tree, because the
    # program now returns in 0 s.  A self-test bound to a defect dies with the defect.
    #
    # So it no longer needs a compiler that hangs: what has to be proved is the MECHANISM --
    # a program that does not return is stopped and named, rather than left running.  A
    # command that sleeps longer than the budget does that, with nothing depending on a bug
    # still being present.
    Say ("self-test: a command that will not return within a {0} s budget" -f 3)
    $victim = $env:ComSpec
    $proc = Start-Process -FilePath $victim -ArgumentList @('/c', 'ping -n 30 127.0.0.1 > nul') -PassThru -NoNewWindow -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        Say 'SELFTEST RESULT: BLIND -- could not start a long-running command to time out on'
        exit 1
    }
    $exited = $proc.WaitForExit(3000)
    if ($exited) {
        Say 'SELFTEST RESULT: BLIND -- the long-running command returned inside the budget, so nothing was timed out'
        exit 1
    }
    try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop } catch { }
    Start-Sleep -Milliseconds 300
    $still = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
    if ($null -ne $still) {
        Say 'SELFTEST RESULT: BLIND -- the timeout fired but the process survived it, which is the leak this file exists to avoid'
        exit 1
    }
    Say 'SELFTEST RESULT: the timeout and the kill are live (a command that overran its budget was stopped and left nothing behind)'
    exit 0
}

$rows = New-Object System.Collections.ArrayList
foreach ($f in $files) {
    $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
    $name = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
    $r = Get-Outcome -Path $f.FullName -Name $name -Budget $BudgetSeconds
    [void]$rows.Add([pscustomobject]@{ Name = $rel; Kind = $r.Kind; Detail = $r.Detail })
}

$hangs = @($rows | Where-Object { $_.Kind -eq 'HANG' })
$accepted = @($rows | Where-Object { $_.Kind -eq 'ACCEPTED' })
$other = @($rows | Where-Object { $_.Kind -notin @('REFUSED', 'HANG', 'ACCEPTED') })
$refused = @($rows | Where-Object { $_.Kind -eq 'REFUSED' })

Say ''
Say ("refused {0}   accepted {1}   hang {2}   other {3}" -f $refused.Count, $accepted.Count, $hangs.Count, $other.Count)

if ($Record) {
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add('# tests\accept\BASELINE.txt -- what the acceptance corpus does TODAY.')
    [void]$lines.Add('#')
    [void]$lines.Add('# Written by `tools\accept-status.ps1 -Record`.  Every program here is written for a')
    [void]$lines.Add('# feature Vela does not have yet, so REFUSED is the expected state and the diagnostic')
    [void]$lines.Add('# is what the compiler says today.  A difference is a failure even when it looks like')
    [void]$lines.Add('# progress: the day a feature lands, this file is what says so, and the program moves')
    [void]$lines.Add('# into tests\cases.txt with a golden.  Shape:  name | outcome | first diagnostic line')
    [void]$lines.Add(("# recorded against compiler sha256 {0}" -f $vmHash.ToLowerInvariant()))
    foreach ($r in $rows) { [void]$lines.Add(("{0} | {1} | {2}" -f $r.Name, $r.Kind, $r.Detail)) }
    [System.IO.File]::WriteAllLines($baseline, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    Say ("wrote {0} ({1} row(s))" -f $baseline, $rows.Count)
    Say 'RESULT: recorded'
    exit 0
}

if (-not (Test-Path -LiteralPath $baseline)) {
    Say "RESULT: failed -- no {0}; run with -Record once to write it" -f $baseline
    exit 1
}

# ---------------------------------------------------------------- judge against the baseline
$pinned = @{}
foreach ($line in (Get-Content -LiteralPath $baseline)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $p = $t -split '\|', 3
    if ($p.Count -lt 2) { continue }
    $pinned[$p[0].Trim()] = [pscustomobject]@{ Kind = $p[1].Trim(); Detail = $(if ($p.Count -gt 2) { $p[2].Trim() } else { '' }) }
}

$failures = New-Object System.Collections.ArrayList
foreach ($r in $rows) {
    if ($r.Kind -eq 'HANG') {
        Say ("  HANG      {0}  -- {1}" -f $r.Name, $r.Detail)
        [void]$failures.Add("$($r.Name) does not terminate")
        continue
    }
    if ($r.Kind -in @('START-FAILED', 'OTHER')) {
        [void]$failures.Add("$($r.Name): $($r.Kind) -- $($r.Detail)")
        continue
    }
    if (-not $pinned.ContainsKey($r.Name)) {
        [void]$failures.Add("$($r.Name) is not in the baseline (record it, or it is a new program nobody has judged)")
        Say ("  NEW       {0}" -f $r.Name)
        continue
    }
    $want = $pinned[$r.Name]
    if ($want.Kind -ne $r.Kind -or $want.Detail -ne $r.Detail) {
        Say ("  CHANGED   {0}" -f $r.Name)
        Say ("      baseline: {0}  {1}" -f $want.Kind, $want.Detail)
        Say ("      today   : {0}  {1}" -f $r.Kind, $r.Detail)
        [void]$failures.Add("$($r.Name) changed: $($want.Kind) -> $($r.Kind)")
        continue
    }
}
foreach ($k in $pinned.Keys) {
    if (-not ($rows | Where-Object { $_.Name -eq $k })) {
        [void]$failures.Add("$k is in the baseline but no longer in the corpus")
        Say ("  GONE      {0}" -f $k)
    }
}

Say ''
if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say ("RESULT: ok -- {0} program(s) behave exactly as tests\accept\BASELINE.txt records" -f $rows.Count)
exit 0
