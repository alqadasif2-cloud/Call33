package app.backup.viewer

import app.backup.core.Csv
import app.backup.core.PlainRecord
import app.backup.core.RecordType
import app.backup.core.TimeFmt
import org.json.JSONObject
import java.time.ZoneId

/** كتابة CSV/JSON/TXT — دوال نقية قابلة للاختبار. السجلات تُمرَّر مرتبة تصاعديًا بالزمن. */
object ExportFormats {
    fun baseName(t: RecordType) = when (t) { RecordType.CALL -> "calls"; RecordType.SMS -> "messages"; RecordType.CLIP -> "clipboard" }

    fun header(t: RecordType): List<String> = when (t) {
        RecordType.CALL -> listOf("id", "date", "timestamp", "number", "type", "duration_sec", "name")
        RecordType.SMS -> listOf("id", "date", "timestamp", "address", "type", "body")
        RecordType.CLIP -> listOf("id", "date", "timestamp", "text")
    }

    fun cells(r: PlainRecord, zone: ZoneId): List<String> {
        val f = r.fields
        val base = listOf(r.id, TimeFmt.iso(r.ts, zone), r.ts.toString())
        return base + when (r.type) {
            RecordType.CALL -> listOf(f.optString("number"), f.optString("type"), f.optLong("durationSec").toString(), f.optString("name", ""))
            RecordType.SMS -> listOf(f.optString("address"), f.optString("type"), f.optString("body"))
            RecordType.CLIP -> listOf(f.optString("text"))
        }
    }

    fun csv(t: RecordType, recs: List<PlainRecord>, zone: ZoneId): String =
        "\uFEFF" + (listOf(Csv.row(header(t))) + recs.map { Csv.row(cells(it, zone)) }).joinToString("\r\n") + "\r\n"

    fun json(recs: List<PlainRecord>, zone: ZoneId): String {
        val sb = StringBuilder("[\n")
        recs.forEachIndexed { i, r ->
            val o = JSONObject()
            o.put("id", r.id); o.put("date", TimeFmt.iso(r.ts, zone)); o.put("timestamp", r.ts); o.put("kind", r.type.id)
            r.fields.keys().forEach { k -> o.put(k, r.fields.get(k)) }
            sb.append(o.toString())
            if (i < recs.size - 1) sb.append(',')
            sb.append('\n')
        }
        return sb.append("]\n").toString()
    }

    fun txt(t: RecordType, recs: List<PlainRecord>, zone: ZoneId): String =
        recs.joinToString("\n\n") { r ->
            val d = TimeFmt.iso(r.ts, zone)
            val f = r.fields
            when (t) {
                RecordType.CALL -> "[$d] ${f.optString("name").ifBlank { f.optString("number") }} | ${f.optString("type")} | ${f.optLong("durationSec")}s"
                RecordType.SMS -> "[$d] ${f.optString("address")} (${f.optString("type")})\n${f.optString("body")}"
                RecordType.CLIP -> "[$d]\n${f.optString("text")}"
            }
        } + "\n"

    fun render(format: String, t: RecordType, recs: List<PlainRecord>, zone: ZoneId): ByteArray = when (format) {
        "csv" -> csv(t, recs, zone)
        "json" -> json(recs, zone)
        else -> txt(t, recs, zone)
    }.toByteArray(Charsets.UTF_8)
}
