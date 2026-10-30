package cn.lwx.lwxaiagent.mail;

/**
 * <h1>邮件发送端口（V33）</h1>
 *
 * <p>认证链只依赖这个抽象，不关心底层是 SMTP 还是本地日志：
 * 生产/已配置 → {@link SmtpMailSender}；开发未配置 → {@link LoggingMailSender}。</p>
 *
 * <p><b>失败语义：</b>发送失败（SMTP 拒绝、超时、认证失败）必须<b>抛出异常</b>，
 * 由上层转成明确的错误响应——绝不能静默吞掉后仍告诉用户"验证码已发送"。</p>
 *
 * @see MailConfig 选择实现的唯一入口
 */
public interface MailSenderPort {

    /**
     * 发送一封纯文本邮件。
     *
     * @param to      收件人地址
     * @param subject 主题
     * @param body    正文（纯文本）
     * @throws RuntimeException 发送失败时抛出（调用方须让请求失败，不得假装成功）
     */
    void send(String to, String subject, String body);
}
