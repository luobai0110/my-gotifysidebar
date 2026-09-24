package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 指数退避重试的纯逻辑单元测试，不依赖 Quarkus 运行时。
 */
class ReconnectBackoffTest {

    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(60);

    @Test
    void succeedsOnFirstAttemptWithoutSleeping() {
        int[] calls = { 0 };
        boolean result = ReconnectBackoff.run(3, INITIAL, MAX, 2, attempt -> {
            calls[0]++;
            return true;
        });

        assertEquals(true, result, "首次成功应返回 true");
        assertEquals(1, calls[0], "首次成功不应重试");
    }

    @Test
    void retriesExactlyThreeTimesThenGivesUp() {
        int[] calls = { 0 };
        boolean result = ReconnectBackoff.run(3, Duration.ZERO, Duration.ZERO, 2, attempt -> {
            calls[0]++;
            return false;
        });

        assertEquals(false, result, "三次都失败应返回 false");
        assertEquals(4, calls[0], "1 次首发 + 3 次重试 = 4 次调用");
    }

    @Test
    void stopsRetryingOnceAnAttemptSucceeds() {
        int[] calls = { 0 };
        boolean result = ReconnectBackoff.run(3, Duration.ZERO, Duration.ZERO, 2, attempt -> {
            calls[0]++;
            return calls[0] >= 2;
        });

        assertEquals(true, result, "第二次成功应返回 true");
        assertEquals(2, calls[0], "成功之后不应继续重试");
    }

    @Test
    void delaysGrowExponentiallyAndAreCappedAtMax() {
        // 1s, 2s, 4s ... 上限 60s
        assertEquals(Duration.ofSeconds(1), ReconnectBackoff.delayForAttempt(1, INITIAL, MAX, 2));
        assertEquals(Duration.ofSeconds(2), ReconnectBackoff.delayForAttempt(2, INITIAL, MAX, 2));
        assertEquals(Duration.ofSeconds(4), ReconnectBackoff.delayForAttempt(3, INITIAL, MAX, 2));
        assertEquals(Duration.ofSeconds(8), ReconnectBackoff.delayForAttempt(4, INITIAL, MAX, 2));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        assertEquals(MAX, ReconnectBackoff.delayForAttempt(20, INITIAL, MAX, 2));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> ReconnectBackoff.delayForAttempt(0, INITIAL, MAX, 2),
                "attempt 从 1 开始，0 应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> ReconnectBackoff.run(-1, INITIAL, MAX, 2, attempt -> true),
                "负数最大重试次数应被拒绝");
    }
}
