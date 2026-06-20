@echo off
title Proxy 2 - Puerto 8085
cd /d "%~dp0\.."
echo === Proxy 2 - Puerto 8085 ===
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.proxy.Proxy 2 8085
echo [!] Proxy 2 se detuvo.
pause
