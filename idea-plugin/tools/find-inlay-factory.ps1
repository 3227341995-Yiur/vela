# Find every class on the build classpath whose constant pool mentions
# InlayPresentationFactory, so the *implementation* of that interface (which is
# what a provider needs an instance of) can be located instead of guessed.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$cpFile = 'C:\Users\lu\Downloads\vela\idea-plugin\build\args\platform-classpath.txt'
$jars = New-Object System.Collections.Generic.List[string]
foreach ($entry in ((Get-Content $cpFile -Raw).Trim() -split ';')) {
    if ($entry -and (Test-Path -LiteralPath $entry) -and $entry -like '*.jar') { $jars.Add($entry) }
}
$needle = 'InlayPresentationFactory'
$found = New-Object System.Collections.Generic.List[string]
foreach ($jar in $jars) {
    try { $z = [System.IO.Compression.ZipFile]::OpenRead($jar) } catch { continue }
    foreach ($e in $z.Entries) {
        if ($e.FullName -notmatch '\.class$') { continue }
        if ($e.Length -gt 500000) { continue }
        $ms = New-Object System.IO.MemoryStream
        $s = $e.Open(); $s.CopyTo($ms); $s.Close()
        $b = $ms.ToArray(); $ms.Dispose()
        $ascii = [System.Text.Encoding]::ASCII.GetString($b)
        if ($ascii.Contains($needle)) {
            $found.Add("$jar :: $($e.FullName)")
        }
    }
    $z.Dispose()
}
$found | Sort-Object
Write-Output "scan: $($jars.Count) jar(s), $($found.Count) class(es) mention $needle"
