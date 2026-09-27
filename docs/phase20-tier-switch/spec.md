# phase20 · spec（实现规格）

> 配套：`tasks.md`（为什么做）→ 本文件（怎么做）→ `checklist.md`（验收结果）

## 1. 改动面

| # | 文件 | 改动 | 类型 |
| --- | --- | --- | --- |
| 1 | `infrastructure/ai/LlmFallbackTier.java` | **新增** `record(name, model, baseUrl)` | 新 |
| 2 | `infrastructure/ai/LlmGateway.java` | 字段泛化 + 构造器改造 + 5 个方法泛化 | 改 |
| 3 | `infrastructure/ai/LlmGatewayProperties.java` | `fallbackEnabled` → `degradeEnabled` | 改 |
| 4 | `config/ChatModelConfig.java` | 产出 `LlmFallbackTier` bean（改名 + `@Order` + 可配） | 改 |
| 5 | `config/BigModelLastResortConfig.java` | 产出 `LlmFallbackTier` bean（`@Order`） | 改 |
| 6 | `config/RestFallbackChatModel.java` | endpoint 从常量 → 构造器参数 | 改 |
| 7 | `resources/application.yml` | `degrade-enabled` + `fallback.model/base-url` | 改 |
| 8 | `resources/application-prod.yml` | 同上 | 改 |
| 9 | `target/classes/application-local.yml` | 同上（gitignored，**真正生效的那份**） | 改 |
| 10 | `test/.../LlmGatewayTest.java` | 15 处 rename | 改 |
| 11 | `test/.../LlmGatewayFirstByteTimeoutTest.java` | 1 处 rename | 改 |
| 12 | `test/.../LlmGatewayThreeTierTest.java` | 1 处 rename + 工厂改构造器 | 改 |
| 13 | `test/.../AdmissionCompletenessTest.java` | 正则扩展 | 改 |
| 14 | `test/.../LlmGatewayTierListTest.java` | **新增** G1/G2 验收用例 | 新 |
| 15 | `docs/03-技术决策记录.md` | 追加 ADR-52 | 改 |

## 2. 新类型

```java
package cn.lwx.lwxaiagent.infrastructure.ai;

/**
 * ADR-52：一个可注册的降级级。
 * 由配置类产出 bean，LlmGateway 经 ObjectProvider 收集并按 @Order 排序。
 */
public record LlmFallbackTier(String name, ChatModel model, String baseUrl) {
    public LlmFallbackTier {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tier name must not be blank");
        java.util.Objects.requireNonNull(model, "tier model must not be null");
        baseUrl = baseUrl == null ? "" : baseUrl;
    }
}
```

**为什么用 record 而不是接口**：tier 是纯数据（名字 + 模型 + 报告用端点），无行为。
每级的**熔断器由网关自己建**（`Map<String, ProviderCircuit>`），不放进 tier ——
熔断器是有状态资源，让配置类持有它会引入"谁 close 它"的额外问题。

## 3. `LlmGateway` 构造器矩阵

| 可见性 | 签名 | 用途 |
| --- | --- | --- |
| `@Autowired` public | `(ChatModel, ObjectProvider<LlmFallbackTier>, LlmGatewayProperties, MeterRegistry, AiTelemetry, ApplicationEventPublisher, Environment)` | Spring 装配 |
| package-private | `(ChatModel, List<LlmFallbackTier>, LlmGatewayProperties, MeterRegistry, AiTelemetry, ApplicationEventPublisher, Environment)` | 核心实现 + **单测构造任意级数** |
| public | `(ChatModel, ChatModel, LlmGatewayProperties, MeterRegistry)` | 兼容 `LlmGatewayTest` 27 用例（**零改动**） |

⛔ **只能有一个 `@Autowired` 构造器** —— 历史事故：曾并存两个，Spring 直接拒绝启动
（`Invalid autowire-marked constructor`），而单测因为不走容器**全绿**。

### 3.1 为什么用 `ObjectProvider` 而不是 `List<LlmFallbackTier>`

**零个 tier 是合法且当前的配置**（ADR-51 两个降级级都关）。而 Spring 对
**构造函数参数 `List<T>`** 的处理是：无候选 bean → `resolveMultipleBeans` 返回 `null`
→ 落到按 `List` 类型找 → 抛 `NoSuchBeanDefinitionException`。**应用起不来。**

`ObjectProvider<T>.orderedStream()` 在零候选时返回**空流**，不报错。

### 3.2 为什么必须 `.filter(Objects::nonNull)`

`BigModelLastResortConfig` 在"缺 `BIGMODEL_API_KEY`"时**返回 `null`**（Spring 记为 `NullBean`，
这是刻意的：最低等级的兜底端点缺凭据不该阻断启动）。`orderedStream()` 可能把该名字
作为元素产出并解出 `null`，故必须过滤。

### 3.3 `@Order` 排序

`ChatModelConfig` 的 tier `@Order(10)`，`BigModelLastResortConfig` 的 `@Order(20)`。
`orderedStream()` 按 `OrderComparator` 升序 → 链序 = `fallback` → `last-resort`，
与 ADR-48 逐字一致。

## 4. 开关语义（口子 1 的修法）

| 配置 | 层 | 默认 | 作用点 |
| --- | --- | --- | --- |
| `app.llm.degrade-enabled` | 运行时 | `true` | `LlmGateway#canDegradeTo` / `#canFallback` |
| `app.llm.fallback-enabled` | 构建期 | `false` | `ChatModelConfig` 的 `@ConditionalOnProperty` |
| `app.llm.last-resort-enabled` | 构建期 | `false` | `BigModelLastResortConfig` 的 `@ConditionalOnProperty` |

**关键**：`LlmGatewayProperties` 是 `@ConfigurationProperties("app.llm")`，
原字段 `fallbackEnabled` 会把 `app.llm.fallback-enabled` **顺带绑进来** ——
这就是"一个 key 两个用途"的机制来源。改名后 `degrade-enabled` 与两个级开关**彻底解耦**。

`degrade-enabled` 默认 `true` 的理由：总闸默认开是安全语义 ——
"降级是否发生"由"链里有没有级"决定；没有级时总闸开着也什么都不做。

**可复现组合**：

```bash
# 只开 bigmodel 兜底（DashScope 不注册）
LLM_DEGRADE_ENABLED=true LLM_FALLBACK_ENABLED=false LLM_LAST_RESORT_ENABLED=true
# 全关（当前默认）
LLM_DEGRADE_ENABLED=true LLM_FALLBACK_ENABLED=false LLM_LAST_RESORT_ENABLED=false
# 应急硬关整条链
LLM_DEGRADE_ENABLED=false
```

## 5. 配置驱动端点（口子 3）

```yaml
app:
  llm:
    fallback:
      model: ${LLM_FALLBACK_MODEL:qwen-plus}
      base-url: ${LLM_FALLBACK_BASE_URL:https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation}
```

`RestFallbackChatModel` 新增 `(apiKey, model, endpoint, connectMs, readMs)` 构造器；
原 `(apiKey, model, connectMs, readMs)` 保留为**重载**，委托到常量
`DEFAULT_ENDPOINT`（原 `ENDPOINT`，改名并公开为 `public static final String`，
供配置类取默认值）。

## 6. 验证方案

### 6.1 判据（先写死，避免事后找理由）

| # | 判据 | 量具 |
| --- | --- | --- |
| V1 | 编译通过 | `BUILD SUCCESS` |
| V2 | 单测全绿且**项数 > 318**（新增用例） | 控制台 `Tests run: N, Failures: 0, Errors: 0` |
| V3 | **应用能启动** | 真启动日志出现 `capacity: gate=24 gateway=24 queue=24 waitMs=3000` |
| V4 | 零 tier 时行为与 ADR-51 一致 | `llm_endpoint_configured_total` 只有 `level="primary"`；`llm.fallback` 条数 = 0 |
| V5 | G1：可单独启用某一级 | 新增单测通过（不依赖真实供应商） |
| V6 | G2：N 级按 `@Order` 依次走 | 新增单测通过 |
| V7 | 主链未被改坏 | E2E 冒烟全绿，`llm_call_total{provider="primary"}` 有成功计数 |
| V8 | 密钥不入库 | diff + 未跟踪文件 + 索引三路扫 `sk-` 形态 |

⛔ **V3 不可省**：本轮的改动正是"单测全绿但 Spring 装配可能失败"的典型形态
（构造器签名 + bean 类型都变了）。历史已踩过一次同形事故。

⛔ **判据只看指标，不 grep 日志**（ADR-51 教训：本项目"量具骗人"累计 6 次）。

### 6.2 三层配置优先级（改配置前必读）

```
环境变量 > target/classes/application-local.yml > src/main/resources/application.yml
```

`target/classes/application-local.yml` 是**唯一真实凭据落点**，且**永不 `mvn clean`**。
改 yml 后必须用 **snakeyaml 语义**（自定义 duplicate-key 检测 constructor）校验，
**不能用 PyYAML** —— 它对重复键静默通过，等于没校验（2026-09-27 实际踩过，
代价是单测 318 → 30 errors）。

## 7. 密钥纪律

- 三处 yml 只写 `${ENV:default}` 占位符，**不写明文**。
- `target/classes/application-local.yml` 已 gitignore；改它前先备份到 `logs/`。
- 提交前扫 diff + 未跟踪文件 + 索引。

## 8. 明确不查 / 不做

- 不查 bigmodel 400 根因（缺 `BIGMODEL_API_KEY`）。
- 不恢复任何降级目标 → **用户可见行为不变**，故不重跑答案质量 A/B。
- 不做故障注入。
- 不补 testcontainers 层。
- 不改 ADR-23 / ADR-31 / ADR-32 的任何不变式。
