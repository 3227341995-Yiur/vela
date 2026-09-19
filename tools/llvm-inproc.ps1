<#
.SYNOPSIS
    The in-process LLVM back end, proved differentially and with raw output.

.DESCRIPTION
    `vm.exe build-llvm x.vel` is the north star's first real step: the compiler
    builds the module *itself* through `runtime/vela_llvm_shim.h`, writes the
    COFF object itself, and leaves nothing in the user's directory but
    `x.exe` -- with no C compiler anywhere on the path.  This script is the
    evidence for that claim, and every claim it makes is a comparison of bytes
    that were actually produced by running something.

    What it proves, step by step (each one prints its raw evidence):

      [1] `examples\hello.vel` and `selfhost\llvm\m1_probe.vel` are run three
          ways -- the interpreter (`vm.exe run`), the C back end (`vm.exe
          build` + run) and the LLVM back end (`vm.exe build-llvm` + run) --
          and all three must print **byte-identical** stdout and exit with the
          same status.  For the M1 program there is a fourth expectation that
          nothing was recorded from: the 24 bytes written down in
          `selfhost/llvm/m1_probe.vel` and in `tools/llvm-shim-probe.ps1`'s own
          `$expectedM1`.

      [2] the user's directory is listed **before and after** each build, and
          it must hold exactly `<stem>.vel` and `<stem>.exe`.  The `.ll` and the
          `.obj` must be in the build scratch
          (`%TEMP%\vela-build\<flattened path>.*`), which is listed too.

      [3] the only external program `build-llvm` runs is `lld-link`.  How that
          was proved, rather than asserted:
            * the whole build runs with `PATH` holding only a sabotage
              directory, the LLVM `bin` and `System32`, and with a stub
              `vcvars` (set through `VELA_VCVARS`) that keeps the CRT's `LIB`
              but *restores* that minimal `PATH` -- so no MSVC directory is on
              `PATH` at all;
            * the sabotage directory holds `cl.bat`, `clang.bat` and
              `clang-cl.bat`, each of which writes a marker file and exits 97,
              and the marker is checked for *before* and *after*: a build that
              touched a C compiler leaves the file, and nothing else can;
            * the driver writes the link command it runs to the scratch
              (`<scratch>.obj_lld_cmd.txt`), and that log is printed and checked
              to name `lld-link` and no C compiler.

      [4] the proof can fail, and does, twice, on purpose:
            * a program the LLVM back end cannot compile yet is fed to
              `build-llvm`, which must exit non-zero, name the construct, and
              leave no executable behind;
            * the byte comparison itself is run against a deliberately mutated
              expectation, which must be reported as a divergence at a named
              byte -- a comparison that cannot fail is not a comparison.

    Two host facts this script had to get right, both inherited from
    `tools/llvm-shim-probe.ps1`, which paid for them first:

      * **the redirection has to live inside the batch file.**  Windows
        PowerShell 5.1 turns a native command's stderr into a terminating error
        under `$ErrorActionPreference='Stop'`, and `1> file` re-encodes stdout
        as UTF-16 with a BOM -- which would silently destroy a byte comparison
        (measured: a 24-byte program output arrived as 50 bytes of UTF-16LE).
        So `cmd` does the redirecting, inside a `.bat`, and PowerShell never
        sees the stream.
      * **`cl.exe` leaves detached helpers.**  Every step here that could touch
        the MSVC toolchain reaps `vctip.exe`/`mspdbsrv.exe` immediately, because
        while one is alive the harness that drives this project refuses to run
        anything else.

.PARAMETER LlvmDir
    The unpacked LLVM package (`bin\lld-link.exe`, `bin\LLVM-C.dll`,
    `lib\LLVM-C.lib`, `include\llvm-c`).  Defaults to the tree
    `tools\get-llvm.ps1` unpacks beside this checkout, the same default
    `tools\build.ps1` uses.

.PARAMETER Scratch
    Where every product of this run goes.  Defaults to
    `$env:TEMP\vela-inproc`.  Nothing of this script is ever written inside the
    repository except through `build-llvm` itself, whose products are the point.

.PARAMETER AllowGap
    Record one known gap as expected instead of failing the run.  Every gap this
    back end has is refused *by name* rather than compiled wrongly, and a refusal
    is a legitimate state for a slice -- but it is not the claim this script
    exists to check, so by default a program that the LLVM back end refuses fails
    here, with the refusal printed.  This switch is for recording such a gap
    deliberately while it is being worked on, rather than for making the run
    green.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\llvm-inproc.ps1
    powershell -ExecutionPolicy Bypass -File tools\llvm-inproc.ps1 -AllowGap

.NOTES
    Exit 0 means every check passed with the raw bytes printed.  Anything that
    differs is reported with the first differing byte and a non-zero exit.
#>
[CmdletBinding()]
param(
    [string] $LlvmDir,
    [string] $Scratch,
    [switch] $AllowGap
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$fail = $false
$checks = 0
$gaps = 0

function Say([string] $text) { Write-Host $text }
function Head([string] $text) { Write-Host ''; Write-Host $text }
function Ok([string] $text) { $script:checks++; Write-Host "    ok   $text" }
function Bad([string] $text) { $script:checks++; $script:fail = $true; Write-Host "    FAIL $text" }
function Gap([string] $text) {
    $script:gaps++
    Write-Host "    GAP  $text"
    if (-not $AllowGap) { $script:fail = $true }
}

# --------------------------------------------------------------- the tools
if (-not $LlvmDir) {
    $LlvmDir = Join-Path (Split-Path -Parent $repo) 'llvm\clang+llvm-23.1.1-x86_64-pc-windows-msvc'
}
$lldLink = Join-Path $LlvmDir 'bin\lld-link.exe'
$llvmDll = Join-Path $LlvmDir 'bin\LLVM-C.dll'
$vm = Join-Path $repo 'selfhost\build\vm.exe'
if (-not $Scratch) { $Scratch = Join-Path $env:TEMP 'vela-inproc' }

Say 'LLVM in-process back end: three paths, identical bytes, lld-link only'
Say ''
Say "  repository : $repo"
Say "  LLVM       : $LlvmDir"
Say "  compiler   : $vm"
Say "  scratch    : $Scratch"

$missing = @()
foreach ($p in @($vm, $lldLink, $llvmDll,
                 (Join-Path (Split-Path -Parent $vm) 'LLVM-C.dll'),
                 (Join-Path (Split-Path -Parent $vm) 'vela_llvm_runtime.obj'))) {
    if (-not (Test-Path -LiteralPath $p)) { $missing += $p }
}
if ($missing.Count -gt 0) {
    foreach ($m in $missing) { Say "  !! missing: $m" }
    Say ''
    Say 'RESULT: no evidence -- run tools\build.ps1 first (it writes the runtime'
    Say '        object and LLVM-C.dll beside every vm.exe it builds)'
    exit 1
}
# does the compiler in the tree have the modes at all?  A tree built before this
# work has no `build-llvm`, and that is worth one sentence rather than a
# confusing failure three steps later.
$vmText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($vm))
if (-not $vmText.Contains('build-llvm')) {
    Say ''
    Say 'RESULT: the compiler in the tree has no `build-llvm` mode - rebuild it'
    Say '        (tools\build.ps1) and run this script again'
    exit 1
}
Say '  the compiler carries the LLVM back end (it knows `build-llvm`)'

Remove-Item $Scratch -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Scratch | Out-Null

# ------------------------------------------------------- running a batch
# Every native step goes through cmd with the redirection *inside* the batch:
# see .DESCRIPTION for the two measurements that force it.
function Invoke-Batch([string[]] $lines, [string] $tag, [string] $envPath) {
    $bat = Join-Path $Scratch "step_$tag.bat"
    $log = Join-Path $Scratch "log_$tag.txt"
    $body = @('@echo off')
    if ($envPath) { $body += "set PATH=$envPath" }
    $body += "cd /d `"$Scratch`""
    $codes = @()
    $n = 0
    foreach ($line in $lines) {
        $n++
        $codeFile = Join-Path $Scratch ("code_${tag}_$n.txt")
        # a line that carries its own redirection (a program whose *bytes* are
        # the point of running it) is prefixed with `!` and run verbatim: cmd
        # applies the last redirection of each stream, so appending `>> log 2>&1`
        # to it would send the bytes to the log instead of to the file being
        # compared.
        if ($line.StartsWith('!')) { $body += $line.Substring(1) }
        else { $body += "$line >> `"$log`" 2>&1" }
        $body += "echo %errorlevel% > `"$codeFile`""
        $codes += $codeFile
    }
    [System.IO.File]::WriteAllText($bat, ($body -join "`r`n") + "`r`n")
    $o = Join-Path $Scratch "run_$tag.out"
    $e = Join-Path $Scratch "run_$tag.err"
    & cmd.exe /c $bat 1> $o 2> $e
    $results = @()
    foreach ($c in $codes) {
        if (Test-Path -LiteralPath $c) { $results += [int]((Get-Content -LiteralPath $c -Raw).Trim()) }
        else { $results += -999 }   # -999, never -1: -1 is a value cmd can really report
    }
    $text = if (Test-Path -LiteralPath $log) { [string](Get-Content -LiteralPath $log -Raw) } else { '' }
    return @{ codes = $results; log = $text }
}

# `cl.exe` starts detached helpers that outlive it and can wedge the job runner
# this project is driven by, so this runs after every step that could touch the
# MSVC toolchain -- not once at the end, because a script killed in between never
# gets there.
function Reap-MsvcHelpers {
    foreach ($name in @('vctip', 'mspdbsrv')) {
        $strays = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
        if ($strays.Count -gt 0) {
            $strays | Stop-Process -Force -ErrorAction SilentlyContinue
            Say "    reaped $($strays.Count) detached $name process(es)"
        }
    }
}

# --------------------------------------------------------------- byte helpers
function Read-Bytes([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    return , ([System.IO.File]::ReadAllBytes($path))
}

function Bytelist($bytes) {
    if ($null -eq $bytes) { return '(no file)' }
    if ($bytes.Count -eq 0) { return '(empty)' }
    $out = @()
    foreach ($b in $bytes) { $out += [int]$b }
    return ($out -join ',')
}

# The whole point of this file: a comparison that *can* report a difference, and
# says which byte it was.  `[4]` feeds it a mutated expectation to prove that it
# still can.
function First-Difference($a, $b) {
    if ($null -eq $a) { return 'the expected side does not exist' }
    if ($null -eq $b) { return 'the produced side does not exist' }
    $n = [Math]::Min($a.Count, $b.Count)
    for ($i = 0; $i -lt $n; $i++) {
        if ($a[$i] -ne $b[$i]) {
            return "byte $i : expected $($a[$i]), got $($b[$i])"
        }
    }
    if ($a.Count -ne $b.Count) {
        return "the lengths differ: expected $($a.Count) bytes, got $($b.Count)"
    }
    return ''
}

# The expected bytes for the M1 program, written down in
# `selfhost/llvm/m1_probe.vel` and in `tools/llvm-shim-probe.ps1` -- never
# recorded from a run, because a back end that is fast and wrong is not a back
# end.  CR LF because MSVC's stdout is in text mode and every golden in
# `tests/golden` holds CR LF.
$expectedM1 = [System.Text.Encoding]::ASCII.GetBytes("hello from Vela`r`nm1: 3`r`n")

# --------------------------------------------------------------- one program
# A program is built and run three ways in a directory of its own, so that "what
# did the build leave behind?" is a question with a knowable answer.
function Test-Program([string] $tag, [string] $source, [byte[]] $expect,
                      [switch] $NoLlm) {
    Head "[$tag] three ways: interpreter, C back end, LLVM back end"
    $dir = Join-Path $Scratch "user_$tag"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item -LiteralPath $source -Destination (Join-Path $dir (Split-Path -Leaf $source)) -Force
    $src = Join-Path $dir (Split-Path -Leaf $source)
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($source)
    $exe = Join-Path $dir "$stem.exe"

    Say "  directory before any build:"
    foreach ($f in (Get-ChildItem -LiteralPath $dir -File | Sort-Object Name)) {
        Say ("    " + $f.Name + "  " + $f.Length + " B")
    }

    # --- 1. the interpreter, which needs no code generator at all ------------
    $r = Invoke-Batch @(
        "!`"$vm`" run `"$src`" > interp_$tag.out 2> interp_$tag.err"
    ) "interp_$tag"
    $interp = Read-Bytes (Join-Path $Scratch "interp_$tag.out")
    $interpCode = $r.codes[0]
    Say "  interpreter : exit $interpCode, $($interp.Count) bytes"
    Say "                $((Bytelist $interp))"

    # --- 2. the C back end, the reference implementation ---------------------
    $r = Invoke-Batch @(
        "!`"$vm`" build `"$src`" > cbuild_$tag.out 2>&1",
        "!`"$exe`" > c_$tag.out 2> c_$tag.err"
    ) "cbuild_$tag"
    Reap-MsvcHelpers
    $cCode = $null
    $c = $null
    if ($r.codes[0] -ne 0) {
        Say "  C back end  : vm.exe build failed (exit $($r.codes[0]))"
        Say (Get-Content -LiteralPath (Join-Path $Scratch "cbuild_$tag.out") -Raw)
    } else {
        $cCode = $r.codes[1]
        $c = Read-Bytes (Join-Path $Scratch "c_$tag.out")
        Say "  C back end  : exit $cCode, $($c.Count) bytes"
        Say "                $((Bytelist $c))"
    }
    $exeLen1 = if (Test-Path -LiteralPath $exe) { (Get-Item -LiteralPath $exe).Length } else { 0 }
    Say "  after the C build the user's directory holds:"
    foreach ($f in (Get-ChildItem -LiteralPath $dir -File | Sort-Object Name)) {
        Say ("    " + $f.Name + "  " + $f.Length + " B")
    }
    $allowed = @("$stem.vel", "$stem.exe")
    $strays = @(Get-ChildItem -LiteralPath $dir -File | Where-Object { $allowed -notcontains $_.Name })
    if ($strays.Count -eq 0) {
        Ok "$tag : the C build left exactly $stem.vel and $stem.exe"
    } else {
        Bad "$tag : the C build left $(($strays | ForEach-Object { $_.Name }) -join ', ')"
    }

    # --- 3. the LLVM back end, in process, lld-link only ---------------------
    if ($NoLlm) { return }
    $r = Invoke-Batch @(
        "!`"$vm`" build-llvm `"$src`" > llbuild_$tag.out 2>&1",
        "!`"$exe`" > ll_$tag.out 2> ll_$tag.err"
    ) "llbuild_$tag"
    $llLog = [string](Get-Content -LiteralPath (Join-Path $Scratch "llbuild_$tag.out") -Raw)
    if ($r.codes[0] -ne 0) {
        Say "  LLVM back end: vm.exe build-llvm exited $($r.codes[0]); its own words:"
        foreach ($line in ($llLog -split "`r?`n")) {
            if ($line.Trim() -ne '') { Say "                | $line" }
        }
        if ($exeLen1 -gt 0 -and (Test-Path -LiteralPath $exe)) {
            # `build-llvm` deletes the target first, so an executable here is
            # this build's own work -- except that the C build just wrote one.
            $age = (Get-Date) - (Get-Item -LiteralPath $exe).LastWriteTime
            if ($age.TotalSeconds -lt 3) {
                Bad "$tag : build-llvm failed and still wrote $exe"
            }
        }
        return @{ refused = $true; log = $llLog }
    }
    $llCode = $r.codes[1]
    $ll = Read-Bytes (Join-Path $Scratch "ll_$tag.out")
    Say "  LLVM backend: exit $llCode, $($ll.Count) bytes"
    Say "                $((Bytelist $ll))"

    # --- the three against each other, byte for byte -------------------------
    $d1 = First-Difference $interp $c
    if ($d1 -eq '') { Ok "$tag : interpreter and C back end are byte-identical" }
    else { Bad "$tag : interpreter vs C back end: $d1" }

    $d2 = First-Difference $c $ll
    if ($d2 -eq '') { Ok "$tag : C back end and LLVM back end are byte-identical" }
    else { Bad "$tag : C back end vs LLVM back end: $d2" }

    if ($interpCode -eq $cCode -and $cCode -eq $llCode) {
        Ok "$tag : all three exit $llCode"
    } else {
        Bad "$tag : exit codes differ: interpreter $interpCode, C $cCode, LLVM $llCode"
    }

    if ($null -ne $expect) {
        $d3 = First-Difference $expect $ll
        if ($d3 -eq '') { Ok "$tag : all three equal the recorded $($expect.Count) bytes" }
        else { Bad "$tag : against the recorded bytes: $d3" }
    }

    # --- what the build left in the user's directory -------------------------
    Say "  after the LLVM build the user's directory holds:"
    $left = @(Get-ChildItem -LiteralPath $dir -File | Sort-Object Name)
    foreach ($f in $left) { Say ("    " + $f.Name + "  " + $f.Length + " B") }
    $strays2 = @($left | Where-Object { $allowed -notcontains $_.Name })
    if ($strays2.Count -eq 0) {
        Ok "$tag : the LLVM build left exactly $stem.vel and $stem.exe - no .ll, no .obj, no C"
    } else {
        Bad "$tag : the LLVM build left $(($strays2 | ForEach-Object { $_.Name }) -join ', ')"
    }

    # --- and where the intermediates went ------------------------------------
    $flat = ($src -replace '[:\\/]', '_')
    $ir = Join-Path (Join-Path $env:TEMP 'vela-build') "$flat.ll"
    $obj = Join-Path (Join-Path $env:TEMP 'vela-build') "$flat.obj"
    foreach ($i in @(@('the .ll', $ir), @('the object', $obj))) {
        if (Test-Path -LiteralPath $i[1]) {
            Ok "$tag : $($i[0]) is in the build scratch ($((Get-Item -LiteralPath $i[1]).Length) B)"
        } else {
            Bad "$tag : no $($i[0]) in the build scratch ($($i[1]))"
        }
    }
    # the IR is a readable artefact: say how big and show its first line
    if (Test-Path -LiteralPath $ir) {
        $first = (Get-Content -LiteralPath $ir -TotalCount 1)
        Say "  first line of the .ll: $first"
    }
    return @{ refused = $false; interp = $interp; c = $c; ll = $ll }
}

# =============================================================== [1] the paths
Say ''
Head '[1] examples\hello.vel -- the interpreter, the C back end, the LLVM back end'
$helloResult = Test-Program 'hello' (Join-Path $repo 'examples\hello.vel') $null
if ($helloResult.refused) {
    $why = ($helloResult.log -split "`r?`n" | Where-Object { $_ -match 'vela: (llvm|panic|safety|type)' } | Select-Object -First 1)
    Say ''
    Gap "examples\hello.vel is not built by the LLVM back end yet:`n                | $why`n                its `Array[float, 8]` needs storage from the runtime arena, and`n                `vela_arena_alloc` is static in runtime/vela_runtime.h -- so emitted`n                IR has no symbol to call.  Run with -AllowGap to record this and`n                still require every other check; that gap is why the first claim`n                above is not yet true."
    Say '  the two paths that do run, run:'
    Say "    interpreter : $((Bytelist (Read-Bytes (Join-Path $Scratch 'interp_hello.out'))))"
    Say "    C back end  : $((Bytelist (Read-Bytes (Join-Path $Scratch 'c_hello.out'))))"
} else {
    Say ''
    Ok 'hello: three paths, byte-identical output and exit status'
}

Say ''
Head '[2] selfhost\llvm\m1_probe.vel -- and the 24 bytes written down before it ran'
$null = Test-Program 'm1' (Join-Path $repo 'selfhost\llvm\m1_probe.vel') $expectedM1

# ================================================= [3] lld-link, and nothing else
Say ''
Head '[3] the only external program build-llvm runs is lld-link'
Say '  how this is proved, rather than asserted:'
Say '    * PATH holds a sabotage directory, the LLVM bin and System32 -- and no'
Say '      MSVC directory at all, because VELA_VCVARS points at a stub that keeps'
Say '      the CRT LIB and then restores that PATH;'
Say '    * the sabotage directory holds cl.bat, clang.bat and clang-cl.bat, each'
Say '      of which writes a marker file and exits 97 -- so a build that reached'
Say '      for a C compiler leaves a file behind, and nothing else can;'
Say '    * the link command the driver actually ran is written to the scratch and'
Say '      is printed here.'

$envPath = "$Scratch\sabotage;$LlvmDir\bin;$env:SystemRoot\system32;$env:SystemRoot"
New-Item -ItemType Directory -Force -Path (Join-Path $Scratch 'sabotage') | Out-Null
foreach ($name in @('cl', 'clang', 'clang-cl')) {
    $marker = Join-Path $Scratch "USED_$name.txt"
    [System.IO.File]::WriteAllText((Join-Path $Scratch "sabotage\$name.bat"),
        "@echo off`r`necho $name was invoked >> `"$marker`"`r`nexit /b 97`r`n")
}
# a stub vcvars: the real one is what puts the CRT's import libraries on LIB, and
# it also prepends its own directory to PATH -- which would make a real cl.exe
# reachable again.  So the stub calls the real one and then restores the minimal
# PATH, and the sabotage stays first.
$realVcvars = $env:VELA_VCVARS
if (-not $realVcvars) {
    foreach ($c in @(
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'))) {
        if (Test-Path -LiteralPath $c) { $realVcvars = $c; break }
    }
}
if (-not $realVcvars) {
    Bad 'no vcvars64.bat found: lld-link needs the CRT import libraries on LIB'
} else {
    [System.IO.File]::WriteAllText((Join-Path $Scratch 'stub_vcvars.bat'),
        "@echo off`r`ncall `"$realVcvars`" >nul 2>&1`r`nset PATH=$envPath`r`nexit /b 0`r`n")
    $env:VELA_VCVARS = Join-Path $Scratch 'stub_vcvars.bat'

    # first prove the trap works: the sabotage cl must be what `cl` resolves to,
    # and invoking it must leave the marker.  Without this half, "no marker
    # appeared" would also be what an empty directory produces.
    Remove-Item -LiteralPath (Join-Path $Scratch 'USED_cl.txt') -Force -ErrorAction SilentlyContinue
    $r = Invoke-Batch @(
        'where cl',
        'cl /nologo'
    ) 'trap' $envPath
    Say '  where cl (in the restricted PATH):'
    foreach ($line in ($r.log -split "`r?`n")) { if ($line.Trim() -ne '') { Say "    $line" } }
    if (Test-Path -LiteralPath (Join-Path $Scratch 'USED_cl.txt')) {
        Ok 'the sabotage cl.bat is reachable and records being invoked (the trap works)'
    } else {
        Bad 'the sabotage cl.bat did not run: the trap proves nothing'
    }

    # now the real thing: a full build-llvm with that PATH
    Remove-Item (Join-Path $Scratch 'USED_*.txt') -Force -ErrorAction SilentlyContinue
    $dir3 = Join-Path $Scratch 'user_lldonly'
    New-Item -ItemType Directory -Force -Path $dir3 | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'selfhost\llvm\m1_probe.vel') -Destination (Join-Path $dir3 'm1_probe.vel') -Force
    $r = Invoke-Batch @(
        "!`"$vm`" build-llvm `"$dir3\m1_probe.vel`" > lldonly.out 2>&1",
        "!`"$dir3\m1_probe.exe`" > lldonly_run.out 2> lldonly_run.err"
    ) 'lldonly' $envPath
    $log3 = [string](Get-Content -LiteralPath (Join-Path $Scratch 'lldonly.out') -Raw)
    if ($r.codes[0] -eq 0) {
        Ok "build-llvm succeeded with no C compiler on PATH (exit 0)"
    } else {
        Bad "build-llvm exited $($r.codes[0]) with the restricted PATH"
        Say $log3
    }
    $used = @(Get-ChildItem -LiteralPath $Scratch -Filter 'USED_*.txt' -ErrorAction SilentlyContinue)
    if ($used.Count -eq 0) {
        Ok 'no cl.bat, clang.bat or clang-cl.bat was invoked by the build'
    } else {
        Bad "the build invoked: $(($used | ForEach-Object { $_.Name }) -join ', ')"
    }
    $d = First-Difference $expectedM1 (Read-Bytes (Join-Path $Scratch 'lldonly_run.out'))
    if ($d -eq '') { Ok 'the program built with that PATH prints the recorded 24 bytes' }
    else { Bad "the program built with that PATH: $d" }

    # the log the driver writes before it runs the linker
    $linkLogs = @(Get-ChildItem -LiteralPath (Join-Path $env:TEMP 'vela-build') -Filter '*_lld_cmd.txt' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($linkLogs.Count -eq 0) {
        Bad 'the driver wrote no *_lld_cmd.txt: the link command was not recorded'
    } else {
        $ll = $linkLogs[0]
        $cmd = ([string](Get-Content -LiteralPath $ll.FullName -Raw)).Trim()
        Say "  the link command the driver ran ($([System.IO.Path]::GetFileName($ll.FullName))):"
        Say "    $cmd"
        if ($cmd.Contains('lld-link')) { Ok 'the recorded link command names lld-link' }
        else { Bad 'the recorded link command does not name lld-link' }
        # A C compiler is a *token* that names one, not a substring: the first
        # version of this check looked for "clang" anywhere and fired on the
        # linker's own directory (`llvm\clang+llvm-23.1.1-...\bin\lld-link.exe`),
        # which is a check that cannot tell a toolchain's name from a program
        # being run.  Measured, on this script's own first run.
        $bad = @(($cmd -split '\s+') | Where-Object { $_ -match '(?i)(^|\\)(cl|clang|clang-cl)(\.exe)?$' })
        if ($bad.Count -eq 0) {
            Ok 'the recorded link command runs no C compiler (no cl, no clang, no clang-cl token)'
        } else {
            Bad "the recorded link command runs a C compiler: $(($bad) -join ', ')"
        }
    }
    # the environment the driver runs in is also worth printing
    Say '  PATH during that build:'
    Say "    $envPath"
}

# ==================================================== [4] the proof can fail
Say ''
Head '[4] deliberate failures: an unsupported program, and a mutated expectation'

# (a) a construct the LLVM back end refuses, with a named reason and no exe.
#
# It is a `parallel for`, and that one is refused *permanently and by design*
# (`selfhost/LLVM_PLAN.md`: LLVM IR has no OpenMP, and a loop that says it is
# parallel while running serially is the one failure mode this project promised
# never to ship).  The program is one the checker accepts -- it writes distinct
# elements of one array, which is what makes it race-free -- so the refusal under
# test is the back end's, not a verdict from somewhere else.
$dir4 = Join-Path $Scratch 'unsupported'
New-Item -ItemType Directory -Force -Path $dir4 | Out-Null
[System.IO.File]::WriteAllText((Join-Path $dir4 'unsupported.vel'),
    "def main() -> None {`r`n    mut xs: Array[int, 4] = [0, 0, 0, 0]`r`n    parallel for i in range(0, 4) {`r`n        xs[i] = i * 2`r`n    }`r`n    print(xs[0], xs[3])`r`n}`r`n")
Remove-Item (Join-Path $dir4 'unsupported.exe') -Force -ErrorAction SilentlyContinue
$r = Invoke-Batch @(
    "!`"$vm`" build-llvm `"$dir4\unsupported.vel`" > unsupported.out 2>&1"
) 'unsupported'
$log4 = [string](Get-Content -LiteralPath (Join-Path $Scratch 'unsupported.out') -Raw)
Say '  vm.exe build-llvm on a `parallel for` (raw output):'
foreach ($line in ($log4 -split "`r?`n")) { if ($line.Trim() -ne '') { Say "    | $line" } }
Say "  exit code: $($r.codes[0])"
if ($r.codes[0] -ne 0) { Ok 'an unsupported program is refused with a non-zero exit' }
else { Bad 'an unsupported program was accepted' }
if ($log4 -match 'parallel for') { Ok 'the refusal names the construct (`parallel for`)' }
else { Bad 'the refusal does not name the construct' }
if (Test-Path -LiteralPath (Join-Path $dir4 'unsupported.exe')) {
    Bad 'a refused program still produced an executable'
} else {
    Ok 'a refused program produced no executable'
}

# (b) the comparison itself, run against a mutated expectation
$mutated = [byte[]]::new($expectedM1.Length)
[Array]::Copy($expectedM1, $mutated, $expectedM1.Length)
$mutated[3] = $mutated[3] -bxor 0x01
$d = First-Difference $mutated $expectedM1
Say '  a comparison against one mutated byte, on purpose:'
Say "    $d"
if ($d -ne '') { Ok 'the byte comparison detects a single changed byte' }
else { Bad 'the byte comparison cannot fail: it proves nothing' }

# ==================================================================== result
Say ''
Say "checks: $checks, gaps: $gaps"
if ($fail) {
    Say 'RESULT: FAILED -- the raw output above says where'
    exit 1
}
if ($gaps -gt 0) {
    Say "RESULT: ok -- every check passed, with $gaps recorded gap(s) (-AllowGap)"
    exit 0
}
Say 'RESULT: ok -- three paths, identical bytes, lld-link only'
exit 0
