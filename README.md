# Xaero Map Sync

[English](README.md) | [简体中文](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.17.1-green.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.3-blue.svg)](https://fabricmc.net/)

Xaero Map Sync is a shared map and waypoint synchronization mod for Minecraft Java Edition 1.17.1 Fabric servers. Clients upload map tiles that Xaero has already rendered; the server validates, merges, persists, and distributes them. Players continue to view maps and manage waypoints through the native Xaero World Map and Xaero Minimap interfaces.

The current release is `3.0.0-alpha.6`, with network protocol `v11` and map storage format `v6`.

## Compatibility

| Component | Version or requirement |
| --- | --- |
| Minecraft | `1.17.1` |
| Fabric Loader | `>= 0.19.3` |
| Fabric API | `0.46.1+1.17` |
| Xaero's World Map | `1.25.1` |
| Xaero's Minimap | `22.11.1` |
| Mod runtime | Java `16+`; local run tasks use Java 17 |
| Build environment | JDK 21; sources compile to the Java 16 target |

Xaero integration uses reflective adapters. If the detected version or reflection contract does not match, the corresponding adapter is disabled instead of writing an unknown format.

## Core capabilities

- Synchronizes Xaero-rendered 16x16-chunk map tiles across clients.
- Does not generate maps on the server by default. `map.sync.server_render.enabled=true` is an explicit diagnostic or disaster-recovery option.
- Uses revisions, content hashes, Merkle trees, catalog epochs, and sync generations for incremental synchronization without full-map downloads or stale-response contamination.
- Coalesces tiles for two seconds and publishes complete square patches with side lengths from 1 to 32 inside each Xaero 32x32 region.
- Transfers large patches with zlib compression, CRC32 fragments, ACK/NACK, timeouts, and bounded exponential backoff. A failed transfer does not clear existing Xaero map data.
- Keeps native local Xaero output authoritative for loaded areas. Remote data only fills unfinished local areas and is committed atomically by region.
- Lets online clients participate in gap recovery. Archived remote tiles use fill-only merging and never overwrite an existing server body.
- Supports PUBLIC and TEAM waypoints, immutable locking, deletion tombstones, regional permissions, quotas, and audit logs.
- Uses mixins to collect block, TNT, explosion, piston, and light activity, maintaining STORM/COOLDOWN state for 8x8-chunk regions and adapting background work to MSPT.
- Provides administrator commands for bandwidth budgets, task suspension, regional control, tracing, and storage maintenance.

## How synchronization works

1. On startup, the server loads tiles, indexes, exploration records, the dirty queue, waypoints, and regional permissions. A missing index can be rebuilt from `.tile` files.
2. A connecting client exchanges protocol, map format, and Xaero adapter versions. Only accepted handshakes participate in synchronization.
3. Across multiple ticks, the client scans Xaero's loaded area and local archive. Stable content is hashed, deduplicated, and uploaded.
4. The server validates the handshake, dimension, format, renderability, size, merge mode, and rate limits, atomically persists the tile body, and updates the index, Merkle root, and patch catalog.
5. The client compares Merkle nodes and requests missing tiles or patches. Verified downloads are applied in Xaero-region batches.
6. The server owns waypoint revisions, visibility, creator/team identity, and tombstones, then sends snapshots or incremental events only to clients allowed to view them.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`src/main/java/.../XaeroMapsync_r.java`](src/main/java/cn/net/rms/xaeromapsync_r/XaeroMapsync_r.java) | Common mod entry point for configuration, server network receivers, and server lifecycle registration. |
| [`src/main/java/.../XaeroMapsyncClient.java`](src/main/java/cn/net/rms/xaeromapsync_r/XaeroMapsyncClient.java) | Client entry point for Xaero detection, client sync, network receivers, and waypoint UI integration. |
| `src/main/java/.../client/` | Client Merkle diffing, tile/patch caches, transfer management, atomic application, gap detection, and GUI integration. |
| `src/main/java/.../server/` | Server lifecycle, scheduling, bandwidth budgets, transfers, gap recovery, regional activity, dirty processing, access control, auditing, and commands. |
| `src/main/java/.../network/` | Handshake, map, patch, tile, transfer, and waypoint payloads and codecs. |
| `src/main/java/.../map/` | v6 tile model, hashing, persistent index, patch catalog, Merkle tree, and diagnostic renderer. |
| `src/main/java/.../waypoint/` | Shared waypoint model, PUBLIC/TEAM visibility, palette validation, and persistence. |
| `src/main/java/.../xaero/` | Reflective Xaero World Map/Minimap adapters and waypoint bridges. |
| `src/main/java/.../mixin/` | Server activity signals and injection points for Xaero's native waypoint screens. |
| `src/main/resources/` | Fabric metadata, mixin configuration, icon, and Chinese/English UI strings. |
| `src/test/java/` | Tests for maps, networking, clients, servers, permissions, activity, dirty processing, and Xaero adapters. |
| `docs/` | Atomic map sync, release/rollback, pull request, and three-process local integration documentation. |
| `scripts/` | PowerShell scripts that prepare and start one server and two isolated clients. |
| `.github/workflows/` | Gradle build, pinned Xaero JAR verification, and Qodana static analysis. |
| `xaeromap-origin/` | Git-ignored pinned Xaero JARs used by local integration runs. |

## Installation and use

Install Fabric Loader, Fabric API, and this mod on the server. Clients that view the shared map or manage shared waypoints also need the pinned Xaero World Map and Xaero Minimap versions.

1. Put the built mod JAR in the server and client `mods/` directories.
2. Start once and review the generated server and client configuration files.
3. Connect a client and confirm that the protocol handshake succeeds in the logs.
4. Explore normally. After Xaero finishes rendering, eligible map tiles are uploaded automatically.

The mod never uploads private waypoints automatically. To share one, select exactly one existing waypoint in Xaero's native waypoint manager and click `Public` or `Team`. A shared waypoint moves into the dedicated `Shared Waypoints` set and displays a lock icon. Delete and recreate it if its contents must change. Any player who can see a shared waypoint may delete it after confirming the action.

## Configuration

The server configuration is generated at `config/xaero-mapsync_r.properties`.

| Group | Important defaults | Purpose |
| --- | --- | --- |
| Protocol | `protocol.version=11`, `map.format.version=6` | Compatibility values managed by the current implementation. |
| Network | `network.compression=zlib`, `network.max_packet_bytes=32768` | Compression, packet size, and per-player/global bandwidth budgets. |
| Map sync | `map.sync.enabled=true`, `map.sync.shadow_mode=false` | Enables synchronization and optional shadow mode. |
| Server rendering | `map.sync.server_render.enabled=false` | Accept client Xaero tiles only by default. |
| Scheduling | `tasks.normal_tick_budget_ms=2`, `tasks.high_load_mspt_threshold=45` | Adapts background work to MSPT and writer queue capacity. |
| Regional activity | `activity.stable_ticks=200`, `activity.storm_cooldown_ticks=100` | Controls STORM, COOLDOWN, and stable processing timing. |
| Waypoints | `waypoints.allow_player_upload=true`, `256` per player, `4096` total | Controls sharing and quotas. |

The client configuration is stored at `config/xaero-mapsync_r-client.properties`:

```properties
mapSyncEnabled=true
publicWaypointsEnabled=true
notificationsEnabled=true
```

## Data and persistence

The server world directory `<world>/xaero-mapsync_r/` contains:

| File or directory | Content |
| --- | --- |
| `tiles-v6/` | Atomic `.tile` bodies organized by dimension and chunk coordinates. |
| `map_patch_index-v6.json` | Tile revisions, content hashes, sampler version, and map index. |
| `public_waypoints.json` | Active shared waypoints and deletion tombstones. |
| `explored_chunks.json` | Explored chunk index. |
| `dirty_chunks.json` | Recoverable dirty work queue. |
| `region_permissions.json` | Regional team allow, deny, and disable rules. |
| `access_audit.jsonl` | Append-only audit trail for permission and waypoint operations. |

The client patch cache is stored at `config/xaero-mapsync_r-client-patches-v6/`. Back up both the server world data and client `XaeroWorldMap` data before upgrading or rolling back.

## Administrator commands

All `/sharedmap` commands require permission level 2.

```text
/sharedmap status
/sharedmap status <player>
/sharedmap trace <player> <seconds>
/sharedmap save
/sharedmap pause
/sharedmap resume
/sharedmap flush
/sharedmap bandwidth <bytesPerTick>
/sharedmap bandwidth player <bytesPerTick>
/sharedmap bandwidth global <bytesPerTick>
/sharedmap rebuild-loaded
/sharedmap region status
/sharedmap region pause
/sharedmap region resume
/sharedmap region mark-storm
/sharedmap region clear-storm
/sharedmap access status
/sharedmap access grant <team>
/sharedmap access revoke <team>
/sharedmap access clear <team>
/sharedmap access disable
/sharedmap access enable
/sharedmap access reset
/sharedmap waypoint list
/sharedmap waypoint inspect <uuid>
/sharedmap waypoint delete <uuid>
```

`rebuild-loaded` generates diagnostic tiles from terrain currently loaded on the server. It is an explicit maintenance action and does not mean that normal synchronization renders server-side maps automatically.

## Build, test, and local integration

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

Artifacts are written to `build/libs/`. The current test tree contains 51 test source files and 277 JUnit `@Test` cases covering protocol payloads, transfer assembly, compression, Merkle trees, patches, tile storage, client coordination, server scheduling, permissions, activity state, dirty queues, and Xaero reflection adapters.

The local end-to-end harness starts one server and two isolated clients:

```powershell
.\scripts\prepare-local-integration.ps1
# Review and accept the Minecraft EULA, then set run/server/eula.txt to eula=true
.\scripts\start-local-integration.ps1
```

The server is bound to `127.0.0.1` and uses offline-mode test identities. Never expose it to a network. See [`docs/local-integration-test.md`](docs/local-integration-test.md) for the full acceptance sequence.

CI uses JDK 21 to validate the Gradle Wrapper, download and SHA-256-check the pinned Xaero JARs, run `gradle build`, and execute Qodana JVM static analysis.

## Security boundaries and limitations

- World saves, entities, containers, complete block NBT, and unrelated data are not uploaded.
- Private waypoints are submitted only after an explicit share action; PRIVATE waypoints cannot be uploaded.
- TEAM ownership comes from the server scoreboard. Client-supplied creator and team fields are not trusted.
- Map uploads must pass handshake, dimension, format, renderability, size, and rate-limit validation.
- Background map work reads only Minecraft objects already loaded on the main thread and does not force-load chunks for synchronization.
- Shared waypoints are immutable. Deletion does not require creator ownership; the server checks the known revision and the client asks for confirmation before submitting it.
- The pinned Xaero versions are a compatibility boundary. Upgrading Xaero requires an adapter update and full regression testing.
- This is an alpha release. Validate it with backups or in staging before a gradual rollout.

## Related documentation

- [`docs/atomic-map-sync-v6.md`](docs/atomic-map-sync-v6.md): protocol v11 / map format v6 lifecycle, observability, release, and rollback.
- [`docs/local-integration-test.md`](docs/local-integration-test.md): three-process local integration and manual acceptance checklist.
- [`docs/pr-description.md`](docs/pr-description.md): pull request description for native client tile publication.
- [`docs/pr-atomic-map-sync-v6.md`](docs/pr-atomic-map-sync-v6.md): pull request description for the gap recovery protocol change.

## Rollback

1. Stop the server and all clients.
2. Restore an older mod JAR that is compatible with the existing v6 storage.
3. If client-side Xaero map writes are corrupted, restore the affected `XaeroWorldMap` directory from the pre-upgrade backup.
4. Do not delete server `tiles-v6/`, `public_waypoints.json`, or other world data unless a confirmed storage incompatibility has a migration plan.

See [`docs/atomic-map-sync-v6.md`](docs/atomic-map-sync-v6.md) for the detailed release and rollback checklist.

## License

This project is licensed under the [MIT License](LICENSE).

Copyright (c) 2025 CXU
