param(
    [string]$Password = 'changeit',
    [int]$ValidezDias = 365
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$java = Get-SteamJava
$keytool = Join-Path (Split-Path $java) 'keytool.exe'
if (-not (Test-Path $keytool)) { throw "No se encontro keytool: $keytool" }
$certDir = Join-Path $SteamRoot 'certs'
New-Item -ItemType Directory -Force $certDir | Out-Null
$keyStore = Join-Path $certDir 'steam-demo.p12'
$trustStore = Join-Path $certDir 'steam-demo-trust.p12'
$certificate = Join-Path $certDir 'steam-demo.crt'
foreach ($file in @($keyStore,$trustStore,$certificate)) {
    if (Test-Path $file) { Remove-Item -LiteralPath $file -Force }
}
& $keytool -genkeypair -alias steam-demo -keyalg RSA -keysize 3072 -validity $ValidezDias `
    -dname 'CN=localhost, OU=ICI-4344, O=PUCV, C=CL' -ext 'SAN=dns:localhost,ip:127.0.0.1' `
    -storetype PKCS12 -keystore $keyStore -storepass $Password -keypass $Password -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Fallo genkeypair' }
& $keytool -exportcert -alias steam-demo -keystore $keyStore -storepass $Password `
    -rfc -file $certificate
if ($LASTEXITCODE -ne 0) { throw 'Fallo exportcert' }
& $keytool -importcert -alias steam-demo -file $certificate -keystore $trustStore `
    -storetype PKCS12 -storepass $Password -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Fallo importcert' }
Write-Host "[OK] Certificados demo generados en $certDir" -ForegroundColor Green
Write-Host 'Use -Dsteam.config=config/steam-tls.properties y mantenga la clave fuera del repositorio en produccion.'
