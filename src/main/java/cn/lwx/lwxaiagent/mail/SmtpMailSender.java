package cn.lwx.lwxaiagent.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * <h1>SMTP 邮件发送实现（V33）</h1>
 *
 * <p>按 {@link MailProperties}（{@code app.mail.*}）自建 {@link JavaMailSenderImpl}，
 * <b>不使用 Spring Boot 的 {@code spring.mail.*} 自动配置</b>——本项目的凭据统一放在
 * {@code app.mail.*}，避免两套配置各说各话。</p>
 *
 * <p><b>加密方式按端口推导</b>（不额外加配置项，少一个能配错的地方）：</p>
 * <ul>
 *   <li>465 → {@code mail.smtp.ssl.enable=true}（隐式 SSL，QQ/163 常用）</li>
 *   <li>587 → {@code mail.smtp.starttls.enable=true}（STARTTLS）</li>
 *   <li>其它端口 → 按明文发送（仅限内网中继）</li>
 * </ul>
 *
 * <p>三个超时都设为 10 秒：SMTP 卡死不能把请求线程一起拖死
 * （本调用在用户请求路径上同步执行）。</p>
 */
@Slf4j
public class SmtpMailSender implements MailSenderPort {

    private static final int TIMEOUT_MS = 10_000;

    private final JavaMailSenderImpl sender;
    private final String from;

    public SmtpMailSender(MailProperties props) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(props.getHost());
        impl.setPort(props.getPort());
        impl.setUsername(props.getUsername());
        impl.setPassword(props.getPassword());
        impl.setDefaultEncoding("UTF-8");

        Properties javaMail = impl.getJavaMailProperties();
        javaMail.put("mail.smtp.auth", "true");
        javaMail.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        javaMail.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        javaMail.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        if (props.getPort() == 465) {
            javaMail.put("mail.smtp.ssl.enable", "true");
        } else if (props.getPort() == 587) {
            javaMail.put("mail.smtp.starttls.enable", "true");
        }

        this.sender = impl;
        this.from = props.getFrom();
        log.info("SmtpMailSender initialized: host={}, port={}, from={}",
                props.getHost(), props.getPort(), mask(props.getFrom()));
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);   // 失败抛 MailException → 上层转成明确错误，不静默
        log.info("Mail sent to {}", mask(to));
    }

    /** 日志里不打完整邮箱（PII 最小化）。 */
    private static String mask(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
