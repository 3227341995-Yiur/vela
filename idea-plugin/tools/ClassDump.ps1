# Read a .class out of a jar and print its declared members (name + descriptor),
# plus the generic Signature attribute when present.  No javap in the JBR.
param(
  [Parameter(Mandatory=$true)][string]$Jar,
  [Parameter(Mandatory=$true)][string]$Entry
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$z=[System.IO.Compression.ZipFile]::OpenRead($Jar)
$e=$z.Entries | Where-Object { $_.FullName -eq $Entry }
if(-not $e){ $z.Dispose(); throw "no entry $Entry in $Jar" }
$ms=New-Object System.IO.MemoryStream
$st=$e.Open(); $st.CopyTo($ms); $st.Close(); $z.Dispose()
$b=$ms.ToArray()
$ms.Dispose()

$p=0
function U1 { $script:v=$script:b[$script:p]; $script:p++ ; return [int]$script:v }
function U2 { $r=([int]$script:b[$script:p] -shl 8) -bor [int]$script:b[$script:p+1]; $script:p+=2; return $r }
function U4 { $r=([int]$script:b[$script:p] -shl 24) -bor ([int]$script:b[$script:p+1] -shl 16) -bor ([int]$script:b[$script:p+2] -shl 8) -bor [int]$script:b[$script:p+3]; $script:p+=4; return $r }

$magic=U4; $minor=U2; $major=U2
$cpCount=U2
$utf=New-Object 'string[]' $cpCount
$cls=New-Object 'int[]' $cpCount
$i=1
while($i -lt $cpCount){
  $tag=U1
  switch($tag){
    1  { $len=U2; $utf[$i]=[System.Text.Encoding]::UTF8.GetString($b,$p,$len); $p+=$len }
    7  { $cls[$i]=U2 }
    8  { U2 | Out-Null }
    3  { $p+=4 } 4 { $p+=4 } 5 { $p+=8; $i++ } 6 { $p+=8; $i++ }
    9  { $p+=4 } 10 { $p+=4 } 11 { $p+=4 } 12 { $p+=4 } 17 { $p+=4 } 18 { $p+=4 }
    15 { $p+=3 } 16 { $p+=2 } 19 { $p+=2 } 20 { $p+=2 }
    default { throw "bad cp tag $tag at $p" }
  }
  $i++
}
$access=U2; $thisClass=U2; $superClass=U2
$ifCount=U2
$ifaces=@(); for($k=0;$k -lt $ifCount;$k++){ $ifaces += $utf[$cls[(U2)]] }

Write-Output "class  $($utf[$cls[$thisClass]])  (major $major)"
if($superClass -ne 0){ Write-Output "super  $($utf[$cls[$superClass]])" }
if($ifaces.Count){ Write-Output "impls  $($ifaces -join ', ')" }
Write-Output "flags  $access"

function Read-Attrs([int]$count){
  $out=@()
  for($k=0;$k -lt $count;$k++){
    $an=$utf[(U2)]; $al=U4
    $val=$null
    if($an -eq 'Signature'){ $val=$utf[(U2)] } else { $p+=$al; continue }
    $out += "$an = $val"
    if($an -eq 'Signature'){ } # consumed
  }
  return $out
}

# fields
$fc=U2
for($k=0;$k -lt $fc;$k++){ U2|Out-Null; U2|Out-Null; U2|Out-Null; $ac=U2; $a=Read-Attrs $ac }
# methods
$mc=U2
Write-Output "--- methods ($mc)"
for($k=0;$k -lt $mc;$k++){
  $af=U2; $n=$utf[(U2)]; $d=$utf[(U2)]; $ac=U2
  $sig=$null
  for($j=0;$j -lt $ac;$j++){
    $an=$utf[(U2)]; $al=U4
    if($an -eq 'Signature'){ $sig=$utf[(U2)] } else { $p+=$al }
  }
  $s = "  [$af] $n $d"
  if($sig){ $s += "   signature=$sig" }
  Write-Output $s
}
