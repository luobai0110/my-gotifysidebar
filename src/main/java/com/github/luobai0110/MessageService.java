package com.github.luobai0110;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 收到消息后的编排：入库 → 阈值判断 → 渲染 → 发信 → 回写状态。
 * <p>
 * <b>事务边界是本类的核心约束：</b>
 * <ul>
 *   <li>入库由 {@link MessagePersister#persistIfAbsent} 在<b>事务 1</b> 中完成并提交，
 *       保证消息一定落库；</li>
 *   <li>发信在<b>事务外</b>执行 —— SMTP 可能耗时数秒，包进事务会长期占用数据库连接，
 *       且发信失败会连累已入库的数据回滚，与「先持久化」的要求直接矛盾；</li>
 *   <li>回写由 {@link MessagePersister#updateMailStatus} 在<b>事务 2</b> 中完成。</li>
 * </ul>
 * 因此本类的方法<b>不能</b>标 {@code @Transactional}，否则整个方法会被包进一个事务。
 * <p>
 * 另一条铁律：<b>任何邮件相关异常都不得传播出去</b>，否则会打断 WebSocket 监听循环。
 */
@ApplicationScoped
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** 展示用固定时区。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    MessagePersister persister;

    @Inject
    MailDispatcher mailDispatcher;

    @Inject
    GotifyConfig gotifyConfig;

    @Location("message.html")
    Template htmlTemplate;

    @Location("message.txt")
    Template textTemplate;

    /**
     * 处理一条 Gotify 消息。本方法不抛异常。
     */
    public void handle(GotifyMessage message) {
        // 步骤 1：幂等入库（事务 1，已提交）。重复投递直接结束，不更新、不发信。
        boolean isNew;
        try {
            isNew = persister.persistIfAbsent(message);
        } catch (Exception e) {
            log.error("消息 {} 入库失败，已跳过", message.id(), e);
            return;
        }
        if (!isNew) {
            log.debug("消息 {} 已处理过，跳过", message.id());
            return;
        }

        // 步骤 2：阈值判断。收件人未配置或未达阈值都记为 NOT_APPLICABLE。
        if (message.priority() <= gotifyConfig.mail().priorityThreshold()) {
            log.debug("消息 {} 优先级 {} 未超过阈值 {}，不发信",
                    message.id(), message.priority(), gotifyConfig.mail().priorityThreshold());
            return;
        }
        List<String> recipients = gotifyConfig.mail().to().orElse(List.of());
        if (recipients.isEmpty()) {
            log.warn("消息 {} 优先级 {} 达到转发条件，但未配置 gotify.mail.to，跳过发信",
                    message.id(), message.priority());
            return;
        }

        // 步骤 3 与 4：渲染并发送（事务外），失败只记状态。
        try {
            PriorityPalette palette = PriorityPalette.of(message.priority());
            String title = orDefault(message.title(), "(无标题)");

            // 注意：这里必须用 HashMap 而不是 Map.of —— Map.of 不接受 null 值，
            // 而没有 extras 的消息 formatExtras 会返回 null，用 Map.of 会抛 NPE。
            Map<String, Object> params = new HashMap<>();
            params.put("title", title);
            params.put("message", orDefault(message.message(), "(无正文)"));
            params.put("priority", message.priority());
            params.put("color", palette.color());
            params.put("label", palette.label());
            params.put("appId", message.appid());
            params.put("timeText", formatTime(message.date()));
            params.put("extrasText", formatExtras(message.extras()));

            String html = htmlTemplate.data(params).render();
            String text = textTemplate.data(params).render();
            String subject = "[Gotify][优先级 %d] %s".formatted(message.priority(), title);

            mailDispatcher.send(recipients, subject, html, text);

            persister.updateMailStatus(message.id(), MailStatus.SENT, null);
            log.info("消息 {} 已转发至 {} 个收件人", message.id(), recipients.size());
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("消息 {} 发信失败：{}", message.id(), reason, e);
            try {
                persister.updateMailStatus(message.id(), MailStatus.FAILED, reason);
            } catch (Exception nested) {
                log.error("消息 {} 的失败状态回写也失败了", message.id(), nested);
            }
        }
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * 显式按展示时区格式化。不能直接用 {@code message.date()} 的偏移量 ——
     * Jackson 的 {@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE} 已把它归一化到 UTC。
     */
    private static String formatTime(OffsetDateTime date) {
        if (date == null) {
            return "(未知时间)";
        }
        return date.atZoneSameInstant(DISPLAY_ZONE).format(TIME_FORMAT);
    }

    private static String formatExtras(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        extras.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(key).append(": ").append(value);
        });
        return sb.toString();
    }
}
