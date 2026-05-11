@echo off
title ClienteJava - Interfaz de Consola
echo === Iniciando Cliente ===
cd /d "%~dp0\.."
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.cliente.ClienteJava
pause
