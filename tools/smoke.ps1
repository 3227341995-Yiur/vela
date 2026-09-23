# tools\smoke.ps1 — is this checkout alive?
#
# One command that answers the only question that matters after a change, a
# session restart, or a wedged shell:
#
#   * can the compiler in this tree still build and run the smallest program?
#   * do the compiled and the interpreted paths agree on an answer?
#   * are the two detached helpers `cl.exe` leaves behind still running?
#
#     powershell -ExecutionPolicy Bypass -File tools\smoke.ps1
#
# Exit status is 0 only when every check passed.
#
# Why this exists as a tool rather than as a habit: four separate defects in this
# project were of the shape "the tree cannot build anything, and nobody noticed
# for several rounds".  A header that lost `vela_bounds_check` left every program
# unlinkable; a wrong inequality in `vela_sub_range` made every subtraction panic
# in compiled code; an include directory one level too high failed every build
# from a directory other than the root; and the harness that runs our commands
# died three times because of the next paragraph.  Each of those is a five-second
# check.
#
# On `vctip.exe` / `mspdbsrv.exe`: `cl.exe` starts both as *detached* processes.
# They outlive the build by minutes or hours, they inherit the handles of whatever
# started them, and while one is alive a Windows job object is never empty — which
# on this project means every later command fails with
#
#     Error: subprocess-local: Windows Job runner exited with exit code 1
#     before proving its managed range empty
#
# The compiler driver now passes `VSCMD_SKIP_SENDTELEMETRY=1` so the telemetry
# client is never started, and this script cleans up whatever a build left behind
# anyway.  **Run it from a terminal outside the harness** when a session is
# wedged: clearing these two is what brings the shell back.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$vm = Join-Path $root 'selfhost\build\vm.exe'
$fail = $false
$n = 0

function Say($t) { Write-Host $t }
function Step($t) { $script:n++; Say ''; Say ("[$($script:n)] " + $t) }
function Ok($t)   { Say "    ok   $t" }
function Bad($t)  { Say "    FAIL $t"; $script:fail = $true }

Say "Vela smoke test — $root"
Say ("  " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

# ---------------------------------------------------------------- 0. strays
#
# **Ownership, not names.**  This step used to kill every `vctip`, `mspdbsrv`, `cl`
# and `link` on the machine, which is a sweep that takes a *different* checkout's
# in-flight compile with it -- and `mspdbsrv` is a *shared* PDB server, so killing
# it breaks somebody else's `cl` in a way that names neither this script nor a PDB
# server.  Measured 2026-09-24: a suite run failed on a `build-c` row with stderr
# truncated before the driver printed its own command line, three times on three
# different rows, while another agent's `build.ps1` was running.
#
# What is left is the part that was ever per-checkout: helpers of *this* checkout's
# own compiler, identified by where the executable lives.  A process whose `Path`
# cannot be read is reported and not killed, because the old failure mode was
# killing too much and an empty path is not evidence of ownership.
Step 'detached compiler helpers'
$heres  = @()
$theirs = @()
foreach ($name in @('vctip', 'mspdbsrv', 'cl', 'link')) {
    foreach ($p in @(Get-Process -Name $name -ErrorAction SilentlyContinue)) {
        $path = ''
        try { $path = $p.Path } catch { $path = '' }
        if ($path -and $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
            $heres += $p
        } else {
            $theirs += [pscustomobject]@{ Name = $p.ProcessName; Id = $p.Id; Path = $path }
        }
    }
}
if ($heres.Count -eq 0) {
    Ok 'no helper of this checkout is running'
} else {
    foreach ($p in $heres) {
        Say ("    found  " + $p.ProcessName + " pid=" + $p.Id + " path=" + $p.Path)
    }
    $heres | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    Ok "killed $($heres.Count) of this checkout's own"
}
if ($theirs.Count -gt 0) {
    Say ("    left alone: $($theirs.Count) process(es) that are not this checkout's -- a sweep")
    Say '    by image name would have killed them and their agent''s build with them:'
    foreach ($t in ($theirs | Select-Object -First 6)) {
        Say ("      " + $t.Name + "  pid=" + $t.Id + "  " + $(if ($t.Path) { $t.Path } else { '(path not openable)' }))
    }
    if ($theirs.Count -gt 6) { Say ("      ... and " + ($theirs.Count - 6) + " more") }
    Say '    (a `vctip`/`mspdbsrv` left behind is the price; see tools\build.ps1''s sweep)'
}

# --------------------------------------------------------------- 1. compiler
Step 'the compiler is present'
if (Test-Path $vm) {
    $i = Get-Item $vm
    Ok ("selfhost\build\vm.exe  " + $i.Length + " B  " + $i.LastWriteTime.ToString('HH:mm:ss'))
} else {
    Bad 'selfhost\build\vm.exe is missing'
    Say ''
    Say 'RESULT: no compiler — nothing else can be checked'
    exit 1
}

# --------------------------------------------------------- 2. build and run
# ------------------------------------------- 1b. the compiler's code generator
# `vm.exe` carries libLLVM inside it (`selfhost/LLVM_PLAN.md` step 5), so
# `LLVM-C.dll` is a *load-time* dependency of the compiler: without it beside the
# executable, Windows refuses to start `vm.exe` at all -- before `main`, with an
# error that says nothing about LLVM, so every check below would fail with a
# message that names the wrong problem.  `tools\build.ps1` copies it beside every
# vm.exe it writes and refuses to finish if it is not there; this is the check
# that it is still there afterwards.
#
# `vela_llvm_runtime.obj` is the other half: it is what `vm.exe build-llvm` links
# into every program it builds, and the driver looks for it beside the compiler.
Step 'the compiler carries its code generator'
foreach ($pair in @(
    @('LLVM-C.dll', 'the load-time dependency of vm.exe'),
    @('vela_llvm_runtime.obj', 'what build-llvm links into every program'))) {
    $f = Join-Path (Split-Path -Parent $vm) $pair[0]
    if (Test-Path -LiteralPath $f) {
        Ok ("$($pair[0])  " + (Get-Item -LiteralPath $f).Length + " B  ($($pair[1]))")
    } else {
        Bad "$f is missing -- $($pair[1]); run tools\build.ps1"
    }
}

# --------------------------------------------------------- 2. build and run
Step 'build and run the smallest program (from the repository root)'
$hello = Join-Path $root 'examples\hello.vel'
Set-Location $root
Remove-Item 'examples\hello.exe', 'examples\hello.obj' -ErrorAction SilentlyContinue
& $vm build $hello *> $null
if ($LASTEXITCODE -eq 0 -and (Test-Path 'examples\hello.exe')) {
    Ok 'build exits 0 and writes examples\hello.exe'
    $out = & 'examples\hello.exe' 2>&1
    if ($LASTEXITCODE -eq 0) {
        Ok ("runs, exit 0, first line: " + (@($out)[0]))
    } else {
        Bad "the program exited $LASTEXITCODE"
    }
} else {
    Bad "build exited $LASTEXITCODE"
    & $vm build $hello 2>&1 | Select-Object -Last 4 | ForEach-Object { Say "    | $_" }
}

# --------------------------------------------- 3. the same from elsewhere
# Two things are checked here, and the second one is the project's north star.
#
# (a) The build must not depend on where the caller is standing.  This was a real
#     defect: the include directory came out one level too high, so every build from
#     any directory but the root died with
#     `fatal error C1083: cannot open include file: 'vela_runtime.h'` — which is what
#     the user saw when their IDE ran a program.
#
# (b) `vm.exe build x.vel` must leave `x.exe` in the user's directory and **nothing
#     else**: no `.c`, no `.obj`, no `.ll`, no `.pdb`.  That is the whole point of a
#     language whose product is its own output, and it is the kind of property that
#     decays silently — the C backend wrote all four of those beside the source until
#     the scratch directory moved into %TEMP%, and the plan for the LLVM backend had
#     the same mistake written into it (`<stem>.ll` beside the source) until this
#     check existed.
Step 'build from outside the repository, and count what it leaves behind'
$tmp = Join-Path $env:TEMP 'vela-smoke-userdir'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Copy-Item $hello (Join-Path $tmp 'hello.vel') -Force
Push-Location $tmp
& $vm build (Join-Path $tmp 'hello.vel') *> $null
$code = $LASTEXITCODE
Pop-Location
if ($code -eq 0 -and (Test-Path (Join-Path $tmp 'hello.exe'))) {
    Ok 'exit 0, and the executable lands beside its source'
} else {
    Bad "exit $code — the include path is still relative to the caller"
}
$allowed = @('hello.vel', 'hello.exe')
$strays = @(Get-ChildItem -LiteralPath $tmp -File | Where-Object { $allowed -notcontains $_.Name })
if ($strays.Count -eq 0) {
    Ok "the user's directory holds exactly $($allowed -join ' and ') — no other language's file"
} else {
    $names = ($strays | ForEach-Object { $_.Name }) -join ', '
    Bad "the build left $($strays.Count) file(s) beside the source that are not the executable: $names"
    foreach ($s in $strays) { Say ("        " + $s.Name + "  " + $s.Length + " B") }
}

# ------------------------------------------------------- 4. checked arithmetic
# Both paths must agree, including on the answer that stops the program.  Three
# of the last four defects were "compiled disagrees with interpreted".
Step 'compiled and interpreted agree, including when they refuse'
$probe = Join-Path $tmp 'parity.vel'
@'
def one() -> int {
    return 1
}

def main() -> None {
    mut a: int = 7
    mut b: int = 3
    print(a - b, a + b, a * b)
    print(1 - 5)
    mut x: int = 9223372036854775807
    x = x + one()
    print(x)
}
'@ | Set-Content -LiteralPath $probe -Encoding utf8
& $vm build $probe *> $null
if ($LASTEXITCODE -ne 0) {
    Bad "the parity probe would not build (exit $LASTEXITCODE)"
} else {
    $compiled = (& (Join-Path $tmp 'parity.exe') 2>&1 | Out-String).Trim()
    $cc = $LASTEXITCODE
    $interpreted = (& $vm run $probe 2>&1 | Out-String).Trim()
    $ic = $LASTEXITCODE
    # The interpreter appends its own "the interpreted program stopped" line and
    # prints the location with an absolute path; the comparison is on the panic
    # message and the exit status, which is what a reader acts on.
    $cl = ($compiled -split "`r?`n" | Where-Object { $_ -match 'vela: (panic|error)' } | Select-Object -First 1)
    $il = ($interpreted -split "`r?`n" | Where-Object { $_ -match 'vela: (panic|error)' } | Select-Object -First 1)
    if ($cc -eq $ic -and $cl -eq $il -and $cl) {
        Ok "both stop with exit $cc and the same message: $cl"
    } else {
        Bad "compiled exit $cc / interpreted exit $ic"
        Say "    | compiled:    $cl"
        Say "    | interpreted: $il"
    }
    # Compare the two paths with each other rather than against a string written
    # here.  The first version of this check hard-coded `10 4 21` — an order
    # copied from a different program — and failed on a probe that was printing
    # `4 10 21` correctly on both paths.  A check that knows the answer
    # independently of the program under test reports its own transcription
    # mistakes as the program's, which is the failure mode this whole file exists
    # to avoid.
    $cfirst = ($compiled -split "`r?`n" | Where-Object { $_ -match '\d' } | Select-Object -First 1)
    $ifirst = ($interpreted -split "`r?`n" | Where-Object { $_ -match '\d' } | Select-Object -First 1)
    if ($cfirst -and $cfirst -eq $ifirst) {
        Ok "plain arithmetic agrees on both paths: $cfirst"
    } else {
        Bad "plain arithmetic differs: compiled [$cfirst] interpreted [$ifirst]"
    }
}

Say ''
if ($fail) { Say 'RESULT: FAILED'; exit 1 }
Say 'RESULT: ok'
exit 0
