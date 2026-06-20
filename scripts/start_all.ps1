param([int]$TimeoutSec = 35, [string]$Config = 'config/steam.properties')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-Location $SteamRoot

$occupied = @($SteamPorts | Where-Object { Test-SteamPort $_ 100 })
if ($occupied.Count -gt 0) { throw "Puertos Steam ocupados: $($occupied -join ', ')" }
if (-not (Test-Path 'target\classes\com\steam\proxy\Proxy.class')) {
    & (Join-Path $PSScriptRoot '1_build.ps1')
}
New-Item -ItemType Directory -Force $SteamRunDir | Out-Null
$Config | Set-Content (Join-Path $SteamRunDir 'config.txt') -Encoding ASCII

$specs = @(
    @('proxy-1','com.steam.proxy.Proxy','1','8080'),
    @('proxy-2','com.steam.proxy.Proxy','2','8085'),
    @('ses-1','com.steam.servidores.svSesiones','1'),
    @('ses-2','com.steam.servidores.svSesiones','2'),
    @('jue-1','com.steam.servidores.svJuegos','1'),
    @('jue-2','com.steam.servidores.svJuegos','2'),
    @('msg-1','com.steam.servidores.svMensajeria','1'),
    @('msg-2','com.steam.servidores.svMensajeria','2')
)
$started = @()
try {
    foreach ($spec in $specs) {
        $extra = @($spec[2..($spec.Length - 1)])
        $process = Start-SteamProcess $spec[0] $spec[1] $extra
        $started += [pscustomobject]@{ Name=$spec[0]; Pid=$process.Id }
    }
    $started | ConvertTo-Json | Set-Content (Join-Path $SteamRunDir 'pids.json') -Encoding UTF8
    foreach ($port in $SteamPorts) {
        if (-not (Wait-SteamPort $port $TimeoutSec)) { throw "Timeout esperando puerto $port" }
    }
    Start-Sleep -Seconds 3
    Write-Host "[OK] Sistema iniciado: 8 JVM, 2 proxies, 18 puertos." -ForegroundColor Green
    $started | Format-Table -AutoSize
} catch {
    foreach ($item in $started) { Stop-Process -Id $item.Pid -Force -ErrorAction SilentlyContinue }
    throw
}
