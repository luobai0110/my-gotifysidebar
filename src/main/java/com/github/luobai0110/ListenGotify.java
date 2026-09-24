package com.github.luobai0110;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
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

    @OnOpen
    public void onOpen(WebSocketClientConnection connection) {
        log.info("Gotify 推送流已建立，连接 ID：{}", connection.id());
    }

    /**
     * 处理 Gotify 下发的一条推送消息。
     * <p>
     * 当前只做日志输出；后续可在此渲染 Qute 模板并发送邮件。
     */
    @OnTextMessage
    public void onMessage(GotifyMessage gotifyMessage, WebSocketClientConnection connection) {
        log.info("收到 Gotify 消息：{}", gotifyMessage);
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
