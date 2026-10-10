package app.jobtracker.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 日期存为天数，时间戳存为毫秒，时刻存为当天秒数，键值表存为 JSON 文本 */
class Converters {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun toLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun fromLocalTime(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun toLocalTime(value: Int?): LocalTime? = value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? = value?.let { Json.encodeToString(mapSerializer, it) }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? = value?.let { Json.decodeFromString(mapSerializer, it) }
}
