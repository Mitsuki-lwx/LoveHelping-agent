package cn.lwx.lwxaiagent.infrastructure.ai;

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
}
