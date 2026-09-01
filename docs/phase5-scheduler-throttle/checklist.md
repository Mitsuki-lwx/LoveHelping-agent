# checklist.md — 后台调度器节流与错峰 验收清单

> 每一项可勾选、可观测。以 curl / SQL / grep / Langfuse API 为准，不许写"功能完整"。
> 修改前基线（2026-09-01 实测，20 分钟窗口）：后台 embedding 89 + chat 6 = 95 次调用，在线请求 1 次；单轮 max=118.7s。

## 一、配置与开关

- [ ] `grep -c "app.scheduler" src/main/resources/application.yml` ≥ 1，且每项带默认注释
- [ ] `SchedulerBudget` 类存在：`grep -c "class SchedulerBudget" src/main/java/cn/lwx/lwxaiagent/infrastructure/scheduler/SchedulerBudget.java` = 1
- [ ] 主开关生效：设 `app.scheduler.master-enabled=false` 启动 → 15 分钟内日志 `grep -c "SCHED_ROUND"` = 0
- [ ] 单调度器开关生效：`app.scheduler.reflect.enabled=false` → `grep -c "SCHED_ROUND name=reflect"` = 0，而 `name=extract` 仍 > 0

## 二、配额与批量

- [ ] 配额硬上限：设 `app.scheduler.llm-budget-per-minute=2` → 单轮日志 `llmCalls=` ≤ 2（`grep "SCHED_ROUND" | awk` 校验）
- [ ] 批量上限可配：`app.scheduler.reflect.batch-limit=3` 且存量候选 > 3 → 单轮 `processed=` ≤ 3
- [ ] 时间预算：`app.scheduler.max-run-ms=1000` → 单轮 `elapsedMs=` ≤ 1500（允许最后一次调用溢出）
- [ ] 配额耗尽有明确日志：`grep -c "budget exhausted" ` > 0

## 三、空转退避

- [ ] 无候选时零 LLM 调用：清空候选（或等待自然为空）→ 该轮 `llmCalls=` = 0 且 Langfuse 该时段无新增 `background` trace
- [ ] 连续空转后间隔变长：连续 ≥ 3 轮 candidates=0 → 日志出现 `backoff=x2`（随后 x4、x8 封顶）
- [ ] 有候选立即复位：制造一个待反思会话 → 下一轮 `backoff=x1` 且 `processed=` ≥ 1

## 四、无回归（在线链路）

- [ ] `mvnw test` 全绿（含新增 `SchedulerBudgetTest`）
- [ ] 冒烟 §7.1 认证链路 4/4 通过
- [ ] 冒烟 §7.2 SSE 流式聊天通过（允许 LLM 抖动，连续 2 次失败才算不通过）
- [ ] 冒烟 §7.3 RAG 聊天通过
- [ ] 冒烟 §7.4 会话记忆 3/3 通过
- [ ] 冒烟 §7.6 安全负向用例 4/4 通过
- [ ] 冒烟 §7.9 话术三级通过
- [ ] 冒烟 §7.10 编排图真实入口通过
- [ ] 记忆萃取功能仍生效：新会话对话后，静置一轮调度 → `SELECT COUNT(*) FROM conversation_summary` 增加
- [ ] 技能反思功能仍生效：`SELECT COUNT(*) FROM evolution_skill` 不减少、无异常清空

## 五、可观测性改善（本次的核心收益）

- [ ] 指标暴露：`curl -s localhost:PORT/actuator/metrics/scheduler.round` 返回非空
- [ ] Langfuse 后台标记：调度 trace 带 `background=true`，可用 Langfuse API 过滤区分
- [ ] **核心验收**：启动后静置 15 分钟，用 Langfuse API 统计窗口内 trace：
      `GET /api/public/traces?fromTimestamp=<15min前>` → 后台 LLM 调用数较基线（95 次/20 分钟）**下降 ≥ 50%**
- [ ] 在线 trace 不再被淹没：同一窗口内 `http get /Love_app/*` trace 可被直接查到（不被 100 条上限挤出）
- [ ] 单轮时长下降：Langfuse 中 `task *` 观测的 max latency 从 118.7s 降至 < 60s

## 六、安全与合规

- [ ] 预算组件异常时 fail-open（不阻断业务功能），且有 WARN 日志
- [ ] 无新增明文密钥（AGENTS.md §3 安全底线）
- [ ] 无新增越权面：调度器按 user_id 处理数据时仍走既有归属校验（未放宽）
- [ ] 无 DDL 变更：本需求不涉及建表/改表（`git diff --stat src/main/resources/db/migration` 为空）
