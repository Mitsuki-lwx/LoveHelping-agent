# Phase 15 · 技术方案（OpenRouter 转主链 + 三级降级 + timeout 修正）

> 立项：2026-09-27　对应 `tasks.md`　决策记录见 `docs/03-技术决策记录.md` ADR-48

## 1. 实测数据（本方案的**唯一**依据，不含推测）

### 1.1 生成速率对比

| 端点 | 模型 | tok/s | 300 tok | 500 tok | 800 tok | 数据来源 |
|---|---|---|---|---|---|---|
| bigmodel | `glm-4-flash` | **11~14** | 21~27s | 36~45s | 57~73s | `probe_llm_timing.py`（两轮复现） |
| OpenRouter | `qwen/qwen-plus` | **42.9~51.1** | 5.9~7.0s | 9.8~11.7s | 15.7~18.7s | `probe_real_prompt_speed.py`（真实 8093 字符 system，n=3） |

⛔ **上一轮的教训**：探测脚本随手写的小 prompt 会**高估**能力。本表右列用
**从 `ChatExecutor.java` 提取的真实 `SYSTEM_PROMPT`（4970 字符）+ 模拟 RAG top8**，
实测 `prompt_tokens=3053`，与线上同量级。

### 1.2 prompt cache（这是换端点的**主要**收益，不是次要收益）

| 模型 | cached_tokens 命中 | 成本变化 |
|---|---|---|
| `qwen/qwen-plus` | **7/8（88%）**，实测 2816/2988 | $0.00079 → $0.00020（**降 74%**） |
| `qwen/qwen3-32b` | 0/3 | — |
| `deepseek/deepseek-chat` | 0/3 | — |
| `deepseek/deepseek-v4-flash` | 0/3 | — |
| bigmodel `glm-4-flash` | **字段都不存在** | — |

真实负载下第 3 次：`cached=2944 / prompt=3053`，成本 $0.001035 → $0.000358（**降 66%**）。

⛔ **方法论**：上一轮我犯的错是"看到字段就以为有能力"。这里**必须实测命中**，
字段存在 ≠ 缓存可用（`qwen3-32b` 有 `cached_tokens` 字段但恒为 0）。

### 1.3 并发（绕过应用直连上游，阶梯探测）

| 并发 | 成功 | 429 | p50 |
|---|---|---|---|
| 8 / 16 / 24 | 全部 | **0** | 10.1 / 9.8 / 9.9s |
| 32 / 48 / 64 | 全部 | **0** | 10.2 / 10.8 / 10.4s |
| 96 | 96/96 | **0** | 10.9s |

对比 bigmodel：32 就开始 7~10/32 得 429。
→ **OpenRouter 未探到硬上限**。`max-concurrent-calls=24` 现在明显保守，但**本 phase 不改**（见 tasks §5）。

### 1.4 能力与其他

- ✅ `tool_calls` 正常返回（`qwen3-32b` 实测 `search_knowledge`）→ Agent 工具链可用
- ✅ `usage` 比 bigmodel 多：`cost` / `prompt_tokens_details.cached_tokens` / `cost_details`
- ⛔ `claude-sonnet-4.5` / `gpt-4o-mini` / `gemini-2.5-flash` **全部 403**（provider ToS）
  → **这个 key 可用范围以国产模型为主**，选型必须限定在实测通过的模型上

## 2. 方案

### 2.1 降级链：两级 → 三级

现状（`LlmGateway.java:93-114`）只支持 primary → fallback：

```
现在：  bigmodel(primary) ──失败──> dashscope qwen-plus(fallback) ──失败──> 抛错
改后：  openrouter qwen-plus ──失败──> dashscope qwen-plus ──失败──> bigmodel glm-4-flash
        [primary]                    [fallback-1]                  [fallback-2，最后兜底]
```

**为什么 bigmodel 放最后而不是直接删**：
用户明确要求"放在最低等级选择"。而且它是**唯一一个已在本仓跑通过、有 key、已做过容量实测**的端点——
真断网/全挂时它仍是一个已知能用的兜底。删掉会让"全挂"从"降级文案"变成"硬 5000"。

### 2.2 不变式（ADR-23 保持不动）

- ✅ `LlmGateway` 仍是**唯一**重试所有者
- ✅ 业务层、路由层**不感知**降级链，不自己重试
- ✅ 每个 provider 有**独立熔断器**（现有 `primaryCircuit` / `fallbackCircuit` 模式扩到三个）
- ✅ 并发许可覆盖**整条**重试+降级链路（ADR-31 发现二，不许退化成"每次尝试各借还"）
- ✅ 流式：一旦已 emit 就不重放（现有 `emitted` 语义，三级链同样适用）

### 2.3 timeout 定值（**按实测推，不拍**）

以 §1.1 的**最低** tok/s = 42.8 计（不用中位，留余量）：

| 目标 completion tokens | 所需秒数 |
|---|---|
| 300（实测中位 262~309） | 7.0s |
| 500（prompt 要求的上沿） | 11.7s |
| 800（长回答 + 行动卡展开） | 18.7s |
| 1200（极端） | 28.0s |

**定值：`attempt-timeout-ms: 25000 → 45000`**
- 45000ms ÷ 42.8 tok/s ≈ **1051 tokens** 余量，覆盖到"长回答 + 行动卡完整展开"
- 不用 30000：30000 只装 1284 tokens 的**理论**上限，没有网络抖动余量，
  而 §1.3 实测 p95/中位 ≈ 12.9/9.9s 说明**抖动可达 30%**
- 不用 60000：装得下但会让"真挂的端点"拖 60s 才降级，把降级链的意义抵消掉

**`total-timeout-ms` 同步 60000 → 90000**
- 三级链意味着最坏情况是 3 次 attempt；`syncAttempt` 取 `min(attemptTimeout, remaining(deadline))`
- 若 total 仍是 60000，第三次降级尝试只剩几百 ms → **等于没有第三级**
- 90000 = attempt 45000 + 一次重试余量，与 `retry.max-attempts=3` 的实际结构对齐

**`stream-idle-timeout-ms` 保持 15000**：它是"两个 chunk 之间的间隔"，
不是总时长，实测 42.9 tok/s 下一个 chunk 间隔远小于此。**不需要动。**

### 2.4 配置形态

**不新增配置项**——降级链的每一级用**独立的 Spring profile yml 片段**表达，
避免在 `app.llm` 下再堆一层 provider 配置（provider 的 base-url / model / key 本来
就该归 Spring AI 的自动配置管，混进网关配置会让"谁拥有端点配置"变得不清楚）。

```yaml
# application.yml（默认，local/prod 都用它）
app:
  llm:
    attempt-timeout-ms: 45000      # 由 25000 上调，依据见 spec §2.3
    total-timeout-ms: 90000        # 由 60000 上调，容纳三级降级
```

三级链通过**三个 `@Bean`** 表达，网关构造时按顺序注入：

```java
@Bean("openRouterChatModel")   // 新增：OpenRouter qwen-plus（primary）
@Bean("deepSeekChatModel")     // 保留：DashScope qwen-plus（fallback-1）
@Bean("bigModelChatModel")     // 新增：bigmodel glm-4-flash（fallback-2，最低）
```

⛔ **凭据**：`OPENROUTER_API_KEY` 走环境变量，**不写进任何仓库文件**。
`application-local.yml` 已被 `.gitignore` 覆盖（且本地有"禁 `mvn clean`"的既有规矩）。

### 2.5 回滚

| 动作 | 回滚方式 |
|---|---|
| 端点 | `OPENAI_BASE_URL` / `OPENAI_MODEL` 指回 bigmodel（配置级，零代码） |
| timeout | `attempt-timeout-ms` / `total-timeout-ms` 改回旧值 |
| 降级链级数 | `LLM_FALLBACK_ENABLED=false` 退回两级（旧两级语义不变） |
| 全部 | git revert |

**每一项都能不回滚代码**，这是本方案的设计约束。

## 3. 风险与未验证项（写下来，不等验证完再补）

| 风险 | 处置 |
|---|---|
| qwen-plus 与 glm-4-flash **答案质量不同** | ⚠️ **本 phase 不测**。质量是 A/B 实验（`logs/run_answer_ab.sh`），不在"修 timeout"范围内。**必须显式声明未验证。** |
| OpenRouter 有 cache → 请求体里带缓存前缀，若上游路由漂移可能不命中 | §1.2 已实测 provider 在 SiliconFlow/DeepInfra/Alibaba 间漂移，命中率 88% 而非 100%。**按 88% 收益估算，不按 100%。** |
| 三级链让最坏延迟变长 | total 90000 是**上界**；正常路径（primary 成功）不受影响 |
| 403 的模型范围 | 选型**只允许**实测通过的模型；配置里若填了 403 模型，会在运行期报错而非启动期 → **要在 spec 明确这是已知限制** |

## 4. 与其他 phase 的关系

- `phase14-model-routing/`：写了但**暂缓**。本 phase 完成后需重读——原假设"切模型只省 token"
  已部分不成立（OpenRouter **有** cache）。见 MEMORY.md 待办 #10。
- ADR-41/42（上游容量）：本 phase 的并发数据是**新供应商的第一次实测**，需追加到 ADR-42。
