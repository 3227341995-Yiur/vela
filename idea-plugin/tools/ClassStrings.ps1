# Print every long UTF-8 constant in a class's constant pool.  Class/field/method
# *generic* signatures are ordinary Utf8 constants here, so a "Signature" value
# shows up as a string that starts with '<' or contains '(' and 'L'.
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
$count=([int]$bytes[8] -shl 8) -bor [int]$bytes[9]
$pos=10; $i=1
while($i -lt $count){
  $tag=[int]$bytes[$pos]; $pos++
  switch($tag){
    1 { $len=([int]$bytes[$pos] -shl 8) -bor [int]$bytes[$pos+1]; $pos+=2
        Write-Output ([System.Text.Encoding]::UTF8.GetString($bytes,$pos,$len)); $pos+=$len }
    7 { $pos+=2 } 8 { $pos+=2 }
    3 { $pos+=4 } 4 { $pos+=4 } 5 { $pos+=8; $i++ } 6 { $pos+=8; $i++ }
    9 { $pos+=4 } 10 { $pos+=4 } 11 { $pos+=4 } 12 { $pos+=4 } 17 { $pos+=4 } 18 { $pos+=4 }
    15 { $pos+=3 } 16 { $pos+=2 } 19 { $pos+=2 } 20 { $pos+=2 }
    default { throw "bad tag $tag" }
  }
  $i++
}
