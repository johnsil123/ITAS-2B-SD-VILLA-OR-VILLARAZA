@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out -sourcepath src src\studycircle\client\ClientApp.java src\studycircle\server\ChatServer.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
echo BUILD OK
