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

1. Confirm both clients complete protocol 8 handshake.
2. Explore a new loaded area with client A. Verify A keeps Xaero's native local rendering without black holes or server replacement, then verify B receives A's uploaded native tile without visiting.
3. Build and remove a visible surface platform; wait for STABLE processing and verify both transitions on B.
4. Create a normal waypoint with Xaero on A, select it in Xaero's native waypoint manager, click `Public`, and verify B receives it while A keeps the exact same native object without a duplicate.
5. Select the shared waypoint on both clients and verify a rendered lock icon appears before its name without changing the name text, the status says `Public`, and Xaero's mutation buttons are gray. Verify `Delete shared` remains enabled and local edits on B are restored from the server without creating a duplicate.
6. Click `Delete shared` on B, verify the public-map warning appears, confirm it, and verify the tombstone removes the synchronized waypoint from both clients.
7. Confirm the Team button is absent while A has no scoreboard team. Put both players on one scoreboard team, select another native Xaero waypoint on A, click `Team`, and verify the status says `Team`; then move B to another team and verify the waypoint is removed after the visibility refresh.
8. Disconnect B, change map and waypoint state with A, reconnect B and verify only changed leaves transfer. Confirm old or duplicated Merkle responses do not complete the new sync generation.
9. Restart the server and verify explored chunks, tile revisions, complete patch manifests, dirty jobs, access rules, locked waypoint identity and tombstones survive. Remove only the test server's `map_patch_index-v6.json`, restart again, and verify the index is rebuilt from existing `.tile` files before clients connect.
10. Run piston and TNT activity near one 8x8 region and verify `/sharedmap region status` enters STORM/COOLDOWN without force-loading chunks.
11. Exercise `access grant/revoke/disable/enable/reset` and verify unauthorized waypoint mutations fail and `access_audit.jsonl` records the decision.
12. Open and close Xaero's native waypoint manager on both clients after each mutation; verify visible sharing controls elastically fill the row, hidden Team leaves no empty slot, English labels fit, clicks are not consumed by the waypoint list, the background is not distorted, and neither client crashes.
13. Fly across already cached terrain on A and confirm local Xaero generation remains authoritative. Verify changed native content hashes upload again, identical hashes are deduplicated, and oversized or rejected uploads fall back without force-loading chunks.
14. Zoom and pan B across a large synchronized area. Verify tiles are applied in 32x32 Xaero region batches with no persistent checkerboard holes and with water, grass, leaves, glass and biome colors matching a locally generated reference.
15. Capture `/sharedmap status` before, during and after load for average/P95 MSPT, task time, pending dirty work and bandwidth.

The original `xaeromap-origin/Data` directory is reference input only. Do not point both clients at the same live Xaero data directory.
