package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>MyBatis-Plus 的**列 ↔ 字段映射**契约（ADR-69 登记的那块缺口）</h3>
 *
 * <p><b>为什么必须验</b>：48 个单测**全是 Mockito** —— mapper 被 mock 掉，
 * 于是"实体字段名 ↔ 数据库列名"这层映射**从来没有真正执行过**。
 * 它一旦错（少一个 {@code @TableField}、列名拼错、用了库里不存在的列），
 * 单测照样全绿，直到线上某次查询**静默少返回字段**或直接报错。
 * ADR-46 就是同一族（载荷契约没人核对）。</p>
 *
 * <p>这里**不启动 Spring 上下文**（那会拖进 LLM/Redis/MCP），只用
 * {@link MybatisSqlSessionFactoryBean} 把 mapper 挂到**真 MySQL 容器**上 ——
 * 与 ADR-69 的分层一致。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · MyBatis 列字段映射与软删路径")
class MybatisMappingIT {

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
            // ⛔ 手工装配（无 Spring）时必须**显式注册 mapper**，否则运行时才报
            //    'Binding Type ... is not known to the MybatisPlusMapperRegistry'
            cfg.addMapper(MessageMapper.class);
            bean.setConfiguration(cfg);
            bean.setGlobalConfig(new GlobalConfig());
            return bean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("MyBatis-Plus 手工装配失败", e);
        }
    }

    private static <T> T withMapper(java.util.function.Function<MessageMapper, T> fn) {
        try (SqlSession session = factory().openSession(true)) {
            return fn.apply(session.getMapper(MessageMapper.class));
        }
    }

    @Test
    @DisplayName("写入→读回：**每个映射字段**都必须逐字回来（少一个 @TableField 就会静默丢）")
    void all_mapped_fields_round_trip() {
        Message m = new Message();
        m.setConversationId("it_map_conv");
        m.setUserId("it_map_user");
        m.setRole("user");
        m.setContent("正文含\"引号\"、\\反斜杠、emoji 🙂 与中文");
        m.setContentHmac("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        m.setPromptVersion("v42");

        withMapper(mapper -> {
            assertThat(mapper.insert(m)).as("插入应影响 1 行").isEqualTo(1);
            Message got = mapper.selectById(m.getId());
            assertThat(got).as("按 id 必须能读回").isNotNull();
            assertThat(got.getConversationId()).isEqualTo("it_map_conv");
            assertThat(got.getUserId()).isEqualTo("it_map_user");
            assertThat(got.getRole()).isEqualTo("user");
            assertThat(got.getContent()).isEqualTo("正文含\"引号\"、\\反斜杠、emoji 🙂 与中文");
            assertThat(got.getContentHmac())
                    .as("content_hmac ↔ contentHmac 的映射（字段名最容易漏）")
                    .isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            assertThat(got.getPromptVersion()).isEqualTo("v42");
            assertThat(got.getFeedback()).as("V6 默认值必须被读到").isEqualTo("NONE");
            assertThat(got.getDeleted()).as("默认未删").isEqualTo(0);
            return null;
        });
    }

    @Test
    @DisplayName("⛔ 字符串列名（\"conversation_id\"/\"deleted\"）必须与真库一致 —— 拼错只会运行期炸")
    void string_column_names_match_real_schema() {
        withMapper(mapper -> {
            for (int i = 0; i < 3; i++) {
                Message m = new Message();
                m.setConversationId("it_map_c2");
                m.setRole("user");
                m.setContent("c" + i);
                mapper.insert(m);
            }
            // 生产里就是这个写法（DeleteService/清理路径），列名是**裸字符串**，编译期查不出来
            int updated = mapper.update(null, new UpdateWrapper<Message>()
                    .eq("conversation_id", "it_map_c2").set("deleted", 1));
            assertThat(updated).as("按 conversation_id 批量软删").isEqualTo(3);

            List<Message> alive = mapper.selectList(new QueryWrapper<Message>()
                    .eq("conversation_id", "it_map_c2").eq("deleted", 0));
            assertThat(alive).as("软删后按 deleted=0 查应为空 —— 说明两处字符串列名都真的对应上了").isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("selectList 的 DISTINCT 子句（DeleteService 用）不会因映射问题炸")
    void distinct_select_works() {
        withMapper(mapper -> {
            Message m = new Message();
            m.setConversationId("it_map_c3");
            m.setUserId("it_map_u3");
            m.setRole("user");
            m.setContent("x");
            mapper.insert(m);
            List<Message> rows = mapper.selectList(new QueryWrapper<Message>()
                    .eq("user_id", "it_map_u3").select("DISTINCT conversation_id"));
            assertThat(rows).as("注销流程第一步就靠它拿会话列表").isNotEmpty();
            return null;
        });
    }
}
