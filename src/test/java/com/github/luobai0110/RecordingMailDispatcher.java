package com.github.luobai0110;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * 测试替身：记录发信调用而不真的投递。
 * <p>
 * 用 {@link Alternative} + {@link Priority} 覆盖生产实现，从而能断言
 * 「优先级 7 触发了发送、优先级 6 没有」。
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingMailDispatcher extends MailDispatcher {

    public final List<String> subjects = new ArrayList<>();

    /** 置为非 null 时，下一次发信将抛出该异常，用于验证失败路径。 */
    public RuntimeException failWith = null;

    @Override
    public void send(List<String> recipients, String subject, String html, String text) {
        if (failWith != null) {
            throw failWith;
        }
        subjects.add(subject);
    }
}
