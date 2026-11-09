#Requires -Version 5.1
<#
  demo-check.ps1 -- the demo programs, actually run.

  WHY THIS EXISTS

  `ide-demo\` holds programs written for a human to open in the editor, and
  `ide-demo\expected\*.txt` holds the exact stdout each one must print.  Nothing ran them.
  `tests\cases.txt` does not mention `ide-demo\`, and no script under `tools\` did either, so
  the expected files were a claim about a program that nobody started.  This project has
  already paid for that shape twice: `tools\llvm-column.ps1` reported seven module cases as
  back-end refusals because it copied one file, and `ast-diff` compared against a frozen
  compiler that predated the feature.  A claim that is never executed is decoration.

  WHAT IT JUDGES, per demo that has an `expected\<name>.txt`

    interp   `vm.exe run ide-demo\<name>.vel` prints the expected bytes
    c        `vm.exe build-c <file>` builds, and the binary prints the expected bytes

  Both are checked, because the demo README tells a reader both paths build it.  A program
  the *compiled* back end refuses is not a pass: it is REFUSED, and the line it must move to
  is `tests\llvm-refusals.txt`/`tests\llvm-gaps.txt` or a fix -- a demo that only the
  interpreter runs is a demo the editor's user cannot ship.

  `broken.vel` is the one program here that is *supposed* to fail: it is the intentional type
  error the plugin draws in the editor.  It is judged the other way round -- `check` must
  refuse it -- and the refusal text is not pinned, because the point of the file is that a
  diagnostic appears at all.

  THE FILES WITH NO `expected\` FILE ARE NOT SKIPPED SILENTLY

  `geometry.vel` is a module (no `main`, so nothing to print) and `broken.vel` is judged as a
  refusal.  Every other demo must have an expected file; one without it is MISSING, and that
  is a failure -- adding a demo and forgetting its expectation is exactly how this directory
  got a file nobody ran.

  Exit code is non-zero when anything fails, like every other gate here.
#>
[CmdletBinding()]
param(
    [string] $Vm,
    [switch] $SelfTest
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Vm) { $Vm = Join-Path $root 'selfhost\build\vm.exe' }

$demoDir = Join-Path $root 'ide-demo'
$expectedDir = Join-Path $demoDir 'expected'
$failures = New-Object System.Collections.ArrayList
$counts = @{ match = 0; refused = 0; missing = 0 }

function Say([string] $s) { Write-Host $s }

function Run-Capture([string] $exe, [string] $argLine, [string] $tag) {
    $out = Join-Path $env:TEMP ("demo-$tag.out")
    $err = Join-Path $env:TEMP ("demo-$tag.err")
    $cmd = 'cd /d "' + $root + '" && "' + $exe + '" ' + $argLine + ' > "' + $out + '" 2> "' + $err + '"'
    & cmd.exe /c $cmd | Out-Null
    $code = $LASTEXITCODE
    $bytes = if (Test-Path -LiteralPath $out) { [System.IO.File]::ReadAllBytes($out) } else { [byte[]]@() }
    $text = if ($bytes.Length -gt 0) { [System.Text.Encoding]::UTF8.GetString($bytes) } else { '' }
    $errText = if (Test-Path -LiteralPath $err) { [System.IO.File]::ReadAllText($err) } else { '' }
    return [pscustomobject]@{ Code = $code; Text = $text; Bytes = $bytes; Err = $errText }
}

function Expected([string] $name) {
    $p = Join-Path $expectedDir ($name + '.txt')
    if (-not (Test-Path -LiteralPath $p)) { return $null }
    return [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))
}

Say "== the demo corpus: ide-demo\*.vel"
Say ''

# Which paths each demo takes.  `async.vel` is the one program here that the interpreter
# refuses by design -- `async def` starts a thread and the interpreter has none, which
# is what `ide-demo\README.md` says as well -- so it is judged on the C back end alone.
$demos = Get-ChildItem -LiteralPath $demoDir -Filter '*.vel' -File | Sort-Object Name
$modes = @{ async = 'c' }
foreach ($d in $demos) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($d.Name)
    if ($name -eq 'geometry') {
        Say ("  {0,-10} module (no main, nothing to print)" -f $name)
        continue
    }
    if ($name -eq 'broken') {
        $r = Run-Capture $Vm ('check "' + $d.FullName + '"') $name
        if ($r.Code -ne 0) {
            $first = ($r.Err -split "`r?`n" | Where-Object { $_ -match 'vela:' } | Select-Object -First 1)
            Say ("  {0,-10} refused on purpose: {1}" -f $name, $first)
            $counts.match++
        } else {
            Say ("  {0,-10} FAIL: this file is the deliberate type error and `check` accepted it" -f $name)
            $failures.Add("$name is supposed to be refused and was not") | Out-Null
        }
        continue
    }

    $want = Expected $name
    if ($null -eq $want) {
        Say ("  {0,-10} MISSING: no expected\{0}.txt -- a demo nobody can check" -f $name)
        $failures.Add("$name has no expected\$name.txt") | Out-Null
        $counts.missing++
        continue
    }

    $problems = New-Object System.Collections.ArrayList

    $wantPaths = if ($modes.ContainsKey($name)) { $modes[$name] } else { 'both' }
    if ($wantPaths -ne 'c') {
    $i = Run-Capture $Vm ('run "' + $d.FullName + '"') ($name + '-i')
    if ($i.Code -ne 0) {
        $problems.Add('the interpreter exited ' + $i.Code + ': ' + (($i.Err -split "`r?`n" | Select-Object -First 1))) | Out-Null
    } elseif ($i.Text -ne $want) {
        $problems.Add('the interpreter printed something else') | Out-Null
    }
    }

    if ($wantPaths -eq 'c') { Say ("  {0,-10} interpreter: refused by design (its README says so)" -f $name) }
    $b = Run-Capture $Vm ('build-c "' + $d.FullName + '"') ($name + '-b')
    $exe = [System.IO.Path]::ChangeExtension($d.FullName, '.exe')
    if ($b.Code -ne 0 -or -not (Test-Path -LiteralPath $exe)) {
        $problems.Add('build-c refused it: ' + (($b.Err -split "`r?`n" | Select-Object -First 1))) | Out-Null
    } else {
        $p = Run-Capture $exe '' ($name + '-c')
        if ($p.Code -ne 0) {
            $problems.Add('the built binary exited ' + $p.Code) | Out-Null
        } elseif ($p.Text -ne $want) {
            $problems.Add('the built binary printed something else') | Out-Null
        }
        Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ([System.IO.Path]::ChangeExtension($d.FullName, '.obj')) -Force -ErrorAction SilentlyContinue
    }

    if ($problems.Count -eq 0) {
        $which = if ($wantPaths -eq 'c') { 'the C back end prints' } else { 'interpreter and C back end both print' }
        Say ("  {0,-10} {1} expected\{0}.txt" -f $name, $which)
        $counts.match++
    } else {
        Say ("  {0,-10} FAIL" -f $name)
        foreach ($p in $problems) { Say ("      | " + $p) }
        $failures.Add("$name`: " + ($problems -join '; ')) | Out-Null
    }
}

Say ''
Say ("COVERAGE: matched {0} / missing {1} / failed {2}" -f $counts.match, $counts.missing, $failures.Count)

if ($SelfTest) {
    # The check has to be able to fail: an expected file with one byte more must be noticed.
    $victim = Join-Path $expectedDir 'hello.txt'
    $saved = [System.IO.File]::ReadAllBytes($victim)
    try {
        [System.IO.File]::WriteAllBytes($victim, ($saved + [byte]32))
        $r = Run-Capture $Vm ('run "' + (Join-Path $demoDir 'hello.vel') + '"') 'selftest'
        $want = Expected 'hello'
        if ($r.Text -eq $want) {
            Say 'SELFTEST RESULT: BLIND -- a file one byte longer compared equal'
            exit 1
        }
        Say 'SELFTEST RESULT: the comparison is live (one extra byte failed it)'
    } finally {
        [System.IO.File]::WriteAllBytes($victim, $saved)
    }
    exit 0
}

if ($failures.Count -gt 0) {
    Say ''
    Say 'RESULT: failed'
    foreach ($f in $failures) { Say ("  - " + $f) }
    exit 1
}
Say 'RESULT: ok'
exit 0
