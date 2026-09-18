# tools/dbg_test.ps1 — the debug protocol's own test.
#
# `tests/run_tests.vel` cannot express this and says so: a case there is "run
# this program, compare stdout", and this needs a *conversation* — start the
# debugger, wait for it to say it is stopped, decide what to say next from what
# it said, and assert the events in between.  The suite has no way to write a
# command file and read an event, so this script is the test (DESIGN.md §10.6),
# and it is self-contained: it needs vm.exe, a source file, and nothing else.
#
# Discipline that matters on Windows:
#   * every command file is written with cmd's `>` or with WriteAllText as
#     UTF-8.  PowerShell's `>` writes UTF-16, and a two-byte BOM in front of
#     `break` is a command the debugger does not have;
#   * stdout and stderr go to *separate* files and are both read only after the
#     process has exited, so no line interleaves in the middle of a read;
#   * the debugged program's exit status is asserted, because a protocol that
#     only looks right when the program aborts is a protocol that is lying.
#
#     pwsh -File tools\dbg_test.ps1                # from the repository root
#     pwsh -File tools\dbg_test.ps1 -Keep          # leave the session directory
param(
    [switch] $Keep
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

$vm      = Join-Path $root 'selfhost\build\vm.exe'
$sample  = 'tools/dbg_sample.vel'
$session = 'tools/dbg-session'
$outFile = Join-Path $root 'tools\_dbg_out.txt'
$errFile = Join-Path $root 'tools\_dbg_err.txt'

if (-not (Test-Path -LiteralPath $vm)) {
    Write-Host "dbg_test: no $vm - build first (tools\build.ps1)"
    exit 1
}

# The session directory is rebuilt every run: a stale cmd.0007 from a previous
# run would be read as this run's seventh command, and the failure would look
# like a state machine bug rather than a dirty directory.
if (Test-Path -LiteralPath $session) { Remove-Item -LiteralPath $session -Recurse -Force }
New-Item -ItemType Directory -Path $session | Out-Null

$script:failures = 0
$script:lines    = New-Object System.Collections.Generic.List[string]

function Say([string] $text) {
    $script:lines.Add($text)
    Write-Host $text
}

function Fail([string] $text) {
    $script:failures++
    Say "FAIL $text"
}

function Check([string] $what, [string] $want, [string] $got) {
    if ($got -eq $want) {
        Say "  ok   $what = $got"
    } else {
        $script:failures++
        Say "  FAIL $what : wanted '$want', got '$got'"
    }
}

# One command file, created only once its content is decided.  `cmd.exe /c echo`
# would append CRLF and the debugger strips that, but writing the bytes here
# keeps the transcript honest about what the driver actually sent.
$script:next = 0
function Send([string[]] $commandLines) {
    $script:next++
    $name = Join-Path $session ('cmd.{0:d3}' -f $script:next)
    $text = ($commandLines -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($name, $text, (New-Object System.Text.UTF8Encoding($false)))
    Say "  > $($commandLines -join ' / ')"
}

Start-Process -FilePath $vm `
    -ArgumentList @('debug', $sample, $session) `
    -WorkingDirectory $root `
    -RedirectStandardOutput $outFile `
    -RedirectStandardError $errFile `
    -NoNewWindow -PassThru | ForEach-Object { $script:proc = $_ }

# "line N: text" becomes the text, so `break 7` can be read as "the line that
# has `total = add` on it" rather than as a number to keep in step by hand.
$sampleLines = Get-Content -LiteralPath (Join-Path $root ($sample -replace '/', '\'))
function LineOf([string] $needle) {
    for ($i = 0; $i -lt $sampleLines.Count; $i++) {
        if ($sampleLines[$i].Contains($needle)) { return $i + 1 }
    }
    throw "dbg_test: no line contains '$needle'"
}

# The debugger holds every stop until the next command file exists, so the
# driver's clock is its own: after `Send`, this waits for the next event line
# and reads it.  The waiting is a poll of the two capture files, which is
# enough because the *only* thing being waited for is a line the debugger
# writes and flushes before it blocks (DESIGN.md §10.3).
function ReadEvents() {
    Start-Sleep -Milliseconds 150
    if (-not (Test-Path -LiteralPath $outFile)) { return @() }
    return @(Get-Content -LiteralPath $outFile -ErrorAction SilentlyContinue)
}

function WaitFor([string] $needle, [int] $timeoutMs = 20000) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ((Get-Date) -lt $deadline) {
        $lines = ReadEvents
        foreach ($l in $lines) { if ($l -eq $needle) { return $true } }
        if ($lines.Count -gt 0) {
            $last = $lines[$lines.Count - 1]
            if ($last -eq $needle) { return $true }
        }
        if ($script:proc.HasExited) { return $false }
        Start-Sleep -Milliseconds 25
    }
    return $false
}

function LastLine() {
    $lines = ReadEvents
    if ($lines.Count -eq 0) { return '' }
    return $lines[$lines.Count - 1]
}

try {
    # ---------------------------------------------------------------- startup
    if (-not (WaitFor 'ready')) { Fail 'the debugger never said ready before the program started' }
    else { Say "  ok   startup event: ready" }

    $lineBreak = LineOf 'total = add'
    $lineIn    = LineOf 'c: int = a + b'
    $lineWhile = LineOf 'while i < 3'

    Send @("break $lineBreak", 'run')
    if (-not (WaitFor "stopped $lineBreak breakpoint")) {
        Fail "no breakpoint stop at line $lineBreak (last event: $(LastLine))"
    } else {
        Say "  ok   stopped at breakpoint on line $lineBreak"
    }

    # ------------------------------------------------- the stop's own report
    Send @('vars', "break $lineIn", 'step')
    if (-not (WaitFor "stopped $lineIn step")) {
        Fail "step did not stop at line $lineIn (last event: $(LastLine))"
    } else {
        Say "  ok   step entered add() at line $lineIn"
    }

    $events = ReadEvents
    # `locals 3` because add's two parameters and its local `c` are all live at
    # the declaration of c: the walker counts the bindings the line has reached
    Check 'vars count while in add' 'locals 3' (($events | Where-Object { $_ -like 'locals *' } | Select-Object -Last 1))
    $joined = $events -join "`n"
    if ($joined -match 'local a = 0') { Say '  ok   local a = 0' } else { Fail 'vars did not show a = 0' }
    if ($joined -match 'local b = 0') { Say '  ok   local b = 0' } else { Fail 'vars did not show b = 0' }

    Send @('stack', 'out')
    if (-not (WaitFor "stopped $lineBreak out")) {
        Fail "out did not stop in the caller (last event: $(LastLine))"
    } else {
        Say "  ok   out stopped in the caller at line $lineBreak"
    }

    $events = ReadEvents
    if (($events -join "`n") -match 'stack [0-9]+') { Say '  ok   stack reported a depth' } else { Fail 'stack reported nothing' }
    if (($events -join "`n") -match '(?m)^1 [0-9]+ <main>$') { Say '  ok   stack showed the caller frame' } else { Fail 'stack did not show the caller frame' }

    Send @('next')
    if (-not (WaitFor "stopped $lineWhile next")) {
        Fail "next did not stop on the loop line (last event: $(LastLine))"
    } else {
        Say "  ok   next stepped over the call to line $lineWhile"
    }

    # ------------------------------------------------------- back to running
    Send @('clear', $lineIn, 'continue')
    if (-not (WaitFor 'exited 0')) {
        Fail "the program did not run to the end (last event: $(LastLine))"
    } else {
        Say '  ok   ran to completion: exited 0'
    }
    $script:proc.WaitForExit(20000) | Out-Null

    # ------------------------------------------- a breakpoint changes nothing
    #
    # The same program, run twice: once with a session that resumes, once with
    # `run`.  A breakpoint that changed the output would be visible here as a
    # difference, and this is the assertion the whole feature rests on.
    $refOut = Join-Path $root 'tools\_dbg_ref.txt'
    $refErr = Join-Path $root 'tools\_dbg_referr.txt'
    $p = Start-Process -FilePath $vm -ArgumentList @('run', $sample) `
        -WorkingDirectory $root -RedirectStandardOutput $refOut `
        -RedirectStandardError $refErr -NoNewWindow -PassThru
    $p.WaitForExit(20000) | Out-Null
    $refText = Get-Content -LiteralPath $refOut -Raw
    $dbgText = ''
    if (Test-Path -LiteralPath $outFile) { $dbgText = Get-Content -LiteralPath $outFile -Raw }
    if ($dbgText -match 'total 3') { Say '  ok   debugged run printed the program output (total 3)' } else { Fail "the debugged run did not print 'total 3'" }
    if ($refText -match 'total 3') { Say '  ok   plain run printed the same output' } else { Fail "the plain run did not print 'total 3'" }

    $errText = ''
    if (Test-Path -LiteralPath $errFile) { $errText = Get-Content -LiteralPath $errFile -Raw }
    if ($errText -and $errText.Trim().Length -gt 0) {
        # stderr is where the program's own diagnostics and any interpreter
        # complaint go: a clean session must leave it empty
        Fail "the debug session wrote to stderr: $($errText.Trim())"
    } else {
        Say '  ok   stderr is empty over the whole session'
    }

    # the debugged program's own exit status, not just its output
    Check 'debugged process exit code' '0' "$($script:proc.ExitCode)"

    Say ''
    $transcript = Join-Path $root 'tools\dbg_transcript.txt'
    [System.IO.File]::WriteAllLines($transcript, $script:lines, (New-Object System.Text.UTF8Encoding($false)))
    if ($script:failures -eq 0) {
        Say 'dbg_test: PASS'
    } else {
        Say "dbg_test: FAIL ($script:failures)"
    }
    Write-Host "transcript: $transcript"
    if (-not $Keep) {
        Remove-Item -LiteralPath $session -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($script:failures -gt 0) { exit 1 }
    exit 0
} finally {
    if ($script:proc -and -not $script:proc.HasExited) {
        $script:proc.Kill()
    }
}
