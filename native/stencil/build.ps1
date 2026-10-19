param(
    [Parameter(Mandatory=$true)][string]$OcctSdk,
    [string]$VisualStudio = 'C:\ProgramFiles\30_VS',
    [string]$Output = (Join-Path $PSScriptRoot '..\..\src\main\resources\org\openpnp\stencil\native\windows-x86_64')
)
$ErrorActionPreference='Stop'
$OcctSdk=[IO.Path]::GetFullPath($OcctSdk)
$Output=[IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Path $Output -Force | Out-Null
$build=Join-Path $PSScriptRoot '..\..\.build\stencil-native'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$bat=Join-Path $build 'compile.cmd'
$libs='TKernel TKMath TKG2d TKG3d TKGeomBase TKBRep TKGeomAlgo TKTopAlgo TKPrim TKBO TKBool TKMesh TKDESTEP TKDESTL TKXSBase TKShHealing TKDE TKCDF TKLCAF TKCAF TKXCAF TKBinL TKBin TKBinXCAF'.Split(' ')
$libArgs=($libs | ForEach-Object { $_+'.lib' }) -join ' '
$lines=@('@echo off',('call "'+$VisualStudio+'\VC\Auxiliary\Build\vcvars64.bat" >nul'),('cl /nologo /O2 /EHsc /std:c++17 /MD /DNOMINMAX /D_USE_MATH_DEFINES /D_CRT_SECURE_NO_WARNINGS /I"'+$OcctSdk+'\inc" "'+$PSScriptRoot+'\stencil_cad.cpp" /Fo"'+$build+'\stencil_cad.obj" /Fe"'+$Output+'\stencil-cad.exe" /link /IMPLIB:"F:\OpenPnP\openpnp-pro-ui\.build\stencil-native\stencil-cad.lib" /LIBPATH:"'+$OcctSdk+'\win64\vc14\lib" '+$libArgs))
[IO.File]::WriteAllLines($bat,$lines,[Text.Encoding]::ASCII)
& cmd.exe /d /c $bat
if($LASTEXITCODE -ne 0){throw 'CAD worker compilation failed'}
$dumpbin=Get-ChildItem -Path (Join-Path $VisualStudio 'VC\Tools\MSVC\*\bin\Hostx64\x64\dumpbin.exe') | Sort-Object FullName -Descending | Select-Object -First 1
$sdkRoot=Split-Path $OcctSdk
$dlls=@{}
Get-ChildItem -LiteralPath $sdkRoot -Filter '*.dll' -Recurse -File | ForEach-Object {if(-not $dlls.ContainsKey($_.Name.ToLowerInvariant())){$dlls[$_.Name.ToLowerInvariant()]=$_.FullName}}
$redistributable=Join-Path $VisualStudio 'VC\Redist\MSVC'
if(Test-Path -LiteralPath $redistributable){Get-ChildItem -LiteralPath $redistributable -Filter '*.dll' -Recurse -File | Where-Object FullName -Match 'x64\\Microsoft.VC.*CRT\\' | ForEach-Object {$dlls[$_.Name.ToLowerInvariant()]=$_.FullName}}
$queue=New-Object 'Collections.Generic.Queue[string]'
$queue.Enqueue((Join-Path $Output 'stencil-cad.exe'))
$seen=@{}
while($queue.Count){$file=$queue.Dequeue();foreach($line in (& $dumpbin.FullName /dependents $file)){if($line -match '^\s+([A-Za-z0-9_.-]+\.dll)\s*$'){$name=$Matches[1].ToLowerInvariant();if(-not $seen.ContainsKey($name)){$seen[$name]=$true;if($dlls.ContainsKey($name)){$dest=Join-Path $Output $name;Copy-Item -LiteralPath $dlls[$name] -Destination $dest -Force;$queue.Enqueue($dest)}}}}}
foreach($name in @('LICENSE_LGPL_21.txt','OCCT_LGPL_EXCEPTION.txt')){Copy-Item -LiteralPath (Join-Path $OcctSdk $name) -Destination $Output -Force}
Get-ChildItem -LiteralPath $Output -Filter '*.txt' -File | ForEach-Object {
    # Hash the same LF bytes that Git archives and Maven packages.
    $content=[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n").Replace("`r","`n")
    $content=[Text.RegularExpressions.Regex]::Replace($content,'(?m)[ \t]+$','')
    [IO.File]::WriteAllText($_.FullName,$content,[Text.UTF8Encoding]::new($false))
}
@{protocol=1;kernel='OCCT 8.0.1';sdk_url='https://github.com/Open-Cascade-SAS/OCCT/releases/download/V8.0.1/occt-combined-release-no-pch.zip';sdk_sha256='AFE36B6ABCC7964D0F8B0404CCB16E7C1F6DDD8E43B450C865F7E7F092440E9D';files=@(Get-ChildItem -LiteralPath $Output -File | Where-Object Name -Ne 'manifest.json' | ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'manifest.json') -Encoding UTF8
& (Join-Path $Output 'stencil-cad.exe') --version
if($LASTEXITCODE -ne 0){throw 'CAD worker runtime dependencies missing'}
