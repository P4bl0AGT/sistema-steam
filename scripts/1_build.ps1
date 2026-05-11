# build.ps1 - Descarga Gson y compila el proyecto sin Maven
# Ejecutar desde la carpeta raiz del proyecto: .\scripts\1_build.ps1

$root    = Split-Path $PSScriptRoot -Parent
$libDir  = "$root\lib"
$outDir  = "$root\out"
$gsonJar = "$libDir\gson-2.10.1.jar"
$gsonUrl = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"

Set-Location $root

# 1. Crear directorios
New-Item -ItemType Directory -Force -Path $libDir, $outDir, "$root\data" | Out-Null

# 2. Descargar Gson si no existe
if (-not (Test-Path $gsonJar)) {
    Write-Host "Descargando Gson 2.10.1..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $gsonUrl -OutFile $gsonJar -UseBasicParsing
    Write-Host "[OK] Gson descargado." -ForegroundColor Green
} else {
    Write-Host "[OK] Gson ya existe en lib/" -ForegroundColor Green
}

# 3. Recolectar todos los .java
$sources = Get-ChildItem -Recurse -Filter "*.java" -Path "$root\src" |
           Select-Object -ExpandProperty FullName

Write-Host "`nCompilando $($sources.Count) archivos Java..." -ForegroundColor Cyan

# 4. Limpiar out/ para recompilacion limpia
Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 5. Escribir lista de fuentes en un argfile (@-file) que javac entiende.
#    Usamos barras / en vez de \ porque javac trata \ como escape dentro del argfile.
$argFile = "$root\sources.txt"
$sources | ForEach-Object { $_ -replace '\\', '/' } | Set-Content $argFile -Encoding UTF8

# 6. Compilar usando el argfile
javac --release 17 -cp $gsonJar -d $outDir "@$argFile" 2>&1 | Write-Host

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERROR] Compilacion fallida. Revisa los errores arriba." -ForegroundColor Red
    Remove-Item $argFile -ErrorAction SilentlyContinue
    exit 1
}

Remove-Item $argFile -ErrorAction SilentlyContinue
Write-Host "[OK] Compilacion exitosa." -ForegroundColor Green

# 7. Crear JAR
Write-Host "`nGenerando sistema-steam.jar..." -ForegroundColor Cyan
Set-Location $outDir
jar cf "$root\sistema-steam.jar" .
Set-Location $root

if (Test-Path "$root\sistema-steam.jar") {
    Write-Host "[OK] sistema-steam.jar creado." -ForegroundColor Green
} else {
    Write-Host "[ERROR] No se pudo crear el JAR." -ForegroundColor Red
    exit 1
}

Write-Host "`nListo. Ejecuta los scripts en orden:" -ForegroundColor Yellow
Write-Host "  2_run_sesiones1.bat  (puerto 8081)" -ForegroundColor Yellow
Write-Host "  3_run_sesiones2.bat  (puerto 8181)" -ForegroundColor Yellow
Write-Host "  4_run_juegos1.bat    (puerto 8082)" -ForegroundColor Yellow
Write-Host "  5_run_juegos2.bat    (puerto 8282)" -ForegroundColor Yellow
Write-Host "  6_run_mensajeria1.bat(puerto 8083)" -ForegroundColor Yellow
Write-Host "  7_run_mensajeria2.bat(puerto 8383)" -ForegroundColor Yellow
Write-Host "  8_run_proxy.bat      (puerto 8080)" -ForegroundColor Yellow
Write-Host "  9_run_cliente.bat" -ForegroundColor Yellow
