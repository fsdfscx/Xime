package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑键盘选区行移动：
 * - Home/End 按 \n 定位当前逻辑行首尾
 * - 上下移动保持列号到相邻逻辑行，行短钳到行尾
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

    private fun visualLine(
        indexStart: Int,
        count: Int,
        top: Float,
        bottom: Float,
    ): List<CharBox> = (0 until count).map { column ->
        CharBox(
            index = indexStart + column,
            left = column * 10f,
            top = top,
            right = (column + 1) * 10f,
            bottom = bottom,
        )
    }

    @Test
    fun `视觉行_向上找相邻行同位置`() {
        val boxes = visualLine(0, 5, 0f, 20f) + visualLine(5, 5, 20f, 40f)
        // 第二行第 3 列，水平位置 28 → 第一行插入点 3（字符 2 与 3 之间）
        assertEquals(3, findVisualLineTarget(boxes, 7, 28f, 20f, 40f, -1))
    }

    @Test
    fun `视觉行_向下找相邻行同位置`() {
        val boxes = visualLine(0, 5, 0f, 20f) + visualLine(5, 5, 20f, 40f)
        // 第一行第 4 列，水平位置 34 → 第二行插入点 8（字符 7 与 8 之间）
        assertEquals(8, findVisualLineTarget(boxes, 3, 34f, 0f, 20f, 1))
    }

    @Test
    fun `视觉行_首行不再向上`() {
        val boxes = visualLine(0, 5, 0f, 20f)
        assertNull(findVisualLineTarget(boxes, 3, 30f, 0f, 20f, -1))
    }

    @Test
    fun `视觉行_末行不再向下`() {
        val boxes = visualLine(0, 5, 0f, 20f) + visualLine(5, 5, 20f, 40f)
        assertNull(findVisualLineTarget(boxes, 7, 30f, 20f, 40f, 1))
    }

    @Test
    fun `视觉行_无字符矩形返回null`() {
        assertNull(findVisualLineTarget(emptyList(), 0, 0f, 0f, 20f, -1))
    }

    @Test
    fun `视觉行_目标距离过远拒绝`() {
        val boxes = visualLine(0, 5, 0f, 20f)
        // 宿主索引错位时可能把远端字符当成相邻行，距离保护必须拒绝
        assertNull(findVisualLineTarget(boxes, 1000, 0f, 20f, 40f, -1))
    }

    @Test
    fun `视觉行_零矩形不参与计算`() {
        val boxes = listOf(
            CharBox(0, 0f, 0f, 0f, 0f),
            CharBox(1, 0f, 0f, 0f, 0f),
        )
        assertNull(findVisualLineTarget(boxes, 5, 0f, 20f, 40f, -1))
    }
}
