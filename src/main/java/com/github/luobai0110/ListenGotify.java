package com.github.luobai0110;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gotify 推送流端点。只处理连接事件，不负责建连与重连（那是 {@link GotifyConnection} 的职责）。
 * <p>
 * 认证走 {@code X-Gotify-Key} 头，且必须是 <b>Client token</b>。
 */
@WebSocketClient(path = "/stream")
public class ListenGotify {

    private static final Logger log = LoggerFactory.getLogger(ListenGotify.class);

    @Inject
    MessageService messageService;

    @OnOpen
    public void onOpen(WebSocketClientConnection connection) {
        log.info("Gotify 推送流已建立，连接 ID：{}", connection.id());
    }

    /**
     * 处理 Gotify 下发的一条推送消息，委托给 {@link MessageService} 完成入库与转发。
     * <p>
     * 端点保持轻薄：这里不做业务判断，也不让异常逃逸 —— 任何异常都会中断推送流的消费。
     */
    @OnTextMessage
    public void onMessage(GotifyMessage gotifyMessage, WebSocketClientConnection connection) {
        log.info("收到 Gotify 消息：id={} 优先级={}", gotifyMessage.id(), gotifyMessage.priority());
        try {
            messageService.handle(gotifyMessage);
        } catch (Exception e) {
            log.error("处理 Gotify 消息 {} 时发生未预期异常", gotifyMessage.id(), e);
        }
    }

    @OnClose
    public void onClose(WebSocketClientConnection connection, CloseReason closeReason) {
        log.warn("Gotify 推送流已关闭，连接 ID：{}，原因：{}", connection.id(), closeReason);
    }

    @OnError
    public void onError(WebSocketClientConnection connection, Throwable throwable) {
        log.error("Gotify 推送流发生错误，连接 ID：{}", connection.id(), throwable);
    }
}
