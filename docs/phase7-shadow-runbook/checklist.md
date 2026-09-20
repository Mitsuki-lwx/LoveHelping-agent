# phase7-shadow-runbook · 验收清单（已逐条自检）

> 交付：2026-09-20。产品默认未变（`app.jev.guardrail.mode` 仍为 `off`）。

## A. 审计补全

- [x] A1 词典 `level >= 3` 拦截写入 `guardrail_event`（`action=BLOCKED`、`rule_id` 取自词典、`level=3`）
- [x] A2 `signal_score` 留空（词典路径没有第二信号概率，**不写 0 冒充**）—— 单测断言 `assertNull`
- [x] A3 落库仍**只存 content_hmac**，无原文 —— 单测断言 `assertNotEquals(原文, hmac)`
- [x] A4 **不补 L1/L2**，避免与 `GuardrailAdvisor` 的 `LOGGED` 双计
      —— 单测 `dictionaryL1L2IsNotRecordedHereToAvoidDoubleCounting`（`verify(never())`）
- [x] A5 对照实验：摘掉那一行 → `dictionaryL3BlockIsAudited` **精确失败且只它一个**；
      恢复后与备份逐字节一致、复跑全绿
- [x] A6 **端到端证据**：影子 E2E 的 `BLOCKED 事件数` 由 **0 → 1**（补全前该查询恒为 0）

## B. 读数工具

- [x] B1 `scripts/guardrail_shadow_report.py` 能读 `guardrail_event` 出：按 action/rule_id 计数、
      分数直方图、越阈条数与占比、**no_result 单列**
- [x] B2 **口径分离**写死在输出里：口径 A（越阈样本内误报占比）与"全量误报率"分开，
      并直接写明"这不是全量误报率，还需要随机抽未越阈样本"
- [x] B3 置信区间用 **Wilson**（不是点估计）；n 过小时打印"别拿点估计下结论"
      —— 单测式抽查：`wilson(9,10)=[0.596,0.982]`、`wilson(0,20)=[0,0.161]`
- [x] B4 `--export-review` 导出待复核清单，并给出**关联取原文的 SQL**（审计表不存原文，设计如此）
- [x] B5 `--label-file` 回填标注后算精确率；`ambiguous` 单列、不计入误报率
- [x] B6 真实数据端到端跑通：24h 窗口 57 条 SHADOW / 0 条 no_result / 越阈 2 条(3.51%)；
      导出 57 条 → 标注 3 条 → 回填出精确率 100%（n=2，Wilson 34%~100%，脚本自动提示区间过宽）
- [x] B7 修掉一个真 bug：pymysql 把 `DECIMAL` 读成 `Decimal`，导出时未转换导致 JSON 序列化失败
      （首跑时复核清单是空的）→ 统一收敛到 `num()` 转换

## C. 更正上一轮的错断言

- [x] C1 给假上游加"对端是否断开"探测（延迟期间 `select()` 轮询 socket，读到 FIN 即中止）
- [x] C2 实测 `slow` 轮：**`client_disconnected=12`、`current=0`、`max=8`** → 客户端**确实中止**了请求
- [x] C3 结论更正：~~"当前没有主动取消在途请求"~~ 是错的；`HttpRequest.timeout` 会中止交换并关闭连接
- [x] C4 上一轮那句断言已从 **5 处**全部更正：`docs/03` ADR-37、`docs/09` §8.18、
      `docs/phase7-prod-sim/{spec,checklist}.md`、`.workbuddy/memory/MEMORY.md`
- [x] C5 保留仍成立但性质不同的那半句：**对方是否停止计算/计费不受我们控制**
- [x] C6 通用教训已写进技能：**计数量具要先能测到现象，否则读数本身就是误导**

## D. 操作手册

- [x] D1 写明开启方式（三个环境变量，改配置不发版）、**建议观测时长**及其依据（基率稀疏）
- [x] D2 写明"读哪三个数"及各自**不合格的样子**（失败率 / 越阈精确率 / 全量误报率）
- [x] D3 写明**全量误报率的具体做法**（必须随机抽未越阈样本一起标注；≥300 条的由来是 rule of three）
- [x] D4 写明判定门槛（失败率 ≤1%、越阈精确率 ≥70% 且 Wilson 下界 ≥50%、拦截占比在预期内），
      并**显式标注这是我给的判断值、不是实测**
- [x] D5 写明回滚方式（改回 `off`，且 `off` 下零请求有单测守）
- [x] D6 写明代价（+0.28s / 故障 +3s 上界 / 不受容量治理约束 / 厂商限流未探）

## E. 回归与交付

- [x] E1 编译通过；全量单测 **247/247**（基线 245，+2）
- [x] E2 影子 E2E **4/4**；22 项 E2E **22/22**
- [x] E3 产品默认未变：`mode` 仍为 `off`；**未改阈值**（ADR-37 已说明理由）
- [x] E4 无对照实验残留（`grep 对照实验 src/main/java` 为空）
- [x] E5 无密钥入库；报告脚本密码只从 `MYSQL_PASSWORD` 读
- [x] E6 `docs/03` ADR-38、`docs/09` §8.19 已同步
- [x] E7 记忆文件已更新；提交推送并 `git ls-remote` 复核

## 未通过项（显式声明）

**无阻塞项未通过。** 必须一起读的限制：

1. **门槛里的 70% / 50% / 1% 是我给的判断值**，不是实测；它们是可被推翻的起点。
2. **观测时长 ≥7 天、标注 ≥300 条**是经验值（由基率与 rule of three 推的），非本系统实测。
3. 报告脚本**不解密**，原文要靠 `content_hmac` 关联 `message` 表并由有权限的人解。
4. 仍未验证：Jev 厂商限流阈值、成本、多实例下的观测口径。
5. B6 的"端到端跑通"只证明**链路可用**，样本量（越阈 2 条）远不足以支撑任何结论。
