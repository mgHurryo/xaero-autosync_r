param()

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$eulaPath = Join-Path $workspace 'run\server\eula.txt'
$propertiesPath = Join-Path $workspace 'run\server\server.properties'

if (-not (Test-Path -LiteralPath $eulaPath) -or -not (Select-String -LiteralPath $eulaPath -Pattern '^eula=true$' -Quiet)) {
    throw 'Minecraft EULA has not been accepted in run\server\eula.txt'
}
if (-not (Test-Path -LiteralPath $propertiesPath)
        -or -not (Select-String -LiteralPath $propertiesPath -Pattern '^server-ip=127\.0\.0\.1$' -Quiet)) {
    throw 'Local integration server must remain bound to 127.0.0.1'
}

$gradle = Join-Path $workspace 'gradlew.bat'
Start-Process -FilePath $gradle -ArgumentList 'runServer' -WorkingDirectory $workspace -WindowStyle Hidden
Start-Sleep -Seconds 8
Start-Process -FilePath $gradle -ArgumentList 'runClient' -WorkingDirectory $workspace
Start-Process -FilePath $gradle -ArgumentList 'runClientB' -WorkingDirectory $workspace

Write-Host 'Started the local server and two isolated clients (MapSyncA and MapSyncB).'
