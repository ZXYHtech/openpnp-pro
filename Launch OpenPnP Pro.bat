@echo off
setlocal
cd /d "%~dp0"

set "OPENPNP_JAR=target\openpnp-gui-2.6P3-beta45.jar"
if not exist "%OPENPNP_JAR%" (
    echo OpenPnP Pro has not been packaged yet.
    echo Run the Maven package task first, then try again.
    pause
    exit /b 1
)

where javaw.exe >nul 2>nul
if errorlevel 1 (
    echo Java was not found. Install or configure Java 11 and try again.
    pause
    exit /b 1
)

start "OpenPnP Pro" javaw.exe ^
    -Xms64m ^
    -Xmx1536m ^
    -XX:MaxMetaspaceSize=256m ^
    -XX:+UseG1GC ^
    -XX:MaxGCPauseMillis=100 ^
    --add-opens=java.base/java.lang=ALL-UNNAMED ^
    --add-opens=java.desktop/java.awt=ALL-UNNAMED ^
    --add-opens=java.desktop/java.awt.color=ALL-UNNAMED ^
    -Dfile.encoding=UTF-8 ^
    -jar "%OPENPNP_JAR%"

endlocal
