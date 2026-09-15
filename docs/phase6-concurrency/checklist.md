# Phase 6 · 高并发性能工程 — 验收清单（checklist）

> 用法：逐条勾选，每行独立可判定。`- [ ]` 未通过 / `- [x]` 已通过。
> 基线锚点见 `spec.md` §1（均为 2026-09-15 本机实测）。

## A. 前置测量（已完成，作为基线锚点）

- [x] A1 厂商并发上限已测得（直连阶梯，绕过应用）：有效上限 ≈ **24**，≤16 全通、32 起出现 429
- [x] A2 应用侧多用户阶梯已测得（8/24/32/48），且**全程零厂商 429**
- [x] A3 三层阈值现状已核实：闸门 24 / 网关并发 24 / 图线程池 24 / 每用户突发桶 8 令牌 1/s
- [x] A4 更正既有错误结论：`burstCapacity=8` 并非"先于"闸门生效，二者是**每用户 vs 全局**两个维度
- [x] A5 确认突发桶真实触发条件：仅"同一用户同时并发"触发，顺序连发 12 次未触发

## B. 规格冻结（三件套，实施前必须）

- [x] B1 `docs/phase6-concurrency/tasks.md` 已写（目标 / 范围 / 不做 / 待确认 / 风险）
- [x] B2 `docs/phase6-concurrency/spec.md` 已写（现状测量 / 核心问题 / 候选方案 / 复现命令）
- [x] B3 `docs/phase6-concurrency/checklist.md`（本文件）已写
- [x] B4 用户已答复 spec §6 三项待确认（2026-09-15）：① 容量目标 = **不追求数字**（只要求不崩 + 拒绝有说明）
      ② 超容行为 = **B 有界排队** ③ 验收线 = **按在途分档重写**（见 spec §4）

## C. 实施（B4 已确认，实施完成）

### C1 阈值一致性与自解释
- [x] C1.1 启动时校验 `app.online.max-inflight ≤ app.llm.max-concurrent-calls`，违反则启动失败并输出可读原因
      （`config/CapacityGuard.java`：越界时抛出并带上实际数值）
- [x] C1.2 启动日志输出容量摘要
      （实测：`capacity: gate=24 gateway=24 queue=24 waitMs=3000 | vendor concurrency <= 24 (measured 2026-09-15, glm-4-flash)`）
- [x] C1.3 单测覆盖：合法配置通过、非法配置（gate > gateway）快速失败（`CapacityGuardTest` 4 项）

### C2 超容行为（**B 有界排队**）
- [x] C2.1 按 B 实现，且不改变已通过的行为 —— 真实 E2E 22/22（含 RAG、Agent、沙盘、三牌、越权拦截）
- [x] C2.2 等待发生在**准入前**（`ChatEntry` 订阅阶段），不占用图线程池；`wait-ms=0` 可一键回退为立即拒绝
- [x] C2.3 队列有上限（`queue-capacity`）、等待有上限（`wait-ms`）；超时拒绝文案含已等待时长与 `retryAfterSec`
- [x] C2.4 指标：`online.inflight.current` / `online.queue.depth`（Gauge）、`online.inflight.wait_ms`（Timer）、
      `online.inflight.{entered,entered_after_wait,queued,queue_full,wait_timeout,interrupted,rejected}`
- [x] C2.5 单测覆盖：等待后放行、等待超时、队列满立即拒绝、`wait-ms=0` 退化、正常路径不排队、超额释放防御
      （`OnlineLoadTrackerTest` 12 项全绿）

### C3 压测工具修正
- [x] C3.1 `scripts/loadtest.py` 支持 `--users N`（N 个独立用户，测全局容量）
- [x] C3.2 单用户模式保留并**明确标注**"测每用户突发桶，非全局容量"
- [x] C3.3 新增 `--warmup`（默认 1 轮，不计入统计，行尾标注"← 预热轮"）
- [x] C3.4 拒绝分类拆分：过载 4003（当前咨询较多/系统繁忙）与限流 429（请求过于频繁）分别计数；
      SSE 下按流内 `event:error` 判定，不误记为成功

## D. 验证（硬性：编译 + 单测 + 真实 E2E + 平台复验）

- [x] D1 `mvn -DskipTests compile` 通过
- [x] D2 全量单测通过：**170/170**（160 + 新增 10）
- [x] D3 真实 E2E `scripts/e2e_live.py` **22/22**（`outputs/e2e-live-p6.json`）
- [x] D4 24 并发基线：24 ok / 0 拒 / 厂商 429=0（改造后 32 并发亦为 **32 ok / 0 拒**）
- [x] D5 48 并发新行为实测：改造前 24 ok / **24 拒** → 改造后 **47 ok / 1 拒**（另一次 40 / 8）；
      64 并发 → 48 ok / 16 拒（= 24 在途 + 24 排队，超出者 `queue_full`，与设计一致）
- [x] D6 Langfuse 平台复验 **11/11**（`outputs/verified-langfuse-p6.json`，按本轮真实 traceId 反查）
- [x] D7 证据落档：`spec.md` §8 阶梯表 + `docs/09` §6（口径）与 §8.8（实测）；原始输出在 `outputs/`

## E. 文档与收尾

- [x] E1 `docs/09` §6 验收线按在途分档重写
- [x] E2 `docs/03` 新增 **ADR-29**：容量口径与超容行为决策（含厂商上限实测证据）
- [x] E3 `docs/09` §8.8 补本轮实测记录（厂商探测 + 多用户阶梯 + 有界排队前后对比 + 拒绝分类）
- [x] E4 提交并推送；无密钥入库（`application-local.yml` 仍不入库）
- [x] E5 项目记忆更新（容量锚点 24、突发桶语义更正、有界排队语义、压测工具用法）

## F. 明确不做（防止范围蔓延）

- [x] F1 未更换/叠加 LLM 厂商
- [x] F2 未引入 MQ、未做跨实例容量协调（ADR-23 既定）
- [x] F3 未改检索 / 提示词 / 业务编排；未启用 rerank（默认关，已实测负收益）
