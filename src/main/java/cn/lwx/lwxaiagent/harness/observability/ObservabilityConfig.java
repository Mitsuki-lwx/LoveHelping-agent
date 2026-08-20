package cn.lwx.lwxaiagent.harness.observability;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * <h1>可观测性配置类 —— OpenTelemetry 分布式追踪配置</h1>
 *
 * <p><strong>核心作用：</strong>在应用启动时检查 OTLP（OpenTelemetry Protocol）导出端点的配置状态，
 * 输出相应的启动日志，帮助运维人员确认分布式追踪是否已正确配置。</p>
 *
 * <h2>OpenTelemetry 分布式追踪简介</h2>
 * <p>分布式追踪（Distributed Tracing）用于追踪一个请求在微服务系统中的完整调用链路。
 * 在 AI Agent 系统中，追踪可以帮助我们：</p>
 * <ul>
 *   <li>分析 LLM 调用的延迟和性能瓶颈</li>
 *   <li>追踪用户请求经过哪些 Advisor（拦截器）以及每个环节的耗时</li>
 *   <li>关联 LLM 调用与用户请求，快速定位问题</li>
 *   <li>分析 Token 消耗和成本</li>
 * </ul>
 *
 * <h2>工作原理</h2>
 * <p>本类本身<b>不负责</b>创建 OTLP 导出器或实现追踪功能。实际的追踪功能由以下组件自动完成：</p>
 * <ol>
 *   <li><b>Spring Boot Actuator + Micrometer Tracing：</b>自动检测 classpath 上的
 *       {@code opentelemetry-exporter-otlp} 依赖，自动创建 OTLP 导出器</li>
 *   <li><b>本类的角色：</b>仅进行配置验证和启动日志输出，告知运维人员追踪是否已正确配置</li>
 * </ol>
 *
 * <h2>环境变量配置说明</h2>
 * <p>需要设置以下 3 个环境变量（或在 {@code application-local.yml} 中配置）：</p>
 * <pre>
 * OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.langsmith.ai/v1/traces
 * OTEL_EXPORTER_OTLP_HEADERS=x-api-key=${LANGSMITH_API_KEY}
 * OTEL_SERVICE_NAME=lwx-ai-agent
 * </pre>
 *
 * <h2>配置验证逻辑</h2>
 * <ul>
 *   <li>如果 {@code management.otlp.tracing.endpoint} 未配置 → <b>WARN 日志</b>：
 *       提醒运维人员追踪未启用，不会导出任何 trace 数据</li>
 *   <li>如果已配置 → <b>INFO 日志</b>：输出导出端点地址，确认追踪已启用</li>
 * </ul>
 *
 * @see <a href="https://opentelemetry.io/">OpenTelemetry 官方文档</a>
 */
@Slf4j
@Configuration
public class ObservabilityConfig {

    /**
     * <h3>OTLP 导出端点地址</h3>
     *
     * <p>从 Spring 配置中注入 {@code management.otlp.tracing.endpoint} 属性值。
     * 该地址指向 OpenTelemetry Collector 或后端服务（如 LangSmith）的 trace 接收端点。</p>
     *
     * <p><b>默认值：</b>空字符串 {@code ""}（表示未配置）</p>
     * <p><b>当前使用的后端：</b>LangSmith（{@code https://otlp.langsmith.ai/v1/traces}）</p>
     */
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    /**
     * <h3>应用启动时检查 OTLP 配置就绪状态</h3>
     *
     * <p>通过 {@link PostConstruct} 注解，在 Spring Bean 初始化完成后自动执行此方法。</p>
     *
     * <h4>检查逻辑：</h4>
     * <ul>
     *   <li>如果 {@code otlpEndpoint} 为空或只包含空白字符：
     *     <ul>
     *       <li>输出 WARN 级别日志：提醒 OTLP 端点未配置，没有 trace 会被导出</li>
     *       <li>输出 WARN 级别日志：提示运维人员在 {@code application-local.yml} 中配置</li>
     *     </ul>
     *   </li>
     *   <li>如果已配置：
     *     <ul>
     *       <li>输出 INFO 级别日志：确认追踪已启用，并显示导出目标端点地址</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p><strong>设计意图：</strong>在应用启动的早期阶段（PostConstruct）进行检查，
     * 确保运维人员在启动日志中能看到追踪配置状态，避免"追踪未生效而不自知"的情况。</p>
     */
    @PostConstruct
    public void init() {
        if (otlpEndpoint.isBlank()) {
            log.warn("OTLP endpoint not configured — no traces exported.");
            log.warn("Set management.otlp.tracing.endpoint in application-local.yml");
        } else {
            log.info("OpenTelemetry tracing enabled → exporting to {}", otlpEndpoint);
        }
    }
}
