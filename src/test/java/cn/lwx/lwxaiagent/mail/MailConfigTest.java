package cn.lwx.lwxaiagent.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邮件装配的三条分支（V33）。
 *
 * <p>最关键的一条：<b>prod 未配置 SMTP 必须启动失败</b>——否则生产会"接口说已发送、
 * 用户永远收不到码"，找回密码功能静默失效且无人报警。</p>
 */
@DisplayName("MailConfig：SMTP / 开发降级 / prod fail-fast")
class MailConfigTest {

    private final MailConfig config = new MailConfig();

    private static Environment env(boolean prod) {
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("prod")).thenReturn(prod);
        return environment;
    }

    private static MailProperties configured() {
        MailProperties props = new MailProperties();
        props.setHost("smtp.qq.com");
        props.setUsername("u@qq.com");
        props.setPassword("auth-code");
        props.setFrom("u@qq.com");
        return props;
    }

    @Test
    void configured_usesSmtpSender() {
        assertThat(config.mailSenderPort(configured(), env(false))).isInstanceOf(SmtpMailSender.class);
        assertThat(config.mailSenderPort(configured(), env(true))).isInstanceOf(SmtpMailSender.class);
    }

    /** 未配置 + 非 prod → 日志降级，且 send() 不抛（本机没有 SMTP 凭据也能跑通链路）。 */
    @Test
    void unconfigured_nonProd_fallsBackToLogging_andSendDoesNotThrow() {
        MailSenderPort port = config.mailSenderPort(new MailProperties(), env(false));

        assertThat(port).isInstanceOf(LoggingMailSender.class);
        port.send("a@b.com", "绑定邮箱验证码", "验证码：123456");
    }

    /** 未配置 + prod → 抛异常拒绝启动（fail fast，绝不允许静默降级）。 */
    @Test
    void unconfigured_prod_failsFast() {
        assertThatThrownBy(() -> config.mailSenderPort(new MailProperties(), env(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置 SMTP");
    }

    /** 只配一半（缺 password）同样视为未配置 → prod 仍必须失败。 */
    @Test
    void partiallyConfigured_prodStillFailsFast() {
        MailProperties partial = new MailProperties();
        partial.setHost("smtp.qq.com");
        partial.setUsername("u@qq.com");
        partial.setFrom("u@qq.com");   // 缺 password

        assertThat(partial.isConfigured()).isFalse();
        assertThatThrownBy(() -> config.mailSenderPort(partial, env(true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
