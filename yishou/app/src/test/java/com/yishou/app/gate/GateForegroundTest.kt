package com.yishou.app.gate

import org.junit.Assert.*
import org.junit.Test

class GateForegroundTest {
    @Test fun `下拉通知和输入法不抹掉当前应用 到期仍检查娱乐应用`() {
        val f = GateForeground()
        f.update("tv.danmaku.bili", false)
        f.update("com.android.systemui", true)
        f.update("input.method", true)
        assertEquals("tv.danmaku.bili", f.current)
        f.update("launcher", false)
        assertEquals("launcher", f.current)
    }
}
