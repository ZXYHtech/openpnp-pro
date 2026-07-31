@echo off

set archi=%PROCESSOR_ARCHITECTURE%

if not x%archi:86=%==x%archi% java -Xms64m -Xmx768m -XX:MaxMetaspaceSize=256m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar target\openpnp-gui-2.6P2.jar
if not x%archi:64=%==x%archi% java -Xms64m -Xmx768m -XX:MaxMetaspaceSize=256m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar target\openpnp-gui-2.6P2.jar


