param(
    [switch]$Online,
    [switch]$RunTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $repoRoot

# Allow Maven's buildnumber plugin to read this exact repository when the
# sandbox user differs from the checkout owner. This is scoped to child
# processes and does not modify the user's global Git configuration.
$env:GIT_CONFIG_COUNT = "1"
$env:GIT_CONFIG_KEY_0 = "safe.directory"
$env:GIT_CONFIG_VALUE_0 = $repoRoot.Replace('\', '/')

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

function Test-AppImageInUse([string]$path) {
    $prefix = [IO.Path]::GetFullPath($path) + [IO.Path]::DirectorySeparatorChar
    foreach ($process in Get-Process -ErrorAction SilentlyContinue) {
        try {
            if ($process.Path -and $process.Path.StartsWith(
                    $prefix, [StringComparison]::OrdinalIgnoreCase)) {
                return $true
            }
        }
        catch {
            # Some protected processes do not expose Path; they cannot be this app image.
        }
    }
    return $false
}

function Get-MsiRows([string]$msiPath, [string]$query, [int]$columnCount) {
    $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
    $database = $windowsInstaller.GetType().InvokeMember(
        "OpenDatabase", "InvokeMethod", $null, $windowsInstaller, @($msiPath, 0))
    $view = $database.GetType().InvokeMember(
        "OpenView", "InvokeMethod", $null, $database, @($query))
    $view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null) | Out-Null
    $rows = @()
    while ($true) {
        $record = $view.GetType().InvokeMember(
            "Fetch", "InvokeMethod", $null, $view, $null)
        if ($null -eq $record) {
            break
        }
        $values = @()
        for ($column = 1; $column -le $columnCount; $column++) {
            $values += $record.GetType().InvokeMember(
                "StringData", "GetProperty", $null, $record, $column)
        }
        $rows += ($values -join "`t")
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null
    }
    $view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($windowsInstaller) | Out-Null
    return $rows
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
if ($version -notmatch '^(\d+)\.(\d+)P(\d+)(?:-beta(\d+))?$') {
    throw "VERSION.txt 必须采用 2.6P4 或 2.6P3-beta1 格式，当前为：$version"
}
$baseMajor = [int]$Matches[1]
$baseMinor = [int]$Matches[2]
$proRelease = [int]$Matches[3]
$betaNumber = if ($Matches[4]) { [int]$Matches[4] } else { 0 }
if ($betaNumber -gt 99) {
    throw "beta 序号不能超过 99；请发布下一个 P 版本。"
}
$installerBuild = if ($betaNumber -eq 0) {
    $proRelease * 100
}
elseif ($betaNumber -le 9) {
    # Keep beta1-beta9 compatible with previously shipped numeric versions 31-39.
    ($proRelease * 10) + $betaNumber
}
else {
    # beta10-beta99 occupy 310-399, leaving formal P4 at 400 and preserving upgrade order.
    ($proRelease * 100) + $betaNumber
}
$installerVersion = "$baseMajor.$baseMinor.$installerBuild"
$jarName = "openpnp-gui-$version.jar"
$installerName = "OpenPnP-Pro-$version-Setup.exe"
$installerResourceSourceRoot = Join-Path $repoRoot "packaging\windows\jpackage-resources"
$releaseNotesPath = Join-Path $repoRoot "packaging\windows\INSTALLER_RELEASE_NOTES_CN.txt"
foreach ($requiredInstallerFile in @(
        (Join-Path $installerResourceSourceRoot "ShortcutPromptDlg.wxs.template"),
        (Join-Path $installerResourceSourceRoot "overrides.wxi"),
        $releaseNotesPath)) {
    if (-not (Test-Path -LiteralPath $requiredInstallerFile)) {
        throw "安装向导资源缺失：$requiredInstallerFile"
    }
}
$releaseNotes = ([IO.File]::ReadAllText($releaseNotesPath, [Text.Encoding]::UTF8) `
        -replace '\s+', ' ').Trim()
if (-not $releaseNotes -or $releaseNotes.Length -gt 220) {
    throw "安装器版本介绍必须为 1～220 个字符，当前长度：$($releaseNotes.Length)"
}
$installerDescription = "OpenPnP Pro $version · $releaseNotes"

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
$installerResourceRoot = Join-Path $buildRoot "jpackage-resources"
$installerBuild = Join-Path $buildRoot "installer"
$installerTemp = Join-Path $buildRoot "jpackage-exe-temp"
$installerRoot = Join-Path $repoRoot "installers"
$appImage = Join-Path $installerRoot "OpenPnP Pro"
$stagedAppImage = Join-Path $installerBuild "OpenPnP Pro"
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
    $staleJarPath = Join-Path $repoRoot "target\$jarName"
    if (Test-Path -LiteralPath $staleJarPath) {
        Remove-Item -LiteralPath $staleJarPath -Force
    }
    $mavenArguments += "package"
    Invoke-Checked $maven $mavenArguments

    $jarPath = Join-Path $repoRoot "target\$jarName"
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "编译完成但未找到 $jarName"
    }

    Write-Step "准备最小化安装内容"
    Remove-BuildPath $buildRoot (Join-Path $repoRoot ".build")
    New-Item -ItemType Directory -Force -Path `
        (Join-Path $inputRoot "lib"), $installerResourceRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $installerResourceSourceRoot "overrides.wxi") `
        -Destination $installerResourceRoot -Force
    $dialogTemplate = [IO.File]::ReadAllText(
        (Join-Path $installerResourceSourceRoot "ShortcutPromptDlg.wxs.template"),
        [Text.Encoding]::UTF8)
    $escapedDescription = [Security.SecurityElement]::Escape($installerDescription)
    $generatedDialog = $dialogTemplate.Replace("@@JP_RELEASE_NOTES@@", $escapedDescription)
    if ($generatedDialog.Contains("@@JP_RELEASE_NOTES@@")) {
        throw "安装器版本介绍没有正确写入向导模板。"
    }
    [IO.File]::WriteAllText(
        (Join-Path $installerResourceRoot "ShortcutPromptDlg.wxs"),
        $generatedDialog,
        (New-Object Text.UTF8Encoding($false)))
    Copy-Item -LiteralPath $jarPath -Destination $inputRoot -Force
    Copy-Item -Path "target\lib\*" -Destination (Join-Path $inputRoot "lib") `
        -Recurse -Force
    Copy-Item -LiteralPath $releaseNotesPath `
        -Destination (Join-Path $inputRoot "INSTALLER_RELEASE_NOTES_CN.txt") -Force
    Copy-Item -LiteralPath (Join-Path $repoRoot "OPENPNP_PRO_CHANGELOG_CN.md") `
        -Destination (Join-Path $inputRoot "OPENPNP_PRO_CHANGELOG_CN.md") -Force

    $jarTool = Join-Path (Split-Path -Parent $jpackage) "jar.exe"
    $jarEntries = & $jarTool tf $jarPath
    if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains "VERSION.txt") {
        throw "JAR 内缺少 VERSION.txt，安装后会无法显示版本号。"
    }

    New-Item -ItemType Directory -Force -Path $installerRoot, $installerBuild | Out-Null
    if (Test-Path -LiteralPath $finalInstaller) {
        Remove-Item -LiteralPath $finalInstaller -Force
    }

    $commonArguments = @(
        "--name", "OpenPnP Pro",
        "--app-version", $installerVersion,
        "--vendor", "ZXYHtech",
        "--copyright", "OpenPnP contributors and ZXYHtech",
        "--description", $installerDescription,
        "--input", $inputRoot,
        "--main-jar", $jarName,
        "--main-class", "org.openpnp.Main",
        "--icon", (Join-Path $repoRoot "src\main\resources\branding\openpnp-pro.ico"),
        "--java-options", "-Xms64m",
        "--java-options", "-Xmx1536m",
        "--java-options", "-XX:MaxMetaspaceSize=256m",
        "--java-options", "-XX:+UseG1GC",
        "--java-options", "-XX:MaxGCPauseMillis=100",
        "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--java-options", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--java-options", "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED"
    )

    Write-Step "生成便携程序目录"
    Invoke-Checked $jpackage (@(
        "--type", "app-image",
        "--dest", $installerBuild
    ) + $commonArguments)

    Write-Step "生成单文件 Windows 图形安装器"
    $wixRoot = Ensure-Wix $toolRoot
    $wrapperRoot = Ensure-LightWrapper $toolRoot $wixRoot
    $env:Path = $wrapperRoot + [IO.Path]::PathSeparator +
        $wixRoot + [IO.Path]::PathSeparator + $env:Path
    $installerArguments = @(
        "--type", "exe",
        "--dest", $installerBuild,
        "--temp", $installerTemp,
        "--resource-dir", $installerResourceRoot,
        "--win-menu",
        "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-dir-chooser",
        "--win-per-user-install",
        "--win-menu-group", "OpenPnP Pro",
        "--win-upgrade-uuid", "C13CF65E-53AF-4E23-9EE2-BBAF876E2461",
        "--win-help-url", "https://github.com/ZXYHtech/openpnp-pro",
        "--win-update-url", "https://github.com/ZXYHtech/openpnp-pro/releases"
    ) + $commonArguments
    try {
        Invoke-Checked $jpackage $installerArguments
    }
    catch {
        Write-Warning "安装器 EXE 首次生成失败，将清理 jpackage 临时文件后自动重试一次：$($_.Exception.Message)"
        Remove-BuildPath $installerTemp $buildRoot
        $partialInstaller = Join-Path $installerBuild "OpenPnP Pro-$installerVersion.exe"
        if (Test-Path -LiteralPath $partialInstaller) {
            Remove-Item -LiteralPath $partialInstaller -Force
        }
        Start-Sleep -Seconds 2
        Invoke-Checked $jpackage $installerArguments
    }

    $generatedMsi = Join-Path $installerTemp `
        "images\win-exe.image\OpenPnP Pro-$installerVersion.msi"
    $compiledDialog = Join-Path $installerTemp "wixobj\ShortcutPromptDlg.wixobj"
    $appliedDialog = Join-Path $installerTemp "config\ShortcutPromptDlg.wxs"
    $appliedOverrides = Join-Path $installerTemp "config\overrides.wxi"
    if (-not (Test-Path -LiteralPath $generatedMsi) -or
            -not (Test-Path -LiteralPath $compiledDialog) -or
            -not (Select-String -LiteralPath $appliedDialog `
                -SimpleMatch "版本介绍与安装方式" -Quiet) -or
            -not (Select-String -LiteralPath $appliedDialog `
                -SimpleMatch "JP_UPGRADABLE_FOUND" -Quiet) -or
            -not (Select-String -LiteralPath $appliedOverrides `
                -SimpleMatch "undef JpAllowDowngrades" -Quiet)) {
        throw "安装器已生成，但版本介绍、旧版替换提示或降级保护没有进入 WiX 构建。"
    }

    $buttonRows = @(Get-MsiRows $generatedMsi `
        'SELECT `Control`, `Attributes` FROM `Control` WHERE `Dialog_`=''ShortcutPromptDlg'' AND (`Control`=''Next'' OR `Control`=''Continue'')' 2)
    $eventRows = @(Get-MsiRows $generatedMsi `
        'SELECT `Control_`, `Event`, `Argument`, `Condition` FROM `ControlEvent` WHERE `Dialog_`=''ShortcutPromptDlg'' AND (`Control_`=''Next'' OR `Control_`=''Continue'')' 4)
    $releaseRows = @(Get-MsiRows $generatedMsi `
        'SELECT `Text` FROM `Control` WHERE `Dialog_`=''ShortcutPromptDlg'' AND `Control`=''ReleaseNotes''' 1)
    $downgradeRows = @(Get-MsiRows $generatedMsi `
        'SELECT `Action` FROM `CustomAction` WHERE `Action`=''JpDisallowDowngrade''' 1)
    if ($buttonRows -notcontains "Next`t0" -or
            $buttonRows -notcontains "Continue`t3" -or
            $eventRows -notcontains "Continue`tSpawnDialog`tReplaceRequiredDlg`tJP_UPGRADABLE_FOUND AND NOT JP_CONFIRM_REPLACE" -or
            $eventRows -notcontains "Continue`tSpawnDialog`tDowngradeBlockedDlg`tJP_DOWNGRADABLE_FOUND" -or
            $eventRows -notcontains "Continue`tNewDialog`tVerifyReadyDlg`tNOT JP_DOWNGRADABLE_FOUND AND (NOT JP_UPGRADABLE_FOUND OR JP_CONFIRM_REPLACE)" -or
            $releaseRows.Count -ne 1 -or $releaseRows[0] -notlike "*$version*" -or
            $downgradeRows -notcontains "JpDisallowDowngrade") {
        throw "MSI 数据表验证失败：安装向导可能绕过版本说明、替换确认或降级保护。"
    }

    $generatedInstaller = Join-Path $installerBuild "OpenPnP Pro-$installerVersion.exe"
    if (-not (Test-Path -LiteralPath $generatedInstaller)) {
        throw "图形安装器生成完成，但找不到预期输出文件。"
    }
    Move-Item -LiteralPath $generatedInstaller -Destination $finalInstaller -Force

    $portableUpdated = $false
    $portableOutput = $appImage
    if (Test-AppImageInUse $appImage) {
        Write-Warning "唯一便携目录正被运行中的 OpenPnP 占用；本次只更新安装程序。关闭程序后重新打包即可刷新 installers\OpenPnP Pro，不再生成 beta 版本化目录。"
    }
    else {
        try {
            Remove-BuildPath $appImage $installerRoot
            Move-Item -LiteralPath $stagedAppImage -Destination $appImage
            $portableUpdated = $true
        }
        catch {
            Write-Warning "便携程序目录无法替换，安装程序仍是最新版本：$($_.Exception.Message)"
        }
    }

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $finalInstaller).Hash
    [IO.File]::WriteAllText(
        $hashFile,
        "$hash  $installerName`n",
        (New-Object Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "打包成功" -ForegroundColor Green
    if ($portableUpdated) {
        Write-Host "程序目录：$portableOutput"
    }
    else {
        Write-Host "程序目录：仍为运行中的旧版本（未覆盖）"
    }
    Write-Host "安装程序：$finalInstaller"
    Write-Host "SHA-256 ：$hash"
}
finally {
    if (Test-Path -LiteralPath $buildRoot) {
        Remove-BuildPath $buildRoot (Join-Path $repoRoot ".build")
    }
}
