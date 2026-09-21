<#
.SYNOPSIS
    Builds the Vela IntelliJ plugin offline: no Gradle, no network, no Maven.

.DESCRIPTION
    This machine has no `java`, `javac`, `gradle` or `kotlin` on PATH and no
    ~/.gradle cache, so `gradlew buildPlugin` cannot run.  What it does have is
    installed JetBrains IDEs, and a JetBrains IDE carries everything needed to
    build a plugin for itself:

        <ide>\jbr\bin\java.exe, javac.exe     a full JDK (Java 21 / 25)
        <ide>\lib\*.jar                       the platform API to compile against
        <ide>\plugins\Kotlin\kotlinc\lib\kotlin-compiler.jar
                                              the Kotlin compiler + stdlib

    So this script compiles the Kotlin sources against the platform jars,
    packs the result into a plugin jar, wraps it in the dist zip that
    "Settings > Plugins > Install Plugin from Disk" wants, and then verifies
    the artifact.

.PARAMETER PlatformHome
    The IDE whose lib\*.jar are the compile classpath.  Defaults to the newest
    installed IntelliJ IDEA, else the newest installed JetBrains IDE.

.PARAMETER JbrHome
    A JetBrains Runtime to run javac / the Kotlin compiler / the build tools.
    Defaults to <PlatformHome>\jbr.

.PARAMETER KotlincHome
    The kotlinc/ distribution directory that holds lib\kotlin-compiler.jar.
    Defaults to <PlatformHome>\plugins\Kotlin\kotlinc, else the first IDE that
    has one.

.PARAMETER SkipVerify
    Compile and package only; do not run the artifact checks.

.EXAMPLE
    .\build-offline.ps1
    Finds the IDEs itself and produces dist\vela-idea-plugin-<version>.zip, where
    <version> is read out of src\main\resources\META-INF\plugin.xml -- the script
    never holds a version of its own, so the file name cannot drift from the
    descriptor.  VerifyPlugin then checks that build.gradle.kts, the dist file name
    and CHANGELOG.md all carry the same number.

.EXAMPLE
    .\build-offline.ps1 -PlatformHome "C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.4" `
                        -JbrHome      "C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.4\jbr"

.NOTES
    Intermediates all land under idea-plugin\build\ ; the deliverables land
    under idea-plugin\dist\ .  Nothing outside idea-plugin\ is written.
#>
[CmdletBinding()]
param(
    [string] $PlatformHome,
    [string] $JbrHome,
    [string] $KotlincHome,
    [string] $KotlinCompilerJar,
    [switch] $SkipVerify,
    [switch] $KeepStaging
)

# Continue, not Stop: java and the Kotlin compiler write to stderr as a matter
# of course (JBR warns about sun.misc.Unsafe on every single run), and turning
# stderr into a terminating error would abort a perfectly good build.  Every
# native call below is checked by hand against $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Version Latest

# ---------------------------------------------------------------- paths

$scriptDir  = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$pluginRoot = $scriptDir
$buildDir   = Join-Path $pluginRoot 'build'
$distDir    = Join-Path $pluginRoot 'dist'
# The verifier's *sources* live in tools\, not under build\: build\ is gitignored,
# and a verifier that exists only in a gitignored directory is a verifier no fresh
# clone can run, on a tree it cannot even build.  Only $toolsOut is output, so only
# $toolsOut belongs under build\.
$toolsSrc   = Join-Path $pluginRoot 'tools\build\src'
$toolsOut   = Join-Path $buildDir 'tools\classes'
$argsDir    = Join-Path $buildDir 'args'
$classesDir = Join-Path $buildDir 'classes'
$stagingDir = Join-Path $buildDir 'staging'
$logDir     = Join-Path $buildDir 'logs'

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- find an IDE

function Get-InstalledIdes {
    $roots = @(
        'D:\JetBrains',
        'C:\JetBrains',
        'C:\Program Files\JetBrains',
        'C:\Program Files (x86)\JetBrains',
        (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'),
        (Join-Path $env:LOCALAPPDATA 'Programs'),
        (Join-Path $env:USERPROFILE '.local\share\JetBrains\Toolbox\apps')
    )
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $dirs = Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue
        foreach ($d in $dirs) {
            if ((Test-Path -LiteralPath (Join-Path $d.FullName 'jbr\bin\java.exe')) -and
                (Test-Path -LiteralPath (Join-Path $d.FullName 'lib')) -and
                (Test-Path -LiteralPath (Join-Path $d.FullName 'build.txt'))) {
                if (-not $found.Contains($d.FullName)) { $found.Add($d.FullName) }
            }
        }
    }
    return $found.ToArray()
}

$ides = @(Get-InstalledIdes)
if ($ides.Count -eq 0) {
    Die ("no JetBrains IDE installation found.  Pass -PlatformHome <ide dir> explicitly.  " +
         "An IDE directory is one containing build.txt, lib\ and jbr\bin\java.exe.")
}

if (-not $PlatformHome) {
    $idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' } | Sort-Object)
    if ($idea.Count -gt 0) { $PlatformHome = $idea[-1] } else { $PlatformHome = (@($ides | Sort-Object))[-1] }
}
if (-not (Test-Path -LiteralPath $PlatformHome)) { Die "PlatformHome does not exist: $PlatformHome" }
if (-not $JbrHome) { $JbrHome = Join-Path $PlatformHome 'jbr' }

if (-not $KotlincHome) {
    $candidate = Join-Path $PlatformHome 'plugins\Kotlin\kotlinc'
    if (Test-Path -LiteralPath (Join-Path $candidate 'lib\kotlin-compiler.jar')) {
        $KotlincHome = $candidate
    } else {
        foreach ($ide in ($ides | Sort-Object)) {
            $candidate = Join-Path $ide 'plugins\Kotlin\kotlinc'
            if (Test-Path -LiteralPath (Join-Path $candidate 'lib\kotlin-compiler.jar')) {
                $KotlincHome = $candidate
                break
            }
        }
    }
}
if (-not $KotlincHome) {
    Die ("found no Kotlin compiler.  Looked for <ide>\plugins\Kotlin\kotlinc\lib\kotlin-compiler.jar.  " +
         "Install an IDE with the bundled Kotlin plugin, or pass -KotlincHome.")
}
if (-not $KotlinCompilerJar) { $KotlinCompilerJar = Join-Path $KotlincHome 'lib\kotlin-compiler.jar' }

$java  = Join-Path $JbrHome 'bin\java.exe'
$javac = Join-Path $JbrHome 'bin\javac.exe'
foreach ($tool in @($java, $javac, $KotlinCompilerJar)) {
    if (-not (Test-Path -LiteralPath $tool)) { Die "missing: $tool" }
}

Step 'Toolchain'
Info "platform (compile classpath) : $PlatformHome"
Info "JDK (JetBrains Runtime)      : $JbrHome"
Info "Kotlin compiler              : $KotlinCompilerJar"
$jdkVersion = (& $java -version 2>&1 | Select-Object -First 1)
Info "java -version                : $jdkVersion"
$platformBuild = (Get-Content -LiteralPath (Join-Path $PlatformHome 'build.txt') -Raw).Trim()
Info "platform build               : $platformBuild"
Info "kotlinc build                : $((Get-Content -LiteralPath (Join-Path $KotlincHome 'build.txt') -Raw).Trim())"

# ---------------------------------------------------------------- clean

foreach ($dir in @($classesDir, $toolsOut, $argsDir, $stagingDir, $logDir)) {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
foreach ($dir in @($buildDir, $classesDir, $toolsOut, $argsDir, $stagingDir, $logDir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
New-Item -ItemType Directory -Force -Path (Join-Path $stagingDir 'vela\lib') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $distDir 'vela\lib') | Out-Null

# ---------------------------------------------------------------- 1. build tools

Step '1/6  Compile the build tools (javac)'
$toolSources = @(Get-ChildItem -LiteralPath $toolsSrc -Filter '*.java' | ForEach-Object { $_.FullName })
if ($toolSources.Count -eq 0) { Die "no *.java sources under $toolsSrc" }
& $javac --release 21 -encoding UTF-8 -cp $KotlinCompilerJar -d $toolsOut @toolSources 2>&1 |
    Tee-Object -FilePath (Join-Path $logDir 'javac-tools.log')
if ($LASTEXITCODE -ne 0) { Die "javac failed for the build tools (see build\logs\javac-tools.log)" }
Info "tools -> $toolsOut"

$toolClasspath = "$KotlinCompilerJar;$toolsOut"

# ---------------------------------------------------------------- 2. platform classpath

Step '2/6  Collect the platform compile classpath'
$platformJars = @(Get-ChildItem -LiteralPath (Join-Path $PlatformHome 'lib') -Filter '*.jar' |
                  ForEach-Object { $_.FullName })
if ($platformJars.Count -eq 0) { Die "no jars under $(Join-Path $PlatformHome 'lib')" }
$classpath = ($platformJars -join ';')
$classpathFile = Join-Path $argsDir 'platform-classpath.txt'
[System.IO.File]::WriteAllText($classpathFile, $classpath, (New-Object System.Text.UTF8Encoding($false)))
Info "$($platformJars.Count) jars, $($classpath.Length) characters -> $classpathFile"
Info '(a file, not a command line: that classpath is 32 KB and would not fit)'

# ---------------------------------------------------------------- 3. kotlin

Step '3/6  Compile the plugin (kotlinc)'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $pluginRoot 'src\main\kotlin') -Recurse -Filter '*.kt' |
             ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Die 'no Kotlin sources found' }
Info "$($sources.Count) source file(s)"

$kotlinArgs = @(
    '-Xmx4g', '-cp', $toolClasspath, 'KotlinBuild',
    '--classpath-file', $classpathFile,
    '--kotlin-home', $KotlincHome,
    '--out', $classesDir,
    '--jvm-target', '21',
    '--module-name', 'vela-idea-plugin',
    '--'
) + $sources

$kotlinLog = Join-Path $logDir 'kotlinc.log'
& $java @kotlinArgs 2>&1 | Tee-Object -FilePath $kotlinLog
if ($LASTEXITCODE -ne 0) {
    # Say *which* compilation failed and where, on stdout, so the failure can be
    # routed to the author of the file without opening the log: the log is the
    # record, this is the message.
    Write-Host 'kotlinc reported errors - each line below is `<file>:<line>: error: <message>`:' -ForegroundColor Red
    Select-String -LiteralPath $kotlinLog -Pattern ': error: ' -SimpleMatch -CaseSensitive -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "    $($_.Line.Trim())" -ForegroundColor Red }
    Die "kotlinc failed (full output: build\logs\kotlinc.log)"
}
# -CaseSensitive matters: the JBR banner ("java.exe : WARNING: ... Unsafe ...") is
# written to the same log on every run, and matching it case-insensitively made
# every clean build report one warning that was not a compiler warning at all.
$warnings = @(Select-String -LiteralPath $kotlinLog -Pattern ': warning: ' -SimpleMatch -CaseSensitive -ErrorAction SilentlyContinue)
if ($warnings.Count -gt 0) {
    Info "$($warnings.Count) warning(s), no errors - see build\logs\kotlinc.log"
} else {
    Info 'no warnings, no errors'
}
Info "classes -> $classesDir"

# ---------------------------------------------------------------- 4. plugin jar

Step '4/6  Pack the plugin jar'
$version = '0.0.0'
$pluginXml = Get-Content -LiteralPath (Join-Path $pluginRoot 'src\main\resources\META-INF\plugin.xml') -Raw
$m = [regex]::Match($pluginXml, '<version>\s*([^<\s]+)\s*</version>')
if ($m.Success) { $version = $m.Groups[1].Value } else { Die 'plugin.xml has no <version> element' }
Info "plugin version: $version"

$jarName     = 'vela-idea-plugin.jar'
$stagedJar   = Join-Path $stagingDir "vela\lib\$jarName"
$resourceDir = Join-Path $pluginRoot 'src\main\resources'

& $java -cp $toolsOut JarTool create $stagedJar --root $classesDir --root $resourceDir
if ($LASTEXITCODE -ne 0) { Die 'could not pack the plugin jar' }

# ---------------------------------------------------------------- 5. dist zip

Step '5/6  Wrap it the way the IDE expects'
$zipPath = Join-Path $distDir "vela-idea-plugin-$version.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
& $java -cp $toolsOut JarTool create $zipPath --root $stagingDir --no-manifest
if ($LASTEXITCODE -ne 0) { Die 'could not write the dist zip' }

Copy-Item -LiteralPath $stagedJar -Destination (Join-Path $distDir "vela\lib\$jarName") -Force
Info "unpacked plugin : $(Join-Path $distDir 'vela')"
Info "install file    : $zipPath"
if (-not $KeepStaging) { Remove-Item -LiteralPath $stagingDir -Recurse -Force }

# ---------------------------------------------------------------- 6. verify

$verifyExit = 0
$verifyLog  = Join-Path $logDir 'verify.log'
if ($SkipVerify) {
    Step '6/6  Verification skipped (-SkipVerify)'
} else {
    Step '6/6  Verify the artifact'
    # The repo root is passed on so the verifier can find selfhost\build\vm.exe and
    # tests\build\*.vel: the diagnostics path is only proven end to end against the
    # compiler that will actually run, not against a synthetic string.
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path
    & $java -cp $toolsOut VerifyPlugin (Join-Path $distDir "vela\lib\$jarName") $classpathFile $repoRoot |
        Tee-Object -FilePath $verifyLog
    $verifyExit = $LASTEXITCODE
    if ($verifyExit -ne 0) {
        Write-Host 'FAILED CHECKS:' -ForegroundColor Red
        # the verifier prints one "  FAIL <reason>" line per problem at the end of
        # its output; repeating them here is what makes the exit code actionable
        Select-String -LiteralPath $verifyLog -Pattern '^  FAIL ' -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host "  - $($_.Line.Trim())" -ForegroundColor Red }
    }
}

Step 'Done'
Info "jar  : $(Join-Path $distDir "vela\lib\$jarName") ($((Get-Item -LiteralPath (Join-Path $distDir "vela\lib\$jarName")).Length) bytes)"
Info "zip  : $zipPath ($((Get-Item -LiteralPath $zipPath).Length) bytes)"
Info 'log  : build\logs\verify.log (the full check list, OK and FAIL per check)'
Info 'surface: build\logs\surface.txt (every extension point, action and menu target, machine-written)'
if ($verifyExit -ne 0) {
    Write-Host 'VERIFICATION FAILED - do not ship this artifact' -ForegroundColor Red
    exit $verifyExit
}
Write-Host 'OK' -ForegroundColor Green
