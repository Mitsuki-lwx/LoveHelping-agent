package cn.lwx.lwxaiagent.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * <h1>邮件发送装配（V33）</h1>
 *
 * <p>唯一决定 {@link MailSenderPort} 实现的地方，三条分支：</p>
 * <ol>
 *   <li>{@code app.mail.*} 已配置 → {@link SmtpMailSender}（真实发信）；</li>
 *   <li>未配置 + <b>prod</b> profile → <b>抛异常拒绝启动</b>（fail fast）；</li>
 *   <li>未配置 + 非 prod → {@link LoggingMailSender}（开发路径，验证码打日志）。</li>
 * </ol>
 *
 * <p><b>为什么 prod 必须 fail fast：</b>若允许静默降级，生产环境会"接口返回已发送、
 * 用户永远收不到码"，找回密码功能事实上失效且无人报警——这类故障必须在启动时就暴露。</p>
 */
@Slf4j
@Configuration
public class MailConfig {

    /** 生产 profile 名（与 docker-compose / application-prod.yml 一致）。 */
    static final String PROD_PROFILE = "prod";

    @Bean
    public MailSenderPort mailSenderPort(MailProperties props, Environment environment) {
        if (props.isConfigured()) {
            return new SmtpMailSender(props);
        }
        if (environment.matchesProfiles(PROD_PROFILE)) {
            throw new IllegalStateException(
                    "生产环境（prod profile）未配置 SMTP —— 拒绝启动：app.mail.host/username/password/from "
                            + "必须通过环境变量注入（MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM）。"
                            + "邮箱验证码与找回密码依赖它，不允许降级为「发不出去还告诉用户已发送」。");
        }
        log.warn("未配置 SMTP（app.mail.*）—— 邮件降级为日志输出（开发路径，验证码不会真正发出）。"
                + "本机验证码请查 WARN 日志；生产环境未配置将直接启动失败。");
        return new LoggingMailSender();
    }
}
