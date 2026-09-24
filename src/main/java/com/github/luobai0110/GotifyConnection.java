package com.github.luobai0110;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * 负责连接 Gotify 推送流：启动建连、断线后的指数退避重连。
 * <p>
 * {@code /stream} 是服务端单向推送流，客户端只订阅、不回发消息。发送消息是另一个端点
 * （{@code POST /message}）配合 Application token 的职责。
 */
@ApplicationScoped
public class GotifyConnection {

    private static final Logger log = LoggerFactory.getLogger(GotifyConnection.class);

    @Inject
    WebSocketConnector<ListenGotify> connector;

    @Inject
    GotifyConfig gotifyConfig;

    /**
     * 应用启动后立即建连。没有这个观察者，{@code @ApplicationScoped} 的 Bean 永远不会被实例化，
     * 也就不会有任何连接，端点的回调自然无从触发。
     */
    void onStart(@Observes StartupEvent event) {
        if (gotifyConfig.token().isEmpty() || gotifyConfig.token().get().isBlank()) {
            log.error("未配置 gotify.token（环境变量 GOTIFY_CLIENT_TOKEN），跳过连接 Gotify。"
                    + "注意这里必须是 Client token，Application token 会被服务端以 401 拒绝。");
            return;
        }
        connectWithRetry();
    }

    /**
     * 建连，失败时按指数退避重试。
     * <p>
     * 401（token 类型不对）这类错误重试也不会成功，但统一走重试路径可以让临时性的网络抖动
     * 自愈；重试次数上限由 {@code gotify.reconnect.max-retries} 控制。
     */
    void connectWithRetry() {
        GotifyConfig.Reconnect reconnect = gotifyConfig.reconnect();
        boolean connected = ReconnectBackoff.run(
                reconnect.maxRetries(),
                reconnect.initialDelay(),
                reconnect.maxDelay(),
                reconnect.multiplier(),
                this::tryConnect);

        if (!connected) {
            log.error("连接 Gotify 推送流失败，已重试 {} 次后放弃。请检查 base-url（{}）与 Client token 是否正确。",
                    reconnect.maxRetries(), gotifyConfig.baseUrl());
        }
    }

    /**
     * 单次建连尝试。
     *
     * @return 建连成功返回 {@code true}
     */
    private boolean tryConnect(int attempt) {
        try {
            WebSocketClientConnection connection = connector
                    .baseUri(gotifyConfig.baseUrl())
                    .addHeader("X-Gotify-Key", gotifyConfig.token().orElse(""))
                    .connectAndAwait();
            log.info("已连接 Gotify 推送流（第 {} 次尝试），连接 ID：{}", attempt, connection.id());
            return true;
        } catch (Exception e) {
            log.warn("第 {} 次连接 Gotify 失败：{}", attempt, rootMessage(e));
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
