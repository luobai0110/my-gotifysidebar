package com.github.luobai0110;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Gotify 推送端点（{@code /stream}）下发的消息。
 * <p>
 * 字段与 Gotify 的 {@code MessageExternal} 对应。标注 {@link JsonIgnoreProperties} 是为了在 Gotify 未来新增字段时
 * 保持前向兼容。
 *
 * @param id       消息 ID
 * @param appid    应用 ID
 * @param title    标题，可能为 {@code null}
 * @param message  正文，可能为 {@code null}
 * @param priority 优先级
 * @param date     推送时间，可能为 {@code null}
 * @param extras   扩展字段，可能为 {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GotifyMessage(
        long id,
        long appid,
        String title,
        String message,
        int priority,
        OffsetDateTime date,
        Map<String, Object> extras) {
}
