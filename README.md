# Xaero Map Sync

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.17.1-green.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.3-blue.svg)](https://fabricmc.net/)

面向 Minecraft Java Edition 1.17.1 Fabric 服务器的共享地图与路径点同步 Mod。客户端上传 Xaero 已渲染地图，服务器只负责合并、持久化和分发地图 tile，并维护共享路径点；客户端继续使用 Xaero World Map 与 Xaero Minimap 的原生界面查看和管理同步结果。

当前预发布版本为 `3.0.0-alpha.6`，固定适配：

- Xaero's World Map `1.25.1`
- Xaero's Minimap `22.11.1`

## 路径点操作

本 Mod 不提供独立的路径点创建器，也不会自动上传私人路径点。所有共享操作都在 Xaero 原生路径点管理页中完成：

1. 使用 Xaero 的路径点快捷键打开原生路径点管理页。
2. 在 Xaero 列表中只选择一个现有路径点。
3. 查看状态标签，点击 `公开共享`；只有玩家已加入计分板队伍时才显示 `队伍共享`。
4. 已共享路径点名称前显示锁图标，共享和修改按钮变灰；如需移除，点击仍可用的 `删除共享` 并确认提示。

共享路径点统一放入独立的 `共享路径点` / `Shared Waypoints` 集合，不改变玩家当前使用的 Xaero 路径点集合。创建者客户端会把所选的原生 Xaero 路径点移动到该集合，不再创建本地副本；删除共享后恢复到原集合。其他有权限的客户端会收到服务器同步的 Xaero 路径点副本。路径点一经共享即不可修改，所有客户端都会恢复服务器版本，但仍可使用 Xaero 原生启用/禁用操作仅在本机隐藏。任何可见该点的玩家均可在确认提示后删除。公开和队伍可见性、创建者身份、revision 与删除 tombstone 均由服务器校验和维护。

Xaero 22.11.1 的路径点颜色是 `0..15` 的调色板索引。服务端加载、写操作和客户端落地都会校验该范围，并迁移旧版本错误写入的 RGB 值，防止 Xaero 渲染器数组越界。

## 已实现

- 客户端与服务端协议握手和兼容性校验。
- 自动地图来源只接受客户端 Xaero 已完成渲染的 tile；服务端不根据出生点、历史 dirty 队列或自然加载区块自动采样地形，因此不会凭空补全客户端从未去过的区域。
- 16x16 地表 tile、revision、探索索引和持久化；客户端上传后进入 2 秒聚合窗口。
- v6 地图格式同步 Xaero 使用的地表高度、顶部高度、稳定生物群系键、光照、发光、透明层和累积不透明度，保留水体、冰、玻璃、树叶与含水方块的原生渲染语义。
- 客户端始终优先使用 Xaero 对本机已加载区块生成的原生地图；服务端瓦片只补充本机未加载区域，不覆盖正在生成或已经完成的本机结果。
- 服务端等待 2 秒合并更新，并在每个 Xaero 32x32 region 内按最大正方形优先切成边长 1–32 的完整 patch；不发布缺边的稀疏点包，也不补黑或清空未声明坐标。
- A/B 客户端会渐进上传 Xaero 已写入的本地地图。玩家附近的新数据可以更新服务端；远处历史数据仅填补服务端缺失 tile，绝不覆盖已有服务端 body。
- 当前加载范围使用跨 tick 持久游标扫描，不再每次从中心重新开始；每次最多检查 256 个 tile、并行排队 64 个上传。实时 tile 的 hash 稳定 2 秒后才发布，相同内容在本次连接中不会重复上传。
- manifest 按玩家视口和移动方向分为前方核心、邻近环和后台三档；边长 3–32 的主波次最多并发请求 8 个 patch，全部校验后按 Xaero region 统一释放，单 tick 提交预算为 4ms。
- 1x1/2x2 小型补洞包进入服务端低优先级队列。只有普通波次清空且玩家与全局带宽低于 50% 低水位时才逐个发送；客户端保持最多 8 个请求在途，并按 2 秒/128 包窗口合并同 Xaero region 提交，减少 refresh 与磁盘保存次数。
- patch body 使用带 CRC32 的分片、ACK/NACK、超时和有限指数退避；缺片只重传当前传输，失败不会清空已有 Xaero 地图。
- patch 中仍在本地生成的 tile 不会阻塞同包远端部分；等待 2 秒后仅提交非加载区远端子集，将加载区交还本机 Xaero 并完成 patch，不再永久挂起或重复下载整包。
- 服务端异常退出后从持久化 tile 自动重建地图索引。
- 协议 v11 使用同步代次和 catalog epoch 隔离迟到响应，并携带正方形原点与边长；patch hash 不包含全局 epoch，单一区域变化不会触发全图重下。v11 还增加了缺口恢复探测，让在线客户端可以优先回传自己已有的 Xaero tile。
- 服务端为已发布 catalog 保留最多 16,384 个被替换的 tile body 内存历史；旧 epoch 请求按内容 hash 读取对应版本，不会因活跃 tile 后续更新而出现 `missing-tile-body`。
- 8x8 chunk 活动区域、STORM/COOLDOWN、MSPT 自适应暂停。
- TNT、爆炸、活塞和光照更新活动信号。
- Xaero World Map `1.25.1` 与 Xaero Minimap `22.11.1` 反射适配。
- PUBLIC/TEAM 路径点创建、不可变锁定、删除、权限、数量限制和 tombstone。
- 原生 Xaero 路径点管理页内的弹性共享操作区、条件队伍按钮、绘制锁图标、删除确认、独立共享集合与本地隐藏。
- 服务端管理命令、区域授权、审计和紧急禁用开关。

`map.sync.server_render.enabled=false` 是默认值。仅诊断或灾难恢复时才可显式设为 `true`，此时服务端会重新启用已加载地形采样。升级不会删除既有 `tiles-v6`；旧版本已经生成并存入服务器的 tile 仍可能继续分发，因为旧格式没有来源字段可安全区分客户端与服务端数据。

## 安全边界

- 不上传世界存档、实体、容器、完整方块 NBT 或无关数据。
- 私人 Xaero 路径点只有在玩家明确选择并点击共享后才会提交。
- 所有玩家都不能修改已共享路径点；任何可见该点的玩家确认后都可删除，OP 也可通过服务端命令删除。
- TEAM 归属由服务器计分板身份决定，不信任客户端提交的创建者或队伍字段。
- 地图任务只读取主线程上已经加载的 Minecraft 对象。
- 地图上传只接受已完成握手、维度匹配、可渲染且通过限流的数据；远处归档上传采用 fill-only 合并，服务端已有 tile 优先。
- Xaero 版本或反射契约不匹配时关闭对应适配器，不写入未知格式。

## 管理命令

命令要求权限等级 2：

```text
/sharedmap status
/sharedmap status <player>
/sharedmap trace <player> <seconds>
/sharedmap save
/sharedmap pause
/sharedmap resume
/sharedmap flush
/sharedmap bandwidth <bytesPerTick>
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

## 配置与数据

- 服务端配置：`config/xaero-mapsync_r.properties`
- 客户端配置：`config/xaero-mapsync_r-client.properties`
- 世界数据：`<world>/xaero-mapsync_r/`

`tasks.dirty_chunk_scan_per_tick` 默认允许检查 4096 个候选区块，按 64 个任务分页领取；渲染数量事故上限为 2048，但正常吞吐由当前/上一 tick 的 MSPT 余量、40ms 事故预算和 writer 队列容量动态截停，并在 45ms 高负载阈值前保留 2ms 余量。瓦片压缩与原子落盘使用 2 至 4 条按坐标有序的 writer；任一可渲染 tile 落盘并发布索引后，其所在的稀疏 patch 即可进入客户端 manifest。

地图 v6 使用服务端 `tiles-v6`、`map_patch_index-v6.json` 和客户端 `xaero-mapsync_r-client-patches-v6` 缓存。本次协议升级不改变存储格式，不重置或删除现有世界、服务端 tile、客户端 Xaero 地图及 `XaeroWaypoints`。发布和回滚步骤见 [`docs/atomic-map-sync-v6.md`](docs/atomic-map-sync-v6.md)。

## 构建与测试

源码兼容 Java 16。当前 Gradle/Loom 需要 Java 21+，Minecraft 1.17.1 的客户端与服务端运行任务则通过 toolchain 固定到 Java 17，避免旧版 LWJGL 在新 JVM 上发生原生崩溃：

```bat
gradlew.bat clean build
```

产物位于 `build/libs/`。三进程本地联调步骤见 [`docs/local-integration-test.md`](docs/local-integration-test.md)。

## 回滚

停止服务端与客户端后回退到兼容现有 v6 存储的旧 Mod JAR。若客户端地图写入异常，再从升级前备份恢复对应 `XaeroWorldMap` 目录；服务端世界、`tiles-v6` 与 `XaeroWaypoints` 无需因本次协议升级回滚。

## 许可协议 / License

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2025 RMS

本软件按"原样"提供，不提供任何明示或暗示的担保，包括但不限于适销性、特定用途适用性和非侵权的担保。在任何情况下，作者或版权持有人均不对因本软件或其使用产生的任何索赔、损害或其他责任负责。

