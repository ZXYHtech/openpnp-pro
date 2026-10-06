param(
    [Parameter(Mandatory = $true)][string]$OpenPnpDirectory,
    [Parameter(Mandatory = $true)][string]$JdkDirectory,
    [string]$SourceDirectory = $PSScriptRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath($SourceDirectory)
$bundleRoot = Split-Path -Parent $sourceRoot
$kernelJar = Join-Path $bundleRoot 'openpnp-corexy-agent.jar'
$outputRoot = Join-Path $sourceRoot 'build'
$javac = Join-Path $JdkDirectory 'bin\javac.exe'
$jar = Join-Path $JdkDirectory 'bin\jar.exe'
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $kernelJar)) {
    throw '开发者编译需要 JDK 和完整插件包；普通用户使用插件不需要安装 JDK。'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$gui = $null
foreach ($candidate in Get-ChildItem -LiteralPath $OpenPnpDirectory -Filter '*.jar' -Recurse -File) {
    $archive = [IO.Compression.ZipFile]::OpenRead($candidate.FullName)
    try {
        if ($null -ne $archive.GetEntry('org/openpnp/Main.class')) { $gui = $candidate.FullName; break }
    }
    finally { $archive.Dispose() }
}
if ($null -eq $gui) { throw '没有找到原版 OpenPnP GUI JAR。' }
New-Item -ItemType Directory -Path (Join-Path $outputRoot 'classes') -Force | Out-Null
$classpath = "$kernelJar;$gui;$(Join-Path $OpenPnpDirectory 'lib')\*;$(Join-Path (Split-Path -Parent $gui) 'lib')\*"
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'corexy') -Filter '*.java' -Recurse -File |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
$argumentsFile = Join-Path $outputRoot 'javac-sources.txt'
[IO.File]::WriteAllLines($argumentsFile, $sources, [Text.UTF8Encoding]::new($false))
& $javac --release 11 -encoding UTF-8 -cp $classpath -d (Join-Path $outputRoot 'classes') "@$argumentsFile"
if ($LASTEXITCODE -ne 0) { throw "原版 API 编译失败：$LASTEXITCODE" }
$builtJar = Join-Path $outputRoot 'openpnp-corexy-agent.jar'
Copy-Item -LiteralPath $kernelJar -Destination $builtJar -Force
& $jar --update --file $builtJar -C (Join-Path $outputRoot 'classes') .
if ($LASTEXITCODE -ne 0) { throw "构建插件 JAR 失败：$LASTEXITCODE" }
Write-Output $builtJar
