package app.backup.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.backup.core.CloudInit
import app.backup.core.RecordType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AssembledIncomingSms(
    val address: String,
    val body: String,
    val timestampMs: Long,
)

/**
 * يلتقط الرسائل النصية الواردة فور وصول بث النظام [Telephony.Sms.Intents.SMS_RECEIVED_ACTION]
 * حتى عندما يكون التطبيق مغلقًا تمامًا، ويجمع أجزاء الرسائل الطويلة في رسالة واحدة بتوقيتها الأصلي.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
        if (messages.isEmpty()) return

        val assembled = LinkedHashMap<String, Pair<Long, StringBuilder>>()
        for (part in messages) {
            val addr = (part.displayOriginatingAddress ?: part.originatingAddress).orEmpty().trim()
            if (addr.isEmpty()) continue
            val textPart = part.displayMessageBody ?: part.messageBody ?: ""
            val ts = part.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            val entry = assembled.getOrPut(addr) { ts to StringBuilder() }
            entry.second.append(textPart)
        }
        if (assembled.isEmpty()) return

        val items = assembled.map { (addr, pair) ->
            AssembledIncomingSms(address = addr, body = pair.second.toString(), timestampMs = pair.first)
        }

        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        ReceiverScope.scope.launch {
            try {
                withTimeoutOrNull(8_500L) {
                    CloudInit.configure(appCtx)
                    Prefs.ensureInit(appCtx)
                    for (msg in items) {
                        Engine.captureIncomingSms(
                            ctx = appCtx,
                            address = msg.address,
                            body = msg.body,
                            pduTimestampMs = msg.timestampMs,
                        )
                    }
                    Scheduler.enqueueDelayedScan(appCtx, "post_sms_followup", 5_000L)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * يكتشف انتهاء المكالمات الهاتفية (الواردة أو الصادرة أو الفائتة أو المرفوضة) فور تحوّل حالة الهاتف إلى IDLE
 * فيقرأ سجل المكالمة الجديد ويحفظه في Room ويجدول رفعه تلقائيًا دون الحاجة لفتح التطبيق.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        ReceiverScope.scope.launch {
            try {
                withTimeoutOrNull(8_500L) {
                    CloudInit.configure(appCtx)
                    Prefs.ensureInit(appCtx)
                    if (!Prefs.onboardingDone.value) return@withTimeoutOrNull
                    if (RecordType.CALL.id !in Prefs.enabled.value) return@withTimeoutOrNull

                    // ننتظر لحظة وجيزة حتى ينتهي نظام الاتصال من كتابة المكالمة في CallLog.Calls
                    delay(2_200L)
                    val dao = AppDb.get(appCtx).dao()
                    Engine.scan(appCtx, dao)
                    val uid = Engine.activeUidOrEmpty(appCtx)
                    if (!Prefs.paused.value && uid.isNotBlank() && dao.pendingOrFailedNowForUser(uid) > 0) {
                        Scheduler.enqueueImmediateUpload(appCtx)
                    }
                    // فحص تأكيدي إضافي بعد 8 ثوانٍ تحسبًا للأجهزة التي تتأخر في تحديث سجل المكالمات
                    Scheduler.enqueueDelayedScan(appCtx, "post_call_followup", 8_000L)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * يستعيد الأعمال المجدولة ومراقبة تغييرات السجلات بعد إعادة تشغيل الهاتف أو تحديث حزمة التطبيق،
 * ويرفع السجلات غير المتزامنة فقط دون إعادة رفع ما سبق مزامنته.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        ReceiverScope.scope.launch {
            try {
                withTimeoutOrNull(8_500L) {
                    CloudInit.configure(appCtx)
                    Prefs.ensureInit(appCtx)
                    LiveContentObservers.register(appCtx)
                    Scheduler.ensureBackgroundSchedule(appCtx)
                    if (Prefs.onboardingDone.value && !Prefs.paused.value) {
                        val dao = AppDb.get(appCtx).dao()
                        Engine.scan(appCtx, dao)
                        val uid = Engine.activeUidOrEmpty(appCtx)
                        if (uid.isNotBlank() && dao.pendingOrFailedNowForUser(uid) > 0) {
                            Scheduler.enqueueImmediateUpload(appCtx, expedited = false)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * نشاط شفاف خفيف يُفتح من إشعار الحفظ السريع على أندرويد 10+ لقراءة الحافظة بطريقة رسمية آمنة
 * فور اكتساب النافذة للتركيز ثم يُغلق تلقائيًا في جزء من الثانية.
 */
class QuickClipSaveActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        lifecycleScope.launch {
            delay(1_500L)
            if (!handled && !isFinishing) {
                captureAndFinish()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            captureAndFinish()
        }
    }

    private fun captureAndFinish() {
        if (handled) return
        handled = true
        val appCtx = applicationContext
        val text = ClipSaver.readCurrentClipboardText(this)
        lifecycleScope.launch {
            val res = ClipSaver.save(appCtx, text, scheduleUpload = true)
            Toast.makeText(appCtx, clipMessage(res, appCtx), Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
        }
    }
}

private object ReceiverScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
