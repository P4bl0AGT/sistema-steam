@echo off
title svMensajeria - Nodo 2 (Puerto 8383)
cd /d "%~dp0\.."
echo === svMensajeria Nodo 2 - Puerto 8383 ===
echo Log: %cd%\logs\svMensajeria-2_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svMensajeria 2
echo.
echo [!] svMensajeria-2 se detuvo. Revisa el log:
echo     %cd%\logs\svMensajeria-2_0.log
pause
