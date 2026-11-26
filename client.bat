@echo off
cd /d "%~dp0"
if not exist out\Client.class call compile.bat || exit /b 1
java -cp out Client
