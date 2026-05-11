@echo off
title Proxy - Balanceador (Puerto 8080)
cd /d "%~dp0\.."
echo === Proxy (Balanceador) - Puerto 8080 ===
echo Log: %cd%\logs\Proxy_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.proxy.Proxy
echo.
echo [!] Proxy se detuvo. Revisa el log:
echo     %cd%\logs\Proxy_0.log
pause
