@echo off
title Falla y reingreso del coordinador
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\run_coordinator_failure.ps1" -DelaySec 0
pause
