<#
.SYNOPSIS
    The acceptance test for the plugin's parser: does it agree with the compiler,
    file for file, node for node?

.DESCRIPTION
    `AstDiff` parses every `.vel` file in the corpus with the plugin's own parser,
    prints the tree in the compiler's canonical format, runs `vm.exe parse` on the
    same file, and diffs the two line for line.  A file that comes out identical is
    a file where the plugin's tree *is* the compiler's tree.

    This script is the only thing that compiles that harness, because the harness
    needs the plugin's own classes and build-offline.ps1 compiles its tools *before*
    kotlinc runs -- so it cannot live in the verifier's source directory.  Its
    sources are tracked in `tools\harness\src` (they were under `build\tools\harness\src`,
    which `build\` being gitignored made unreachable from a fresh clone), and the
    plugin classes come from `build\classes`, which means `build-offline.ps1` has to
    have run at least once.

    THE COMPILER IS FROZEN FIRST, and that is not a detail.  Another agent rebuilds
    selfhost\build\vm.exe while this runs; comparing against a file that changes
    underneath the comparison proves nothing.  So vm.exe is copied once, into
    %TEMP%\vela-plugin-parse\, and every run below uses that copy.  Its size and
    SHA256 are printed with the results, because "which compiler did you compare
    against" is the first question any such table has to answer.

.PARAMETER RepoRoot
    The Vela checkout to compare over.  Default: the parent of this script's folder.

.PARAMETER FrozenVm
    The frozen compiler copy.  Default: %TEMP%\vela-plugin-parse\vm.exe, created from
    the tree's vm.exe if it is not there yet.  Pass -Refreeze to replace it.

.PARAMETER Refreeze
    Re-copy vm.exe from the tree, replacing the frozen one.  Do this when the tree's
    compiler has moved on and you mean to compare against the new one.

.PARAMETER Single
    Compare one file (a path relative to the repo root) and stop.

.PARAMETER ShowTrees
    Pass --verbose to the harness: for every file that is not identical, print both
    trees in full beside each other.

.PARAMETER Shape
    Pass --shape: print the plugin parser's own tree (kind and token range per node)
    rather than a comparison.  This is how a difference gets investigated.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\ast-diff.ps1

.NOTES
    Nothing outside idea-plugin\build\ and %TEMP%\vela-plugin-parse\ is written.
    The Vela compiler's own build scripts are never invoked, and neither is the
    test suite: `vm.exe parse` is a read-only query over a frozen binary.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $FrozenVm,
    [switch] $Refreeze,
    [string] $Single,
    [switch] $ShowTrees,
    [switch] $Shape,
    [switch] $SkipSelfTest
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
$harnessSrc = Join-Path $pluginRoot 'tools\harness\src'
$harnessOut = Join-Path $pluginRoot 'build\tools\harness\classes'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

# ---------------------------------------------------------------- the JDK
#
# The same discovery build-offline.ps1 uses: this machine has no java on PATH, and
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
if ($ides.Count -eq 0) { Die 'no JetBrains IDE found; pass -JbrHome by editing this script (the JDK is the IDE''s)' }
$idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' })
$best = if ($idea.Count -gt 0) { $idea[-1] } else { $ides[-1] }
$java  = Join-Path $best 'jbr\bin\java.exe'
$javac = Join-Path $best 'jbr\bin\javac.exe'
foreach ($t in @($java, $javac)) { if (-not (Test-Path -LiteralPath $t)) { Die "missing: $t" } }

Step 'Preconditions'
Info "repo root     : $RepoRoot"
Info "JDK           : $best"
if (-not (Test-Path -LiteralPath $classpathFile)) {
    Die "missing $classpathFile.  Run build-offline.ps1 first: it writes the installed IDE's classpath, and the parser under test is its classes."
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
$frozen = Get-Item -LiteralPath $FrozenVm
$frozenHash = (Get-FileHash -LiteralPath $FrozenVm -Algorithm SHA256).Hash.ToLowerInvariant()
Info "frozen compiler: $($frozen.Length) bytes  sha256 $frozenHash"

# `vm.exe` DOES NOT START WITHOUT `LLVM-C.dll` BESIDE IT, and freezing only the .exe
# is the mistake that made this whole comparison meaningless.  The compiler imports
# the LLVM shim statically, so a bare copy exits 0xC0000135 with no message at all --
# and this harness reads that exit code as "the compiler refused the file".  Measured:
# a run that froze only vm.exe reported 119 of 120 files as
# "compiler refused (exit -1073741515) -- but this parser accepted it", 0 matches and
# VERDICT FAIL, while the parser under test was fine.  So the DLL is frozen too, and
# its absence is fatal here rather than something the table quietly absorbs.
$frozenDll = Join-Path (Split-Path -Parent $FrozenVm) 'LLVM-C.dll'
if (-not (Test-Path -LiteralPath $frozenDll)) {
    $treeDll = Join-Path $RepoRoot 'selfhost\LLVM-C.dll'
    if (-not (Test-Path -LiteralPath $treeDll)) {
        Die ("no LLVM-C.dll beside $FrozenVm and none at $treeDll.  Without it vm.exe exits " +
             "0xC0000135 and every file in the table would read as 'the compiler refused it'.")
    }
    Copy-Item -LiteralPath $treeDll -Destination $frozenDll -Force
    Info "froze LLVM-C.dll beside it ($((Get-Item -LiteralPath $treeDll).Length) bytes): vm.exe does not start without it"
}

# ---------------------------------------------------------------- compile the harness

Step 'Compile the harness against the plugin classes'
New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$sources = @(Get-ChildItem -LiteralPath $harnessSrc -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Die "no *.java under $harnessSrc" }

# THE CLASSPATH DOES NOT FIT ON A COMMAND LINE.  `platform-classpath.txt` is 32 305
# bytes over 429 jars and the JDK lives under `D:\JetBrains\IntelliJ IDEA 2026.2.1`,
# so every entry contains a space and the command line goes past Windows' 32 767
# character limit.  Passed directly, javac fails to *launch* ("The filename or
# extension is too long") -- which looks like a compile error but writes no class
# file, so the run then dies with `ClassNotFoundException` and reads as a harness bug.
# An @argfile takes it off the command line; backslashes are escapes inside an
# argfile, so every path is written with forward slashes, which javac accepts here.
$cp = "$harnessOut;$pluginClasses;$classpath"
function Quoted([string] $s) { '"' + ($s -replace '\\', '/') + '"' }
$javacArgsFile = Join-Path $harnessOut 'javac.args'
[System.IO.File]::WriteAllLines($javacArgsFile, [string[]] (@(
            '--release 21',
            '-encoding UTF-8',
            '-d ' + (Quoted $harnessOut),
            '-cp ' + (Quoted $cp)
        ) + @($sources | ForEach-Object { Quoted $_ })), (New-Object System.Text.UTF8Encoding($false)))
# Only the harness sources are passed, never a stale class file: the tree is what is
# tested, and a class file from a previous round would silently disagree with it.
& $javac ('@' + $javacArgsFile) 2>&1 |
    Tee-Object -FilePath (Join-Path $harnessOut 'javac.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed for the harness (see $harnessOut\javac.log)" }
if (-not (Test-Path -LiteralPath (Join-Path $harnessOut 'AstDiff.class'))) {
    Die "javac reported success but wrote no AstDiff.class into $harnessOut"
}
Info "$($sources.Count) source file(s) -> $harnessOut (javac driven from an @argfile, $((Get-Item $javacArgsFile).Length) bytes)"

# ---------------------------------------------------------------- run it

Step 'Differential: the plugin''s tree vs the compiler''s tree'
$harnessArgs = @('AstDiff', $RepoRoot, '--vm', $FrozenVm)
if ($ShowTrees) { $harnessArgs += '--verbose' }
if ($Shape)   { $harnessArgs += '--shape' }
if ($Single)  { $harnessArgs += @('--single', $Single) }
# On by default: a run that reports "everything matches" is only worth reading if
# the same invocation also showed the comparison rejecting a wrong tree.
if (-not $SkipSelfTest) { $harnessArgs += '--selftest' }
$log = Join-Path $harnessOut 'ast-diff.log'
$runArgsFile = Join-Path $harnessOut 'ast-diff.args'
[System.IO.File]::WriteAllLines($runArgsFile, [string[]] (@('-cp ' + (Quoted $cp)) + @($harnessArgs | ForEach-Object { Quoted $_ })), (New-Object System.Text.UTF8Encoding($false)))
# Written to a file rather than piped: this machine's shell turns a native program's
# stderr into a terminating error when it is captured in a pipeline, and the harness
# is a Java program that may write there.
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
Step 'Done'
Info "full log : $log"
Info "frozen   : $FrozenVm ($($frozen.Length) bytes, sha256 $frozenHash)"
exit $code
