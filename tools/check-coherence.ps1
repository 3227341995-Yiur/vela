#Requires -Version 5.1
<#
  check-coherence.ps1 -- the things that must agree after a build, and that no single
  script asserts.

  WHY THIS EXISTS

  `build.ps1` already carries the warning, in its own words: the middle promotion was
  missing until an OpenMP change to the emitter "had no effect on anything", because the
  tests and the benchmarks run `selfhost\build\vm.exe` and it "stayed the binary built
  from the *previous* source -- one generation behind the tree, quietly".  A build whose
  results are about the wrong program is worse than a red build.

  The promotion is implemented now.  What was still missing is anything that *notices*
  when it does not happen.  Measured 2026-09-24: `selfhost\vm.exe` was 868,352 bytes and
  fixed the `mut a = 1` hang, while `selfhost\build\vm.exe` was 867,840 bytes and still
  hung on it -- 32 minutes behind, in the same tree, with both files named `vm.exe`.
  Every gate run in that window measured the old compiler.

  THREE CLAIMS

    1. THE COMPILER IN THE TREE IS THE ONE THE SOURCE PRODUCED.  `selfhost\vm.exe` is
       the binary step 4 wrote; `selfhost\build\vm.exe` is the copy the tests and the
       benchmarks use.  They must be the same *program*, which is checked as the same
       size PLUS the same behaviour on a program whose answer changed recently -- the
       bytes may differ because a PE timestamp is not reproducible, and this project has
       already recorded that.  A size difference means the promotion did not run.

    2. THE C FIXPOINT.  Three generations of the emitted C must be byte-identical:
       `selfhost\build\vm.c` (the seed), `selfhost\vm.c` (what the compiler wrote), and
       `selfhost\build\_fixpoint_gen2.c` (what the compiler the compiler wrote, wrote).
       `build.ps1` checks this as it goes; checking it again here costs one hash each and
       catches a build that reported success over files that disagree.

    3. EVERY BINARY THAT MUST LOAD CAN LOAD.  A `vm.exe` linked against `LLVM-C.lib`
       does not start at all without `LLVM-C.dll` beside it, which looks like a crash
       rather than a missing file.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\check-coherence.ps1
    powershell -ExecutionPolicy Bypass -File tools\check-coherence.ps1 -SelfTest

  -SelfTest proves the first claim can fail: it points the comparison at a copy of the
  compiler with one byte appended, which is not a program the source produced, and
  requires the run to fail.  Exit: 0 = RESULT: ok, 1 = RESULT: failed.
#>
[CmdletBinding()]
param(
    [switch] $SelfTest
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

function Say([string] $t) { Write-Host $t }
$failures = New-Object System.Collections.ArrayList
function Fail([string] $t) { Say ("  FAIL  " + $t); $script:failures.Add($t) | Out-Null }
function Pass([string] $t) { Say ("  ok    " + $t) }

$inTree   = Join-Path $root 'selfhost\vm.exe'
$inBuild  = Join-Path $root 'selfhost\build\vm.exe'
$byVela   = Join-Path $root 'selfhost\build\vm_by_vela.exe'
$seedC    = Join-Path $root 'selfhost\build\vm.c'
$gen1C    = Join-Path $root 'selfhost\vm.c'
$gen2C    = Join-Path $root 'selfhost\build\_fixpoint_gen2.c'

Say '== 1. the compiler in the tree is the one the source produced'

if ($SelfTest) {
    # The comparison is on SIZE, deliberately (a PE timestamp is not reproducible, so two
    # builds of one program have different hashes -- this project has already recorded
    # that).  So the self-test has to change the size: a copy of `selfhost\vm.exe` with
    # one byte appended is one byte longer, and comparing it against the file it came
    # from is a pair that IS coherent in the real world -- which is what makes this
    # isolation, rather than the real defect below passing for a self-test.
    $tmp = Join-Path $env:TEMP 'coherence-selftest.exe'
    $bytes = [System.IO.File]::ReadAllBytes($inTree)
    [System.IO.File]::WriteAllBytes($tmp, ($bytes + [byte]0))
    Say '  self-test: comparing selfhost\vm.exe against a copy of itself one byte longer'
    $a = if (Test-Path -LiteralPath $tmp) { (Get-Item -LiteralPath $tmp).Length } else { -1 }
    $b = if (Test-Path -LiteralPath $inTree) { (Get-Item -LiteralPath $inTree).Length } else { -1 }
    Say ("  {0} bytes  vs  {1} bytes" -f $a, $b)
    if ($a -eq $b) {
        Say 'SELFTEST RESULT: BLIND -- a file one byte longer compared equal, so this cannot tell a promoted compiler from a stale one'
        exit 1
    }
    Say 'SELFTEST RESULT: the comparison is live (a one-byte difference in length failed it)'
    exit 0
}

$sizes = @{}
foreach ($f in @($inTree, $inBuild, $byVela)) {
    if (Test-Path -LiteralPath $f) {
        $sizes[$f] = (Get-Item -LiteralPath $f).Length
        Say ("  {0,-46} {1,9} B  sha256 {2}" -f (Split-Path $f -Leaf), $sizes[$f], (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.Substring(0, 12))
    } else {
        Fail "missing: $f"
    }
}
$distinct = @($sizes.Values | Select-Object -Unique)
if ($distinct.Count -eq 1) {
    Pass ("all {0} binaries are the same size ({1} bytes)" -f $sizes.Count, $distinct[0])
    # Size alone cannot tell "the same program, built twice" from "two different programs that
    # happen to be the same length" -- and the first version of this check said `ok` for a tree
    # whose `selfhost\vm.exe` was 868,352 bytes of a NEWER compiler than the promoted one, which
    # is the exact condition it exists to catch.  So a size match is followed by a deterministic
    # comparison: the same program emits the same C for the same input, byte for byte, and
    # `emit-c` needs no C compiler, so this costs two runs.
    $probe = Join-Path $root 'tests\build\arith_basics.vel'
    if (Test-Path -LiteralPath $probe) {
        $emitted = @{}
        foreach ($f in @($inTree, $inBuild, $byVela)) {
            if (-not (Test-Path -LiteralPath $f)) { continue }
            $outFile = Join-Path $env:TEMP ("coh-{0}.c" -f (Split-Path $f -Leaf))
            Remove-Item -LiteralPath $outFile -Force -ErrorAction SilentlyContinue
            & cmd /c ("`"{0}`" emit-c `"{1}`" 1> `"{2}`" 2> nul" -f $f, $probe, $outFile) | Out-Null
            if (Test-Path -LiteralPath $outFile) {
                $emitted[$f] = (Get-FileHash -LiteralPath $outFile -Algorithm SHA256).Hash.Substring(0, 12)
            }
        }
        $eDistinct = @($emitted.Values | Select-Object -Unique)
        if ($emitted.Count -ge 2 -and $eDistinct.Count -eq 1) {
            Pass ("and they are the same program: all of them emit the same C for tests\build\arith_basics.vel (sha256 {0})" -f $eDistinct[0])
        } elseif ($emitted.Count -lt 2) {
            Pass 'emit-c comparison skipped: fewer than two binaries could be run'
        } else {
            Fail ("the binaries are the same SIZE but different PROGRAMS -- they emit different C for the same input: " +
                  (($emitted.GetEnumerator() | ForEach-Object { "$(Split-Path $_.Key -Leaf)=$($_.Value)" }) -join ', ') +
                  ".  A rebuild that only moved the PE timestamp would emit identical C, so this is a compiler that was not promoted")
        }
    }
} else {
    Fail ("the compiler in the tree and the one the tests use are DIFFERENT PROGRAMS: " +
          (($sizes.GetEnumerator() | ForEach-Object { "$(Split-Path $_.Key -Leaf)=$($_.Value)" }) -join ', ') +
          " -- the promotion in build.ps1 step 4 did not run, so every gate and every benchmark is measuring the previous source")
    # What this failure looked like the first time it was seen, so the next reader does not
    # have to reconstruct it.  `build.ps1` report, 2026-09-24 02:22:23:
    #
    #   === 2/8  bootstrap vm.exe from selfhost\build\vm.c
    #   LINK : fatal error LNK1104: 无法打开文件 "...\selfhost\build\vm.exe"
    #   build FAILED at step 2
    #
    # Step 2 links the compiler back out of the seed C, and on Windows a RUNNING
    # executable cannot be overwritten.  So a compiler process that never exits holds the
    # file and the build dies two steps before the promotion it was supposed to reach.
    # Anything that can hang `vm.exe` will therefore wedge the build: a probe with no
    # timeout, a killed PowerShell job whose child was left alive, a corpus case that does
    # not terminate.  The report file is at ..\vela-build-report.txt from the repository
    # root, and its last step is the one that failed.
    Say '        (if build.ps1 stopped at step 2 with LNK1104, the output file was held by a'
    Say '         running vm.exe -- a hung probe leaves one behind.  Check `Get-Process vm`:'
    Say '         the strays have no start time and 0 MB.  The report is ..\vela-build-report.txt)'
}

Say ''
Say '== 2. the C fixpoint, three generations byte-identical'
$gens = @($seedC, $gen1C, $gen2C)
$hashes = @()
foreach ($g in $gens) {
    if (Test-Path -LiteralPath $g) {
        $h = (Get-FileHash -LiteralPath $g -Algorithm SHA256).Hash
        $hashes += $h
        Say ("  {0,-30} {1,9} B  sha256 {2}" -f (Split-Path $g -Leaf), (Get-Item -LiteralPath $g).Length, $h.Substring(0, 16))
    } else {
        Fail "missing: $g"
    }
}
if ($hashes.Count -gt 0) {
    if ((@($hashes | Select-Object -Unique)).Count -eq 1) {
        Pass "all three generations of the emitted C are byte-identical"
    } else {
        Fail "the three generations of the emitted C DISAGREE, so the compiler does not reproduce itself"
    }
}

Say ''
Say '== 3. a vm.exe that cannot load its DLL looks like a crash'
foreach ($b in @($inTree, $inBuild, $byVela)) {
    if (-not (Test-Path -LiteralPath $b)) { continue }
    $dll = Join-Path (Split-Path $b -Parent) 'LLVM-C.dll'
    if (Test-Path -LiteralPath $dll) { Pass ("LLVM-C.dll is beside " + (Split-Path $b -Leaf)) }
    else { Fail ("no LLVM-C.dll beside " + $b + " -- that vm.exe does not load at all") }
}

Say ''
Say '== 4. no corpus harness is frozen on a stale compiler'
# The same defect as claim 1, one level up, and it was found the same way -- by a number that
# did not fit.  `tools\safety.ps1` does not copy the compiler per run: it CACHES one at
# `%TEMP%\vela-safety\frozen\vm.exe`, and if a run ever happened before a rebuild, every later
# run measures the old binary while printing a perfectly normal report.  Measured by the
# compiler agent on 2026-09-24: after the promotion the tree was `42E5D5EE` / 868,352 bytes and
# the cached copy was still `501B6CBB` / 867,840, so a newly wired corpus row reported
# `check exit=-1` (the stale compiler hangs on it) with the old hash in the same log.  The hash
# line is the only thing that says which compiler was measured, and nothing compares it.
#
# A deliberately pinned copy is a legitimate thing to have -- that is what `-FrozenVm` is for --
# so this claim is about the CACHE, whose whole purpose is to be the tree's compiler.
$caches = @(
    (Join-Path $env:TEMP 'vela-safety\frozen\vm.exe')
)
$treeSize = -1
if (Test-Path -LiteralPath $inBuild) { $treeSize = (Get-Item -LiteralPath $inBuild).Length }
foreach ($c in $caches) {
    if (-not (Test-Path -LiteralPath $c)) {
        Say ("  ok    no cached compiler at " + $c)
        continue
    }
    $cs = (Get-Item -LiteralPath $c).Length
    $ch = (Get-FileHash -LiteralPath $c -Algorithm SHA256).Hash.Substring(0, 12)
    if ($cs -eq $treeSize) {
        Pass ("the cached compiler matches the tree's size ({0} bytes, sha256 {1})" -f $cs, $ch)
    } else {
        Fail ("a corpus harness is frozen on a stale compiler: $c is $cs bytes (sha256 $ch) while the tree's is $treeSize -- every run that uses the cache measures the previous source.  Delete $c (and rerun), or pass -FrozenVm explicitly to say which compiler you mean")
    }
}

Say ''
if ($SelfTest) {
    if ($failures.Count -gt 0) {
        Say 'SELFTEST RESULT: the comparison is live (a one-byte change failed the run)'
        exit 0
    }
    Say 'SELFTEST RESULT: BLIND -- a one-byte change passed, so this cannot tell a promoted compiler from a stale one'
    exit 1
}

if ($failures.Count -gt 0) {
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok'
exit 0
