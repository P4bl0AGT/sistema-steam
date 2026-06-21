param(
    [int]$Solicitudes = 30,
    [string]$Salida = 'evidencia\proxy-failover.txt'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-Location $SteamRoot
$proxy = Get-SteamPidItems | Where-Object Name -eq 'proxy-1' | Select-Object -First 1
if (-not $proxy) { throw 'Proxy-1 no esta registrado en target/run/pids.json' }
Stop-Process -Id ([int]$proxy.Pid) -Force
$limit = (Get-Date).AddSeconds(10)
while ((Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) -and (Get-Date) -lt $limit) {
    Start-Sleep -Milliseconds 200
}
try {
    New-Item -ItemType Directory -Force (Split-Path (Join-Path $SteamRoot $Salida)) | Out-Null
    $text = & java -cp "target\classes;target\test-classes;lib\gson-2.10.1.jar" `
            com.steam.tests.PruebaDisponibilidad $Solicitudes 2>&1
    $text | Set-Content (Join-Path $SteamRoot $Salida) -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la continuidad con Proxy-1 caido' }
} finally {
    if (-not (Test-SteamPort 8080 100)) {
        $nuevo = Start-SteamProcess 'proxy-1' 'com.steam.proxy.Proxy' @('1','8080')
        Replace-SteamPid 'proxy-1' $nuevo.Id
        if (-not (Wait-SteamPort 8080 20)) { throw 'Proxy-1 no reingreso' }
    }
}
Write-Host "[OK] Failover de proxy: $Solicitudes solicitudes sin perdida; Proxy-1 reingreso." -ForegroundColor Green
