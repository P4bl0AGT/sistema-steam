@echo off
title Build y pruebas - Sistema Steam
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\1_build.ps1"
if errorlevel 1 (
    echo [ERROR] Build fallido.
    pause
    exit /b 1
)
echo [OK] Build y pruebas de componentes completados.
pause
