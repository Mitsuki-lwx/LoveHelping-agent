package cn.lwx.lwxaiagent.infrastructure.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Jev（TypeSafe AI / System One）配置。
 *
 * <p><b>默认关闭</b>：这是一条外部依赖，且尚未做成本核算与灰度；关闭时全仓行为与接入前完全一致。
 * 密钥只从环境变量 {@code JEV_API_KEY} 注入，不写入任何配置文件（AGENTS.md §3）。</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.jev")
public class JevProperties {
    /** 是否启用 Jev 判定（关闭时调用方走原有回退路径）。 */
    private boolean enabled;
    /** 形如 {@code apikey_...}；来自环境变量 JEV_API_KEY。 */
    private String apiKey;
    private String baseUrl = "https://api.typesafe.ai";
    /** 官方旗舰模型别名。 */
    private String model = "jev-latest";
    /**
     * 单次调用超时。**故意设短**：这些都是后台增强型判定，
     * 宁可回退到原有路径，也不许把请求拖慢。
     */
    @Min(200) @Max(30000) private long timeoutMs = 3000;

    /**
     * 护栏加召回（安全边界）。
     *
     * <p>与"情绪打分替换"分开开关：安全边界的变更必须能独立控制、独立回滚。</p>
     */
    @Valid
    private Guardrail guardrail = new Guardrail();

    /**
     * 护栏第二信号的运行模式。
     *
     * <p><b>为什么是枚举而不是"再加一个 shadow 布尔"</b>：{@code enabled=false} + {@code shadow=true}
     * 是非法态，两个布尔值迟早会被人配出这种组合。关 / 观测 / 拦截本身就是一条有序轴。</p>
     */
    public enum Mode {
        /** 不调用 Jev，行为等于接入前（默认）。 */
        OFF,
        /**
         * 判定并落库（{@code action=SHADOW}），但<b>绝不改变响应</b>。
         *
         * <p>影子观测的全部价值在于"看到真实流量长什么样"；一旦它会影响用户，
         * 那就不是影子而是灰度拦截，拿到的分布也被自己污染了。</p>
         */
        SHADOW,
        /** 判定 + 越阈值拦截（抛 4001 + 转介文案）。 */
        ENFORCE
    }

    @Getter
    @Setter
    public static class Guardrail {
        /** 默认 {@link Mode#OFF}。开启后每条用户消息多一次 Jev 调用（同步，计入首字延迟）。 */
        private Mode mode = Mode.OFF;
        /**
         * 升级阈值：Jev 判"有自伤意愿"的概率达到此值才升级为 L3 自伤处理。
         *
         * <p><b>0.6 是标定出来的，不是拍的</b>（`scripts/jev_guardrail_threshold_probe.py`，
         * 12 例人工标注实测）：阈值 0.3~0.6 召回都是 8/8 且误报 0；0.7 掉到 6/8；0.9 只剩 3/8。
         * 正例最低概率 0.61、负例最高只有 0.05（分离度 12 倍），故 0.6 落在分离带里、且留有余量。</p>
         *
         * <p>⚠️ 初始我拍的是 0.9（"误报会让用户拿不到答案，取高位"）——实测那样会白丢一半召回。
         * 保持这条注释是为了提醒：**门槛类参数应当标定，不要凭直觉取整**。</p>
         */
        @DecimalMin("0.1") @DecimalMax("1.0") private double minProbability = 0.6;
    }
}
