@echo off
cd /d "%~dp0"
if not exist out\Server.class call compile.bat || exit /b 1
java -cp out Server %*
