# phase25 · spec：统一 LLM provider（配置即接入）

## 接口

```yaml
app:
  llm:
    providers:
      - name: primary                # 唯一名 = 指标标签 + 熔断器键（重名启动失败）
        base-url: https://api.deepseek.com       # 不含 /chat/completions
        api-key: ${OPENAI_API_KEY:}              # 只留占位；真值在 gitignored 文件
        model: deepseek-flash
      - name: cline                  # 第二条 = 第一个降级级
        enabled: false               # 保留配置但不注册
        base-url: https://api.cline.bot/api
        api-key: ${CLINE_API_KEY:}
        model: deepseek/deepseek-v4.1-flash
        response-envelope: data      # 非流式响应包了 {data:{...}} → 自动拆封
```

字段默认值：`completions-path=/v1/chat/completions`、`enabled=true`、
`response-envelope` 空（不拆）、`connect-timeout-ms` 空（用 `app.llm.connect-timeout-ms`）。

## 语义

| 概念 | 规则 |
|---|---|
| 主链 | **第一条 `enabled=true`**（不是"索引 0"） |
| 降级级 | 其余 `enabled=true` 的条目，顺序即链路顺序 |
| 缺 api-key | 照旧注册但启动时 WARN（沿用"凭据在调用期校验"约定）；主链同理——不阻断启动 |
| 重名 | `LlmGateway` 构造期直接失败（两级共用一个熔断器无法解释） |
| 链为空 | 合法（单 provider 形态），退化为「主链 + 重试」 |

## 信封拆封的判据（关键：宁可不拆，不可误拆）

只在响应体**确实**是 `{"<field>":{ ... "choices":[...] }}` 时才替换为内层 JSON。
其余一律原样放行：
- 标准 OpenAI 形状（顶层已有 `choices`）
- 错误体（`{"error":...,"success":false}`）
- 非 JSON（HTML 502、SSE 行）
- `data` 非对象 / 内层无 `choices`

⛔ 误拆会把一个好响应静默改坏，且不报错 —— 故这条边界有永久单测
（`LlmProviderConfigEnvelopeTest`，4 例）。

## 传输层覆盖（实测教训）

`OpenAiApi` 对**非流式**用 `RestClient`、**流式**用 `WebClient`。
所以拆封在两处都要挂：`restClientBuilder(interceptor)` + `webClientBuilder(filter)`。
> 只挂 WebClient 一次 → `/sandbox/ta-view` 持续 5000（实测复现 2 次）。

RestClient 侧需自行修正 `Content-Length`（拆封后长度变了），否则解析器按旧长度截断。

## 超时

| 参数 | 默认 | 说明 |
|---|---|---|
| `app.llm.connect-timeout-ms` | 3000 | 环境变量 `APP_LLM_CONNECT_TIMEOUT_MS` |
| `app.llm.first-byte-timeout-ms` | 15000 | 原已可配 |
| `app.llm.stream-idle-timeout-ms` | 15000 | **本轮新加可配**（原硬编码） |
| `app.llm.total-timeout-ms` | 90000 | socket 读超时用此值（不是 attempt-timeout） |

> 读超时取 `total-timeout` 而非 `attempt-timeout`：后者会把长回答的**流式**请求
> 按"整次调用预算"砍掉（attempt 语义是同步整调用）。

## 不做

- 非 OpenAI 协议供应商（如 DashScope 原生）不再有专门实现。
- 不引入通用 HTTP 客户端硬套各家协议（ADR-52 §未改 的立场不变）。
- `VisionChatClient` 仍绕网关（既有 P3）。
