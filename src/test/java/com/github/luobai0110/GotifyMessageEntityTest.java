package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GotifyMessageEntityTest {

    @Inject
    MessagePersister persister;

    private static GotifyMessage sample(long id, int priority) {
        return new GotifyMessage(id, 1L, "标题", "正文", priority,
                OffsetDateTime.parse("2026-09-24T10:00:00Z"), Map.of("k", "v"));
    }

    @Test
    @TestTransaction
    void 同一id重复写入只保留一行() {
        long id = 9001L;
        assertTrue(persister.persistIfAbsent(sample(id, 7)), "首次写入应返回 true");
        assertEquals(false, persister.persistIfAbsent(sample(id, 7)), "重复写入应返回 false");
        assertEquals(1L, GotifyMessageEntity.count("id = ?1", id));
    }

    @Test
    @TestTransaction
    void 实体完整保存Gotify字段() {
        long id = 9002L;
        persister.persistIfAbsent(sample(id, 3));

        GotifyMessageEntity saved = GotifyMessageEntity.findById(id);
        assertEquals(1L, saved.appId);
        assertEquals("标题", saved.title);
        assertEquals("正文", saved.message);
        assertEquals(3, saved.priority);
        assertEquals(MailStatus.NOT_APPLICABLE, saved.mailStatus);
        assertTrue(saved.pushedAt != null, "pushedAt 应已写入");
        assertTrue(saved.extrasJson.contains("\"k\""), "extras 应序列化为 JSON");
    }
}
