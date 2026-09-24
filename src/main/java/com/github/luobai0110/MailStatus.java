package com.github.luobai0110;

/**
 * 一条消息的邮件转发状态。
 * <p>
 * 所有消息都会入库，但只有优先级超过阈值的才需要发信，因此必须能区分
 * 「本就不该发」与「该发但失败了」。
 */
public enum MailStatus {

    /** 优先级未达阈值，或收件人未配置，本就不该发信。 */
    NOT_APPLICABLE,

    /** 达到阈值，待发信（入库时的初始态）。 */
    PENDING,

    /** 发送成功。 */
    SENT,

    /** 发送失败，原因记于 {@code mail_error}。 */
    FAILED
}
