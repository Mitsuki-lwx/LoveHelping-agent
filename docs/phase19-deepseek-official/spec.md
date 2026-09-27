# phase19 · 技术方案

## 1. 改动面（共 4 个文件 + 文档）

| 文件 | 改什么 | 是否入库 |
|---|---|---|
| `src/main/resources/application.yml` | `spring.ai.openai.*` 默认值 → DeepSeek；`app.llm` 两级开关默认值 | ✅ 入库 |
| `src/main/resources/application-prod.yml` | `spring.ai.openai.*` → DeepSeek；`fallback-enabled=false`、`last-resort-enabled=false` | ✅ 入库 |
| `src/main/java/.../config/ChatModelConfig.java` | fallback bean 条件化 | ✅ 入库 |
| `target/classes/application-local.yml` | 本地生效配置 → DeepSeek（**改前必须备份**） | ❌ gitignored |
| `docs/phase19-deepseek-official/*`、`docs/03-技术决策记录.md` | 文档与 ADR-51 | ✅ 入库 |

> ⚠️ **`target/classes/application-local.yml` 是唯一真实凭据落点**
> （`src/main/resources/application-local.yml` **不存在**，只有 `.example`）。
> 它靠 `.gitignore` 的 `target/` 兜住，**`mvn clean` 会把它删掉** —— 这就是
> 「禁止 `mvn clean`」这条硬规则的由来。改动前先 `cp` 备份。

## 2. 配置取值

### 2.1 三层配置的优先级（实测确认）

```
application.yml（基座，入库）        ← 默认 sensenova / gpt-4o
  ↑ 被覆盖
application-local.yml（target/classes，gitignored）  ← 默认 openrouter / space-bunny  ← **真正生效**
  ↑ 被覆盖
环境变量 OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL
```

`spring.ai.openai.base-url: ${OPENAI_BASE_URL:https://...}` 的形态意味着：
**yml 里写的是"占位符 + 默认值"**，环境变量优先。所以只 export 环境变量也能生效，
但**要让"不 export 时也走 DeepSeek"就必须改 yml 里的默认值**。

### 2.2 目标值

| 键 | 值 |
|---|---|
| `spring.ai.openai.base-url` | `https://api.deepseek.com` |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY:}`（**只走环境变量**） |
| `spring.ai.openai.chat.options.model` | `deepseek-flash` |
| `app.llm.fallback-enabled` | `false` |
| `app.llm.last-resort-enabled` | `false` |

**completions-path 保持框架默认 `/v1/chat/completions`**：
DeepSeek 官方同时接受 `https://api.deepseek.com/chat/completions` 与
`https://api.deepseek.com/v1/chat/completions`（两条都实测过 200）。
这与 bigmodel 的 `/api/paas/v4` 形态不同，**不要**照搬 ADR-48 里那个
`completionsPath("/chat/completions")` 的写法——那是为智谱专门设的。

### 2.3 fallback bean 条件化

现状（`ChatModelConfig:57`）**无条件**创建 `RestFallbackChatModel`，硬编码 DashScope 原生端点：

```java
@Bean("deepSeekChatModel")
public ChatModel deepSeekFallbackModel(@Value("${spring.ai.dashscope.api-key:}") String dashScopeKey,
                                       LlmGatewayProperties props) {
    return new RestFallbackChatModel(dashScopeKey, "qwen-plus", ...);   // ← 永远注册
}
```

改法：加 `@ConditionalOnProperty(name = "app.llm.fallback-enabled", havingValue = "true")`。
置 `false` 后 bean 不注册 → `LlmGateway` 的
`@Autowired(required = false) @Qualifier("deepSeekChatModel")` 拿到 `null` →
`degradeTiers()` 返回空列表 → 启动横幅**不再打印**不存在的 fallback 级 →
`canFallback()` 恒 false → 不降级。

**为什么用条件化而不是删掉这个 bean**：删掉是单向的；条件化保留回滚能力
（把开关拨回 `true` 即恢复两级），符合 `BigModelLastResortConfig`
已有的「开关式回滚，无需改代码」惯例（`app.llm.last-resort-enabled`）。

**不删 `RestFallbackChatModel` 类本身**：它仍在（带 `@ConditionalOnProperty` 之外无引用），
未来若 dashscope 恢复可达，拨开关即可复活。但要在类注释里写明
「2026-09-27 起域名不可达，已默认关闭」。

### 2.4 `LlmGateway` 一行不改

`degradeTiers()` / `canDegradeTo()` / `canFallback()` 已经全部走
`fallback != null || lastResort != null` 的判据（ADR-48 引入），
`lastResort == null` 与 `fallback == null` 都是已支持的退化路径。
**本次变更只动配置与 bean 注册条件，不动网关逻辑。**

## 3. 验证方案（DoD：编译 + 单测 + E2E 三者缺一不可）

### 3.1 编译 + 单测

本机唯一可用姿势（`mvn`/`mvnw` 已坏）：

```bash
java -classpath "D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=D:\apache-maven-3.9.11\bin\m2.conf" \
  "-Dmaven.home=D:\apache-maven-3.9.11" \
  "-Dmaven.multiModuleProjectDirectory=D:\java\lwx-ai-agent" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

- 基线：**318/318**（接手时独立复跑值）
- ⚠️ 退出码不可信：本机 safe-delete 钩子拦截 pytest/临时目录清理会把退出码变成 1，
  **判据只看 `target/surefire-reports/*.xml` 的 `failures` / `errors`**

### 3.2 真实 E2E（不可跳过）

真启动应用（`spring-boot:run`）+ 真 HTTP 请求 + 真 DeepSeek 官方端点，然后抓
`GET /api/actuator/prometheus`，逐条核对：

| 判据 | 期望 |
|---|---|
| `llm_endpoint_configured_total{level="primary"}` | `target` 含 `https://api.deepseek.com` 与 `deepseek-flash` |
| `llm_endpoint_configured_total{level="fallback"}` | **不存在**（bean 未注册） |
| `llm_endpoint_configured_total{level="last-resort"}` | **不存在** |
| `llm_call_total{provider="primary",outcome="success"}` | > 0 |
| `llm_fallback` 指标条数 | **0** |
| 用户可见结果 | 真实回答，**不是**「AI 服务暂时不可用」 |

> ⚠️ **判据只能用指标**：见 tasks §2.3 —— 日志 grep 在本项目已被证明会给出错误结论。

### 3.3 端到端失败时怎么记

若外部依赖不可用（网络 / 额度），**必须显式声明哪部分未验证**，
不得用"单测全绿"冒充做完（`SOUL.md` 报忧不报喜）。

## 4. 密钥纪律

- DeepSeek key 只落两处：`target/classes/application-local.yml`（gitignored）
  与运行期环境变量；另有 `.workbuddy-ai/.env.local`（gitignored，供脚本复用）
- 提交前必须扫：`git diff` 全文 + 未跟踪文件列表 + `git grep` 关键词
- 顺带发现（**已存在的泄漏，非本次引入**）：`logs/run_answer_stability_ab.sh:101`
  明文硬编码 OpenRouter key。`logs/` 已 gitignore，故未入库，但明文在磁盘上。
  本轮**不改它**（改评测脚本会污染既有 A/B 的可复现性），记录待办。

## 5. 绕过网关的模型调用（T5 排查结论）

见 `checklist.md` F 节。判据：是否存在**不经过 `@Primary ChatModel`（即 `LlmGateway`）**
的模型请求。`LlmGateway` 是唯一重试/熔断/计量所有者（ADR-23）。

## 6. 明确不查的（避免把范围做大）

- **bigmodel 400 的根因**：需要 `BIGMODEL_API_KEY`，而
  `target/classes/application-local.yml` 里 `spring.ai.openai.api-key` 已是
  **占位符**（`${OPENAI_API_KEY}`，18 字符 = 该占位符本身），
  bigmodel key 不在其中（上一轮 `run_answer_stability_ab.sh` 是从
  `logs/application-local.yml.bak-adr48-*` 备份里取的）。
  关掉 last-resort 后该缺陷**不再影响用户**，故降级为待办。
- **space-bunny 额度**：Reset = 2026-09-28 00:00 UTC（= 北京时间 08:00）。
  切到 DeepSeek 后不再依赖它。
