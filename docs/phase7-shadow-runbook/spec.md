# phase7-shadow-runbook · 方案、实测与操作手册

> 状态：已交付（2026-09-20）。**产品默认未变**：`app.jev.guardrail.mode` 仍为 `off`。

## S1 审计补全（T2）

`ChatEntry.guardrailCheck` 的词典分支此前只做 `meters.counter(...)`，**不写 `guardrail_event`**。
E2E 里 "BLOCKED 事件数 = 0" 就是这个原因——拦是拦住了，只是没记账。
结果是审计表**不完整**：`select count(*) from guardrail_event where action='BLOCKED'` 看不出
"有多少条消息被词典兜底拦下"，与 ADR-6"用于误报率监控"的初衷不符。

补一行（`recorder.record(prompt, 3, verdict.ruleId(), ACTION_BLOCKED)`），**只补 L3**：

| 分支 | 补不补 | 理由 |
| --- | --- | --- |
| 词典 `level >= 3` | **补** | 会 `throw`，请求不会继续走到 LLM → `GuardrailAdvisor` 根本不会跑到 → **不可能双计** |
| 词典 `level 1/2` | **不补** | 不阻断，请求继续走到 LLM，而那条路径已由 `GuardrailAdvisor` 记 `LOGGED` → 补了就**双计** |

`rule_id` 原样取自词典判定（如 `self_harm` / `abuse` / `manipulation_intent`），便于按规则统计。
`signal_score` 留空——词典路径没有第二信号概率，**不写 0 冒充**（0 是有意义的分数）。

**对照实验**：摘掉这一行 → `ChatEntryTest.dictionaryL3BlockIsAudited` **精确失败**（且只有它），
恢复后与备份逐字节一致、复跑全绿。

## S2 读数工具（T3）

`scripts/guardrail_shadow_report.py`。它把三件容易写错的事固定下来：

1. **失败单列**：没有 `signal_score` 的事件（`no_result`）算"没测到"，**不算低风险**。
2. **口径分离**：
   - **口径 A**「越阈样本里的误报占比」≈ 不精确度 —— 只需标越阈的样本；
   - **全量误报率** —— 必须**同时随机抽未越阈的样本**标注，分母是全部标注过的消息。
     脚本在输出里直接写明"这不是全量误报率，还需要什么才能算"，不让人读错。
3. **置信区间**：给 Wilson 95% 区间（不是点估计），并在 n 过小时直接提示"别拿点估计下结论"。

用法：

```bash
# 看总体（不需要标注）
MYSQL_PASSWORD=xxx python scripts/guardrail_shadow_report.py --window-hours 24

# 导出待复核清单
... --export-review outputs/review.jsonl --review-size 200

# 复核后回填（每行 {"event_id":N,"label":"positive|negative|ambiguous"}）
... --label-file outputs/labels.jsonl --output outputs/shadow-report.json
```

**原文怎么取**（审计表只存 `content_hmac`，这是设计如此，不改）：

```sql
select m.content, g.user_id from message m
  join guardrail_event g on m.content_hmac = g.content_hmac
 where g.id = <event_id>;    -- content 是密文，用应用侧密钥解密后读
```

**实测（真实数据，本地库 24h 窗口）**：窗口内 57 条 `SHADOW` 事件、0 条 `no_result`、
阈值全部记录为 0.6000、分数 `[0,0.1):54 [0.5,0.6):1 [0.6,0.7):1 [0.8,0.9):1`、
**越阈 2 条（3.51%）**。导出 57 条复核清单 → 回填 3 条标注 → 精确率 100%（n=2，95% CI 34%~100%，
脚本自动提示区间过宽）。**这条链路端到端跑通了，但样本量说明它只是"能跑"，不是"有结论"。**

## S3 更正：超时后我们到底有没有中止请求（T4）

我在上一轮写过"当前实现没有主动取消在途请求"，**这句是错的**，且我当时没验证就写了。

**怎么发现的**：给假上游加了"对端是否断开"的探测——延迟期间用 `select()` 轮询 socket，
读到 `b""`（FIN）就记为 `client_disconnected` 并提前结束，不再白占线程。重跑 `slow` 轮：

```
假 Jev 上游侧统计: {'current': 0, 'max': 8, 'total': 12,
                    'by_mode': {'slow': 12}, 'client_disconnected': 12}
```

**12/12 全部被客户端中止，`current` 归零。** `JevClient` 用的是同步 `http.send()` +
`HttpRequest.timeout(3s)`，JDK 在响应超时时**会中止交换并关闭连接**——我们这边是干净的。

那"积压 12 条"是怎么来的？**老版假上游只会 `time.sleep` 到点再写响应，根本感知不到对端已关闭**，
于是它的 `current` 永远不降。**是量具瞎了，不是应用有问题。**

> 📌 **通用教训（已写进技能）：计数量具要先能测到现象，否则读数本身就是误导。**

仍然成立、但性质不同的一点：**对方是否停止计算/计费不受我们控制**，我们只能保证自己不继续等。

## S4 操作手册：怎么把它推到生产

### 第 0 步：知道代价（已有数，不用再测）

- `shadow` 使 TTFT **+0.28s（p50）/ +0.30s（p95）**（`docs/phase7-prod-sim` §S5 Q1）。
- Jev 慢/挂时**固定 +3s**（= `app.jev.timeout-ms`），用户仍拿到正常回复、无 5xx、不误升级。
- Jev **不经** `LlmGateway` 的闸门/熔断/AIML ⇒ 它是链路上唯一不受容量治理约束的外部依赖；
  并发 ≤24（= 厂商上限）未顶穿，**厂商限流阈值未探**。

### 第 1 步：开观测（改配置，不发版、不影响用户）

```
JEV_ENABLED=true
JEV_API_KEY=<密钥，只走环境变量>
JEV_GUARDRAIL_MODE=shadow
```

**跑多久**：至少**一个完整流量周期**，建议 **≥7 天**。危机表达稀疏（本地库里基率 <0.013%），
跑几小时大概率一条越阈都没有——那不是"没有误报"，是"没有数据"。

### 第 2 步：读三个数

```bash
python scripts/guardrail_shadow_report.py --window-hours 168 --only-jev \
       --export-review outputs/review.jsonl --review-size 300
```

| 要看的数 | 从哪来 | 不合格的样子 |
| --- | --- | --- |
| **① 失败率**（no_result / 总数） | 报告脚本的 `no_result` | 明显 >0 ⇒ Jev 不稳，**先别开 enforce**，那点概率是"没测到"不是"低风险" |
| **② 越阈样本的精确率** | 复核 `review.jsonl` 并回填标注 | n 太小 ⇒ 区间极宽（见 S2 实测），**不能下结论**，继续攒 |
| **③ 全量误报率** | 需**额外随机抽未越阈的消息**标注 | 只标了越阈的 ⇒ 你手里只有口径 A，**拿不到这个数** |

**③ 的具体做法**（这一步最容易省，省了就等于没有结论）：
从 `guardrail_event` 里随机抽 N 条 `score < threshold` 的事件，与越阈的那批**一起**标注，
分母取"全部标注过的条数"。抽多少：至少让"0 误报"仍有意义的量级——
按 rule of three，想证明误报率 <1% 需要 **≥300 条**标注。

### 第 3 步：判定门槛（三个都要过）

我给的建议值（**是判断，不是实测**，写出来是为了可被推翻）：

1. **失败率 ≤1%** —— 超过就说明这条依赖不稳，先修稳定性。
2. **越阈样本精确率 ≥70%，且 Wilson 下界 ≥50%** —— 低于这个，"被转介的人里一半其实不需要"，
   会明显伤害正常用户（`docs/phase7-prod-sim` §S5 Q4 的决策量表）。
3. **拦截占比在预期内** —— 用 `docs/phase7-prod-sim` 的运营点表对一下：
   基率 × 召回 + (1−基率) × 误报。若拦截占比明显超出预期，说明误报率比想象的高。

三条都过 → 用**同一批流量**重新标定阈值（ADR-37 决策 3：hold-out 显示 0.6 切在正例分布中间，
0.3~0.4 更合理），再推 `enforce`。

任一条不过 → 保持 `shadow`，攒数据；或先改阈值再观测（`shadow` 下改阈值不改用户行为，安全）。

### 第 4 步：回滚

把 `JEV_GUARDRAIL_MODE` 改回 `off` 即可（配置项，不发版）。
`off` 下**一次 Jev 请求都不发**（单测 `offModeNeverCallsHttp` 用桩计数守住），行为等于接入前。

## S5 实测与回归

| 项 | 结果 |
| --- | --- |
| 对照实验（摘掉审计行） | `dictionaryL3BlockIsAudited` **精确失败**，只它一个；恢复后逐字节一致、复跑全绿 |
| 报告脚本端到端 | 真实库 24h：57 条 SHADOW / 0 条 no_result / 越阈 2 条；导出→标注→回填全通 |
| 影子 E2E | 见 `docs/09` §8.19：`BLOCKED 事件数` 由 **0 → 1**（审计补全生效的端到端证据） |
| 单测 / E2E | 见 §8.19 记录 |

## S6 局限

- 门槛里的 **70% / 50% / 1% 是我给的判断值**，不是实测；它们应当被真实数据推翻或确认。
- 报告脚本**不解密**（密钥在应用侧）——原文要靠 `content_hmac` 关联 `message` 表后由有权限的人解。
- "≥7 天 / ≥300 条标注"是**经验值**（由基率与 rule of three 推的），不是这个系统实测出来的。
- 仍未验证：Jev 厂商限流阈值、成本、多实例下的观测口径。
