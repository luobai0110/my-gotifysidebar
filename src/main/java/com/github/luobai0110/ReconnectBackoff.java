package com.github.luobai0110;

import java.time.Duration;
import java.util.function.IntPredicate;

/**
 * 指数退避重试的纯逻辑实现，与 WebSocket、Quarkus 无关，便于单元测试。
 * <p>
 * 延迟序列为 {@code initialDelay * multiplier^(attempt-1)}，并以 {@code maxDelay} 封顶。
 */
final class ReconnectBackoff {

    private ReconnectBackoff() {
    }

    /**
     * 按指数退避策略重试，直到成功或耗尽重试次数。
     *
     * @param maxRetries   最大重试次数（不含首次尝试）；0 表示只尝试一次
     * @param initialDelay 首次重试前的等待时长
     * @param maxDelay     单次等待的时长上限
     * @param multiplier   每次重试延迟的倍数
     * @param attempt      单次尝试，返回 {@code true} 表示成功
     * @return 任一次尝试成功返回 {@code true}，否则 {@code false}
     */
    static boolean run(int maxRetries, Duration initialDelay, Duration maxDelay, int multiplier,
            IntPredicate attempt) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负数: " + maxRetries);
        }
        // 首次尝试
        if (attempt.test(1)) {
            return true;
        }
        for (int retry = 1; retry <= maxRetries; retry++) {
            sleep(delayForAttempt(retry, initialDelay, maxDelay, multiplier));
            if (attempt.test(retry + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算第 {@code attempt} 次尝试前应等待的时长。
     *
     * @param attempt 第几次尝试，从 1 开始
     */
    static Duration delayForAttempt(int attempt, Duration initialDelay, Duration maxDelay, int multiplier) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须从 1 开始: " + attempt);
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier 必须 >= 1: " + multiplier);
        }
        // 用 double 累乘后统一截断，避免 long 溢出
        double factor = Math.pow(multiplier, attempt - 1);
        long millis = (long) (initialDelay.toMillis() * factor);
        if (millis < 0 || millis > maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis(millis);
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重连等待被中断", e);
        }
    }
}
