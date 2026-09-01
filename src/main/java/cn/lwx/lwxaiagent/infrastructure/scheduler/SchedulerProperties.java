package cn.lwx.lwxaiagent.infrastructure.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台调度预算配置（ADR-20）。
 * <p>所有阈值可配置、有默认；默认值对既有行为无回归（配额默认宽松、节奏与原硬编码一致）。
 * 排查与压测时 {@code app.scheduler.master-enabled=false} 一键静默全部后台调度。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.scheduler")
public class SchedulerProperties {

    /** 总开关：false 时所有后台调度直接跳过（排查/压测用） */
    private boolean masterEnabled = true;

    /** 每分钟允许后台消耗的 LLM/embedding 调用次数（全调度器共享，进程内令牌桶） */
    private int llmBudgetPerMinute = 30;

    /** 单轮调度最长执行毫秒数：超出即结束本轮，剩余候选留待下轮 */
    private long maxRunMs = 60_000;

    /** 空转退避：连续 N 轮无候选后，实际执行频率按 2 的幂衰减（封顶 max-multiplier 倍） */
    private IdleBackoff idleBackoff = new IdleBackoff();

    /** 技能反思调度器（ReflectionScheduler） */
    private SchedulerSpec reflect = new SchedulerSpec();

    /** 记忆萃取调度器（MemoryExtractionScheduler.scanAndExtract） */
    private SchedulerSpec extract = new SchedulerSpec();

    @Data
    public static class SchedulerSpec {
        /** 单调度器开关 */
        private boolean enabled = true;
        /** 扫描间隔（毫秒）——与 @Scheduled fixedDelay 绑定 */
        private long fixedDelayMs;
        /** 单轮批量上限（候选会话数） */
        private int batchLimit = 10;
    }

    @Data
    public static class IdleBackoff {
        /** 连续多少轮无候选后开始退避 */
        private int threshold = 3;
        /** 退避倍数封顶（2 的幂次封顶：8 表示最多 8 倍间隔） */
        private int maxMultiplier = 8;
    }
}
