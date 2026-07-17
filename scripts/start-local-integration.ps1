param()

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$eulaPath = Join-Path $workspace 'run\server\eula.txt'
$propertiesPath = Join-Path $workspace 'run\server\server.properties'

if (-not (Test-Path -LiteralPath $eulaPath) -or -not (Select-String -LiteralPath $eulaPath -Pattern '^eula=true$' -Quiet)) {
    throw 'Minecraft EULA has not been accepted in run\server\eula.txt'
}
$propertiesExist = Test-Path -LiteralPath $propertiesPath
$serverIsLocal = $propertiesExist -and (Select-String -LiteralPath $propertiesPath -Pattern '^server-ip=127\.0\.0\.1$' -Quiet)
if (-not $serverIsLocal) {
    throw 'Local integration server must remain bound to 127.0.0.1'
}

$gradle = Join-Path $workspace 'gradlew.bat'
$serverCommand = '"' + $gradle + '" runServer --no-daemon'
$clientACommand = '"' + $gradle + '" runClient --no-daemon'
$clientBCommand = '"' + $gradle + '" runClientB --no-daemon'
Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $serverCommand -WorkingDirectory $workspace -WindowStyle Hidden
Start-Sleep -Seconds 8
Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $clientACommand -WorkingDirectory $workspace -WindowStyle Hidden
Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $clientBCommand -WorkingDirectory $workspace -WindowStyle Hidden

Write-Host 'Started the local server and two isolated clients (MapSyncA and MapSyncB).'
