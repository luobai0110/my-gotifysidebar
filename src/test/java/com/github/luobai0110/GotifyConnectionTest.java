package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class GotifyConnectionTest {

    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(60);

    @Test
    void 首次重连使用初始延迟() {
        assertEquals(Duration.ofSeconds(1), GotifyConnection.nextDelay(0, INITIAL, MAX, 2));
    }

    @Test
    void 延迟按倍数增长() {
        assertEquals(Duration.ofSeconds(2), GotifyConnection.nextDelay(1, INITIAL, MAX, 2));
        assertEquals(Duration.ofSeconds(4), GotifyConnection.nextDelay(2, INITIAL, MAX, 2));
        assertEquals(Duration.ofSeconds(32), GotifyConnection.nextDelay(5, INITIAL, MAX, 2));
    }

    @Test
    void 延迟封顶在最大值() {
        assertEquals(Duration.ofSeconds(60), GotifyConnection.nextDelay(10, INITIAL, MAX, 2));
    }

    @Test
    void 极大次数不会溢出() {
        assertEquals(Duration.ofSeconds(60), GotifyConnection.nextDelay(5000, INITIAL, MAX, 2));
    }
}
