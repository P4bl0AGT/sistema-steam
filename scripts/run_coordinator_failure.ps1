param(
    [int]$DelaySec = 0,
    [string]$Salida = 'evidencia\falla-coordinador.json'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-Location $SteamRoot
& java -cp "target\classes;lib\gson-2.10.1.jar" com.steam.carga.FallaInducida $DelaySec $Salida
if ($LASTEXITCODE -ne 0) { throw 'El escenario de coordinador fallo' }
$salidaPath = if ([IO.Path]::IsPathRooted($Salida)) { $Salida } else { Join-Path $SteamRoot $Salida }
$estado = Get-Content -Raw $salidaPath | ConvertFrom-Json
$nodo = [int]$estado.coordinadorCaido
$nombre = "jue-$nodo"
$puertoServicio = if ($nodo -eq 1) { 8082 } else { 8282 }
$puertoBully = if ($nodo -eq 1) { 9082 } else { 9282 }
$puertoMutex = if ($nodo -eq 1) { 9182 } else { 9382 }
$puertoReplica = if ($nodo -eq 1) { 9482 } else { 9582 }
$nuevo = Start-SteamProcess $nombre 'com.steam.servidores.svJuegos' @("$nodo")
Replace-SteamPid $nombre $nuevo.Id
foreach ($port in @($puertoServicio,$puertoBully,$puertoMutex,$puertoReplica)) {
    if (-not (Wait-SteamPort $port 25)) { throw "Nodo $nodo no reingreso en puerto $port" }
}
Start-Sleep -Seconds 8
& java -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" com.steam.tests.PruebaDisponibilidad 10
if ($LASTEXITCODE -ne 0) { throw 'Servicio no disponible tras el reingreso' }
Write-Host "[OK] Coordinador nodo-$nodo cayo, hubo reeleccion en $($estado.recuperacionMs) ms y reingreso." -ForegroundColor Green
