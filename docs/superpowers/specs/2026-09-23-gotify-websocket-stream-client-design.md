# Gotify WebSocket 流监听端点设计

- 日期：2026-09-23
- 状态：待评审
- 相关代码：`src/main/java/com/github/luobai0110/ListenGotify.java`

## 1. 背景与目标

`gotify-sidebar` 是一个 Quarkus 3.39.4（Java 21）应用。当前仓库里已经有一个
`@WebSocketClient(path = "/stream")` 的空壳类 `ListenGotify`，但它：

- 没有任何回调方法（`@OnOpen` / `@OnTextMessage` / `@OnClose` / `@OnError`）；
- 没有任何触发建连的代码。

需要说明的是，Quarkus WebSockets Next 的 `@WebSocketClient` **不会自动建连**，
必须由代码注入 `WebSocketConnector<CLIENT>` 并主动调用 `connect()`。因此这个空壳目前
完全不会连出去。

**目标**：把它补成一个可用的 WebSocket 客户端 —— 连接 Gotify 的 `/stream` 端点，
接收实时推送，并通过邮件（Qute 模板渲染 HTML）把消息通知出去。

**非目标（本期不做）**：

- 不做入站 WebSocket 服务端（不对外暴露端点给浏览器连）。
- 不做按应用 / 优先级路由到不同收件人。
- 不做消息持久化、去重、聚合摘要。
- 不做 Gotify REST API 的历史消息补拉（`since` 参数）。

## 2. 术语与外部依赖

Gotify 的推送端点是 `GET /stream`（WebSocket 升级），鉴权使用 Gotify 的 token。
本设计假定推送的 JSON 结构为 Gotify 的 `MessageExternal`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 消息 ID |
| `appid` | number | 应用 ID |
| `title` | string | 标题 |
| `message` | string | 正文 |
| `priority` | number | 优先级 |
| `date` | string | RFC3339 时间戳 |
| `extras` | object | 扩展字段 |

> 说明：本机无法访问 `https://gotify.net/api-docs`（DNS 把外部域名解析到非公网地址，
> 抓取工具被拦），上述结构依据已知的 Gotify 接口约定。**鉴权方式与 JSON 结构需要在
> 实测中确认**，见 §10 风险。

## 3. 架构

### 3.1 组件

| 文件 | 职责 | 依赖 |
| --- | --- | --- |
| `ListenGotify` | WebSocket 端点。只处理四个回调：解析文本消息并转交 `GotifyMailNotifier`。不含连接与重连逻辑。 | `GotifyMailNotifier`、`ObjectMapper`、`GotifyConnection` |
| `GotifyConnection` | 连接生命周期：启动建连、断线后指数退避重连、`@PreDestroy` 优雅关闭。 | `WebSocketConnector<ListenGotify>`、`GotifyConfig` |
| `GotifyMessage` | `record`，映射推送 JSON。 | — |
| `GotifyMailNotifier` | 渲染 Qute 模板并调用 `Mailer` 发送。 | `GotifyConfig`、`Template`、`Mailer` |
| `GotifyConfig` | `@ConfigMapping(prefix = "gotify")` 配置映射。 | — |
| `templates/message.html` | 邮件 HTML 正文模板。 | — |

### 3.2 依赖关系

```
ListenGotify ──> GotifyConnection ──> WebSocketConnector<ListenGotify>
      │
      └──> GotifyMailNotifier ──> Mailer / Template
```

`WebSocketConnector` 是 Quarkus 提供的 `@Dependent` bean，其实现
（`WebSocketConnectorImpl`）通过字符串类名从端点映射表里查找端点，**不反向注入**
`ListenGotify`，因此上述环不构成 CDI 循环依赖。

`ListenGotify` 之所以需要引用 `GotifyConnection`，是因为端点回调是唯一能感知
「连接断了」的地方，需要据此触发重连。

### 3.3 线程模型

`@OnTextMessage` 标注 `@Blocking`（`io.smallrye.common.annotation.Blocking`）。
原因：回调内部要同步调用阻塞式的 `Mailer.send()`，不能占用 Vert.x event loop 线程。
已确认 `io.smallrye.common.annotation.Blocking` 被 WebSockets Next 的构建期处理器识别。

## 4. 连接与鉴权

### 4.1 约束

从 Quarkus WebSockets Next 3.39.4 的源码可以确认三个硬约束：

1. `@WebSocketClient` 注解只能声明 `path`，**不能**声明 base URI；base URI 必须由
   `WebSocketConnector.baseUri(...)` 或配置项 `<clientId>.base-uri` 提供。
2. `WebSocketConnector` **没有** query 参数 API。
3. `customizeOptions(...)` 中设置的 `WebSocketConnectOptions.setURI(...)` 会被框架在
   之后覆盖，因此无法借此注入 query string。

### 4.2 方案

利用框架的 path 参数替换机制：`WebSocketConnectorBase.replacePathParameters` 会把
path 里的 `{name}` 占位符替换为经过 URL 编码的值，且**不校验替换结果必须是纯路径**。
因此把 token 放进 path 声明的 query 部分：

```java
@WebSocketClient(path = "/stream?token={token}")
public class ListenGotify { ... }
```

建连时：

```java
connector.get()
    .baseUri(config.baseUrl())                  // http(s):// 或 ws(s):// 均可
    .pathParam("token", config.token())         // 框架负责 URL 编码
    .addHeader("X-Gotify-Key", config.token())  // 双保险
    .connectAndAwait();
```

最终请求 URI 为 `<base-url>/stream?token=<token>`。

- `base-url` 结尾的 `/` 会在代码里裁掉，否则会拼出 `//stream` 导致 404。
- 框架的 `baseUri` 接受 `http`/`ws`（非加密）与 `https`/`wss`（加密）四种 scheme，
  所以 `base-url` 可以直接复用 Gotify 的 REST 地址。
- 额外附带 `X-Gotify-Key` 请求头，因为 Gotify 的鉴权中间件同时接受该头和 `token`
  query 参数；多带一个头的成本为零，可提高兼容性。

### 4.3 备选

如果实测发现 query 参数方式不被 Gotify 接受，退化为：

```java
@WebSocketClient(path = "/stream")
... .baseUri(config.baseUrl()).addHeader("X-Gotify-Key", config.token())
```

改动量为 1 行。

## 5. 消息处理

`@OnTextMessage` 的签名使用 `String raw`，而不是直接声明 `GotifyMessage` 参数。

原因：若直接声明 POJO，框架会用内置 JSON codec 解码；**解码失败会进入 `@OnError`**，
从而被误判为连接故障、触发一次无谓的重连。使用 `String` 后自行解析：

```java
@OnTextMessage
@Blocking
void onTextMessage(String raw) {
    try {
        notifier.notify(objectMapper.readValue(raw, GotifyMessage.class));
    } catch (JsonProcessingException e) {
        LOG.warnf(e, "无法解析 Gotify 消息，已忽略: %s", raw);
    }
}
```

`ObjectMapper` 由 `quarkus-jackson` 提供。`GotifyMessage` 上标注
`@JsonIgnoreProperties(ignoreUnknown = true)`，对 Gotify 未来新增字段保持前向兼容。

`date` 字段映射为 `java.time.OffsetDateTime`（比 `Instant` 对带偏移量的 RFC3339
更宽容）。

## 6. 重连策略

- 使用单个 JDK `ScheduledExecutorService`，**不引入 `quarkus-scheduler`**。
- 退避公式：`delay = min(initialDelay * multiplier^attempt, maxDelay)`，
  默认 1s → 2s → 4s → … → 封顶 60s；连接成功后 `attempt` 归零。
- 同一时刻只允许存在一个待执行的重连任务：用 `AtomicReference<ScheduledFuture>`
  保存，排新任务前先 `cancel` 旧任务。
- 触发重连的时机：`connect()` 的 `Uni` 失败、`@OnClose`、`@OnError`。
- `@PreDestroy` 时先置 `shuttingDown` 标志，再关闭连接与线程池，避免关闭过程中
  再次触发重连。
- `gotify.token` 为空时：启动阶段记录 ERROR 并**不**发起连接。目的是避免配置错误
  导致无休止的失败重连刷日志。

## 7. 邮件通知

- 主题：`[Gotify] {title}`；`title` 为空时退化为正文前 30 个字符；正文也为空时用
  「新消息」。
- 正文：渲染 `templates/message.html` 得到 HTML，同时通过 `Mail.withHtml(...)`
  再调用 `.setText(...)` 附一份纯文本兜底。
- 收件人：`gotify.mail.to`，逗号分隔支持多个，空项过滤。
- 模板数据以显式命名传入（`title` / `message` / `priority` / `appid` / `date`），
  不依赖 Qute 对 record 组件的隐式属性解析，避免运行期解析失败。
- **发信失败只记录 ERROR 日志，不向上抛出**，避免因为一次 SMTP 抖动把 WebSocket
  连接打挂。

## 8. 配置

`application.properties`（本次同时移除 `quarkus-config-yaml` 依赖，见 §10）：

```properties
gotify.base-url=${GOTIFY_BASE_URL:http://localhost:8080}
gotify.token=${GOTIFY_TOKEN:}
gotify.mail.to=${GOTIFY_MAIL_TO:}
gotify.reconnect.initial-delay=1s
gotify.reconnect.max-delay=60s
gotify.reconnect.multiplier=2

quarkus.mailer.host=${SMTP_HOST:localhost}
quarkus.mailer.port=${SMTP_PORT:25}
quarkus.mailer.username=${SMTP_USERNAME:}
quarkus.mailer.password=${SMTP_PASSWORD:}
quarkus.mailer.start-tls=OPTIONAL
quarkus.mailer.from=${SMTP_FROM:gotify-sidebar@localhost}

%test.gotify.base-url=ws://localhost:8081
%test.gotify.token=test-token
%test.gotify.mail.to=test@example.com
%test.quarkus.mailer.mock=true
```

## 9. 测试策略

### 9.1 单元测试

退避公式：`GotifyConnection.nextDelay(attempt, initial, max, multiplier)` 作为
包级可见的静态方法，便于直接断言：

| attempt | 期望 |
| --- | --- |
| 0 | 1s |
| 1 | 2s |
| 5 | 32s |
| 10 | 60s（封顶） |

### 9.2 端到端测试

`@QuarkusTest`，在 `src/test/java` 下放一个**测试专用**的服务端端点：

```java
@WebSocket(path = "/stream")
public class FakeGotifyStreamEndpoint {
    @OnOpen
    void onOpen(WebSocketConnection connection) {
        connection.sendText(GOTIFY_JSON);
    }
}
```

把 `%test.gotify.base-url` 指向 `ws://localhost:8081`（Quarkus 测试 HTTP 端口），
让客户端连上被测应用自身。断言 `MockMailbox` 收到 1 封发往 `test@example.com` 的
邮件，且主题与正文包含推送的标题。

用 Awaitility（本地仓库已有 4.2.2）等待异步投递。

该测试不需要外部 Gotify，也不需要网络。

### 9.3 本机执行限制

本机 Maven 本地仓库缺少 `org.apache.maven.surefire:surefire-junit-platform`（3.5.6
与 3.2.5 均缺失），且外网被沙箱阻断，因此**本机无法执行 `mvn test`**。
测试代码会正常提交，需在联网环境（CI 或开发者本机）执行。

本机可执行的验证为：

1. `mvn -o compile` —— 全量源码编译通过。
2. 一次性运行时冒烟验证（在仓库外的临时副本中进行，不进入版本库）：用临时服务端
   端点模拟 Gotify 推送 + `quarkus.mailer.mock=true`，观察 MockMailbox 是否收到渲染
   后的 HTML 邮件，以覆盖 CDI 装配、WS 建连、JSON 解析、Qute 模板解析与发信链路。

## 10. 风险与权衡

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| `token` 放在 path 的 query 中不被 Gotify 接受 | 连接 401，功能不可用 | 同时带 `X-Gotify-Key` 头；若仍失败，改用 §4.3 备选（1 行改动） |
| Gotify 推送的 JSON 结构与本设计假设不符 | 解析失败，消息被丢弃 | `@JsonIgnoreProperties(ignoreUnknown = true)`；解析失败只记 warn 不重连；实测确认 |
| 本机无法执行 `mvn test` | 测试未在本机验证 | §9.3 的冒烟验证兜底；测试代码交由联网环境执行 |
| `quarkus-config-yaml` 依赖被移除 | 配置介质由 YAML 变为 properties | 该扩展的 deployment 产物在本机缺失导致无法构建；`application.yaml` 原本就是 0 字节空文件，且该改动等于回到 HEAD 状态；Quarkus 对两种介质完全等价 |
| 发信阻塞 event loop | 连接卡死 | `@OnTextMessage` 标注 `@Blocking` |

## 11. 变更清单

- 新增 `GotifyMessage`、`GotifyConnection`、`GotifyMailNotifier`、`GotifyConfig`。
- 改写 `ListenGotify`，补齐四个回调。
- 新增 `src/main/resources/templates/message.html`。
- 新增 `application.properties`（替换空的 `application.yaml`）。
- `pom.xml`：移除 `quarkus-config-yaml`，新增 `quarkus-jackson`。
- 新增测试：退避单元测试、`@QuarkusTest` 端到端测试。
