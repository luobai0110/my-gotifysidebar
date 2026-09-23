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
    }
}
