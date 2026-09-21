# Mutation tests for VerifyPlugin: build a scratch jar with one deliberate bug in it
# and require the verifier to fail it.  If the verifier ever stops catching these,
# the checks have gone vacuous -- and these are the exact shapes of the bugs that
# have shipped from this file.
#
# Every mutation derives its *target* from the current plugin.xml at run time
# (`group-id="..."`, `<action id="..."`, `class="dev.vela.plugin..."`, the
# run-configuration producer's implementation).  The menus are expected to change
# shape: an earlier version of this script named `Vela.Menu`, `Vela.Check` and
# `Vela.BuildAndRun` literally, and after those were deleted it would have been
# testing nothing while still printing "CAUGHT".  A case whose target has
# disappeared reports VOID instead, and the run starts by requiring the
# *unmutated* jar to PASS, so a mutation result can never be an artefact of a
# stale `build\classes`.
#
# Run it after changing VerifyPlugin, or after changing the shape of plugin.xml:
#     powershell -ExecutionPolicy Bypass -File build\tools\negative-tests.ps1
$ErrorActionPreference = 'Continue'
$plugin = 'C:\Users\lu\Downloads\vela\idea-plugin'
$repo   = 'C:\Users\lu\Downloads\vela'
$ide    = 'D:\JetBrains\IntelliJ IDEA 2026.2.1'
$java   = Join-Path $ide 'jbr\bin\java.exe'
$tools  = Join-Path $plugin 'build\tools\classes'
$cp     = Join-Path $plugin 'build\args\platform-classpath.txt'
$resSrc = Join-Path $plugin 'src\main\resources'
$classes= Join-Path $plugin 'build\classes'
$scratch= Join-Path $plugin 'build\verify\negative'

if (-not (Test-Path -LiteralPath (Join-Path $classes 'dev\vela\plugin'))) {
    Write-Host 'build\classes is empty - run build-offline.ps1 first' -ForegroundColor Red
    exit 2
}
if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

$xmlPath = Join-Path $resSrc 'META-INF\plugin.xml'
$raw     = Get-Content -LiteralPath $xmlPath -Raw
# Mutate the document *without comments*.  plugin.xml documents the bugs it once
# had, in prose that quotes the very attributes these mutations target
# (`add-to-group group-id="Vela.BuildAndRun"` still appears in the header comment);
# a raw-text scan would patch the comment, the jar would stay correct, and the case
# would silently stop testing anything.
$xml = [regex]::Replace($raw, '(?s)<!--.*?-->', '')

function First-Match([string] $text, [string] $pattern) {
    $m = [regex]::Match($text, $pattern)
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# the targets every mutation is built from, taken from the file as it stands now
$targetGroupId  = First-Match $xml 'group-id="([^"]+)"'
$targetActionId = First-Match $xml '<action\s+id="([^"]+)"'
$targetClass    = First-Match $xml '\bclass="(dev\.vela\.plugin\.[A-Za-z0-9_.]+)"'
$targetProducer = First-Match $xml '<runConfigurationProducer\s+implementation="([^"]+)"'

Write-Host "plugin.xml targets: group-id=$targetGroupId  action-id=$targetActionId  class=$targetClass  producer=$targetProducer"
Write-Host ''

function New-MutatedJar([string] $name, [string] $patched) {
    $dir = Join-Path $scratch $name
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'META-INF') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'icons') | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dir 'META-INF\plugin.xml'), $patched, (New-Object System.Text.UTF8Encoding($false)))
    Copy-Item -LiteralPath (Join-Path $resSrc 'META-INF\pluginIcon.svg') -Destination (Join-Path $dir 'META-INF\pluginIcon.svg') -Force
    Copy-Item -LiteralPath (Join-Path $resSrc 'icons\vela.svg') -Destination (Join-Path $dir 'icons\vela.svg') -Force
    $jar = Join-Path $scratch "$name.jar"
    & $java -cp $tools JarTool create $jar --root $classes --root $dir | Out-Null
    return $jar
}

function Invoke-Verifier([string] $jar, [string] $log) {
    & $java -cp $tools VerifyPlugin $jar $cp $repo *>&1 | Tee-Object -FilePath $log | Out-Null
    return $LASTEXITCODE
}

# ---- baseline: the unmutated jar must pass, or no mutation result means anything
$baseJar = New-MutatedJar 'baseline' $xml
$baseLog = Join-Path $scratch 'baseline.log'
$null = Invoke-Verifier $baseJar $baseLog
$basePass = @(Select-String -LiteralPath $baseLog -Pattern '^RESULT: PASS' -ErrorAction SilentlyContinue).Count -eq 1
if (-not $basePass) {
    Write-Host 'BASELINE FAILED - the unmutated jar does not pass, so the mutations below prove nothing.' -ForegroundColor Red
    Write-Host "see $baseLog (usually: build\classes is stale, or a class plugin.xml names is not compiled yet)"
    exit 2
}
Write-Host 'baseline: PASS (the unmutated jar agrees with the verifier)'
Write-Host ''

$cases = @(
    @{ name = 'A_fileType_vela_only'; desc = 'the 0.1.0 file type: extensions="vela" only'
       patch = { param($t) if ($t -notlike '*extensions="vel;vela"*') { return $t }
                          return $t.Replace('extensions="vel;vela"', 'extensions="vela"') }
       expect = 'does not claim "vel"' },
    @{ name = 'B_action_named_as_group'; desc = 'the 0.1.0 add-to-group: an <action id> used as a group-id'
       patch = { param($t) if (-not $targetGroupId -or -not $targetActionId) { return $t }
                          return $t.Replace("group-id=`"$targetGroupId`"", "group-id=`"$targetActionId`"") }
       expect = 'names an <action>' },
    @{ name = 'C_unknown_group'; desc = 'a group id nobody defines'
       patch = { param($t) if (-not $targetGroupId) { return $t }
                          return $t.Replace("group-id=`"$targetGroupId`"", 'group-id="Vela.NoSuchGroup.Anywhere"') }
       expect = 'not an action or group id the installed platform defines' },
    @{ name = 'D_missing_class'; desc = 'a class named by plugin.xml that is not in the jar'
       patch = { param($t) if (-not $targetClass) { return $t }
                          return $t.Replace("class=`"$targetClass`"", "class=`"$($targetClass)Typo`"") }
       expect = 'NO CLASS FILE' },
    @{ name = 'E_bad_extension_point'; desc = 'an extension point id no descriptor declares'
       patch = { param($t) if ($t -notlike '*<lang.parserDefinition*') { return $t }
                          return $t.Replace('<lang.parserDefinition', '<parserDefinition') }
       expect = 'silent no-op' },
    @{ name = 'F_bad_language'; desc = 'a language id the plugin does not register'
       patch = { param($t) if ($t -notlike '*language="Vela"*') { return $t }
                          return $t.Replace('language="Vela"', 'language="Vella"') }
       expect = 'is not the language this plugin registers' },
    @{ name = 'G_wrong_type_for_ep'; desc = 'the producer registered under an existing class of the wrong type'
       patch = { param($t) if (-not $targetProducer -or -not $targetClass) { return $t }
                          return $t.Replace("implementation=`"$targetProducer`"", "implementation=`"$targetClass`"") }
       expect = 'is NOT a ' },
    @{ name = 'H_unknown_producer_ep'; desc = 'the producer registered under an extension point id nobody declares'
       patch = { param($t) if ($t -notlike '*<runConfigurationProducer*') { return $t }
                          return $t.Replace('<runConfigurationProducer', '<runConfigurationProducerUnknown') }
       expect = 'silent no-op' },
    # --- the three registrations that arrived unregistered, plus the attribute no-op ---
    @{ name = 'I1_annotator_wrong_type'; desc = 'an annotator registered against a class that is not an Annotator'
       patch = { param($t) if ($t -notlike '*<annotator*' -or -not $targetClass) { return $t }
                          return [regex]::Replace($t, '(<annotator\b[^>]*implementationClass=")[^"]*"', "`${1}$targetClass`"") }
       expect = 'is NOT a ' },
    @{ name = 'I2_unknown_goto_ep'; desc = 'the go-to-declaration tag renamed to an id nobody declares'
       patch = { param($t) if ($t -notlike '*<gotoDeclarationHandler*') { return $t }
                          return $t.Replace('<gotoDeclarationHandler', '<gotoDeclarationHandlerUnknown') }
       expect = 'silent no-op' },
    @{ name = 'I3_reference_contributor_attribute'; desc = 'the silent no-op: the class named under an attribute the platform does not bind'
       patch = { param($t) if ($t -notlike '*psi.referenceContributor*') { return $t }
                          return [regex]::Replace($t, '(<psi\.referenceContributor\b[^>]*?)\simplementation="', '$1 implementationClass="') }
       expect = 'check registrationAttributes' }
)

$summary = New-Object System.Collections.Generic.List[string]
foreach ($case in $cases) {
    $patched = & $case.patch $xml
    if ($patched -eq $xml) { $summary.Add("  VOID    :: $($case.desc)  (target no longer exists in plugin.xml)"); continue }
    $jarPath = New-MutatedJar $case.name $patched
    $log = Join-Path $scratch "$($case.name).log"
    $code = Invoke-Verifier $jarPath $log
    $hit = @(Select-String -LiteralPath $log -Pattern $case.expect -SimpleMatch -ErrorAction SilentlyContinue).Count
    $failed = @(Select-String -LiteralPath $log -Pattern '^RESULT: FAIL' -ErrorAction SilentlyContinue).Count
    $ok = ($code -ne 0) -and ($hit -gt 0) -and ($failed -eq 1)
    $summary.Add(("  {0}  exit={1}  matched='{2}'x{3}  verdict={4}  ::  {5}" -f `
        $(if ($ok) { 'CAUGHT ' } else { 'MISSED ' }), $code, $case.expect, $hit, `
        $(if ($failed -eq 1) { 'FAIL' } else { 'no FAIL' }), $case.desc))
}
$summary | ForEach-Object { Write-Host $_ }

# ---- I4: the header-rename mutation.  It cannot use the generic loop, because it
# mutates the *tree* the compiler checks read (runtime/vela_runtime.h) rather than
# plugin.xml, so it builds a scratch repo root out of junctions to selfhost\ and
# examples\ plus a real copy of runtime\ with one symbol renamed.  That is the drift
# that made every program in the repository unlinkable: the emitter called
# vela_bounds_check and the header had stopped defining it.
$mutRepo = Join-Path $scratch 'mutated-repo'
try {
    if (Test-Path -LiteralPath $mutRepo) { Remove-Item -LiteralPath $mutRepo -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $mutRepo | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $mutRepo 'selfhost') -Target (Join-Path $repo 'selfhost') -ErrorAction Stop | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $mutRepo 'examples') -Target (Join-Path $repo 'examples') -ErrorAction Stop | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'runtime') -Destination (Join-Path $mutRepo 'runtime') -Recurse -Force
    $hPath = Join-Path $mutRepo 'runtime\vela_runtime.h'
    $hText = Get-Content -LiteralPath $hPath -Raw
    $renamed = $hText.Replace('vela_bounds_check', 'vela_bounds_check_renamed')
    if ($renamed -eq $hText) {
        $summary.Add('  VOID    :: I4_header_rename_mutation  (vela_runtime.h does not name vela_bounds_check any more)')
    } else {
        [System.IO.File]::WriteAllText($hPath, $renamed, (New-Object System.Text.UTF8Encoding($false)))
        $log4 = Join-Path $scratch 'I4_header_rename.log'
        & $java -cp $tools VerifyPlugin $baseJar $cp $mutRepo *>&1 | Tee-Object -FilePath $log4 | Out-Null
        $code4 = $LASTEXITCODE
        $hit4 = @(Select-String -LiteralPath $log4 -Pattern 'check emitterHeaderContract' -SimpleMatch -ErrorAction SilentlyContinue).Count
        $ok4 = ($code4 -ne 0) -and ($hit4 -gt 0)
        $summary.Add(("  {0}  exit={1}  matched='check emitterHeaderContract'x{2}  verdict={3}  ::  {4}" -f `
            $(if ($ok4) { 'CAUGHT ' } else { 'MISSED ' }), $code4, $hit4, `
            $(if ($code4 -ne 0) { 'FAIL' } else { 'no FAIL' }), `
            'the emitter/header contract: vela_bounds_check renamed in runtime/vela_runtime.h'))
    }
} catch {
    $summary.Add("  VOID    :: I4_header_rename_mutation  (could not build the mutated repo root: $_)")
}
$summary | Select-Object -Last 1 | ForEach-Object { Write-Host $_ }
Write-Host ''
$missed = @($summary | Where-Object { $_ -like '*MISSED*' }).Count
$void   = @($summary | Where-Object { $_ -like '*VOID*' }).Count
$total  = $cases.Count + 1
Write-Host "negative tests: $($total - $missed - $void)/$total caught; $missed missed; $void void"
if ($missed -gt 0) { exit 1 }
