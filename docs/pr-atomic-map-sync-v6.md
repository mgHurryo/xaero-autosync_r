# feat(map-sync)!: recover map gaps from online Xaero clients

## 修改目标

消除高速移动时客户端同步产生的大量黑块、区域失真和低吞吐，并建立可定位 manifest、传输、校验与 Xaero 提交阶段的日志链路。

## 修改内容

- 协议 v11、地图格式 v6、应用版本 `3.0.0-alpha.6`。
- 自动地图来源改为客户端 Xaero tile；服务端地形采样默认关闭，避免出生点、历史 dirty 队列或加载状态生成玩家从未访问过的地图。
- 服务端 2 秒聚合更新，在每个 Xaero 32x32 region 内按最大正方形优先切成边长 1–32 的完整 patch，并保留视口/移动方向优先级。
- 恢复受限的客户端 tile 上传，并渐进合并 A/B 已有 Xaero region；远处数据只填空，不覆盖服务端已有 body。
- 修复每次只扫描中心 8 个 tile 导致外圈饥饿的问题，改为持久游标轮询和每批最多 64 个有界并行上传。
- 实时 Xaero tile 增加 2 秒 hash 稳定窗口；本地生成超时后仅提交非加载区的远端子集，将加载区交还本机 Xaero 并完成 patch，避免永久挂起与重复下载整包。
- tile 数据仓库增加按内容 hash 的 16,384 项有界版本历史，保证 catalog 发布后即使当前 body 被替换，旧 epoch 仍可完整组包。
- CRC32 分片、ACK/NACK、超时、有限重传、最多 8 路主包请求以及有界压缩/解码工作线程。
- 主波次全部校验后按 Xaero region 统一释放；1x1/2x2 补洞包由服务端低水位队列逐个传输，客户端保持 8 个请求窗口并按 2 秒/128 包合并同 region 提交。
- 缺口的实际相邻 tile 达到目标 revision 且稳定 30 秒后才请求恢复；服务端先查询合并存储，再用 750ms 有界窗口向同维度在线客户端探测。请求限制为每客户端 4 批/秒、全局 32 批/秒和每批最多 8 个 peer，且不会启动服务端地形渲染。
- 当前强/弱加载 chunk 始终保留本机 Xaero 结果，曾访问但当前未加载的 chunk 允许云端刷新。
- 每 Xaero 32x32 region 单事务提交，禁止部分主波次落地。
- `trace_id`、阶段日志、10 秒汇总、玩家限时 trace、kill switch 与 shadow mode。
- 继续使用现有 v6 世界、服务端 tile 和客户端地图缓存，不重置数据。

## 不包含的内容

不修改共享路径点协议、权限、存储格式、UI 行为或共享路径。

## 风险等级

High

## 行为变化

修改前：按单 tile/不完整区域接收和反复刷新，客户端可上传地图，快速移动时队列无视口优先级。

修改后：A/B 本地 Xaero tile 通过受限上传合并，服务端每 2 秒冻结一个自适应完整正方形波次；客户端并行校验主包后统一释放，小补洞包在低水位逐个补齐。服务端不再自动生成玩家从未访问过的 tile。

## 验证方式

- [x] 单元测试
- [x] 协议编解码测试
- [x] 原子提交回归测试
- [x] 路径点既有回归测试
- [ ] 三进程高速移动人工测试
- [ ] staging 长时性能测试

## 评测结果

基线日志：客户端 A/B 分别出现 94/34 次注入延迟，85/26 次 region refresh pending。

修改后：277 项测试通过，覆盖 1–32 正方形分割、2 秒聚合、波次屏障、低水位补洞、协议编解码、当前加载区块所有权、归档 region 反射契约、fill-only 合并、缺口稳定窗口和在线同伴缺口恢复限流。单客户端实测 2,586 个 patch 全部应用，提交队列归零，稳态 P95 为 0.071ms；A/B 双客户端完成 v11 握手并触发同维度 peer probe。高速移动和 staging 长时测试仍待执行。

## 性能和成本

客户端同步 tick 硬预算 4ms；主 patch 和小型补洞请求窗口上限均为 8；服务端低优先级传输逐包使用玩家与全局预算的低水位区间；manifest 页面上限 128。无模型或 Token 成本。

## 安全影响

未增加权限、外部网络或敏感数据访问。地图上传要求握手、维度匹配、可渲染校验和双层限流；远处上传不得覆盖已有服务端 tile。日志只记录技术标识与计数。

## 发布方式

Shadow Mode 后灰度发布。

## 回滚方式

设置 `map.sync.enabled=false`，停止进程并恢复兼容 v6 的旧 JAR；仅在客户端地图写入异常时恢复对应 `XaeroWorldMap` 备份，世界、服务端 tile 与路径点无需回滚。

## 关联任务

Refs: NO-TICKET

BREAKING CHANGE: protocol v11 is required for adaptive square waves and peer-assisted gap recovery; map format remains v6 and does not require a world, map cache or waypoint reset.
