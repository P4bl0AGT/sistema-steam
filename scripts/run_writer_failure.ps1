$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-Location $SteamRoot

$configMarker = Join-Path $SteamRunDir 'config.txt'
$configPath = if (Test-Path $configMarker) { (Get-Content -Raw $configMarker).Trim() } else { 'config/steam.properties' }
$configFull = if ([IO.Path]::IsPathRooted($configPath)) { $configPath } else { Join-Path $SteamRoot $configPath }
$writerNode = 1
$writerLine = Get-Content $configFull -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^steam\.juegos\.writer\.node=' } | Select-Object -First 1
if ($writerLine) { $writerNode = [int](($writerLine -split '=', 2)[1].Trim()) }
$writerName = "jue-$writerNode"
$writerPort = if ($writerNode -eq 1) { 8082 } else { 8282 }
$writer = Get-SteamPidItems | Where-Object Name -eq $writerName | Select-Object -First 1
if (-not $writer) { throw "$writerName no esta registrado en target/run/pids.json" }
$idempotenciaEstado = Join-Path $SteamRunDir 'idempotencia-reinicio.txt'
& java "-Dsteam.config=$configPath" -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" `
        com.steam.tests.PruebaIdempotenciaReinicio preparar $idempotenciaEstado
if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar la prueba idempotente' }
Stop-Process -Id ([int]$writer.Pid) -Force
$limit = (Get-Date).AddSeconds(10)
while ((Test-SteamPort $writerPort 100) -and (Get-Date) -lt $limit) {
    Start-Sleep -Milliseconds 200
}
if (Test-SteamPort $writerPort 100) { throw 'El writer de juegos no se detuvo' }

try {
    & java "-Dsteam.config=$configPath" -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" `
            com.steam.tests.PruebaWriterNoDisponible $writerNode
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la prueba con writer caido' }
} finally {
    if (-not (Test-SteamPort $writerPort 100)) {
        $nuevo = Start-SteamProcess $writerName 'com.steam.servidores.svJuegos' @("$writerNode")
        Replace-SteamPid $writerName $nuevo.Id
        $ports = if ($writerNode -eq 1) { @(8082,9082,9182,9482) } else { @(8282,9282,9382,9582) }
        foreach ($port in $ports) {
            if (-not (Wait-SteamPort $port 25)) { throw "Writer no reingreso en puerto $port" }
        }
        & java "-Dsteam.config=$configPath" -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" `
                com.steam.tests.PruebaPreparacion 25
        if ($LASTEXITCODE -ne 0) { throw 'Writer reiniciado sin reconciliar' }
        & java "-Dsteam.config=$configPath" -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" `
                com.steam.tests.PruebaIdempotenciaReinicio verificar $idempotenciaEstado
        if ($LASTEXITCODE -ne 0) { throw 'La idempotencia no sobrevivio al reinicio' }
    }
}
Write-Host '[OK] Writer caido: replica promovida, lecturas/escrituras disponibles y reingreso correcto.' -ForegroundColor Green
