@echo off
title Carga - 50 hilos por 60 segundos
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\10_run_generador_carga.ps1" -Hilos 50 -DuracionSeg 60
pause
