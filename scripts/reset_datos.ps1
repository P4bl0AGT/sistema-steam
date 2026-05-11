# reset_datos.ps1
# Limpia todas las bases de datos y deja el sistema en estado inicial.
# Los servidores volvern a sembrar usuarios y juegos por defecto al arrancar.
# IMPORTANTE: ejecutar con los servidores DETENIDOS.

$root    = Split-Path $PSScriptRoot -Parent
$dataDir = "$root\data"

Write-Host "`n=== RESET DE BASE DE DATOS ===" -ForegroundColor Yellow
Write-Host "ATENCION: Asegurate de que todos los servidores esten detenidos." -ForegroundColor Red
Write-Host ""
$confirm = Read-Host "Escribi RESET para confirmar"
if ($confirm -ne "RESET") {
    Write-Host "Cancelado." -ForegroundColor Gray
    exit 0
}

New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

# ── Sesiones: sin usuarios ni sesiones (el servidor los siembra al arrancar) ──
$sesDatos = '{"usuarios":[],"sesiones":[]}'
Set-Content "$dataDir\SES_Main.txt" $sesDatos -Encoding UTF8
Set-Content "$dataDir\SES_Copy.txt" $sesDatos -Encoding UTF8
Write-Host "[OK] SES_Main.txt / SES_Copy.txt  -> vacios" -ForegroundColor Green

# ── Juegos: sin catalogo, reservas, ventas ni billeteras (el servidor los siembra) ──
$gmeDatos = '{"catalogo":[],"reservas":[],"ventas":[],"billeteras":{}}'
Set-Content "$dataDir\GME_Main.txt" $gmeDatos -Encoding UTF8
Set-Content "$dataDir\GME_Copy.txt" $gmeDatos -Encoding UTF8
Write-Host "[OK] GME_Main.txt / GME_Copy.txt  -> vacios" -ForegroundColor Green

# ── Mensajeria: sin mensajes ─────────────────────────────────────────────────
$msgDatos = '{"mensajes":[]}'
Set-Content "$dataDir\MSG_Main.txt" $msgDatos -Encoding UTF8
Set-Content "$dataDir\MSG_Copy.txt" $msgDatos -Encoding UTF8
Write-Host "[OK] MSG_Main.txt / MSG_Copy.txt  -> vacios" -ForegroundColor Green

Write-Host ""
Write-Host "=== RESET COMPLETO ===" -ForegroundColor Green
Write-Host "Al iniciar los servidores se crearan automaticamente:" -ForegroundColor Cyan
Write-Host "  Usuarios : admin/admin123  vendedor1/pass123  cliente1/pass123  cliente2/pass123"
Write-Host "  Juegos   : Counter-Strike 2 (\$29.99)  Cyberpunk 2077 (\$59.99)  Stardew Valley (\$14.99)"
Write-Host "  Billetera: cliente1=\$500  cliente2=\$200  vendedor1=\$0  admin=\$0"
