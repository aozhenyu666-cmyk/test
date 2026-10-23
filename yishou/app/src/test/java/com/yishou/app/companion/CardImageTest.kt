package com.yishou.app.companion

import com.yishou.app.companion.core.CardImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 训练卡图片：只在自家目录里、只认自己命名的 jpg */
class CardImageTest {

    private val filesDir = File("C:/app/files")

    @Test
    fun `文件名按场次和轮次排`() {
        assertEquals("12-3.jpg", CardImage.fileName(12, 3))
        assertEquals("companion/cards", CardImage.DIR)
        val f = CardImage.file(filesDir, 12, 3)
        assertEquals("12-3.jpg", f.name)
        assertEquals("cards", f.parentFile.name)
    }

    @Test
    fun `只认自家目录下的 jpg`() {
        val ours = CardImage.file(filesDir, 7, 1).canonicalPath
        assertTrue(CardImage.owned(filesDir, ours))
        assertFalse(CardImage.owned(filesDir, File(filesDir, "other/7-1.jpg").canonicalPath))
        assertFalse(CardImage.owned(filesDir, File(filesDir, "companion/cards/7-1.png").canonicalPath))
        assertFalse(CardImage.owned(filesDir, ""))
    }

    @Test
    fun `跳出目录的路径不算自己的`() {
        val escape = File(filesDir, "companion/cards/../../secret/7-1.jpg").canonicalPath
        assertFalse(CardImage.owned(filesDir, escape))
    }
}
