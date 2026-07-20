# Xaero Map Sync

[English](README.md) | [简体中文](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.17.1-green.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.3-blue.svg)](https://fabricmc.net/)

Xaero Map Sync 是面向 Minecraft Java Edition 1.17.1 Fabric 服务器的共享地图与路径点同步 Mod。客户端把 Xaero 已经渲染完成的地图 tile 上传到服务器，服务器负责校验、合并、持久化和分发；玩家仍然通过 Xaero World Map 与 Xaero Minimap 的原生界面查看地图和管理路径点。

当前版本为 `3.0.0-alpha.7`，网络协议为 `v11`，地图存储格式为 `v6`。

## 兼容性

| 组件 | 版本或要求 |
| --- | --- |
| Minecraft | `1.17.1` |
| Fabric Loader | `>= 0.19.3` |
| Fabric API | `0.46.1+1.17` |
| Xaero's World Map | `1.14.5.2` 至 `1.37.8`（74 个 Fabric 版本） |
| Xaero's Minimap | `21.12.5.1` 至 `23.9.7`（80 个 Fabric 版本） |
| Mod 运行时 | Java `16+`；本地运行任务固定使用 Java 17 |
| 构建环境 | JDK 21；源码以 Java 16 目标版本编译 |

本项目通过自适应反射支持所有面向 Minecraft 1.17.1 发布的 Xaero Fabric 版本。版本或反射契约不匹配时，对应适配器会关闭，避免向未知格式写入数据。

## 核心能力

- 在多个客户端之间同步 Xaero 已渲染的 16x16 chunk 地图 tile。
- 服务端默认不生成地图，只接受客户端 Xaero 数据；`map.sync.server_render.enabled=true` 仅用于显式诊断或灾难恢复。
- 使用 revision、内容哈希、Merkle 树、catalog epoch 和同步代次进行增量同步，避免全图重复下载和迟到响应污染新会话。
- 在 2 秒窗口内聚合 tile，按 Xaero 32x32 region 生成边长 1–32 的完整正方形 patch。
- 使用 zlib、CRC32 分片、ACK/NACK、超时和有限指数退避传输大型 patch；失败不会清空已有 Xaero 地图。
- 客户端优先保留本机 Xaero 对已加载区域的原生结果，远端数据只补充本机未完成区域，并按 region 原子提交。
- 在线客户端可参与缺口恢复；远处历史 tile 采用 fill-only 合并，不覆盖服务端已有内容。
- 支持 PUBLIC/TEAM 共享路径点、不可变锁定、删除 tombstone、区域权限、数量限制和审计日志。
- 通过经过验证的反射契约支持 Minecraft 1.17.1 已发布的 Xaero World Map 与 Xaero Minimap Fabric 版本范围。
- 通过 mixin 采集方块、TNT、爆炸、活塞和光照活动，维护 8x8 chunk 区域的 STORM/COOLDOWN 状态并根据 MSPT 调节后台任务。
- 提供带宽预算、任务暂停、区域控制、追踪和存储维护等管理员命令。

## 工作流程

1. 服务端启动时加载 tile、索引、探索记录、dirty 队列、路径点和区域权限；索引丢失时可从 `.tile` 文件重建。
2. 客户端连接后交换协议版本、地图格式和 Xaero 适配器版本。只有握手通过的客户端才参与同步。
3. 客户端跨 tick 扫描 Xaero 当前加载区域和本地归档，稳定内容经过哈希去重后上传。
4. 服务端校验握手、维度、格式、可渲染性、大小、合并模式和限流，原子写入 tile body，更新索引、Merkle 根和 patch catalog。
5. 客户端比较 Merkle 节点并请求缺失 tile 或 patch；下载完成、校验通过后按 Xaero region 批量应用。
6. 路径点由服务端维护 revision、可见性、创建者、队伍和 tombstone，并向有权查看的客户端发送快照或增量事件。

## 仓库结构

| 路径 | 作用 |
| --- | --- |
| [`src/main/java/.../XaeroMapsync_r.java`](src/main/java/cn/net/rms/xaeromapsync_r/XaeroMapsync_r.java) | 通用 Mod 入口：注册配置、服务端网络接收器和服务端生命周期。 |
| [`src/main/java/.../XaeroMapsyncClient.java`](src/main/java/cn/net/rms/xaeromapsync_r/XaeroMapsyncClient.java) | 客户端入口：检测 Xaero、注册客户端同步、网络接收器和路径点界面集成。 |
| `src/main/java/.../client/` | 客户端 Merkle 差异、tile/patch 缓存、传输管理、原子应用、缺口检测和 GUI 集成。 |
| `src/main/java/.../server/` | 服务端生命周期、调度、带宽预算、传输、缺口恢复、区域活动、dirty 处理、权限、审计和命令。 |
| `src/main/java/.../network/` | 握手、地图、patch、tile、传输和路径点协议载荷及编解码。 |
| `src/main/java/.../map/` | v6 tile 模型、哈希、持久化索引、patch catalog、Merkle 树和诊断渲染器。 |
| `src/main/java/.../waypoint/` | 共享路径点模型、PUBLIC/TEAM 可见性、调色板校验和持久化。 |
| `src/main/java/.../xaero/` | Xaero World Map/Minimap 的反射适配与路径点桥接。 |
| `src/main/java/.../mixin/` | 服务端活动信号与 Xaero 原生路径点界面的注入点。 |
| `src/main/resources/` | Fabric 元数据、mixin 配置、图标和中英文界面文本。 |
| `src/test/java/` | 地图、网络、客户端、服务端、权限、活动、dirty 和 Xaero 适配器测试。 |
| `docs/` | 原子地图同步、发布/回滚、PR 说明和本地三进程联调文档。 |
| `scripts/` | 准备并启动一个服务端与两个隔离客户端的 PowerShell 脚本。 |
| `.github/workflows/` | Gradle 构建、代表性 Xaero Fabric JAR 校验和 Qodana 静态检查。 |
| `xaeromap-origin/` | 兼容测试与本地联调所需的受支持 Xaero Fabric JAR；该目录被 Git 忽略。 |

## 安装与使用

服务端安装 Fabric Loader、Fabric API 和本 Mod。需要查看共享地图或操作共享路径点的客户端还必须安装受支持的 Minecraft 1.17.1 Fabric 版 Xaero World Map 与 Xaero Minimap。

1. 将构建出的 Mod JAR 放入服务端和客户端的 `mods/`。
2. 首次启动后检查服务端和客户端配置。
3. 客户端连接后确认日志中的协议握手成功。
4. 正常探索世界；Xaero 完成渲染后，客户端会自动上传允许同步的地图 tile。

本 Mod 不会自动上传私人路径点。共享路径点时，在 Xaero 原生路径点管理页中只选择一个现有路径点，然后点击 `公开共享` 或 `队伍共享`。共享后的点会移入独立的 `共享路径点` / `Shared Waypoints` 集合并显示锁图标；如需修改，请先删除共享再重新创建。任何能够看到该路径点的玩家都可在确认后删除它。

## 配置

服务端配置位于 `config/xaero-mapsync_r.properties`，首次启动时自动生成。主要配置组如下：

| 配置组 | 关键默认值 | 说明 |
| --- | --- | --- |
| 协议 | `protocol.version=11`、`map.format.version=6` | 兼容性参数，由当前实现固定管理。 |
| 网络 | `network.compression=zlib`、`network.max_packet_bytes=32768` | 压缩、单包大小和玩家/全局带宽预算。 |
| 地图同步 | `map.sync.enabled=true`、`map.sync.shadow_mode=false` | 启用同步及 shadow mode。 |
| 服务端渲染 | `map.sync.server_render.enabled=false` | 默认只接收客户端 Xaero tile。 |
| 调度 | `tasks.normal_tick_budget_ms=2`、`tasks.high_load_mspt_threshold=45` | 按 MSPT 和 writer 队列动态限制后台工作。 |
| 活动区域 | `activity.stable_ticks=200`、`activity.storm_cooldown_ticks=100` | 控制 STORM、COOLDOWN 和稳定处理时机。 |
| 路径点 | `waypoints.allow_player_upload=true`、每玩家 `256`、全局 `4096` | 控制共享入口和数量上限。 |

客户端配置位于 `config/xaero-mapsync_r-client.properties`：

```properties
mapSyncEnabled=true
publicWaypointsEnabled=true
notificationsEnabled=true
```

## 数据与持久化

服务端世界目录 `<world>/xaero-mapsync_r/` 包含：

| 文件或目录 | 内容 |
| --- | --- |
| `tiles-v6/` | 按维度和 chunk 坐标保存的原子 `.tile` body。 |
| `map_patch_index-v6.json` | tile revision、内容哈希、采样器版本和地图索引。 |
| `public_waypoints.json` | 活跃共享路径点和删除 tombstone。 |
| `explored_chunks.json` | 已探索 chunk 索引。 |
| `dirty_chunks.json` | 可恢复的 dirty 工作队列。 |
| `region_permissions.json` | 区域级队伍授权、拒绝和禁用规则。 |
| `access_audit.jsonl` | 权限变更和路径点操作的追加式审计记录。 |

客户端 patch 缓存位于 `config/xaero-mapsync_r-client-patches-v6/`。升级或回滚前应同时备份服务端世界数据和客户端 `XaeroWorldMap` 数据。

## 管理命令

所有 `/sharedmap` 命令要求权限等级 2。

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

`rebuild-loaded` 会从服务端当前已加载的地形生成诊断 tile，属于显式维护操作，不代表默认同步模式会自动渲染服务端地图。

## 构建、测试与本地联调

Windows：

```powershell
.\gradlew.bat clean build
```

Linux/macOS：

```bash
./gradlew clean build
```

构建产物位于 `build/libs/`。当前测试套件包含 291 个 JUnit 测试，覆盖协议载荷、传输重组、压缩、Merkle、patch、tile 存储、客户端协调、服务端调度、权限、活动状态、dirty 队列和 Xaero 反射适配。完整版本矩阵见 [`docs/xaero-compatibility.md`](docs/xaero-compatibility.md)。

本地端到端联调使用一个服务端和两个隔离客户端：

```powershell
.\scripts\prepare-local-integration.ps1
# 阅读并接受 Minecraft EULA 后，将 run/server/eula.txt 改为 eula=true
.\scripts\start-local-integration.ps1
```

服务端仅绑定 `127.0.0.1`，且使用离线模式测试身份，不得暴露到网络。完整验收步骤见 [`docs/local-integration-test.md`](docs/local-integration-test.md)。

CI 会使用 JDK 21 校验 Gradle Wrapper、下载并校验代表性 Xaero Fabric JAR 的 SHA-256、执行 `gradle build`，并运行 Qodana JVM 静态分析。

## 安全边界与限制

- 不上传世界存档、实体、容器、完整方块 NBT 或无关数据。
- 私人路径点只有在玩家明确选择并点击共享后才会提交；PRIVATE 路径点不能上传。
- TEAM 归属来自服务端计分板，不信任客户端提交的创建者或队伍字段。
- 地图上传必须通过握手、维度、格式、可渲染性、大小和速率限制校验。
- 地图后台任务只读取主线程上已经加载的 Minecraft 对象，不为同步强制加载 chunk。
- 共享路径点不可原地修改；删除不要求创建者身份，服务端校验已知 revision，客户端在提交前要求确认。
- 文档列出的 Xaero Fabric 范围及已验证反射契约是兼容边界；范围外版本需要更新适配器并执行完整回归测试。
- 当前为 alpha 版本，建议先在备份世界或 staging 环境验证，再逐步发布。

## 相关文档

- [`docs/atomic-map-sync-v6.md`](docs/atomic-map-sync-v6.md)：协议 v11 / 地图格式 v6 的同步生命周期、可观测性、发布和回滚。
- [`docs/release-process.md`](docs/release-process.md)：强制执行 `feature/*` 到 `master` 再到 `release` 的晋级与 tag 规则。
- [`docs/xaero-compatibility.md`](docs/xaero-compatibility.md)：受支持的 Xaero Fabric 版本与二进制签名策略。
- [`docs/local-integration-test.md`](docs/local-integration-test.md)：三进程本地联调和人工验收清单。
- [`docs/pr-description.md`](docs/pr-description.md)：当前地图 tile 发布变更的 PR 描述。
- [`docs/pr-atomic-map-sync-v6.md`](docs/pr-atomic-map-sync-v6.md)：缺口恢复协议变更的 PR 描述。

## 回滚

1. 停止服务端和所有客户端。
2. 恢复兼容现有 v6 存储的旧 Mod JAR。
3. 如果客户端 Xaero 地图写入异常，恢复升级前备份的对应 `XaeroWorldMap` 目录。
4. 不要删除服务端 `tiles-v6/`、`public_waypoints.json` 或其他世界数据；只有在确认存储不兼容并具备迁移方案时才恢复这些文件。

详细发布与回滚检查清单见 [`docs/atomic-map-sync-v6.md`](docs/atomic-map-sync-v6.md)。

## 许可协议

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2025 CXU
