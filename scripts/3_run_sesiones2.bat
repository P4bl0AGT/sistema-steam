@echo off
title svSesiones - Nodo 2 (Puerto 8181)
cd /d "%~dp0\.."
echo === svSesiones Nodo 2 - Puerto 8181 ===
echo Log: %cd%\logs\svSesiones-2_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svSesiones 2
echo.
echo [!] svSesiones-2 se detuvo. Revisa el log:
echo     %cd%\logs\svSesiones-2_0.log
pause
