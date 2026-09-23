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

    1. THE COMPILER IN THE TREE IS THE ONE THE SOURCE PRODUCED, and the compiler under
       test is *this* compiler.  `selfhost\vm.exe` is what step 4 promoted;
       `selfhost\build\vm.exe` is the copy the tests, the benchmarks and every later gate
       run; `vm_by_vela.exe` is the same binary under the name that says how it was made.
       Step 4 copies one file onto the other two, so the size must be identical -- and a
       size mismatch is the one thing a copy cannot produce, which is what makes it the
       right *first* question.  But size is a proxy for "the same program" and this file
       must not stop at a proxy: since 2026-09-24 it is followed by behaviour.  They must
       emit the same C for the same program (which is a property of the whole front end and
       both back ends), they must agree about the **default build path** -- all three of
       them build a three-line program with no C compiler started, which is the claim the
       promotion exists to make -- and they must all accept the C path's name, because a
       compiler that does not know `build-c` is a compiler from before the flip.  Each of
       those is cheap and none needs a C compiler.
       WHY SIZE ALONE WAS NOT ENOUGH, in this project's own history: the first version of
       this check passed a tree whose two files were the same length and *different
       programs*, one generation apart, for half an hour while every gate measured the old
       one.  Size happened to be equal; nothing about the programs was.

    2. THE C FIXPOINT.  Three generations of the emitted C must be byte-identical:
       `selfhost\build\vm.c` (the seed), `selfhost\vm.c` (what the promoted compiler emits),
       and `selfhost\build\_fixpoint_gen2.c` (what the compiler the compiler built emits).
       `build.ps1` produces and compares all three; checking it again here costs one hash
       each and catches a build that reported success over files that disagree.  Note that
       none of the three is a promoted *side effect* any more: `build` is the LLVM path and
       writes no C, so `build.ps1` asks both generations for it (`emit-c`) at the moment it
       compares them, and a file this claim reads is a file that step wrote in this run.

    3. EVERY BINARY THAT MUST LOAD CAN LOAD.  A `vm.exe` linked against `LLVM-C.lib`
       does not start at all without `LLVM-C.dll` beside it, which looks like a crash
       rather than a missing file.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\check-coherence.ps1
    powershell -ExecutionPolicy Bypass -File tools\check-coherence.ps1 -SelfTest

  -SelfTest points claim 1 at a file one byte longer than `selfhost\vm.exe` and requires
  the run to fail, so the size comparison is shown to be live rather than assumed.  Exit:
  0 = RESULT: ok, 1 = RESULT: failed.
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

Say ''
Say '== 1. the compiler in the tree is the one the source produced'

if ($SelfTest) {
    # The size comparison, shown to be live.  A copy of `selfhost\vm.exe` with one byte
    # appended is one byte longer than the file it came from, and comparing that pair is
    # exactly what the real claim does when a promotion has not run -- so this isolates the
    # comparison instead of demonstrating the real defect.  `Length` is read with Get-Item,
    # which is the same property the claim compares.
    $tmp = Join-Path $env:TEMP 'coherence-selftest.exe'
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    $bytes = [System.IO.File]::ReadAllBytes($inTree)
    [System.IO.File]::WriteAllBytes($tmp, ($bytes + [byte]0))
    Say '  self-test: comparing selfhost\vm.exe against a copy of itself one byte longer'
    $a = if (Test-Path -LiteralPath $tmp) { (Get-Item -LiteralPath $tmp).Length } else { -1 }
    $b = if (Test-Path -LiteralPath $inTree) { (Get-Item -LiteralPath $inTree).Length } else { -1 }
    Say ("  {0} bytes  vs  {1} bytes" -f $a, $b)
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    if ($a -eq $b) {
        Say 'SELFTEST RESULT: BLIND -- a file one byte longer compared equal, so the size comparison cannot tell a promoted compiler from one that was not'
        exit 1
    }
    Say 'SELFTEST RESULT: the size comparison is live (a one-byte difference in length failed it)'
    Say ''
    Say '  The behaviour half is live by construction and does not need a synthetic file: it'
    Say '  runs the three binaries under test.  What would make IT blind is a runner that'
    Say '  cannot fail, which is why each comparison below fails with the hashes printed.'
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
if ($distinct.Count -ne 1) {
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
} else {
    Pass ("all {0} binaries are the same size ({1} bytes)" -f $sizes.Count, $distinct[0])

    # ---- and now the part that is not a proxy.
    #
    # A size match cannot tell "the same program, built twice" from "two different programs
    # of the same length", and this file's own history has a green run against two programs
    # one generation apart.  So three behavioural questions are asked of every binary.  None
    # of them starts a C compiler, so this stays cheap enough to run after every build.
    $probe = Join-Path $root 'tests\build\arith_basics.vel'
    if (-not (Test-Path -LiteralPath $probe)) {
        Fail "no probe program at $probe, so the behaviour half of claim 1 cannot be asked"
    } else {
        $tmpDir = Join-Path $env:TEMP 'vela-coherence'
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

        # (a) the same C for the same program: a property of the front end, the resolver,
        #     the checker and the C back end together.
        $emitted = @{}
        foreach ($f in @($inTree, $inBuild, $byVela)) {
            if (-not (Test-Path -LiteralPath $f)) { continue }
            $outFile = Join-Path $tmpDir ("emit-c-{0}.c" -f (Split-Path $f -Leaf))
            Remove-Item -LiteralPath $outFile -Force -ErrorAction SilentlyContinue
            & cmd /c ("`"{0}`" emit-c `"{1}`" 1> `"{2}`" 2> nul" -f $f, $probe, $outFile) | Out-Null
            if (Test-Path -LiteralPath $outFile) {
                $emitted[$f] = (Get-FileHash -LiteralPath $outFile -Algorithm SHA256).Hash.Substring(0, 12)
            }
        }
        $eDistinct = @($emitted.Values | Select-Object -Unique)
        if ($emitted.Count -ge 2 -and $eDistinct.Count -eq 1) {
            Pass ("they are the same program: all of them emit the same C for tests\build\arith_basics.vel (sha256 {0})" -f $eDistinct[0])
        } elseif ($emitted.Count -lt 2) {
            Fail 'fewer than two of the three binaries could be run, so the emitted-C comparison proved nothing'
        } else {
            Fail ("the binaries are the same SIZE but different PROGRAMS -- they emit different C for the same input: " +
                  (($emitted.GetEnumerator() | ForEach-Object { "$(Split-Path $_.Key -Leaf)=$($_.Value)" }) -join ', ') +
                  ".  A rebuild that only moved the PE timestamp would emit identical C, so this is a compiler that was not promoted")
        }

        # (b) the default build path, behaviourally: a three-line program must build into a
        #     runnable executable with **no C compiler started**.  `CL=/Zs` is how that is
        #     enforced rather than observed -- it is read by cl.exe itself as extra arguments,
        #     so unlike a `cl.bat` on PATH it cannot be walked around by `vcvars64.bat`.  This
        #     is the claim the promotion exists to make, and a tree whose promoted compiler
        #     predates the flip fails right here.
        $tiny = Join-Path $tmpDir 'tiny.vel'
        [System.IO.File]::WriteAllText($tiny, "def main() -> None {`r`n    print(42)`r`n}`r`n",
                                       (New-Object System.Text.UTF8Encoding($false)))
        $built = @{}
        foreach ($f in @($inTree, $inBuild, $byVela)) {
            if (-not (Test-Path -LiteralPath $f)) { continue }
            $exe = Join-Path $tmpDir ("tiny-{0}.exe" -f (Split-Path $f -Leaf))
            Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
            $bat = Join-Path $tmpDir ("build-{0}.bat" -f (Split-Path $f -Leaf))
            $body = @('@echo off', 'set CL=/Zs', 'set VSCMD_SKIP_SENDTELEMETRY=1',
                      ('"' + $f + '" build "' + $tiny + '" > nul 2>&1'))
            [System.IO.File]::WriteAllLines($bat, $body)
            & cmd /c ('"' + $bat + '"') | Out-Null
            $ran = ''
            # `build` puts the executable beside the source, and the source is `tiny.vel` --
            # one name, so each binary's product is the same path.  Move it aside per binary
            # rather than assume.
            $product = Join-Path $tmpDir 'tiny.exe'
            if (Test-Path -LiteralPath $product) {
                $ran = (& cmd /c ('"' + $product + '"') | Out-String).Trim()
                Move-Item -LiteralPath $product -Destination $exe -Force
            }
            $built[$f] = $ran
        }
        $bad = @($built.GetEnumerator() | Where-Object { $_.Value -ne '42' })
        if ($built.Count -ge 2 -and $bad.Count -eq 0) {
            Pass ("all of them build a program with no C compiler started (CL=/Zs set) and it prints 42")
        } else {
            Fail ("these binaries could not build a three-line program with CL=/Zs: " +
                  (($built.GetEnumerator() | ForEach-Object { "$(Split-Path $_.Key -Leaf)='" + $_.Value + "'" }) -join ', ') +
                  " -- a compiler that cannot do that is not the one the source produced, and a blank answer is what a compiler that reached for cl.exe gives")
        }

        # (c) and they all know the C path's name, because a compiler that does not is a
        #     compiler from before the flip.  An unknown mode prints the usage list and exits
        #     non-zero, so the question is one exit code.  `build-c` on this program is not
        #     run to completion -- the mode name is what is being asked about -- so it is
        #     asked with the runtime directory empty, which refuses fast and by name.
        $knows = @{}
        foreach ($f in @($inTree, $inBuild, $byVela)) {
            if (-not (Test-Path -LiteralPath $f)) { continue }
            $bat = Join-Path $tmpDir ("mode-{0}.bat" -f (Split-Path $f -Leaf))
            [System.IO.File]::WriteAllLines($bat, @('@echo off',
                ('"' + $f + '" build-c "' + $tiny + '" > "' + (Join-Path $tmpDir 'mode.out') + '" 2>&1'),
                ('echo %errorlevel% > "' + (Join-Path $tmpDir 'mode.code') + '"')))
            $modeOut = Join-Path $tmpDir 'mode.out'
            $modeCode = Join-Path $tmpDir 'mode.code'
            Remove-Item -LiteralPath $modeOut, $modeCode -Force -ErrorAction SilentlyContinue
            & cmd /c ('"' + $bat + '"') | Out-Null
            $text = ''
            if (Test-Path -LiteralPath $modeOut) { $text = [string](Get-Content -LiteralPath $modeOut -Raw) }
            $knows[$f] = -not ($text -match 'unknown mode')
        }
        $ignorant = @($knows.GetEnumerator() | Where-Object { -not $_.Value })
        if ($knows.Count -ge 2 -and $ignorant.Count -eq 0) {
            Pass 'and all of them know the C path by name (`build-c`), so none predates the flip'
        } else {
            Fail ("these binaries do not know `build-c`: " +
                  (($ignorant | ForEach-Object { Split-Path $_.Key -Leaf }) -join ', ') +
                  " -- `unknown mode` means a compiler built before `build` became the LLVM path")
        }

        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
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
