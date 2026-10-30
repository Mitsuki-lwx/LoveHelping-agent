package cn.lwx.lwxaiagent.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h1>邮件发送配置（V33 邮箱绑定/找回密码）</h1>
 *
 * <p>对应 {@code app.mail.*}，<b>全部字段都是 {@code ${ENV:}} 占位且默认为空</b>——
 * 仓库里永远不落明文凭据（AGENTS.md §3；CI 有 gitleaks 全历史扫描）。</p>
 *
 * <p><b>未配置时的行为（由 {@link MailConfig} 决定）：</b></p>
 * <ul>
 *   <li>非 prod（本地/测试）：降级为 {@link LoggingMailSender}，验证码打到 WARN 日志，链路可跑通；</li>
 *   <li><b>prod：直接启动失败</b>（fail fast）——绝不允许"发不出去还告诉用户已发送"。</li>
 * </ul>
 *
 * @see MailConfig
 * @see MailSenderPort
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    /** SMTP 服务器（如 smtp.qq.com）。空 = 未配置。 */
    private String host;

    /** SMTP 端口，默认 465（SMTPS 隐式 SSL）。 */
    private int port = 465;

    /** SMTP 登录用户名（通常就是邮箱地址）。空 = 未配置。 */
    private String username;

    /** SMTP 登录密码/授权码（QQ/163 用「授权码」而非登录密码）。空 = 未配置。 */
    private String password;

    /** 发件人地址（From 头）。空 = 未配置。 */
    private String from;

    /**
     * 是否已配置完整（缺一即视为未配置 → 走降级/失败分支）。
     * <p>四个必填项：host / username / password / from。</p>
     */
    public boolean isConfigured() {
        return notBlank(host) && notBlank(username) && notBlank(password) && notBlank(from);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
