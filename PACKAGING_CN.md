# OpenPnP Pro 一键打包说明

## 最简单的使用方式

双击项目根目录中的 `一键打包 OpenPnP Pro.cmd`。

脚本会依次完成：

1. 使用 Maven 编译并执行代码规范检查。
2. 验证 JAR 内包含版本文件和品牌资源。
3. 生成 `installers\OpenPnP Pro` 便携程序目录。
4. 生成 `installers\OpenPnP-Pro-版本号-Setup.exe` 图形安装程序。
5. 生成对应的 SHA-256 校验文件。

首次打包如果本机没有 WiX，脚本会从 WiX 官方 GitHub 发布页下载 3.14.1，
保存到被 Git 忽略的 `.build-tools` 目录；后续打包直接复用。

## 可选参数

在 PowerShell 中运行：

```powershell
.\package-openpnp-pro.ps1 -RunTests
```

会在打包前运行完整测试。默认跳过测试以提高日常打包速度，但始终执行代码规范检查和编译。

```powershell
.\package-openpnp-pro.ps1 -Online
```

允许 Maven 在线获取缺失依赖。默认使用离线模式，减少等待和网络波动。

## 版本规则

只需要修改根目录的 `VERSION.txt`，例如从 `2.6P1` 改为 `2.6P2`。
脚本会自动换算 Windows 安装器要求的数字版本，并同步输出文件名。

## 安全范围

打包脚本只会清理项目中的 `.build\package-openpnp-pro`、对应的安装输出和临时安装器目录。
不会读取、修改或删除用户的 `.openpnp2` 机器、飞达、吸嘴、物料与任务数据。
