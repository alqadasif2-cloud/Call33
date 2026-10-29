package app.backup.viewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.backup.core.CloudRepo
import app.backup.core.Crypto
import app.backup.core.PlainRecord
import app.backup.core.RecordCodec
import app.backup.core.RecordType
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Exporter {
    data class Req(
        val deviceId: String, val deviceName: String, val types: Set<RecordType>, val formats: Set<String>,
        val from: Long?, val to: Long?, val zip: Boolean,
    ) {
        val singleFileName: String get() = "${ExportFormats.baseName(types.first())}.${formats.first()}"
    }

    /**
     * يجلب السجلات من السحابة، يفك تشفيرها، يكتبها عبر منتقي الملفات (SAF)،
     * ثم يعيد قراءة الملف من الذاكرة ويقارن بصمات SHA-256 قبل إعلان النجاح.
     */
    suspend fun run(
        ctx: Context, uri: Uri, req: Req, key: ByteArray, repo: CloudRepo, onProgress: (Long, Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val total = req.types.sumOf { repo.count(req.deviceId, it, req.from, req.to) }
        var done = 0L
        val shas = LinkedHashMap<String, String>()
        val counts = JSONObject()
        var warn = ""
        onProgress(0, total)

        try {
            val raw = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("تعذّر فتح الملف للكتابة")
            val zos = if (req.zip) ZipOutputStream(BufferedOutputStream(raw)) else null
            val plain = if (!req.zip) BufferedOutputStream(raw) else null
            try {
                for (type in req.types) {
                    val recs = ArrayList<PlainRecord>()
                    var cursor: DocumentSnapshot? = null
                    do {
                        val p = repo.page(req.deviceId, type, req.from, req.to, cursor, 300)
                        for (e in p.items) {
                            try { recs += RecordCodec.decrypt(key, req.deviceId, e) }
                            catch (ex: Exception) { throw IOException("فشل فك تشفير سجل؛ تحقق من عبارة المرور أو سلامة البيانات") }
                        }
                        cursor = p.cursor
                        onProgress(done + recs.size, total)
                        if (p.exhausted) break
                    } while (true)
                    done += recs.size
                    recs.reverse() // ترتيب زمني تصاعدي
                    counts.put(type.id, recs.size)
                    for (fmt in req.formats) {
                        val bytes = ExportFormats.render(fmt, type, recs, zone)
                        val name = "${ExportFormats.baseName(type)}.$fmt"
                        shas[name] = Crypto.sha256Hex(bytes)
                        if (zos != null) { zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
                        else plain!!.write(bytes)
                    }
                }
                if (done != total) warn = " (تنبيه: العدد المتوقع $total والمُصدَّر $done؛ ربما تغيّرت البيانات أثناء التصدير)"
                if (zos != null) {
                    val manifest = JSONObject()
                        .put("deviceId", req.deviceId).put("deviceName", req.deviceName)
                        .put("exportedAt", Instant.now().toString()).put("counts", counts)
                        .put("from", req.from ?: JSONObject.NULL).put("to", req.to ?: JSONObject.NULL)
                        .toString(2).toByteArray(Charsets.UTF_8)
                    shas["manifest.json"] = Crypto.sha256Hex(manifest)
                    zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest); zos.closeEntry()
                }
            } finally {
                zos?.close()
                plain?.close()
            }

            // التحقق: إعادة قراءة الملف المكتوب ومقارنة البصمات
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر إعادة فتح الملف للتحقق")
            val read = LinkedHashMap<String, String>()
            input.use {
                if (req.zip) {
                    val zin = ZipInputStream(it)
                    while (true) {
                        val e = zin.nextEntry ?: break
                        read[e.name] = Crypto.sha256Hex(zin.readBytes())
                    }
                } else read[req.singleFileName] = Crypto.sha256Hex(it.readBytes())
            }
            if (read != shas) throw IOException("فشل التحقق: الملف المكتوب لا يطابق البيانات المسترجعة")
            "تم تصدير $done سجل في ${shas.size} ملف، وجرى التحقق من اكتمال الملفات$warn"
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            throw e
        }
    }
}
