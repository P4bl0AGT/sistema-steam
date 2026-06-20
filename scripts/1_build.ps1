param([switch]$SinPruebas)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gson = Join-Path $root 'lib\gson-2.10.1.jar'
$classes = Join-Path $root 'target\classes'
$testClasses = Join-Path $root 'target\test-classes'
$jarFile = Join-Path $root 'sistema-steam.jar'
Set-Location $root

if (-not (Get-Command java -ErrorAction SilentlyContinue) -or
    -not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'Se requiere un JDK 17 o superior (java y javac en PATH).'
}
if (-not (Test-Path $gson)) {
    New-Item -ItemType Directory -Force (Split-Path $gson) | Out-Null
    Invoke-WebRequest 'https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar' -OutFile $gson
}

foreach ($dir in @($classes, $testClasses)) {
    $full = [IO.Path]::GetFullPath($dir)
    if (-not $full.StartsWith([IO.Path]::GetFullPath((Join-Path $root 'target')))) {
        throw "Ruta de build fuera de target: $full"
    }
    if (Test-Path $full) { Remove-Item -LiteralPath $full -Recurse -Force }
    New-Item -ItemType Directory -Force $full | Out-Null
}

function Invoke-Javac([string[]]$Sources, [string]$Destino, [string]$Classpath) {
    if ($Sources.Count -eq 0) { return }
    $argFile = Join-Path $root ("target\sources-" + [guid]::NewGuid() + '.txt')
    [IO.File]::WriteAllLines($argFile, ($Sources | ForEach-Object { '"' + ($_ -replace '\\','/') + '"' }),
            [Text.UTF8Encoding]::new($false))
    try {
        & javac --release 17 -encoding UTF-8 -cp $Classpath -d $Destino "@$argFile"
        if ($LASTEXITCODE -ne 0) { throw 'javac termino con error' }
    } finally { Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue }
}

$mainSources = @(Get-ChildItem 'src\main\java' -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-Javac $mainSources $classes $gson
$testSources = @(Get-ChildItem 'src\test\java' -Recurse -Filter '*.java' -ErrorAction SilentlyContinue | ForEach-Object FullName)
Invoke-Javac $testSources $testClasses "$classes;$gson"

$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += @(Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object FullName)
$javaHome = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\jar.exe') } | Select-Object -First 1
if (-not $javaHome) { throw 'No se encontro un JDK completo con jar.exe' }
$jarExe = Join-Path $javaHome 'bin\jar.exe'
if (Test-Path $jarFile) { Remove-Item -LiteralPath $jarFile -Force }
& $jarExe --create --file $jarFile -C $classes .
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jarFile)) { throw 'No se pudo crear sistema-steam.jar' }

if (-not $SinPruebas -and $testSources.Count -gt 0) {
    & java -cp "$classes;$testClasses;$gson" com.steam.tests.PruebasComponentes
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas de componentes' }
}
Write-Host "[OK] Build Java 17: $($mainSources.Count) fuentes, $($testSources.Count) pruebas." -ForegroundColor Green
