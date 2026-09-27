# phase14 · spec — LLM 路由决策层

> 对应 `tasks.md`。本文定义**接口 / 数据 / 状态机 / 不变式**。
> 契约先定，实现后写；实现若偏离本文，按 AGENTS.md §1 回本文改，不允许代码与文档静默偏离。

## 1. 现状契约（改动前必须成立的事实）

| ID | 事实 | 证据 |
| --- | --- | --- |
| C1 | `@Primary ChatModel` = `LlmGateway`，消费者零改动即获得重试/降级/计量 | `ChatModelConfig:38-42` |
| C2 | primary = `openAiChatModel`（prod: bigmodel `glm-4-flash`），fallback = `deepSeekChatModel`（dashscope `qwen-plus`） | `LlmGateway:45-47`、`ChatModelConfig:51` |
| C3 | 切换条件是**失败**（`canFallback`），不是任务类型 | `LlmGateway:108`、`185` |
| C4 | 熔断按 provider 独立记账 | `LlmGateway:62-63`（`primaryCircuit` / `fallbackCircuit`） |
| C5 | 并发许可**覆盖整条重试+降级链路**，不是每次尝试各借还 | ADR-31，注释见 `LlmGateway:79-84`、`174-179` |
| C6 | fallback 在 local 默认**关**、prod 默认**开** | `application.yml:220` / `application-prod.yml:23` |
| C7 | 视觉走独立旁路，**不经 LlmGateway** | `VisionChatClient:39-49` |

## 2. 目标：不引入新的重试所有者

**核心不变式**：`LlmGateway` 仍是**唯一**的重试所有者（ADR-23）。路由层只做**选择**，不做重试、不做降级、不碰熔断。

理由：记忆里已有明确教训——"重试所有者"一旦分裂，故障会被放大成乘法（ADR-23 原文）。
路由若自带重试，等于造出第二个所有者。**这是本设计最重要的约束。**

## 3. 数据契约

### 3.1 `ModelRoute`（不可变决策记录）

```java
public record ModelRoute(
    String  target,        // "primary" | "fallback" | "vision" | "bypass"
    String  reason,        // 机器可读判定依据，如 "simple_qa" / "tools_required" / "vision_media" / "provider_circuit_open"
    String  reasonDetail,  // 人类可读补充，可为空
    double  costTier,      // 预期成本档，0=最便宜 … 1=最贵
    double  latencyTier    // 预期延迟档，同上
) {}
```

不变式：
- `target` 只能取上述 4 个字面量之一；
- `reason` **必须**是有限枚举（进指标标签，不可自由文本——否则指标维度爆炸）；
- `reasonDetail` 才承接自由文本，**仅进 span，不进指标标签**。

### 3.2 决策输入（全部为请求时已存在的量，零额外 LLM）

```java
public record RouteInput(
    boolean toolsRequired,   // 来自 CapabilityRouter.needTools
    boolean adviceRequested, // 来自 CapabilityRouter.isAdviceRequest
    boolean simpleQuestion,  // 来自 CapabilityRouter.isSimpleQuestion
    boolean visionMedia,     // mediaIds 非空
    int     messageLength,
    boolean primaryCircuitOpen,
    boolean fallbackCircuitOpen
) {}
```

不变式：**输入全部来自已存在的路由判定结果，不得为此新增任何 LLM 调用或规则匹配。**
若将来发现某类请求无法用现有量判定，正确做法是**在 `CapabilityRouter` 里加一个显式判据**，
而不是在路由层藏一个新规则——否则判定逻辑会散落两处，未来没人知道该改哪。

### 3.3 决策矩阵（三态语义同 ADR-36）

| 条件 | `target` | `reason` | 备注 |
| --- | --- | --- | --- |
| `visionMedia` | `vision` | `vision_media` | 走 `VisionPort`，不参与主备选择 |
| `toolsRequired \|\| adviceRequested` | `primary` | `tools_required` / `advice_requested` | 复杂路径不省 |
| `simpleQuestion && !primaryCircuitOpen` | `primary` | `simple_qa` | 影子期重点观测此类 |
| `primaryCircuitOpen && !fallbackCircuitOpen` | `fallback` | `provider_circuit_open` | 与 C4 的熔断状态一致 |
| 两者都 open | `bypass` | `all_circuits_open` | 交还 LlmGateway 走既有 `CircuitOpenException` 路径 |

不变式：矩阵**必须是纯函数** `RouteInput -> ModelRoute`，无 IO、无时钟、无随机。
理由：路由决策要可复现（同输入必得同输出），否则观测数据无法解读——
这与记忆里"量具先标定"的教训同源。

## 4. 状态机

```
     ┌──────────┐
     │   off    │  默认。决策照做、照记，target 一律 primary —— 等价于无路由
     └────┬─────┘
          │ 显式开启
     ┌────▼─────┐
     │  shadow  │  决策 + 落库(route_decision/route_reason)，**绝不改流量**
     └────┬─────┘
          │ 影子数据证明质量持平 + 覆盖度足够
     ┌────▼─────┐
     │ enforce  │  决策结果真正决定 target
     └──────────┘
```

**回滚路径**：`enforce → shadow → off` 三级均可**运行时热切**（环境变量），不需重启、不需回滚发布。

## 5. 数据落库

Flyway **V25**（ADR-13：DDL 只经 Flyway，禁止改 `schema.sql`）：

```sql
ALTER TABLE chat_message
  ADD COLUMN route_decision VARCHAR(32)  NULL COMMENT 'phase14 路由决策结果（off/shadow 时可能为空）',
  ADD COLUMN route_reason  VARCHAR(64)  NULL COMMENT '机器可读判定依据，进指标标签',
  ADD COLUMN route_detail  VARCHAR(255) NULL COMMENT '自由文本补充，仅进 span';
```

⚠️ 参照 ADR-36 的教训：**判定依据与阈值都要落两列**（此处即 `route_decision` / `route_reason`），
只有开 `enforce` 后才需要按它们做分组分析；`shadow` 期它们是"未来分析的原料"，
不落库就等于影子数据白采。

## 6. 可观测契约

| 信号 | 维度 | 用途 |
| --- | --- | --- |
| `llm.route` counter | `decision`（= `ModelRoute.target`）, `reason`, `mode` | 影子期核心读数：**有多少请求会被改道、改到哪** |
| `llm.route.target` gauge | `target` | 改道占比的实时水位 |
| span `llm.route.target` / `llm.route.reason` | — | Langfuse 侧按 traceId 反查单次决策 |
| `llm.vision.call` counter | `outcome` | T3 视觉治理补齐后的健康度 |

## 7. T3：视觉治理补齐的契约

**不改** wire 格式（ADR-11 的 base64 data URL 是实测验通的）。
**只加**治理：视觉请求纳入与 LLM 通道同口径的并发闸门与指标。

⚠️ **容量口径必须分通道**：记忆里已实测——LLM 通道厂商上限 ≈24，硅基流动 embedding ≥64 / rerank ≥32
**全零 429**（ADR-42）。视觉走的是主通道（bigmodel/sensenova 端点），**与 embedding 不是同一档**，
不得混用闸门值。

不变式：视觉治理**不得**改变视觉请求的成功/失败语义——只加"限流与计数"，
不引入新的失败码（避免 `BizException` 码位膨胀）。

## 8. 兼容与回滚

| 项 | 保证 |
| --- | --- |
| `app.llm.routing.enabled`（默认 `false`） | 关闭时 `ChatModelRouter` 对调用方**完全透明**，行为与改动前逐字一致 |
| 热切三态 | 环境变量 `ROUTING_MODE=off\|shadow\|enforce`，不需重启 |
| 决策记录不参与响应 | 无论哪一态，`ModelRoute` **绝不进入 prompt 或响应体**（防止变成回答内容） |
| 与 ADR-46 同源纪律 | 换掉有返回契约的组件时**逐字段核对**：`target`/`reason`/`costTier`/`latencyTier` 都要断言，<br>不能只断言"条数对了""调用发生了" |

## 9. 待确认项（承接 `tasks.md`，未拍板前不实现对应部分）

1. 答案质量红线是否 = "零下降"（我的建议：是，基线 0.885 不可交易）
2. `enforce` 是否只对请求子类开（我的建议：是）
3. 本轮是否只做 T1+T2+T3，T4 留给影子数据积累后（我的建议：是）
