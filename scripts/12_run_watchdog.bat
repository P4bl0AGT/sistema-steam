@echo off
title WatchdogServidor (Supervisor de Nodos)
cd /d "%~dp0\.."
echo === WatchdogServidor - Supervisor de Nodos ===
echo Monitorea los 6 nodos servidores y los reinicia si caen.
echo Intervalo de chequeo: 15s  ^|  Fallos para reinicio: 3
echo Log: %cd%\logs\WatchdogServidor_0.log
echo.
echo IMPORTANTE: Ejecutar DESPUES de haber arrancado los servidores.
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.watchdog.WatchdogServidor
echo.
echo [!] WatchdogServidor se detuvo.
pause
