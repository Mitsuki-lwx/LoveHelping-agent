package cn.lwx.lwxaiagent.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * <h1>开发路径邮件实现（V33）—— 未配置 SMTP 时的降级</h1>
 *
 * <p><b>这不是"发送成功"，而是显式的开发降级：</b>验证码以 {@code WARN} 打到应用日志，
 * 便于本机在没有任何 SMTP 凭据的情况下验证整条「发码 → 校验 → 绑定/找回」链路。</p>
 *
 * <p>⛔ 只在<b>非 prod</b> profile 且 {@code app.mail.*} 未配置时启用；
 * prod 未配置 SMTP 由 {@link MailConfig} 直接拒绝启动（fail fast）。</p>
 */
@Slf4j
public class LoggingMailSender implements MailSenderPort {

    @Override
    public void send(String to, String subject, String body) {
        log.warn("【开发路径·验证码未真正发出】未配置 SMTP（app.mail.host/username/password/from）——"
                        + "邮件没有发送，仅打印到日志。生产环境必须配置 SMTP（prod 未配置会启动失败）。"
                        + "\n  to={}\n  subject={}\n  body={}",
                to, subject, body);
    }
}
