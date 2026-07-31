param(
    [switch]$Online,
    [switch]$RunTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $repoRoot

function Write-Step([string]$message) {
    Write-Host ""
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Find-Executable([string[]]$candidates, [string]$commandName) {
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "找不到 $commandName，请先安装对应工具。"
}

function Invoke-Checked([string]$executable, [string[]]$arguments) {
    & $executable @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$executable 执行失败，退出码 $LASTEXITCODE"
    }
}

function Remove-BuildPath([string]$path, [string]$allowedRoot) {
    $fullPath = [IO.Path]::GetFullPath($path)
    $fullRoot = [IO.Path]::GetFullPath($allowedRoot)
    if (-not $fullPath.StartsWith(
            $fullRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理构建目录之外的路径：$fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function Ensure-Wix([string]$toolRoot) {
    $wixRoot = Join-Path $toolRoot "wix314"
    if ((Test-Path -LiteralPath (Join-Path $wixRoot "candle.exe")) -and
            (Test-Path -LiteralPath (Join-Path $wixRoot "light.exe"))) {
        return $wixRoot
    }

    Write-Step "首次准备 Windows 图形安装器工具"
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $archive = Join-Path $toolRoot "wix314-binaries.zip"
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" `
        -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $wixRoot -Force
    Remove-Item -LiteralPath $archive -Force
    return $wixRoot
}

function Ensure-LightWrapper([string]$toolRoot, [string]$wixRoot) {
    $wrapperRoot = Join-Path $toolRoot "wix-light-wrapper"
    $wrapper = Join-Path $wrapperRoot "light.exe"
    $source = Join-Path $repoRoot "packaging\windows\LightWrapper.cs"
    if (-not (Test-Path -LiteralPath $wrapper) -or
            (Get-Item -LiteralPath $source).LastWriteTimeUtc -gt
            (Get-Item -LiteralPath $wrapper).LastWriteTimeUtc) {
        New-Item -ItemType Directory -Force -Path $wrapperRoot | Out-Null
        $compiler = Find-Executable @(
            "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
            "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
        ) "csc.exe"
        Invoke-Checked $compiler @(
            "/nologo", "/optimize+", "/target:exe",
            "/out:$wrapper", $source
        )
    }
    return $wrapperRoot
}

$version = (Get-Content -LiteralPath "VERSION.txt" -Raw).Trim()
if ($version -notmatch '^(\d+)\.(\d+)P(\d+)$') {
    throw "VERSION.txt 必须采用类似 2.6P1 的格式，当前为：$version"
}
$installerVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
$jarName = "openpnp-gui-$version.jar"
$installerName = "OpenPnP-Pro-$version-Setup.exe"

$mavenCandidates = @(
    (Join-Path $repoRoot "..\.tools\maven\apache-maven-3.9.16\bin\mvn.cmd")
)
if ($env:MAVEN_HOME) {
    $mavenCandidates += Join-Path $env:MAVEN_HOME "bin\mvn.cmd"
}
$maven = Find-Executable $mavenCandidates "mvn.cmd"

$jpackageCandidates = @(
    "C:\Program Files\ANSYS Inc\Shared Files\licensing\tools\java\winx64\bin\jpackage.exe"
)
if ($env:JAVA_HOME) {
    $jpackageCandidates = @(
        (Join-Path $env:JAVA_HOME "bin\jpackage.exe")
    ) + $jpackageCandidates
}
$jpackage = Find-Executable $jpackageCandidates "jpackage.exe"

$buildRoot = Join-Path $repoRoot ".build\package-openpnp-pro"
$inputRoot = Join-Path $buildRoot "input"
$installerBuild = Join-Path $buildRoot "installer"
$installerRoot = Join-Path $repoRoot "installers"
$appImage = Join-Path $installerRoot "OpenPnP Pro"
$finalInstaller = Join-Path $installerRoot $installerName
$hashFile = Join-Path $installerRoot "OpenPnP-Pro-$version-SHA256.txt"
$toolRoot = Join-Path $repoRoot ".build-tools"

try {
    Write-Step "编译 OpenPnP Pro $version"
    $env:MAVEN_OPTS = "-Xms32m -Xmx384m -XX:MaxMetaspaceSize=192m " +
        "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dfile.encoding=UTF-8"
    $mavenArguments = @()
    if (-not $Online) {
        $mavenArguments += "-o"
    }
    if (-not $RunTests) {
        $mavenArguments += "-DskipTests"
    }
    $mavenArguments += "package"
    Invoke-Checked $maven $mavenArguments

    $jarPath = Join-Path $repoRoot "target\$jarName"
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "编译完成但未找到 $jarName"
    }

    Write-Step "准备最小化安装内容"
    Remove-BuildPath $buildRoot (Join-Path $repoRoot ".build")
    New-Item -ItemType Directory -Force -Path (Join-Path $inputRoot "lib") | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination $inputRoot -Force
    Copy-Item -Path "target\lib\*" -Destination (Join-Path $inputRoot "lib") `
        -Recurse -Force

    $jarTool = Join-Path (Split-Path -Parent $jpackage) "jar.exe"
    $jarEntries = & $jarTool tf $jarPath
    if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains "VERSION.txt") {
        throw "JAR 内缺少 VERSION.txt，安装后会无法显示版本号。"
    }

    New-Item -ItemType Directory -Force -Path $installerRoot, $installerBuild | Out-Null
    Remove-BuildPath $appImage $installerRoot
    if (Test-Path -LiteralPath $finalInstaller) {
        Remove-Item -LiteralPath $finalInstaller -Force
    }

    $commonArguments = @(
        "--name", "OpenPnP Pro",
        "--app-version", $installerVersion,
        "--vendor", "ZXYHtech",
        "--copyright", "OpenPnP contributors and ZXYHtech",
        "--description", "OpenPnP Pro $version - Professional SMT placement control",
        "--input", $inputRoot,
        "--main-jar", $jarName,
        "--main-class", "org.openpnp.Main",
        "--icon", (Join-Path $repoRoot "src\main\resources\branding\openpnp-pro.ico"),
        "--java-options", "-Xms64m",
        "--java-options", "-Xmx768m",
        "--java-options", "-XX:MaxMetaspaceSize=256m",
        "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--java-options", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--java-options", "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED"
    )

    Write-Step "生成便携程序目录"
    Invoke-Checked $jpackage (@(
        "--type", "app-image",
        "--dest", $installerRoot
    ) + $commonArguments)

    Write-Step "生成单文件 Windows 图形安装器"
    $wixRoot = Ensure-Wix $toolRoot
    $wrapperRoot = Ensure-LightWrapper $toolRoot $wixRoot
    $env:Path = $wrapperRoot + [IO.Path]::PathSeparator +
        $wixRoot + [IO.Path]::PathSeparator + $env:Path
    Invoke-Checked $jpackage (@(
        "--type", "exe",
        "--dest", $installerBuild,
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-per-user-install",
        "--win-menu-group", "OpenPnP Pro",
        "--win-upgrade-uuid", "C13CF65E-53AF-4E23-9EE2-BBAF876E2461",
        "--win-help-url", "https://github.com/ZXYHtech/openpnp-pro",
        "--win-update-url", "https://github.com/ZXYHtech/openpnp-pro/releases"
    ) + $commonArguments)

    $generatedInstaller = Join-Path $installerBuild "OpenPnP Pro-$installerVersion.exe"
    if (-not (Test-Path -LiteralPath $generatedInstaller)) {
        throw "图形安装器生成完成，但找不到预期输出文件。"
    }
    Move-Item -LiteralPath $generatedInstaller -Destination $finalInstaller -Force

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $finalInstaller).Hash
    [IO.File]::WriteAllText(
        $hashFile,
        "$hash  $installerName`n",
        (New-Object Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "打包成功" -ForegroundColor Green
    Write-Host "程序目录：$appImage"
    Write-Host "安装程序：$finalInstaller"
    Write-Host "SHA-256 ：$hash"
}
finally {
    if (Test-Path -LiteralPath $buildRoot) {
        Remove-BuildPath $buildRoot (Join-Path $repoRoot ".build")
    }
}
