package com.zongkong.app.system

import org.junit.Assert.assertEquals
import org.junit.Test

class EnabledServiceListTest {
    @Test fun appendPreservesOtherServicesAndRecognizesShortNames() {
        assertEquals("a/.Reader:b/b.Reader:me/me.Guard", EnabledServiceList.append("a/.Reader:b/b.Reader", "me/me.Guard"))
        assertEquals("a/.Reader:me/.Guard", EnabledServiceList.append("a/.Reader:me/.Guard", "me/me.Guard"))
        assertEquals("me/me.Guard", EnabledServiceList.append("", "me/me.Guard"))
    }
}
