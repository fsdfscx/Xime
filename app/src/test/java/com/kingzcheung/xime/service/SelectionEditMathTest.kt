package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑键盘 Home/End 的行边界计算：
 * 按 \n 分行定位当前逻辑行，不跨行。
 *
 * 上下选择已改为发送完整的 Shift + 方向键序列，由宿主按视觉行处理。
 */
class SelectionEditMathTest {

    private val multiLine = "aaa\nbbb\nccc"

    @Test
    fun `行首行尾_多行中间`() {
        assertEquals(4, lineStartAt(multiLine, 5))
        assertEquals(7, lineEndAt(multiLine, 5))
    }

    @Test
    fun `行首行尾_首行与末行`() {
        assertEquals(0, lineStartAt(multiLine, 1))
        assertEquals(3, lineEndAt(multiLine, 1))
        assertEquals(8, lineStartAt(multiLine, 9))
        assertEquals(11, lineEndAt(multiLine, 9))
    }

    @Test
    fun `行首行尾_行首位置归属当前行`() {
        assertEquals(4, lineStartAt(multiLine, 4))
        assertEquals(7, lineEndAt(multiLine, 4))
        assertEquals(8, lineStartAt(multiLine, 8))
        assertEquals(11, lineEndAt(multiLine, 8))
    }

    @Test
    fun `空文本不越界`() {
        assertEquals(0, lineStartAt("", 3))
        assertEquals(0, lineEndAt("", 3))
    }
}
