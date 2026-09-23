<#
.SYNOPSIS
    Runs the inspection harness: the three `localInspection` rules and their quick
    fixes, against the frozen Vela compiler.

.DESCRIPTION
    `harness.ps1` compiles *every* `.java` under `tools\harness\src` and runs ten
    tools from a list.  This tool is not in that list yet (the list belongs to
    `harness.ps1`, which another agent owns this round), so it has its own driver 闁?    the way `ast-diff.ps1` and `psi-tree-diff.ps1` drive the two tools that are not
    in the list either.

    WHAT IT RUNS

      InspectionProbe   the three inspections 闁?assignment to an immutable binding,
                        `+` on strings, and int/float mixing 闁?against `vm.exe check`
                        over the corpus.  Every finding the *registered* inspection
                        classes report must be walked to a refusal the compiler
                        itself makes on that line (one fix at a time, because
                        `check` reports only its first refusal), every fix must make
                        the refusal it was offered for go away, and no finding may
                        land on a file the compiler accepts.

    THIS DRIVER COMPILES TWO FILES, NOT ALL OF THEM, ON PURPOSE.

    `harness.ps1` compiles the whole directory, so one half-written tool belonging
    to someone else stops every measurement in the directory 闁?measured, not
    hypothetical: a `HoverTruth.java` that imported a class which did not exist yet
    made the whole run exit 2 at the javac step with no results from any tool.
    This driver compiles `Coverage.java` (this tool's only dependency) and
    `InspectionProbe.java` and nothing else, so that cannot happen here.  The one
    thing that must be true instead is that the tool compiles 闁?`-CompileOnly`
    checks it without running anything.

    THE COMPILER IS FROZEN FIRST, into this driver's own directory rather than
    `harness.ps1`'s, because `selfhost\build\vm.exe` is being rebuilt by another
    agent while these runs happen and a `-Refreeze` from a parallel run would
    change the oracle under this one.  Its size and SHA256 are printed.

.PARAMETER RepoRoot
    The Vela checkout to measure over.  Default: the parent of this script's folder.

.PARAMETER FrozenVm
    The frozen compiler copy.  Default: %TEMP%\vela-plugin-inspection-probe\vm.exe.

.PARAMETER Refreeze
    Re-copy `vm.exe` and `LLVM-C.dll` from the tree, replacing the frozen copies.

.PARAMETER Single
    One file, relative to the repo root, instead of the whole corpus.

.PARAMETER Exclude
    A corpus file, relative to the repo root, left out of this run.  Repeatable.
    The tool prints every exclusion and counts it under excluded-by-request, so an
    exclusion is never a silent hole in the corpus.  Its one use so far is
    	ests/safety/cases/strict_no_inferred_binding_type.vel, on which
    m.exe check does not terminate: the full run reports that as the defect
    category check-did-not-terminate (and is non-clean because of it), while a run
    with this flag measures the other 309 files with a verdict only about the
    inspections.

.PARAMETER ShowAll
    Pass `--verbose` to the tool: print every finding, including the ones that agree.
    (Called `-ShowAll` and not `-Verbose` because `[CmdletBinding()]` already owns
    that name, and declaring it twice is a parameter-binding error before the
    script runs -- measured on the first invocation of this file.)

.PARAMETER NoFix
    Pass `--no-fix`: measure the findings only, do not apply and re-check the fixes.

.PARAMETER CompileOnly
    Compile the tool and stop.  For checking that this round's `.java` compiles
    before anyone else's run picks it up.

.PARAMETER LockTimeoutSeconds
    How long to wait for %TEMP%\vela-plugin-build.lock before giving up.  The lock
    is taken because this driver writes under `idea-plugin\build\`.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\tools\harness\inspection-probe.ps1

.NOTES
    To make this tool part of `harness.ps1` (its owner's call, not this file's),
    three lines are needed there:

      * `InspectionProbe` in the `$TOOLS` array;
      * in the per-tool `switch`:
          'InspectionProbe' { $a += @('--vm', $FrozenVm) }
      * and, for `-Single`, `if ($Single) { $a += @('--single', $Single) }`
        in the same case.

    Nothing outside `idea-plugin\build\` and %TEMP%\vela-plugin-inspection-probe\
    is written, and `vm.exe check` is a read-only query over a frozen binary.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $FrozenVm,
    [switch] $Refreeze,
    [string] $Single,
    [switch] $ShowAll,
    [string[]] $Exclude = @(),
    [switch] $NoFix,
    [switch] $CompileOnly,
    [int]    $LockTimeoutSeconds = 900
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$harnessSrc = Join-Path $scriptDir 'src'     # tools\harness\src holds the tools
$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDir '..\..')).Path
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

# ---------------------------------------------------------------- the JDK
#
# The same discovery `harness.ps1` uses: this machine has no java on PATH, and an
# installed JetBrains IDE carries one.

function Get-InstalledIdes {
    $roots = @(
        'D:\JetBrains', 'C:\JetBrains',
        'C:\Program Files\JetBrains', 'C:\Program Files (x86)\JetBrains',
        (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'),
        (Join-Path $env:LOCALAPPDATA 'Programs'),
        (Join-Path $env:USERPROFILE '.local\share\JetBrains\Toolbox\apps')
    )
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($d in (Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue)) {
            if ((Test-Path -LiteralPath (Join-Path $d.FullName 'jbr\bin\java.exe')) -and
                (Test-Path -LiteralPath (Join-Path $d.FullName 'build.txt'))) {
                if (-not $found.Contains($d.FullName)) { $found.Add($d.FullName) }
            }
        }
    }
    return $found.ToArray()
}

$ides = @(Get-InstalledIdes | Sort-Object)
if ($ides.Count -eq 0) { Die 'no JetBrains IDE found; the JDK is the IDE''s' }
$idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' })
$best = if ($idea.Count -gt 0) { $idea[-1] } else { $ides[-1] }
$java  = Join-Path $best 'jbr\bin\java.exe'
$javac = Join-Path $best 'jbr\bin\javac.exe'
foreach ($t in @($java, $javac)) { if (-not (Test-Path -LiteralPath $t)) { Die "missing: $t" } }

$classesDir    = Join-Path $pluginRoot 'build\tools\harness\classes'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$resourceDir   = Join-Path $pluginRoot 'src\main\resources'

Step 'Preconditions'
Info "repo root : $RepoRoot"
Info "plugin    : $pluginRoot"
Info "JDK       : $best"
if (-not (Test-Path -LiteralPath $classpathFile)) {
    Die "missing $classpathFile.  Run build-offline.ps1 first: it writes the installed IDE's classpath, and the plugin classes under test come from build\classes."
}
if (-not (Test-Path -LiteralPath $pluginClasses)) {
    Die "missing $pluginClasses.  Run build-offline.ps1 first: this harness is compiled against the plugin's own classes, and the inspection classes it drives live there."
}

# ---------------------------------------------------------------- the lock
#
# Writing under `idea-plugin\build\` needs %TEMP%\vela-plugin-build.lock, because
# several agents build this plugin at once, and a parallel `build-offline.ps1` run
# replaces `build\classes` -- which would end this run with a compile error from
# somebody else's edit, looking like this tool's failure.  The lock is taken here
# rather than left to the caller so that a run cannot forget it.
#
# THE LOCK CARRIES THIS PROCESS'S PID, AND THAT IS NOT DECORATION.
#
# A lock is released in a `finally`, and a `finally` does not run when the process
# is killed.  This driver was killed mid-run once (a caller truncated its output
# with `Select-Object -First`, which stops the pipeline) and left an empty lock
# file behind: every later run then waited ten minutes for a lock whose owner no
# longer existed.  So this driver writes its PID into the file and, before giving
# up on a lock, reads it back: a lock whose PID is a process that is gone is broken
# and retried.  A lock with *no* PID in it was taken by the plain one-liner the
# other agents use, and that one is never broken automatically -- an empty file
# means "held", not "mine".
$lock = Join-Path $env:TEMP 'vela-plugin-build.lock'
$held = $false
$waited = 0
$waitedSaid = 0
while (-not (New-Item -ItemType File -Path $lock -ErrorAction SilentlyContinue)) {
    if ($waited -ge 0 -and (Test-Path -LiteralPath $lock)) {
        $owner = (Get-Content -LiteralPath $lock -Raw -ErrorAction SilentlyContinue)
        if ($owner) { $owner = $owner.Trim() }
        $ownerPid = 0
        if ($owner -and [int]::TryParse($owner, [ref] $ownerPid) -and $ownerPid -gt 0) {
            if (-not (Get-Process -Id $ownerPid -ErrorAction SilentlyContinue)) {
                Write-Host "    held by pid $ownerPid, which is gone: breaking the stale lock" -ForegroundColor DarkYellow
                Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
                continue
            }
        }
    }
    if ($waited -ge $LockTimeoutSeconds) {
        Die "waited $LockTimeoutSeconds s for $lock and it is still held; not writing under build\ without it"
    }
    Start-Sleep -Seconds 5
    $waited += 5
    if ($waited - $waitedSaid -ge 30) {
        Write-Host "    waiting for $lock ... ${waited}s" -ForegroundColor DarkYellow
        $waitedSaid = $waited
    }
}
[System.IO.File]::WriteAllText($lock, "$PID", (New-Object System.Text.UTF8Encoding($false)))
$held = $true
Info "lock      : $lock (pid $PID, waited ${waited}s)"
if ($waited -gt 0) { Write-Host "    (another build held it for ${waited}s)" -ForegroundColor DarkYellow }

try {
    # ------------------------------------------------------------ snapshot the classes
    #
    # THE MEASUREMENT RUNS AGAINST A COPY OF `build\classes`, TAKEN UNDER THE LOCK,
    # AND THAT IS NOT BELT-AND-BRACES -- IT IS A MEASURED REPAIR.
    #
    # The lock is not universally honoured (this run's own log is where that was
    # seen: a parallel `build-offline.ps1` replaced `build\classes` while a run was
    # in progress, and six files came back as `java.lang.NoClassDefFoundError:
    # dev/vela/plugin/VelaSyntaxParser$Companion$WhenMappings` -- a class that had
    # been deleted a moment earlier, reported as `crashed-file`, which is a *wrong
    # number* about the plugin rather than a wrong answer from it).  A copy taken
    # while the lock is held cannot be taken away by anything, so the run's numbers
    # are about one fixed set of class files, and the count is printed so that a
    # reader knows which set it was.

    Step 'Snapshot the plugin classes (under the lock)'
    $scratch = Join-Path $env:TEMP 'vela-plugin-inspection-probe'
    New-Item -ItemType Directory -Force -Path $scratch | Out-Null
    $snapshot = Join-Path $scratch 'classes-snapshot'
    if (Test-Path -LiteralPath $snapshot) { Remove-Item -LiteralPath $snapshot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $snapshot | Out-Null
    Copy-Item -Path (Join-Path $pluginClasses '*') -Destination $snapshot -Recurse -Force
    $snapshotFiles = @(Get-ChildItem -LiteralPath $snapshot -Recurse -File)
    if ($snapshotFiles.Count -eq 0) { Die "the snapshot of $pluginClasses is empty" }
    Info "snapshot  : $snapshot ($($snapshotFiles.Count) file(s), taken while the lock was held)"
    Info "            ^ every number below is about these class files, not about build\classes"

    # ------------------------------------------------------------ freeze the compiler

    Step 'Freeze the compiler'
    if (-not $FrozenVm) { $FrozenVm = Join-Path $scratch 'vm.exe' }
    $treeVm = Join-Path $RepoRoot 'selfhost\build\vm.exe'
    if ($Refreeze -or -not (Test-Path -LiteralPath $FrozenVm)) {
        if (-not (Test-Path -LiteralPath $treeVm)) { Die "no compiler at $treeVm and no frozen copy at $FrozenVm" }
        Copy-Item -LiteralPath $treeVm -Destination $FrozenVm -Force
        Info "froze $treeVm -> $FrozenVm"
    } else {
        Info "using the frozen compiler already at $FrozenVm (-Refreeze to replace it)"
    }
    # `vm.exe` does not start at all without `LLVM-C.dll` beside it (exit 0xC0000135,
    # no message), so the frozen directory has to hold one.
    $frozenDll = Join-Path (Split-Path -Parent $FrozenVm) 'LLVM-C.dll'
    if (-not (Test-Path -LiteralPath $frozenDll)) {
        $treeDll = Join-Path $RepoRoot 'selfhost\LLVM-C.dll'
        if (Test-Path -LiteralPath $treeDll) {
            Copy-Item -LiteralPath $treeDll -Destination $frozenDll -Force
            Info 'froze LLVM-C.dll beside it (vm.exe does not start without it)'
        } else {
            Info "WARNING: no LLVM-C.dll beside the frozen compiler and none at $treeDll"
        }
    }
    $frozen = Get-Item -LiteralPath $FrozenVm
    $frozenHash = (Get-FileHash -LiteralPath $FrozenVm -Algorithm SHA256).Hash.ToLowerInvariant()
    Info "frozen compiler: $($frozen.Length) bytes  sha256 $frozenHash"
    if (Test-Path -LiteralPath $treeVm) {
        $tree = Get-Item -LiteralPath $treeVm
        Info "tree   compiler: $($tree.Length) bytes  sha256 $((Get-FileHash -LiteralPath $treeVm -Algorithm SHA256).Hash.ToLowerInvariant())"
        if ($tree.Length -ne $frozen.Length) {
            Info "                       ^ a different build from the frozen one; that is why the frozen copy is the oracle"
        }
    }
    $frozenTime = $frozen.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')

    # ------------------------------------------------------------ compile

    Step 'Compile the harness (two files, see the header)'
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $cp = "$classesDir;$snapshot;$resourceDir;$classpath"
    $sources = @(
        (Join-Path $harnessSrc 'Coverage.java'),
        (Join-Path $harnessSrc 'InspectionProbe.java')
    )
    foreach ($s in $sources) { if (-not (Test-Path -LiteralPath $s)) { Die "missing source: $s" } }
    # The classpath is 32 KB over 429 jars and Windows refuses a command line over
    # 32 767 characters, so it travels in an @argfile with forward slashes (a
    # backslash is an escape character inside an argfile).  Same reason as
    # harness.ps1's.
    function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
    $javacArgsFile = Join-Path $classesDir 'inspection-probe-javac.args'
    [System.IO.File]::WriteAllLines($javacArgsFile, [string[]] (@(
            '--release 21',
            '-encoding UTF-8',
            '-d ' + (Quoted $classesDir),
            '-cp ' + (Quoted $cp)
        ) + @($sources | ForEach-Object { Quoted $_ })),
        (New-Object System.Text.UTF8Encoding($false)))
    & $javac ('@' + $javacArgsFile) 2>&1 | Tee-Object -FilePath (Join-Path $classesDir 'inspection-probe-javac.log')
    if ($LASTEXITCODE -ne 0) { Die "javac failed for InspectionProbe (see $classesDir\inspection-probe-javac.log)" }
    Info "compiled: $($sources.Count) source file(s) -> $classesDir"

    if ($CompileOnly) {
        Info '-CompileOnly: nothing was run'
        exit 0
    }

    # ------------------------------------------------------------ run

    Step 'InspectionProbe'
    $a = @('InspectionProbe', $RepoRoot, '--vm', $FrozenVm)
    if ($Single)  { $a += @('--single', $Single) }
    if ($ShowAll) { $a += '--verbose' }
    if ($NoFix)   { $a += '--no-fix' }
    foreach ($x in $Exclude) { $a += @('--exclude', $x) }
    $runArgsFile = Join-Path $classesDir 'InspectionProbe.args'
    [System.IO.File]::WriteAllLines($runArgsFile, [string[]] (@('-cp ' + (Quoted $cp)) + @($a | ForEach-Object { Quoted $_ })),
        (New-Object System.Text.UTF8Encoding($false)))
    $log = Join-Path $classesDir 'InspectionProbe.log'
    # Written to a file rather than piped: this machine's shell turns a native
    # program's stderr into a terminating error when it is captured in a pipeline.
    & $java ('@' + $runArgsFile) 1> $log 2> "$log.err"
    $code = $LASTEXITCODE
    Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
    if (Test-Path -LiteralPath "$log.err") {
        $errText = (Get-Content -LiteralPath "$log.err" -Raw)
        if ($errText -and $errText.Trim().Length -gt 0) {
            # The JBR prints an `Unsafe` deprecation banner on every run; it is not a
            # result and printing it as one made a clean run look alarming.
            $real = @($errText -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'WARNING|sun\.misc|Unsafe|^At line|CategoryInfo|FullyQualified|^\s*\+' })
            if ($real.Count -gt 0) {
                Write-Host '--- stderr' -ForegroundColor DarkYellow
                $real | ForEach-Object { Write-Host $_ }
            }
        }
    }
    $verdict = @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*VERDICT' })
    $coverage = @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue | Where-Object { $_ -like 'COVERAGE:*' })
    Step 'Done'
    if ($coverage.Count -gt 0) { Info $coverage[-1].Trim() }
    if ($verdict.Count -gt 0)  { Info $verdict[-1].Trim() }
    Info "frozen compiler : $FrozenVm ($($frozen.Length) bytes, sha256 $frozenHash, written $frozenTime)"
    Info "log             : $log"
    Info "exit            : $code"
    exit $code
} finally {
    if ($held) { Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue }
}
