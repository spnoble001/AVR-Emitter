@echo off
setlocal
cd /d "%~dp0"
set "JAR=%~dp0target\transpilador-v2.jar"
if not exist "%JAR%" (
  echo No se encuentra target\transpilador-v2.jar.
  echo Ejecuta: powershell -ExecutionPolicy Bypass -File build.ps1 package
  pause
  exit /b 1
)
set "JVM_FLAGS=-Xmx512m --enable-native-access=ALL-UNNAMED --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED"
set "JAVA_BIN=C:\Program Files\Zulu\zulu-25\bin\javaw.exe"
if exist "%JAVA_BIN%" (
  start "" "%JAVA_BIN%" %JVM_FLAGS% -jar "%JAR%" --gui
  exit /b 0
)
start "" javaw.exe %JVM_FLAGS% -jar "%JAR%" --gui
