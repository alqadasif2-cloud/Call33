package app.backup.viewer

import app.backup.core.Csv
import app.backup.core.PlainRecord
import app.backup.core.RecordType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ExportFormatsTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val sms = listOf(
        PlainRecord("1", "d", RecordType.SMS, 1_700_000_000_000L, JSONObject().put("address", "+966500000000").put("type", "inbox").put("body", "مرحبا، \"كيف\" الحال؟\nسطر ثانٍ")),
        PlainRecord("2", "d", RecordType.SMS, 1_700_000_100_000L, JSONObject().put("address", "شركة").put("type", "sent").put("body", "تم")),
    )

    @Test fun csvRoundTripKeepsArabicAndMultilineBodies() {
        val rows = Csv.parse(ExportFormats.csv(RecordType.SMS, sms, zone))
        assertEquals(3, rows.size) // الترويسة + سجلان
        assertEquals("مرحبا، \"كيف\" الحال؟\nسطر ثانٍ", rows[1][5])
        assertTrue(rows[1][1].startsWith("2023-11-15T01:13:20+03:00"))
    }

    @Test fun jsonIsValidAndComplete() {
        val arr = JSONArray(ExportFormats.json(sms, zone))
        assertEquals(2, arr.length())
        assertEquals("شركة", arr.getJSONObject(1).getString("address"))
        assertEquals(1_700_000_100_000L, arr.getJSONObject(1).getLong("timestamp"))
    }

    @Test fun emptyExportsAreStillValid() {
        assertEquals(0, JSONArray(ExportFormats.json(emptyList(), zone)).length())
        assertEquals(1, Csv.parse(ExportFormats.csv(RecordType.CALL, emptyList(), zone)).size)
    }

    @Test fun txtContainsBodies() {
        assertTrue(ExportFormats.txt(RecordType.SMS, sms, zone).contains("سطر ثانٍ"))
    }
}
