package com.github.luobai0110;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * 一条 Gotify 消息的持久化记录。
 * <p>
 * 主键<b>直接使用 Gotify 的消息 id</b>，而非自增列。去重语义就是「Gotify 消息 id 唯一」，
 * 用主键让数据库在结构层面保证幂等，避免应用层「先查后插」的竞态窗口。
 */
@Entity
@Table(name = "gotify_message")
public class GotifyMessageEntity extends PanacheEntityBase {

    /** Gotify 的消息 id，直接作主键。 */
    @Id
    public Long id;

    @Column(name = "app_id")
    public long appId;

    @Column(name = "title")
    public String title;

    @Lob
    @Column(name = "message")
    public String message;

    @Column(name = "priority")
    public int priority;

    @Column(name = "pushed_at")
    public OffsetDateTime pushedAt;

    /** extras 序列化后的 JSON 字符串；extras 是任意键值对，为其建关联表属过度设计。 */
    @Lob
    @Column(name = "extras_json")
    public String extrasJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "mail_status", nullable = false)
    public MailStatus mailStatus;

    @Lob
    @Column(name = "mail_error")
    public String mailError;

    @Column(name = "received_at", nullable = false)
    public OffsetDateTime receivedAt;
}
