@echo off

set archi=%PROCESSOR_ARCHITECTURE%

if not x%archi:86=%==x%archi% java -Xms64m -Xmx1024m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar target\openpnp-gui-2.6P3-beta60.jar
if not x%archi:64=%==x%archi% java -Xms64m -Xmx1536m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar target\openpnp-gui-2.6P3-beta60.jar
