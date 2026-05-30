@echo off
title svSesiones - Nodo 1 (Puerto 8081)
cd /d "%~dp0\.."
echo === svSesiones Nodo 1 - Puerto 8081 ===
echo Puertos: Servicio=8081 ^| Repl=9483
echo Log: %cd%\logs\svSesiones-1_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svSesiones 1
echo.
echo [!] svSesiones-1 se detuvo. Revisa el log para ver la causa:
echo     %cd%\logs\svSesiones-1_0.log
pause
