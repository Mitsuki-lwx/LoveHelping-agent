# phase20 · 降级链泛化：按级开关 + 可扩展级数（ADR-52）

> 前置：ADR-51（phase19）把主链切到 DeepSeek 官方 `deepseek-flash`，并**关闭**两个实测不可用的
> 降级级。关闭后复核发现：**架构能力在，但留了三个口子**（见 `docs/03-技术决策记录.md`
> ADR-51 §已知限制）。本轮修其中两个（口子 1、口子 2），顺带把口子 3 的端点硬编码也一并处理。

## 1. 目标

用户原话（2026-09-27）：

> 「我是现在不用多 provider，**架构里面要有的啊**，以后可能换模型的」

拆成两条可验收的目标：

| # | 目标 | 验收方式 |
| --- | --- | --- |
| G1 | **任一级可独立启用/停用**，不再被"总闸"绑死 | 新增单测：只注册 last-resort、不注册 fallback 时，主挂能走到 last-resort |
| G2 | **加第 N 级不用改 `LlmGateway` 构造器签名** | 新增单测：3 个 tier 按 `@Order` 依次走完 |
| G3 | 降级级的 `endpoint` / `model` 不再硬编码在 Java | 配置驱动；`RestFallbackChatModel.ENDPOINT` 常量退化为默认值 |

## 2. 问题现状（全部为读码/实测，非推断）

### 2.1 口子 1：两个开关语义不对称 → 无法单独启用某一级

同一个 yml key `app.llm.fallback-enabled` **同时驱动两件不相干的事**：

```java
// (a) 构建期：决定 dashscope 级 bean 是否注册
@Bean("deepSeekChatModel")
@ConditionalOnProperty(name = "app.llm.fallback-enabled", havingValue = "true")
public ChatModel deepSeekFallbackModel(...)          // ChatModelConfig

// (b) 运行时：决定整条降级链是否发生（这是"总闸"，与上面那件事无关）
private boolean fallbackEnabled = true;              // LlmGatewayProperties（@ConfigurationProperties("app.llm")）
...
return props.isFallbackEnabled() && failure != null && fallbackAllowed(failure);   // LlmGateway#canDegradeTo
```

而 `app.llm.last-resort-enabled` 是**另一类**开关：它只被 `BigModelLastResortConfig`
的方法内 `if` 读，**`LlmGateway` 完全不知道它的存在**。

**后果（真实、可复现）**：想"只开 bigmodel 兜底、不开 DashScope"做不到 ——
总闸 `fallback-enabled=true` 一开，`degradeTiers()` 会把**已注册的 DashScope** 一并放进链里
（本机不可达 → 每次主链故障白等一次 TLS 握手超时）。

单测 `LlmGatewayThreeTierTest#fallbackDisabledSkipsWholeChain` **把这个语义固化了**
（名字直译："关掉 fallback 就跳过整条链"）—— 所以这是**有意为之**，只是命名与直觉相反。

### 2.2 口子 2：`degradeTiers()` 是 2 个**具名**槽位

```java
public LlmGateway(@Qualifier("openAiChatModel") ChatModel primary,
                  @Autowired(required = false) @Qualifier("deepSeekChatModel") ChatModel fallback,
                  @Autowired(required = false) @Qualifier("bigModelChatModel") ChatModel lastResort, ...)

private List<Tier> degradeTiers() {
    List<Tier> tiers = new ArrayList<>(2);
    if (fallback != null) tiers.add(new Tier(fallback, fallbackCircuit, "fallback"));
    if (lastResort != null) tiers.add(new Tier(lastResort, lastResortCircuit, "last-resort"));
    return tiers;
}
```

**遍历逻辑本身是通用的**（同步 `for (Tier t : degradeTiers())`；流式
`degradingStream(..., List<Tier> tiers, int tierIndex)` 递归）—— 卡住扩展的只有
**构造器签名**与 `degradeTiers()` 里那两条写死的 `if`。

### 2.3 口子 3：降级级的 endpoint / model 硬编码

- `RestFallbackChatModel.ENDPOINT` 是 `private static final URI` 常量；
- `ChatModelConfig` 里 `"qwen-plus"` 是字面量。

对比 `BigModelLastResortConfig` 的 `base-url` / `model` 走 `@Value` **可配** —— 两种风格不统一。

### 2.4 顺带发现：bean 名 `deepSeekChatModel` 名不副实

该 bean 实际是 **DashScope `qwen-plus`**，名字却是 `deepSeekChatModel`
（源码注释已承认这是历史包袱："切勿照名字理解"）。泛化改造会换掉这个 bean，
**顺带把这个坑填了**。

## 3. 做什么

### T1 引入 `LlmFallbackTier`（新类型）

```java
public record LlmFallbackTier(String name, ChatModel model, String baseUrl) { ... }
```

- `name`：指标 tag 与日志用（`fallback` / `last-resort` / …）
- `model`：实际 `ChatModel`
- `baseUrl`：**仅用于"生效端点"报告**（修口子 3 的报告部分）

### T2 `LlmGateway` 泛化

- 字段：`List<LlmFallbackTier> tiers` + `Map<String, ProviderCircuit> tierCircuits`（每级独立熔断不变）
- `@Autowired` 构造器：`ObjectProvider<LlmFallbackTier>`，`orderedStream().filter(Objects::nonNull).toList()`
  - 用 `ObjectProvider` 而不是 `List<T>`：**零个 tier 时 `List<T>` 构造器注入会抛
    `NoSuchBeanDefinitionException`**，而"零个 tier"正是当前配置 —— 必须不报错
  - `filter(nonNull)`：`BigModelLastResortConfig` 在缺 key 时返回 `null`（NullBean），需过滤
- 保留**包私有** `List<LlmFallbackTier>` 构造器，供单测构造任意级数
- 保留**公有 4 参**重载 `(primary, fallback, props, meters)` —— `LlmGatewayTest` 27 个用例走它，
  **零改动**
- `degradeTiers()` / `canDegradeTo` / `canFallback` / `endpointBaseUrl` / `publishEffectiveEndpoints`
  全部改为从 `tiers` 泛化推导，**不再出现字面量 `"fallback"` / `"last-resort"`**

### T3 开关语义拆开（修口子 1）

| 配置 | 层 | 语义 |
| --- | --- | --- |
| `app.llm.degrade-enabled` | 运行时 | **降级总闸**（原 `fallback-enabled` 的运行时语义，改名） |
| `app.llm.fallback-enabled` | 构建期 | 只决定 dashscope 级 bean 是否注册 |
| `app.llm.last-resort-enabled` | 构建期 | 只决定 bigmodel 级 bean 是否注册 |

→ 「只开 bigmodel」= `degrade-enabled=true` + `fallback-enabled=false` + `last-resort-enabled=true` ✅

### T4 配置驱动端点/模型（修口子 3）

`app.llm.fallback.model` / `app.llm.fallback.base-url`；`RestFallbackChatModel` 的 endpoint
改为构造器参数（旧构造器保留为重载，指向原常量）。

### T5 两个配置类产出 tier bean

- `ChatModelConfig`：`@Bean("dashScopeFallbackTier") @Order(10)`
- `BigModelLastResortConfig`：`@Bean("bigModelLastResortTier") @Order(20)`

### T6 测试

- 17 处 `props.setFallbackEnabled(false)` → `setDegradeEnabled(false)`（3 个文件）
- `LlmGatewayThreeTierTest` 工厂改用新构造器
- `AdmissionCompletenessTest` 正则扩展到新 tier bean 名（**它是"单一准入点"守护**，
  新增 tier 类型等于新增绕过向量，必须同步）
- 新增 `LlmGatewayTierListTest`：G1 / G2 的直接验收

## 4. 不做什么

- **不重新启用任何降级级**。ADR-51 的结论（两个目标都不可用）未变，本轮只改**架构**，
  不恢复任何目标。所以**行为对用户完全不变**（仍是单 provider + 重试）。
- **不做故障注入**（单 provider 无兜底的用户可见行为仍未验证，H2 继续挂着）。
- **不重测答案质量**（不得声称变好或变差）。
- **不引入 testcontainers**（`docs/09` §3 的缺口本轮不补）。
- **不动 `LlmGateway` 的重试/熔断/闸门语义**（ADR-23 / ADR-31 / ADR-32 不变式）。

## 5. 已知代价

- 多一层间接（`LlmFallbackTier` 包一层 `ChatModel`），启动期多几次 bean 装配。
- **公开 API 变更**：`LlmGatewayProperties.setFallbackEnabled` → `setDegradeEnabled`。
  属破坏性改名，但全仓调用点仅 17 处（全部在测试），已一并改。
- 口子 3 只修了"报告 + 配置"部分：`RestFallbackChatModel` 的**请求体形状**
  （DashScope 原生协议）仍与该供应商强绑定 —— 换供应商仍需写新类。这是刻意的：
  不同供应商的 body/解析差异很大，硬套一个通用 HTTP 客户端只会更脆。
