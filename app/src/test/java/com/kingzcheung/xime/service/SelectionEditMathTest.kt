package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑键盘选区行移动：
 * - 行首/行尾按 \n 计算，不跨行
 * - 上下移动保持列号，行短钳到行尾
 * - 首行 ↑ / 末行 ↓（含单行文本）原地不动
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
    fun `上下移动_保持列号`() {
        assertEquals(1, verticalTarget(multiLine, 5, -1))
        assertEquals(9, verticalTarget(multiLine, 5, 1))
    }

    @Test
    fun `上下移动_行短时钳到行尾`() {
        val text = "abcdef\nxy\nzzzz"
        assertEquals(9, verticalTarget(text, 4, 1))
        assertEquals(12, verticalTarget(text, 9, 1))
        assertEquals(9, verticalTarget(text, 12, -1))
    }

    @Test
    fun `上下移动_首行末行不动`() {
        assertNull(verticalTarget(multiLine, 1, -1))
        assertNull(verticalTarget(multiLine, 9, 1))
    }

    @Test
    fun `上下移动_单行文本不动`() {
        assertNull(verticalTarget("一整段没有换行的文本", 5, -1))
        assertNull(verticalTarget("一整段没有换行的文本", 5, 1))
    }

    @Test
    fun `空文本不越界`() {
        assertEquals(0, lineStartAt("", 3))
        assertEquals(0, lineEndAt("", 3))
        assertNull(verticalTarget("", 0, -1))
        assertNull(verticalTarget("", 0, 1))
    }
}
