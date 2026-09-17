# 验收清单：上游故障期日志降噪

> 逐条可判定。做完对照自检，未通过项注明原因。

## A. 前置测量（已完成，留作基线）

- [x] A1 量化基线：`logs/soak/app-20260916c.log` = **1.91 GB / 16,008,138 行**
- [x] A2 定位体积构成：**堆栈行 15,031,336 行（93.9%）**
- [x] A3 定位噪音源（Top）：`MessageAggregator` ERROR 104,157 / `SpringAiRetryAutoConfiguration` WARN 53,883 / `DashScopeEmbeddingModel` ERROR 53,561
- [x] A4 确认"我们自己的降级日志**已无堆栈**"（`MemoryVectorStore` 等）→ 不动它们
- [x] A5 确认基线速率：**≈3.6 GB/h**（1.78 GB / 30 min）
- [x] A6 排查"SDK 是否在偷偷重试"：`spring.ai.retry.max-attempts=1` 已正确设置，
      `Retry count: 1` 是"一次即失败"的记录，**非重复重试** → 不是缺陷

## B. 实施

- [x] B1 新增 `LogThrottleFilter`（logback `TurboFilter`，白名单 + 时间窗限流）
- [x] B2 白名单外 logger **永不 DENY**（不改变其他任何日志行为）
- [x] B3 窗口内保留 `max-per-window` 条**完整样本**（含堆栈）
- [x] B4 窗口结束对"被抑制条数"输出**一条汇总**（专用 logger 名，避免递归）
- [x] B5 `enabled=false` 时全量放行（一键回退）
- [x] B6 新增 `logback-spring.xml`（含 Spring 属性注入 + 默认 console 输出保持）
- [x] B7 `application.yml` 增补 `app.logging.throttle.*` 并写明 ADR 引用与回退方式
- [x] B8 参数默认值：窗口 10s、每窗 3 条、白名单 3 个第三方 logger

## C. 验证（硬性：单测 + 真实复现 + 回归）

- [x] C1 新增 `LogThrottleFilterTest`：窗口内前 N 放行、第 N+1 起 DENY
- [x] C2 单测：窗口滚动后计数重置
- [x] C3 单测：白名单外 logger 永远 NEUTRAL
- [x] C4 单测：`enabled=false` 全放行
- [x] C5 编译通过
- [x] C6 全量单测全绿（基线 196/196，新增后应 >196 且全绿）
- [x] C7 **真实复现**（非 mock）：主模型指向假上游（恒 500）+ `app.llm.fallback-enabled=false`
      ⇒ 每个请求必失败；24 并发 **SSE** 请求（`MessageAggregator` 只在流式聚合时打日志）。
      两轮工况一致：均 **24 请求全失败**、假上游收到 **21 / 20** 次请求 → 可比
- [x] C8 实测降幅：日志增量 **488 KB → 35 KB（−93%）**；`MessageAggregator` 可见行 **60 → 3**
      （稳态高流量下降幅更大：限流把"每请求约 2.5 条"压成"每 10s ≤3 条"；
      按长压基线 117 条/s 推算，稳态降幅 ≈99%）
- [x] C9 可观测性未丢：白名单 logger **仍保留 3 条完整样本**（含堆栈，见 `logs/noise-ON-*.log`）
- [x] C10 可观测性未丢：日志中出现 `app.log.throttled` 汇总条，原文为
      「…MessageAggregator 在最近 10000ms 内被抑制 **57** 条（每个窗口保留 3 条完整样本…）」
      —— 与保留的 3 条**算术闭合**（3 + 57 = 60，等于限流关那轮总数）
- [x] C11 可观测性未丢：Prometheus 指标正常增长（复现中抓到
      `llm_call_total{outcome="fail",provider="primary"}=24`、`llm_retry_total{outcome="scheduled"}=20`）
- [x] C12 真实 E2E 22/22（`outputs/e2e-live-lognoise.json`，回归不退化）

## D. 文档与收尾

- [x] D1 `docs/03` 新增 ADR-33（可观测性策略：对已知重复的第三方噪音允许限流）
- [x] D2 `docs/09` §8.9(7) 的"未修复/待决策"改为"已修复"，补实测对比数据
- [x] D3 `docs/phase6-log-noise/` 三件套落档
- [x] D4 提交并推送（443 绕过）；工作区干净；无密钥入库
- [x] D5 项目记忆更新（噪音源清单 + 回退开关）

## E. 明确不做（防范围蔓延）

- [x] E1 未改任何业务逻辑（ChatExecutor / LlmGateway / 检索链路）
- [x] E2 未动我们自己的降级日志（它们已无堆栈、是结构化信息源）
- [x] E3 未引入新依赖
- [x] E4 未做日志轮转/归档策略（本次只治"产生速率"）
- [x] E5 **未删除历史日志文件**（`logs/` 属用户数据，需单独确认后再清）
