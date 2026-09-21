# What is inside RunContextPopupGroup (the only content of ProjectViewPopupMenuRunGroup)?
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ide = 'D:\JetBrains\IntelliJ IDEA 2026.2.1'
$targets = @('RunContextPopupGroup', 'ProjectViewPopupMenuRunGroup', 'ProjectViewCompileGroup', 'RunConfigurationGroup', 'RunConfigurationsGroup')
$jars = New-Object System.Collections.Generic.List[string]
foreach ($f in @(Get-ChildItem -LiteralPath "$ide\lib" -Filter '*.jar')) { $jars.Add($f.FullName) }

$printed = @{}
foreach ($j in $jars) {
    try { $z = [System.IO.Compression.ZipFile]::OpenRead($j.FullName) } catch { continue }
    foreach ($e in $z.Entries) {
        if (-not $e.FullName.EndsWith('.xml')) { continue }
        if ($e.Length -gt 1500000) { continue }
        try { $sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8); $t = $sr.ReadToEnd(); $sr.Dispose() } catch { continue }
        foreach ($id in $targets) {
            $needle = "<group id=`"$id`""
            $i = $t.IndexOf($needle)
            if ($i -lt 0) { continue }
            $key = "$id@$($e.FullName)"
            if ($printed.ContainsKey($key)) { continue }
            $printed[$key] = 1
            # the block: from the <group to its matching </group> (self-closing groups too)
            $close = $t.IndexOf('</group>', $i)
            $selfClose = $t.IndexOf('/>', $i)
            $end = if ($close -ge 0 -and ($selfClose -lt 0 -or $close -lt $selfClose)) { $close + 8 } else { $selfClose + 2 }
            if ($end -le $i) { $end = [Math]::Min($t.Length, $i + 600) }
            $block = $t.Substring($i, [Math]::Min($end - $i, 1500))
            Write-Host "=== $id @ $([System.IO.Path]::GetFileName($j))!$($e.FullName) ==="
            Write-Host $block
            Write-Host ""
        }
    }
    $z.Dispose()
}
