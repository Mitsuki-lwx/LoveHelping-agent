# phase25 · 统一 LLM provider（配置即接入）

> 状态：**已实施并实测**（2026-09-29）。决策记录：`docs/03` ADR-58。
> 目录原为 `phase25-siliconflow-fallback`，因方案从"再写一个 provider 类"改为
> "统一封装、配置驱动"而改名。

## 起因（Mitsuki 原话）

> 「不能统一封装一个 llm provider 吗，不用我每次换 provider 就要写一堆文件，
> 要加 llm 就直接在配置文件写了 url 和 key 就行的那种」

在改之前，接一个供应商的成本是：新写一个配置类（`BigModelLastResortConfig` /
`ClineFallbackConfig`），必要时再写一个协议适配类（`RestFallbackChatModel` 原生协议 /
`ClineApiCompatConfig` 信封拆封）。**接第 N 个供应商的成本是线性的**，且"哪些级生效"
同时取决于构建期开关与运行时总闸。

## 目标

`app.llm.providers` 列表：**加一条配置 = 加一个 provider，Java 零改动。**

## 实测的协议事实（2026-09-29，持 key）

| 事实 | 证据 |
|---|---|
| cline 网关**非流式**响应带信封 `{"data":{...},"success":true}` | 顶层无 `choices` → 标准客户端解析失败 |
| cline 网关**流式**是标准 OpenAI chunk | 顶层 `choices[].delta`，实测 3/3 成功 |
| **Spring AI 两条路径两套 client** | `OpenAiApi` 里 `chatCompletionEntity`→`RestClient`、`chatCompletionStream`→`WebClient` |
| 因此信封拆封必须**两边都挂** | 只挂 WebClient → `LlmGateway.call()`（沙盘 ta-view）返回 5000，实测复现 2 次 |
| cline 首字节偏慢、connect 偶发抖动 | ttft 6.8~15.8s；曾被 `connect-timeout-ms=3000` 与 `first-byte/idle=15000` 打死 |

## 设计

1. `LlmProviderProperties`（`app.llm.providers[]`）：`name / base-url / api-key / model /
   completions-path / enabled / response-envelope / connect-timeout-ms`。
   **顺序即链路顺序，第一条 enabled 的即主链。**
2. `LlmProviderConfig`：为每条 provider 建一个 `OpenAiChatModel`（独立 WebClient + RestClient、
   单次重试），打包成单个 `LlmProviderChain(primary, primaryBaseUrl, tiers)`。
3. `LlmGateway` 构造器改为消费 `LlmProviderChain`（消灭 `ObjectProvider<List<T>>` 歧义）。
4. **删除**：`BigModelLastResortConfig` / `ClineFallbackConfig` / `ClineApiCompatConfig` /
   `RestFallbackChatModel`。
5. 三处超时改为可配（`APP_LLM_CONNECT_TIMEOUT_MS` / `APP_LLM_STREAM_IDLE_TIMEOUT_MS`；
   `first-byte` 原已可配）。

## 边界（刻意不做）

- **只覆盖 OpenAI 兼容协议**。非 OpenAI 协议（DashScope 原生）不再有专门实现 ——
  那正是"每接一个供应商写一个类"的来源；需要就走各家的 OpenAI 兼容模式。
- **不做"通用 HTTP 客户端"**：信封拆封是**声明式开关 + 两套 client 的同一份判据**，
  不是请求体改造。ADR-52 §未改 中"不硬套通用客户端"的理由仍然成立。
- `VisionChatClient` 仍读 `spring.ai.openai.*` 并绕过网关（既有 P3 待办，本轮未动）。

## 验收

见 `checklist.md`。核心：357 单测 + 22 E2E + **双路径真实链路**（流式聊天 + 非流式
`/sandbox/ta-view`）在 cline 主链下均通过。
