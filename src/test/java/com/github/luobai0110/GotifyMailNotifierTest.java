package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GotifyMailNotifierTest {

    private static final String RECIPIENT = "test@example.com";
    private static final String SUBJECT = "[Gotify] 单元测试消息";

    @Inject
    GotifyMailNotifier notifier;

    @Inject
    MockMailbox mailbox;

    @Test
    void 渲染模板并发送邮件() {
        GotifyMessage message = new GotifyMessage(7L, 3L, "单元测试消息", "这是正文 <b>不应被当作 HTML</b>",
                5, OffsetDateTime.parse("2026-09-23T12:00:00+08:00"), null);

        notifier.notify(message);

        AwaitSupport.until("邮件投递到 MockMailbox", Duration.ofSeconds(10), () -> findSent().size() == 1);

        Mail mail = findSent().get(0);
        assertTrue(mail.getHtml().contains("单元测试消息"), "HTML 正文应包含标题");
        assertTrue(mail.getHtml().contains("这是正文"), "HTML 正文应包含消息正文");
        assertTrue(mail.getHtml().contains("&lt;b&gt;"), "消息正文必须被 HTML 转义");
        assertTrue(mail.getText().contains("单元测试消息"), "纯文本兜底应包含标题");
    }

    private List<Mail> findSent() {
        return mailbox.getMailsSentTo(RECIPIENT).stream()
                .filter(mail -> SUBJECT.equals(mail.getSubject()))
                .toList();
    }
}
