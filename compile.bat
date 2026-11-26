@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\Server.java src\Client.java
if errorlevel 1 (
    echo COMPILE FAILED
    exit /b 1
)
echo COMPILE OK
