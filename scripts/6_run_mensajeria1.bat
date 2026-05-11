@echo off
title svMensajeria - Nodo 1 (Puerto 8083)
cd /d "%~dp0\.."
echo === svMensajeria Nodo 1 - Puerto 8083 ===
echo Log: %cd%\logs\svMensajeria-1_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svMensajeria 1
echo.
echo [!] svMensajeria-1 se detuvo. Revisa el log:
echo     %cd%\logs\svMensajeria-1_0.log
pause
