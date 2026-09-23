# tools/_hold-probe.ps1 - what a rename needs, and what predicts it
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\_hold-probe.ps1
#
# A probe, not a gate (`gitignore` ignores `/tools/_*`).  It exists because two versions of
# `Test-FileRenamable` in `tools\build.ps1` were written from intuition and both were wrong
# in the direction that flatters -- each answered "the file can be renamed" for a file it
# had just failed to rename.  So the table is measured here, once, and the function's
# comment cites this file.
#
# The question is narrow and worth stating exactly: on Windows a rename needs DELETE access
# on the file, and a holder can only pass that on by including `FileShare::Delete` in its
# own open.  Which open-mode probe reports that?
#
#     - asking for delete *sharing* always succeeds, so it reports nothing;
#     - asking for write *access* also trips over a holder that shares only Read;
#     - a running executable is held with delete sharing on (that is how Windows opens an
#       image), so it is renamable, which is the case `Clear-LockedTarget` exists for.

$ErrorActionPreference = 'Continue'
$dir = Join-Path $env:TEMP 'vela-hold-probe'
Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function Try-Open([string] $path, [System.IO.FileAccess] $acc, [System.IO.FileShare] $shr) {
    try {
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, $acc, $shr)
        $fs.Close()
        return 'ok'
    } catch { return 'no' }
}

# The predicate build.ps1 uses, written here as it is written there.
function Test-FileRenamable([string] $path) {
    try {
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open,
                                     [System.IO.FileAccess]::ReadWrite,
                                     [System.IO.FileShare]::None)
        $fs.Close()
        return $true
    } catch { return $false }
}

$holders = @(
    @{ Name = 'nothing';        Share = $null },
    @{ Name = 'FileShare::None';Share = [System.IO.FileShare]::None },
    @{ Name = 'Read';           Share = [System.IO.FileShare]::Read },
    @{ Name = 'Write';          Share = [System.IO.FileShare]::Write },
    @{ Name = 'ReadWrite';      Share = [System.IO.FileShare]::ReadWrite },
    @{ Name = 'Delete only';    Share = [System.IO.FileShare]::Delete },
    @{ Name = 'ReadWrite|Delete'; Share = [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete }
)

Write-Host "Vela: what a rename needs, and what predicts it - $dir"
Write-Host ''
Write-Host ("{0,-18} {1,-9} {2,-20} {3}" -f 'the holder shares', 'a rename', 'Read, RW|Del open', 'Test-FileRenamable')

$wrong = 0
foreach ($c in $holders) {
    $target = Join-Path $dir 'x.exe'
    $aside = Join-Path $dir 'x.exe.held'
    Remove-Item -LiteralPath $target, $aside -Force -ErrorAction SilentlyContinue
    [System.IO.File]::WriteAllBytes($target, [byte[]](1, 2, 3, 4))

    $h = $null
    if ($c.Share) {
        $h = [System.IO.File]::Open($target, [System.IO.FileMode]::Open,
                                    [System.IO.FileAccess]::ReadWrite, $c.Share)
    }
    $other = Try-Open $target ([System.IO.FileAccess]::Read) ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
    $said = ''
    try {
        Move-Item -LiteralPath $target -Destination $aside -Force -ErrorAction Stop
        $said = 'moved'
        Move-Item -LiteralPath $aside -Destination $target -Force
    } catch {
        $said = 'refused'
        if (Test-Path -LiteralPath $aside) { Move-Item -LiteralPath $aside -Destination $target -Force }
    }
    $pred = Test-FileRenamable $target
    $expect = ($said -eq 'moved')
    Write-Host ("{0,-18} {1,-9} {2,-20} {3}" -f $c.Name, $said, $other, $pred)
    if ($pred -ne $expect) { $wrong++ }
    if ($h) { $h.Close() }
}

Write-Host ''
Write-Host "Test-FileRenamable disagreed with a real rename in $wrong of $($holders.Count) rows above."
Write-Host 'That is why its comment carries the table instead of a claim: it only chooses a'
Write-Host 'sentence for a failure the linker is about to report anyway.'
Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
exit 0
