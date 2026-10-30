package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>IT · 批量驳回（V34）：只驳 PENDING 低分，APPROVED 绝不能被驳回</h3>
 *
 * <p><b>为什么必须有这一层</b>：单测里 mapper 被 mock 掉，"只驳 PENDING 低分"这条保证
 * <b>全在 SQL 里</b>——mock 永远返回你想要的东西，量具对要防的错误免疫。
 * 这里用真 MySQL 跑真 SQL，把 APPROVED 的保护<b>断死</b>。</p>
 *
 * <p>Docker 缺失时跳过而不是假绿（{@code disabledWithoutDocker}）；由 failsafe 在
 * {@code mvn verify} 跑，{@code mvn test} 保持零外部依赖。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 进化技能批量驳回")
class EvolutionBulkRejectIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    private static SqlSessionFactory factory() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try {
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration();
            // ⛔ 手工装配（无 Spring）时必须显式注册 mapper，否则运行期才报 registry 未知
            cfg.addMapper(EvolutionSkillMapper.class);
            bean.setConfiguration(cfg);
            bean.setGlobalConfig(new GlobalConfig());
            return bean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("MyBatis-Plus 手工装配失败", e);
        }
    }

    private static <T> T withMapper(Function<EvolutionSkillMapper, T> fn) {
        try (SqlSession session = factory().openSession(true)) {
            return fn.apply(session.getMapper(EvolutionSkillMapper.class));
        }
    }

    /**
     * 清空 evolution_skill：同类的多个用例共享静态容器，不清就会**用例间顺序依赖**
     * （例如"驳回了 1 条"会被上一个用例留下的 PENDING 低分行影响）。本仓 IT 已有先例
     * （MysqlMigrationIT 显式 clean 后再断言"空库"）。
     */
    private static void clearSkills() {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = c.createStatement()) {
            st.execute("DELETE FROM evolution_skill");
        } catch (Exception e) {
            throw new IllegalStateException("清理 evolution_skill 失败", e);
        }
    }

    private static Long insert(EvolutionSkillMapper m, String status, int score, boolean active) {
        EvolutionSkill s = new EvolutionSkill("default", "it-bulk-" + System.nanoTime(), "d", "c",
                "it-bulk-sess-" + System.nanoTime(), score);
        s.setAuditStatus(status);
        s.setIsActive(active);
        m.insert(s);
        return s.getId();
    }

    @Test
    @DisplayName("只驳 PENDING 且 quality_score <= maxScore；APPROVED 与高分 PENDING 不受影响")
    void onlyPendingLowScoreRejected() {
        clearSkills();
        withMapper(m -> {
            Long pendingLow = insert(m, "PENDING", 3, true);
            Long pendingHigh = insert(m, "PENDING", 9, true);
            Long approved = insert(m, "APPROVED", 3, true);

            List<Long> ids = m.findPendingIdsByMaxScore(5, 2000);
            assertThat(ids).as("候选只含 PENDING 低分").contains(pendingLow)
                    .doesNotContain(pendingHigh, approved);

            assertThat(m.bulkRejectByIds(ids)).isEqualTo(1);
            assertThat(m.selectById(pendingLow).getAuditStatus()).isEqualTo("REJECTED");
            assertThat(m.selectById(pendingLow).getIsActive()).isFalse();
            assertThat(m.selectById(pendingHigh).getAuditStatus()).as("高分 PENDING 不该被驳").isEqualTo("PENDING");
            assertThat(m.selectById(approved).getAuditStatus()).isEqualTo("APPROVED");
            assertThat(m.selectById(approved).getIsActive()).as("APPROVED 必须仍活跃").isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("⛔ 即使把 APPROVED 的 id 直接喂进批量更新，也一行都改不动")
    void approvedIdCanNeverBeRejected() {
        clearSkills();
        withMapper(m -> {
            Long approved = insert(m, "APPROVED", 1, true);

            assertThat(m.bulkRejectByIds(List.of(approved))).isZero();
            assertThat(m.selectById(approved).getAuditStatus()).isEqualTo("APPROVED");
            assertThat(m.selectById(approved).getIsActive()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("limit 真的生效：limit=1 只取 1 条候选")
    void limitIsApplied() {
        clearSkills();
        withMapper(m -> {
            insert(m, "PENDING", 1, true);
            insert(m, "PENDING", 2, true);

            assertThat(m.findPendingIdsByMaxScore(5, 1)).hasSize(1);
            return null;
        });
    }

    @Test
    @DisplayName("列表按状态过滤且带 limit（findByAuditStatus）")
    void listByAuditStatus() {
        clearSkills();
        withMapper(m -> {
            Long pending = insert(m, "PENDING", 7, true);
            Long rejected = insert(m, "REJECTED", 7, false);

            List<EvolutionSkill> pendings = m.findByAuditStatus("PENDING", 200);
            assertThat(pendings).extracting(EvolutionSkill::getId).contains(pending).doesNotContain(rejected);
            assertThat(m.findByAuditStatus("PENDING", 1)).hasSize(1);
            return null;
        });
    }
}
