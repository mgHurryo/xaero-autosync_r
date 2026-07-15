# Xaero Map Sync

面向 Minecraft Java Edition 1.17.1 Fabric 服务器的共享地图与公共路径点同步 Mod 骨架。

本项目的长期目标是让服务器内安装客户端 Mod 的玩家共同维护一份全服共享地图，并继续使用 Xaero's World Map / Xaero's Minimap 作为主要查看入口。项目优先服务生电服务器场景：同步可以延迟，但不能为了地图功能影响服务器 TPS、强加载区块或公开玩家私有路径点。

## 当前目标

- 固定运行环境为 Minecraft 1.17.1、Java 16、Fabric Loader、Fabric API。
- 同一个 Mod JAR 同时包含客户端和服务端入口。
- 先建立稳定的协议、配置和 Xaero 检测边界，再逐步实现路径点、探索记录、地图瓦片、哈希树和增量同步。
- 所有地图相关处理必须以“低干扰、可限速、可暂停、不强加载区块”为前提。

## 当前阶段能力

当前代码处于第一阶段骨架实现，已包含以下能力：

- **1.17.1 Fabric 项目配置**
  - `minecraft_version=1.17.1`
  - Java 编译目标为 16
  - Fabric Loader / Fabric API 依赖由 `gradle.properties` 管理

- **客户端和服务端入口**
  - 服务端/通用入口：`cn.net.rms.xaeromapsync_r.XaeroMapsync_r`
  - 客户端入口：`cn.net.rms.xaeromapsync_r.XaeroMapsyncClient`
  - `fabric.mod.json` 的 `environment` 为 `*`，同一 JAR 可在客户端和服务端加载

- **基础握手**
  - 客户端加入服务器后发送 `C2S_HELLO`
  - 服务端返回 `S2C_HELLO`
  - 握手字段包含协议版本、地图格式版本、Xaero 适配版本、压缩方式和最大包大小
  - 服务端记录已接受客户端状态；协议或地图格式不兼容时拒绝同步握手
  - 客户端会请求公共路径点快照和地图 tile 索引快照

- **基础配置**
  - 启动时生成/加载 `config/xaero-mapsync_r.properties`
  - 已预留协议、地图格式、压缩、最大包大小、探索、任务预算和路径点相关配置项
  - 当前配置只支撑骨架阶段，不代表完整同步策略已经实现

- **Xaero 检测边界**
  - 客户端检测 `xaerominimap` 和 `xaeroworldmap` 是否加载
  - 当前只记录检测结果和可用状态
  - `XaeroMapAdapter` 与 `XaeroWaypointAdapter` 仍是适配接口边界，尚未写入 Xaero 地图缓存或路径点文件

- **基础服务端状态**
  - `/sharedmap status` 查看已握手客户端数、探索区块数和公共路径点计数
  - `/sharedmap save` 保存当前服务端状态
  - `/sharedmap pause` / `/sharedmap resume` 暂停或恢复重型同步任务
  - `/sharedmap flush` 清理已稳定的脏区块记录
  - `/sharedmap bandwidth <bytesPerTick>` 调整单玩家每 Tick 网络预算
  - `/sharedmap rebuild-loaded` 只对在线玩家所在的已加载区块生成调试 tile 索引
  - 公共路径点已具备数据模型、revision 字段和删除墓碑字段
  - 服务端停止时会保存公共路径点索引、基础探索索引、脏区块索引和地图 tile 索引

- **基础探索记录**
  - 服务端每秒按玩家附近视距扫描一次自然已加载区块
  - 探索半径使用服务器玩家视距减 1 个区块
  - 只通过 `hasChunk` 判断当前已加载区块，不主动加载区块
  - 探索索引按维度持久化到世界目录下的 `xaero-mapsync_r/explored_chunks.json`

- **公共路径点同步基础**
  - 已实现创建、更新、删除和快照 payload 编解码
  - 服务端创建路径点时覆盖创建者 UUID 和名称，避免客户端伪造身份
  - 更新/删除要求 revision 匹配，并且只允许创建者或 OP 操作
  - 删除不会物理移除，会保留 tombstone 防止离线客户端恢复旧数据
  - 客户端维护公共路径点内存缓存，Xaero 写入仍由后续适配层完成

- **脏区块与活动状态**
  - 通过 mixin 在 `Level#setBlock` 成功返回后标记所在区块和 16x16 列
  - 标记阶段只更新内存状态，不扫描区块、不压缩、不写盘、不广播
  - 支持 `ACTIVE`、`STORM`、`COOLDOWN`、`STABLE` 状态
  - 脏区块状态持久化到世界目录下的 `xaero-mapsync_r/dirty_chunks.json`

- **地图 tile 索引和清单同步**
  - `/sharedmap rebuild-loaded` 可从已加载区块生成 16x16 高度调试 tile
  - tile 索引保存 `contentHash`、独立 `revision` 和更新时间
  - 服务端维护 root hash，客户端可通过 `C2S_MAP_ROOT_HASH` 请求差异清单
  - 服务端可下发 Merkle 摘要快照，客户端维护内存摘要缓存
  - 客户端可按缺失 revision 请求 `C2S_TILE_REQUEST`
  - 服务端只在区块已探索且当前自然加载时返回 `S2C_TILE_DATA`
  - 当前 tile 内容是 16x16 高度调试数据，不是真实 Xaero 地图像素或缓存

- **网络保护**
  - tile 数据使用 zlib 压缩，包大小受 `network.max_packet_bytes` 限制
  - 单玩家 tile 发送受每 Tick 字节预算限制
  - 已定义 `TRANSFER_PART` / `TRANSFER_ACK` payload，后续可用于大对象分片和断点续传

- **测试覆盖**
  - 已添加 JUnit 5 单元测试
  - 覆盖哈希稳定性、路径点校验、脏区块状态转换、tile index revision/rootHash 行为

## 本地构建命令

在项目根目录执行：

```bash
./gradlew build
```

Windows PowerShell / CMD 可执行：

```bat
gradlew.bat build
```

常用开发任务：

```bat
gradlew.bat runClient
gradlew.bat runServer
```

构建产物通常位于：

```text
build/libs/
```

## 未实现范围

以下内容是计划方向，当前尚未实现：

- 真实共享地图像素瓦片生成、存储和同步
- 真实 Xaero 地图像素/缓存写入
- 地图级探索位图和完整客户端增量同步
- 基于 Merkle 的逐层按需差异请求
- 分片传输的调度器、ACK 重传和断点续传落地
- 公共路径点客户端管理界面和 Xaero 导入/移除
- 客户端管理界面
- Xaero 地图缓存写入、路径点导入或自动刷新
- 完整权限系统和区域暂停控制
- 多客户端本地联调脚本或自动化测试场景

## 安全约束

- 不强加载未自然加载的区块。
- 不上传完整世界存档、实体、容器内容、完整方块 NBT 或与地图显示无关的数据。
- 不默认公开玩家的私有 Xaero 路径点；公共路径点必须走显式选择和确认流程。
- 方块变化监听只能做轻量标记，不能在事件内直接重算地图、压缩、写盘或广播。
- 高负载、生电机器、爆炸、活塞阵列、光照/更新抑制等场景下，应优先暂停或延后地图任务。
- 所有网络传输都需要受协议版本、包大小、带宽预算和服务端负载限制约束。
- Minecraft 世界对象只能在安全线程上下文读取；后台线程不得直接访问 `ServerWorld`、`WorldChunk`、`BlockState` 等对象。
- 任何未来的管理命令、路径点修改或同步开关都应保留审计、权限和回滚设计空间。

## 设计原则

地图同步允许延迟，但不能影响服务器运行。当前实现只打通项目骨架、握手、配置和 Xaero 检测边界；后续功能应继续遵守最小权限、低干扰、可暂停、可限速和不强加载区块的原则。
