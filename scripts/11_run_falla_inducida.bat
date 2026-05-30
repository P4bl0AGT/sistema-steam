@echo off
title Falla Inducida - Mata al coordinador
cd /d "%~dp0\.."
echo === Falla Inducida ===
echo Esperara 30s y luego enviara SHUTDOWN_GRACEFUL al coordinador.
echo El otro nodo deberia re-elegir automaticamente.
echo.
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.carga.FallaInducida
echo.
pause
