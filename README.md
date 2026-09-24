# gotify-sidebar

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Configuration

### Gotify token 的两种类型

Gotify 区分两种用途不同的 token，**不能混用**：

| Token 类型 | 用途 | 在 Gotify UI 的位置 | 前缀 |
| --- | --- | --- | --- |
| **Client token** | 订阅 `/stream` 接收消息 | Clients 页 | 通常 `C` 开头 |
| **Application token** | `POST /message` 发送消息 | Apps 页 | 通常 `A` 开头 |

本项目订阅推送流只接收、不发送，因此必须使用 **Client token**。若误用 Application token，
Gotify 会在 WebSocket 握手阶段直接返回 `401`，日志中表现为：

```
WARN  ... 第 1 次连接 Gotify 失败：UpgradeRejectedException: WebSocket upgrade failure: 401
```

### 环境变量

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `GOTIFY_BASE_URL` | Gotify 服务地址，`http(s)://` 与 `ws(s)://` 均可 | `http://localhost:8080` |
| `GOTIFY_CLIENT_TOKEN` | **Client token**，用于订阅 `/stream`（必填） | 空 |
| `GOTIFY_APP_TOKEN` | Application token，用于发送消息（当前代码未使用） | 空 |
| `GOTIFY_MAIL_TO` | 邮件收件人，多个用逗号分隔 | 空 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_FROM` | SMTP 配置 | 见 `application.yaml` |

> **注意：** 仓库根目录的 `.env` 文件**不会**被 Quarkus 自动读取，它只在 Docker / docker-compose
> 场景下生效。本地用 `./mvnw quarkus:dev` 运行时需要手动导出环境变量：
>
> ```powershell
> $env:GOTIFY_CLIENT_TOKEN='C你的客户端token'
> $env:GOTIFY_BASE_URL='http://localhost:8080'
> ./mvnw quarkus:dev
> ```
>
> ```bash
> GOTIFY_CLIENT_TOKEN=C你的客户端token GOTIFY_BASE_URL=http://localhost:8080 ./mvnw quarkus:dev
> ```

### 重连行为

应用启动时会自动建立 WebSocket 连接（`GotifyConnection#onStart`）。建连失败按指数退避重试：

- 首次重试延迟 1s，倍数 2，即 1s → 2s → 4s
- 单次延迟上限 60s
- 最多重试 3 次（不含首次连接），耗尽后记录 ERROR 并停止

相关配置项位于 `application.yaml` 的 `gotify.reconnect.*`。

### 排查连接问题

启动日志中可确认连接状态：

```
INFO  ... Gotify 推送流已建立，连接 ID：xxx          # 连接成功
INFO  ... 收到 Gotify 消息：{...}                    # 收到推送
WARN  ... Gotify 推送流已关闭，原因：xxx             # 连接被关闭
ERROR ... 连接 Gotify 推送流失败，已重试 3 次后放弃   # 彻底失败
```

若持续 401，先确认用的是 Client token 而非 Application token。

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/gotify-sidebar-1.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- WebSockets Next ([guide](https://quarkus.io/guides/websockets-next-reference)): Implementation of the WebSocket API
  with enhanced efficiency and usability
- Qute ([guide](https://quarkus.io/guides/qute)): Offer templating support for web, email, etc in a build time,
  type-safe way
- Mailer ([guide](https://quarkus.io/guides/mailer)): Send emails
