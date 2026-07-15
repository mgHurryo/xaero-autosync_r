# Xaero Map Sync

面向 Minecraft Java Edition 1.17.1 Fabric 服务端的共享地图与公共路径点同步 Mod。服务端维护已探索区域、地表 tile 和公共路径点，安装客户端 Mod 的玩家继续通过 Xaero World Map / Xaero Minimap 查看同步结果。

当前预发布版本为 `2.1.0-beta.1`，同步协议和地图格式均为版本 2。客户端 Xaero 反射适配器固定支持：

- Xaero's World Map `1.25.1`
- Xaero's Minimap `22.11.1`

版本或运行时签名不匹配时，相关适配器会关闭并记录原因，不会尝试写入未知格式。

## 已实现

- 客户端/服务端协议握手；未通过握手的同步请求会被拒绝。
- 只记录玩家附近自然加载且已探索的区块，不主动加载区块。
- 读取每个区块的 16x16 地表高度、方块状态、群系和光照数据。
- tile 内容、revision、探索索引、脏区块队列和路径点 tombstone 持久化。
- 2x2 分层 Merkle 树逐层比较，按 dimension 请求变化节点和缺失 tile。
- zlib 压缩、16 KiB 分片、CRC 校验、ACK、超时重试和每玩家带宽预算。
- 稳定脏区块 claim/confirm/defer 处理，失败或未加载时不会丢失任务。
- 8x8 chunk 活动区域、STORM/COOLDOWN 状态、MSPT 自适应暂停和人工区域暂停。
- TNT、爆炸、活塞和光照更新专用活动信号，以及无 Carpet 编译时依赖的更新抑制桥接边界。
- 反射写入 Xaero World Map `1.25.1`，成功后才保存客户端 revision。
- 反射同步 Xaero Minimap `22.11.1` 公共路径点；只管理带本 Mod UUID 标记的路径点。
- PUBLIC/TEAM 路径点创建、编辑、删除、revision 冲突检测、创建者权限、数量上限和删除确认。
- 基于原生计分板队伍的可见性过滤，以及按维度和 8x8 chunk 区域持久化的队伍访问规则。
- 客户端管理界面：连接状态、同步开关、统计、搜索、路径点 CRUD 和显式本地 Xaero 路径点导入。
- 管理员路径点检查/删除、区域授权与审计日志，支持区域紧急禁用开关。
- 最近 1,200 tick 的平均/P95 MSPT、地图任务耗时、吞吐和网络预算统计。
- 中英文界面资源，默认按键 `]` 打开管理器。

## 安全边界

- 不上传世界存档、实体、容器、完整方块 NBT 或与地图显示无关的数据。
- 私人 Xaero 路径点只在客户端按当前维度读取；必须经过选择、预览和确认才会上传，且不会自动公开。
- 普通玩家只能修改自己创建的公共路径点，OP 可以管理全部路径点。
- TEAM 路径点的队伍归属由服务端计分板身份确定，不信任客户端提交的创建者或队伍字段。
- 地图任务可全局暂停或按区域暂停；高 MSPT 和 STORM 区域自动延后处理。
- 服务端渲染仅访问主线程上的已加载 Minecraft 对象；后台线程只写不可变 tile 数据。
- 反射写入失败时适配器会话级熔断，避免继续破坏 Xaero 状态。

## 管理命令

命令要求权限等级 2：

```text
/sharedmap status
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

`rebuild-loaded` 只处理在线玩家所在、当前已加载的区块，并同时更新 tile 数据缓存与索引。区域命令使用执行命令玩家当前所在的维度与 8x8 chunk 区域；路径点检查和删除命令也可由服务端控制台执行。访问规则保存在世界数据目录，变更和路径点写操作追加记录到 `access_audit.jsonl`。

## 配置

服务端首次启动生成 `config/xaero-mapsync_r.properties`，主要选项包括协议版本、最大包大小、每玩家/全局带宽预算、脏区块预算、MSPT 阈值、活动阈值以及路径点数量和上传开关。

客户端设置保存在 `config/xaero-mapsync_r-client.properties`，可分别关闭地图同步、公共路径点和通知。

世界数据保存在：

```text
<world>/xaero-mapsync_r/
```

## 构建与测试

需要 Java 16：

```bat
gradlew.bat clean build
```

产物位于 `build/libs/`。单元测试覆盖 Merkle、tile 编解码和持久化、分片重组与重试、网络预算、脏任务状态机、区域活动状态机、区域权限、路径点策略、客户端 revision 缓存，以及固定 Xaero 版本的反射适配边界。

三进程本地联调环境和验收步骤见 [`docs/local-integration-test.md`](docs/local-integration-test.md)。准备脚本只复制 `xaeromap-origin` 中固定版本的 Xaero JAR，并生成绑定 `127.0.0.1` 的离线开发服务端；脚本不会代替用户接受 Minecraft EULA。

## 仍需发布前验证

- 使用已准备的两个独立客户端和一个本地服务端，人工完成探索、离线补图、PUBLIC/TEAM 路径点 CRUD、显式导入和重启恢复的端到端验收。
- 在大型活塞/TNT/光照更新场景下采集 P95 MSPT、吞吐、带宽和缓存规模，并据实际服务器负载调整默认阈值。
- 验证 Xaero `1.25.1` 在所有维度切换、地图打开状态和异步保存时序下的长期稳定性。

## 回滚

停止服务端后回退 Mod JAR，并同时恢复对应版本的 `<world>/xaero-mapsync_r/` 数据备份。协议 2 客户端不能与协议 1 服务端混用。客户端可删除本 Mod 的 revision/root 配置以触发完整重新比较；不要直接修改 Xaero 自身缓存文件。
