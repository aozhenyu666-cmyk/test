package com.yishou.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsTest {
    private val legacy = mapOf(
        "speech_base_url" to "https://old.example/v1",
        "speech_api_key" to "old-key",
        "speech_stt_model" to "old-input",
        "speech_tts_model" to "old-output",
        "speech_tts_voice" to "old-voice",
    )

    @Test
    fun oldSharedConfigurationInitializesBothIndependentServices() {
        val migrated = SpeechSettings.migrate(legacy)
        val speech = SpeechSettings.read(migrated)
        assertEquals("https://old.example/v1", speech.input.baseUrl)
        assertEquals("old-key", speech.input.apiKey)
        assertEquals("old-input", speech.input.model)
        assertEquals("https://old.example/v1", speech.output.baseUrl)
        assertEquals("old-key", speech.output.apiKey)
        assertEquals("old-output", speech.output.model)
        assertEquals("old-voice", speech.output.voice)
        assertTrue(speech.sttReady && speech.ttsReady)
        assertEquals(legacy["speech_api_key"], migrated["speech_api_key"])
        assertEquals(migrated, SpeechSettings.migrate(migrated))
    }

    @Test
    fun changingOneServiceDoesNotChangeTheOtherOrItsCredentials() {
        val migrated = SpeechSettings.migrate(legacy)
        val input = SpeechEndpoint("https://input.example/v1", "input-key", "input-model", language = "en")
        val changed = migrated + SpeechSettings.inputValues(input)
        val speech = SpeechSettings.read(changed)
        assertEquals(input, speech.input)
        assertEquals("old-key", speech.output.apiKey)
        val output = SpeechEndpoint("https://output.example/v1", "output-key", "output-model", voice = "voice", format = "wav")
        val updated = SpeechSettings.read(changed + SpeechSettings.outputValues(output))
        assertEquals(input, updated.input)
        assertEquals(output, updated.output)
    }

    @Test
    fun intentionallyClearedServiceDoesNotReappearOnRestart() {
        val cleared = SpeechSettings.migrate(legacy) + SpeechSettings.inputValues(SpeechEndpoint())
        val restarted = SpeechSettings.read(SpeechSettings.migrate(cleared))
        assertFalse(restarted.sttReady)
        assertTrue(restarted.ttsReady)
        assertEquals("", restarted.input.apiKey)
        assertEquals("old-key", restarted.output.apiKey)
    }

    @Test
    fun partialMigrationPreservesAlreadySavedIndependentService() {
        val input = SpeechEndpoint("https://new.example/v1", "new-key", "new-input")
        val restarted = SpeechSettings.read(SpeechSettings.migrate(legacy + SpeechSettings.inputValues(input)))
        assertEquals(input, restarted.input)
        assertEquals("old-output", restarted.output.model)
    }

    @Test
    fun legacyConstructorRemainsUsableAndNoConfigurationNeedsNoMigration() {
        val config = SpeechConfig("https://old.example/v1", "old-key", "in", "out", "voice")
        assertEquals("in", config.input.model)
        assertEquals("out", config.output.model)
        assertTrue(config.sttReady && config.ttsReady)
        val empty = SpeechSettings.read(SpeechSettings.migrate(emptyMap()))
        assertFalse(empty.sttReady || empty.ttsReady)
    }
}
