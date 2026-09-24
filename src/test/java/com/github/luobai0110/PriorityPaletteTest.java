package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriorityPaletteTest {

    @Test
    void 低优先级用灰蓝色() {
        assertEquals("#57606a", PriorityPalette.of(1).color());
        assertEquals("#57606a", PriorityPalette.of(3).color());
    }

    @Test
    void 中优先级用琥珀色() {
        assertEquals("#bf8700", PriorityPalette.of(4).color());
        assertEquals("#bf8700", PriorityPalette.of(6).color());
    }

    @Test
    void 高优先级用橙红色() {
        assertEquals("#d1550d", PriorityPalette.of(7).color());
    }

    @Test
    void 紧急优先级用正红色() {
        assertEquals("#cf222e", PriorityPalette.of(8).color());
        assertEquals("#cf222e", PriorityPalette.of(99).color());
    }

    @Test
    void 每个档位都有中文标签() {
        assertEquals("低", PriorityPalette.of(1).label());
        assertEquals("中", PriorityPalette.of(5).label());
        assertEquals("高", PriorityPalette.of(7).label());
        assertEquals("紧急", PriorityPalette.of(10).label());
    }

    @Test
    void 零与负数按低优先级处理() {
        assertEquals("#57606a", PriorityPalette.of(0).color());
        assertEquals("#57606a", PriorityPalette.of(-5).color());
    }
}
