# phase19 · 主链切 DeepSeek 官方（只用 deepseek-flash）

> 起因（用户原话）：「所有模型现在都走deepseek官方key是sk-...」→「只用flash」

## 1. 目标

把**所有模型调用**统一到 DeepSeek 官方端点 `https://api.deepseek.com`，模型只用 `deepseek-flash`。
并顺手处理掉 ADR-48 三级降级链里**已被实测证伪的两级**（dashscope 不可达、bigmodel 400），
因为它们不是"备用"，而是"假备用"——它们让链路看起来还有三层，实际只有一层。

## 2. 问题现状（全部为实测，非推断）

### 2.1 触发点：A 臂 6/6 全败，用户拿到 5000

`outputs/ab-A-spacebunny-195200.json`：primary=space-bunny-alpha，6 轮
**`ok:false` 全败**，用户可见「AI 服务暂时不可用，请稍后再试」（`LlmGateway:520` 的 `BizException(5000)`）。
B 臂（primary=bigmodel）6/6 成功。**降级链一次都没救回来。**

### 2.2 逐级归因

| 级 | 目标 | 实测结论 | 证据 |
|---|---|---|---|
| primary | OpenRouter `stealth/space-bunny-alpha` | **429，当日免费额度耗尽** | 日志 12 处 `429 Too Many Requests from POST https://openrouter.ai/api/v1/chat/completions`；`free-models-per-day-stealth` Remaining=0 / Limit=1000 / Reset=1790553600000 |
| fallback | DashScope `qwen-plus`（`RestFallbackChatModel`） | **域名本机不可达** | `dashscope.aliyuncs.com` 解析到 Clash fake-ip `198.18.0.138` / `fdfe:dcba:9876::c5`；直连 `curl: (35) schannel: failed to receive handshake`、走代理（CONNECT 隧道 200 后）同样握手失败；Java 侧同形失败最早见于 **2026-09-16**：`ResourceAccessException: I/O error on POST ... Remote host terminated the handshake` |
| last-resort | bigmodel `glm-4-flash` | **400 Bad Request** | `WebClientResponseException$BadRequest: 400 Bad Request from POST https://open.bigmodel.cn/api/paas/v4/chat/completions`（URL 正确，故问题在请求体/参数，**根因未定**） |

### 2.3 ⭐ 「0 dashscope 命中」是**测量假象**（本项目第 4 次同形坑）

上一轮记录写了「dashscope 0 命中，可疑」。该结论**不成立**，三重原因叠加：

1. **`RestFallbackChatModel` 整个类零日志** —— 裸 `java.net.http.HttpClient`，
   端点常量从不打印，失败只抛 `RestClientResponseException`/`ResourceAccessException`。
   它是链上**唯一失败不留痕**的一级，而恰恰就是被查的那一级 → 「没日志」被读成「没调用」。
2. **日志被节流**：`app.logging.throttle`（`max-per-window: 3` / `window-ms: 10000`）
   把 `MessageAggregator` 的错误**抑制了 31 条**（4 个窗口：7+7+7+10）→
   日志里 `429` 出现 12 次、`400` 出现 1 次，**都不是真实次数**。
3. **代码顺序保证 fallback 恒在 last-resort 之前被尝试**：
   `degradeTiers()` 首位即 fallback；`degradingStream` 无条件先试 `tierIndex=1`。
   若 last-resort 被到达，则 fallback 必已被尝试过。

**教训**：判"某级是否被调用"只能看指标（`llm_call_total{provider=...}` /
`llm.fallback{name=...}`），不能 grep 日志。这与 ADR-48 记的
「`grep last-resort` 每次都命中启动横幅」是同一类错误的两个变体。

### 2.4 DeepSeek 官方可用性（本轮实测）

```
GET https://api.deepseek.com/models →
  deepseek-flash     DeepSeek-V4.1-Flash  ctx=1048576  max_output=393216  input=[text,image]
  deepseek-v4-pro    DeepSeek-V4-Pro      ctx=1048576  max_output=393216  input=[text]
```

`POST /chat/completions` 6 个变体**全部 200**（最小非流式 / 流式 / 流式+`stream_options` /
`max_tokens=32768` / `max_tokens=4095` / `temperature=0.7`）。

⚠️ **三个必须记住的坑**：

1. **`deepseek-chat` / `deepseek-reasoner` 是遗留别名，不是真 ID** —— 发了也 200，
   但响应体 `model` 字段回落成 `deepseek-flash`（静默别名）。
   ⛔ **但"任意未知模型名会 400"** —— 实测 `mimo-v2.5`：
   `The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed mimo-v2.5`。
   所以"模型名写错"分两种：命中遗留别名 → 静默跑成别的模型；其余 → 明确 400。
   （本文件初稿写成"写错不报错"，**不准确，已更正**。）
2. **`max_tokens` 过小会返回空 `content`** —— 实测 `max_tokens=8` 时
   4 次里有 2 次 `content=''`（推理 token 吃掉了预算）。
   而 `LlmGateway` 对空响应抛 `EmptyResponseException` 并**触发降级** →
   若把 `max_tokens` 配小，会制造**虚假降级**。
3. **视觉通道必须同时换模型名** —— `app.llm.vision-model` 原值 `mimo-v2.5` 是别的厂商的模型，
   改端点后会直接 400。实测 `deepseek-flash` **支持图像输入**
   （`input_modalities=[text,image]`）：用 32×32 纯红 PNG 以
   `image_url → data URL(base64)` 发送，正确答出「红色」。
   → `app.llm.vision-model` 必须一并改为 `deepseek-flash`，否则图片理解功能整体不可用。

## 3. 做什么

| # | 任务 |
|---|---|
| T1 | `spring.ai.openai.*` 指向 `https://api.deepseek.com` + `model=deepseek-flash`（基座 / prod / local 三层） |
| T2 | 关闭 fallback 级（dashscope）：bean 条件化 + `app.llm.fallback-enabled=false` |
| T3 | 关闭 last-resort 级（bigmodel）：`app.llm.last-resort-enabled=false` |
| T4 | `LlmGateway` 的三级机制**保留不动**（可配置回滚），只改"配了什么" |
| T5 | 排查并逐条记录**绕过网关直连其他 provider** 的组件 |
| T6 | 编译 + 单测（基线 318/318） |
| T7 | 真实 E2E：真启动 + 真 HTTP + 真 DeepSeek，确认生效端点与结局指标 |
| T8 | ADR-51 落档 |

## 4. 不做什么

- ⛔ 不改 `LlmGateway` 的重试 / 熔断 / 并发闸门语义（ADR-23 不变式：网关是唯一重试所有者）
- ⛔ 不动 embedding（siliconflow `Qwen/Qwen3-Embedding-0.6B`）与 rerank（`Qwen/Qwen3-Reranker-8B`）——
  DeepSeek 无 embedding/rerank 接口，且这两条通道实测可用
- ⛔ 不去猜 bigmodel 400 的根因（无 key，无法复现；见 spec §6）
- ⛔ 不把任何明文密钥写进入库文件（`AGENTS.md` §3）

## 5. 已知代价（不粉饰）

- **单 provider = 无兜底**：DeepSeek 侧故障 / 额度耗尽时，用户直接拿 5000。
  这正是刚刚杀死 A 臂的故障模式，只是换了个 provider 重演。
- **成本从 0 变正**：space-bunny-alpha 的 pricing 全 0，DeepSeek 官方按 token 计费。
- 关掉两级后，`attempt-timeout` / `total-timeout`（ADR-48 按"最坏跑 3 级"定的 45s/90s）
  留有余量但不再必要；**本轮不调**，避免与本次变更混在一起无法归因。
