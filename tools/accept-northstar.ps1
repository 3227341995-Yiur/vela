#Requires -Version 5.1
<#
  accept-northstar.ps1 -- the north-star acceptance, as one command.

  The promise under test, stated so that a machine can say no to it:

    1. `vm.exe build-llvm x.vel` produces a native executable whose output is
       byte-identical to what the interpreter prints, with the same exit code.
    2. On that path the only external program started is a LINKER.  No C
       compiler, no assembler driver, no `cl.exe`, no `clang.exe`.
    3. The user's directory gains the executable and nothing else: no `.c`,
       no `.ll`, no `.obj`, no `.pdb`.  Intermediate files belong in the
       build scratch under the system temp directory.
    4. The legacy `build` path, for contrast, is expected to start `cl.exe`.
       That is the known hard gap; this script records it rather than hides it.

  Usage
    powershell -ExecutionPolicy Bypass -File tools\accept-northstar.ps1
    powershell -ExecutionPolicy Bypass -File tools\accept-northstar.ps1 -SelfTest

  -SelfTest judges the LEGACY `build` log against promise (2).  The legacy
  path does start a C compiler, so the run must FAIL.  A run that passes
  -SelfTest means the C-compiler detector is blind, not that the tree is
  clean.  Exit codes: 0 = promise held, 1 = promise broken or self-test
  passed when it should have failed.

  The script is ASCII-only on purpose: a PowerShell script holding non-ASCII
  text without a BOM is read as ANSI by Windows PowerShell, and string
  literals then silently stop matching.
#>
param(
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

function Fail([string]$msg) {
    Write-Host ("  FAIL  " + $msg) -ForegroundColor Red
    $script:bad++
}

function Pass([string]$msg) {
    Write-Host ("  ok    " + $msg) -ForegroundColor Green
}

$script:bad = 0

# Native programs are run with `&`, but with $ErrorActionPreference dropped to
# 'Continue' for the call.  Why: on Windows PowerShell 5.1 a native program
# writing to stderr produces an ErrorRecord on the pipeline, and under
# $ErrorActionPreference = 'Stop' that becomes terminating -- so the compiler's
# own progress lines would read as the script crashing.  The compiler writes
# such lines on every *successful* build, so the naive form fails on good runs,
# which is the worst kind of false alarm.
#
# Start-Process with -RedirectStandardOutput is deliberately not used: it needs
# a pipe to collect the child's output, and this session's file sandbox denies
# that ("Access is denied"), with no escalation available.
function Invoke-Native {
    param(
        [string]$Exe,
        [string[]]$Argv,
        [string]$Stem
    )
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $lines = @()
    try {
        $lines = & $Exe @Argv 2>&1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $saved
    }
    $code = $LASTEXITCODE
    return [pscustomobject]@{ Code = $code; Text = ($lines -join "`n") }
}

# --- locate the checkout from this script, not from the caller's cwd --------
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$vm = Join-Path $root 'selfhost\build\vm.exe'
if (-not (Test-Path $vm)) {
    Write-Host ("no compiler at " + $vm + " -- run tools\build.ps1 first")
    exit 1
}

# --- scratch ---------------------------------------------------------------
# `_*` is gitignored, so a failed run leaves nothing for a commit to pick up.
$dir = Join-Path $root '_accept-northstar'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory $dir | Out-Null

$src = Join-Path $dir 'prog.vel'
$program = @'
def main() -> None {
    mut a: int = 7
    mut b: int = 3
    print(a + b, a - b, a * b)
    print(to_float(a) / to_float(b))
    print(a // b, a % b)
}
'@
# Write without a BOM: the compiler reads the file as bytes.
[System.IO.File]::WriteAllText($src, $program, (New-Object System.Text.UTF8Encoding($false)))

$frozen = (Get-FileHash $vm -Algorithm SHA256).Hash.Substring(0, 8)
Write-Host ("compiler " + $frozen + "  (frozen for this run)")

# The compiler is rebuilt while gates run; a swap under us would make every
# number below a measurement of two different programs.
function Get-WorkingDirEntries {
    Get-ChildItem $dir | ForEach-Object { $_.Name }
}

# --- 1. the interpreter is the reference -----------------------------------
$r = Invoke-Native -Exe $vm -Argv @('run', $src) -Stem 'interp'
$refCode = $r.Code
$refText = $r.Text.TrimEnd("`r", "`n")
Write-Host ""
Write-Host "reference (interpreter):"
$refText -split "`n" | ForEach-Object { Write-Host ("    " + $_) }

$entriesBefore = @(Get-WorkingDirEntries)
if ($entriesBefore.Count -ne 1 -or $entriesBefore[0] -ne 'prog.vel') {
    Fail ("scratch was not clean before the build: " + ($entriesBefore -join ', '))
}

# --- 2. build-llvm, and read the log like a process monitor ----------------
$mode = 'build-llvm'
if ($SelfTest) { $mode = 'build' }

$b = Invoke-Native -Exe $vm -Argv @($mode, $src) -Stem 'build'
$buildCode = $b.Code
$buildText = $b.Text.TrimEnd("`r", "`n")

Write-Host ""
Write-Host ("build log (" + $mode + "):")
$buildText -split "`n" | ForEach-Object { Write-Host ("    " + $_) }

# --- 3. which external programs does that log name? ------------------------
# A C compiler shows up as `cl.exe`, as `clang.exe`, or -- and this is the case
# a first version of this script missed -- as a bare `cl` with no extension at
# all: the legacy path logs `... && cl /nologo /std:c11 ...`.  A detector that
# only looked for `cl.exe` therefore reported "no C compiler" on a build that
# had just started one.  The negative control (-SelfTest) is what caught it.
#
# The bare-token pattern excludes a preceding `-` so that `clang-cl.exe` is not
# read as two hits, and excludes a following word character so that `clang` and
# `clear` are not read as `cl`.  Residual risk: a directory literally named `cl`.
$ccPatterns = @(
    '(?i)\bcl\.exe\b',
    '(?<![A-Za-z0-9_\-])(?i:cl)(?![A-Za-z0-9_.\-])',
    '(?i)\bclang(\+\+)?\.exe\b',
    '(?i)\bclang-cl\.exe\b',
    '(?i)\bgcc\.exe\b',
    '(?i)\bcc\.exe\b'
)
$ccHit = @()
foreach ($p in $ccPatterns) {
    foreach ($m in [regex]::Matches($buildText, $p)) { $ccHit += $m.Value }
}
$linkerHit = [regex]::IsMatch($buildText, '(?i)\b(lld-link|link|ld)\.exe\b|\blld-link\b')

if ($SelfTest) {
    # The checks must be able to fail: on the legacy path a C compiler is
    # started, so `no C compiler` must come out FALSE here.
    if ($ccHit.Count -gt 0) {
        Pass ("self-test: the C-compiler detector fired on the legacy path (" + (($ccHit | Select-Object -Unique) -join ',') + ")")
    } else {
        Fail "self-test: the legacy path started no visible C compiler, so the detector proves nothing"
    }
    Write-Host ""
    Write-Host "SELFTEST RESULT: " -NoNewline
    if ($script:bad -eq 0) {
        Write-Host "the detector is live (it caught the legacy path)"
        exit 0
    } else {
        Write-Host "the detector is BLIND"
        exit 1
    }
}

if ($ccHit.Count -eq 0) {
    Pass "no C compiler in the build log"
} else {
    Fail ("a C compiler was started: " + (($ccHit | Select-Object -Unique) -join ','))
}
if ($linkerHit) {
    Pass "the linker is there"
} else {
    Fail "no linker named in the build log -- was anything linked?"
}

if ($buildCode -ne 0) {
    Fail ("the build exited " + $buildCode)
}

# --- 4. what landed in the user's directory --------------------------------
$entriesAfter = @(Get-WorkingDirEntries)
$added = @($entriesAfter | Where-Object { $entriesBefore -notcontains $_ })
$exeName = 'prog.exe'
$exe = Join-Path $dir $exeName

if ($added.Count -eq 1 -and $added[0] -eq $exeName) {
    Pass ("the directory gained exactly " + $exeName)
} else {
    Fail ("the directory gained: " + ($added -join ', '))
}

$forbidden = @('.c', '.ll', '.obj', '.o', '.pdb', '.ilk', '.exp', '.lib', '.i')
foreach ($ext in $forbidden) {
    $leak = @(Get-ChildItem $dir -Filter ("*" + $ext) -File)
    if ($leak.Count -gt 0) {
        Fail ("another language's file is sitting in the user's directory: " + (($leak | ForEach-Object Name) -join ', '))
    }
}
if ($script:bad -eq 0) { Pass "no intermediate files in the user's directory" }

# --- 5. does the product actually run, and say the same thing? -------------
if (-not (Test-Path $exe)) {
    Fail "no executable was produced"
    Write-Host ""
    Write-Host "RESULT: FAILED"
    exit 1
}

$rr = Invoke-Native -Exe $exe -Argv @() -Stem 'product'
$runCode = $rr.Code
$runText = $rr.Text.TrimEnd("`r", "`n")

if ($runText -eq $refText) {
    Pass "the executable prints exactly what the interpreter prints"
} else {
    Fail "output differs"
    Write-Host "    interpreter: " ($refText -replace "`n", ' | ')
    Write-Host "    executable : " ($runText -replace "`n", ' | ')
}
if ($runCode -eq $refCode) {
    Pass ("the exit code matches (" + $refCode + ")")
} else {
    Fail ("exit codes differ: interpreter " + $refCode + ", executable " + $runCode)
}

# --- 6. was the compiler swapped under us? ---------------------------------
$after = (Get-FileHash $vm -Algorithm SHA256).Hash.Substring(0, 8)
if ($after -eq $frozen) {
    Pass ("the compiler did not change during the run (" + $frozen + ")")
} else {
    Fail ("the compiler was rebuilt mid-run: " + $frozen + " -> " + $after + " -- rerun")
}

Write-Host ""
Write-Host "RESULT: " -NoNewline
if ($script:bad -eq 0) {
    Write-Host ("PASS  (build-llvm: native exe, linker only, directory clean; compiler " + $frozen + ")")
    exit 0
} else {
    Write-Host ("FAILED  (" + $script:bad + " checks)")
    exit 1
}
