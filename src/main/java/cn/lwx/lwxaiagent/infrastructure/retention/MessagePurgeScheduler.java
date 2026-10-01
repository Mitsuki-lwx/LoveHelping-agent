package cn.lwx.lwxaiagent.infrastructure.retention;

import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * <h3>软删消息的**定期物理清除**（ADR-5 承诺、长期未实现）</h3>
 *
 * <p>2026-10-01 审计：ADR-5 写明"MySQL 软删 → **定期物理清除任务**"，而全仓 `@Scheduled` 只有
 * 反思/萃取/Agent 三类，**没有清除任务**；`物理清除` 只存在于 `Message.java` 的一句注释里。
 * ⇒ 软删的消息**永远留在库里**，"删除权"只停在了标记层。</p>
 *
 * <p><b>两条硬约束</b>：
 * <ul>
 *   <li>⛔ **尊重总闸 `app.scheduler.master-enabled`** —— 排查/压测/评测期一键静默后台调度，
 *       清除任务也必须在其中，否则评测期的数据会被它悄悄改掉。</li>
 *   <li>⛔ **保留期可配**（`app.retention.deleted-message-days`，默认 30 天）—— 这是业务口径，
 *       不该硬编码在代码里；改口径要改配置而不是改代码。</li>
 * </ul>
 */
@Slf4j
@Component
public class MessagePurgeScheduler {

    private final JdbcTemplate mysqlJdbc;
    private final SchedulerProperties schedulerProperties;
    private final int retentionDays;

    public MessagePurgeScheduler(JdbcTemplate mysqlJdbc,
                                 SchedulerProperties schedulerProperties,
                                 @Value("${app.retention.deleted-message-days:30}") int retentionDays) {
        this.mysqlJdbc = mysqlJdbc;
        this.schedulerProperties = schedulerProperties;
        this.retentionDays = retentionDays;
    }

    /** 默认每天一次；首次延迟 10 分钟（避开启动期）。 */
    @Scheduled(fixedDelayString = "${app.retention.purge-fixed-delay-ms:86400000}", initialDelay = 600_000)
    public void purgeDeletedMessages() {
        if (!schedulerProperties.isMasterEnabled()) {
            log.debug("后台调度总闸关闭（app.scheduler.master-enabled=false），跳过软删消息物理清除");
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int rows = RetentionPurge.purgeDeletedMessages(mysqlJdbc, cutoff);
            if (rows > 0) {
                log.info("[ADR-5] 物理清除软删消息 {} 条（deleted=1 且 created_at < {}，保留期 {} 天）",
                        rows, cutoff, retentionDays);
            }
        } catch (Exception e) {
            // ⛔ 清除失败不能拖垮应用：它是维护任务，不是主链路。但必须**喊出来**（不是 debug）
            log.warn("[ADR-5] 软删消息物理清除失败（保留期 {} 天）：{}", retentionDays, e.getMessage());
        }
    }
}
