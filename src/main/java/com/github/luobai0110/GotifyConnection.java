package com.github.luobai0110;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 负责连接 Gotify 推送流：启动建连、断线后的指数退避重连、关闭时的优雅退出。
 */
@ApplicationScoped
public class GotifyConnection {

    /**
     * 计算第 {@code attempt} 次重连的延迟：{@code min(initialDelay * multiplier^attempt, maxDelay)}。
     *
     * @param attempt      重连次数，从 0 开始
     * @param initialDelay 首次重连延迟
     * @param maxDelay     延迟上限
     * @param multiplier   延迟倍数
     * @return 本次重连应等待的时长
     */
    static Duration nextDelay(int attempt, Duration initialDelay, Duration maxDelay, int multiplier) {
        double raw = initialDelay.toMillis() * Math.pow(multiplier, attempt);
        // Math.pow 在大指数下会得到 Infinity，min 之后自然收敛到 maxDelay
        return Duration.ofMillis((long) Math.min(raw, (double) maxDelay.toMillis()));
    }
}
