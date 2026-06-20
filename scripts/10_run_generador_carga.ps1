param(
    [int]$Hilos = 50,
    [int]$DuracionSeg = 60,
    [string]$Salida = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not $Salida) { $Salida = 'evidencia\carga\' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
& java -cp "target\classes;lib\gson-2.10.1.jar" com.steam.carga.GeneradorCarga $Hilos $DuracionSeg $Salida
if ($LASTEXITCODE -ne 0) { throw 'Fallo el generador de carga' }
