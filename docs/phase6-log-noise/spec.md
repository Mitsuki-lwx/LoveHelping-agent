# 方案：上游故障期日志降噪

## 1. 方案选择（为什么不是更简单的做法）

| 方案 | 能否达标 | 结论 |
| --- | --- | --- |
| **A. `logging.level: OFF`** 静默三个第三方 logger | 体积达标 | ❌ **弃**：完全失去样本。上游故障时"embedding 为什么失败"（网络 / 配额 / 鉴权）无从查起，而这正是排障第一入口 |
| **B. logback 内置 `DuplicateMessageFilter`** | 只能治 1/3 | ❌ **弃**：它按**格式化消息**去重。`MessageAggregator` 的 `Aggregation Error` 是固定文本 ✅，但 `DashScopeEmbeddingModel` 的 `Error embedding request: [你好]` 与重试日志**含变量**（query / 异常详情）→ 无法去重 |
| **C. 自定义 `TurboFilter`：按 logger + 时间窗限流（本方案）** | 达标 | ✅ **采纳**：三个 logger 都治；窗口内**保留样本**、窗口结束给**抑制汇总**，排障信息不丢 |
| **D. 改第三方源码 / 包一层** | 治本 | ❌ 不可行：`MessageAggregator`、`SpringAiRetryAutoConfiguration`、`DashScopeEmbeddingModel` 均为依赖内部类 |

## 2. 设计

### 2.1 组件

新增 `infrastructure/logging/LogThrottleFilter`（logback `TurboFilter`）：

```
decide(marker, logger, level, format, params, throwable):
  if (!enabled) return NEUTRAL                    # 一键回退
  if (白名单不含 logger.getName()) return NEUTRAL  # 白名单外完全放行（零影响）
  window = windows.computeIfAbsent(logger, new Window)
  若窗口已过期 → 滚动（旧窗口的 suppressed 计数交给汇总线程输出）
  若本窗口放行数 < maxPerWindow → 放行数++，return NEUTRAL（含完整堆栈）
  否则 suppressed++，return DENY
```

### 2.2 抑制汇总（关键：让排障者知道"有东西被压了"）

用一个 **daemon 单线程调度器**（period = `windowMs`）遍历各窗口：
若某窗口 `suppressed > 0`，用**专用 logger 名 `app.log.throttled`** 输出一条汇总并清零：

```
上游降级噪音抑制：logger=c.a.c.a.d.e.DashScopeEmbeddingModel 在过去 10s 内被抑制 3214 条
（窗口内已保留 3 条完整样本）
```

`app.log.throttled` **不在白名单** → 不会递归触发限流。

### 2.3 为什么不改业务代码

分析显示：**我们自己的降级日志已经不打全栈**（`MemoryVectorStore` 等只打 message），
它们是结构化信息源，**本就不该被压**。噪音 100% 来自第三方 logger 的内部错误输出，
且同一失败我们**在入口层已经处理**（转 SSE error 事件 / 降级日志 / Prometheus 指标）。
因此用配置层解决即可，**不碰任何业务逻辑**。

## 3. 配置（`logback-spring.xml`，此前不存在此文件）

```xml
<configuration>
  <springProperty name="throttleEnabled"  source="app.logging.throttle.enabled"          defaultValue="true"/>
  <springProperty name="throttleWindowMs" source="app.logging.throttle.window-ms"        defaultValue="10000"/>
  <springProperty name="throttleMax"      source="app.logging.throttle.max-per-window"   defaultValue="3"/>
  <springProperty name="throttleLoggers"  source="app.logging.throttle.loggers"
      defaultValue="org.springframework.ai.chat.model.MessageAggregator,org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration,com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel"/>

  <turboFilter class="cn.lwx.lwxaiagent.infrastructure.logging.LogThrottleFilter">
    <enabled>${throttleEnabled}</enabled>
    <windowMs>${throttleWindowMs}</windowMs>
    <maxPerWindow>${throttleMax}</maxPerWindow>
    <loggers>${throttleLoggers}</loggers>
  </turboFilter>

  <!-- 其它 logger 行为不变：沿用 Spring Boot 默认 console 输出 -->
  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
  <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
  <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

`application.yml` 增补：

```yaml
app:
  logging:
    throttle:
      enabled: true          # 一键回退：false 即完全恢复原行为
      window-ms: 10000       # 限流窗口
      max-per-window: 3      # 每窗口保留的完整样本数
      loggers: <见上默认值>
```

## 4. 预期效果（算账）

故障期每条第三方错误约 **70 行堆栈**（93.9% 为堆栈行，1.9GB / 16M 行 ≈ 125 字节/行）。

| 项 | 现状 | 修复后（估算） |
| --- | --- | --- |
| 第三方错误产生速率 | 约 210K 条 / 30 min ≈ **117 条/s** | 每 logger 每 10s 最多 3 条 → 3×3/10s = **0.9 条/s** |
| 堆栈行速率 | ~110 行/s × 70 ≈ **7700 行/s** | ~0.9/s × 70 ≈ **63 行/s** |
| 体积速率 | **≈3.6 GB/h** | **≈30 MB/h**（降幅 ≈99%） |

保守目标 `<0.2 GB/h` 有较大裕量。

## 5. 验证设计

1. **单测**（`LogThrottleFilterTest`）：窗口内前 N 条放行、之后 DENY；窗口滚动后重置；
   白名单外 logger 永不 DENY；`enabled=false` 时全放行。
2. **真实复现**（关键，不用 mock）：DashScope 当前**恰好不可达**（DNS → 198.18.0.177），
   天然可制造"上游全故障"工况：
   - 起真实栈（mcp-server + 应用），跑 `scripts/soak.py` N 分钟
   - 采样应用日志文件大小随时间变化 → 算 GB/h
   - 与基线 `logs/soak/app-20260916c.log`（1.78 GB / 30 min）对比
3. **可观测性未丢**：确认（a）白名单 logger 仍有样本；（b）出现 `app.log.throttled` 汇总条；
   （c）Prometheus 指标（`llm.call{outcome=fail}`、检索降级）仍正常增长。
4. **回归**：全量单测 + 真实 E2E 22/22。

## 6. 回退

`app.logging.throttle.enabled=false`（或删除 `logback-spring.xml`）即完全恢复原行为，无需回滚代码。
