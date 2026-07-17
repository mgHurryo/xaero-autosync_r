# refactor(map-sync)!: replace tile streaming with atomic region patches

## 修改目标

消除高速移动时客户端同步产生的大量黑块、区域失真和低吞吐，并建立可定位 manifest、传输、校验与 Xaero 提交阶段的日志链路。

## 修改内容

- 协议 v8、地图格式 v6、应用版本 `3.0.0-alpha.1`。
- 服务端权威的 4x4 chunk 原子 patch catalog 与视口/移动方向优先级。
- CRC32 分片、ACK/NACK、超时、有限重传与客户端并发窗口。
- 每 Xaero 32x32 region 单事务提交，禁止部分 patch 落地。
- `trace_id`、阶段日志、10 秒汇总、玩家限时 trace、kill switch 与 shadow mode。
- 全新服务端世界和客户端原生地图目录；不迁移旧地图数据。

## 不包含的内容

不修改共享路径点协议、权限、存储格式、UI 行为或共享路径。

## 风险等级

High

## 行为变化

修改前：按单 tile/不完整区域接收和反复刷新，客户端可上传地图，快速移动时队列无视口优先级。

修改后：服务端只发布完整 patch，客户端校验完整 body 后按 region 一次性提交，失败时保留旧地图；旧地图网络通道不注册。

## 验证方式

- [x] 单元测试
- [x] 协议编解码测试
- [x] 原子提交回归测试
- [x] 路径点既有回归测试
- [ ] 三进程高速移动人工测试
- [ ] staging 长时性能测试

## 评测结果

基线日志：客户端 A/B 分别出现 94/34 次注入延迟，85/26 次 region refresh pending。

修改后：单元测试保证不完整 patch 无法构造或提交；实时黑块与长时 P95 仍需 staging 验证。

## 性能和成本

客户端同步 tick 硬预算 4ms；patch 请求并发上限 8；manifest 页面上限 128。无模型或 Token 成本。

## 安全影响

未增加权限、外部网络或敏感数据访问。移除客户端地图上传入口。日志只记录技术标识与计数。

## 发布方式

Shadow Mode 后灰度发布。

## 回滚方式

停止进程，恢复旧 JAR、旧服务端完整世界和每个客户端的旧 `XaeroWorldMap` 目录，禁止混用 v5/v6 数据。

## 关联任务

Refs: NO-TICKET

BREAKING CHANGE: protocol v8 and map format v6 require a fresh world and fresh client map storage.
