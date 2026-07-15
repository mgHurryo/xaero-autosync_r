# Local integration test

The local harness uses three isolated Loom run directories:

```text
run/server
run/client-a
run/client-b
```

Both clients load the pinned reference versions from `xaeromap-origin`:

- Xaero World Map 1.25.1 for Fabric 1.17.1
- Xaero Minimap 22.11.1 for Fabric 1.17.1

## Prepare

Run:

```powershell
.\scripts\prepare-local-integration.ps1
```

Review the Minecraft EULA and explicitly change `run/server/eula.txt` to `eula=true`. The generated server is offline-mode only for the two local development identities and is bound to `127.0.0.1`; do not expose it to a network.

## Start

```powershell
.\scripts\start-local-integration.ps1
```

Connect both clients to `127.0.0.1:25565`. The clients use independent names, configs, Xaero caches and Map Sync revision stores.

## Acceptance sequence

1. Confirm both clients complete protocol 2 handshake.
2. Explore a new loaded area with client A and verify client B receives it without visiting.
3. Build and remove a visible surface platform; wait for STABLE processing and verify both transitions on B.
4. Create, edit and delete a PUBLIC waypoint on A; verify B and Xaero Minimap follow the tombstone lifecycle.
5. Put both players on one scoreboard team, create a TEAM waypoint, then move B to another team and verify the waypoint is removed after the visibility refresh.
6. Import one local Xaero waypoint through selection, preview and confirmation; verify no unselected or managed `[xms-*]` waypoint is uploaded.
7. Disconnect B, change map and waypoint state with A, reconnect B and verify only changed leaves transfer.
8. Restart the server and verify explored chunks, tile revisions, dirty jobs, access rules and tombstones survive.
9. Run piston and TNT activity near one 8x8 region and verify `/sharedmap region status` enters STORM/COOLDOWN without force-loading chunks.
10. Exercise `access grant/revoke/disable/enable/reset` and verify unauthorized waypoint mutations fail and `access_audit.jsonl` records the decision.
11. Capture `/sharedmap status` before, during and after load for average/P95 MSPT, task time and bandwidth.

The original `xaeromap-origin/Data` directory is reference input only. Do not point both clients at the same live Xaero data directory.
