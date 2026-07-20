# Xaero compatibility for Minecraft 1.17.1

This project supports every Xaero Fabric file published with an exact Minecraft `1.17.1` tag:

| Mod | Supported versions | Published Fabric files |
| --- | --- | ---: |
| Xaero's World Map | `1.14.5.2` through `1.37.8` | 74 |
| Xaero's Minimap | `21.12.5.1` through `23.9.7` | 80 |

All compatibility discovery, CI fixtures, and local integration inputs are Fabric JARs whose metadata contains `fabric.mod.json` and declares Minecraft `1.17.1`. The upstream CDN domain is only a file host and is not a loader marker; no Forge-loader artifact is used.

## Runtime strategy

The adapter first checks the published Minecraft 1.17.1 version boundary and then validates the installed JAR's reflection contract. A contract mismatch disables only the affected Xaero bridge for the current session before any mutation.

World Map releases form nine relevant binary signature families. The adaptive runtime handles legacy colour arrays, optional load gates and terrain markers, both two- and three-argument surface tile lookup, legacy `BiomeKey` and native Minecraft biome keys, and all four overlay constructors. Minimap uses one stable waypoint model; releases before `22.8.0` resolve the mod singleton through `AXaeroMinimap.INSTANCE` because the session accessor was not yet present.

## Verification

`XaeroCompatibilityTest` enumerates all 154 published Fabric versions. `XaeroBinaryCompatibilityTest` scans every supplied fixture and validates its Fabric metadata and the complete reflection contract without executing fixture code. The `3.0.0-alpha.7` release was checked against all 74 World Map and 80 Minimap JARs published for Minecraft 1.17.1. CI keeps 12 World Map and 3 Minimap representatives, including the transitional World Map `1.17.3` API, and verifies their fixed SHA-256 hashes. This binary-contract matrix does not replace an in-game end-to-end run of every upstream version.

For a local runtime check, place the desired Fabric 1.17.1 pair in `xaeromap-origin` and select it through `scripts/prepare-local-integration.ps1`. The default pair is World Map `1.37.8` and Minimap `23.9.7`.
