@echo off
title Ver Logs - Sistema Steam
cd /d "%~dp0\.."
echo ============================================
echo   LOGS DEL SISTEMA STEAM
echo ============================================
echo.

if not exist "logs\" (
    echo [!] La carpeta logs\ no existe todavia.
    echo     Inicia al menos un servidor primero.
    pause
    exit /b
)

echo Archivos de log disponibles:
echo.
dir /b logs\*.log 2>nul || echo   (ninguno encontrado)
echo.
echo Elige que log ver:
echo   1. Proxy
echo   2. svSesiones Nodo 1
echo   3. svSesiones Nodo 2
echo   4. svJuegos   Nodo 1
echo   5. svJuegos   Nodo 2
echo   6. svMensajeria Nodo 1
echo   7. svMensajeria Nodo 2
echo   8. Ver TODOS (ultimas 30 lineas de cada uno)
echo   0. Salir
echo.
set /p op="Opcion: "

if "%op%"=="1" goto proxy
if "%op%"=="2" goto ses1
if "%op%"=="3" goto ses2
if "%op%"=="4" goto jue1
if "%op%"=="5" goto jue2
if "%op%"=="6" goto msg1
if "%op%"=="7" goto msg2
if "%op%"=="8" goto todos
goto fin

:proxy
powershell -Command "Get-Content 'logs\Proxy_0.log' -Tail 50 -Wait"
goto fin
:ses1
powershell -Command "Get-Content 'logs\svSesiones-1_0.log' -Tail 50 -Wait"
goto fin
:ses2
powershell -Command "Get-Content 'logs\svSesiones-2_0.log' -Tail 50 -Wait"
goto fin
:jue1
powershell -Command "Get-Content 'logs\svJuegos-1_0.log' -Tail 50 -Wait"
goto fin
:jue2
powershell -Command "Get-Content 'logs\svJuegos-2_0.log' -Tail 50 -Wait"
goto fin
:msg1
powershell -Command "Get-Content 'logs\svMensajeria-1_0.log' -Tail 50 -Wait"
goto fin
:msg2
powershell -Command "Get-Content 'logs\svMensajeria-2_0.log' -Tail 50 -Wait"
goto fin

:todos
echo.
echo ===== PROXY =====
powershell -Command "if(Test-Path 'logs\Proxy_0.log'){Get-Content 'logs\Proxy_0.log' -Tail 10}else{Write-Host '(sin log)'}"
echo.
echo ===== svSesiones-1 =====
powershell -Command "if(Test-Path 'logs\svSesiones-1_0.log'){Get-Content 'logs\svSesiones-1_0.log' -Tail 10}else{Write-Host '(sin log)'}"
echo.
echo ===== svJuegos-1 =====
powershell -Command "if(Test-Path 'logs\svJuegos-1_0.log'){Get-Content 'logs\svJuegos-1_0.log' -Tail 10}else{Write-Host '(sin log)'}"
echo.
echo ===== svMensajeria-1 =====
powershell -Command "if(Test-Path 'logs\svMensajeria-1_0.log'){Get-Content 'logs\svMensajeria-1_0.log' -Tail 10}else{Write-Host '(sin log)'}"
pause

:fin
