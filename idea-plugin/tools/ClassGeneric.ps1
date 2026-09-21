# Print the full generic signature of a class plus its supertypes, using the
# Signature attribute (raw generic type arguments), and the *type* of every
# abstract/inherited member.  Complements ClassDump.ps1: this one prints the
# class-level generic signature that tells us `InlayHintsProvider<T>`.
param(
  [Parameter(Mandatory=$true)][string]$Jar,
  [Parameter(Mandatory=$true)][string]$Entry
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead($Jar)
$e=$z.Entries | Where-Object { $_.FullName -eq $Entry }
if(-not $e){ $z.Dispose(); throw "no entry $Entry" }
$ms=New-Object System.IO.MemoryStream
$st=$e.Open(); $st.CopyTo($ms); $st.Close(); $z.Dispose()
$bytes=$ms.ToArray(); $ms.Dispose()

# Constant pool parse (read-only, no script-scope during pipeline use).
$cpU=@(); $cpC=@()
$pos=8
$count=([int]$bytes[8] -shl 8) -bor [int]$bytes[9]
$pos=10
$i=1
while($i -lt $count){
  $tag=$bytes[$pos]; $pos++
  switch($tag){
    1 { $len=([int]$bytes[$pos] -shl 8) -bor [int]$bytes[$pos+1]; $pos+=2
        $cpU += ,@($i,[System.Text.Encoding]::UTF8.GetString($bytes,$pos,$len)); $pos+=$len }
    7 { $cpC += ,@($i,(([int]$bytes[$pos] -shl 8) -bor [int]$bytes[$pos+1])); $pos+=2 }
    8 { $cpU += ,@($i,""); $pos+=2 }
    3 { $pos+=4 } 4 { $pos+=4 } 5 { $pos+=8; $i++ } 6 { $pos+=8; $i++ }
    9 { $pos+=4 } 10 { $pos+=4 } 11 { $pos+=4 } 12 { $pos+=4 } 17 { $pos+=4 } 18 { $pos+=4 }
    15 { $pos+=3 } 16 { $pos+=2 } 19 { $pos+=2 } 20 { $pos+=2 }
    default { throw "bad tag $tag" }
  }
  $i++
}
function U2 { $r=([int]$bytes[$pos] -shl 8) -bor [int]$bytes[$pos+1]; $script:pos+=2; return $r }
function U4 { $r=([int]$bytes[$pos] -shl 24) -bor ([int]$bytes[$pos+1] -shl 16) -bor ([int]$bytes[$pos+2] -shl 8) -bor [int]$bytes[$pos+3]; $script:pos+=4; return $r }
$utf=@{}; foreach($p in $cpU){ $utf[$p[0]]=$p[1] }
$cls=@{}; foreach($p in $cpC){ $cls[$p[0]]=$p[1] }

$pos=8; U4|Out-Null; U2|Out-Null; U2|Out-Null; U2|Out-Null
$acc=U2; $thisC=(U2); $superC=(U2)
$attrs=@()
Write-Output "THIS   $($utf[$cls[$thisC]])  access=$acc"
if($superC -ne 0){ Write-Output "SUPER  $($utf[$cls[$superC]])" }
$ic=U2
for($k=0;$k -lt $ic;$k++){ Write-Output "IFACE  $($utf[$cls[(U2)]])" }
# class attributes include the generic Signature
$fc=U2
for($k=0;$k -lt $fc;$k++){ U2|Out-Null; U2|Out-Null; $a=U2; for($j=0;$j -lt $a;$j++){ U2|Out-Null; $len=U4; $pos+=$len } }
$mc=U2
for($k=0;$k -lt $mc;$k++){ U2|Out-Null; U2|Out-Null; U2|Out-Null; $a=U2; for($j=0;$j -lt $a;$j++){ U2|Out-Null; $len=U4; $pos+=$len } }
$ac=U2
for($j=0;$j -lt $ac;$j++){
  $an=$utf[(U2)]; $len=U4
  if($an -eq 'Signature'){ Write-Output "CLASS SIGNATURE = $($utf[(U2)])" }
  else { $pos+=$len }
}
