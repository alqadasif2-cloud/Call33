package app.backup.source

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.backup.core.Crypto
import app.backup.core.PlainRecord
import app.backup.core.RecordIds
import app.backup.core.RecordType
import app.backup.core.Sensitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

object CallTypes {
    fun fromInt(i: Int) = when (i) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }
}

object SmsTypes {
    fun fromInt(i: Int) = when (i) {
        1 -> "inbox"
        2 -> "sent"
        3 -> "draft"
        4 -> "outbox"
        5 -> "failed"
        6 -> "queued"
        else -> "other"
    }
}

object PhoneNorm {
    /** تطبيع عنوان المرسل لمطابقة صيغ الأرقام المحلية والدولية (مثل 050... و+96650...). */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { it.isLetter() }) return trimmed.lowercase()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return trimmed
        return if (digits.length >= 8) digits.takeLast(8) else digits
    }
}

object Rec {
    fun call(deviceId: String, number: String, ts: Long, type: String, durationSec: Long, name: String?): PlainRecord {
        val f = JSONObject()
            .put("number", number)
            .put("type", type)
            .put("durationSec", durationSec)
        if (!name.isNullOrBlank()) f.put("name", name)
        return PlainRecord(
            RecordIds.make(deviceId, RecordType.CALL, number, ts, type, durationSec),
            deviceId,
            RecordType.CALL,
            ts,
            f,
        )
    }

    fun sms(
        deviceId: String,
        address: String,
        body: String,
        ts: Long,
        type: String,
        dateSent: Long = 0L,
        fromPdu: Boolean = false,
    ): PlainRecord {
        val f = JSONObject()
            .put("address", address)
            .put("body", body)
            .put("type", type)
        if (dateSent > 0L) f.put("dateSent", dateSent)
        if (fromPdu) f.put("src", "pdu")
        return PlainRecord(
            RecordIds.make(deviceId, RecordType.SMS, address, ts, type, body),
            deviceId,
            RecordType.SMS,
            ts,
            f,
        )
    }

    fun clip(deviceId: String, text: String, ts: Long): PlainRecord {
        // المعرّف مشتق من النص والجهاز: نفس النص لا يُحفظ مرتين
        return PlainRecord(
            RecordIds.make(deviceId, RecordType.CLIP, text),
            deviceId,
            RecordType.CLIP,
            ts,
            JSONObject().put("text", text),
        )
    }
}

/** التحقق من صحة البيانات قبل الحفظ المحلي. */
object Validator {
    fun ok(r: PlainRecord): Boolean {
        if (r.ts <= 0L) return false
        return when (r.type) {
            RecordType.CALL -> true // الرقم قد يكون فارغًا للمتصل المخفي
            RecordType.SMS -> r.fields.optString("address").isNotBlank()
            RecordType.CLIP -> r.fields.optString("text").isNotBlank()
        }
    }
}

/**
 * يمنع تكرار نفس الرسالة النصية الواردة عندما يلتقطها [SmsBroadcastReceiver] فور وصولها (بتوقيت PDU/SMSC)
 * ثم يكتبها تطبيق الرسائل الافتراضي في [Telephony.Sms] بعد ثوانٍ بتوقيت إدخال محلي مختلف.
 */
object SmsDeduplicator {
    const val WINDOW_MS = 120_000L

    fun filterProviderDuplicates(
        incomingBatch: List<PlainRecord>,
        existingInWindow: List<RecordEntity>,
    ): List<PlainRecord> {
        if (incomingBatch.isEmpty() || existingInWindow.isEmpty()) return incomingBatch

        data class ExistingSms(
            val id: String,
            val normAddr: String,
            val body: String,
            val type: String,
            val ts: Long,
            val dateSent: Long,
            val isPdu: Boolean,
            var matched: Boolean = false,
        )

        val candidates = existingInWindow.mapNotNull { row ->
            if (row.type != RecordType.SMS.id) return@mapNotNull null
            val obj = runCatching { JSONObject(row.json) }.getOrNull() ?: return@mapNotNull null
            ExistingSms(
                id = row.id,
                normAddr = PhoneNorm.normalize(obj.optString("address")),
                body = obj.optString("body"),
                type = obj.optString("type"),
                ts = row.ts,
                dateSent = obj.optLong("dateSent", 0L),
                isPdu = obj.optString("src") == "pdu",
            )
        }

        if (candidates.isEmpty()) return incomingBatch

        val accepted = ArrayList<PlainRecord>(incomingBatch.size)
        for (rec in incomingBatch) {
            if (rec.type != RecordType.SMS) {
                accepted += rec
                continue
            }
            val type = rec.fields.optString("type")
            if (type != "inbox") {
                accepted += rec
                continue
            }
            val normAddr = PhoneNorm.normalize(rec.fields.optString("address"))
            val body = rec.fields.optString("body")
            val dateSent = rec.fields.optLong("dateSent", 0L)
            val fromPdu = rec.fields.optString("src") == "pdu"

            val dup = candidates.firstOrNull { ex ->
                if (ex.id == rec.id) return@firstOrNull true
                if (ex.matched || ex.type != "inbox" || ex.normAddr != normAddr || ex.body != body) {
                    return@firstOrNull false
                }
                when {
                    // مطابقة دقيقة عبر توقيت الإرسال الأصلي لشبكة الاتصالات (DATE_SENT == PDU timestamp)
                    !fromPdu && ex.isPdu && dateSent > 0L && abs(ex.ts - dateSent) <= 3_000L -> true
                    fromPdu && !ex.isPdu && ex.dateSent > 0L && abs(rec.ts - ex.dateSent) <= 3_000L -> true
                    // إذا لم يوفّر المزوّد DATE_SENT، نطابق سجل PDU واحد ضمن نافذة زمنية ضيقة (25 ثانية)
                    ex.isPdu != fromPdu && abs(ex.ts - rec.ts) <= 25_000L -> true
                    else -> false
                }
            }

            if (dup != null) {
                dup.matched = true
            } else {
                accepted += rec
            }
        }
        return accepted
    }
}

object CallLogCollector {
    /** نافذة رجوع 6 ساعات لضمان عدم تفويت مكالمة طويلة بدأت قبل مكالمة فائتة قصيرة وانتهت بعدها. */
    const val LOOKBACK_MS = 6 * 3600_000L

    /** يقرأ السجلات عبر الواجهة الرسمية CallLog (يتطلب READ_CALL_LOG). */
    suspend fun collect(ctx: Context, deviceId: String, since: Long, sink: suspend (List<PlainRecord>) -> Unit) =
        withContext(Dispatchers.IO) {
            val querySince = if (since > 0L) (since - LOOKBACK_MS).coerceAtLeast(0L) else 0L
            val proj = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.CACHED_NAME,
            )
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                proj,
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(querySince.toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { c ->
                val buf = ArrayList<PlainRecord>(250)
                while (c.moveToNext()) {
                    buf += Rec.call(
                        deviceId = deviceId,
                        number = c.getString(0) ?: "",
                        ts = c.getLong(2),
                        type = CallTypes.fromInt(c.getInt(1)),
                        durationSec = c.getLong(3),
                        name = c.getString(4),
                    )
                    if (buf.size >= 250) {
                        sink(buf.toList())
                        buf.clear()
                    }
                }
                if (buf.isNotEmpty()) sink(buf.toList())
            }
            Unit
        }
}

object SmsCollector {
    const val LOOKBACK_MS = 6 * 3600_000L

    /** يقرأ الرسائل الواردة والصادرة عبر Telephony.Sms (يتطلب READ_SMS). */
    suspend fun collect(ctx: Context, deviceId: String, since: Long, sink: suspend (List<PlainRecord>) -> Unit) =
        withContext(Dispatchers.IO) {
            val querySince = if (since > 0L) (since - LOOKBACK_MS).coerceAtLeast(0L) else 0L
            val proj = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE_SENT,
            )
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                proj,
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(querySince.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val buf = ArrayList<PlainRecord>(250)
                while (c.moveToNext()) {
                    buf += Rec.sms(
                        deviceId = deviceId,
                        address = c.getString(0) ?: "",
                        body = c.getString(1) ?: "",
                        ts = c.getLong(2),
                        type = SmsTypes.fromInt(c.getInt(3)),
                        dateSent = c.getLong(4),
                        fromPdu = false,
                    )
                    if (buf.size >= 250) {
                        sink(buf.toList())
                        buf.clear()
                    }
                }
                if (buf.isNotEmpty()) sink(buf.toList())
            }
            Unit
        }
}

enum class ClipResult { SAVED, DUPLICATE, SENSITIVE, EMPTY, TOO_LONG, DISABLED }

object ClipSaver {
    fun validateAndBuild(
        deviceId: String,
        enabledTypes: Set<String>,
        text: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ClipResult, PlainRecord?> {
        if (RecordType.CLIP.id !in enabledTypes) return ClipResult.DISABLED to null
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return ClipResult.EMPTY to null
        if (t.length > 100_000) return ClipResult.TOO_LONG to null
        if (Sensitive.looksSensitive(t)) return ClipResult.SENSITIVE to null
        return ClipResult.SAVED to Rec.clip(deviceId, t, nowMs)
    }

    /** يحفظ نص الحافظة محليًا في Room ثم يجدول رفعه الفوري إلى Firebase عند توفر الاتصال. */
    suspend fun save(ctx: Context, text: String?, scheduleUpload: Boolean = true): ClipResult {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        val uid = Engine.activeUidOrEmpty(appCtx)
        val deviceId = Prefs.deviceIdForUser(uid.ifBlank { null })
        val (status, rec) = validateAndBuild(deviceId, Prefs.enabled.value, text)
        if (rec == null) return status

        val inserted = Engine.storeAndQueue(
            ctx = appCtx,
            dao = AppDb.get(appCtx).dao(),
            batch = listOf(rec),
            uid = uid,
            scheduleUpload = scheduleUpload,
        )
        Prefs.lastCapturedClipHash = Crypto.sha256Hex(rec.fields.optString("text")).take(40)
        Prefs.setLastScan(System.currentTimeMillis())
        return if (inserted == 0) ClipResult.DUPLICATE else ClipResult.SAVED
    }

    fun readCurrentClipboardText(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount <= 0) return null
        val desc = clip.description
        if (desc != null && isMarkedSensitiveOnApi33(desc)) return null
        return clip.getItemAt(0)?.coerceToText(ctx)?.toString()
    }

    private fun isMarkedSensitiveOnApi33(desc: ClipDescription): Boolean {
        val extras = desc.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }
}

/**
 * مراقبة الحافظة التلقائية المتوافقة رسميًا مع إصدارات أندرويد:
 * - على Android 8.0–9 (API 26–28): يعمل مستمع [ClipboardManager.OnPrimaryClipChangedListener] تلقائيًا حتى في الخلفية.
 * - على Android 10+ (API 29+): يمنع النظام قراءة الحافظة للتطبيقات الخلفية، لذا يلتقط التطبيق النص تلقائيًا فور
 *   حصول نافذته على التركيز (أو عبر نافذة الحفظ السريع الشفافة / قائمة التحديد / المشاركة) مع فحص طابع وقت الحافظة
 *   أولًا لتجنّب تكرار تنبيه القراءة في Android 12+ عندما لا يتغير النص.
 */
object ClipboardAutoMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val appCtx = ctx.applicationContext
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.addPrimaryClipChangedListener {
                onClipboardEvent(appCtx, cm, fromWindowFocus = false)
            }
            registered = true
        }
    }

    fun onAppWindowFocused(ctx: Context) {
        val appCtx = ctx.applicationContext
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        onClipboardEvent(appCtx, cm, fromWindowFocus = true)
    }

    private fun onClipboardEvent(appCtx: Context, cm: ClipboardManager, fromWindowFocus: Boolean) {
        Prefs.ensureInit(appCtx)
        if (!Prefs.onboardingDone.value) return
        if (RecordType.CLIP.id !in Prefs.enabled.value) return
        if (Engine.activeUidOrEmpty(appCtx).isBlank()) return

        val hasClip = runCatching { cm.hasPrimaryClip() }.getOrDefault(false)
        if (!hasClip) return

        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        val descTs = desc?.timestamp ?: 0L
        if (fromWindowFocus && descTs > 0L && descTs == Prefs.lastCheckedClipTimestamp) {
            return
        }

        val text = ClipSaver.readCurrentClipboardText(appCtx)?.trim().orEmpty()
        if (text.isEmpty()) return
        if (descTs > 0L) {
            Prefs.lastCheckedClipTimestamp = descTs
        }

        val hash = Crypto.sha256Hex(text).take(40)
        if (hash == Prefs.lastCapturedClipHash) return

        scope.launch {
            ClipSaver.save(appCtx, text, scheduleUpload = true)
        }
    }
}

object NotificationChannels {
    const val SYNC_CHANNEL_ID = "backup_bg_sync"
    const val QUICK_CLIP_CHANNEL_ID = "backup_quick_clip"
    const val SYNC_NOTIFICATION_ID = 4101
    const val QUICK_CLIP_NOTIFICATION_ID = 4102

    fun ensureCreated(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val syncChannel = NotificationChannel(
            SYNC_CHANNEL_ID,
            "مزامنة النسخ الاحتياطي",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "يظهر مؤقتًا عند تنفيذ مهام المزامنة المسرّعة في الخلفية"
            setShowBadge(false)
        }
        val clipChannel = NotificationChannel(
            QUICK_CLIP_CHANNEL_ID,
            "الحفظ السريع للحافظة",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "إشعار اختياري هادئ يتيح حفظ النص المنسوخ بضغطة واحدة على أندرويد 10+"
            setShowBadge(false)
        }
        nm.createNotificationChannel(syncChannel)
        nm.createNotificationChannel(clipChannel)
    }
}

object QuickClipNotification {
    fun canPostNotifications(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun sync(ctx: Context) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        NotificationChannels.ensureCreated(appCtx)
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val shouldShow = Prefs.onboardingDone.value &&
            !Prefs.paused.value &&
            RecordType.CLIP.id in Prefs.enabled.value &&
            Prefs.quickClipNotifEnabled.value &&
            canPostNotifications(appCtx)

        if (!shouldShow) {
            nm.cancel(NotificationChannels.QUICK_CLIP_NOTIFICATION_ID)
            return
        }

        val saveIntent = Intent(appCtx, QuickClipSaveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val savePending = PendingIntent.getActivity(appCtx, 201, saveIntent, piFlags)

        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(appCtx, 202, openIntent, piFlags)

        val notification = NotificationCompat.Builder(appCtx, NotificationChannels.QUICK_CLIP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("الحفظ السريع للحافظة مفعّل")
            .setContentText("اضغط هنا لحفظ النص المنسوخ حاليًا ومزامنته مشفّرًا")
            .setContentIntent(savePending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_save, "حفظ النص المنسوخ", savePending)
            .addAction(android.R.drawable.ic_menu_preferences, "فتح التطبيق", openPending)
            .build()

        nm.notify(NotificationChannels.QUICK_CLIP_NOTIFICATION_ID, notification)
    }
}
