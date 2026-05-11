@echo off
echo === Compilando Sistema Steam ===
cd /d "%~dp0\.."
call mvn clean package -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilacion fallida.
    pause
    exit /b 1
)
echo [OK] Build exitoso. JARs en target/
pause
