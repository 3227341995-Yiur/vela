<#
.SYNOPSIS
    Mutation tests for the Vela plugin verifier: does VerifyPlugin actually fail when
    something in the plugin is wrong?

.DESCRIPTION
    A verifier that never fails is indistinguishable from no verifier at all, and
    nothing had ever proved that this one fails.  This script takes the built plugin,
    breaks one thing at a time in a scratch copy of the source tree, runs the real
    verifier against the mutant, and requires the failure to *name* the problem.

    Every mutant is applied to a copy under %TEMP% (or -ScratchRoot).  Nothing under
    idea-plugin\ is written except by the build this script invokes and its logs.

    Two mechanical details matter, and both were mistakes waiting to happen:

    * The descriptor inside the jar is mutated too, always, by repacking the jar from
      its own extracted classes with the mutated plugin.xml in place.  The verifier's
      descriptorFreshness check compares the jar's descriptor with the source tree, so
      mutating only the source would make *every* mutant fail with "the artifact is
      STALE" -- a pass for the wrong reason, which would have hidden real holes.

    * Every text substitution asserts that it changed something, and how many times.
      A mutation that silently does not apply shows up as "the verifier did not catch
      it", which reads exactly like a verifier hole: the one confusion this script
      must not create.

    The baseline is run first, with no mutation.  If the baseline does not reproduce
    the verifier's verdict on the real tree, no mutant verdict below it means anything.

    FAIL lines from `[check smoke]`, `[check parity]` and `[check contract]` are
    counted separately as ENVIRONMENT, not as evidence, because those three run the
    C toolchain and were measured to be non-deterministic while another process is
    building the same repository (three identical runs of `vm.exe build
    examples/hello.vel` gave exit 0, 2, 2).  The patterns this script requires a
    mutant to produce are all descriptor-level, so a flaky compiler cannot make a
    mutant look caught or uncaught.

.PARAMETER Jar
    The built plugin jar to mutate.  Default: dist\vela\lib\vela-idea-plugin.jar.

.PARAMETER ScratchRoot
    Where the mutant trees go.  Default: %TEMP%\vela-mutation.

.PARAMETER JbrHome
    A JetBrains Runtime holding java.exe and javac.exe.  Default: the newest
    installed IDE's jbr (same discovery as build-offline.ps1).

.PARAMETER Only
    Run one mutant by name (debugging), instead of all of them.

.PARAMETER KeepScratch
    Keep every mutant's whole tree, not just its verify log.

.PARAMETER RequireAllCaught
    Exit 1 if a mutant is a HOLE (the verifier did not catch it).  By default a hole
    is a finding to report, not a script failure.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File idea-plugin\build-offline.ps1
    powershell -ExecutionPolicy Bypass -File idea-plugin\mutation-test.ps1

.NOTES
    Requires the verifier's own sources to be current: this script compiles
    build\tools\src\VerifyPlugin.java and JarTool.java itself into the scratch tree,
    so it tests the tree, not a stale class file.  Only those two are compiled: the
    rest of build\tools\src (KotlinBuild.java) imports the Kotlin compiler, which the
    verifier does not need and which is not needed to run it.
#>
[CmdletBinding()]
param(
    [string] $Jar,
    [string] $ScratchRoot,
    [string] $JbrHome,
    [string] $Only,
    [switch] $KeepScratch,
    [switch] $RequireAllCaught
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptDir  = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$pluginRoot = $scriptDir
$repoRoot   = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path
if (-not $Jar) { $Jar = Join-Path $pluginRoot 'dist\vela\lib\vela-idea-plugin.jar' }
if (-not $ScratchRoot) { $ScratchRoot = Join-Path $env:TEMP 'vela-mutation' }

function Step([string] $text) { Write-Host ''; Write-Host "=== $text" -ForegroundColor Cyan }
function Info([string] $text) { Write-Host "    $text" }
function Die([string] $text)  { Write-Host "ERROR: $text" -ForegroundColor Red; exit 2 }

# ---------------------------------------------------------------- toolchain

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

if (-not $JbrHome) {
    $ides = @(Get-InstalledIdes | Sort-Object)
    if ($ides.Count -eq 0) { Die 'no JetBrains IDE found; pass -JbrHome <...>\jbr' }
    $idea = @($ides | Where-Object { $_ -match 'IntelliJ|IDEA' })
    $best = if ($idea.Count -gt 0) { $idea[-1] } else { $ides[-1] }
    $JbrHome = Join-Path $best 'jbr'
}
$java  = Join-Path $JbrHome 'bin\java.exe'
$javac = Join-Path $JbrHome 'bin\javac.exe'
foreach ($t in @($java, $javac)) { if (-not (Test-Path -LiteralPath $t)) { Die "missing: $t" } }

$classpathFile = Join-Path $pluginRoot 'build\args\platform-classpath.txt'
if (-not (Test-Path -LiteralPath $classpathFile)) {
    Die ("missing $classpathFile.  Run build-offline.ps1 first: the verifier needs the installed " +
         "IDE's compile classpath, and the jar under test comes from the same build.")
}
if (-not (Test-Path -LiteralPath $Jar)) {
    Die "missing $Jar.  Run build-offline.ps1 first."
}
$Jar = (Resolve-Path -LiteralPath $Jar).Path

Step 'Toolchain'
Info "plugin jar   : $Jar"
Info "repo root    : $repoRoot"
Info "scratch root : $ScratchRoot"
Info "JDK          : $JbrHome"
Info "classpath    : $classpathFile"

# ---------------------------------------------------------------- scratch setup

New-Item -ItemType Directory -Force -Path $ScratchRoot | Out-Null
$toolsDir = Join-Path $ScratchRoot 'tools'
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
$logDir = Join-Path $ScratchRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# THE EVIDENCE IS PUBLISHED, VERSION-STAMPED, INSIDE THE REPO.
#
# The run itself has to happen in a scratch tree (it builds mutant jars and mutant
# descriptors, and nothing under idea-plugin\ must be written except by the build).  But
# a proof that lives only in %TEMP% cannot be checked by a reader, and one that lands in
# an unversioned directory cannot be told apart from the proof for a different build.
# "The verifier passed" and "the verifier can fail" must not be the same file at the same
# timestamp: `build\logs\verify.log` says the first, and
# `build\verify\mutation-<version>\` says the second, with the version in the file name
# and in the first line of every report.
$publishDir = Join-Path $pluginRoot 'build\verify'

Step '1/4  Compile the verifier from the tree (not from a stale class file)'
$verifySrc = Join-Path $pluginRoot 'tools\build\src\VerifyPlugin.java'
$jarToolSrc = Join-Path $pluginRoot 'tools\build\src\JarTool.java'
foreach ($f in @($verifySrc, $jarToolSrc)) { if (-not (Test-Path -LiteralPath $f)) { Die "missing $f" } }
& $javac --release 21 -encoding UTF-8 -d $toolsDir $verifySrc $jarToolSrc 1> "$logDir\javac.out" 2> "$logDir\javac.err"
if ($LASTEXITCODE -ne 0) {
    Get-Content "$logDir\javac.err" | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    Die "javac failed for the verifier (see $logDir\javac.out)"
}
Info "VerifyPlugin + JarTool -> $toolsDir"

# The version the jar was built at, read out of the artifact: every mutation that
# needs the current number reads it here rather than hard-coding one.
$jarXml = Join-Path $ScratchRoot 'jar-plugin.xml'
# JarTool extract does not overwrite (Files.copy onto an existing file throws
# FileAlreadyExistsException), so a second run of this script has to clear the
# destination itself.  Measured, not guessed: it failed here once.
$setupExtract = Join-Path $ScratchRoot 'extract'
if (Test-Path -LiteralPath $setupExtract) { Remove-Item -LiteralPath $setupExtract -Recurse -Force }
& $java -cp $toolsDir JarTool extract $Jar $setupExtract 1> "$logDir\extract.out" 2> "$logDir\extract.err"
if ($LASTEXITCODE -ne 0) { Die "could not extract the jar (see $logDir\extract.err)" }
Copy-Item -LiteralPath (Join-Path $setupExtract 'META-INF\plugin.xml') -Destination $jarXml -Force
$descriptorBytes = [System.IO.File]::ReadAllBytes($jarXml)
$descriptorText = [System.Text.Encoding]::UTF8.GetString($descriptorBytes)
$m = [regex]::Match($descriptorText, '<version>\s*([^<\s]+)\s*</version>')
if (-not $m.Success) { Die "the jar's plugin.xml has no <version>" }
$version = $m.Groups[1].Value
Info "plugin version: $version"

# A mutation is a text edit applied to a copy, so the copy has to be exact.  If the
# descriptor does not decode and re-encode byte-for-byte, every mutation would be
# applied to a different file than the one the verifier reads.
$reencoded = [System.Text.Encoding]::UTF8.GetBytes($descriptorText)
if ($reencoded.Length -ne $descriptorBytes.Length) {
    Die ("the descriptor does not round-trip through UTF-8 ($($descriptorBytes.Length) -> " +
         "$($reencoded.Length) bytes): mutations cannot be applied safely")
}
for ($i = 0; $i -lt $descriptorBytes.Length; $i++) {
    if ($descriptorBytes[$i] -ne $reencoded[$i]) { Die "the descriptor does not round-trip at byte $i" }
}
Info "descriptor encoding round-trips byte-for-byte through UTF-8 (mutations are exact)"

# ---------------------------------------------------------------- helpers

function New-MutantTree([string] $name) {
    $dir = Join-Path $ScratchRoot $name
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    # Only what the verifier reads: it derives every path from the jar's location, so
    # dist\, the sources, the Gradle file and the changelog all have to be there.
    Copy-Item -LiteralPath (Join-Path $pluginRoot 'src') -Destination (Join-Path $dir 'src') -Recurse
    Copy-Item -LiteralPath (Join-Path $pluginRoot 'build.gradle.kts') -Destination $dir
    Copy-Item -LiteralPath (Join-Path $pluginRoot 'CHANGELOG.md') -Destination $dir
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'dist\vela\lib') | Out-Null
    Copy-Item -LiteralPath $Jar -Destination (Join-Path $dir 'dist\vela\lib\vela-idea-plugin.jar')
    Get-ChildItem -LiteralPath (Join-Path $pluginRoot 'dist') -Filter '*.zip' -File -ErrorAction SilentlyContinue |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $dir 'dist') }
    return $dir
}

# One literal substitution, with the number of hits asserted.  `expected` is the
# number of occurrences the mutation is written against; a mismatch means the source
# changed shape and the mutation is no longer the mutation it claims to be.
function Edit-Text([string] $path, [string] $old, [string] $new, [int] $expected = 1) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $hits = 0
    $idx = 0
    while (($idx = $text.IndexOf($old, $idx, [System.StringComparison]::Ordinal)) -ge 0) {
        $hits++
        $idx += $old.Length
    }
    if ($hits -ne $expected) {
        throw "mutation did not apply: `"$old`" occurs $hits time(s) in $path, expected $expected"
    }
    $text = $text.Replace($old, $new)
    [System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::UTF8.GetBytes($text))
}

function Remove-Text([string] $path, [string] $pattern, [int] $expected = 1, [switch] $Multiline) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $options = if ($Multiline) { [System.Text.RegularExpressions.RegexOptions]::Multiline }
               else { [System.Text.RegularExpressions.RegexOptions]::Singleline }
    $rx = [regex]::new($pattern, $options)
    $hits = $rx.Matches($text).Count
    if ($hits -ne $expected) {
        throw "mutation did not apply: /$pattern/ matches $hits time(s) in $path, expected $expected"
    }
    [System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::UTF8.GetBytes($rx.Replace($text, '')))
}

# The descriptor inside the jar is rebuilt from the mutant's own source descriptor,
# so the two always agree and descriptorFreshnessnever fires: a mutant must fail
# because of what was mutated, never because the artifact went stale.
function Update-Jar([string] $dir) {
    $extract = Join-Path $dir 'jarextract'
    if (Test-Path -LiteralPath $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
    & $java -cp $toolsDir JarTool extract $Jar $extract 1> (Join-Path $dir 'extract.out') 2> (Join-Path $dir 'extract.err')
    if ($LASTEXITCODE -ne 0) { throw 'could not extract the jar for repacking' }
    Copy-Item -LiteralPath (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') `
              -Destination (Join-Path $extract 'META-INF\plugin.xml') -Force
    $out = Join-Path $dir 'dist\vela\lib\vela-idea-plugin.jar'
    & $java -cp $toolsDir JarTool create $out --root $extract 1> (Join-Path $dir 'pack.out') 2> (Join-Path $dir 'pack.err')
    if ($LASTEXITCODE -ne 0) { throw 'could not repack the jar' }
    if (-not $KeepScratch) { Remove-Item -LiteralPath $extract -Recurse -Force }
}

function Invoke-Verifier([string] $dir, [string] $tag) {
    $jarPath = Join-Path $dir 'dist\vela\lib\vela-idea-plugin.jar'
    $log = Join-Path $logDir "$tag.log"
    # Redirected to files, never piped: java writes warnings to stderr and PowerShell
    # turns a native command's stderr into a terminating error when it is piped.
    & $java -cp $toolsDir VerifyPlugin $jarPath $classpathFile $repoRoot 1> $log 2> "$log.err"
    $code = $LASTEXITCODE
    $lines = @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue)
    $fails = @($lines | Where-Object { $_ -like '  FAIL *' } | ForEach-Object { $_.Substring(7).Trim() })
    $interim = @($lines | Where-Object { $_ -like '    FAIL *' } | ForEach-Object { $_.Substring(9).Trim() })
    $verdict = @($lines | Where-Object { $_ -like 'RESULT:*' })
    return [pscustomobject]@{
        Exit    = $code
        Log     = $log
        Fails   = $fails
        Interim = $interim
        Verdict = if ($verdict.Count -gt 0) { $verdict[0] } else { '(no RESULT line: the verifier did not finish)' }
        Crashed = ($verdict.Count -eq 0)
    }
}

$FLAKY_PATTERNS = @('\[check smoke', '\[check parity', '\[check contract')
function Split-Fails($fails) {
    $flaky = @($fails | Where-Object { $f = $_; @($FLAKY_PATTERNS | Where-Object { $f -match $_ }).Count -gt 0 })
    $real = @($fails | Where-Object { $f = $_; @($FLAKY_PATTERNS | Where-Object { $f -match $_ }).Count -eq 0 })
    return [pscustomobject]@{ Environment = $flaky; Real = $real }
}

# ---------------------------------------------------------------- the mutations
#
# Each entry: Name, Why (what is broken, in one line), Patterns (every one must
# appear in a FAIL line for the mutant to count as CAUGHT), Apply (edits the copy).
# An entry with no Patterns is a control: the verifier must NOT fail it.

$mutations = @(
    @{ Name = 'wrongAttribute_referenceContributor'
       Why  = 'psi.referenceContributor spelled implementationClass instead of implementation (the silent no-op the verifier once accepted)'
       Patterns = @('names the class with', '\[check registrationAttributes\]', 'com\.intellij\.psi\.referenceContributor')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') `
                     'implementation="dev.vela.plugin.VelaReferenceContributor"' `
                     'implementationClass="dev.vela.plugin.VelaReferenceContributor"'
       } }

    @{ Name = 'wrongAttribute_liveTemplateContext'
       Why  = 'liveTemplateContext (beanClass point, binds implementation) spelled implementationClass'
       Patterns = @('names the class with', '\[check registrationAttributes\]', 'com\.intellij\.liveTemplateContext')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') `
                     'implementation="dev.vela.plugin.VelaTemplateContextType"' `
                     'implementationClass="dev.vela.plugin.VelaTemplateContextType"'
       } }

    @{ Name = 'classNotInJar'
       Why  = 'a registration names a class that is in no class file'
       Patterns = @('NO CLASS FILE', 'VelaFindUsagesProviderTypo')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') `
                     'implementationClass="dev.vela.plugin.VelaFindUsagesProvider"' `
                     'implementationClass="dev.vela.plugin.VelaFindUsagesProviderTypo"'
       } }

    @{ Name = 'classRegisteredNowhere'
       Why  = 'the inlay provider compiles and is registered nowhere (the inert feature, third time)'
       Patterns = @('\[check unregisteredImplementations\]', 'VelaParameterNameInlayHintsProvider', 'InlayHintsProvider')
       Apply = {
           param($dir)
           Remove-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') `
                       '<codeInsight\.inlayProvider\b[^>]*/>'
       } }

    @{ Name = 'fileTypeLosesVel'
       Why  = 'the file type stops claiming the .vel suffix (exactly what 0.1.0 shipped)'
       Patterns = @('does not claim "vel"', 'extensions="vela"')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') 'extensions="vel;vela"' 'extensions="vela"'
       } }

    @{ Name = 'addToGroupUnknownGroup'
       Why  = 'add-to-group names a group id that does not exist'
       Patterns = @('is not an action or group id the installed platform defines', 'NewGroupNoSuch')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') '<add-to-group group-id="NewGroup"' `
                     '<add-to-group group-id="NewGroupNoSuch"'
       } }

    @{ Name = 'addToGroupNamesAnAction'
       Why  = 'add-to-group group-id names an <action> in this plugin.xml (the 0.1.0 SEVERE)'
       Patterns = @('names an <action> in this plugin.xml, not a', 'Vela\.NewFile')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') '<add-to-group group-id="NewGroup"' `
                     '<add-to-group group-id="Vela.NewFile"'
       } }

    @{ Name = 'extensionPointUnknownId'
       Why  = 'a registration uses an extension point id no installed descriptor declares (silent no-op)'
       Patterns = @('which no installed descriptor declares or uses', 'lang\.commenterX')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') '<lang.commenter language="Vela"' `
                     '<lang.commenterX language="Vela"'
       } }

    @{ Name = 'parserDefinitionWithoutLangPrefix'
       Why  = 'lang.parserDefinition written without the lang. prefix, which names a point that does not exist'
       Patterns = @('declares that extension point as "com\.intellij\.lang\.parserDefinition"')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') '<lang.parserDefinition language="Vela"' `
                     '<parserDefinition language="Vela"'
       } }

    @{ Name = 'versionDriftsFromGradle'
       Why  = 'build.gradle.kts disagrees with plugin.xml about the version'
       Patterns = @('version drift', '9\.9\.9', 'build\.gradle\.kts')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'build.gradle.kts') "version = `"$version`"" 'version = "9.9.9"'
       } }

    @{ Name = 'versionBumpedOnlyInDescriptor'
       Why  = 'the version in plugin.xml is bumped and nothing else follows (dist zip and changelog left behind)'
       Patterns = @('version drift', 'no dist file named for this version', 'no heading for version 9\.9\.9')
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') "<version>$version</version>" '<version>9.9.9</version>'
       } }

    @{ Name = 'changelogHasNoSection'
       Why  = 'the changelog has no entry for the version plugin.xml carries'
       Patterns = @('has no heading for version', '\[check versionDiscipline\]')
       Apply = {
           param($dir)
           Remove-Text (Join-Path $dir 'CHANGELOG.md') "^##\s*$([regex]::Escape($version)).*$" 1 -Multiline
       } }

    @{ Name = 'equivalent_xmlComment'
       Why  = 'CONTROL: a comment inside <extensions> changes no behaviour, so the verifier must NOT fail'
       Patterns = @()
       Apply = {
           param($dir)
           Edit-Text (Join-Path $dir 'src\main\resources\META-INF\plugin.xml') '<extensions defaultExtensionNs="com.intellij">' `
                     "<extensions defaultExtensionNs=`"com.intellij`">`n        <!-- mutation-test: equivalent mutation -->"
       } }
)

# ---------------------------------------------------------------- run

Step "2/4  Baseline (no mutation): the verifier must reproduce its verdict on the tree as built"
$baselineDir = New-MutantTree 'baseline'
Update-Jar $baselineDir
$baseline = Invoke-Verifier $baselineDir 'baseline'
$split = Split-Fails $baseline.Fails
Info "verdict   : $($baseline.Verdict)"
Info "log       : $($baseline.Log)"
foreach ($f in $split.Real) { Write-Host "      FAIL (real) $f" -ForegroundColor Red }
foreach ($f in $split.Environment) { Write-Host "      FAIL (environment/flaky) $f" -ForegroundColor DarkYellow }
if ($baseline.Crashed) { Die "the baseline verifier did not finish; nothing below it would mean anything" }
$baselineReal = $split.Real
$baselineEnv  = $split.Environment.Count

Step '3/4  Mutants'
$rows = New-Object System.Collections.Generic.List[object]
foreach ($mu in $mutations) {
    if ($Only -and $mu.Name -ne $Only) { continue }
    $name = $mu.Name
    Write-Host ''
    Write-Host "  --- $name" -ForegroundColor White
    Info "why: $($mu.Why)"
    $dir = New-MutantTree $name
    $applied = $true
    try {
        & $mu.Apply $dir
    } catch {
        $applied = $false
        Info "MUTATION DID NOT APPLY: $($_.Exception.Message)"
    }
    if (-not $applied) {
        $rows.Add([pscustomobject]@{ Mutant = $name; Expected = ($mu.Patterns -join ' & '); Verdict = 'NOT-APPLIED'; Message = 'the edit matched nothing (script defect, not a verifier finding)'; Log = '' })
        continue
    }
    try {
        Update-Jar $dir
    } catch {
        $rows.Add([pscustomobject]@{ Mutant = $name; Expected = ($mu.Patterns -join ' & '); Verdict = 'REPACK-FAILED'; Message = $_.Exception.Message; Log = '' })
        continue
    }
    $res = Invoke-Verifier $dir $name
    $mutSplit = Split-Fails $res.Fails
    $all = @($res.Fails) + @($res.Interim)
    $joined = $all -join "`n"
    $missing = @($mu.Patterns | Where-Object { $p = $_; $joined -notmatch $p })
    $extra = @($mutSplit.Real | Where-Object { $f = $_; $baselineReal -notcontains $f })

    if ($mu.Patterns.Count -eq 0) {
        $verdict = if ($res.Fails.Count -eq 0) { 'NOT-CAUGHT (correct: equivalent)' } else { 'CAUGHT (WRONG: this mutation is equivalent)' }
        $message = if ($res.Fails.Count -eq 0) { '(no failure at all, as required)' } else { $res.Fails -join ' ;; ' }
    } elseif ($res.Crashed) {
        $verdict = 'CRASHED (verifier threw)'
        $message = $res.Fails -join ' ;; '
        if (-not $message) { $message = (Get-Content -LiteralPath $res.Log -Tail 3) -join ' ' }
    } elseif ($res.Fails.Count -eq 0) {
        $verdict = 'HOLE (not caught)'
        $message = '(the verifier passed the mutant: RESULT: PASS)'
    } elseif ($missing.Count -gt 0) {
        $verdict = 'WRONG-REASON'
        $message = 'missing: ' + ($missing -join ', ') + ' | got: ' + (($res.Fails -join ' ;; '))
    } else {
        $verdict = 'CAUGHT'
        $message = @($res.Fails | Where-Object { $f = $_; @($mu.Patterns | Where-Object { $f -match $_ }).Count -gt 0 }) -join ' ;; '
    }
    foreach ($f in $res.Fails) { Write-Host "      FAIL $f" -ForegroundColor $(if ($mutSplit.Environment -contains $f) { 'DarkYellow' } else { 'Red' }) }
    if ($verdict -eq 'CAUGHT') { Write-Host "      -> CAUGHT" -ForegroundColor Green }
    elseif ($verdict -like 'NOT-CAUGHT*') { Write-Host "      -> $verdict" -ForegroundColor Green }
    else { Write-Host "      -> $verdict" -ForegroundColor Red }
    if ($extra.Count -gt 0) { Info "also failed, beyond the baseline: $($extra -join ' ;; ')" }
    $envCount = $mutSplit.Environment.Count
    if ($envCount -gt 0) { Info "environment/flaky failures: $envCount (not used as evidence)" }

    $rows.Add([pscustomobject]@{
        Mutant   = $name
        Expected = if ($mu.Patterns.Count -eq 0) { '(none: control)' } else { ($mu.Patterns -join ' & ') }
        Verdict  = $verdict
        Message  = ($message -replace '\s+', ' ')
        Log      = $res.Log
    })
    if (-not $KeepScratch) { Remove-Item -LiteralPath $dir -Recurse -Force }
}

# ---------------------------------------------------------------- table

Step '4/4  The table'
$table = @()
$table += 'mutant                              | expected failure                                         | actual verdict              | log'
$table += '------------------------------------+----------------------------------------------------------+-----------------------------+----'
foreach ($r in $rows) {
    $e = $r.Expected; if ($e.Length -gt 56) { $e = $e.Substring(0, 53) + '...' }
    $table += ("{0,-35} | {1,-56} | {2,-27} | {3}" -f $r.Mutant, $e, $r.Verdict, (Split-Path -Leaf $r.Log))
}
$table += ''
$table += 'the message each mutant used:'
foreach ($r in $rows) {
    $table += ("  {0}`n      {1}" -f $r.Mutant, $r.Message)
}
$table += ''
$table += "baseline (no mutation)             : $($baseline.Verdict)"
$table += "baseline environment failures      : $baselineEnv (smoke/parity/contract: the C toolchain, flaky under concurrent builds)"
$caught = @($rows | Where-Object { $_.Verdict -like 'CAUGHT' }).Count
$holes = @($rows | Where-Object { $_.Verdict -like 'HOLE*' -or $_.Verdict -like 'CRASHED*' }).Count
$wrong = @($rows | Where-Object { $_.Verdict -like 'WRONG-REASON*' -or $_.Verdict -like 'NOT-APPLIED*' -or $_.Verdict -like 'REPACK-FAILED*' -or $_.Verdict -like 'CAUGHT (WRONG*' }).Count
$controls = @($rows | Where-Object { $_.Verdict -like 'NOT-CAUGHT (correct*' }).Count
$table += "mutants: $($rows.Count)   caught: $caught   holes: $holes   controls correct: $controls   script/verdict problems: $wrong"

$report = Join-Path $logDir 'mutation-report.txt'
[System.IO.File]::WriteAllLines($report, $table, (New-Object System.Text.UTF8Encoding($false)))
$table | ForEach-Object { Write-Host $_ }
Write-Host ''
Info "full report: $report"
Info "per-mutant verifier logs: $logDir"

# ---- publish the proof where a reader can find it, named for the build it describes
$outDir = Join-Path $publishDir "mutation-$version"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$header = @(
    "# Vela plugin mutation tests -- the proof that VerifyPlugin CAN fail",
    "# plugin version      : $version",
    "# jar under test      : $Jar",
    "# jar sha256          : $((Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash.ToLowerInvariant())",
    "# verifier sources    : $verifySrc (+ JarTool.java), compiled fresh into $toolsDir",
    "# classpath document  : $classpathFile",
    "# written             : $((Get-Date).ToString('o'))",
    "# baseline            : $($baseline.Verdict)",
    "# mutants             : $($rows.Count)   caught: $caught   holes: $holes   controls correct: $controls   script/verdict problems: $wrong",
    ""
)
[System.IO.File]::WriteAllLines((Join-Path $outDir "mutation-report-$version.txt"),
    [string[]] ($header + $table), (New-Object System.Text.UTF8Encoding($false)))
foreach ($f in (Get-ChildItem -LiteralPath $logDir -Filter '*.log' -File -ErrorAction SilentlyContinue)) {
    Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $outDir $f.Name) -Force
}
Info "published          : $outDir (mutation-report-$version.txt + $($rows.Count) per-mutant verifier log(s))"

if ($wrong -gt 0) { exit 1 }
if ($RequireAllCaught -and $holes -gt 0) { exit 1 }
exit 0
