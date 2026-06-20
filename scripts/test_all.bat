@echo off
title Suite automatica - Sistema Steam
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test_all.ps1"
pause
