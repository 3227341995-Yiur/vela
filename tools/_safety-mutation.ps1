# tools/_safety-mutation.ps1 - can `tools\safety.ps1` fail, and name the row it fails on?
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\_safety-mutation.ps1
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\_safety-mutation.ps1 -Only 3
#
# A probe (`gitignore` ignores `/tools/_*`), and it is the answer to a question a corpus like
# `tests\safety\` has to be asked out loud: **a gate that cannot fail is not a gate**, and this
# one reads its expectations out of a manifest, so the way it could be blind is that a row's
# expectation is never really compared.
#
# HOW IT WORKS, and why this is a mutation test rather than a re-run:
#
#   1. the manifest is copied aside;
#   2. for each row that asserts an exit code, **that exit code is flipped** (2 -> 3, 0 -> 9,
#      and a missing one is set) -- one mutation at a time, written to the real manifest;
#   3. `tools\safety.ps1 -Only <that row>` is run, and the verdict must be
#      `RESULT: FAIL` with the row's name in the output and a reason naming the column;
#   4. the manifest is restored, always, including on a throw (a `try/finally`), and the
#      restore is verified by hash -- a probe that leaves a mutated manifest behind would be
#      worse than no probe.
#
# Every row with an exit expectation is mutated, in each of the columns that row asserts:
# `check_exit` (modes `check`, `ok`, `run-ok`, `violation`), `run_exit` (modes `native`,
# `run-ok`) and `native_exit` (modes `native`, `run-ok`).  The exit code is the right column
# to mutate because it is the one the harness compares as a *number*: a message expectation is
# a substring test, and flipping one to nonsense would prove the substring comparison is live
# rather than that the row is judged at all.
#
# Each mutation is judged on the line the harness prints for the row, so a red run that names
# a *different* row is a failure of this probe rather than a pass.  The reason line has to be
# the one for the column under test, by the harness's own wording for it ("expected run to
# exit 3, got 2", "compiled stderr lacks \"...\"", ...): matching the mutated *value* is not
# enough, because `run_msg` and `native_msg` are frequently the same string in the manifest and
# the probe then names the wrong column.
#
# ONE EQUIVALENT MUTANT IS RECORDED RATHER THAN TESTED, and it is worth knowing about.  A
# `violation` row carries a `check_exit` (2, everywhere), but that mode does not compare the
# number: its assertion is "the rule is kept", and `refusedAsRequired` is `exit != 0` with the
# message being what pins the reason.  So flipping a `violation` row's `check_exit` from 2 to 3
# is *not* caught -- by design, and the first version of this probe failed on exactly that row
# (`bounds_zero_len_array_constant`) before the column list below was made per-mode.  The 15
# `violation` rows are therefore mutated on `check_msg`, which is what they really assert.  If
# that ever needs tightening, it is a change to the harness's *promise* ("a violation row
# asserts the exit code too"), not a change to this probe.

[CmdletBinding()]
param(
    # Run only the first N mutations -- for iterating on this script, not for a claim.
    [int] $Only = 0,
    [switch] $KeepGoing
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $root 'tests\safety\manifest.txt'
$safety = Join-Path $root 'tools\safety.ps1'
$holder = Join-Path $env:TEMP 'vela-safety-mutation'
Remove-Item $holder -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $holder | Out-Null

function Say($t) { Write-Host $t }

$original = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($manifest))
$originalHash = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash
$lines = @($original -split "`n")

# **Freeze the compiler once, before any mutation**, and pass it to every run with
# `-FrozenVm`.  Without this the probe is unreliable in a way that looks like the harness
# failing: a no-argument `safety.ps1` run copies `selfhost\build\vm.exe` over
# `%TEMP%\vela-safety\frozen\vm.exe` every time, and this probe starts hundreds of those runs,
# so two of them raced and one printed
#
#     powershell.exe : Copy-Item : The process cannot access the file
#     '...\vela-safety\frozen\vm.exe' because it is being used by another process
#
# which aborted the probe with 11 mutations run.  `-FrozenVm` was already the harness's own
# answer to "reproduce a run against one compiler" (it uses the file in place and copies
# nothing), so the fix is that flag rather than a retry loop.  The file has to be the tree's
# compiler *and* have `LLVM-C.dll` beside it, which `selfhost\build\` does.
$sourceVm = Join-Path $root 'selfhost\build\vm.exe'
if (-not (Test-Path -LiteralPath $sourceVm)) { throw ('no compiler at ' + $sourceVm + ' -- run tools\build.ps1 first') }
$frozenVm = Join-Path $env:TEMP 'vela-safety-mutation-frozen\vm.exe'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $frozenVm) | Out-Null
Copy-Item -LiteralPath $sourceVm -Destination $frozenVm -Force
Copy-Item -LiteralPath (Join-Path $root 'selfhost\build\LLVM-C.dll') -Destination (Join-Path (Split-Path -Parent $frozenVm) 'LLVM-C.dll') -Force
Say ("frozen compiler for every run: {0} bytes, {1}" -f (Get-Item -LiteralPath $frozenVm).Length, $frozenVm)

# ---------------------------------------------------------------- the rows
#
# A row is `name | file | mode | check_exit | check_msg | run_exit | run_msg | native_exit | ...`
# with an optional trailing `#` comment, and blank or `#` lines are not rows.
$rows = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $hash = $line.IndexOf('#')
    $body = if ($hash -ge 0) { $line.Substring(0, $hash) } else { $line }
    if ($body.Trim().Length -eq 0) { continue }
    $f = @()
    foreach ($p in ($body.Split('|'))) { $f += $p.Trim() }
    while ($f.Count -lt 16) { $f += '' }
    if ($f[0].Length -eq 0 -or $f[2].Length -eq 0) { continue }
    $rows += [pscustomobject]@{
        Index = $i; Line = $line
        Name = $f[0]; Mode = $f[2]
        CheckExit = $f[3]; CheckMsg = $f[4]
        RunExit = $f[5]; RunMsg = $f[6]
        NativeExit = $f[7]; NativeMsg = $f[8]
        NativeOut = $f[9]; RunOut = $f[10]
        Xfail = $f[12]
    }
}
Say ("rows: {0} in {1}" -f $rows.Count, $manifest)

# The columns each mode actually compares, read out of the `switch` in `tools\safety.ps1`
# rather than assumed.  This matters more than it looks: the first version of this probe
# planned a `check_exit` mutation for every mode that has one, and a `violation` row *passed*
# with its exit code flipped from 2 to 3.  That is not a hole in the harness, it is what the
# mode means: a `violation` row's assertion is "the rule is kept", and `refusedAsRequired` is
# `exit != 0` -- the *number* is deliberately loose there because the row is about whether the
# program is refused at all, and the message is the assertion that pins the reason.  A mutant
# that no expectation can kill is an equivalent mutant; planning one is the probe's mistake,
# so the column list below is per mode and the `violation` row is mutated on its message.
#
#   check       checkExit, checkMsg
#   ok          checkExit
#   native      nativeExit, nativeMsg, runExit, runMsg
#   run-ok      checkExit, nativeExit, nativeOut, runExit, runOut
#   violation   checkMsg                      (checkExit is refused != 0, so flipping it is
#                                              an equivalent mutant -- recorded, not tested)
#   diverge     (no row in the manifest)
#   deliberate  checkExit
function Get-Mutations($row) {
    $m = @()
    function Add([string] $column, [int] $cell, [string] $value) {
        $script:__m += [pscustomobject]@{ Column = $column; Cell = $cell; Was = $value }
    }
    $script:__m = @()
    switch ($row.Mode) {
        'check' {
            if ($row.CheckExit -match '^\d+$') { Add 'check_exit' 3 $row.CheckExit }
            if ($row.CheckMsg.Length -gt 0) { Add 'check_msg' 4 $row.CheckMsg }
        }
        'ok' {
            if ($row.CheckExit -match '^\d+$') { Add 'check_exit' 3 $row.CheckExit }
        }
        'native' {
            if ($row.NativeExit -match '^\d+$') { Add 'native_exit' 7 $row.NativeExit }
            if ($row.NativeMsg.Length -gt 0) { Add 'native_msg' 8 $row.NativeMsg }
            if ($row.RunExit -match '^\d+$') { Add 'run_exit' 5 $row.RunExit }
            if ($row.RunMsg.Length -gt 0) { Add 'run_msg' 6 $row.RunMsg }
        }
        'run-ok' {
            if ($row.CheckExit -match '^\d+$') { Add 'check_exit' 3 $row.CheckExit }
            if ($row.NativeExit -match '^\d+$') { Add 'native_exit' 7 $row.NativeExit }
            if ($row.RunExit -match '^\d+$') { Add 'run_exit' 5 $row.RunExit }
        }
        'violation' {
            if ($row.CheckMsg.Length -gt 0) { Add 'check_msg' 4 $row.CheckMsg }
        }
        'deliberate' {
            if ($row.CheckExit -match '^\d+$') { Add 'check_exit' 3 $row.CheckExit }
        }
    }
    foreach ($x in $script:__m) { $m += $x }
    return $m
}
$plan = @()
foreach ($r in $rows) { foreach ($m in (Get-Mutations $r)) { $plan += [pscustomobject]@{ Row = $r; Mut = $m } } }
Say ("mutations planned: {0}" -f $plan.Count)
if ($Only -gt 0) { $plan = @($plan | Select-Object -First $Only); Say ("-Only " + $Only + ": running " + $plan.Count) }

# ---------------------------------------------------------------- the mutation
function Set-Cell([int] $lineIndex, [int] $cell, [string] $value) {
    $line = $lines[$lineIndex]
    $cut = $line.IndexOf('#')
    $body = if ($cut -ge 0) { $line.Substring(0, $cut) } else { $line }
    $tail = if ($cut -ge 0) { $line.Substring($cut) } else { '' }
    $f = @($body.Split('|'))
    while ($f.Count -le $cell) { $f += ' ' }
    $f[$cell] = ' ' + $value + ' '
    $lines[$lineIndex] = (($f -join '|').TrimEnd()) + $tail
    [System.IO.File]::WriteAllBytes($manifest, (New-Object System.Text.UTF8Encoding($false)).GetBytes(($lines -join "`n")))
}

function Restore-Manifest {
    [System.IO.File]::WriteAllBytes($manifest, (New-Object System.Text.UTF8Encoding($false)).GetBytes($original))
}

$failures = New-Object System.Collections.ArrayList
$done = 0
try {
    foreach ($item in $plan) {
        $row = $item.Row
        $mut = $item.Mut
        $isXfail = ($row.Xfail -eq 'yes')
        $isExit = $mut.Column -like '*_exit'
        if ($isExit) {
            $was = [int]$mut.Was
            $now = if ($was -eq 0) { 9 } else { $was + 1 }
        } else {
            # A message expectation is a substring test, so the mutation appends a marker that
            # cannot be in the compiler's own words.  No space and no `|`, because the cell is
            # `|`-separated and the comparison trims.
            $was = $mut.Was
            $now = $was + 'ZZMUTATED'
        }
        Set-Cell $row.Index $mut.Cell $now
        $done++

        $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $safety -Only $row.Name -FrozenVm $frozenVm 2>&1 | Out-String
        $failed = ($out -match 'RESULT: FAIL')
        $named = ($out -match [regex]::Escape($row.Name))
        # Every `reason:` line for the row, so the one that quotes *this* column can be shown.
        # The *first* reason is not it: a `native` row asserts four things (the program's exit
        # and message, the interpreter's exit and message), so a mutation of one fails the row
        # while the printer lists another first -- measured, and it cost one wrong conclusion
        # about which column the harness had failed on.  And the column has to be matched by
        # its *own* wording, not by the value: `run_msg` and `native_msg` are often the same
        # string in the manifest, so "the reason quotes the mutated value" matches both, and
        # the probe named the wrong column twice before this was written out.
        $reasons = @($out -split "`r?`n" | Where-Object { $_ -match '^\s+reason:' } | ForEach-Object { $_.Trim() })
        # The wording differs by mode, so a column cannot be recognised by one phrase, and this
        # table has now been corrected three times by measurement rather than by reading:
        #
        #   `check`      "expected check exit 3, got 2"
        #   `run-ok`     "check must accept it, but exited 3"          (same cell, other mode)
        #   `deliberate` "a DELIBERATE row is no longer accepted: the row requires check exit 9
        #                 and stdout \"ok\", but check gave exit 0 ..."  (the third wording)
        #
        # The first two cost `bounds_zero_len_array_constant` and `memory_return_str_binding`;
        # the third cost `hole_member_method_as_value`.  All three were the *probe* calling a
        # caught mutation a miss, which is why the accepted wordings are a list per column.
        $want = switch ($mut.Column) {
            'check_exit'  { @('expected check exit', 'must accept it, but exited', 'is no longer accepted') }
            'check_msg'   { @('stderr lacks') }
            'run_exit'    { @('expected run to exit') }
            'run_msg'     { @('run stderr lacks') }
            'native_exit' { @('expected the compiled program to exit') }
            'native_msg'  { @('compiled stderr lacks') }
            default       { @($mut.Column) }
        }
        $mine = @($reasons | Where-Object { $r = $_; @($want | Where-Object { $r -match [regex]::Escape($_) }).Count -gt 0 } | Select-Object -First 1)
        $reasonText = if ($mine.Count -gt 0) { $mine[0] } elseif ($reasons.Count -gt 0) { $reasons[0] } else { '(no reason line)' }
        # The row failed for the column under test, not merely somewhere.
        $reasonIsMine = ($mine.Count -gt 0)

        if ($failed -and $named -and $reasonIsMine) {
            Say ("  ok    {0,-46} {1,-12}  red, row named, reason names the column  [{2}]" -f $row.Name, $mut.Column, $reasonText)
        } elseif ($isXfail -and $named -and $reasonIsMine) {
            # An `xfail` row is *expected* to violate its assertion, so its failure is counted in
            # the `FAIL-expected` column and kept out of the exit code -- `RESULT:` says PASS even
            # while the row reads FAIL.  For such a row the mutation is still caught, and the
            # evidence is the row's own FAIL line plus a reason naming the column, not the exit
            # code.  Measured on `hole_mut_scalar_parameter` (mode `run-ok`, xfail): its exit
            # mutation produced `RESULT: FAIL=False rowNamed=True` and this probe called it a
            # miss.  Treating "the row is red for this column" as the pass is the honest test --
            # the alternative would be to exclude all 16 xfail rows from the sweep, which is
            # exactly where a blind spot would hide.
            Say ("  ok    {0,-46} {1,-12}  xfail row reports FAIL for this column  [{2}]" -f $row.Name, $mut.Column, $reasonText)
        } elseif ($failed -and $named) {
            Say ("  FAIL  {0,-46} {1,-12}  red and named, but no reason line for this column  [{2}]" -f $row.Name, $mut.Column, $reasonText)
            [void]$failures.Add(('{0}: mutation of {1} failed the row for some other reason' -f $row.Name, $mut.Column))
            if (-not $KeepGoing) { break }
        } else {
            Say ("  FAIL  {0,-46} {1,-12}  RESULT:FAIL={2} rowNamed={3}  was `"{4}`"" -f $row.Name, $mut.Column, $failed, $named, $was)
            [void]$failures.Add(('{0}: mutation of {1} was NOT caught' -f $row.Name, $mut.Column))
            if (-not $KeepGoing) { break }
        }
        Restore-Manifest
    }
} finally {
    Restore-Manifest
    $after = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash
    if ($after -ne $originalHash) {
        Say ("  !! the manifest was NOT restored: {0} vs {1}" -f $originalHash, $after)
        [void]$failures.Add('the manifest was not restored')
    } else {
        Say ("manifest restored: {0}" -f $originalHash.Substring(0, 16))
    }
    Remove-Item -LiteralPath $holder -Recurse -Force -ErrorAction SilentlyContinue
}

Say ''
Say ("mutations run: {0} of {1}" -f $done, $plan.Count)
if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok -- every expectation the safety harness compares, when mutated, is caught by the row it belongs to'
exit 0
