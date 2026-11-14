package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMediaControlTest {
    @Test
    fun mapsPauseAndResumeToMediaKeyCodes() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, PhoneMediaControl.keyCodeForAction("pause"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, PhoneMediaControl.keyCodeForAction("STOP"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, PhoneMediaControl.keyCodeForAction("resume"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, PhoneMediaControl.keyCodeForAction("play"))
        assertNull(PhoneMediaControl.keyCodeForAction("unknown"))
        assertNull(PhoneMediaControl.keyCodeForAction(null))
    }
}
