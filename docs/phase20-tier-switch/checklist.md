# phase20 · 验收清单（实测结果）

> 状态：**已按实测回填**（2026-09-27）。每条都带证据；未验证的集中在 I 节，不勾。
> 实现前写就的判据见本文件 git 历史。

## A. 新类型与网关泛化

- [x] A1 `LlmFallbackTier` record 建立：`name` 非空校验、`model` 非 null 校验、`baseUrl` null → `""`
      —— 单测 `blankTierNameRejected` 覆盖
- [x] A2 `LlmGateway` 字段改为 `List<LlmFallbackTier> tiers` + `Map<String, ProviderCircuit> tierCircuits`
- [x] A3 `@Autowired` 构造器改用 `ObjectProvider<LlmFallbackTier>`，**全类只有这一个 `@Autowired`**
      —— E2E 启动成功反证装配无歧义
- [x] A4 零候选不报错：`orderedStream()` 返回空流 —— **这是本轮最容易炸的一条**，
      当前配置正是零 tier，E2E 取到端口 8088 即证明
- [x] A5 `.filter(Objects::nonNull)` 已加（`BigModelLastResortConfig` 缺 key 时返回 null）
- [x] A6 包私有 `List<LlmFallbackTier>` 构造器存在 —— `LlmGatewayTierListTest` 用它构造 1/2/3 级链
- [x] A7 公有 4 参重载保留 —— `LlmGatewayTest` 27 用例与 `LlmGatewayFirstByteTimeoutTest` **零改动**通过
- [x] A8 五处不再出现字面量 `"fallback"` / `"last-resort"`
      —— `degradeTiers()` / `canDegradeTo` / `canFallback` / `endpointBaseUrl` / `publishEffectiveEndpoints`
      均改为从 `tiers` 推导（`endpointBaseUrl` 现在按 tier 名查 `baseUrl`）
      ⚠️ 精确说明：全文件仍有两处含 `"fallback"` 字样，均**不在生产路径**——
      ① `endpointBaseUrl` 的 javadoc（说明"不再按字面量分支"）；
      ② 公有 4 参**测试重载**里给 tier 起的名字 `new LlmFallbackTier("fallback", fallback)`。
      生产链的级别名来自配置类（`dashScopeFallbackTier` / `bigModelLastResortTier`）。

## B. 开关语义拆开（口子 1）

- [x] B1 `LlmGatewayProperties.fallbackEnabled` → `degradeEnabled`（javadoc 写明它是总闸 + 改名理由）
- [x] B2 `app.llm.degrade-enabled` 进入三处 yml，默认 `true`
- [x] B3 `LlmGateway` 两处判据改用 `isDegradeEnabled()`
- [x] B4 全仓 17 处 `setFallbackEnabled(false)` → `setDegradeEnabled(false)`，**0 处残留**
      —— `grep -rn "FallbackEnabled" src/` 无输出
- [x] B5 `app.llm.fallback-enabled` 不再被 `LlmGatewayProperties` 绑定
      —— 改名后前缀 `app.llm` 下无同名字段；该 key 现仅由 `@ConditionalOnProperty` 消费

## C. 级数可扩展（口子 2）+ 端点可配（口子 3）

- [x] C1 `ChatModelConfig` 产出 `LlmFallbackTier` bean，名 `dashScopeFallbackTier`，`@Order(10)`
- [x] C2 `BigModelLastResortConfig` 产出 `LlmFallbackTier` bean，名 `bigModelLastResortTier`，`@Order(20)`
- [x] C3 旧 bean 名 `deepSeekChatModel` / `bigModelChatModel` **已无代码引用**
      ⚠️ 精确说明：`grep` 仍有 3 处命中，全部是**注释/文档**，无一是引用 ——
      `ChatModelConfig` 的 javadoc（说明改名的理由）、`AdmissionCompletenessTest` 的
      javadoc 与正则（**刻意**保留旧名以让守护也能扫到旧写法）。
- [x] C4 `app.llm.fallback.model` / `app.llm.fallback.base-url` 可配，默认值与原硬编码一致
- [x] C5 `RestFallbackChatModel` 支持构造器传 endpoint；旧构造器保留为重载并指向
      `DEFAULT_ENDPOINT`（原 `ENDPOINT` 常量改名公开）

## D. 测试

- [x] D1 3 个测试文件的 rename 全部完成，`test-compile` 通过
- [x] D2 `LlmGatewayThreeTierTest` 两个工厂改用新构造器；原有用例语义不变（全绿）
- [x] D3 `AdmissionCompletenessTest` 正则扩展 + **新增**"谁可提及 `LlmFallbackTier`"白名单断言
      —— 非空断言仍成立（网关自身 `@Qualifier("openAiChatModel")` 被扫到）
- [x] D4 新增 `LlmGatewayTierListTest`（12 个用例）：
  - [x] D4a 空链 + 主挂 → 直接 E5000，且 `llm.fallback` 计数器不存在（用 `find(...).counter()` 断言 null）
  - [x] D4b **只有 last-resort 注册** → 主挂后直接走它（**G1 验收**），指标 tag 如实为 `last-resort`
  - [x] D4c 三个 tier → `InOrder` 断言调用次序 primary → t1 → t2 → t3（**G2 验收**）
  - [x] D4d `degradeEnabled=false` 且 2 个 tier 注册 → `verifyNoInteractions` 一级都不走
  - [x] D4e 流式同样按链走（`degradingStream` 泛化后未被改坏）
  - [x] D4f 额外：中间级挂掉不终止链；重名 tier 启动期失败；端点指标带 tier 自报 baseUrl；
        空链时生效端点指标只有 primary

## E. 编译与单测

- [x] E1 `BUILD SUCCESS`
- [x] E2 `Tests run: 331, Failures: 0, Errors: 0`，**331 > 318**（+13：12 个新用例 + 1 个准入守护）
- [x] E3 项数取自**控制台**行，未使用 surefire XML 汇总

## F. 真实端到端冒烟

- [x] F1 **应用启动成功**（端口 8088）—— `ObjectProvider<LlmFallbackTier>` 装配 OK
- [x] F2 `llm_endpoint_configured_total{level="primary",target="https://api.deepseek.com | deepseek-flash"} 1.0`
- [x] F3 `llm.fallback` 指标条数 = 0
- [x] F4 `llm_call_total{outcome="success",provider="primary"} 9.0`
- [x] F5 E2E **22/22**；应用层 ERROR = 0；「AI 服务暂时不可用」= 0；带上下文 402 = 0
- [x] F6 额外断言：启动横幅 `degradeEnabled=true 降级链=[空（单级：主链 + 重试）]`；
      fallback / last-resort 自报行数 = 0；bigmodel 兜底注册行数 = 0

## G. 文档

- [x] G1 `docs/phase20-tier-switch/{tasks,spec,checklist}.md` 三份齐备
- [x] G2 `docs/03-技术决策记录.md` 追加 **ADR-52**（编号连续，上一条 ADR-51）
- [x] G3 ADR-51 §已知限制 加**更正块**（原文保留 + 指向 ADR-52），未静默重写
- [x] G4 `docs/02-架构设计.md` 的"架构能力"段同步为泛化后的描述（含"注入 tier = 绕过网关"的警告）

## H. 密钥与提交纪律

- [x] H1 三处 yml 均只含 `${ENV:default}` 占位符（`target/` 那份写的是具体值但**已 gitignore**）
- [x] H2 三处 yml 用 snakeyaml 语义校验重复键 → 全部 `OK`
- [x] H3 diff + 未跟踪文件 + 索引三路扫 `sk-` 形态 → 0 命中
- [x] H4 `git diff --cached --name-only` 无 `application-local.yml`
- [x] H5 带 443 绕行推送，`git ls-remote` 复核远端 HEAD == 本地 HEAD
- [x] H6 工作区干净

## I. 显式未验证（不许含糊）

- [ ] I1 **用户可见行为未变**（本轮不恢复任何降级目标）→ 未做答案质量 A/B，
      **不得声称变好或变差**
- [ ] I2 单 provider 无兜底的故障注入仍未做（ADR-51 H2 继续挂着）
- [ ] I3 **"只开 bigmodel" 的组合只在单测层验证**，未在真实栈跑
      （缺 `BIGMODEL_API_KEY`，且该级实测 400，恢复了也没意义）；真实栈验的是**零 tier** 形态
- [ ] I4 `RestFallbackChatModel` 的请求体形状仍与 DashScope 原生协议强绑定 —— 换供应商仍需写新类
- [ ] I5 `VisionChatClient` 绕过网关的问题未动
- [ ] I6 `logs/run_phase20_e2e.sh` 在 `logs/` 下（gitignore），**不在仓库内** ——
      长期复现需移到 `scripts/`
