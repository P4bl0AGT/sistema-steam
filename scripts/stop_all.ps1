$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
New-Item -ItemType Directory -Force $SteamRunDir | Out-Null
'shutdown' | Set-Content (Join-Path $SteamRunDir 'shutdown.marker') -Encoding ASCII
Stop-SteamPids
Remove-Item -LiteralPath (Join-Path $SteamRunDir 'config.txt') -Force -ErrorAction SilentlyContinue
Write-Host '[OK] Procesos registrados detenidos.' -ForegroundColor Green
