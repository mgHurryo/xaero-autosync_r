# Xaero Map Sync

面向 Minecraft Java Edition 1.17.1 Fabric 服务器的共享地图与路径点同步 Mod。服务器维护已探索区域、地图 tile 和共享路径点，客户端继续使用 Xaero World Map 与 Xaero Minimap 的原生界面查看和管理同步结果。

当前预发布版本为 `2.1.0-beta.3`，固定适配：

- Xaero's World Map `1.25.1`
- Xaero's Minimap `22.11.1`

## 路径点操作

本 Mod 不提供独立的路径点创建器，也不会自动上传私人路径点。所有共享操作都在 Xaero 原生路径点管理页中完成：

1. 使用 Xaero 的路径点快捷键打开原生路径点管理页。
2. 在 Xaero 列表中只选择一个现有路径点。
3. 查看状态标签，点击 `公开共享`；只有玩家已加入计分板队伍时才显示 `队伍共享`。
4. 已共享路径点名称前显示锁图标，共享和修改按钮变灰；如需移除，点击仍可用的 `删除共享` 并确认提示。

创建者客户端直接复用所选的 Xaero 路径点，不会再创建一份本地副本。其他有权限的客户端会收到服务器同步的 Xaero 路径点副本。路径点一经共享即不可修改，所有客户端都会恢复服务器版本；任何可见该点的玩家均可在确认提示后删除。公开和队伍可见性、创建者身份、revision 与删除 tombstone 均由服务器校验和维护。

Xaero 22.11.1 的路径点颜色是 `0..15` 的调色板索引。服务端加载、写操作和客户端落地都会校验该范围，并迁移旧版本错误写入的 RGB 值，防止 Xaero 渲染器数组越界。

## 已实现

- 客户端与服务端协议握手和兼容性校验。
- 只记录玩家附近自然加载且已探索的区块，不主动加载区块。
- 16x16 地表 tile、revision、探索索引和持久化。
- 服务端异常退出后从持久化 tile 自动重建地图索引。
- 分层 Merkle 比较、压缩分片、CRC、ACK、超时重试和带宽预算。
- 8x8 chunk 活动区域、STORM/COOLDOWN、MSPT 自适应暂停。
- TNT、爆炸、活塞和光照更新活动信号。
- Xaero World Map `1.25.1` 与 Xaero Minimap `22.11.1` 反射适配。
- PUBLIC/TEAM 路径点创建、不可变锁定、删除、权限、数量限制和 tombstone。
- 原生 Xaero 路径点管理页内的弹性共享操作区、条件队伍按钮、绘制锁图标和删除确认。
- 服务端管理命令、区域授权、审计和紧急禁用开关。

## 安全边界

- 不上传世界存档、实体、容器、完整方块 NBT 或无关数据。
- 私人 Xaero 路径点只有在玩家明确选择并点击共享后才会提交。
- 所有玩家都不能修改已共享路径点；任何可见该点的玩家确认后都可删除，OP 也可通过服务端命令删除。
- TEAM 归属由服务器计分板身份决定，不信任客户端提交的创建者或队伍字段。
- 地图任务只读取主线程上已经加载的 Minecraft 对象。
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

## 构建与测试

需要 Java 16：

```bat
gradlew.bat clean build
```

产物位于 `build/libs/`。三进程本地联调步骤见 [`docs/local-integration-test.md`](docs/local-integration-test.md)。

## 回滚

停止服务端后回退 Mod JAR，并恢复同一版本的 `<world>/xaero-mapsync_r/` 数据备份。客户端可删除本 Mod 的 revision/root 配置触发完整重新比较；不要手工编辑 Xaero 缓存文件。
