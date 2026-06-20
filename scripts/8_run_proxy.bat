@echo off
title Proxy 1 - Puerto 8080
cd /d "%~dp0\.."
echo === Proxy 1 - Puerto 8080 ===
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.proxy.Proxy 1 8080
echo [!] Proxy 1 se detuvo.
pause
