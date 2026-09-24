package com.github.luobai0110;

/**
 * 优先级到视觉呈现的映射。
 * <p>
 * 抽成纯函数是为了让配色规则可单测，同时避免把分支逻辑写进 Qute 模板 ——
 * 模板只负责展示，不负责判断。
 *
 * @param color 该档位的主色（十六进制）
 * @param label 该档位的中文标签
 */
public record PriorityPalette(String color, String label) {

    /** 低：灰蓝。 */
    private static final PriorityPalette LOW = new PriorityPalette("#57606a", "低");

    /** 中：琥珀。 */
    private static final PriorityPalette MEDIUM = new PriorityPalette("#bf8700", "中");

    /** 高：橙红。 */
    private static final PriorityPalette HIGH = new PriorityPalette("#d1550d", "高");

    /** 紧急：正红。 */
    private static final PriorityPalette URGENT = new PriorityPalette("#cf222e", "紧急");

    /**
     * 按 Gotify 惯例把优先级归入四个档位。
     *
     * @param priority 原始优先级，零与负数按最低档处理
     */
    public static PriorityPalette of(int priority) {
        if (priority >= 8) {
            return URGENT;
        }
        if (priority == 7) {
            return HIGH;
        }
        if (priority >= 4) {
            return MEDIUM;
        }
        return LOW;
    }
}
