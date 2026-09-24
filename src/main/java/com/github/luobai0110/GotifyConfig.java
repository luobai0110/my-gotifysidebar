package com.github.luobai0110;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Gotify 相关的配置，前缀 {@code gotify}。
 */
@ConfigMapping(prefix = "gotify")
public interface GotifyConfig {

    /**
     * Gotify 服务地址。支持 {@code http(s)://} 与 {@code ws(s)://} 四种 scheme。
     */
    @WithDefault("http://localhost:8080")
    String baseUrl();

    /**
     * Gotify 的 token。留空表示未配置。
     */
    Optional<String> token();

    Mail mail();

    Reconnect reconnect();

    interface Mail {

        /**
         * 收件人列表，多个用逗号分隔。
         */
        Optional<List<String>> to();

        /**
         * 邮件转发优先级阈值：严格大于该值才转发（即 7 及以上）。
         * <p>
         * 默认值 6 由 {@code application.yaml} 的 {@code ${GOTIFY_MAIL_PRIORITY_THRESHOLD:6}}
         * 提供。这里刻意不加 {@code @WithDefault} —— YAML 的占位符自带兜底值，该键永远有值，
         * 再加一个默认值也不会被触发，反而形成两处重复定义、改了不生效的隐患。
         */
        int priorityThreshold();
    }

    interface Reconnect {

        /**
         * 首次重连延迟。
         */
        @WithDefault("1s")
        Duration initialDelay();

        /**
         * 重连延迟上限。
         */
        @WithDefault("60s")
        Duration maxDelay();

        /**
         * 每次重连延迟的倍数。
         */
        @WithDefault("2")
        int multiplier();

        /**
         * 最大重试次数（不含首次连接）。
         */
        @WithDefault("3")
        int maxRetries();
    }
}
