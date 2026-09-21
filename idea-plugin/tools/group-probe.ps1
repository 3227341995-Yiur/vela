# Answers, from the installed IDE's own descriptors, which ids the new plugin.xml
# registrations name and what ProjectViewPopupMenuRunGroup actually holds.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ide = 'D:\JetBrains\IntelliJ IDEA 2026.2.1'
$ids = @('ProjectViewPopupMenu', 'NewGroup', 'ProjectViewPopupMenuRunGroup', 'EditorPopupMenu', 'RunMenu', 'Vela.Menu')

$jars = New-Object System.Collections.Generic.List[string]
foreach ($f in @(Get-ChildItem -LiteralPath "$ide\lib" -Filter '*.jar')) { $jars.Add($f.FullName) }
foreach ($p in @(Get-ChildItem -LiteralPath "$ide\plugins" -Directory)) {
    foreach ($sub in @('lib', 'lib\modules')) {
        $d = Join-Path $p.FullName $sub
        if (-not (Test-Path -LiteralPath $d)) { continue }
        foreach ($f in @(Get-ChildItem -LiteralPath $d -Filter '*.jar' -ErrorAction SilentlyContinue)) { $jars.Add($f.FullName) }
    }
}
Write-Host "jars scanned: $($jars.Count)"
Write-Host ""

$decl = @{}          # id -> list of "group|action @ where"
$addTo = @{}         # id -> list of owners
$blocks = @{}        # id -> block text (first group declaration)

foreach ($j in $jars) {
    try { $z = [System.IO.Compression.ZipFile]::OpenRead($j) } catch { continue }
    foreach ($e in $z.Entries) {
        if (-not $e.FullName.EndsWith('.xml')) { continue }
        if ($e.Length -gt 1500000) { continue }
        try { $sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8); $t = $sr.ReadToEnd(); $sr.Dispose() } catch { continue }
        if ($t -notlike '*ProjectViewPopupMenu*' -and $t -notlike '*NewGroup*') { continue }
        $where = "$([System.IO.Path]::GetFileName($j))!$($e.FullName)"

        foreach ($id in $ids) {
            foreach ($kind in @('group', 'action')) {
                if ($t.Contains("<$kind id=`"$id`"")) {
                    if (-not $decl.ContainsKey($id)) { $decl[$id] = New-Object System.Collections.Generic.List[string] }
                    $decl[$id].Add("$kind @ $where")
                }
            }
            # who adds something to it
            $needle = "group-id=`"$id`""
            if ($t.Contains($needle)) {
                foreach ($m in [regex]::Matches($t, [regex]::Escape($needle))) {
                    $before = $t.Substring(0, $m.Index)
                    $owner = '?'
                    $la = $before.LastIndexOf('<action ')
                    $lg = $before.LastIndexOf('<group ')
                    $idx = [Math]::Max($la, $lg)
                    if ($idx -ge 0) {
                        $tag = $before.Substring($idx, [Math]::Min(400, $before.Length - $idx))
                        $om = [regex]::Match($tag, 'id="([^"]+)"')
                        if ($om.Success) { $owner = $om.Groups[1].Value }
                    }
                    if (-not $addTo.ContainsKey($id)) { $addTo[$id] = New-Object System.Collections.Generic.List[string] }
                    $addTo[$id].Add("$owner @ $where")
                }
            }
        }

        # the full declaration block of ProjectViewPopupMenuRunGroup, once
        if (-not $blocks.ContainsKey('ProjectViewPopupMenuRunGroup')) {
            $i = $t.IndexOf('<group id="ProjectViewPopupMenuRunGroup"')
            if ($i -ge 0) {
                $depth = 0; $k = $i; $end = $t.Length
                while ($k -lt $t.Length) {
                    if ($t.Substring($k).StartsWith('<group ')) { $depth++ }
                    elseif ($t.Substring($k).StartsWith('</group>')) { $depth--; if ($depth -eq 0) { $end = $k + 8; break } }
                    $k++
                }
                $blocks['ProjectViewPopupMenuRunGroup'] = "$where`n" + $t.Substring($i, $end - $i)
            }
        }
    }
    $z.Dispose()
}

Write-Host "=== declarations ==="
foreach ($id in $ids) {
    if ($decl.ContainsKey($id)) { Write-Host "  $id : $($decl[$id].Count) declaration(s) -> $($decl[$id][0])" }
    else { Write-Host "  $id : NO DECLARATION FOUND" }
}
Write-Host ""
Write-Host "=== who adds to each id (count, first 6) ==="
foreach ($id in $ids) {
    if ($addTo.ContainsKey($id)) {
        Write-Host "  $id : $($addTo[$id].Count) add-to-group line(s)"
        $addTo[$id] | Select-Object -First 6 | ForEach-Object { Write-Host "      <- $_" }
    } else { Write-Host "  $id : nobody adds to it" }
}
Write-Host ""
Write-Host "=== ProjectViewPopupMenuRunGroup declaration block ==="
if ($blocks.ContainsKey('ProjectViewPopupMenuRunGroup')) { Write-Host $blocks['ProjectViewPopupMenuRunGroup'] }
else { Write-Host '  not found' }
