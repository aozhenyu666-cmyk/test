package io.github.ccch1mneyyy.dsh_app.loop.intercept

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPasteEditTextTest {
    @Test
    fun pasteCutAndCopyAreRefused() {
        assertTrue(NoPasteEditText.blocks(android.R.id.paste))
        assertTrue(NoPasteEditText.blocks(android.R.id.pasteAsPlainText))
        assertTrue(NoPasteEditText.blocks(android.R.id.cut))
        assertTrue(NoPasteEditText.blocks(android.R.id.copy))
    }

    @Test
    fun ordinaryEditingActionsKeepWorking() {
        assertFalse(NoPasteEditText.blocks(android.R.id.selectAll))
    }
}
