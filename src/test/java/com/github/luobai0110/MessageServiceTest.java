package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class MessageServiceTest {

    @Inject
    MessageService messageService;

    @Inject
    RecordingMailDispatcher dispatcher;

    @BeforeEach
    void reset() {
        dispatcher.subjects.clear();
        dispatcher.failWith = null;
    }

    private static GotifyMessage msg(long id, int priority, String title, String body) {
        return new GotifyMessage(id, 42L, title, body, priority,
                OffsetDateTime.parse("2026-09-24T02:00:00Z"), Map.of("client::display", "x"));
    }

    @Test
    void 低优先级消息也会入库但不发信() {
        messageService.handle(msg(1001L, 1, "低", "正文"));

        GotifyMessageEntity saved = GotifyMessageEntity.findById(1001L);
        assertNotNull(saved, "优先级 1 的消息也必须入库");
        assertEquals(0, dispatcher.subjects.size(), "低优先级不应发信");
        assertEquals(MailStatus.NOT_APPLICABLE, saved.mailStatus);
    }

    @Test
    void 优先级7超过阈值会发信() {
        messageService.handle(msg(1002L, 7, "告警", "磁盘满了"));

        assertEquals(1, dispatcher.subjects.size(), "优先级 7 应触发发信");
        assertTrue(dispatcher.subjects.get(0).contains("优先级 7"), "主题应含优先级");
        assertTrue(dispatcher.subjects.get(0).contains("告警"), "主题应含标题");
        assertEquals(MailStatus.SENT, GotifyMessageEntity.<GotifyMessageEntity>findById(1002L).mailStatus);
    }

    @Test
    void 优先级6等于阈值不发信() {
        messageService.handle(msg(1003L, 6, "边界", "正文"));

        assertEquals(0, dispatcher.subjects.size(), "阈值是严格大于，6 不应发信");
        assertEquals(MailStatus.NOT_APPLICABLE,
                GotifyMessageEntity.<GotifyMessageEntity>findById(1003L).mailStatus);
    }

    @Test
    void 重复投递同一id只入库一行且只发一次信() {
        messageService.handle(msg(1004L, 8, "重复", "正文"));
        messageService.handle(msg(1004L, 8, "重复", "正文"));

        assertEquals(1L, GotifyMessageEntity.count("id = ?1", 1004L), "同一 id 只应有一行");
        assertEquals(1, dispatcher.subjects.size(), "重复投递不应发第二封邮件");
    }

    @Test
    void 无extras且标题正文为空的紧急消息也能正常发信() {
        // 这条覆盖了一个真实陷阱：没有 extras 时 extrasText 为 null，
        // 曾用 Map.of 传参会抛 NPE 导致消息被误判为 FAILED。
        GotifyMessage bare = new GotifyMessage(1006L, 42L, null, null, 9,
                OffsetDateTime.parse("2026-09-24T02:00:00Z"), null);

        messageService.handle(bare);

        assertEquals(1, dispatcher.subjects.size(), "无 extras 也应正常发信");
        assertTrue(dispatcher.subjects.get(0).contains("(无标题)"), "标题应兜底");
        GotifyMessageEntity saved = GotifyMessageEntity.findById(1006L);
        assertEquals(MailStatus.SENT, saved.mailStatus, "不应被误判为 FAILED");
    }

    @Test
    void 发信失败时消息仍在库中且状态为失败() {
        dispatcher.failWith = new IllegalStateException("SMTP 不可用");

        messageService.handle(msg(1005L, 9, "紧急", "正文"));

        GotifyMessageEntity saved = GotifyMessageEntity.findById(1005L);
        assertNotNull(saved, "发信失败不能影响已入库的消息");
        assertEquals(MailStatus.FAILED, saved.mailStatus);
        assertNotNull(saved.mailError, "应记录失败原因");
        assertTrue(saved.mailError.contains("SMTP 不可用"), "失败原因应被保留");
    }
}
