param([switch]$MantenerProcesos)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
& (Join-Path $PSScriptRoot '1_build.ps1')
& (Join-Path $PSScriptRoot 'reset_datos.ps1') -Force
& (Join-Path $PSScriptRoot 'start_all.ps1') -SinBuild
try {
    & java -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" com.steam.tests.PruebaIntegracion
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la prueba de integracion' }
    & (Join-Path $PSScriptRoot 'run_writer_failure.ps1')
    Write-Host '[OK] Suite completa aprobada.' -ForegroundColor Green
} finally {
    if (-not $MantenerProcesos) { & (Join-Path $PSScriptRoot 'stop_all.ps1') }
}
