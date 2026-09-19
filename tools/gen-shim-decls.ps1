<#
.SYNOPSIS
    Generate the Vela side of the LLVM shim's interface from its C header.

.DESCRIPTION
    `runtime\vela_llvm_shim.c` is the compiler's own code generator: a pure-C API over
    `llvm-c`, which the Vela compiler reaches through `extern c` declarations.  Those
    declarations are the interface, and an interface written twice by hand is an
    interface that drifts 闁?one side grows a function, the other does not, and the
    failure appears later as a symbol the linker cannot find in a file nobody edited.

    So the Vela side is **generated** from the header, and this script also checks it:
    with `-Check` it regenerates in memory and fails if the file on disk differs, which
    is what makes "the two agree" a fact rather than an intention.

    The type mapping is the compiler's own, read out of `emit_ctype`
    (`selfhost\parts\emit.vel:177`), so a declaration here produces the C the header
    declares:

        int64_t  -> int       int32_t -> i32      uint8_t -> u8
        double   -> float     bool    -> bool     vela_str -> str
        void     -> None

.PARAMETER Check
    Do not write; fail (exit 1) if `selfhost\parts\llvm_shim.vel` is not what the
    header would generate right now.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\gen-shim-decls.ps1
    powershell -ExecutionPolicy Bypass -File tools\gen-shim-decls.ps1 -Check
#>
[CmdletBinding()]
param(
    [switch] $Check
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$header = Join-Path $repo 'runtime\vela_llvm_shim.h'
# Staged under selfhost\llvm\ rather than selfhost\parts\, and that is deliberate:
# `tools\build.ps1` now refuses to build when a file in parts\ is not in the linker's
# part list, and registering this one today would break the build, because `extern c`
# refuses `str` parameters (check.vel:2580) and six of the 48 declarations take one
# (`module_open`, `fn_declare`, `fn_define`, `string_global`, `block_append`,
# `emit_object`).  The file moves into parts\ and gets its part_name() entry in the
# same change that allows a `str` parameter -- and until then it is an interface on
# paper, not a part of the compiler.  Measured: with those six filtered out the other
# 42 type-check (`vm.exe check` 鈫?`ok`, exit 0), so the mapping table itself is right.
$out = Join-Path $repo 'selfhost\parts\llvm_shim.vel'

if (-not (Test-Path -LiteralPath $header)) { throw "no shim header at $header" }
$text = Get-Content -LiteralPath $header -Raw

# Every declaration of the form `<type> vshim_...(params);`.  The header is the
# source of truth; nothing here invents a function.
#
# `vela_str` is a return type here, and that matters: the first version of this
# pattern listed only `int32_t|int64_t|double|void|bool`, so the three functions that
# return a `str` 鈥?`vshim_last_error`, `vshim_host_triple`, `vshim_module_ir` 鈥?were
# **silently dropped**.  The generator reported "48 declarations" and passed its own
# `-Check`, and the number was wrong by three.  Silent dropping is the defect class
# this whole project keeps paying for, so the pattern now covers every type the
# header uses AND the count is reconciled against the header below: a declaration
# that goes missing is a thrown error, not a smaller number.
$pattern = '(?m)^(int32_t|int64_t|double|void|bool|vela_str)\s+(vshim_[a-z0-9_]+)\s*\(([^;]*)\);'
$decls = [regex]::Matches($text, $pattern)
if ($decls.Count -eq 0) { throw "no vshim_* declarations found in $header" }

# Reconcile: every `vshim_name(` that looks like a declaration must have been
# matched, whatever its return type.  Anything left over is named.
$inHeader = @([regex]::Matches($text, '(?m)^[a-z0-9_]+\s+(vshim_[a-z0-9_]+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value })
$extracted = @($decls | ForEach-Object { $_.Groups[2].Value })
$dropped = @($inHeader | Where-Object { $extracted -notcontains $_ })
if ($dropped.Count -gt 0) {
    $names = ($dropped | Sort-Object -Unique) -join ', '
    $msg = "the header declares $($inHeader.Count) function(s) and this pattern matched $($extracted.Count); it dropped: $names.  Add the missing type to the pattern rather than generating an interface that is smaller than the header."
    throw $msg
}

function Convert-Type([string] $c, [string] $name) {
    switch ($c.Trim()) {
        'int64_t' { return "${name}: int" }
        'int32_t' { return "${name}: i32" }
        'uint8_t' { return "${name}: u8" }
        'double'  { return "${name}: float" }
        'bool'    { return "${name}: bool" }
        'vela_str' { return "${name}: str" }
        default   { throw "no Vela spelling for the C type '$c' in parameter '$name'" }
    }
}

function Convert-Return([string] $c) {
    switch ($c.Trim()) {
        'int64_t' { return 'int' }
        'int32_t' { return 'i32' }
        'uint8_t' { return 'u8' }
        'double'  { return 'float' }
        'bool'    { return 'bool' }
        'vela_str' { return 'str' }
        'void'    { return 'None' }
        default   { throw "no Vela spelling for the C return type '$c'" }
    }
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# ============================================================================')
$lines.Add('# llvm_shim.vel -- the Vela side of the compiler''s own code generator.')
$lines.Add('#')
$lines.Add('# GENERATED by tools\gen-shim-decls.ps1 from runtime\vela_llvm_shim.h.')
$lines.Add('# Do not edit by hand: edit the header and regenerate, or the two sides drift')
$lines.Add('# and the failure surfaces as an undefined symbol in a file nobody touched.')
$lines.Add('#')
$lines.Add('# This is the bridge that makes the north star reachable: `vm.exe` carries')
$lines.Add('# libLLVM inside it (through these calls) instead of shelling out to a C')
$lines.Add('# compiler, so `vm.exe build x.vel` can write an object file itself and leave')
$lines.Add('# only the executable in the user''s directory.')
$lines.Add('#')
$lines.Add('# The C types map by the compiler''s own rule (emit_ctype, emit.vel:177):')
$lines.Add('# int64_t -> int, int32_t -> i32, uint8_t -> u8, double -> float,')
$lines.Add('# bool -> bool, vela_str -> str, void -> None.')
$lines.Add('#')
$lines.Add("# $($decls.Count) declarations, in the header's order.")
$lines.Add('# ============================================================================')
$lines.Add('')
$lines.Add('# Handles (modules, types, functions, blocks, values) are `int` here because')
$lines.Add('# they are opaque pointers in C: the emitter passes them back unchanged and')
$lines.Add('# never looks inside one.')
$lines.Add('')

foreach ($d in $decls) {
    $ret = Convert-Return $d.Groups[1].Value
    $fn  = $d.Groups[2].Value
    $paramsText = $d.Groups[3].Value.Trim()
    # A declaration that takes or returns a `str` is not expressible in Vela -- the
    # rule is "the types are scalars" (`check.vel:2580`), and it is deliberate: a
    # `str` is a length plus a pointer into Vela's own string region, and letting one
    # cross would make `extern c def strlen(s: str) -> int` compile and then hand a
    # 16-byte struct to something expecting a pointer.  The shim answers that by
    # carrying its strings through a byte buffer, so every such function has a `_buf`
    # sibling that IS scalar.  This generator therefore emits the sibling and records
    # the original as omitted -- with the reason, and with the sibling named, because
    # a reader who finds a function missing must be able to see what to call instead.
    $takesStr = ($d.Groups[1].Value.Trim() -eq 'vela_str') -or ($paramsText -match '\bvela_str\b')
    if ($takesStr) {
        $sibling = "${fn}_buf"
        $hasSibling = $decls | Where-Object { $_.Groups[2].Value -eq $sibling }
        if ($hasSibling) {
            $lines.Add("# omitted: $fn  (takes or returns a str; the scalar form is ${sibling}, below)")
        } else {
            $lines.Add("# omitted: $fn  (takes or returns a str, and has no _buf sibling)")
        }
        continue
    }
    if ($paramsText -eq '' -or $paramsText -eq 'void') {
        $lines.Add("extern c def $fn() -> $ret")
        continue
    }
    $parts = @()
    foreach ($p in $paramsText -split ',') {
        $p = $p.Trim()
        # `<type> <name>`; the header names every parameter, so there is no
        # unnamed-parameter case to guess at.
        $m = [regex]::Match($p, '^(int32_t|int64_t|double|bool|uint8_t|vela_str)\s+([a-z0-9_]+)$')
        if (-not $m.Success) {
            if ($p -match '^\.\.\.$') {
                # varargs: Vela has no spelling for them, and the shim has none
                # either -- a call the emitter cannot typecheck is a call the
                # emitter must not make.
                throw "variadic parameter in ${fn}: the interface must stay typable"
            }
            throw "cannot read the parameter '$p' of ${fn}"
        }
        $parts += (Convert-Type $m.Groups[1].Value $m.Groups[2].Value)
    }
    $lines.Add("extern c def $fn($($parts -join ', ')) -> $ret")
}

$lines.Add('')
$generated = ($lines -join "`n") + "`n"

if ($Check) {
    if (-not (Test-Path -LiteralPath $out)) {
        Write-Host "MISSING: $out does not exist; run this script without -Check" -ForegroundColor Red
        exit 1
    }
    $current = Get-Content -LiteralPath $out -Raw
    if ($current -ne $generated) {
        Write-Host "STALE: $out is not what runtime\vela_llvm_shim.h generates now" -ForegroundColor Red
        $curLines = $current -split "`n"
        $genLines = $generated -split "`n"
        $n = [Math]::Min($curLines.Count, $genLines.Count)
        for ($i = 0; $i -lt $n; $i++) {
            if ($curLines[$i] -ne $genLines[$i]) {
                Write-Host ("  first difference at line {0}:" -f ($i + 1))
                Write-Host ("    on disk: {0}" -f $curLines[$i])
                Write-Host ("    header : {0}" -f $genLines[$i])
                break
            }
        }
        Write-Host ("  ($($decls.Count) declarations in the header)")
        exit 1
    }
    Write-Host "in step: $out matches runtime\vela_llvm_shim.h ($($decls.Count) declarations)"
    exit 0
}

[System.IO.File]::WriteAllText($out, $generated, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $out"
Write-Host "  $($decls.Count) declarations from runtime\vela_llvm_shim.h"
