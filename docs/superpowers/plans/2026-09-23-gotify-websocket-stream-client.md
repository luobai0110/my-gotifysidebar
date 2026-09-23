# Gotify WebSocket 流监听端点 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把空的 `ListenGotify` 补成可用的 WebSocket 客户端，连接 Gotify 的 `/stream`，把推送消息经 Qute 模板渲染成 HTML 邮件发出。

**Architecture:** 端点类 `ListenGotify` 只负责四个 WS 回调；`GotifyConnection` 负责建连、指数退避重连与优雅关闭；`GotifyMailNotifier` 负责渲染与发信；`GotifyConfig` 集中配置。依赖方向 `ListenGotify → GotifyConnection → WebSocketConnector<ListenGotify>`，不构成 CDI 循环依赖。

**Tech Stack:** Quarkus 3.39.4、Java 21、quarkus-websockets-next、quarkus-qute、quarkus-mailer、quarkus-jackson、JUnit 5

**设计依据：** `docs/superpowers/specs/2026-09-23-gotify-websocket-stream-client-design.md`

---

## 本机执行说明（重要）

本机（`C:\Users\yyt\Documents\workplace\gotify-sidebar`）的构建环境有两个坑，**每个 mvn 命令都要带上这两个参数**：

1. 全局 `settings.xml`（`D:\maven-3.9.9\apache-maven-3.9.9\conf\settings.xml`）把 `localRepository` 指向空的 `D:\repository`，必须用 `-s` 覆盖到 `C:\Users\yyt\.m2\repository`。
2. 沙箱不允许写 `~/.m2`，Maven 的 `_remote.repositories` 校验会失败，必须用 `-Daether.enhancedLocalRepository.trackingFilename` 指向一个不存在的文件名来跳过校验。
3. 默认 `JAVA_HOME` 是 JDK 17，但 `pom.xml` 要求 `release 21`，必须切到 `D:\dev\java\jdk-25.0.2+10`。

固定的本机命令前缀：

```powershell
$env:JAVA_HOME='D:\dev\java\jdk-25.0.2+10'
$MVN_ARGS=@('-o','-B','-s','C:\Users\yyt\AppData\Local\Temp\dsh-mvn-settings.xml','-Daether.enhancedLocalRepository.trackingFilename=_remote.repositories.off')
```

（`C:\Users\yyt\AppData\Local\Temp\dsh-mvn-settings.xml` 内容只有一行 `<localRepository>C:\Users\yyt\.m2\repository</localRepository>`，已创建好。）

**本机无法执行 `mvn test`**：本地仓库缺 `org.apache.maven.surefire:surefire-junit-platform`（3.5.6、3.2.5 均缺失），测试只能在联网环境（CI 或开发者本机）运行。本机可执行 `compile` 与 `package`。

**本机的 package 命令一律用 `-Dmaven.test.skip=true` 而不是 `-DskipTests`**：后者仍会编译测试代码，而本机测试依赖不全，会在 test-compile 阶段失败。

**下文所有 `./mvnw xxx` 是本机之外的标准命令**；在本机执行时替换为上面前缀，例如：

```powershell
mvn @MVN_ARGS package -Dmaven.test.skip=true
```

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `pom.xml` | 移除 `quarkus-config-yaml`，新增 `quarkus-jackson` |
| `src/main/resources/application.properties` | 全部配置项（替换空的 `application.yaml`） |
| `src/main/java/com/github/luobai0110/GotifyMessage.java` | 推送消息的 record |
| `src/main/java/com/github/luobai0110/GotifyConfig.java` | `@ConfigMapping` 配置映射 |
| `src/main/java/com/github/luobai0110/GotifyConnection.java` | 建连、退避重连、优雅关闭 |
| `src/main/java/com/github/luobai0110/GotifyMailNotifier.java` | Qute 渲染 + 发信 |
| `src/main/java/com/github/luobai0110/ListenGotify.java` | WebSocket 端点四个回调（改写现有空壳） |
| `src/main/resources/templates/message.html` | 邮件 HTML 模板 |
| `src/test/java/com/github/luobai0110/AwaitSupport.java` | 测试用的极简轮询等待工具 |
| `src/test/java/com/github/luobai0110/GotifyMessageTest.java` | JSON 反序列化单元测试 |
| `src/test/java/com/github/luobai0110/GotifyConnectionTest.java` | 退避公式单元测试 |
| `src/test/java/com/github/luobai0110/GotifyMailNotifierTest.java` | `@QuarkusTest` 发信测试 |
| `src/test/java/com/github/luobai0110/GotifyStreamTest.java` | `@QuarkusTest` 端到端测试 |
| `src/test/java/com/github/luobai0110/FakeGotifyStreamEndpoint.java` | 测试专用假 Gotify 服务端 |

---

## Task 1: 恢复可构建状态（依赖与配置介质）

**背景：** 工作区里有一处未提交的改动把 `application.properties` 重命名成了 `application.yaml` 并加了 `quarkus-config-yaml` 依赖，但本机缺少 `quarkus-config-yaml-deployment` 产物导致无法构建。本任务把它回退为 properties 介质，并加上 JSON 解析所需的 jackson 扩展。

**Files:**
- Modify: `pom.xml`
- Delete: `src/main/resources/application.yaml`
- Create: `src/main/resources/application.properties`

- [ ] **Step 1: 修改 pom.xml —— 移除 quarkus-config-yaml**

把 `pom.xml` 中这段（第 55-59 行附近）整块删除：

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-config-yaml</artifactId>
        </dependency>
```

- [ ] **Step 2: 修改 pom.xml —— 新增 quarkus-jackson**

在 `quarkus-arc` 依赖之后、`quarkus-junit` 之前插入：

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jackson</artifactId>
        </dependency>
```

- [ ] **Step 3: 删除 application.yaml，创建 application.properties**

删除 `src/main/resources/application.yaml`，新建 `src/main/resources/application.properties`：

```properties
# ---- Gotify ----
# Gotify 服务地址，http(s):// 与 ws(s):// 都支持
gotify.base-url=${GOTIFY_BASE_URL:http://localhost:8080}
# Gotify 的 token（必填，留空则启动时记录错误并跳过连接）
gotify.token=${GOTIFY_TOKEN:}
# 邮件收件人，多个用逗号分隔
gotify.mail.to=${GOTIFY_MAIL_TO:}
# 断线重连的指数退避参数
gotify.reconnect.initial-delay=1s
gotify.reconnect.max-delay=60s
gotify.reconnect.multiplier=2

# ---- SMTP ----
quarkus.mailer.host=${SMTP_HOST:localhost}
quarkus.mailer.port=${SMTP_PORT:25}
quarkus.mailer.username=${SMTP_USERNAME:}
quarkus.mailer.password=${SMTP_PASSWORD:}
quarkus.mailer.start-tls=OPTIONAL
quarkus.mailer.from=${SMTP_FROM:gotify-sidebar@localhost}

# ---- 测试环境 ----
%test.gotify.base-url=ws://localhost:8081
%test.gotify.token=test-token
%test.gotify.mail.to=test@example.com
%test.quarkus.mailer.mock=true
```

- [ ] **Step 4: 验证构建通过**

Run（本机）：`mvn @MVN_ARGS clean package -Dmaven.test.skip=true`
Expected: `BUILD SUCCESS`

（联网环境：`./mvnw clean package -DskipTests`）

- [ ] **Step 5: 提交**

```bash
git add -A pom.xml src/main/resources
git commit -m "chore(构建): 配置介质改用 properties 并引入 quarkus-jackson" -m "本机缺少 quarkus-config-yaml 的 deployment 产物导致无法构建；application.yaml 原本即为空文件，改用 application.properties 后功能完全等价。同时引入 quarkus-jackson 以提供 ObjectMapper。"
```

---

## Task 2: GotifyMessage 记录类型

**Files:**
- Create: `src/main/java/com/github/luobai0110/GotifyMessage.java`
- Test: `src/test/java/com/github/luobai0110/GotifyMessageTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/github/luobai0110/GotifyMessageTest.java`：

```java
package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class GotifyMessageTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void 解析完整的推送消息() throws Exception {
        String json = """
                {"id":25,"appid":5,"title":"my title","message":"my message","priority":2,
                 "date":"2018-02-27T19:36:10.5045044+01:00",
                 "extras":{"client::display":{"contentType":"text/markdown"}}}
                """;

        GotifyMessage message = mapper.readValue(json, GotifyMessage.class);

        assertEquals(25L, message.id());
        assertEquals(5L, message.appid());
        assertEquals("my title", message.title());
        assertEquals("my message", message.message());
        assertEquals(2, message.priority());
        assertEquals(OffsetDateTime.parse("2018-02-27T19:36:10.5045044+01:00"), message.date());
        assertEquals("text/markdown",
                ((Map<?, ?>) message.extras().get("client::display")).get("contentType"));
    }

    @Test
    void 忽略未知字段并允许缺失可选字段() throws Exception {
        String json = """
                {"id":1,"appid":2,"message":"only body","unknownField":"ignored"}
                """;

        GotifyMessage message = mapper.readValue(json, GotifyMessage.class);

        assertEquals(1L, message.id());
        assertNull(message.title());
        assertNull(message.date());
        assertNull(message.extras());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=GotifyMessageTest`
Expected: 编译失败，报 `找不到符号: 类 GotifyMessage`

- [ ] **Step 3: 写最小实现**

创建 `src/main/java/com/github/luobai0110/GotifyMessage.java`：

```java
package com.github.luobai0110;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Gotify 推送端点（{@code /stream}）下发的消息。
 * <p>
 * 字段与 Gotify 的 {@code MessageExternal} 对应。标注 {@link JsonIgnoreProperties} 是为了在 Gotify 未来新增字段时
 * 保持前向兼容。
 *
 * @param id       消息 ID
 * @param appid    应用 ID
 * @param title    标题，可能为 {@code null}
 * @param message  正文，可能为 {@code null}
 * @param priority 优先级
 * @param date     推送时间，可能为 {@code null}
 * @param extras   扩展字段，可能为 {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GotifyMessage(
        long id,
        long appid,
        String title,
        String message,
        int priority,
        OffsetDateTime date,
        Map<String, Object> extras) {
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=GotifyMessageTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/luobai0110/GotifyMessage.java src/test/java/com/github/luobai0110/GotifyMessageTest.java
git commit -m "feat(Gotify): 添加推送消息的强类型映射" -m "映射 Gotify MessageExternal 的全部字段，未知字段忽略以保持前向兼容。"
```

---

## Task 3: GotifyConfig 配置映射

**Files:**
- Create: `src/main/java/com/github/luobai0110/GotifyConfig.java`

- [ ] **Step 1: 写实现**

（配置映射是纯声明，无可单测的逻辑；它的绑定正确性由 Task 5 与 Task 6 的 `@QuarkusTest` 覆盖 —— 若映射有误，那两个测试在应用启动阶段就会失败。）

创建 `src/main/java/com/github/luobai0110/GotifyConfig.java`：

```java
package com.github.luobai0110;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Gotify 相关的配置，前缀 {@code gotify}。
 */
@ConfigMapping(prefix = "gotify")
public interface GotifyConfig {

    /**
     * Gotify 服务地址。支持 {@code http(s)://} 与 {@code ws(s)://} 四种 scheme。
     */
    @WithDefault("http://localhost:8080")
    String baseUrl();

    /**
     * Gotify 的 token。留空表示未配置。
     */
    Optional<String> token();

    Mail mail();

    Reconnect reconnect();

    interface Mail {

        /**
         * 收件人列表，多个用逗号分隔。
         */
        Optional<List<String>> to();
    }

    interface Reconnect {

        /**
         * 首次重连延迟。
         */
        @WithDefault("1s")
        Duration initialDelay();

        /**
         * 重连延迟上限。
         */
        @WithDefault("60s")
        Duration maxDelay();

        /**
         * 每次重连延迟的倍数。
         */
        @WithDefault("2")
        int multiplier();
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run（本机）：`mvn @MVN_ARGS compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/luobai0110/GotifyConfig.java
git commit -m "feat(配置): 添加 GotifyConfig 配置映射" -m "集中声明 gotify.base-url、gotify.token、gotify.mail.to 与重连退避参数，支持环境变量覆盖。"
```

---

## Task 4: 重连退避计算

**Files:**
- Create: `src/main/java/com/github/luobai0110/GotifyConnection.java`
- Test: `src/test/java/com/github/luobai0110/GotifyConnectionTest.java`

（本任务只建出 `GotifyConnection` 的退避算法骨架，建连与重连逻辑在 Task 6 补齐。）

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/github/luobai0110/GotifyConnectionTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=GotifyConnectionTest`
Expected: 编译失败，报 `找不到符号: 类 GotifyConnection`

- [ ] **Step 3: 写最小实现**

创建 `src/main/java/com/github/luobai0110/GotifyConnection.java`：

```java
package com.github.luobai0110;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 负责连接 Gotify 推送流：启动建连、断线后的指数退避重连、关闭时的优雅退出。
 */
@ApplicationScoped
public class GotifyConnection {

    /**
     * 计算第 {@code attempt} 次重连的延迟：{@code min(initialDelay * multiplier^attempt, maxDelay)}。
     *
     * @param attempt      重连次数，从 0 开始
     * @param initialDelay 首次重连延迟
     * @param maxDelay     延迟上限
     * @param multiplier   延迟倍数
     * @return 本次重连应等待的时长
     */
    static Duration nextDelay(int attempt, Duration initialDelay, Duration maxDelay, int multiplier) {
        double raw = initialDelay.toMillis() * Math.pow(multiplier, attempt);
        // Math.pow 在大指数下会得到 Infinity，min 之后自然收敛到 maxDelay
        return Duration.ofMillis((long) Math.min(raw, (double) maxDelay.toMillis()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=GotifyConnectionTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/luobai0110/GotifyConnection.java src/test/java/com/github/luobai0110/GotifyConnectionTest.java
git commit -m "feat(连接): 添加重连指数退避计算" -m "退避公式为 min(initialDelay * multiplier^attempt, maxDelay)，对极大重试次数做了溢出保护。"
```

---

## Task 5: 邮件模板与 GotifyMailNotifier

**Files:**
- Create: `src/main/resources/templates/message.html`
- Create: `src/main/java/com/github/luobai0110/GotifyMailNotifier.java`
- Create: `src/test/java/com/github/luobai0110/FakeGotifyStreamEndpoint.java`
- Test: `src/test/java/com/github/luobai0110/GotifyMailNotifierTest.java`

（`FakeGotifyStreamEndpoint` 在本任务建出但暂不被触发 —— Task 6 的客户端才会连它。放在这里是为了让 Task 6 的端到端测试一次成型。）

- [ ] **Step 1: 创建邮件模板**

创建 `src/main/resources/templates/message.html`：

```html
<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8">
    <title>{title}</title>
</head>
<body style="margin:0;padding:24px;background:#f5f5f5;font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;color:#24292f;">
<div style="max-width:640px;margin:0 auto;background:#ffffff;border:1px solid #d0d7de;border-radius:8px;overflow:hidden;">
    <div style="padding:16px 24px;background:#24292f;color:#ffffff;font-size:18px;font-weight:600;">
        {title}
    </div>
    <div style="padding:24px;font-size:15px;line-height:1.6;white-space:pre-wrap;">{message}</div>
    <div style="padding:12px 24px;border-top:1px solid #d0d7de;background:#f6f8fa;color:#57606a;font-size:12px;">
        优先级 {priority} &middot; 应用 #{appid} &middot; {date}
    </div>
</div>
</body>
</html>
```

- [ ] **Step 2: 写失败的测试**

先建轮询等待工具。**不要用 Awaitility** —— 本机实测它并不在 `quarkus-junit` 的传递依赖里，引入它需要额外加测试依赖，代价大于收益。

创建 `src/test/java/com/github/luobai0110/AwaitSupport.java`：

```java
package com.github.luobai0110;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 极简的轮询等待工具，用于断言异步投递的结果。
 * <p>
 * 类名刻意不以 {@code Test} 开头，避免被 Surefire 的 {@code **&#47;Test*.java} 默认包含规则当成测试类。
 */
final class AwaitSupport {

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private AwaitSupport() {
    }

    static void until(String description, Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待「" + description + "」时被中断", e);
            }
        }
        throw new AssertionError("等待超时：" + description);
    }
}
```

创建 `src/test/java/com/github/luobai0110/FakeGotifyStreamEndpoint.java`：

```java
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
```

创建 `src/test/java/com/github/luobai0110/GotifyMailNotifierTest.java`：

```java
package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GotifyMailNotifierTest {

    private static final String RECIPIENT = "test@example.com";
    private static final String SUBJECT = "[Gotify] 单元测试消息";

    @Inject
    GotifyMailNotifier notifier;

    @Inject
    MockMailbox mailbox;

    @Test
    void 渲染模板并发送邮件() {
        GotifyMessage message = new GotifyMessage(7L, 3L, "单元测试消息", "这是正文 <b>不应被当作 HTML</b>",
                5, OffsetDateTime.parse("2026-09-23T12:00:00+08:00"), null);

        notifier.notify(message);

        AwaitSupport.until("邮件投递到 MockMailbox", Duration.ofSeconds(10), () -> findSent().size() == 1);

        Mail mail = findSent().get(0);
        assertTrue(mail.getHtml().contains("单元测试消息"), "HTML 正文应包含标题");
        assertTrue(mail.getHtml().contains("这是正文"), "HTML 正文应包含消息正文");
        assertTrue(mail.getHtml().contains("&lt;b&gt;"), "消息正文必须被 HTML 转义");
        assertTrue(mail.getText().contains("单元测试消息"), "纯文本兜底应包含标题");
    }

    private List<Mail> findSent() {
        return mailbox.getMailsSentTo(RECIPIENT).stream()
                .filter(mail -> SUBJECT.equals(mail.getSubject()))
                .toList();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./mvnw test -Dtest=GotifyMailNotifierTest`
Expected: 编译失败，报 `找不到符号: 类 GotifyMailNotifier`

- [ ] **Step 4: 写最小实现**

创建 `src/main/java/com/github/luobai0110/GotifyMailNotifier.java`：

```java
package com.github.luobai0110;

import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 把 Gotify 推送的消息渲染成 HTML 邮件并发出。
 */
@ApplicationScoped
public class GotifyMailNotifier {

    private static final Logger LOG = Logger.getLogger(GotifyMailNotifier.class);

    /**
     * 标题缺失时，用正文前这么多个字符充当标题。
     */
    private static final int TITLE_FALLBACK_LENGTH = 30;

    @Inject
    Mailer mailer;

    @Inject
    GotifyConfig config;

    @Inject
    @Location("message.html")
    Template messageTemplate;

    /**
     * 发送通知邮件。任何失败都只记录日志，不向调用方抛出 —— 不能让一次 SMTP 抖动影响 WebSocket 连接。
     *
     * @param message 收到的 Gotify 消息
     */
    void notify(GotifyMessage message) {
        List<String> recipients = recipients();
        if (recipients.isEmpty()) {
            LOG.warnf("收到 Gotify 消息但未配置收件人（gotify.mail.to），已忽略：%s", displayTitle(message));
            return;
        }

        String subject = subjectFor(message);
        try {
            String html = messageTemplate
                    .data("title", displayTitle(message))
                    .data("message", nullToEmpty(message.message()))
                    .data("priority", message.priority())
                    .data("appid", message.appid())
                    .data("date", message.date() == null ? "" : message.date().toString())
                    .render();

            Mail mail = new Mail()
                    .setTo(recipients)
                    .setSubject(subject)
                    .setHtml(html)
                    .setText(plainText(message));

            mailer.send(mail);
            LOG.infof("已发送 Gotify 通知邮件「%s」给 %s", subject, recipients);
        } catch (RuntimeException e) {
            LOG.errorf(e, "发送 Gotify 通知邮件「%s」失败", subject);
        }
    }

    /**
     * @return 过滤掉空项后的收件人列表
     */
    List<String> recipients() {
        return config.mail().to().orElse(List.of()).stream()
                .filter(recipient -> recipient != null && !recipient.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * @return 邮件主题
     */
    static String subjectFor(GotifyMessage message) {
        return "[Gotify] " + displayTitle(message);
    }

    /**
     * @return 用于展示的标题；标题为空时退化为正文前 30 个字符，正文也为空时返回「新消息」
     */
    static String displayTitle(GotifyMessage message) {
        if (message.title() != null && !message.title().isBlank()) {
            return message.title().strip();
        }
        String body = nullToEmpty(message.message()).strip();
        if (body.isEmpty()) {
            return "新消息";
        }
        return body.length() <= TITLE_FALLBACK_LENGTH
                ? body
                : body.substring(0, TITLE_FALLBACK_LENGTH) + "…";
    }

    /**
     * @return 纯文本兜底正文
     */
    static String plainText(GotifyMessage message) {
        return displayTitle(message) + System.lineSeparator()
                + System.lineSeparator()
                + nullToEmpty(message.message());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw test -Dtest=GotifyMailNotifierTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/luobai0110/GotifyMailNotifier.java src/main/resources/templates/message.html src/test/java/com/github/luobai0110/AwaitSupport.java src/test/java/com/github/luobai0110/GotifyMailNotifierTest.java src/test/java/com/github/luobai0110/FakeGotifyStreamEndpoint.java
git commit -m "feat(通知): 添加基于 Qute 模板的邮件通知" -m "渲染 message.html 为 HTML 正文并附带纯文本兜底；标题缺失时退化为正文前 30 个字符；发信失败只记日志不抛出。"
```

---

## Task 6: WebSocket 端点与连接管理

**Files:**
- Modify: `src/main/java/com/github/luobai0110/ListenGotify.java`（整文件替换）
- Modify: `src/main/java/com/github/luobai0110/GotifyConnection.java`（补齐建连与重连）
- Test: `src/test/java/com/github/luobai0110/GotifyStreamTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/github/luobai0110/GotifyStreamTest.java`：

```java
package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * 端到端：应用启动时客户端连上测试内的假 Gotify 服务端，收到推送后发出邮件。
 */
@QuarkusTest
class GotifyStreamTest {

    private static final String RECIPIENT = "test@example.com";
    private static final String SUBJECT = "[Gotify] " + FakeGotifyStreamEndpoint.TITLE;

    @Inject
    MockMailbox mailbox;

    @Test
    void 把推送的消息转成邮件发出() {
        AwaitSupport.until("收到推送生成的邮件", Duration.ofSeconds(30), () -> findSent() != null);

        Mail mail = findSent();
        assertNotNull(mail, "应收到主题为「" + SUBJECT + "」的邮件");
        assertTrue(mail.getHtml().contains(FakeGotifyStreamEndpoint.TITLE), "HTML 正文应包含标题");
        assertTrue(mail.getHtml().contains(FakeGotifyStreamEndpoint.BODY), "HTML 正文应包含正文");
    }

    private Mail findSent() {
        return mailbox.getMailsSentTo(RECIPIENT).stream()
                .filter(m -> SUBJECT.equals(m.getSubject()))
                .findFirst()
                .orElse(null);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=GotifyStreamTest`
Expected: FAIL，报 `AssertionError: 等待超时：收到推送生成的邮件` —— 此时还没有客户端去连假服务端

- [ ] **Step 3: 改写 ListenGotify**

整文件替换 `src/main/java/com/github/luobai0110/ListenGotify.java`：

```java
package com.github.luobai0110;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;

/**
 * Gotify 推送端点（{@code /stream}）的 WebSocket 客户端。
 * <p>
 * token 通过 path 里的 {@code {token}} 占位符注入 —— Quarkus 的 {@code WebSocketConnector} 没有 query 参数 API，
 * 而 path 中的占位符会被框架替换并做 URL 编码，替换结果允许包含查询串。
 * <p>
 * 本类只处理回调；建连与重连由 {@link GotifyConnection} 负责。
 */
@WebSocketClient(path = "/stream?token={token}")
public class ListenGotify {

    private static final Logger LOG = Logger.getLogger(ListenGotify.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GotifyMailNotifier notifier;

    @Inject
    GotifyConnection gotifyConnection;

    @OnOpen
    void onOpen(WebSocketClientConnection connection) {
        LOG.infof("Gotify 推送流已连接，连接 ID：%s", connection.id());
    }

    /**
     * 参数声明为 {@code String} 而不是 {@link GotifyMessage}：若声明为 POJO，框架会用内置 JSON codec 解码，
     * 而解码失败会走 {@link #onError}，被误判成连接故障触发无谓重连。
     */
    @OnTextMessage
    @Blocking
    void onTextMessage(String raw) {
        try {
            notifier.notify(objectMapper.readValue(raw, GotifyMessage.class));
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "无法解析 Gotify 推送的消息，已忽略：%s", raw);
        }
    }

    @OnClose
    void onClose(CloseReason reason) {
        LOG.warnf("Gotify 推送流已断开：%s", reason);
        gotifyConnection.scheduleReconnect("连接关闭 " + reason);
    }

    @OnError
    void onError(Throwable error) {
        LOG.errorf(error, "Gotify 推送流出错");
        gotifyConnection.scheduleReconnect("连接出错");
    }
}
```

- [ ] **Step 4: 补齐 GotifyConnection**

整文件替换 `src/main/java/com/github/luobai0110/GotifyConnection.java`：

```java
package com.github.luobai0110;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * 负责连接 Gotify 推送流：启动建连、断线后的指数退避重连、关闭时的优雅退出。
 */
@ApplicationScoped
public class GotifyConnection {

    private static final Logger LOG = Logger.getLogger(GotifyConnection.class);

    @Inject
    Instance<WebSocketConnector<ListenGotify>> connector;

    @Inject
    GotifyConfig config;

    /**
     * 重连调度器。不引入 quarkus-scheduler，单个守护线程足够。
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gotify-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger attempt = new AtomicInteger();

    private final AtomicReference<ScheduledFuture<?>> pendingReconnect = new AtomicReference<>();

    private volatile boolean shuttingDown;

    private volatile WebSocketClientConnection current;

    void onStart(@Observes StartupEvent event) {
        if (token().isEmpty()) {
            LOG.error("未配置 gotify.token，跳过连接 Gotify 推送流");
            return;
        }
        connect();
    }

    /**
     * 建立一次连接。失败时安排重连。
     */
    void connect() {
        if (shuttingDown) {
            return;
        }
        String baseUrl = stripTrailingSlashes(config.baseUrl());
        try {
            WebSocketClientConnection connection = connector.get()
                    .baseUri(URI.create(baseUrl))
                    .pathParam("token", token())
                    .addHeader("X-Gotify-Key", token())
                    .connectAndAwait();
            current = connection;
            attempt.set(0);
            LOG.infof("Gotify 推送流连接已建立：%s", connection.id());
        } catch (RuntimeException e) {
            LOG.errorf(e, "连接 Gotify 推送流失败：%s", baseUrl);
            scheduleReconnect("连接失败");
        }
    }

    /**
     * 安排一次重连。同一时刻只会有一个待执行的重连任务。
     *
     * @param reason 触发原因，仅用于日志
     */
    void scheduleReconnect(String reason) {
        if (shuttingDown) {
            return;
        }
        GotifyConfig.Reconnect reconnect = config.reconnect();
        Duration delay = nextDelay(attempt.getAndIncrement(), reconnect.initialDelay(), reconnect.maxDelay(),
                reconnect.multiplier());

        ScheduledFuture<?> task = scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pendingReconnect.getAndSet(task);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
        LOG.infof("Gotify 推送流将在 %d 毫秒后重连（原因：%s）", delay.toMillis(), reason);
    }

    /**
     * 计算第 {@code attempt} 次重连的延迟：{@code min(initialDelay * multiplier^attempt, maxDelay)}。
     *
     * @param attempt      重连次数，从 0 开始
     * @param initialDelay 首次重连延迟
     * @param maxDelay     延迟上限
     * @param multiplier   延迟倍数
     * @return 本次重连应等待的时长
     */
    static Duration nextDelay(int attempt, Duration initialDelay, Duration maxDelay, int multiplier) {
        double raw = initialDelay.toMillis() * Math.pow(multiplier, attempt);
        // Math.pow 在大指数下会得到 Infinity，min 之后自然收敛到 maxDelay
        return Duration.ofMillis((long) Math.min(raw, (double) maxDelay.toMillis()));
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;

        ScheduledFuture<?> task = pendingReconnect.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();

        WebSocketClientConnection connection = current;
        if (connection != null) {
            try {
                connection.closeAndAwait();
            } catch (RuntimeException e) {
                LOG.debugf(e, "关闭 Gotify 推送流时出错");
            }
        }
    }

    private String token() {
        return config.token().map(String::trim).orElse("");
    }

    private static String stripTrailingSlashes(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw test -Dtest=GotifyStreamTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: 回归全部测试**

Run: `./mvnw test`
Expected: `Tests run: 8, Failures: 0, Errors: 0`（2 个 `GotifyMessageTest` + 4 个 `GotifyConnectionTest` + 1 个 `GotifyMailNotifierTest` + 1 个 `GotifyStreamTest`）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/github/luobai0110/ListenGotify.java src/main/java/com/github/luobai0110/GotifyConnection.java src/test/java/com/github/luobai0110/GotifyStreamTest.java
git commit -m "feat(Gotify): 实现推送流客户端与指数退避重连" -m "ListenGotify 补齐四个回调并通过 path 占位符注入 token；GotifyConnection 负责启动建连、单任务指数退避重连与优雅关闭；JSON 解析失败只记警告，不触发重连。"
```

---

## Task 7: 文档与本机冒烟验证

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README**

在 `README.md` 的 `## Related Guides` 之前插入：

```markdown
## 配置

应用通过 WebSocket 连接 Gotify 的 `/stream` 端点，把推送的消息渲染成 HTML 邮件发出。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GOTIFY_BASE_URL` | `http://localhost:8080` | Gotify 服务地址，支持 `http(s)://` 与 `ws(s)://` |
| `GOTIFY_TOKEN` | 空 | Gotify token，必填；留空则启动时记录错误并跳过连接 |
| `GOTIFY_MAIL_TO` | 空 | 收件人，多个用逗号分隔 |
| `SMTP_HOST` | `localhost` | SMTP 服务器 |
| `SMTP_PORT` | `25` | SMTP 端口 |
| `SMTP_USERNAME` | 空 | SMTP 用户名 |
| `SMTP_PASSWORD` | 空 | SMTP 密码 |
| `SMTP_FROM` | `gotify-sidebar@localhost` | 发件人 |

断线后按 `1s → 2s → 4s …` 指数退避重连，上限 60 秒，连接成功后计数归零。
```

- [ ] **Step 2: 本机验证 —— 编译与打包（含 CDI 装配校验）**

Run（本机）：

```powershell
$env:JAVA_HOME='D:\dev\java\jdk-25.0.2+10'
mvn -o -B -s C:\Users\yyt\AppData\Local\Temp\dsh-mvn-settings.xml "-Daether.enhancedLocalRepository.trackingFilename=_remote.repositories.off" clean package -Dmaven.test.skip=true
```

Expected: `BUILD SUCCESS`。`package` 会执行 Quarkus 的增强阶段，从而校验 CDI 依赖图（循环依赖、`WebSocketConnector` 注入点类型等）与 Qute 模板的构建期解析。

- [ ] **Step 3: 本机验证 —— 运行时冒烟（仓库外临时副本）**

本机跑不了 `mvn test`（缺 surefire provider），因此用临时副本做一次真实运行验证。**所有操作在 `$env:TEMP\gs-smoke` 下进行，不要改动本仓库。**

```powershell
$smoke="$env:TEMP\gs-smoke"
Remove-Item $smoke -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $smoke | Out-Null
Copy-Item "C:\Users\yyt\Documents\workplace\gotify-sidebar\pom.xml" $smoke
Copy-Item "C:\Users\yyt\Documents\workplace\gotify-sidebar\src" $smoke -Recurse
Set-Location $smoke
```

在副本里：把 `FakeGotifyStreamEndpoint.java` 从 `src/test/java` 复制到 `src/main/java`，并在 `src/main/resources/application.properties` 末尾追加：

```properties
quarkus.mailer.mock=true
gotify.token=smoke-token
gotify.mail.to=smoke@example.com
gotify.base-url=ws://localhost:8080
```

再新建 `src/main/java/com/github/luobai0110/SmokeVerifier.java`：

```java
package com.github.luobai0110;

import java.util.concurrent.TimeUnit;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class SmokeVerifier {

    @Inject
    MockMailbox mailbox;

    void onStart(@Observes StartupEvent event) {
        new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("SMOKE-TOTAL=" + mailbox.getTotalMessagesSent());
            mailbox.getMailsSentTo("smoke@example.com")
                    .forEach(mail -> System.out.println("SMOKE-SUBJECT=" + mail.getSubject()
                            + " HTML-HAS-TITLE=" + mail.getHtml().contains(FakeGotifyStreamEndpoint.TITLE)
                            + " HTML-HAS-BODY=" + mail.getHtml().contains(FakeGotifyStreamEndpoint.BODY)));
            Quarkus.asyncExit(0);
        }, "smoke-verifier").start();
    }
}
```

运行：

```powershell
mvn -o -B -s C:\Users\yyt\AppData\Local\Temp\dsh-mvn-settings.xml "-Daether.enhancedLocalRepository.trackingFilename=_remote.repositories.off" package -Dmaven.test.skip=true
java -jar target\quarkus-app\quarkus-run.jar
```

Expected 输出：

```
SMOKE-TOTAL=1
SMOKE-SUBJECT=[Gotify] 构建失败 HTML-HAS-TITLE=true HTML-HAS-BODY=true
```

只要 `SMOKE-TOTAL` 不是 0，就说明这一整条链路是通的：启动建连 → WebSocket 握手带上了 token → 收到推送 → JSON 反序列化 → Qute 渲染 → 邮件发出。若为空，从进程日志里找 `连接 Gotify 推送流失败` 或 `无法解析 Gotify 推送的消息` 两条错误，即可定位是哪一环断了。

跑完删除临时副本：

```powershell
Set-Location "C:\Users\yyt\Documents\workplace\gotify-sidebar"
Remove-Item $smoke -Recurse -Force
```

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs(README): 补充 Gotify 与 SMTP 配置说明" -m "列出全部环境变量及其默认值，并说明断线后的指数退避重连策略。"
```

---

## 完成标准

1. `./mvnw clean package -DskipTests` 在联网环境 BUILD SUCCESS（本机用「本机执行说明」中的命令）。
2. `./mvnw test` 全部通过（联网环境，共 8 个用例）。
3. 本机 `package` 通过 —— 证明 CDI 装配与 Qute 构建期解析无误。
4. 本机冒烟验证输出 `SMOKE-TOTAL=1` —— 证明运行时链路完整。
5. 工作区中不再有 `application.yaml`，配置集中在 `application.properties`。

## 已知未覆盖项

- Gotify 的 `/stream` 是否接受 query 参数形式的 token，只能在有真实 Gotify 的环境验证。若失败，按设计文档 §4.3 改为仅用 `X-Gotify-Key` 头（`ListenGotify` 的 `path` 改回 `/stream`，去掉 `pathParam` 调用）。
- 本机的 `mvn test` 无法执行，测试结论需以联网环境的运行结果为准。
- 模板中 `{message}` 依赖 Quarkus Qute 对 `.html` 模板的默认 HTML 转义。`GotifyMailNotifierTest` 里的 `&lt;b&gt;` 断言就是用来锁定这个行为的；若该断言失败，说明转义未生效，属于需要正视的安全问题，不要直接删断言。
