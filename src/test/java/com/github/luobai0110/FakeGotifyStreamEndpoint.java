package com.github.luobai0110;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

/**
 * 测试专用：冒充 Gotify 的 /stream 端点，连接建立后立刻推一条消息。
 * 只在测试的 classpath 上存在，不会进入生产包。
 */
@WebSocket(path = "/stream")
public class FakeGotifyStreamEndpoint {

    static final String TITLE = "构建失败";
    static final String BODY = "流水线 #128 在测试阶段失败";

    static final String PAYLOAD = """
            {"id":25,"appid":5,"title":"%s","message":"%s","priority":8,
             "date":"2026-09-23T10:00:00+08:00","extras":{}}
            """.formatted(TITLE, BODY);

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        connection.sendTextAndAwait(PAYLOAD);
    }
}
