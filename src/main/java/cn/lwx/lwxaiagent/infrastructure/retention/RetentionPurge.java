package cn.lwx.lwxaiagent.infrastructure.retention;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * <h3>保留期与删除权（ADR-5）的**执行体**</h3>
 *
 * <p>ADR-5 的决策是：注销/删除请求 → MySQL 软删 → **定期物理清除任务** → **同步删除该用户产生的向量**。
 * 2026-10-01 审计发现：**这三步里的后两步从未实现**（`物理清除` 全仓只出现在一句注释里；
 * 全仓没有任何删向量的代码路径）。本类是它们的落地。</p>
 *
 * <p><b>为什么把 SQL 抽成静态方法</b>（而不是藏在 service 私有方法里）：
 * 集成测试层（ADR-69，Testcontainers）**不启动 Spring 上下文**，只连真容器 ——
 * SQL 若不可从外部调用，这一层就**测不到它**。把 SQL 与执行分开后，
 * `RetentionPurgeIT` 可以拿容器的 {@link JdbcTemplate} 调**同一个方法**，验的就是生产路径。</p>
 *
 * <p>⛔ 两条纪律：
 * <ul>
 *   <li>删除范围必须**显式**（软删标记 / source+userId），不许写"删全部"这种会误伤共享知识库的语句；</li>
 *   <li>方法返回**影响行数** —— 删除是最不该"静默成功"的操作，行数要能被日志与测试看到。</li>
 * </ul>
 */
public final class RetentionPurge {

    private RetentionPurge() {
    }

    /**
     * 物理清除**已被软删且超过保留期**的消息（ADR-5 的"定期物理清除"）。
     * ⛔ 只动 `deleted = 1`：未软删的消息碰都不碰（软删是用户意图的表达，物理清除只是延后执行）。
     */
    public static final String PURGE_DELETED_MESSAGES_SQL =
            "DELETE FROM message WHERE deleted = 1 AND created_at < ?";

    /**
     * 删除某用户**由私信派生的记忆向量**（ADR-5 的"级联到向量库"）。
     * ⛔ 只删 `source='memory'` 且 `metadata.userId = ?`：
     * 共享知识库（source 非 memory）与他人向量**一律不碰** —— 越界删除是不可逆事故。
     * 与 {@code PgVectorVectorStoreConfig} 里已有的裸 SQL 是同一套 metadata 约定。
     */
    public static final String PURGE_USER_MEMORY_VECTORS_SQL =
            "DELETE FROM vector_store WHERE metadata->>'source' = 'memory' AND metadata->>'userId' = ?";

    /** @return 物理删除的行数 */
    public static int purgeDeletedMessages(JdbcTemplate jdbc, Instant cutoff) {
        return jdbc.update(PURGE_DELETED_MESSAGES_SQL, Timestamp.from(cutoff));
    }

    /**
     * 找出**账号已禁用**的用户（注销后 {@code users.enabled = false}）。
     *
     * <p>用于补扫（ADR-75 / phase33 R3）：注销当时若 PG 不可用，向量删除失败、账号照样被禁用 ——
     * 那些残留向量就落在这个集合里。⛔ 只按 {@code enabled = false} 认，
     * **不引入新状态字段**（多一个字段就多一处可能不同步）。</p>
     */
    public static List<String> findDisabledUserIds(JdbcTemplate mysqlJdbc) {
        return mysqlJdbc.queryForList("SELECT username FROM users WHERE enabled = FALSE", String.class);
    }

    /** @return 删除的向量条数 */
    public static int purgeUserMemoryVectors(JdbcTemplate pgJdbc, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空：越界删除不可逆");
        }
        return pgJdbc.update(PURGE_USER_MEMORY_VECTORS_SQL, userId);
    }
}
