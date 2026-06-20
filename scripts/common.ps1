$SteamRoot = Split-Path $PSScriptRoot -Parent
$SteamRunDir = Join-Path $SteamRoot 'target\run'
$SteamClasspath = (Join-Path $SteamRoot 'target\classes') + ';' + (Join-Path $SteamRoot 'lib\gson-2.10.1.jar')
$SteamPorts = @(8080,8085,8081,8181,8082,8282,8083,8383,9082,9282,9182,9382,9482,9582,9483,9583,9484,9584)

function Get-SteamJava {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object FullName)
    $exe = $candidates | ForEach-Object { Join-Path $_ 'bin\java.exe' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) { throw 'No se encontro java.exe en un JDK completo' }
    return $exe
}

function Test-SteamPort([int]$Port, [int]$TimeoutMs = 250) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect('localhost', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($async)
        return $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

function Wait-SteamPort([int]$Port, [int]$TimeoutSec = 30) {
    $limit = (Get-Date).AddSeconds($TimeoutSec)
    do {
        if (Test-SteamPort $Port) { return $true }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $limit)
    return $false
}

function Start-SteamProcess([string]$Name, [string]$Class, [string[]]$Extra = @()) {
    New-Item -ItemType Directory -Force $SteamRunDir | Out-Null
    $java = Get-SteamJava
    $configMarker = Join-Path $SteamRunDir 'config.txt'
    $config = if (Test-Path $configMarker) { (Get-Content -Raw $configMarker).Trim() } else { 'config/steam.properties' }
    $arguments = @("-Dsteam.config=$config", '-cp', $SteamClasspath, $Class) + $Extra
    return Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $SteamRoot `
        -RedirectStandardOutput (Join-Path $SteamRunDir "$Name.out.log") `
        -RedirectStandardError (Join-Path $SteamRunDir "$Name.err.log") `
        -WindowStyle Hidden -PassThru
}

function Stop-SteamPids {
    $pidFile = Join-Path $SteamRunDir 'pids.json'
    if (-not (Test-Path $pidFile)) { return }
    $items = Get-Content -Raw $pidFile | ConvertFrom-Json
    foreach ($item in $items) {
        $process = Get-Process -Id ([int]$item.Pid) -ErrorAction SilentlyContinue
        if ($process) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

function Get-SteamPidItems {
    $pidFile = Join-Path $SteamRunDir 'pids.json'
    if (-not (Test-Path $pidFile)) { return @() }
    return @(Get-Content -Raw $pidFile | ConvertFrom-Json | ForEach-Object { $_ })
}

function Set-SteamPidItems($Items) {
    $pidFile = Join-Path $SteamRunDir 'pids.json'
    @($Items) | ConvertTo-Json | Set-Content $pidFile -Encoding UTF8
}

function Replace-SteamPid([string]$Name, [int]$ProcessId) {
    $items = @(Get-SteamPidItems | Where-Object Name -ne $Name)
    $items += [pscustomobject]@{ Name=$Name; Pid=$ProcessId }
    Set-SteamPidItems $items
}
