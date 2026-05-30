@echo off
title svJuegos - Nodo 2 (Puerto 8282)
cd /d "%~dp0\.."
echo === svJuegos Nodo 2 - Puerto 8282 ===
echo Puertos: Servicio=8282 ^| Bully=9282 ^| Mutex=9382 ^| Repl=9582
echo Log: %cd%\logs\svJuegos-2_0.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.servidores.svJuegos 2
echo.
echo [!] svJuegos-2 se detuvo. Revisa el log:
echo     %cd%\logs\svJuegos-2_0.log
pause
