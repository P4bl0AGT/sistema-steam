@echo off
title svJuegos - Nodo 1 (Puerto 8082)
cd /d "%~dp0\.."
echo === svJuegos Nodo 1 - Puerto 8082 ===
echo Log: %cd%\logs\svJuegos-1_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svJuegos 1
echo.
echo [!] svJuegos-1 se detuvo. Revisa el log:
echo     %cd%\logs\svJuegos-1_0.log
pause
