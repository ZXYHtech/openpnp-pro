@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title OpenPnP Pro 一键打包
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-openpnp-pro.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
if not "%RESULT%"=="0" (
    echo 打包失败，请查看上方提示。
) else (
    echo 打包完成，安装程序位于 installers 文件夹。
)
pause
exit /b %RESULT%
