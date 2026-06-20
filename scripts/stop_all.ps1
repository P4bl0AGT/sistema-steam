$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Stop-SteamPids
Remove-Item -LiteralPath (Join-Path $SteamRunDir 'config.txt') -Force -ErrorAction SilentlyContinue
Write-Host '[OK] Procesos registrados detenidos.' -ForegroundColor Green
