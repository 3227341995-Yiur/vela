#Requires -Version 5.1
<#
  selfhost-llvm.ps1 -- can the compiler build ITSELF with no C compiler, and work?

  WHY THIS EXISTS

  This is the north star's hardest test, and until now it was tested by hand, once, and
  recorded in a message: `vm.exe build-llvm selfhost/vm.vel` links the compiler through
  libLLVM in-process with only a linker on the outside -- and the result must be a compiler
  that compiles.  On 2026-09-24 the compiler agent ran exactly that and found the worst
  possible outcome, which is why this file exists:

      built vm.exe  (LLVM in-process; lld-link, no C compiler)      exit=0
      $ built-vm.exe check hello.vel
      vela: syntax error: expected a newline between statements, found ''
        at line 25                     <- on a three-line file

  It links, it runs, and its PARSER is broken: every program fails that way, including
  `print(1)`.  The lexer is byte-identical to the tree compiler's (`lex` dumps diff to
  nothing), so the token cursor runs past the token array and reads a wrapped index -- a
  WRONG ANSWER, which this project treats as worse than a refusal.  Nothing in the tree
  checked it: the corpus and the shape corpus compare the interpreter against `build-llvm`
  for ordinary programs, and neither of them is the compiler's own parser.

  So this is the missing gate: build the compiler with the LLVM path, run the result on a
  three-line program, and require the answer to be right.

  WHAT IT DOES, and the control that makes it a control

    1. copies `selfhost/vm.vel` OUT of the tree (building it in place would overwrite
       `selfhost/vm.exe`, which is the compiler being promoted from);
    2. builds it with `build-llvm`, and puts `LLVM-C.dll` and `vela_llvm_runtime.obj` beside
       the result -- without the DLL the built compiler dies with `0xC0000135` before running
       any of its own code, and that reads as a code failure (measured: exit -1073741515, no
       output);
    3. runs the result on a three-line program: `check` must exit 0 and print `ok`, and `run`
       must print what the program says;
    4. runs the SAME two commands through the tree's own compiler, and requires those to pass.
       That is the control: if the probe program or this script is wrong, the control fails
       too, and a failure of step 3 can then be read as what it is.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\selfhost-llvm.ps1
    powershell -ExecutionPolicy Bypass -File tools\selfhost-llvm.ps1 -Keep   # leave the scratch

  Exit: 0 = RESULT: ok, 1 = RESULT: failed.  RED today, on purpose: the miscompile above is
  real and unfixed, and this script is how the next session can see it, bisect it, and know
  when it is gone.
#>
[CmdletBinding()]
param(
    [switch] $Keep,
    [int] $BudgetSeconds = 120
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$selfSource = Join-Path $root 'selfhost\vm.vel'
$dll = Join-Path $root 'selfhost\build\LLVM-C.dll'
$rtObj = Join-Path $root 'selfhost\build\vela_llvm_runtime.obj'

function Say([string] $t) { Write-Host $t }
$failures = New-Object System.Collections.ArrayList
function Fail([string] $t) { Say ("  FAIL  " + $t); [void]$script:failures.Add($t) }
function Pass([string] $t) { Say ("  ok    " + $t) }

foreach ($f in @($vm, $selfSource)) {
    if (-not (Test-Path -LiteralPath $f)) { Say "RESULT: failed -- missing $f"; exit 1 }
}

$scratch = Join-Path $env:TEMP 'vela-selfhost-llvm'
Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

# The probe program.  Three lines, and it prints something, so `check` and `run` both have an
# answer to be wrong about.  A three-line file that reports `line 25` is the symptom this
# gate was written from.
$probe = Join-Path $scratch 'probe.vel'
[System.IO.File]::WriteAllText($probe, "def main() -> None {`n    print(42)`n}`n", (New-Object System.Text.UTF8Encoding($false)))
$probeExpected = '42'

$treeHash = (Get-FileHash -LiteralPath $vm -Algorithm SHA256).Hash.Substring(0, 8)
Say ("tree compiler : {0} bytes, sha256 {1}" -f (Get-Item -LiteralPath $vm).Length, $treeHash)

# ---------------------------------------------------------------- 1. build the compiler with LLVM
$src = Join-Path $scratch 'vm.vel'
Copy-Item -LiteralPath $selfSource -Destination $src -Force
Say ''
Say '== 1. build-llvm selfhost/vm.vel (a copy, outside the tree)'
$built = Join-Path $scratch 'vm.exe'
$sw = [Diagnostics.Stopwatch]::StartNew()
& cmd /c ("`"{0}`" build-llvm `"{1}`" 1> `"{2}`" 2>&1" -f $vm, $src, (Join-Path $scratch 'build.log')) | Out-Null
$buildCode = $LASTEXITCODE
$sw.Stop()
$buildLog = [System.IO.File]::ReadAllText((Join-Path $scratch 'build.log'))
foreach ($line in ($buildLog -split "`r?`n")) { if ($line.Trim() -ne '') { Say ("    | " + $line.Trim()) } }
if ($buildCode -eq 0 -and (Test-Path -LiteralPath $built)) {
    Pass ("the LLVM path built the compiler ({0} bytes, {1} s, no C compiler)" -f (Get-Item -LiteralPath $built).Length, [int]$sw.Elapsed.TotalSeconds)
} else {
    Fail ("build-llvm could not build the compiler (exit $buildCode)")
}

# Without these two files the built compiler cannot run at all, and the failure looks like a
# code failure rather than a missing file.
foreach ($f in @($dll, $rtObj)) {
    if (Test-Path -LiteralPath $f) { Copy-Item -LiteralPath $f -Destination (Join-Path $scratch (Split-Path $f -Leaf)) -Force }
}

# ---------------------------------------------------------------- 2. run one command through each
function Invoke-Probe {
    param([string] $Compiler, [string] $Verb)
    $out = Join-Path $scratch ("{0}-{1}.out" -f (Split-Path $Compiler -Leaf), $Verb)
    $err = Join-Path $scratch ("{0}-{1}.err" -f (Split-Path $Compiler -Leaf), $Verb)
    Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue
    & cmd /c ("`"{0}`" {1} `"{2}`" 1> `"{3}`" 2> `"{4}`"" -f $Compiler, $Verb, $probe, $out, $err) | Out-Null
    $code = $LASTEXITCODE
    $o = ''
    $e = ''
    if (Test-Path -LiteralPath $out) { $o = [System.IO.File]::ReadAllText($out) }
    if (Test-Path -LiteralPath $err) { $e = [System.IO.File]::ReadAllText($err) }
    return [pscustomobject]@{ Code = $code; Out = $o.Trim(); Err = $e.Trim() }
}

# The control first: if the tree's compiler cannot pass the probe, the probe is the problem.
Say ''
Say '== 2. the control: the same two commands through the tree compiler (must pass)'
$cCheck = Invoke-Probe -Compiler $vm -Verb 'check'
$cRun = Invoke-Probe -Compiler $vm -Verb 'run'
if ($cCheck.Code -eq 0 -and $cCheck.Out -eq 'ok') {
    Pass 'the tree compiler accepts the three-line probe (`check` exit 0, `ok`)'
} else {
    Fail ("the tree compiler failed the probe (check exit {0}) -- the probe or this script is wrong, not the built compiler: {1}" -f $cCheck.Code, ($cCheck.Err -split "`r?`n")[0])
}
if ($cRun.Code -eq 0 -and $cRun.Out -eq $probeExpected) {
    Pass ("the tree compiler runs it: {0}" -f $cRun.Out)
} else {
    Fail ("the tree compiler ran it and printed '{0}' (exit {1}), expected '{2}'" -f $cRun.Out, $cRun.Code, $probeExpected)
}

if (-not (Test-Path -LiteralPath $built)) {
    Say ''
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}

# ---------------------------------------------------------------- 3. the built compiler
Say ''
Say '== 3. the compiler the LLVM path built (this is the north star, and it is RED today)'
$bCheck = Invoke-Probe -Compiler $built -Verb 'check'
Say ("  built compiler: check exit {0}" -f $bCheck.Code)
if ($bCheck.Out -ne '') { Say ("    stdout: " + ($bCheck.Out -replace "`r?`n", ' | ')) }
if ($bCheck.Err -ne '') { foreach ($l in ($bCheck.Err -split "`r?`n" | Select-Object -First 4)) { if ($l.Trim() -ne '') { Say ("    stderr: " + $l.Trim()) } } }
if ($bCheck.Code -eq 0 -and $bCheck.Out -eq 'ok') {
    Pass 'the LLVM-built compiler accepts a three-line program'
} else {
    Fail ("the LLVM-built compiler does not work: `check` on a three-line program exits {0} and says '{1}' -- a silent miscompile of its own parser, not a refusal" -f $bCheck.Code, (($bCheck.Err -split "`r?`n") | Where-Object { $_ -match 'vela:' } | Select-Object -First 1))
}

$bRun = Invoke-Probe -Compiler $built -Verb 'run'
Say ("  built compiler: run   exit {0}   stdout '{1}'" -f $bRun.Code, ($bRun.Out -replace "`r?`n", ' | '))
if ($bRun.Code -eq 0 -and $bRun.Out -eq $probeExpected) {
    Pass ("the LLVM-built compiler runs it: {0}" -f $bRun.Out)
} else {
    Fail ("the LLVM-built compiler printed '{0}' (exit {1}), expected '{2}'" -f $bRun.Out, $bRun.Code, $probeExpected)
}

Say ''
Say ("scratch: " + $scratch + $(if ($Keep) { ' (kept)' } else { ' (delete it yourself, or pass -Keep to keep it)' }))
if ($failures.Count -gt 0) {
    Say ''
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    Say ''
    Say '  To bisect: this is the smallest reproduction of the miscompile that exists today.'
    Say '  The compiler agent ruled out array parameters, mut struct parameters, and a cursor'
    Say '  shape in miniature, all three identical across the paths, so the construct is one'
    Say '  the compiler own parser has and those do not.'
    Say ''
    Say '  AND THE SIGNATURE IS A CONSTANT, which rules out the first hypothesis.  Measured by'
    Say '  running the built compiler on programs of 1, 3, 5 and 10 lines: every one of them'
    Say '  reports `at line 25`, so the wrong value does NOT depend on the input at all.  The'
    Say '  first reading of this failure was "the token cursor runs past the array and takes a'
    Say '  wrapped index" -- that would move with the input.  A constant says a fixed place is'
    Say '  being read where a variable should be, and in an emitted program the usual cause of'
    Say '  that is a STRUCT FIELD OFFSET: the parser reads a token line number and gets a'
    Say '  constant from a neighbouring field.  That would also explain why the lexer is'
    Say '  byte-identical (it is the same source) while only the generated code differs.'
    Say '  Worth checking first, then: what the LLVM back end emits for a field READ of a'
    Say '  multi-field struct against what the C back end emits for the same read -- and'
    Say '  whether any existing shape has a struct whose fields are read out of order.'
    Say '  (An empty file gives a different and sane diagnostic, `could not read the file`, so'
    Say '  the file reader itself is fine.)'
    exit 1
}
Say 'RESULT: ok'
exit 0
