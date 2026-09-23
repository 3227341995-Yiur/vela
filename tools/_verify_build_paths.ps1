# tools\_verify_build_paths.ps1
#
# Verification for the cwd-independence fix in selfhost/parts/vm_main.vel.
# The tree's own corpus is gone, so this does not touch tools/build.ps1 or the
# suite; it builds a *fake tree* outside the repository whose only runtime is
# reachable by walking up from the compiler's own location:
#
#     <scratch>\selfhost\build\vm_fixed.exe     the compiler under test
#     <scratch>\runtime\vela_runtime.h          the header it must find
#     <scratch>\tests\build\control_flow.vel    the program it must build
#     <outside>\                                the directory it must work from
#
# If the driver still used a cwd-relative `runtime`, every build below fails
# with C1083.  Nothing here writes inside the repository except the compiler
# scratch file, which is the pre-existing bootstrap step.
#
# **The mode is `build-c`, and it has to be, because the thing under test is the *include
# path*.**  These three runs passed `build` until 2026-09-24, when `build` became the LLVM
# path: that path never starts a C compiler and never reads `vela_runtime.h` at all, so
# every step here would have become a test of nothing.  Two of the steps failed *loudly*
# when the flip landed -- step 5 found no `control_flow_cc_cmd.txt`, which is the C
# compiler's own command log, and steps 6 and 7's poison and bare trees failed with
# `0xC0000135 (STATUS_DLL_NOT_FOUND)` instead of the C1083 they exist to assert, because a
# copy of `vm.exe` outside the tree has no `LLVM-C.dll` beside it.  A loud failure is a
# lucky one: the include path is exactly what `build-c` drives, so that is what these run.
param([string] $ScratchRoot = (Join-Path $env:TEMP 'vela_buildpath_check'))

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo

function Step([string] $title) { Write-Host ''; Write-Host "=== $title ===" }
function Run([string] $exe, [string[]] $argv, [string] $cwd) {
    Push-Location -LiteralPath $cwd
    try {
        $o = & $exe @argv 2>&1 | Out-String
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    return @{ out = $o.TrimEnd(); code = $code }
}
function Show($r) { Write-Host "--- output (exit $($r.code)) ---"; Write-Host $r.out }

# ------------------------------------------------------------------ the subject
Step '0. link + build the fixed compiler (cwd = repo root)'
$seed = Join-Path $repo 'selfhost\build\vm.exe'
# Re-link first: `selfhost/vm.vel` is generated from the parts, and the compiler
# under test must be the one that includes the current vm_main.vel.  Passing
# `_vm_linked.vel` straight to the build without this step is how the first run
# of this very script tested a stale binary.
& (Join-Path $repo 'tools\link_selfhost.exe') 2>&1 | Out-String | Write-Host
Copy-Item -LiteralPath (Join-Path $repo 'selfhost\vm.vel') -Destination (Join-Path $repo 'tools\_vm_linked.vel') -Force
& $seed build 'tools\_vm_linked.vel' 2>&1 | Out-Null
$fixed = Join-Path $repo 'tools\_vm_linked.exe'
if (-not (Test-Path -LiteralPath $fixed)) { Write-Host 'no fixed compiler built'; exit 1 }
Write-Host "built $fixed  ($((Get-Item -LiteralPath $fixed).LastWriteTime))"

# ------------------------------------------------------------------ fake tree
Step '1. lay out a fake tree outside the repository'
if (Test-Path -LiteralPath $ScratchRoot) { Remove-Item -LiteralPath $ScratchRoot -Recurse -Force }
$fakeBuild = Join-Path $ScratchRoot 'selfhost\build'
$fakeTests = Join-Path $ScratchRoot 'tests\build'
$outside   = Join-Path ([IO.Path]::GetTempPath()) 'vela_buildpath_outside'
foreach ($d in @($fakeBuild, $fakeTests, (Join-Path $ScratchRoot 'runtime'), $outside)) {
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}
Copy-Item -LiteralPath (Join-Path $repo 'runtime\vela_runtime.h') -Destination (Join-Path $ScratchRoot 'runtime\vela_runtime.h') -Force
$subj = Join-Path $fakeBuild 'vm_fixed.exe'
Copy-Item -LiteralPath $fixed -Destination $subj -Force
$program = Join-Path $fakeTests 'control_flow.vel'
[IO.File]::WriteAllText($program, "def main() -> None {`n    mut n: int = 0`n    while n < 3 {`n        n += 1`n    }`n    emit_str(`"control_flow `")`n    emit_int(n)`n    emit_nl()`n}`n", (New-Object Text.UTF8Encoding($false)))
Write-Host "subject : $subj"
Write-Host "runtime : $(Join-Path $ScratchRoot 'runtime\vela_runtime.h')  (reachable from the subject by walking up 2 levels)"
Write-Host "program : $program"
Write-Host "cwd     : $outside   (outside the repository, no runtime/ below it)"

# ------------------------------------------------ baseline for unchanged paths
Step '2. baseline: check/run output today, to prove the fix changes neither'
$baseline = @{}
foreach ($mode in @('check', 'run')) {
    $r = Run $seed @($mode, $program) $outside
    $baseline[$mode] = $r
    Write-Host "-- seed vm.exe $mode (exit $($r.code)):"; Write-Host ($r.out -replace "`r?`n", ' | ')
}
$identical = $true
foreach ($mode in @('check', 'run')) {
    $r = Run $subj @($mode, $program) $outside
    Write-Host "-- fixed compiler $mode (exit $($r.code)):"; Write-Host ($r.out -replace "`r?`n", ' | ')
    if ($r.out -ne $baseline[$mode].out -or $r.code -ne $baseline[$mode].code) {
        $identical = $false
        Write-Host "   DIFFERS from the seed"
    } else { Write-Host "   identical to the seed" }
}
Write-Host "check+run identical to today: $identical"

# --------------------------------------------------------- the fix under test
Step '3. build an ABSOLUTE path from outside the repository'
$r = Run $subj @('build-c', $program) $outside
Show $r
$exe = [IO.Path]::ChangeExtension($program, '.exe')
Write-Host "exe produced: $(Test-Path -LiteralPath $exe)  ($exe)"

Step '4. run the produced binary'
if (Test-Path -LiteralPath $exe) {
    $r = Run $exe @() $outside
    Show $r
}

Step '5. the emitted compile command (evidence of the include path actually used)'
$log = Join-Path $fakeTests 'control_flow_cc_cmd.txt'
if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log | ForEach-Object { Write-Host "  $_" } } else { Write-Host "  (no $log)" }

Step '6. a deliberately broken runtime must fail loudly, in English, with no C4819'
$poison = Join-Path $ScratchRoot 'selfhost\build_poison'
New-Item -ItemType Directory -Path $poison -Force | Out-Null
Copy-Item -LiteralPath $subj -Destination (Join-Path $poison 'vm_poison.exe') -Force
$poisonRT = Join-Path $ScratchRoot 'selfhost\runtime'
New-Item -ItemType Directory -Path $poisonRT -Force | Out-Null
# a header that cannot compile: the walk-up from build_poison finds
# selfhost\runtime first, so this is the one the build must use
[IO.File]::WriteAllText((Join-Path $poisonRT 'vela_runtime.h'), "this is not C;`n", (New-Object Text.UTF8Encoding($false)))
$r = Run (Join-Path $poison 'vm_poison.exe') @('build-c', $program) $outside
Show $r
$bad = $r.out
Write-Host "mentions 'error'        : $($bad -match 'error')"
Write-Host "mentions C4819          : $($bad -match 'C4819')"
Write-Host "mojibake (non-ascii)    : $([bool]($bad -match '[^\x00-\x7F]'))"
Write-Host "C1083 (wrong diagnosis) : $($bad -match 'C1083')"

Step '7. no runtime reachable at all keeps today exact failure'
$bare = Join-Path $env:TEMP 'vela_buildpath_bare'
if (Test-Path -LiteralPath $bare) { Remove-Item -LiteralPath $bare -Recurse -Force }
New-Item -ItemType Directory -Path $bare -Force | Out-Null
Copy-Item -LiteralPath $subj -Destination (Join-Path $bare 'vm_bare.exe') -Force
$r = Run (Join-Path $bare 'vm_bare.exe') @('build-c', $program) $outside
Show $r
Write-Host "mentions C1083: $($r.out -match 'C1083')   exit $($r.code)"

Write-Host ''
Write-Host "scratch tree left at: $ScratchRoot"
