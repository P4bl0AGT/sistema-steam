param(
    [int]$Hilos = 50,
    [int]$DuracionSeg = 60,
    [int]$FallaEnSeg = 20,
    [string]$Evidencia = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not $Evidencia) { $Evidencia = 'evidencia\ejecucion-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
$evidencePath = Join-Path $root $Evidencia
New-Item -ItemType Directory -Force $evidencePath | Out-Null

& (Join-Path $PSScriptRoot '1_build.ps1')
& (Join-Path $PSScriptRoot 'reset_datos.ps1') -Force
& (Join-Path $PSScriptRoot 'start_all.ps1')
try {
    $failureOut = Join-Path $evidencePath 'falla-coordinador.out.txt'
    $failureErr = Join-Path $evidencePath 'falla-coordinador.err.txt'
    $failureJson = Join-Path $evidencePath 'falla-coordinador.json'
    $failureStart = Join-Path $evidencePath 'falla-inicio.marker'
    $failureRecovered = Join-Path $evidencePath 'falla-recuperada.marker'
    $failureArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',
        (Join-Path $PSScriptRoot 'run_coordinator_failure.ps1'),
        '-DelaySec',"$FallaEnSeg",'-Salida',$failureJson,
        '-MarcadorInicio',$failureStart,'-MarcadorRecuperacion',$failureRecovered)
    $failure = Start-Process -FilePath 'powershell.exe' -ArgumentList $failureArgs `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $failureOut -RedirectStandardError $failureErr

    & java -cp "target\classes;lib\gson-2.10.1.jar" com.steam.carga.GeneradorCarga `
        $Hilos $DuracionSeg (Join-Path $evidencePath 'carga') $failureStart $failureRecovered
    if ($LASTEXITCODE -ne 0) { throw 'La carga termino con error' }

    if (-not $failure.WaitForExit(60000)) { Stop-Process -Id $failure.Id -Force; throw 'Timeout del escenario de falla' }
    $failure.Refresh()
    if ($null -ne $failure.ExitCode -and $failure.ExitCode -ne 0) {
        throw "Escenario de falla termino con codigo $($failure.ExitCode)"
    }
    if (-not (Test-Path $failureJson)) { throw 'No se genero evidencia de la falla' }

    & (Join-Path $PSScriptRoot 'run_proxy_failover.ps1') -Solicitudes 50 `
        -Salida (Join-Path $Evidencia 'proxy-failover.txt')

    $membershipDir = Join-Path $evidencePath 'membresia'
    New-Item -ItemType Directory -Force $membershipDir | Out-Null
    Copy-Item 'data\proxy-1\MEMBRESIA.json','data\proxy-2\MEMBRESIA.json' -Destination $membershipDir
    $archivos = @('data\sesiones-1\Main.json','data\sesiones-2\Main.json',
        'data\juegos-1\Main.json','data\juegos-2\Main.json',
        'data\mensajeria-1\Main.json','data\mensajeria-2\Main.json')
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $hashes = $archivos | Where-Object { Test-Path $_ } | ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $_))
        [pscustomobject]@{ Path=$_; Hash=([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-','') }
    }
    $hashes | Format-Table -AutoSize | Out-String | Set-Content (Join-Path $evidencePath 'hashes-replicas.txt')
    & cmd.exe /c 'java -version 2>&1' | Set-Content (Join-Path $evidencePath 'java-version.txt')
    git rev-parse HEAD 2>&1 | Set-Content (Join-Path $evidencePath 'git-head.txt')
    Copy-Item 'target\run\*.err.log' -Destination $evidencePath -ErrorAction SilentlyContinue
    Write-Host "[OK] Evidencia final: $evidencePath" -ForegroundColor Green
} finally {
    & (Join-Path $PSScriptRoot 'stop_all.ps1')
}
