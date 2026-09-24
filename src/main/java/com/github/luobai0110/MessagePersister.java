package com.github.luobai0110;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 负责消息的落库与状态回写。
 * <p>
 * <b>独立于 MessageService 存在是刻意的</b>：Panache 的 {@code @Transactional}
 * 依赖 CDI 代理，同类内部自调用不会开启事务。把事务方法拆到本 bean，
 * 才能保证「入库」与「回写」各自是独立事务。
 */
@ApplicationScoped
public class MessagePersister {

    private static final Logger log = LoggerFactory.getLogger(MessagePersister.class);

    @Inject
    ObjectMapper objectMapper;

    /**
     * 幂等写入一条消息。
     *
     * @return 实际写入返回 {@code true}；该 id 已存在则跳过并返回 {@code false}
     */
    @Transactional
    public boolean persistIfAbsent(GotifyMessage message) {
        if (GotifyMessageEntity.findByIdOptional(message.id()).isPresent()) {
            log.debug("消息 {} 已存在，跳过重复写入", message.id());
            return false;
        }

        GotifyMessageEntity entity = new GotifyMessageEntity();
        entity.id = message.id();
        entity.appId = message.appid();
        entity.title = message.title();
        entity.message = message.message();
        entity.priority = message.priority();
        entity.pushedAt = message.date();
        entity.extrasJson = toJson(message.extras());
        entity.mailStatus = MailStatus.NOT_APPLICABLE;
        entity.receivedAt = OffsetDateTime.now();
        entity.persist();
        return true;
    }

    /**
     * 回写邮件发送状态。这是一个独立短事务，与入库事务分离。
     */
    @Transactional
    public void updateMailStatus(long id, MailStatus status, String error) {
        GotifyMessageEntity entity = GotifyMessageEntity.findById(id);
        if (entity == null) {
            log.warn("回写状态时找不到消息 {}，可能已被清理", id);
            return;
        }
        entity.mailStatus = status;
        entity.mailError = error;
    }

    private String toJson(java.util.Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(extras);
        } catch (JsonProcessingException e) {
            log.warn("extras 序列化失败，将丢弃该字段", e);
            return null;
        }
    }
}
