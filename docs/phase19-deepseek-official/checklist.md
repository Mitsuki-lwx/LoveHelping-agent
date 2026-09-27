# phase19 · 验收清单（实测结果）

> 日期：2026-09-27。**报忧不报喜**：A~G 全部达成并留证据；H 节是**未验证项**，不删。

## A. 配置：主链切 DeepSeek 官方

- [x] **A1** `application.yml`：`spring.ai.openai.base-url` → `https://api.deepseek.com`
- [x] **A2** `application.yml`：`chat.options.model` → `deepseek-flash`
- [x] **A3** `application.yml`：`app.llm.fallback-enabled` 显式默认 `false`
- [x] **A4** `application-prod.yml`：base-url / model → DeepSeek
- [x] **A5** `application-prod.yml`：`fallback-enabled=false`、`last-resort-enabled=false`
- [x] **A6** `target/classes/application-local.yml` 已备份到
      `logs/application-local.yml.bak-phase19-20260927-225122`
- [x] **A7** `target/classes/application-local.yml`：base-url / model → DeepSeek；
      api-key 保持 `${OPENAI_API_KEY}` 占位符（**不写明文**）
- [x] **A8** 未设 `completions-path`（DeepSeek 用框架默认 `/v1/chat/completions`）
- [x] **A9** ⭐ **新增**：`app.llm.vision-model` `mimo-v2.5` → `deepseek-flash`。
      理由：`VisionChatClient` 读 `spring.ai.openai.*`，改端点后原值会 **400**
      （`The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed mimo-v2.5`）。
      实测 `deepseek-flash` 支持图像输入（32×32 纯红 PNG → 正确答「红色」）。
- [x] **A10** ⭐ **新增**：`prod_env_from_local.py` 修掉**占位符污染** ——
      原版把 `${OPENAI_BASE_URL:...}` 整串当值导出，`eval` 后环境变量是垃圾字符串，
      而主 yml 的 `${VAR:default}` 会优先取走它 → 应用拿到非法 URL 且**不报错**。
      修后：`${VAR:default}` 取 default、`${VAR}` 跳过并告警。实测导出
      `OPENAI_BASE_URL`(24) / `OPENAI_MODEL`(14) 正确，`OPENAI_API_KEY` 被跳过。

## B. 代码：关闭两个失效降级级

- [x] **B1** `ChatModelConfig.deepSeekFallbackModel` 加 `@ConditionalOnProperty(havingValue="true")`
- [x] **B2** `RestFallbackChatModel` 类注释写明「域名不可达，已默认关闭」+「本类刻意零日志，排障请用指标」
- [x] **B3** `LlmGateway` **零改动**（`git diff --stat` 中不出现该文件）✓
- [x] **B4** `BigModelLastResortConfig` **零改动**（已支持 `last-resort-enabled` 开关）
- [x] **B5** 三级链单测不受影响（`LlmGatewayTest` / `LlmGatewayThreeTierTest` 走显式构造器）——
      单测 318/318 全绿可证

## C. 文档

- [x] **C1** `docs/phase19-deepseek-official/tasks.md`
- [x] **C2** `docs/phase19-deepseek-official/spec.md`
- [x] **C3** `docs/phase19-deepseek-official/checklist.md`（本文件）
- [x] **C4** `docs/03-技术决策记录.md` 追加 **ADR-51**（编号连续，上一条 ADR-50）
- [x] **C5** ADR-51 记下「0 dashscope 命中」是测量假象的三条原因

## D. 编译与单测

- [x] **D1** 编译通过（`logs/mb.sh -o test`，classworlds Launcher）
- [x] **D2** **`Tests run: 318, Failures: 0, Errors: 0`** = 接手基线 318/318
- [x] **D3** 判据取自 Maven 控制台汇总 + surefire XML（`failures=0 errors=0`）
- [x] **D4** ⚠️ 记录一个**判据陷阱**：surefire XML 目录里有 **3 个残留旧报告**
      （44 个 XML 中 41 个是本次写的），按 XML 汇总会得到虚高的 324。
      **用 XML 汇总必须按 mtime 过滤**。

## E. 真实端到端冒烟（真启动 + 真 HTTP + 真 DeepSeek）

脚本 `logs/run_phase19_e2e.sh`，日志 `logs/app-e2e-phase19-225626.log`，端口 8088。

- [x] **E1** 应用真实启动成功（`Tomcat started on port 8088`）
- [x] **E2** 真实 HTTP 链路 **22/22 全通过**（`{"passed": 22, "total": 22}`）：
      注册/登录、SSE 全流、RAG 工具检索、会话落库、跨用户隔离、管理端鉴权、
      提示词探查拦截 ×3、三牌结构化输出（两种 Accept）、护栏阻断、
      Agent 多步工具、沙盘真实模型链路、结构化记忆查询
- [x] **E3** `llm_endpoint_configured_total{level="primary",target="https://api.deepseek.com | deepseek-flash"} 1.0`
- [x] **E4** `level="fallback"` **不存在** ✓
- [x] **E5** `level="last-resort"` **不存在** ✓
- [x] **E6** `llm_call_total{outcome="success",provider="primary"} 9.0` > 0 ✓
- [x] **E7** `llm_fallback` 指标条数 = **0** ✓
- [x] **E8** 启动横幅**不再**打印 fallback / last-resort 级（`fallback 级自报行数=0`、
      `last-resort 级自报行数=0`、`bigmodel 兜底注册行数=0`）✓
- [x] **E9** 402 作为 HTTP 状态（带上下文）命中 **0**；应用层 ERROR = **0**
- [x] **E10** 用户可见「AI 服务暂时不可用」出现 **0** 次 ✓

## F. 绕过网关的模型调用（T5）

- [x] **F1** 全部模型消费者已枚举：`ChatExecutor` / `MemoryExtractor` / `SkillReflector` /
      `QueryRewriter` / `GuardrailAdvisor` / `MyKeywordEnricher` / `InsightService` /
      `CapabilityRouter` / `AgentLlmNode` / `LlmDocumentReranker` / `GoldenSetRunner`
- [x] **F2** 上述全部注入 `@Primary ChatModel`（= `LlmGateway`）→ **走 DeepSeek** ✓
- [x] **F3** 绕过者逐条记录（**不静默放过**）：
      | 组件 | 状态 |
      |---|---|
      | `VisionChatClient` | ⚠️ **绕过网关**：自建 RestClient 读 `spring.ai.openai.*`。端点已是 DeepSeek（故"provider 统一"成立），但**无重试/熔断/计量/闸门** —— `docs/02` 已列为已知例外。本轮**不改**（改它属另一件事，会与本次变更混在一起无法归因）。 |
      | `BigModelLastResortConfig` | ✅ 已关（`last-resort-enabled=false`），不再自建 client |
      | embedding / rerank | siliconflow `Qwen/Qwen3-Embedding-0.6B` / `Qwen/Qwen3-Reranker-8B` —— DeepSeek 无对应接口，**保持不动** |

## G. 密钥纪律

- [x] **G1** DeepSeek key 未入库（`git grep` 扫描，见提交前检查）
- [x] **G2** `git diff` 全文人工过一遍，无明文密钥
- [x] **G3** 未跟踪文件列表过一遍，无凭据文件
- [x] **G4** DeepSeek key 落点：`target/classes/application-local.yml`（gitignored，占位符形态）
      + `.workbuddy-ai/.env.local`（gitignored）+ 运行期环境变量
- [x] **G5** 记录**已存在但未修**的泄漏：`logs/run_answer_stability_ab.sh:101` 明文
      OpenRouter key（`logs/` 已 gitignore，故未入库；改评测脚本会污染既有 A/B 可复现性，本轮不动）

## H. 显式未验证（不许含糊）

- [ ] **H1** bigmodel 400 的根因**仍未查清**（无 key）；关掉该级后不再影响用户
- [ ] **H2** **容灾能力未测**：单 provider 无兜底，DeepSeek 侧故障/额度耗尽时用户会直接拿 5000。
      本轮**未做故障注入验证**。
- [ ] **H3** `attempt-timeout=45s` / `total-timeout=90s` 是按「最坏跑 3 级」定的，单级后**未重新标定**
- [ ] **H4** 成本影响**未量化**（space-bunny pricing=0 → DeepSeek 按 token 计费）
- [ ] **H5** 答案质量**未重测**（换 provider 后 `answer_eval.py` 16 例口径未跑）→
      **不得声称质量变好或变差**
- [ ] **H6** embedding / rerank 仍走 siliconflow，**未动**
- [ ] **H7** `logs/run_answer_stability_ab.sh:101` 的明文 OpenRouter key **未清理**
- [ ] **H8** `VisionChatClient` 的视觉链**未在应用内实测**（只做了端点级探测：
      直连 DeepSeek 发图得「红色」）。应用内走它需触发图片聊天，本轮 E2E 未覆盖。
