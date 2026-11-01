package com.yishou.app.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoSlotsTest {

    @Test fun 两个槽拼成一句() {
        assertEquals("生产性服务业，要赋能制造业", Slots("生产性服务业", "赋能制造业").sentence())
        assertEquals("生产性服务业", Slots("生产性服务业", "").sentence())
        assertEquals("赋能制造业", Slots("", "赋能制造业").sentence())
        assertEquals("", Slots().sentence())
    }

    @Test fun 空的不能交_套话方向不能交() {
        assertFalse(TwoSlots.canSubmit(Slots()))
        assertNull(TwoSlots.checkSubject("生产性服务业"))
        assertNotNull(TwoSlots.checkSubject(""))
        assertNotNull(TwoSlots.checkSubject("嗯"))
        // “发展”两个字太笼统，不是方向
        assertNotNull(TwoSlots.checkDirection("发展"))
        assertNotNull(TwoSlots.checkDirection("看情况"))
        assertNull(TwoSlots.checkDirection("赋能制造业向高端延伸"))
    }

    @Test fun 只填一个槽也能交_由判卷说还缺哪层() {
        assertTrue(TwoSlots.canSubmit(Slots(subject = "生产性服务业", direction = "")))
        assertTrue(TwoSlots.canSubmit(Slots(subject = "", direction = "赋能制造业")))
        assertTrue(TwoSlots.canSubmit(Slots("生产性服务业", "赋能制造业")))
    }

    @Test fun 追问指出缺的那一层() {
        assertTrue(TwoSlots.nudge(Slots(), emptyList()).contains("主体"))
        assertTrue(TwoSlots.nudge(Slots(subject = "生产性服务业"), listOf("它给谁服务")).contains("往哪走"))
        assertTrue(TwoSlots.nudge(Slots("生产性服务业", "赋能制造业"), listOf("补一层和制造业的联系")).contains("补一层和制造业的联系"))
    }

    @Test fun 差哪一槽说清楚() {
        assertNotNull(TwoSlots.why(Slots()))
        assertNull(TwoSlots.why(Slots("生产性服务业", "赋能制造业")))
    }
}
