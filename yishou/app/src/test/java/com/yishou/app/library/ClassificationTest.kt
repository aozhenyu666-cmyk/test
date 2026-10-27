package com.yishou.app.library

import org.junit.Assert.*
import org.junit.Test

class ClassificationTest {
    @Test fun `同批内容逐条分类而不是全塞进一个行测框`() {
        assertEquals(3L, Classification.category("海龟汤：汤面是一人走进餐厅，汤底另附", "material", emptyList()))
        assertEquals(2L, Classification.category("根据给定资料概括主要问题，申论要点", "material", emptyList()))
        assertEquals(1L, Classification.category("片段阅读 选出主旨", "card", emptyList()))
        assertEquals(4L, Classification.category("这是我的一段读书笔记", "material", emptyList()))
    }
    @Test fun `自建分类名可以命中 手工指定优先于自动`() {
        val tabs = listOf(Topic(10, "英语"))
        assertEquals(10L, Classification.category("英语阅读练习", "material", tabs))
        assertEquals(10L, Classification.choose("申论", "material", tabs, 10))
    }
}
