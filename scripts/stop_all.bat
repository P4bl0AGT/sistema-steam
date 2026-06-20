@echo off
title Detener topologia - Sistema Steam
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\stop_all.ps1"
pause
