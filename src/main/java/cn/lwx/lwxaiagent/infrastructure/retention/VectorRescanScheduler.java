package cn.lwx.lwxaiagent.infrastructure.retention;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h3>残留向量的<b>定时补扫</b>（ADR-75 / phase33 R3）</h3>
 *
 * <p><b>它修的是什么</b>：{@code DeleteService} 注销时同步删该用户的记忆向量，
 * 但那条路径<b>失败不阻断注销</b>（账号已禁用，主诉求已达成）⇒ PG 抖动一次，
 * 就有一批向量**永远留在库里**。向量是从私信派生的个人信息（《个保法》§47），
 * "留着等人来清"是不合格的。</p>
 *
 * <p><b>为什么这么设计</b>：
 * <ul>
 *   <li>⛔ <b>不引入新状态字段</b>：判据只用 {@code users.enabled = false}
 *       —— 注销**已经**把这个事实写进 MySQL 了，多加一个"待清理"标记就多一处可能不同步；
 *   <li>⛔ <b>幂等</b>：已删干净的账号再来扫，删到 0 条，不会重复报错；</li>
 *   <li>⛔ <b>尊重总闸</b> {@code app.scheduler.master-enabled}：评测/压测期冻结后台调度时
 *       它也必须静默（否则评测期间数据会被悄悄改掉）；</li>
 *   <li>⛔ <b>失败不抛</b>：单个账号失败不影响其它账号 —— 补扫要的是"反复能收敛"，不是"一次全成"。</li>
 * </ul>
 */
@Slf4j
@Component
public class VectorRescanScheduler {

    private final JdbcTemplate mysqlJdbc;
    private final JdbcTemplate pgJdbc;
    private final SchedulerProperties schedulerProperties;
    private final int batchLimit;

    public VectorRescanScheduler(org.springframework.jdbc.core.JdbcTemplate mysqlJdbc,
                                 PgvectorProperties pgvectorProperties,
                                 SchedulerProperties schedulerProperties,
                                 @Value("${app.retention.rescan-batch-limit:200}") int batchLimit) {
        this.mysqlJdbc = mysqlJdbc;
        // 与 DeleteService 同一套建法：PG 数据源本仓不是 bean，各自按 PgvectorProperties 构造
        // ⛔ DriverManagerDataSource 没有 4 参构造器（url/user/pass/driver）—— 用 setter 链，
        //    与 DeleteService 里 DataSourceBuilder 的建法保持同一套心智。
        DriverManagerDataSource pgDataSource = new DriverManagerDataSource();
        pgDataSource.setUrl(pgvectorProperties.getUrl());
        pgDataSource.setUsername(pgvectorProperties.getUsername());
        pgDataSource.setPassword(pgvectorProperties.getPassword());
        pgDataSource.setDriverClassName(pgvectorProperties.getDriverClassName());
        this.pgJdbc = new JdbcTemplate(pgDataSource);
        this.schedulerProperties = schedulerProperties;
        this.batchLimit = batchLimit;
    }

    /** 默认每 10 分钟扫一次（够快：合规残留窗口以分钟计，不是以天计） */
    @Scheduled(fixedDelayString = "${app.retention.rescan-fixed-delay-ms:600000}", initialDelay = 120_000)
    public void rescanDisabledUsers() {
        if (!schedulerProperties.isMasterEnabled()) {
            log.debug("后台调度总闸关闭，跳过残留向量补扫");
            return;
        }
        int total = 0;
        int users = 0;
        try {
            List<String> disabled = RetentionPurge.findDisabledUserIds(mysqlJdbc);
            // ⛔ 必须限量：账号多的时候一次全扫会把 PG 打满（补扫是兜底，不是主链路）
            for (String userId : disabled.subList(0, Math.min(disabled.size(), batchLimit))) {
                try {
                    int deleted = RetentionPurge.purgeUserMemoryVectors(pgJdbc, userId);
                    if (deleted > 0) {
                        total += deleted;
                        users++;
                    }
                } catch (Exception e) {
                    // 单个账号失败不中断：补扫靠"反复收敛"，不靠"一次全成"
                    log.warn("[ADR-75] 用户 {} 的残留向量清理失败，下轮重试：{}", userId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[ADR-75] 残留向量补扫本轮失败（不影响主链路）：{}", e.getMessage());
            return;
        }
        if (total > 0) {
            log.info("[ADR-75] 残留向量补扫：清理 {} 个已注销用户的 {} 条向量", users, total);
        }
    }
}
