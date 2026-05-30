# GeneradorCarga.ps1 – Ejecuta la prueba de carga
param(
    [int]$Hilos       = 50,
    [int]$DuracionSeg = 60
)
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
Write-Host "=== Generador de Carga ===" -ForegroundColor Cyan
Write-Host "Hilos=$Hilos  Duracion=${DuracionSeg}s"
Write-Host "Logs en: $root\logs\carga_*.log"
java -cp "sistema-steam.jar;lib\gson-2.10.1.jar" com.steam.carga.GeneradorCarga $Hilos $DuracionSeg
