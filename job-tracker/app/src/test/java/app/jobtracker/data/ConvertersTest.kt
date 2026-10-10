package app.jobtracker.data

import app.jobtracker.data.db.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun localDateRoundTrip() {
        val date = LocalDate.of(2026, 10, 9)
        assertEquals(date, converters.toLocalDate(converters.fromLocalDate(date)))
        assertNull(converters.fromLocalDate(null))
        assertNull(converters.toLocalDate(null))
    }

    @Test
    fun instantRoundTripKeepsMillis() {
        val instant = Instant.parse("2026-10-09T01:02:03.456Z")
        assertEquals(instant, converters.toInstant(converters.fromInstant(instant)))
    }

    @Test
    fun localTimeRoundTrip() {
        val time = LocalTime.of(9, 0)
        assertEquals(9 * 3600, converters.fromLocalTime(time))
        assertEquals(time, converters.toLocalTime(converters.fromLocalTime(time)))
    }

    @Test
    fun mapRoundTripKeepsChineseKeys() {
        val map = mapOf("某招聘平台" to "com.example.jobs", "邮箱" to "com.example.mail")
        assertEquals(map, converters.toStringMap(converters.fromStringMap(map)))
        assertEquals(emptyMap<String, String>(), converters.toStringMap(converters.fromStringMap(emptyMap())))
    }
}
