param(
    [switch]$SkipBuild,
    [string]$JdkPath = 'C:\Program Files\ANSYS Inc\Shared Files\licensing\tools\java\winx64'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$stageRoot = Join-Path $repoRoot '.build\corexy-extension'
$classesRoot = Join-Path $stageRoot 'jar'
$outputRoot = Join-Path $repoRoot 'installers'
$version = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION.txt') -Raw).Trim()
$bundleRoot = Join-Path $stageRoot "CoreXY-Precision-$version-Windows-x64"
$jarTool = Join-Path $JdkPath 'bin\jar.exe'
$jlinkTool = Join-Path $JdkPath 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $jarTool) -or -not (Test-Path -LiteralPath $jlinkTool)) {
    throw '打包需要工作区现有的完整 JDK。生成的插件包自带运行时，用户不需要安装 JDK。'
}
function Invoke-Checked([string]$executable, [string[]]$arguments) {
    & $executable @arguments
    if ($LASTEXITCODE -ne 0) { throw "$executable exited with code $LASTEXITCODE" }
}
function Remove-Stage([string]$path) {
    $resolved = [IO.Path]::GetFullPath($path)
    $allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot '.build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理工作区构建目录之外的路径：$resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
if (-not $SkipBuild) {
    $env:JAVA_HOME = $JdkPath
    $env:GIT_CONFIG_COUNT = '1'
    $env:GIT_CONFIG_KEY_0 = 'safe.directory'
    $env:GIT_CONFIG_VALUE_0 = $repoRoot.Replace('\', '/')
    Push-Location -LiteralPath $repoRoot
    try {
        Invoke-Checked (Join-Path $repoRoot '..\.tools\maven\apache-maven-3.9.16\bin\mvn.cmd') @('-o', '-DskipTests', 'package')
    }
    finally { Pop-Location }
}
Remove-Stage $stageRoot
New-Item -ItemType Directory -Path $classesRoot, $bundleRoot, $outputRoot -Force | Out-Null
$kernelSource = Join-Path $repoRoot 'target\classes\org\openpnp\corexy'
if (-not (Test-Path -LiteralPath $kernelSource)) { throw '缺少编译后的 CoreXY 模块，请先打包 Pro。' }
New-Item -ItemType Directory -Path (Join-Path $classesRoot 'org\openpnp') -Force | Out-Null
Copy-Item -LiteralPath $kernelSource -Destination (Join-Path $classesRoot 'org\openpnp\corexy') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'target\classes\corexy') -Destination (Join-Path $classesRoot 'corexy') -Recurse
Push-Location -LiteralPath $classesRoot
try {
    foreach ($dependency in @('byte-buddy-1.15.11.jar', 'byte-buddy-agent-1.15.11.jar', 'commons-math3-3.6.1.jar', 'gson-2.2.3.jar')) {
        $path = Join-Path $repoRoot "target\lib\$dependency"
        if (-not (Test-Path -LiteralPath $path)) { throw "缺少依赖：$path" }
        Invoke-Checked $jarTool @('xf', $path)
    }
}
finally { Pop-Location }
$metadata = Join-Path $classesRoot 'META-INF'
if (Test-Path -LiteralPath $metadata) {
    Get-ChildItem -LiteralPath $metadata -File | Where-Object { $_.Name -match '^(MANIFEST\.MF|.*\.(SF|RSA|DSA))$' } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
}
$manifest = "Manifest-Version: 1.0`nMain-Class: org.openpnp.corexy.distribution.CoreXYInstaller`nPremain-Class: org.openpnp.corexy.agent.CoreXYAgent`nAgent-Class: org.openpnp.corexy.agent.CoreXYAgent`nCan-Redefine-Classes: true`nCan-Retransform-Classes: true`nMulti-Release: true`nImplementation-Version: $version`n`n"
$manifestPath = Join-Path $stageRoot 'MANIFEST.MF'
[IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
$agentJar = Join-Path $bundleRoot 'openpnp-corexy-agent.jar'
Invoke-Checked $jarTool @('cfm', $agentJar, $manifestPath, '-C', $classesRoot, '.')
Invoke-Checked $jlinkTool @('--module-path', (Join-Path $JdkPath 'jmods'), '--add-modules', 'java.se,jdk.unsupported,jdk.attach,jdk.crypto.ec',
    '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', (Join-Path $bundleRoot 'runtime'))
if (-not (Test-Path -LiteralPath (Join-Path $bundleRoot 'runtime\bin\java.exe'))) { throw '运行时缺少 Java 启动器。' }
$installScript = "@echo off`r`nchcp 65001 >nul`r`nsetlocal DisableDelayedExpansion`r`n`"%~dp0runtime\bin\java.exe`" -jar `"%~dp0openpnp-corexy-agent.jar`"`r`nif errorlevel 1 pause`r`n"
[IO.File]::WriteAllText((Join-Path $bundleRoot '安装独立插件.bat'), $installScript, [Text.UTF8Encoding]::new($false))
$offlineScript = "@echo off`r`nchcp 65001 >nul`r`nsetlocal DisableDelayedExpansion`r`n`"%~dp0runtime\bin\java.exe`" -cp `"%~dp0openpnp-corexy-agent.jar`" org.openpnp.corexy.distribution.CoreXYOffline `"%~dp0offline-reports`"`r`npause`r`n"
[IO.File]::WriteAllText((Join-Path $bundleRoot '离线演练.bat'), $offlineScript, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $repoRoot 'extensions\corexy\README_CN.md') -Destination (Join-Path $bundleRoot 'README_CN.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'extensions\corexy\VERIFICATION_CN.md') -Destination (Join-Path $bundleRoot 'VERIFICATION_CN.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'extensions\corexy\upstream-docs') -Destination (Join-Path $bundleRoot 'upstream-docs') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE.txt') -Destination (Join-Path $bundleRoot 'OPENPNP_LICENSE.txt')
$sourceRoot = Join-Path $bundleRoot 'source'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'src\main\java\org\openpnp\corexy') -Destination (Join-Path $sourceRoot 'corexy') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'src\test\java\org\openpnp\corexy') -Destination (Join-Path $sourceRoot 'tests') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'src\main\resources\corexy') -Destination (Join-Path $sourceRoot 'resources') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'package-corexy-extension.ps1') -Destination $sourceRoot
Copy-Item -LiteralPath (Join-Path $repoRoot 'extensions\corexy\build-source.ps1') -Destination $sourceRoot
Copy-Item -LiteralPath (Join-Path $repoRoot 'extensions\corexy\validation') -Destination (Join-Path $sourceRoot 'validation') -Recurse
$zip = Join-Path $outputRoot "CoreXY-Precision-$version-Windows-x64.zip"
Compress-Archive -LiteralPath $bundleRoot -DestinationPath $zip -Force
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
[IO.File]::WriteAllText((Join-Path $outputRoot "CoreXY-Precision-$version-SHA256.txt"), "$hash  $([IO.Path]::GetFileName($zip))`n", [Text.UTF8Encoding]::new($false))
Write-Output $zip
Write-Output $agentJar
