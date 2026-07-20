# Release 分支与发布规则

本项目使用长期 `master` 集成分支和长期 `release` 发布分支。所有 Git tag 与 GitHub Release 必须基于 `release` 分支中已经通过检查的提交；不得直接从 `master`、任务分支或本地未推送提交发布。

## 分支职责

- `feature/*`、`fix/*`、`chore/*` 等任务分支只承载单个可审查目标。
- `master` 是完成日常集成验证的主分支，不直接承载 GitHub Release。
- `release` 是唯一发布分支，只接收来自 `master` 的 Pull Request，不直接开发或修复代码。
- 禁止直接向 `master` 或 `release` 推送，禁止对两个长期分支执行 force push。

## 必经流程

1. 在任务分支完成实现、测试、版本更新、发布说明和回滚说明。
2. 将任务分支推送到远端，并创建目标为 `master` 的 Pull Request。
3. 等待 Gradle build、完整测试和 Qodana 全部通过；检查未完成或失败时不得合并。
4. 合并到 `master` 后，从 `master` 创建目标为 `release` 的 Pull Request。不得通过 cherry-pick 绕过已验证的 `master` 提交历史。
5. 再次等待 `release` Pull Request 的 Gradle build、完整测试和 Qodana 全部通过，然后合并。
6. 从最新 `release` 提交执行干净构建，验证版本元数据、产物内容和 SHA-256。
7. 在该 `release` 提交创建 annotated tag，推送 tag，并等待 tag 对应的 build 通过。
8. 使用已经验证的 tag 创建 GitHub Release。发布资产只能来自同一提交的干净构建。

## 发布门禁

- tag 指向的提交必须同时位于 `origin/release`，且版本号与 `gradle.properties`、`fabric.mod.json` 展开结果、文件名和 Release 标题一致。
- 发布前必须执行单元测试、集成测试和本次变更要求的兼容性矩阵；未执行的人工或端到端测试必须在 Release Notes 中明确说明。
- 发布资产不得包含上游 Xaero fixture、用户数据、密钥、日志或本地运行目录。
- Release 必须附主 JAR、sources JAR 和覆盖全部资产的 `SHA256SUMS`。
- alpha、beta 和 rc 版本必须标记为 prerelease。

## 修复与回滚

发布后发现问题时，从 `master` 创建 `hotfix/*` 分支，仍按 `hotfix/* -> master -> release` 两级 Pull Request 流程发布。需要撤销版本时，优先创建可审查的 revert 提交并重新经过两级门禁；不得移动、覆盖或复用已经发布的 tag。
