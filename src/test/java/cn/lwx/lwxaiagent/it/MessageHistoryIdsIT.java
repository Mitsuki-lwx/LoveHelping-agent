package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.memory.MemoryService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h3>带 id 的历史接口（ADR-74 / phase33 R2）在真 MySQL 上的行为</h3>
 *
 * <p><b>为什么必须在容器里测</b>：这条链路的全部价值就是"那两条查询<b>真的</b>能把行取出来、
 * id <b>真的</b>是主键值"。mapper 被 mock 的单测永远给不出这个保证。</p>
 *
 * <p>同时钉住一个容易分叉的地方：新端点与旧端点必须**取到同一批行、同一顺序** ——
 * 用户来回切换对话时看到内容变了，是很难归因的"幽灵 bug"。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 带 messageId 的历史（ADR-74）")
class MessageHistoryIdsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    private static SqlSessionFactory sessionFactory() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.addMapper(MessageMapper.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Message.class);
        try {
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            bean.setConfiguration(cfg);
            bean.setGlobalConfig(new GlobalConfig());
            return bean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("MyBatis-Plus 装配失败", e);
        }
    }

    private static MemoryService service(MessageMapper mapper) {
        cn.lwx.lwxaiagent.infrastructure.EncryptionService enc = mock(cn.lwx.lwxaiagent.infrastructure.EncryptionService.class);
        when(enc.decrypt(anyString(), anyString())).thenAnswer(i -> i.getArgument(0)); // 明文往返
        return new MemoryService(mapper,
                new JdbcTemplate(new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())),
                enc);
    }

    private static Long insert(MessageMapper mapper, String conv, String role, String content, int deleted) {
        Message m = new Message();
        m.setConversationId(conv);
        m.setUserId("it_user");
        m.setRole(role);
        m.setContent(content);
        m.setFeedback("NONE");
        m.setDeleted(deleted);
        mapper.insert(m);
        return m.getId();
    }

    @BeforeEach
    void reset() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            s.getMapper(MessageMapper.class).delete(null);
        }
    }

    @Test
    @DisplayName("每条都带**真实主键 id**（这正是旧端点给不出的东西）")
    void every_item_carries_real_id() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            MessageMapper mapper = s.getMapper(MessageMapper.class);
            Long first = insert(mapper, "c1", "USER", "你好", 0);
            Long second = insert(mapper, "c1", "ASSISTANT", "我在", 0);

            List<MemoryService.HistoryItem> items = service(mapper).getHistoryWithIds("c1");

            assertThat(items).hasSize(2);
            assertThat(items.get(0).messageId()).isEqualTo(first);
            assertThat(items.get(1).messageId()).isEqualTo(second);
            assertThat(items.get(0).content()).isEqualTo("你好");
            assertThat(items.get(0).role()).isEqualTo("USER");
        }
    }

    @Test
    @DisplayName("⭐ id 是真主键：用它调 feedback 端点的写路径能命中同一行（端到端可用性）")
    void id_is_usable_as_feedback_target() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            MessageMapper mapper = s.getMapper(MessageMapper.class);
            Long id = insert(mapper, "c1", "ASSISTANT", "可被点踩的回复", 0);

            List<MemoryService.HistoryItem> items = service(mapper).getHistoryWithIds("c1");
            Long got = items.get(0).messageId();

            // 模拟 POST /memory/message/{messageId}/feedback 背后的写操作
            Message target = mapper.selectById(got);
            assertThat(target).as("取到的 id 必须真的能定位到那一行").isNotNull();
            assertThat(target.getContent()).isEqualTo("可被点踩的回复");

            target.setFeedback("DISLIKE");
            mapper.updateById(target);
            assertThat(mapper.selectById(id).getFeedback()).isEqualTo("DISLIKE");
        }
    }

    @Test
    @DisplayName("⛔ 软删的消息不出现（与旧端点口径一致，用户不会看到已删内容）")
    void deleted_messages_are_hidden() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            MessageMapper mapper = s.getMapper(MessageMapper.class);
            insert(mapper, "c1", "USER", "保留", 0);
            insert(mapper, "c1", "USER", "已软删", 1);

            List<MemoryService.HistoryItem> items = service(mapper).getHistoryWithIds("c1");

            assertThat(items).hasSize(1);
            assertThat(items.get(0).content()).isEqualTo("保留");
        }
    }

    @Test
    @DisplayName("⭐ 与旧端点取到**同一批行、同一顺序**（两处若分叉，来回切会话会看到内容变化）")
    void same_rows_and_order_as_legacy_endpoint() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            MessageMapper mapper = s.getMapper(MessageMapper.class);
            for (int i = 0; i < 5; i++) {
                insert(mapper, "c1", i % 2 == 0 ? "USER" : "ASSISTANT", "第" + i + "条", 0);
            }
            MemoryService svc = service(mapper);

            List<String> legacyTexts = svc.getHistory("c1").stream()
                    .map(m -> m.getText()).toList();
            List<String> newTexts = svc.getHistoryWithIds("c1").stream()
                    .map(MemoryService.HistoryItem::content).toList();

            assertThat(newTexts).isEqualTo(legacyTexts);
            assertThat(legacyTexts).as("必须有内容，否则这测试是空转").hasSize(5);
        }
    }

    @Test
    @DisplayName("空会话返回空列表（不报错）")
    void empty_conversation_returns_empty() {
        try (SqlSession s = sessionFactory().openSession(true)) {
            assertThat(service(s.getMapper(MessageMapper.class)).getHistoryWithIds("nope")).isEmpty();
        }
    }
}
