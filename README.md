# Xaero Map Sync

面向 Minecraft Java Edition 1.17.1 Fabric 服务器的共享地图与路径点同步 Mod。服务器维护已探索区域、地图 tile 和共享路径点，客户端继续使用 Xaero World Map 与 Xaero Minimap 的原生界面查看和管理同步结果。

当前预发布版本为 `2.1.0-beta.12`，固定适配：

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
- 只记录玩家附近自然加载且已探索的区块，不主动加载区块。
- 16x16 地表 tile、revision、探索索引和持久化；自然加载区块首次探索时立即进入生成队列。
- v5 地图格式同步 Xaero 使用的地表高度、顶部高度、稳定生物群系键、光照、发光、透明层和累积不透明度，保留水体、冰、玻璃、树叶与含水方块的原生渲染语义。
- 客户端始终优先使用 Xaero 对本机已加载区块生成的原生地图；服务端瓦片只补充本机未加载区域，不覆盖正在生成或已经完成的本机结果。
- 服务端瓦片按 Xaero 32x32 chunk 区域批量注入，一批只触发一次区域刷新，并遵循原生纹理缓存准备与延迟保存流程。
- 客户端从 Xaero 已完成的本机 tile 读取不可变地表快照并限流上传；服务端按坐标和内容哈希去重、校验当前已加载区块、原子落盘并主动分发，哈希变化时采用新的快照。
- 若客户端快照缺失、超出安全包大小、校验失败或等待超时，服务端仅对自然加载中的区块执行回退渲染，不会主动加载历史区块。
- 服务端异常退出后从持久化 tile 自动重建地图索引。
- 带同步代次和请求编号的分层 Merkle 比较、压缩分片、CRC、ACK、超时重试和带宽预算，迟到响应不会推进新会话。
- 8x8 chunk 活动区域、STORM/COOLDOWN、MSPT 自适应暂停。
- TNT、爆炸、活塞和光照更新活动信号。
- Xaero World Map `1.25.1` 与 Xaero Minimap `22.11.1` 反射适配。
- PUBLIC/TEAM 路径点创建、不可变锁定、删除、权限、数量限制和 tombstone。
- 原生 Xaero 路径点管理页内的弹性共享操作区、条件队伍按钮、绘制锁图标、删除确认、独立共享集合与本地隐藏。
- 服务端管理命令、区域授权、审计和紧急禁用开关。

## 安全边界

- 不上传世界存档、实体、容器、完整方块 NBT 或无关数据。
- 私人 Xaero 路径点只有在玩家明确选择并点击共享后才会提交。
- 所有玩家都不能修改已共享路径点；任何可见该点的玩家确认后都可删除，OP 也可通过服务端命令删除。
- TEAM 归属由服务器计分板身份决定，不信任客户端提交的创建者或队伍字段。
- 地图任务只读取主线程上已经加载的 Minecraft 对象。
- 客户端地图上传在解压前受握手、单包大小、包数和压缩字节预算约束，并在服务端对自然加载区块重算完整 v5 地表哈希，拒绝伪造的方块、光照、透明层、生物群系或洞穴字段。
- Xaero 版本或反射契约不匹配时关闭对应适配器，不写入未知格式。

## 管理命令

命令要求权限等级 2：

```text
/sharedmap status
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

`tasks.dirty_chunk_scan_per_tick` 默认允许检查 4096 个候选区块，按 64 个任务分页领取；渲染数量事故上限为 2048，但正常吞吐由当前/上一 tick 的 MSPT 余量、40ms 事故预算和 writer 队列容量动态截停，并在 45ms 高负载阈值前保留 2ms 余量。瓦片压缩与原子落盘使用 2 至 4 条按坐标有序的 writer；只有落盘和索引发布成功后才确认 dirty 任务，随后主动推送到同维度客户端。Merkle 比较仅用于连接建立、丢包、断线和背压恢复，不承担持续轮询同步。

地图 v5 使用服务端 `tiles-v5`、`map_tile_index-v5.json` 和客户端 `xaero-mapsync_r-client-tiles-v5` 完整瓦片缓存。客户端每次连接都会校验服务端 revision、content hash 和 Merkle 根，优先从本机恢复匹配瓦片，只向服务端请求缺失、变化或损坏的内容。旧版数据会保留用于回滚但不会混入 v5 索引；历史区域会在区块再次自然加载时按 v5 格式补齐，Mod 不会为迁移主动加载世界区块。地表采样算法带有独立版本标记；升级后旧采样 tile 会排入持久化重建队列，并在对应区块自然加载时原位更新。

## 构建与测试

源码兼容 Java 16。当前 Gradle/Loom 需要 Java 21+，Minecraft 1.17.1 的客户端与服务端运行任务则通过 toolchain 固定到 Java 17，避免旧版 LWJGL 在新 JVM 上发生原生崩溃：

```bat
gradlew.bat clean build
```

产物位于 `build/libs/`。三进程本地联调步骤见 [`docs/local-integration-test.md`](docs/local-integration-test.md)。

## 回滚

停止服务端后回退 Mod JAR，并恢复同一版本的 `<world>/xaero-mapsync_r/` 数据备份。客户端可删除本 Mod 的 revision/root 配置触发完整重新比较；不要手工编辑 Xaero 缓存文件。
