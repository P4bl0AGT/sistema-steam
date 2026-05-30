@echo off
title Generador de Carga - 50 hilos / 60s
cd /d "%~dp0\.."
echo === Generador de Carga ===
echo Hilos: 50  |  Duracion: 60s
echo Log en: logs\carga_*.log
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.carga.GeneradorCarga 50 60
echo.
pause
