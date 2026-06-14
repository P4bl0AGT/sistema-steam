# 13_run_prueba_trafico.ps1
# Orquesta la PRUEBA DE TRÁFICO REAL completa (rúbrica §3):
#   1. Reset de datos (no interactivo) para partir limpio.
#   2. Levanta los 6 servidores + proxy en segundo plano.
#   3. Espera a que se estabilicen Bully + registro en el proxy.
#   4. Lanza la falla inducida (mata al coordinador a los N s y mide recuperación).
#   5. Corre el generador de carga en primer plano y captura el reporte.
#   6. Derriba todos los procesos y deja los logs listos para el informe.
#
# Uso:  .\scripts\13_run_prueba_trafico.ps1 [-Hilos 50] [-DuracionSeg 60] [-EsperaFalla 30]

param(
    [int]$Hilos       = 50,
    [int]$DuracionSeg = 60,
    [int]$EsperaFalla = 30
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$cp = "sistema-steam.jar;lib\gson-2.10.1.jar"
New-Item -ItemType Directory -Force -Path "$root\logs", "$root\data" | Out-Null

Write-Host "`n══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  PRUEBA DE TRÁFICO  (hilos=$Hilos, dur=${DuracionSeg}s, falla@${EsperaFalla}s)" -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════`n" -ForegroundColor Cyan

# ── 1. Reset de datos: BORRAR los archivos (0 bytes/ausentes) para que los ─────
#    servidores re-siembren. (El guard de siembra usa archivoVacio()==length 0,
#    por eso NO sirve escribir JSON vacío: hay que dejar el archivo ausente.)
Remove-Item "$root\data\SES_Main.txt","$root\data\SES_Copy.txt",`
            "$root\data\GME_Main.txt","$root\data\GME_Copy.txt",`
            "$root\data\MSG_Main.txt","$root\data\MSG_Copy.txt",`
            "$root\data\MEMBRESIA.txt" -ErrorAction SilentlyContinue
Write-Host "[ORQ] Datos borrados (los servidores re-siembran al arrancar)." -ForegroundColor Green

# ── 2. Helper para lanzar un componente en segundo plano ──────────────────────
$procs = @()
function Start-Comp([string]$clase, [string[]]$extra, [string]$nombre) {
    $argumentos = @("-cp", $cp, $clase) + $extra
    Start-Process -FilePath "java" -ArgumentList $argumentos -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$root\logs\orq_$nombre.out" `
        -RedirectStandardError  "$root\logs\orq_$nombre.err"
}

# ── 3. Servidores escalonados: el nodo 1 de cada par siembra ANTES que el ─────
#    nodo 2 lea (evita carrera de siembra sobre el archivo compartido Main/Copy).
Write-Host "[ORQ] Levantando 6 servidores (escalonados)..." -ForegroundColor Yellow
$procs += Start-Comp "com.steam.servidores.svSesiones"   @("1") "ses1"; Start-Sleep -Seconds 2
$procs += Start-Comp "com.steam.servidores.svJuegos"     @("1") "jue1"; Start-Sleep -Seconds 2
$procs += Start-Comp "com.steam.servidores.svMensajeria" @("1") "msg1"; Start-Sleep -Seconds 2
$procs += Start-Comp "com.steam.servidores.svSesiones"   @("2") "ses2"
$procs += Start-Comp "com.steam.servidores.svJuegos"     @("2") "jue2"
$procs += Start-Comp "com.steam.servidores.svMensajeria" @("2") "msg2"
Start-Sleep -Seconds 3

Write-Host "[ORQ] Levantando proxy..." -ForegroundColor Yellow
$procs += Start-Comp "com.steam.proxy.Proxy" @() "proxy"

Write-Host "[ORQ] Esperando estabilización (Bully + registro en proxy)..." -ForegroundColor Yellow
Start-Sleep -Seconds 14

# ── 4. Falla inducida en segundo plano ────────────────────────────────────────
Write-Host "[ORQ] Lanzando falla inducida (matará al coordinador a los ${EsperaFalla}s)..." -ForegroundColor Magenta
$falla = Start-Process -FilePath "java" `
    -ArgumentList @("-cp", $cp, "com.steam.carga.FallaInducida", "$EsperaFalla") `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$root\logs\orq_falla.out" `
    -RedirectStandardError  "$root\logs\orq_falla.err"

# ── 5. Generador de carga en primer plano (bloquea hasta terminar) ────────────
Write-Host "[ORQ] Iniciando generador de carga...`n" -ForegroundColor Green
& java -cp $cp com.steam.carga.GeneradorCarga $Hilos $DuracionSeg

# ── 6. Esperar a que la falla termine de medir la recuperación ────────────────
if ($falla -and -not $falla.HasExited) { $falla.WaitForExit(20000) | Out-Null }
Write-Host "`n── Resultado de la falla inducida ─────────────────────" -ForegroundColor Magenta
Get-Content "$root\logs\orq_falla.out" -ErrorAction SilentlyContinue

# ── 7. Teardown ───────────────────────────────────────────────────────────────
Write-Host "`n[ORQ] Deteniendo procesos..." -ForegroundColor Yellow
if ($falla) { try { Stop-Process -Id $falla.Id -Force -ErrorAction Stop } catch {} }
foreach ($p in $procs) { try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch {} }

Write-Host "`n[ORQ] LISTO. Evidencia en logs\:" -ForegroundColor Cyan
Write-Host "  - carga_*.log            (reporte de carga: throughput, p95, pérdidas, msgs coord)"
Write-Host "  - orq_falla.out          (tiempo de recuperación tras la falla)"
Write-Host "  - svJuegos-1_0.log / svJuegos-2_0.log  (Lamport, Bully, Mutex, reelección)"
Write-Host "  - MEMBRESIA.txt en data\ (membresía del clúster)"
