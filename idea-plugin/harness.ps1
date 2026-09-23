<#
.SYNOPSIS
    Runs the goto / parameter-hint / model / folding harnesses: the measurements
    behind every number this plugin's evidence quotes.

.DESCRIPTION
    Ten harness classes live in `tools\harness\src` (tracked -- they used to live
    under `build\tools\harness\src`, and `build\` is gitignored, so a fresh clone
    had no way to reproduce a single number this plugin claims).  Only two of them
    had a driver (`ast-diff.ps1` runs AstDiff, `psi-tree-diff.ps1` runs
    PsiTreeDiff).  This is the driver for the other eight, which is why it exists:
    an unmeasurable claim is the one thing this project does not accept.

    Each tool is a differential with one authority:

      GotoOracle   go-to-declaration, against `vm.exe check` with one declaration
                   renamed at a time.  Prints the completeness table -- correct /
                   no target / WRONG / ambiguous / unjudged.  This is where
                   "474 correct / 218 none / 0 wrong" comes from.
      HintDiff     the parameter-name inlay hints, against `vm.exe parse`'s own
                   `param name=` lines.  Prints hints drawn / correct / WRONG.
      HintNames    the two sources of a callable's parameter names (the symbol's
                   `detail` text vs the tree's `param` nodes) -- do they agree?
      HintShapes   the call *shapes* that make the hint engine draw a name no
                   declaration has, plus every truncated prefix of every file.
      HintDupes    where a parameter name repeats inside one signature (the source
                   of the old `s: s: s:`).
      SymbolDiff   VelaModel.symbols from the parser vs the token scan it replaced.
      FoldDiff     fold ranges from the tree vs the ranges the brace matcher built.
      AstDiff / PsiTreeDiff  (see ast-diff.ps1 and psi-tree-diff.ps1 instead.)

    THE COMPILER IS FROZEN FIRST.  Another agent rebuilds
    `selfhost\build\vm.exe` during this round; an oracle whose binary changes
    underneath it decides nothing.  The frozen copy's size and SHA256 are printed
    with every result, because "which compiler did you compare against" is the
    first question any one of these tables has to answer.

.PARAMETER Tool
    Which harness to run.  One of GotoOracle, HintDiff, HintNames, HintShapes,
    HintDupes, SymbolDiff, FoldDiff, or All (the default).  AstDiff and
    PsiTreeDiff have their own scripts and are not run from here.

.PARAMETER RepoRoot
    The Vela checkout to measure over.  Default: the parent of this script's folder.

.PARAMETER FrozenVm
    The frozen compiler copy.  Default: %TEMP%\vela-plugin-parse\vm.exe, the same
    file ast-diff.ps1 uses, so every harness in this plugin compares against one
    binary.  Pass -Refreeze to replace it from the tree.

.PARAMETER Refreeze
    Re-copy vm.exe from the tree, replacing the frozen one.

.PARAMETER Single
    Pass --single <file> where the harness supports it (GotoOracle, HintDiff,
    SymbolDiff).  A path relative to the repo root.

.PARAMETER RebuildOracle
    Pass --rebuild-oracle to GotoOracle: ignore its cache.  The cache is keyed by
    (compiler sha256, file hash, declaration), so a new compiler rebuilds it
    anyway -- this is for when the corpus itself changed under one compiler.

.PARAMETER Truncate
    Pass --truncate <n> to HintDiff / HintNames / HintShapes / HintDupes: also run
    every prefix of every file, the state a user is in while typing.

.PARAMETER Shapes
    Pass --shapes to HintShapes (its hand-written list of call shapes) instead of
    the corpus scan.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\harness.ps1 -Tool All

.NOTES
    Nothing outside idea-plugin\build\ and %TEMP%\vela-plugin-parse\ is written.
    `vm.exe check` and `vm.exe parse` are read-only queries over a frozen binary:
    the Vela compiler's own build scripts are never invoked.
#>
[CmdletBinding()]
param(
    [string] $Tool = 'All',
    [string] $RepoRoot,
    [string] $FrozenVm,
    [switch] $Refreeze,
    [string] $Single,
    [int]    $Truncate = 0,
    [switch] $RebuildOracle,
    [switch] $Shapes,
    [switch] $HarnessDebug
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$pluginRoot = $scriptDir
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

# The harness's sources are tracked (tools\harness\src); its classes are output and
# go under the gitignored build\.
$harnessSrc    = Join-Path $pluginRoot 'tools\harness\src'
$harnessOut    = Join-Path $pluginRoot 'build\tools\harness\classes'
$oracleCache   = Join-Path $pluginRoot 'build\tools\harness\goto-oracle.txt'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
# The plugin's *resources* are a separate root: `build\classes` holds compiled classes
# only, and `build-offline.ps1` packs resources into the jar from `src\main\resources`.
# FeatureProbe looks up `liveTemplates/Vela.xml` on the classpath, so without this root
# that lookup misses for a reason that has nothing to do with the plugin -- which is
# exactly how its first run reported a false "NOT ON THE CLASSPATH".
$resourceDir = Join-Path $pluginRoot 'src\main\resources'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

# ---------------------------------------------------------------- the JDK
#
# The same discovery the other drivers use: this machine has no java on PATH, and
# an installed JetBrains IDE carries one.

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

$TOOLS = @('GotoOracle', 'HintDiff', 'HintNames', 'HintShapes', 'HintDupes', 'SymbolDiff', 'FoldDiff',
           'FeatureProbe', 'ParamNames', 'HintTruth')
if ($Tool -eq 'All') { $run = $TOOLS }
else {
    if ($TOOLS -notcontains $Tool) { Die "-Tool must be one of: $($TOOLS -join ', '), All" }
    $run = @($Tool)
}

Step 'Preconditions'
Info "repo root     : $RepoRoot"
Info "JDK           : $best"
Info "tools         : $($run -join ', ')"
if (-not (Test-Path -LiteralPath $classpathFile)) {
    Die "missing $classpathFile.  Run build-offline.ps1 first: it writes the installed IDE's classpath, and the plugin classes under test come from build\classes."
}
if (-not (Test-Path -LiteralPath $pluginClasses)) {
    Die "missing $pluginClasses.  Run build-offline.ps1 first: the harness is compiled against the plugin's own classes."
}

# ---------------------------------------------------------------- freeze the compiler

$scratch = Join-Path $env:TEMP 'vela-plugin-parse'
New-Item -ItemType Directory -Force -Path $scratch | Out-Null
if (-not $FrozenVm) { $FrozenVm = Join-Path $scratch 'vm.exe' }
$treeVm = Join-Path $RepoRoot 'selfhost\build\vm.exe'

if ($Refreeze -or -not (Test-Path -LiteralPath $FrozenVm)) {
    if (-not (Test-Path -LiteralPath $treeVm)) { Die "no compiler at $treeVm and no frozen copy at $FrozenVm" }
    Copy-Item -LiteralPath $treeVm -Destination $FrozenVm -Force
    Info "froze $treeVm -> $FrozenVm"
} else {
    Info "using the frozen compiler already at $FrozenVm (-Refreeze to replace it)"
}
# `vm.exe` does not start at all without `LLVM-C.dll` beside it (exit 0xC0000135, no
# message), so the directory the frozen copy lives in has to hold one.
$frozenDir = Split-Path -Parent $FrozenVm
$frozenDll = Join-Path $frozenDir 'LLVM-C.dll'
if (-not (Test-Path -LiteralPath $frozenDll)) {
    $treeDll = Join-Path $RepoRoot 'selfhost\LLVM-C.dll'
    if (Test-Path -LiteralPath $treeDll) {
        Copy-Item -LiteralPath $treeDll -Destination $frozenDll -Force
        Info "froze LLVM-C.dll beside it (vm.exe does not start without it)"
    } else {
        Info "WARNING: no LLVM-C.dll beside the frozen compiler and none at $treeDll"
    }
}
$frozen = Get-Item -LiteralPath $FrozenVm
$frozenHash = (Get-FileHash -LiteralPath $FrozenVm -Algorithm SHA256).Hash.ToLowerInvariant()
Info "frozen compiler: $($frozen.Length) bytes  sha256 $frozenHash"
Info "tree   compiler: $((Get-Item -LiteralPath $treeVm).Length) bytes  sha256 $((Get-FileHash -LiteralPath $treeVm -Algorithm SHA256).Hash.ToLowerInvariant())"

# ---------------------------------------------------------------- compile the harness

Step 'Compile the harness against the plugin classes'
New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$sources = @(Get-ChildItem -LiteralPath $harnessSrc -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Die "no *.java under $harnessSrc" }

# THE CLASSPATH DOES NOT FIT ON A COMMAND LINE, AND THAT IS NOT A DETAIL.
# `platform-classpath.txt` is 32 305 bytes over 429 jars, the JDK is installed under
# `D:\JetBrains\IntelliJ IDEA 2026.2.1` so every one of those 429 entries contains a
# space, and Windows refuses a command line over 32 767 characters.  Passed directly,
# javac fails to *launch* -- "The filename or extension is too long" -- which reads
# exactly like a compile error but leaves no class file behind, so the run then fails
# with `ClassNotFoundException` and looks like a harness bug.  Measured, not guessed.
# An @argfile moves it off the command line.  Backslashes are escape characters inside
# an argfile, so every path is written with forward slashes, which javac and java both
# accept on Windows.
$cp = "$harnessOut;$pluginClasses;$resourceDir;$classpath"
$fwdCp = $cp -replace '\\', '/'
function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
function Write-ArgFile([string] $path, [string[]] $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]] $lines, (New-Object System.Text.UTF8Encoding($false)))
}
$javacArgsFile = Join-Path $harnessOut 'javac.args'
Write-ArgFile $javacArgsFile (@(
        '--release 21',
        '-encoding UTF-8',
        '-d ' + (Quoted $harnessOut),
        '-cp ' + (Quoted $cp)
    ) + @($sources | ForEach-Object { Quoted $_ }))
# Every source is passed, never a stale class file: the tree is what is tested, and a
# class file from a previous round would silently disagree with it.
& $javac ('@' + $javacArgsFile) 2>&1 | Tee-Object -FilePath (Join-Path $harnessOut 'javac.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed for the harness (see $harnessOut\javac.log)" }
$compiled = @(Get-ChildItem -LiteralPath $harnessOut -Filter '*.class')
if ($compiled.Count -eq 0) { Die "javac reported success but wrote no class file into $harnessOut" }
Info "$($sources.Count) source file(s) -> $harnessOut ($($compiled.Count) class file(s))"
Info "javac driven from an @argfile ($((Get-Item $javacArgsFile).Length) bytes, over the 32 767-character limit)"

# ---------------------------------------------------------------- run them

$exit = 0
$summary = New-Object System.Collections.Generic.List[string]
$coverageRows = New-Object System.Collections.Generic.List[object]

foreach ($t in $run) {
    Step "$t"
    # The java command line carries the same 32 KB classpath, so it goes through an
    # argfile too.
    $a = @($t, $RepoRoot)
    switch ($t) {
        'GotoOracle' {
            $a += @('--vm', $FrozenVm, '--cache', $oracleCache)
            if ($RebuildOracle) { $a += '--rebuild-oracle' }
            if ($Single)        { $a += @('--single', $Single) }
            # --debug prints the oracle's own reasoning about one declaration: which
            # lines the compiler bound to it and which diagnostic said so.  That is how
            # a WRONG row gets investigated rather than guessed at.
            if ($HarnessDebug)  { $a += '--debug' }
        }
        'HintDiff' {
            $a += @('--vm', $FrozenVm)
            if ($Truncate -gt 0) { $a += @('--truncate', "$Truncate") }
            if ($Single)         { $a += @('--single', $Single) }
        }
        'SymbolDiff' {
            if ($Single) { $a += @('--single', $Single) }
        }
        'FeatureProbe' {
            # Takes the repo root only; it reads no compiler.  The six features it
            # measures are decided inside the plugin, which is the point: nothing it
            # reports depends on a process starting successfully.
        }
        'HintShapes' {
            if ($Shapes) { $a = @($t, '--shapes') }
            elseif ($Truncate -gt 0) { $a += @('--truncate', "$Truncate") }
        }
        'ParamNames' {
            # The completion-side half of a parameter list: `VelaHints.parameterNames`
            # and `callTemplate`.  Takes the repo root; --single and --truncate as the
            # others do, and always runs the two structural invariants (a symbol whose
            # parameter list was never read from a tree answers no names; a builtin's
            # prose is not a signature).
            if ($Truncate -gt 0) { $a += @('--truncate', "$Truncate") }
            if ($Single)         { $a += @('--single', $Single) }
        }
        'HintTruth' {
            # The hint labels against an oracle that is not the hint's own model.
            if ($Truncate -gt 0) { $a += @('--truncate', "$Truncate") }
            if ($Single)         { $a += @('--single', $Single) }
        }
        default {
            if ($Truncate -gt 0) { $a += @('--truncate', "$Truncate") }
        }
    }
    $runArgsFile = Join-Path $harnessOut "$t.args"
    Write-ArgFile $runArgsFile (@('-cp ' + (Quoted $cp)) + @($a | ForEach-Object { Quoted $_ }))
    $log = Join-Path $harnessOut "$t.log"
    # Written to files rather than piped: this machine's shell turns a native
    # program's stderr into a terminating error when it is captured in a pipeline,
    # and these are Java programs that may write there.
    & $java ('@' + $runArgsFile) 1> $log 2> "$log.err"
    $code = $LASTEXITCODE
    Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
    if (Test-Path -LiteralPath "$log.err") {
        $errText = (Get-Content -LiteralPath "$log.err" -Raw)
        if ($errText -and $errText.Trim().Length -gt 0) {
            Write-Host '--- stderr' -ForegroundColor DarkYellow
            Write-Host $errText.Trim()
        }
    }
    Info "exit $code   log: $log"
    # Every tool now ends with a COVERAGE line as well as a VERDICT, and the two are
    # collected together: a verdict with no number behind it is what let a crashed
    # oracle report a clean pass.
    $verdict = @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue | Where-Object { $_ -like 'VERDICT*' })
    $coverage = @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue | Where-Object { $_ -like 'COVERAGE:*' })
    $covText = if ($coverage.Count -gt 0) { $coverage[-1].Trim() } else { 'COVERAGE: (the tool printed none)' }
    $verText = if ($verdict.Count -gt 0) { $verdict[-1].Trim() } else { '(no VERDICT line: the tool did not finish)' }
    $coverageRows.Add([pscustomobject]@{
        Tool = $t; Exit = $code; Coverage = $covText; Verdict = $verText
    })
    if ($code -ne 0) {
        $exit = $code
        # GotoOracle exits 1 when it found a WRONG target and 3 when the oracle itself
        # crashed on a file; both are findings, and which one happened matters.
        $summary.Add("$t : exit $code -- $verText")
        if ($code -eq 3) { $summary.Add("$t : exit 3 is a HARNESS/corpus defect, not a wrong answer") }
    }
}

Step 'Coverage'
Info 'the measurement behind every verdict above, one line per tool:'
foreach ($r in $coverageRows) {
    Write-Host ("  {0,-14} exit {1,-3} {2}" -f $r.Tool, $r.Exit, $r.Coverage)
}
Write-Host ''
Info 'the verdict each tool reached:'
foreach ($r in $coverageRows) {
    Write-Host ("  {0,-14} {1}" -f $r.Tool, $r.Verdict)
}

Step 'Done'
Info "frozen compiler : $FrozenVm ($($frozen.Length) bytes, sha256 $frozenHash)"
Info "logs            : $harnessOut"
if ($summary.Count -gt 0) {
    Write-Host ''
    Write-Host 'non-zero exits (the harness found something):' -ForegroundColor Yellow
    foreach ($s in $summary) { Write-Host "  $s" -ForegroundColor Yellow }
}
exit $exit
