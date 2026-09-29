# phase25 · checklist

> DoD = 编译 + 相关单测 + 触达面 E2E（AGENTS.md §2）。未通过项显式写原因，不得默认通过。

## A. 设计前提

- [x] A1 三件套齐（tasks / spec / checklist）
- [x] A2 协议事实实测：非流式信封 / 流式标准 / 两条 client 路径（见 spec）
- [x] A3 ADR-58 写入 `docs/03`（含与 ADR-51/52 的关系）

## B. 实现

- [x] B1 `LlmProviderProperties`（`app.llm.providers[]`）
- [x] B2 `LlmProviderConfig` 工厂：每 provider 独立 WebClient + RestClient + 单次重试
- [x] B3 `LlmProviderChain` 单一 bean（消灭 `ObjectProvider<List<T>>` 歧义）
- [x] B4 `LlmGateway` 构造器改消费 Chain；保留包私有构造器供单测
- [x] B5 删除 `BigModelLastResortConfig` / `ClineFallbackConfig` /
      `ClineApiCompatConfig` / `RestFallbackChatModel`
- [x] B6 三处超时可配（connect / first-byte / stream-idle）
- [x] B7 `app.llm.providers` 写入 `application.yml` + `application-prod.yml`（入仓只留占位 key）
- [x] B8 `AdmissionCompletenessTest` 白名单与自检同步（旧版靠"网关必然命中"证明扫描器有效，
      现已改为合成样本自检）

## C. 验证（命令与结果）

- [x] C1 编译 `BUILD SUCCESS`
- [x] C2 全量单测 **357/357**（基线 353 + 信封单测 4）
- [x] C3 信封纯函数单测 4 例（含"不许误拆"的四类反例）
- [x] C4 真实 E2E **22/22**（默认 DeepSeek 主链，无回归）
- [x] C5 **流式**真实链路（cline 主链）：2/2 轮有实质内容
- [x] C6 **非流式** `LlmGateway.call()` 真实链路：`/sandbox/ta-view` → `code 200` + 真实回复
      （修复前为 `code 5000`，实测复现 2 次）
- [x] C7 启动横幅自报 provider（`[ADR-58] LLM 主链/降级级：...`），E2E 断言 B 由恒真项
      改为断言"配置真被读到"

## D. 报告与收尾

- [x] D1 ADR-58 + 三件套
- [x] D2 `docs/11` 装置登记（新增两个探针脚本）
- [x] D3 README 数字订正（ADR 数 / 单测数）
- [x] D4 项目记忆 `.workbuddy/memory/`
- [ ] D5 密钥扫描（`sk-` / `apikey_` 三路）——提交前执行
- [ ] D6 `git push` 后 `git ls-remote` 复核

## ⛔ 未验证 / 已知限制（不掩盖）

1. **`response-envelope` 只在 cline 网关实测过**；其它网关的同类形状未测。
2. **降级链在真实栈里仍未跑过**：默认配置零降级级，故 `llm.fallback` 仍为 0；
   "主挂 → 兜底接管"的故障注入**仍未做**（继承 ADR-51 H2 / ADR-52 遗留）。
3. **cline 网关的模型行为未做质量评测**：本轮只证明"能通、能出内容"，
   未与 DeepSeek 官方做答案质量对照 → 不得声称变好变差。
4. **`VisionChatClient` 仍读 `spring.ai.openai.*` 并绕过网关**（既有 P3，未动）。
   后果：主链换供应商后，视觉链路会**指向旧端点**——这是一处真实的口径不一致。
5. **cline 的 `deepseek-v4.1-flash` 是重推理模型**：`max_tokens` 太小会让推理吃光预算
   产出空 content（`EmptyResponseException` → 虚假降级）。本轮未给 provider 级
   `max-tokens` 配置项。
