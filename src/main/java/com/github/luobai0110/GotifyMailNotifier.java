package com.github.luobai0110;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 把 Gotify 推送的消息渲染成 HTML 邮件并发出。
 */
@ApplicationScoped
public class GotifyMailNotifier {

    private static final Logger LOG = Logger.getLogger(GotifyMailNotifier.class);

    /**
     * 标题缺失时，用正文前这么多个字符充当标题。
     */
    private static final int TITLE_FALLBACK_LENGTH = 30;

    @Inject
    Mailer mailer;

    @Inject
    GotifyConfig config;

    @Inject
    @Location("message.html")
    Template messageTemplate;

    /**
     * 发送通知邮件。任何失败都只记录日志，不向调用方抛出 —— 不能让一次 SMTP 抖动影响 WebSocket 连接。
     *
     * @param message 收到的 Gotify 消息
     */
    void notify(GotifyMessage message) {
        List<String> recipients = recipients();
        if (recipients.isEmpty()) {
            LOG.warnf("收到 Gotify 消息但未配置收件人（gotify.mail.to），已忽略：%s", displayTitle(message));
            return;
        }

        String subject = subjectFor(message);
        try {
            String html = messageTemplate
                    .data("title", displayTitle(message))
                    .data("message", nullToEmpty(message.message()))
                    .data("priority", message.priority())
                    .data("appid", message.appid())
                    .data("date", formatDate(message.date()))
                    .render();

            Mail mail = new Mail()
                    .setTo(recipients)
                    .setSubject(subject)
                    .setHtml(html)
                    .setText(plainText(message));

            mailer.send(mail);
            LOG.infof("已发送 Gotify 通知邮件「%s」给 %s", subject, recipients);
        } catch (RuntimeException e) {
            LOG.errorf(e, "发送 Gotify 通知邮件「%s」失败", subject);
        }
    }

    /**
     * @return 过滤掉空项后的收件人列表
     */
    List<String> recipients() {
        return config.mail().to().orElse(List.of()).stream()
                .filter(recipient -> recipient != null && !recipient.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * @return 邮件主题
     */
    static String subjectFor(GotifyMessage message) {
        return "[Gotify] " + displayTitle(message);
    }

    /**
     * @return 用于展示的标题；标题为空时退化为正文前 30 个字符，正文也为空时返回「新消息」
     */
    static String displayTitle(GotifyMessage message) {
        if (message.title() != null && !message.title().isBlank()) {
            return message.title().strip();
        }
        String body = nullToEmpty(message.message()).strip();
        if (body.isEmpty()) {
            return "新消息";
        }
        return body.length() <= TITLE_FALLBACK_LENGTH
                ? body
                : body.substring(0, TITLE_FALLBACK_LENGTH) + "…";
    }

    /**
     * @return 纯文本兜底正文
     */
    static String plainText(GotifyMessage message) {
        return displayTitle(message) + System.lineSeparator()
                + System.lineSeparator()
                + nullToEmpty(message.message());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 把推送时间格式化成本机时区的可读形式。
     * <p>
     * 注意：Jackson 默认会把带偏移量的时间归一化到 UTC，所以 {@code message.date()} 拿到的偏移量已经不可信，
     * 这里统一按系统时区展示。
     */
    private static String formatDate(OffsetDateTime date) {
        return date == null
                ? ""
                : date.atZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
