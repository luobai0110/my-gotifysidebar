package com.github.luobai0110;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 极简的轮询等待工具，用于断言异步投递的结果。
 * <p>
 * 类名刻意不以 {@code Test} 开头，避免被 Surefire 的 {@code **&#47;Test*.java} 默认包含规则当成测试类。
 */
final class AwaitSupport {

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private AwaitSupport() {
    }

    static void until(String description, Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待「" + description + "」时被中断", e);
            }
        }
        throw new AssertionError("等待超时：" + description);
    }
}
