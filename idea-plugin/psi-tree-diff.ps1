<#
.SYNOPSIS
    The PSI half of the parser's acceptance test: is the tree the IDE is given the
    tree the parser built, and does it cover the file?

.DESCRIPTION
    `AstDiff` proves the parser agrees with the compiler about what a Vela program
    is.  It cannot see the other half: `VelaParserDefinition` has to replay that
    tree onto the platform's `PsiBuilder`, and a replay can be wrong in a way the
    differential cannot detect -- the first version of it dropped every leaf token
    one level up, so `NAME` elements came out empty with the identifier beside them
    as a sibling, and the compiler-format dump, which prints attributes rather than
    leaves, agreed with the compiler anyway.

    So this parses every file in the corpus through the platform's *own*
    `PsiBuilderImpl` and checks the result against the parser's tree:

      * the composite elements, in order, are the parser's nodes by kind;
      * the leaves tile the file's text exactly -- offset 0 to the end, no gap, no
        overlap -- so nothing is lost;
      * every non-trivia leaf is a token the parser claimed, with the same text, so
        nothing is invented;
      * a file the parser reported a problem in still gets a `VELA_ERROR` element,
        and the file's last line still has a node, so one bad line never costs the
        rest of the file.

    Running the platform's builder headlessly needs a booted Application, which this
    machine has no `idea.exe` for; the harness boots the platform's own
    `MockApplication` (the one its test framework uses) instead, and says so in its
    output.  Nothing else is faked: the builder, the lexer, the element types and
    the marker bookkeeping are the platform's.

    That test found a real defect the differential had hidden: a stray `}` at file
    level made the parser report the same token four million times, so a five-line
    file produced a tree with four million error elements -- invisible in the dump
    (an error node prints nothing) and fatal in an editor.

.PARAMETER RepoRoot
    The Vela checkout to run over.  Default: the parent of this script's folder.

.PARAMETER Single
    Test one file (a path relative to the repo root) instead of the corpus.

.PARAMETER ShowTrees
    Pass --verbose to the harness.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\psi-tree-diff.ps1
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Single,
    [switch] $ShowTrees
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$pluginRoot = $scriptDir
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$harnessSrc = Join-Path $pluginRoot 'build\tools\harness\src'
$harnessOut = Join-Path $pluginRoot 'build\tools\harness\psi-classes'
$pluginClasses = Join-Path $pluginRoot 'build\classes'
$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

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
if ($ides.Count -eq 0) { Die 'no JetBrains IDE found' }
$idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' })
$best = if ($idea.Count -gt 0) { $idea[-1] } else { $ides[-1] }
$java  = Join-Path $best 'jbr\bin\java.exe'
$javac = Join-Path $best 'jbr\bin\javac.exe'
foreach ($t in @($java, $javac)) { if (-not (Test-Path -LiteralPath $t)) { Die "missing: $t" } }

Step 'Preconditions'
Info "repo root : $RepoRoot"
Info "IDE       : $best"
Info '(the platform jars are the compile classpath *and* the runtime classpath: the'
Info ' harness boots the platform''s own MockApplication and drives its PsiBuilderImpl)'
if (-not (Test-Path -LiteralPath $classpathFile)) { Die "missing $classpathFile.  Run build-offline.ps1 first." }
if (-not (Test-Path -LiteralPath $pluginClasses)) { Die "missing $pluginClasses.  Run build-offline.ps1 first." }

Step 'Compile the harness against the plugin classes'
New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$classpath = Get-Content -LiteralPath $classpathFile -Raw
& $javac --release 21 -encoding UTF-8 -cp "$pluginClasses;$classpath" -d $harnessOut `
    (Join-Path $harnessSrc 'PsiTreeDiff.java') 2>&1 |
    Tee-Object -FilePath (Join-Path $harnessOut 'javac.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed for the harness (see $harnessOut\javac.log)" }

Step 'Every file in the corpus, through the platform''s own builder'
$harnessArgs = @('-cp', "$harnessOut;$pluginClasses;$classpath", 'PsiTreeDiff', $RepoRoot)
if ($ShowTrees) { $harnessArgs += '--verbose' }
if ($Single)    { $harnessArgs += @('--single', $Single) }
$log = Join-Path $harnessOut 'psi-tree-diff.log'
& $java @harnessArgs 1> $log 2> "$log.err"
$code = $LASTEXITCODE
Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
$errText = ''
if (Test-Path -LiteralPath "$log.err") { $errText = (Get-Content -LiteralPath "$log.err" -Raw) }
if ($errText -and $errText.Trim().Length -gt 0) {
    Write-Host '--- stderr' -ForegroundColor DarkYellow
    Write-Host $errText.Trim()
}
Step 'Done'
Info "full log : $log"
exit $code
