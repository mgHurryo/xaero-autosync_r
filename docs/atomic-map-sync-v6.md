# Atomic map sync v6

## 目标与边界

地图同步协议升级到 v8，地图数据格式升级到 v6。服务端是地图数据唯一权威来源；客户端上传地图的旧通道不再注册。共享路径点协议、存储、权限模型与界面行为不在本次变更范围内。

本版本不迁移旧地图数据。上线时必须使用全新服务端世界、全新 `tiles-v6`/`map_patch_index-v6.json`，并让客户端从空的 Xaero World Map 目录开始。旧世界和旧客户端地图只作为离线回滚备份。

## 同步生命周期

1. 服务端只从自然加载的已探索区块生成 16x16 tile，不主动加载世界。
2. 4x4 tile 全部持久化后，catalog 才发布一个完整 patch manifest。
3. 客户端按视口中心与移动方向分页请求 manifest；服务端按核心、邻近环、后台排序。
4. 客户端并发获取最多 8 个 patch。body 始终走 CRC32 分片传输，并使用 ACK/NACK、30 秒组装超时和有限重试。
5. 客户端校验 16 个坐标、revision 与 content hash 后进入原子提交队列。
6. 同一个 Xaero region 同时只允许一个提交。若 Xaero 正在本地生成或刷新，保留旧地图并有限退避；16 个 tile 一次性写入成功后才更新本地 applied hash。
7. 每 100 tick 增量检查 catalog。全局 epoch 只保证分页一致性，不参与 patch content hash，因此无关 patch 变化不会造成全图重下。

## 可观测性

常规日志使用可检索的 `map_sync event=<name>` 字段，并在服务端连接、manifest、patch 和分片事件中携带 `trace_id`。每 10 秒输出一次客户端汇总，包括队列、在途请求、提交队列、成功/拒绝/重试数量、tick P95/最大耗时及无进展时间。

```text
/sharedmap status
/sharedmap status <player>
/sharedmap trace <player> <seconds>
```

详细分片日志只在指定玩家的限时 trace 窗口内输出，避免常态日志和磁盘 I/O 反过来拖慢同步。日志不得增加 token、密钥或玩家隐私内容。

## 发布

1. 在 staging 将 `map.sync.shadow_mode=true`，确认握手、采样、完整 patch 数量及服务端 MSPT 正常。
2. 停止服务端，备份旧世界、两个客户端 `XaeroWorldMap` 目录和旧 JAR。
3. 设置新的 `level-name`，保持 `map.sync.enabled=true`，先单客户端验证出生点与高速移动。
4. 关闭 shadow mode，扩到两个客户端，观察黑块、patch 重试、`tick_p95_ms`、网络预算与服务端 MSPT。
5. 验收目标：不出现部分 patch 提交；正常网络下核心视口持续推进；客户端同步 tick P95 不超过 4ms；共享路径点回归测试通过。

紧急开关：`map.sync.enabled=false` 停止 manifest 与 patch 分发；`map.sync.shadow_mode=true` 保留服务端生成和诊断但拒绝客户端下载。

## 回滚

停止全部进程，恢复旧 JAR、旧服务端完整世界目录及每个客户端的旧 `XaeroWorldMap` 目录。不要将 v6 索引或客户端缓存复制到旧世界。确认 `level-name` 指回旧世界后再启动；共享路径点若位于旧世界数据中，也应随完整世界备份一致恢复。
