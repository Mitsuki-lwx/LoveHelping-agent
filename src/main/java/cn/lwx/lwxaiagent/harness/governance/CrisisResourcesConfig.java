package cn.lwx.lwxaiagent.harness.governance;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code app.crisis.hotlines} 灌进 {@link CrisisResources}（ADR-89）。
 *
 * <p>⛔ <b>为什么用"静态持有 + 启动覆盖"而不是普通的依赖注入</b>：
 * 出站文案的使用方分散在 {@code CheckNode}（图末端）、{@code StreamRegistry}（流式实时拦截）、
 * {@code GuardrailAdvisor}（advisor）、{@code ChatEntry}（入口）、{@code SandboxController} 五处，
 * 其中 {@link GuardrailMessages} 是<b>静态工具类</b>（刻意如此：避免
 * {@code infrastructure.orchestration} ↔ {@code graph.node} 的循环包依赖）。
 * 为了一个"换热线号不用发版"的诉求，把五处都改成注入式，收益远小于风险。</p>
 *
 * <p>所以：<b>默认值写在 {@link CrisisResources}（单一事实源），启动时由配置覆盖一次</b>。
 * 代价是它不可热更（改完要重启），但热线号码的变更频率是"年"级，重启完全可接受。</p>
 */
@Slf4j
@Configuration
public class CrisisResourcesConfig {

    private final String hotlines;

    public CrisisResourcesConfig(@Value("${app.crisis.hotlines:}") String hotlines) {
        this.hotlines = hotlines;
    }

    @PostConstruct
    void apply() {
        if (hotlines == null || hotlines.isBlank()) {
            log.info("未配置 app.crisis.hotlines —— 使用内置默认热线清单（{}）",
                    CrisisResources.hotlines());
            return;
        }
        CrisisResources.overrideHotlines(hotlines);
        log.info("危机干预热线已由配置覆盖（app.crisis.hotlines）");
    }
}
