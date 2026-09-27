# phase14 · checklist

> **本 phase 已于 ADR-50（docs/03）退役，不实施。**
> 三件套保留为历史记录；判据与实测数据见 `outputs/routing-premise-*.json`。
> 本文件不再代表"待办"，下面是逐项关闭的原始记录，仅备查。
>
> 核心判据：路由层唯一可能的价值（省钱 / 降延迟 / 提质量）在实测里三项全部不成立 ——
> 现主模型 pricing=0、速率 40.7~48.7 tok/s、答案正确性 0.794 **高于**降级候选 0.719。
> 把任何请求挪走只会更贵、更慢、或更差。

> 逐条可勾选，一行一条，独立判定通过与否。**DoD = 编译 + 相关单测 + 触达面 E2E，三者缺一不可**
> （AGENTS.md §2）。未通过项必须在交付说明里**显式写出原因**，不得默认通过。

## A. 文档门禁（实现前）

- [ ] A1 `tasks.md` / `spec.md` / `checklist.md` 三件套已写齐
- [ ] A2 `tasks.md` 的 3 条待确认项已由 Mitsuki 拍板并写回文档
- [ ] A3 新增技术决策已在 `docs/03-技术决策记录.md` 补 ADR（下一个编号 = 48）

## B. T1 路由决策层

- [ ] B1 `ModelRoute` 为不可变 record，`target` 仅允许 4 个字面量
- [ ] B2 `RouteInput` 的 7 个字段全部来自**已存在**的判定结果，无新增 LLM 调用
- [ ] B3 决策函数为纯函数：无 IO、无时钟、无随机（同输入必得同输出）
- [ ] B4 单元测试：5 条决策矩阵各一例，逐字段断言 `target` + `reason`
- [ ] B5 单元测试：`reason` 越界字面量被拒（防止指标维度爆炸）
- [ ] B6 `app.llm.routing.enabled=false` 时，路由层对调用方完全透明
- [ ] B7 **对照实验**：还原实现 → 对应单测精确失败 → 恢复 → 复跑全绿
- [ ] B8 断言 `LlmGateway` 仍是唯一重试所有者（路由层无任何 retry/降级代码）
- [ ] B9 断言 `ModelRoute` 四个字段**都不进入** prompt 与响应体

## C. T2 影子态

- [ ] C1 Flyway **V25** 新增三列（`route_decision` / `route_reason` / `route_detail`）
- [ ] C2 默认 `off`，`shadow` 态**绝不改变真实流量**（target 一律 primary）
- [ ] C3 单测断言 `shadow` 态下 `ModelRoute.target` 恒为 `primary` 而 `reason` 仍被记录
- [ ] C4 三态可热切（`ROUTING_MODE` 环境变量），**不需重启**
- [ ] C5 指标 `llm.route{decision,reason,mode}` 已注册，维度值符合 spec §3.1 约束
- [ ] C6 span tag `llm.route.target` / `llm.route.reason` 可见（Langfuse 按 traceId 可反查）
- [ ] C7 真实流量跑通并产出影子读数：**有多少请求会被改道、改到哪**

## D. T3 视觉治理补齐

- [ ] D1 `VisionChatClient` 的 wire 格式**逐字未变**（base64 data URL，ADR-11）
- [ ] D2 视觉请求纳入并发闸门，与 LLM 通道**分通道**取阈值（ADR-42，不得混用）
- [ ] D3 视觉请求纳入熔断，指标 `llm.vision.call{outcome}` 可观测
- [ ] D4 **未引入任何新的 `BizException` 码位**
- [ ] D5 治理补齐后，视觉路径原有行为（成功/失败语义）逐字不变

## E. 验证（DoD）

- [ ] E1 编译通过
- [ ] E2 相关单测全绿（新增 + 既有 295 例无回归）
- [ ] E3 **真实 E2E 22/22 通过**（触达 LLM 通道与视觉路径，必须真实依赖，不用 mock）
- [ ] E4 真实对话抽样：改道判定的 `reason` 与实际请求特征一致（人工核对 ≥10 例）
- [ ] E5 未验证项已**显式列出**（如有）

## F. 交付

- [ ] F1 `docs/03` 已补 ADR，编号连续无跳号
- [ ] F2 本清单逐条自检完成，未通过项写明原因
- [ ] F3 汇报含**端到端实测证据**（命令输出 / 指标截图 / 状态码），非"应该没问题"
- [ ] F4 提交前已看全量 `git status`（**禁 `git stash`**，用 `cp` 备份）
