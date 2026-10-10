package app.jobtracker.ui.settings

import app.jobtracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun acceptsHttpsUrlAndModelNames() {
        assertNull(validateModelSettings("https://api.anthropic.com", "claude-sonnet-5-5", "claude-haiku-5-5"))
        assertNull(validateModelSettings("  https://relay.example.com/  ", "a", "b"))
    }

    @Test
    fun rejectsNonHttpsOrEmptyUrl() {
        assertEquals(R.string.settings_error_base_url, validateModelSettings("http://api.anthropic.com", "a", "b"))
        assertEquals(R.string.settings_error_base_url, validateModelSettings("https://", "a", "b"))
        assertEquals(R.string.settings_error_base_url, validateModelSettings("", "a", "b"))
    }

    @Test
    fun rejectsBlankModelNames() {
        assertEquals(R.string.settings_error_model_blank, validateModelSettings("https://x.com", " ", "b"))
        assertEquals(R.string.settings_error_model_blank, validateModelSettings("https://x.com", "a", ""))
    }
}
