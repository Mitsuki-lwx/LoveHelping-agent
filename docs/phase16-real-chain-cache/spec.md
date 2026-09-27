# phase16 · spec

## 1. 数据通路

```
用户提问
  → ChatExecutor.issuePrompt
      system = SYSTEM_PROMPT(4293字符) + assembleContext()(实测恒为空)
      advisors = [MyLoggerAdvisor, GuardrailAdvisor, MessageChatMemoryAdvisor, RetrievalAugmentationAdvisor]
  → LlmGateway.stream → OpenRouter stealth/space-bunny-alpha
  → usage: prompt_tokens / completion_tokens / prompt_tokens_details.cached_tokens
  → 埋点 llm.tokens{type="prompt"|"completion"|"cached"}
  → prometheus /actuator/prometheus
```

## 2. 埋点设计

### 2.1 `cached_tokens` 取值路径

Spring AI 的 `DefaultUsage` **没有** `cachedTokens` 字段 → 必须走 `getNativeUsage()` 反射：

```
Usage.getNativeUsage()  →  OpenAiApi.Usage
  .promptTokensDetails()  →  OpenAiApi.Usage.PromptTokensDetails
    .cachedTokens()  →  Integer
```

用反射 + 单一形状取，不强转具体类型 —— 换 provider/换适配器时不该编译失败。

### 2.2 ⛔ 0 的含义不可区分

`cachedTokens()` 返回 0 有两种可能：

1. 上游确实没命中
2. 反射路径没对上（`getNativeUsage()` 装的不是 `OpenAiApi.Usage`）

**报告里不得把 0 说成「没命中」** —— 这是「字段存在 ≠ 缓存可用」那条纪律的翻版：
**字段取到 ≠ 值有意义**。

### 2.3 ⛔ 故意不埋命中率 gauge

命中率比例的分母（prompt 长度）逐次变化，瞬时 gauge 极易被后来者读成「稳定命中率」；
且 Micrometer `gauge` 签名要求持有对象引用，为派生量引入状态不划算。

命中率由**量具侧**用同一窗口的
`llm_tokens{type="cached"}` / `llm_tokens{type="prompt"}` 差值自算 —— 分母同源、可复算。

> 踩坑记录：`meters.gauge(name, double, String, String)` **不存在**，可变数量 tag 只有
> `gauge(String, Iterable<Tag>, T)`。第一版和这次都试了错签名。

## 3. 探针设计

### 3.1 为什么不能用「连发同一 prompt」

`probe_real_prompt_speed.py` 的 2948/2950（99.9%）是同一 prompt 连发 3 次测的。
真实链路每次 prompt 都不同（用户输入 + 记忆 + RAG 片段），静态前缀若被打断命中率会塌到 0，
而**改动前没有任何一条自动化断言看 cached_tokens** → 打断了也不会变红。

### 3.2 取数方法：指标差值法

SSE 无 usage 事件 → 记调用前后 `llm.tokens{type="cached"}` 的 prometheus 计数差值。

**必须按键名过滤 `_created` 序列**：

```python
if key.endswith("_created"):   # ✅
    continue
# ⛔ line.endswith("_created ") —— 永不成立
#   实际输出是 `llm_tokens_total{...}_created 1.75e9`，结尾是数字不是空格
```

### 3.3 提问必须内容不同

内置 8 条互不相同的真实恋爱提问，按轮次取。
⛔ 不能用同一句连发 —— 那测的是理想化场景。

### 3.4 前提断言

发请求前必须确认 `llm_endpoint_configured{level="primary",target="…space-bunny-alpha"}` 存在，
否则量的不是目标端点。

## 4. 机制对照实验设计

真实链路观测到 141/1180 二元分叉后，**不能靠读代码推断原因**，必须逐条证伪。

| 组 | 变量 | 目的 |
|---|---|---|
| A 基线 | 完全相同连发 | 前提断言：缓存本身有效 |
| B | system **尾部**追加动态段 | 尾部变化是否断前缀 |
| C | system **头部**插入动态段 | 头部变化是否全废 |
| D | system 不变、user 变 | 只改 user 是否影响前缀 |
| E/F/G | 扫静态前缀长度 | 找「多长才真正生效」的门槛 |
| H | 超长前缀 + user 变 | 长前缀下的稳态 |
| I/J/K | 尾部空 / 有记忆段 / 交替 | 模拟 `assembleContext` 的二元命中 |

⚠️ **每组必须先预热再取稳态，且稳态至少 2 次**。单次采样无法区分
「稳定的低命中」和「抖动的低命中」。

## 5. Payload 取证（`PromptPayloadDump`）

排除三种解释后，剩下只能在**真实装配结果**里找，而此前没有任何地方记录过实际发出的载荷。

- 开关：环境变量 `PROMPT_DUMP_DIR`，不设就完全不执行（一次判空，生产零开销）
- 落盘：JSONL，每行含 system 各段长度 + SHA-256 前 16 位 + 段首 120 字
- ⛔ **不落用户原文**（隐私），长度+哈希足够定位「哪一段变了」
- 诊断工具绝不能影响主链路：写失败只 `log.warn`

## 6. 附带修复：total-timeout 无日志

**现象**：流式路径 `takeUntilOther(Mono.delay(totalTimeout))` 超时后直接 `Mono.error`，
**一行日志都不打**，最终变成 5000 给用户，事后完全无法归因。

**实测**：2026-09-27 三次运行，「准备结婚/两家对婚礼花费有分歧」这题 **100% 撞 90s 超时**，
日志里只有 RAG/rerank 记录、**没有 `GRAPH_TRACE`** → 流程死在 LLM 调用阶段，但看不出原因。

**修复**：`log.warn` + `llm.timeout.total` 指标，`emitted` 单独打标签
（已吐内容与未吐内容是两种故障：后者才可能降级重试）。
