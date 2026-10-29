package app.backup.source

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.backup.core.Csv
import app.backup.core.PlainRecord
import app.backup.core.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** المسار البديل: استيراد ملفات يصدّرها المستخدم بنفسه (دون أي صلاحيات). */
object Importer {
    data class Out(val records: List<PlainRecord>, val skipped: Int)

    private fun idx(h: List<String>, vararg names: String) = h.indexOfFirst { it.trim().lowercase() in names }

    private fun callTypeOf(s: String): String {
        s.trim().toIntOrNull()?.let { return CallTypes.fromInt(it) }
        return when (s.trim().lowercase()) {
            "incoming", "in" -> "incoming"
            "outgoing", "out" -> "outgoing"
            "missed" -> "missed"
            "rejected" -> "rejected"
            "blocked" -> "blocked"
            "voicemail" -> "voicemail"
            else -> "other"
        }
    }

    private fun smsTypeOf(s: String): String {
        s.trim().toIntOrNull()?.let { return SmsTypes.fromInt(it) }
        return when (s.trim().lowercase()) {
            "inbox", "received", "in" -> "inbox"
            "sent", "out" -> "sent"
            "draft" -> "draft"
            "outbox" -> "outbox"
            "failed" -> "failed"
            "queued" -> "queued"
            else -> "other"
        }
    }

    /** أعمدة: number,type,date,duration,name — date: ميلي ثانية أو ISO-8601. */
    fun callsFromCsv(deviceId: String, text: String): Out {
        val rows = Csv.parse(text).filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return Out(emptyList(), 0)
        val h = rows[0]
        val iN = idx(h, "number", "phone", "phone_number")
        val iT = idx(h, "type", "call_type")
        val iD = idx(h, "date", "time", "timestamp", "datetime")
        val iU = idx(h, "duration", "duration_sec")
        val iName = idx(h, "name", "contact", "contact_name")
        if (iN < 0 || iD < 0) return Out(emptyList(), rows.size - 1)
        var skipped = 0
        val out = ArrayList<PlainRecord>()
        for (r in rows.drop(1)) {
            val ts = r.getOrNull(iD)?.let { TimeFmt.parse(it) }
            if (ts == null || ts <= 0) {
                skipped++
                continue
            }
            val type = if (iT >= 0) callTypeOf(r.getOrNull(iT) ?: "") else "other"
            val dur = if (iU >= 0) (r.getOrNull(iU)?.trim()?.toLongOrNull() ?: 0L) else 0L
            val name = if (iName >= 0) r.getOrNull(iName) else null
            out += Rec.call(deviceId, r.getOrNull(iN)?.trim() ?: "", ts, type, dur, name)
        }
        return Out(out, skipped)
    }

    /** أعمدة: address,body,date,type */
    fun smsFromCsv(deviceId: String, text: String): Out {
        val rows = Csv.parse(text).filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return Out(emptyList(), 0)
        val h = rows[0]
        val iA = idx(h, "address", "number", "from", "to", "phone")
        val iB = idx(h, "body", "text", "message")
        val iD = idx(h, "date", "time", "timestamp", "datetime")
        val iT = idx(h, "type")
        if (iA < 0 || iB < 0 || iD < 0) return Out(emptyList(), rows.size - 1)
        var skipped = 0
        val out = ArrayList<PlainRecord>()
        for (r in rows.drop(1)) {
            val ts = r.getOrNull(iD)?.let { TimeFmt.parse(it) }
            val addr = r.getOrNull(iA)?.trim().orEmpty()
            if (ts == null || ts <= 0 || addr.isEmpty()) {
                skipped++
                continue
            }
            out += Rec.sms(
                deviceId = deviceId,
                address = addr,
                body = r.getOrNull(iB) ?: "",
                ts = ts,
                type = if (iT >= 0) smsTypeOf(r.getOrNull(iT) ?: "") else "other",
            )
        }
        return Out(out, skipped)
    }

    /** صيغة XML الشائعة لتطبيق «SMS Backup & Restore». */
    fun smsFromXml(deviceId: String, input: InputStream): Out {
        val p = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        var skipped = 0
        val out = ArrayList<PlainRecord>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sms") {
                val addr = p.getAttributeValue(null, "address")?.trim().orEmpty()
                val ts = p.getAttributeValue(null, "date")?.toLongOrNull()
                if (addr.isEmpty() || ts == null || ts <= 0) skipped++
                else out += Rec.sms(
                    deviceId = deviceId,
                    address = addr,
                    body = p.getAttributeValue(null, "body") ?: "",
                    ts = ts,
                    type = SmsTypes.fromInt(p.getAttributeValue(null, "type")?.toIntOrNull() ?: 0),
                )
            }
            ev = p.next()
        }
        return Out(out, skipped)
    }

    suspend fun importUri(ctx: Context, uri: Uri, calls: Boolean): String = withContext(Dispatchers.IO) {
        try {
            Prefs.ensureInit(ctx)
            val uid = Engine.activeUidOrEmpty(ctx)
            val deviceId = Prefs.deviceIdForUser(uid.ifBlank { null })
            val name = uri.lastPathSegment?.lowercase().orEmpty()
            val stream = ctx.contentResolver.openInputStream(uri) ?: return@withContext "تعذّر فتح الملف"
            val out = stream.use {
                when {
                    calls -> callsFromCsv(deviceId, it.readBytes().toString(Charsets.UTF_8))
                    name.endsWith(".xml") -> smsFromXml(deviceId, it)
                    else -> smsFromCsv(deviceId, it.readBytes().toString(Charsets.UTF_8))
                }
            }
            val valid = out.records.filter { Validator.ok(it) }
            val added = Engine.storeAndQueue(
                ctx = ctx,
                dao = AppDb.get(ctx).dao(),
                batch = valid,
                uid = uid,
                scheduleUpload = true,
            )
            Prefs.setLastScan(System.currentTimeMillis())
            "تم استيراد $added سجل جديد، وتخطّي ${out.skipped + (out.records.size - valid.size)} سجل غير صالح، و${valid.size - added} مكرر."
        } catch (e: Exception) {
            "فشل الاستيراد: الملف غير مطابق للصيغة المتوقعة (${e.javaClass.simpleName})"
        }
    }
}
