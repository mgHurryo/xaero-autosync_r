# Atomic map sync v6

## 目标与边界

地图同步协议升级到 v11，地图数据格式保持 v6。服务端维护合并后的共享 catalog；客户端上传 Xaero 已完成写入的 tile，服务端对远处历史数据执行 fill-only 合并。在线客户端可在服务端缺少 tile 时参与短时缺口恢复探测。共享路径点协议、存储、权限模型与界面行为不在本次变更范围内。

本版本不迁移或重置地图数据，继续使用现有世界、`tiles-v6`、`map_patch_index-v6.json`、客户端 Xaero World Map 与 v6 缓存。升级前仍应备份，但不得删除现有地图或 `XaeroWaypoints`。

## 同步生命周期

1. 客户端只上传 Xaero 已完成渲染的 16x16 tile。服务端默认不采样 Minecraft 地形，只负责校验、合并、持久化与分发；`map.sync.server_render.enabled=true` 仅作为显式诊断/恢复开关。
2. 首个未发布更新进入 2 秒聚合窗口。窗口到期后冻结一个 catalog 波次，并在每个 Xaero 32x32 region 内以最大正方形优先切成边长 1–32 的完整 patch；未声明坐标不会被清空或填黑。
3. 客户端按视口中心与移动方向分页请求 manifest；服务端按核心、邻近环、后台排序。
4. 客户端并发获取最多 8 个边长 3–32 的主 patch。body 始终走 CRC32 分片传输，并使用 ACK/NACK、30 秒组装超时和有限重试；压缩和解码使用有界工作线程。
5. 客户端校验完整正方形的唯一坐标、revision 与 content hash。主波次全部校验后才按 Xaero region 统一释放；1x1/2x2 补洞包进入服务端低优先级队列逐个发送，客户端保持最多 8 个请求在途并按 2 秒/128 包窗口合并同 region 提交，避免每个黑点触发一次 Xaero region refresh。
6. 同一个 Xaero region 同时只允许一个提交。当前客户端强/弱加载范围内的 chunk 始终由本机 Xaero 负责：READY 保留，GENERATING 最多等待 2 秒后保留本机并释放队列，云端不得覆盖。曾经访问但当前未加载的 chunk 可以接收较新的云端结果。
7. 客户端按 Xaero 已检测 region 渐进扫描历史 tile；远处上传只能填补服务端缺失 body，附近上传仍可更新当前探索结果。
   当前加载范围采用持久环形游标，每 tick 最多检查 256 个 tile、排队 64 个上传，不存在固定 8 tile 的中心重扫上限；压缩队列饱和时在后续轮次继续。实时 hash 必须连续稳定 2 秒才发布，避免渲染中间态持续替换 catalog body。
8. 每 100 tick 增量检查 catalog。全局 epoch 只保证分页一致性，不参与 patch content hash，因此无关 patch 变化不会造成全图重下。
9. catalog 历史引用的旧 tile body 以内容 hash 保留在 16,384 项有界 LRU 中；当前磁盘 body 被新上传替换后，慢客户端仍能完成旧 epoch patch。服务重启后只发布当前磁盘版本，不需要持久化这段临时历史。
10. 缺口探测只把达到目标 revision 的相邻 tile 视为已存在；同 region 后续 patch 会重新开始 30 秒稳定窗口。peer recovery 每客户端最多 4 批/秒、全局最多 32 批/秒，每批最多探测 8 个同维度客户端。

## 可观测性

常规日志使用可检索的 `map_sync event=<name>` 字段，并在服务端连接、manifest、patch 和分片事件中携带 `trace_id`。每 10 秒输出客户端汇总，包括大/小包队列、已校验波次、在途请求、提交阶段、本地生成等待、成功/拒绝/重试数量和 tick P95；服务端同时输出普通/低优先级传输、最老补洞等待、低水位延迟 tick 与带宽 P95。

```text
/sharedmap status
/sharedmap status <player>
/sharedmap trace <player> <seconds>
```

详细分片日志只在指定玩家的限时 trace 窗口内输出，避免常态日志和磁盘 I/O 反过来拖慢同步。日志不得增加 token、密钥或玩家隐私内容。

## 发布

1. 在 staging 将 `map.sync.shadow_mode=true`，确认握手、客户端归档发现、完整 patch 数量及服务端 MSPT 正常。
2. 停止服务端和客户端，备份当前世界、两个客户端 `XaeroWorldMap` 目录和旧 JAR，不删除 v6 数据。
3. 保持原 `level-name` 与 `map.sync.enabled=true`，先连接接收端客户端验证旧空洞补齐，再连接探索端客户端执行高速移动。
4. 关闭 shadow mode，扩到两个客户端，观察黑块、patch 重试、`tick_p95_ms`、网络预算与服务端 MSPT。
5. 验收目标：不出现部分 patch 提交；正常网络下核心视口持续推进；客户端同步 tick P95 不超过 4ms；共享路径点回归测试通过。

紧急开关：`map.sync.enabled=false` 停止 manifest、patch 分发与客户端地图上传；`map.sync.shadow_mode=true` 保留诊断但拒绝客户端下载。默认保持 `map.sync.server_render.enabled=false`，避免服务端生成玩家从未访问过的地图。

## 回滚

设置 `map.sync.enabled=false` 并停止全部进程，恢复兼容 v6 的旧 JAR。若仅地图写入异常，再恢复对应客户端的 `XaeroWorldMap` 备份；本次不改变世界、服务端 tile 或路径点格式，因此它们无需回滚。
