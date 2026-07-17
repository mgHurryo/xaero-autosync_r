param(
    [string]$WorldMapJar = 'XaerosWorldMap_1.37.8_Fabric_1.17.1.jar',
    [string]$MinimapJar = 'Xaeros_Minimap_23.9.7_Fabric_1.17.1.jar'
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle = Join-Path $workspace 'gradlew.bat'

& $gradle prepareXaeroTestMods "-PxaeroWorldMapJar=$WorldMapJar" "-PxaeroMinimapJar=$MinimapJar"
if ($LASTEXITCODE -ne 0) {
	throw "Failed to prepare selected Xaero test mods"
}

$serverDir = Join-Path $workspace 'run\server'
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null

$eulaPath = Join-Path $serverDir 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath)) {
    Set-Content -LiteralPath $eulaPath -Encoding ASCII -Value @(
        '# Review https://aka.ms/MinecraftEULA before changing this value.'
        'eula=false'
    )
}

$propertiesPath = Join-Path $serverDir 'server.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    Set-Content -LiteralPath $propertiesPath -Encoding ASCII -Value @(
        'server-ip=127.0.0.1'
        'server-port=25565'
        'online-mode=false'
        'motd=Xaero Map Sync local integration'
        'view-distance=8'
        'simulation-distance=8'
        'enable-command-block=true'
        'spawn-protection=0'
    )
}

Write-Host "Prepared isolated runs under $workspace\run"
Write-Host "Review the EULA, then set run\server\eula.txt to eula=true before starting."
