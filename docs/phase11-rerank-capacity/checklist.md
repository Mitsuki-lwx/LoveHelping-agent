# phase11 · 验收清单

> 结论一句话：**`max-concurrent=2` 让 79% 的重排静默降级 —— 开着的收益，八成请求拿不到。**
> 产品行为逻辑零改动（只补观测）；`max-concurrent` **默认值未改**（容量参数，需拍板）。

## A. 前提与装置

- [x] A1 **前提断言通过**：这条 prompt 在聊天链路真的触发检索 + 重排
      —— `RAG_RETRIEVAL=1`、`Rerank call=1`、`Rerank ok=1`（修复前该断言恒为 0，根因见 ADR-40）
- [x] A2 生效配置从日志回显可查
      —— `Rerank configured: enabled=true mode=remote endpoint=https://api.siliconflow.cn/v1/rerank
      model=Qwen/Qwen3-Reranker-8B topN=20 topK=5 timeoutMs=5000 maxConcurrent=N failureThreshold=3`
- [x] A3 本地 8091 已关闭（`killed on 8091: none`）→ 成功的重排**只可能走远端**
- [x] A4 冷启动首调用 **922ms** —— 上一轮记的 "5.21s > `timeout-ms=5000`" **本轮未复现**
      （不因此判定"没问题"，只收窄为"未复现"，见 spec §S5）

## B. 实测：并发代价

- [x] B1 四档 TTFT 已记录（8/16/24：p50 ≈2.5~3.7s；32 档因 81% 被拒样本仅 5 条）
- [x] B2 拒绝与错误**单独计数**：8/16/24 三档 0 拒 0 错；32 档过载拒 26~27（≈81%，**既有容量行为**）
- [x] B3 `executions_total = 58` 与"成功聊天请求 58"**完全吻合** → 每次检索都执行了重排
- [x] B4 **降级率已量化**：`max-concurrent=2` → **46/58 = 79.3%**；8 → 27.6%；16 → **0%**
- [x] B5 原因由**独立证据**确认：`Rerank call`(=拿到许可)=12，`Rerank ok`=12 → 上游零失败；
      `executions - call = 46` 即许可不足。**不再靠翻源码推断**
- [x] B6 降级对用户**完全静默**：对话仍 success、无 4xx/5xx、回复内容正常

## C. 量具补齐（本轮唯一产品代码改动）

- [x] C1 启动回显补 `maxConcurrent` / `failureThreshold`（**决定降级率的参数**此前没回显）
- [x] C2 `rag.rerank.fallback` 补 `reason` 标签，有限取值（saturated/circuit_open/missing_key/upstream/other）
- [x] C3 降级路径补 DEBUG 一行 + **打印完整异常与 cause**（只打 `getMessage()` 会丢 cause）
- [x] C4 单测 +3：`saturated` 与 `upstream` 必须可分；`missing_key` 不得误归上游；未知落 `other`
- [x] C5 **对照实验**：`reasonOf` 恒返回 `other` → 两例**精确失败且只它两个**；恢复后逐字节一致
- [x] C6 未改任何**行为逻辑**；`max-concurrent` 默认值**未改**；`git status src/main` 只含本轮文件

## D. 许可数对照

- [x] D1 同脚本、同档位(8/16/24/32)、同 prompt、同代码，只改 `APP_RAG_RERANK_MAXCONCURRENT`（2/8/16）
- [x] D2 「许可数 → 降级率」表：**2 → 79.3%、8 → 27.6%、16 → 0%**（拐点在 8 与 16 之间，8 不够）
- [x] D3 检查上游失败：8 档 **1 次 `reason=upstream`**（58 次中）、16 档 **0 次**
      → **短时 16 并发未观测到厂商限流**，但**不能据此断言"不会限流"**（样本小）
- [x] D4 结论按实测下：**"2 是瓶颈"成立**（16 档 0 降级且上游零失败）

## E. 回归与交付

- [x] E1 全量单测 **285/285**（基线 282，+3）
- [x] E2 真实 E2E **22/22**
- [x] E3 `git status src/main` 只含本轮三个文件（2 个 main + 1 个 test）
- [x] E4 无明文密钥入库（grep 自查）
- [x] E5 决策记录（ADR-41）/ 测试策略（§8.23）/ 记忆已同步
- [x] E6 **未验证项已列出**：样本量小（每档 8~24 次）；多实例未测（信号量与熔断是进程内状态）；
      厂商 rerank 并发上限未探；24 档 p95≈10.5s 未归因；**未跑 `rerank=false` 对照轮**（重排对 TTFT
      的净贡献没有干净分解）；冷启动 5.21s 未复现也未证伪

## F. 遗留（另立项，不在本轮范围）

- [x] F1 ~~`max-concurrent` 该定多少~~ → **用户已拍板：改成 16，本轮已实施并复验**（见 G 段）
- [ ] F2 24 档 p95 ≈10.5s 归因（三轮重现）
- [ ] F3 在同一装置上补一轮 `RERANK_ENABLED=false`，把重排对 TTFT 的净贡献拆出来
- [ ] F4 厂商 rerank API 的并发上限（需长时压测，本轮 58 次样本不够）

## G. 实施与复验（2026-09-23，用户拍板后）

- [x] G1 `max-concurrent` 2 → **16**，且 **Java 字段默认值同步为 16**（两处不一致 = 隐患）
- [x] G2 `connect-timeout-ms` 500 → **2000**（新发现：实测到上游的连接+TLS 常 >500ms）
- [x] G3 两处都加**环境变量占位符**（`RERANK_MAX_CONCURRENT` / `RERANK_CONNECT_TIMEOUT_MS`）→ 可运行态回滚
- [x] G4 启动回显补 `connectTimeoutMs`（量具缺口，与上轮补的 `maxConcurrent` 同族）
- [x] G5 **复验（不设任何 `RERANK_*`）**：回显 `connectTimeoutMs=2000 maxConcurrent=16`；
      `executions=61` 且**无任何 fallback**（saturated 与 upstream 均为 0）
- [x] G6 单测 **285/285**、真实 E2E **22/22**
- [x] G7 **补上"重排对 TTFT 的净代价"**（同期配对）：均值 **+1.55s**（8/16/24 = +1.15/+1.43/+2.08s）
- [x] G8 记录的**干扰段**：12:56~13:06 TTFT 高一个数量级，**关掉重排同样高** → 判为环境，非本次改动
- [x] G9 压测脚本加 `SKIP_PREREQ=1`（关重排的对照轮需要），断言本身**保留不删**

## H. 仍未验证

- [ ] H1 厂商 rerank 并发上限（16 档两轮未见限流，推不出"不会限流"）
- [ ] H2 多实例（许可/熔断/超时均为进程内状态）
- [ ] H3 24 档 p95 ≈10.5s 未归因（既有，三轮重现）
- [ ] H4 12:56~13:06 网络为何变慢（只到"关掉重排也一样"这一层）
