# tools\refreeze.ps1 — re-record the suite's locks without destroying its evidence
#
#     powershell -ExecutionPolicy Bypass -File tools\refreeze.ps1
#
# The corpus holds two kinds of golden, and treating them alike is how a test
# suite starts agreeing with itself:
#
#   * `.out` and `.err` are **expectations**.  They say what a program must print
#     or what diagnostic it must produce, and the ones in this tree were recovered
#     from the pre-port suite and from recorded evidence rather than from the
#     current compiler.  Re-recording one of those replaces the question with the
#     answer: the case can then never fail, which is the opposite of the point.
#   * `digests.txt` is a **lock**.  It is hashes of this compiler's own output for
#     `lex`, `parse` and `emit-c` over a list of files.  It can only ever prove
#     "nothing changed", never "the output is right" — and when it is missing, the
#     `dumps` cases fail as "no recorded digest", which looks like a broken case
#     and is really a missing lock.
#
# So this script does the one sequence that is both safe and sufficient: back
# every golden up, let `record` write everything, then put every `.out`/`.err`
# back byte for byte, keeping only the digests and any golden that did not exist
# before (those belong to cases that are new, and they are listed by name).
#
# It also re-runs the suite afterwards and prints the tally, because a freeze that
# is not followed by a run has proved nothing.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$golden = Join-Path $root 'tests\golden'
$backup = Join-Path $root '.work\golden_backup'
$suite = Join-Path $root 'tests\run_tests.exe'
$fail = $false

function Say($t) { Write-Host $t }

Say "Vela suite re-freeze — $root"
Say ("  " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

if (-not (Test-Path $vm)) { Say '  no compiler at selfhost\build\vm.exe'; exit 1 }
Set-Location $root

# ------------------------------------------------------------------ 1. backup
Say ''
Say '[1/5] back up every golden'
if (Test-Path $backup) { Remove-Item $backup -Recurse -Force }
New-Item -ItemType Directory -Force -Path $backup | Out-Null
if (Test-Path $golden) {
    Copy-Item (Join-Path $golden '*') $backup -Force
    Say ("      " + (Get-ChildItem $backup -File).Count + " golden files -> .work\golden_backup")
} else {
    Say '      no tests\golden yet; everything record writes will be new'
}

# ------------------------------------------------------------- 2. build suite
Say ''
Say '[2/5] build the suite'
Remove-Item $suite, (Join-Path $root 'tests\run_tests.obj') -ErrorAction SilentlyContinue
& $vm build 'tests\run_tests.vel' *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $suite)) {
    Say "      FAILED to build tests\run_tests.vel (exit $LASTEXITCODE)"
    exit 1
}
Say ("      " + (Get-Item $suite).Length + " B  " + (Get-Item $suite).LastWriteTime.ToString('HH:mm:ss'))

# ----------------------------------------------------------------- 3. record
Say ''
Say '[3/5] record (writes every golden, including the digests)'

# `run_tests.vel` finds the compiler through `VELA_SELF`, and falls back to the
# *relative* `selfhost/build/vm.exe` — forward slashes, and `shell()` wraps the
# whole command in an extra pair of quotes for cmd.exe's sake.  cmd.exe then
# splits that path at the first `/` and answers
#
#     'selfhost' is not recognized as an internal or external command
#
# so the compiler is never started, and the stderr the suite reports as
# "could not be built" is the driver's harmless first line.  Measured: with the
# variable unset, 30 cases failed this way; with it set, 5.  It has to be set
# **before the record pass**, not merely before the run — otherwise `record`
# fails the same way and never writes the goldens it exists to write, which is
# how the four new `parallel_alias_*` cases ended up with no `.out` at all.
# This is what `tools\build.ps1` does when it runs the suite.
$env:VELA_SELF = (Resolve-Path -LiteralPath $vm).Path
Say "      VELA_SELF = $env:VELA_SELF"

& $suite record 2>&1 | Select-Object -Last 4 | ForEach-Object { Say "      | $_" }

# ------------------------------------------------------- 4. restore evidence
Say ''
Say '[4/5] restore the expectations, keep the locks'
$restored = 0
Get-ChildItem $backup -File | Where-Object { $_.Name -ne 'digests.txt' } | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $golden $_.Name) -Force
    $restored++
}
Say "      restored $restored golden files from the backup"
Say '      digests.txt deliberately NOT restored: it is the lock the record pass just wrote.'
Say '      (The first run of this script restored it too, and reported every lex/parse/emit'
Say '       digest as "output changed" for that reason alone.  A lock that is overwritten by'
Say '       the old lock is not a lock.)'
$new = @(Get-ChildItem $golden -File | Where-Object { -not (Test-Path (Join-Path $backup $_.Name)) })
if ($new.Count -gt 0) {
    Say '      new in this pass (belongs to cases that did not exist before):'
    $new | ForEach-Object { Say ("        " + $_.Name + "  " + $_.Length + " B") }
} else {
    Say '      nothing new'
}
if (Test-Path (Join-Path $golden 'digests.txt')) {
    $d = Get-Item (Join-Path $golden 'digests.txt')
    Say ("      digests.txt  " + $d.Length + " B  " + (Get-Content $d.FullName | Measure-Object -Line).Lines + " lines")
} else {
    Say '      !! digests.txt was not written: the dumps cases cannot pass'
    $fail = $true
}

# ------------------------------------------------------------------ 5. verify
Say ''
Say '[5/5] run the suite'

# Do **not** clear build outputs or kill helpers at this point.  An earlier version
# of this script did both, between the record pass and the verification pass, and it
# broke its own verification: `record` reported 189 passed / 0 failed and the run
# after it reported five failures — four `parallel_alias_*` cases ("the compiled
# program printed something else") and one that "could not be built" — every one of
# which builds and runs correctly by hand, and all of which pass when nothing is
# cleared in between.  Measured: the same suite, run with nothing touched, is
# **190 passed, 0 failed**.
#
# The reaping that is actually needed now happens in the compiler driver itself,
# immediately after the C compiler exits (`reap_helpers` in
# `selfhost\parts\vm_main.vel`), which is the only place that can guarantee one build
# leaves nothing behind — a script that drives the suite never reaches its own
# cleanup when the harness is wedged by a helper.

& $suite > (Join-Path $root '.work\refreeze_suite.out') 2> (Join-Path $root '.work\refreeze_suite.err')

# (The `VELA_SELF` assignment above the record pass is the other half of this: it has
# to be set before `record`, not merely before the run, or the record pass fails the
# same way and writes no goldens at all.)
$code = $LASTEXITCODE
$out = Get-Content (Join-Path $root '.work\refreeze_suite.out')
$out | Where-Object { $_ -match 'passed|failed' } | Select-Object -Last 1 | ForEach-Object { Say "      $_" }
$fails = @($out | Where-Object { $_ -match '^\s+FAIL' })
if ($fails.Count -gt 0) {
    Say '      failures:'
    $fails | ForEach-Object { Say ("        " + $_.Trim()) }
}
Say ("      exit code " + $code)

# A build leaves detached helpers behind; see tools\smoke.ps1 for why that
# matters here.  `mspdbsrv` is deliberately not swept: it is a *shared* PDB server,
# so killing it by image name breaks another checkout's compile with a message that
# names neither this script nor a PDB server (`tools\_cl-helper-spawn.ps1` measures
# that our builds never ask for one).  `reap_helpers` in the compiler driver is what
# reaps the helpers of a build this script ran.
$strays = @(Get-Process -Name vctip -ErrorAction SilentlyContinue)
if ($strays.Count -gt 0) {
    $strays | Stop-Process -Force -ErrorAction SilentlyContinue
    Say ("      cleaned up $($strays.Count) detached vctip process(es)")
}

Say ''
if ($fail -or $fails.Count -gt 0) { Say 'RESULT: not green — read the failures above'; exit 1 }
Say 'RESULT: green'
exit 0
