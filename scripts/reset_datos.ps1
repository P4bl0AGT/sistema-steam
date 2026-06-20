param([switch]$Force)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$data = [IO.Path]::GetFullPath((Join-Path $root 'data'))
if (-not $Force) {
    $answer = Read-Host 'Escriba RESET para borrar el estado de demostracion'
    if ($answer -ne 'RESET') { Write-Host 'Cancelado.'; exit 0 }
}
New-Item -ItemType Directory -Force $data | Out-Null
$targets = @(Get-ChildItem -LiteralPath $data -Force -ErrorAction SilentlyContinue)
foreach ($target in $targets) {
    $full = [IO.Path]::GetFullPath($target.FullName)
    if (-not $full.StartsWith($data + [IO.Path]::DirectorySeparatorChar)) {
        throw "Ruta fuera de data: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}
Write-Host '[OK] Datos eliminados. Cada nodo sembrara su almacenamiento independiente.' -ForegroundColor Green
