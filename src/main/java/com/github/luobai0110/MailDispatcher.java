package com.github.luobai0110;

import java.util.List;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 邮件发送的<b>唯一出口</b>。
 * <p>
 * 收敛成一个 bean 是为了让测试能整体替换它，从而断言「是否触发发送」而不真的连 SMTP。
 */
@ApplicationScoped
public class MailDispatcher {

    @Inject
    Mailer mailer;

    /**
     * 发送一封 HTML + 纯文本的多部分邮件。
     *
     * @throws RuntimeException SMTP 不可用或配置缺失时由底层抛出，由调用方捕获
     */
    public void send(List<String> recipients, String subject, String html, String text) {
        mailer.send(Mail.withHtml(recipients.get(0), subject, html)
                .setText(text)
                .setTo(recipients));
    }
}
