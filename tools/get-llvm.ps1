<#
.SYNOPSIS
    Fetch a portable LLVM/Clang for the Vela native backend, without an installer.

.DESCRIPTION
    The Vela native backend (see `selfhost/LLVM_PLAN.md`) needs `clang-cl` and, for
    the in-process phase, `libLLVM`.  Neither is on this machine: Visual Studio
    2022 Build Tools' `VC\Tools\Llvm\` holds only `clang-format` and `clang-tidy`,
    `vswhere -requires Microsoft.VisualStudio.Component.VC.Llvm.Clang` returns
    empty, and `C:\Program Files\LLVM` does not exist.  Installing the component
    through the Visual Studio Installer needs administrator rights; a Vela
    checkout on a machine where that is inconvenient should not be blocked on it.

    So this script fetches the official **portable** Windows archive and unpacks it
    beside the checkout.  No installer, no registry, no PATH change: the paths it
    prints are the ones to hand to the build.

.PARAMETER Version
    The LLVM release, e.g. 23.1.1.  Defaults to the version this project measured.

.PARAMETER Dir
    Where to unpack.  Defaults to a `llvm` directory next to the checkout —
    deliberately **not** inside it: a toolchain is not source and does not belong in
    this repository, and on this host the DSH file sandbox only allows writing
    inside the session workspace anyway (a sibling of the checkout is inside it;
    `C:\Users\lu\llvm` is not, and that attempt failed with access denied).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\get-llvm.ps1
    powershell -ExecutionPolicy Bypass -File tools\get-llvm.ps1 -Version 23.1.1 -Dir D:\llvm

.NOTES
    Two environment facts this script works around, both measured on 2026-09-19:

      * **`curl.exe` cannot be used here.** Its schannel backend fails with
        `SEC_E_NO_CREDENTIALS` because this sandbox intercepts TLS, and .NET's
        `Invoke-WebRequest` fails the same way (`基础连接已经关闭`).  Node's
        OpenSSL stack works, but only with `--use-system-ca`, which makes it trust
        the system store where the interception's root certificate lives.  So the
        download is done by `node --use-system-ca`, with `curl` tried first because
        on an ordinary machine curl is the right tool.

      * **A long download can be killed by the harness.**  If this host's Windows
        job runner wedges (`Windows Job runner exited with exit code 1 before
        proving its managed range empty`), run this script from a plain terminal
        instead; the compiler driver's `reap_helpers` and `tools\build.ps1`'s
        cleanup exist for the same reason.
#>
[CmdletBinding()]
param(
    [string] $Version = '23.1.1',
    [string] $Dir,
    [switch] $KeepArchive
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Dir) { $Dir = Join-Path (Split-Path -Parent $repo) 'llvm' }

$asset = "clang+llvm-$Version-x86_64-pc-windows-msvc.tar.zst"
$url = "https://github.com/llvm/llvm-project/releases/download/llvmorg-$Version/$asset"
$archive = Join-Path $Dir $asset

Write-Host "Vela: fetching LLVM $Version"
Write-Host "  from $url"
Write-Host "  into $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

# ------------------------------------------------------------------ 1. download
if (Test-Path -LiteralPath $archive) {
    Write-Host "  archive already present: $((Get-Item $archive).Length / 1MB -as [int]) MB"
} else {
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    $ok = $false
    if ($curl) {
        Write-Host '  trying curl.exe'
        & curl.exe -fL --retry 3 --max-time 3600 -o $archive $url
        $ok = ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $archive))
    }
    if (-not $ok) {
        # node --use-system-ca: see .NOTES.  Written to a file rather than passed
        # with -e because the script is long enough that quoting it would be the
        # only interesting thing about it.
        $node = Get-Command node.exe -ErrorAction SilentlyContinue
        if (-not $node) { throw "neither curl.exe nor node.exe worked, and there is no other HTTPS client here" }
        Write-Host '  falling back to node --use-system-ca'
        $js = Join-Path $Dir 'download-llvm.js'
        @'
const https = require('https'), fs = require('fs');
const url = process.argv[2], out = process.argv[3];
function get(u, depth) {
  if (depth > 8) { console.log('too many redirects'); process.exit(1); }
  https.get(u, { headers: { 'user-agent': 'vela-get-llvm' } }, res => {
    if ([301, 302, 303, 307, 308].includes(res.statusCode)) { res.resume(); return get(res.headers.location, depth + 1); }
    if (res.statusCode !== 200) { console.log('HTTP ' + res.statusCode); process.exit(1); }
    const total = Number(res.headers['content-length'] || 0);
    let got = 0, last = 0;
    const f = fs.createWriteStream(out);
    res.on('data', c => { got += c.length; if (Date.now() - last > 15000) { last = Date.now(); console.log('    ' + (got / 1048576).toFixed(0) + ' / ' + (total / 1048576).toFixed(0) + ' MB'); } });
    res.pipe(f);
    f.on('finish', () => { console.log('    DONE ' + got + ' bytes'); process.exit(0); });
  }).on('error', e => { console.log('    ERR ' + e.message); process.exit(1); });
}
get(url, 0);
'@ | Set-Content -LiteralPath $js -Encoding utf8
        & node.exe --use-system-ca $js $url $archive
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archive)) { throw "download failed: $url" }
    }
}

# ------------------------------------------------------------------ 2. unpack
# `tar.exe` on Windows is bsdtar with zstd support in recent builds; where it is
# not, node's zstd decoder produces the plain `.tar` and tar takes it from there.
$bin = Join-Path $Dir "clang+llvm-$Version-x86_64-pc-windows-msvc\bin\clang-cl.exe"
if (Test-Path -LiteralPath $bin) {
    Write-Host "  already unpacked"
} else {
    Write-Host '  unpacking'
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    $done = $false
    if ($tar) {
        & tar.exe -xf $archive -C $Dir
        $done = (Test-Path -LiteralPath $bin)
    }
    if (-not $done) {
        Write-Host '  tar could not read the zstd stream; decompressing with node first'
        $tarFile = [IO.Path]::ChangeExtension($archive, '.tar')
        $js2 = Join-Path $Dir 'unzstd.js'
        @'
const fs = require('fs'), zlib = require('zlib');
const src = process.argv[2], dst = process.argv[3];
// The archive is a concatenation of zstd frames like the DSH transcripts are, and
// the one-shot API stops after the first; walk the frames by hand, which is the
// method that worked for those.
const MAGIC = 0xfd2fb528;
const buf = fs.readFileSync(src);
function frames(b) {
  const out = []; let p = 0;
  while (p + 4 <= b.length && b.readUInt32LE(p) === MAGIC) {
    out.push(p);
    const fhd = b[p + 4], fcsFlag = (fhd >> 6) & 3, single = (fhd >> 5) & 1, sum = (fhd >> 2) & 1;
    let q = p + 5; if (!single) q += 1; q += [0, 1, 2, 4][fhd & 3];
    let fcs = [0, 2, 4, 8][fcsFlag]; if (fcsFlag === 0 && single) fcs = 1; q += fcs;
    for (;;) {
      if (q + 3 > b.length) { q = b.length; break; }
      const h = b.readUInt32LE(q) & 0xffffff, last = h & 1, type = (h >> 1) & 3, size = (h >> 3) & 0x1fffff;
      q += 3;
      if (type === 0 || type === 2) q += size; else if (type === 1) q += 1; else { q = b.length; break; }
      if (last) break;
    }
    if (sum) q += 4;
    p = q;
  }
  return out;
}
const starts = frames(buf);
const fd = fs.openSync(dst, 'w');
for (let i = 0; i < starts.length; i++) {
  const end = i + 1 < starts.length ? starts[i + 1] : buf.length;
  fs.writeSync(fd, zlib.zstdDecompressSync(buf.subarray(starts[i], end)));
  if (i % 40 === 0) console.log('    frame ' + i + ' / ' + starts.length);
}
fs.closeSync(fd);
console.log('    ' + starts.length + ' frames -> ' + dst);
'@ | Set-Content -LiteralPath $js2 -Encoding utf8
        & node.exe --use-system-ca $js2 $archive $tarFile
        if ($LASTEXITCODE -ne 0) { throw 'zstd decompression failed' }
        & tar.exe -xf $tarFile -C $Dir
        Remove-Item -LiteralPath $tarFile -Force
        $done = (Test-Path -LiteralPath $bin)
    }
    if (-not $done) { throw "unpacked, but $bin is still missing" }
}

if (-not $KeepArchive) { Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue }

# ------------------------------------------------------------------ 3. verify
$clangCl = $bin
$llc = Join-Path (Split-Path $bin) 'llc.exe'
Write-Host ''
Write-Host 'Vela: LLVM is in place'
Write-Host "  clang-cl : $clangCl"
if (Test-Path -LiteralPath $llc) { Write-Host "  llc      : $llc" }
Write-Host ''
Write-Host '  version:'
& $clangCl --version 2>&1 | Select-Object -First 3 | ForEach-Object { Write-Host "    $_" }
Write-Host ''
Write-Host '  Next, per selfhost\LLVM_PLAN.md: prove the pipeline with a hand-written'
Write-Host '  .ll before writing an emitter, then make the native backend emit that shape.'
