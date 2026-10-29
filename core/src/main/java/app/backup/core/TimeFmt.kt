package app.backup.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    fun iso(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ts).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** يقبل: ميلي ثانية، ثواني، ISO-8601 (مع/بدون منطقة زمنية). */
    fun parse(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = s.trim()
        t.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { Instant.parse(t).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }
}
