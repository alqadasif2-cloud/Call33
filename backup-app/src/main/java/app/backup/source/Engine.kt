package app.backup.source

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.backup.core.CloudInit
import app.backup.core.CloudRepo
import app.backup.core.EncRecord
import app.backup.core.Net
import app.backup.core.PlainRecord
import app.backup.core.RecordCodec
import app.backup.core.RecordType
import app.backup.core.Session
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit

object Perms {
    fun forType(t: RecordType): String? = when (t) {
        RecordType.CALL -> Manifest.permission.READ_CALL_LOG
        RecordType.SMS -> Manifest.permission.READ_SMS
        RecordType.CLIP -> null
    }

    fun permissionsForType(t: RecordType): List<String> = when (t) {
        RecordType.CALL -> listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
        )
        RecordType.SMS -> listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
        )
        RecordType.CLIP -> emptyList()
    }

    fun onboardingPermissions(calls: Boolean, sms: Boolean, includeNotifications: Boolean = true): List<String> =
        buildList {
            if (calls) {
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (sms) {
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.RECEIVE_SMS)
            }
            if (includeNotifications && Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    fun granted(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canReadCallLog(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_CALL_LOG)
    fun canDetectPhoneState(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_PHONE_STATE)
    fun canReadSmsProvider(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_SMS)
    fun canReceiveIncomingSms(ctx: Context): Boolean = granted(ctx, Manifest.permission.RECEIVE_SMS)

    fun typeAllowed(ctx: Context, t: RecordType): Boolean = when (t) {
        RecordType.CALL -> canReadCallLog(ctx)
        RecordType.SMS -> canReadSmsProvider(ctx) || canReceiveIncomingSms(ctx)
        RecordType.CLIP -> true
    }
}

data class BackgroundHealthStatus(
    val isBackgroundRestricted: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val summaryAr: String,
    val guidanceAr: String?,
)

object BackgroundHealth {
    fun inspect(ctx: Context): BackgroundHealthStatus {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am?.isBackgroundRestricted ?: false }.getOrDefault(false)
        } else {
            false
        }
        val ignoringDoze = runCatching {
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        }.getOrDefault(false)

        return when {
            restricted -> BackgroundHealthStatus(
                isBackgroundRestricted = true,
                isIgnoringBatteryOptimizations = ignoringDoze,
                summaryAr = "مقيّد في الخلفية من إعدادات النظام",
                guidanceAr = "قام النظام بتقييد عمل التطبيق في الخلفية، مما قد يؤخّر أو يمنع التقاط السجلات ورفعها وهو مغلق. افتح إعدادات التطبيق ← البطارية ← اختر «غير مقيّد / Unrestricted».",
            )
            !ignoringDoze -> BackgroundHealthStatus(
                isBackgroundRestricted = false,
                isIgnoringBatteryOptimizations = false,
                summaryAr = "نشط (مع تحسين بطارية قياسي قد يؤخّر الرفع أثناء السكون)",
                guidanceAr = "لضمان عدم تأخير المزامنة على واجهات Samsung One UI وXiaomi وغيرها أثناء قفل الشاشة، يُستحسن ضبط بطارية التطبيق على «غير مقيّد».",
            )
            else -> BackgroundHealthStatus(
                isBackgroundRestricted = false,
                isIgnoringBatteryOptimizations = true,
                summaryAr = "نشط بالكامل في الخلفية (دون قيود بطارية)",
                guidanceAr = null,
            )
        }
    }

    fun appDetailsSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ctx.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

interface CloudUploader {
    suspend fun registerDevice(deviceId: String, name: String, model: String, enabled: List<String>)
    suspend fun upload(deviceId: String, recs: List<EncRecord>)
    suspend fun markSynced(deviceId: String, at: Long)
}

class FirestoreCloudUploader(private val repo: CloudRepo) : CloudUploader {
    override suspend fun registerDevice(deviceId: String, name: String, model: String, enabled: List<String>) =
        repo.registerDevice(deviceId, name, model, enabled)

    override suspend fun upload(deviceId: String, recs: List<EncRecord>) =
        repo.upload(deviceId, recs)

    override suspend fun markSynced(deviceId: String, at: Long) =
        repo.markSynced(deviceId, at)
}

object SyncErrorClassifier {
    fun classify(e: Throwable): Pair<String, String> = when {
        e is TimeoutCancellationException ->
            "انتهت مهلة الاتصال بالخادم أثناء الرفع" to
                "تأكد من استقرار شبكة الإنترنت؛ سيعيد WorkManager محاولة رفع السجلات المتبقية تلقائيًا."
        e is FirebaseNetworkException || e is IOException ->
            "تعذّر الوصول إلى السحابة بسبب انقطاع الإنترنت" to
                "جميع السجلات محفوظة محليًا في Room وستُرفع تلقائيًا فور عودة الاتصال."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "الخدمة السحابية غير متاحة مؤقتًا أو الشبكة ضعيفة" to
                "تبقى السجلات في قائمة الانتظار المحلية وسيُعاد رفعها تلقائيًا بتأخير متزايد."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "رفضت قواعد أمان Firebase حفظ الدفعة" to
                "افتح التطبيق وسجّل الخروج ثم أعد تسجيل الدخول لتحديث جلسة المصادقة، ثم اضغط «إعادة محاولة الفاشل»."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            "انتهت صلاحية جلسة المصادقة السحابية" to
                "افتح التطبيق وأعد تسجيل الدخول بحسابك لاستئناف المزامنة."
        e is FirebaseAuthRecentLoginRequiredException ->
            "يلزم تحديث تسجيل الدخول للحساب" to
                "سجّل الخروج ثم الدخول مجددًا داخل التطبيق."
        e is FirebaseTooManyRequestsException ||
            (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED) ->
            "تم تجاوز حد الطلبات المؤقت في السحابة" to
                "انتظر قليلًا؛ سيعيد التطبيق المحاولة تلقائيًا دون ضياع أي سجل."
        else ->
            "تعذّر رفع بعض السجلات (${e.javaClass.simpleName})" to
                "تحقق من اتصال الإنترنت، أو اضغط «إعادة محاولة السجلات الفاشلة» لإعادة إرسالها فقط."
    }
}

sealed interface SyncResult {
    data class Uploaded(val uploadedCount: Int) : SyncResult
    data object NothingToUpload : SyncResult
    data class PartialFailure(val uploadedCount: Int, val failedCount: Int, val reason: String) : SyncResult
    data object Offline : SyncResult
    data object NeedsLogin : SyncResult
    data object Paused : SyncResult
    data class Failed(val failedCount: Int, val reason: String) : SyncResult
}

object Engine {
    fun activeUidOrEmpty(ctx: Context): String =
        runCatching { Session.ensureLoaded(ctx.applicationContext)?.first.orEmpty() }.getOrDefault("")

    /**
     * يحفظ السجلات الصالحة غير المكررة في Room فورًا مع الحفاظ على توقيتها الأصلي.
     * يعيد عدد السجلات الجديدة التي أُدرجت فعليًا.
     */
    suspend fun storeLocal(
        dao: RecordDao,
        batch: List<PlainRecord>,
        uid: String = "",
        pausedOrUnauthenticated: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val valid = batch.filter { Validator.ok(it) }
        if (valid.isEmpty()) return 0

        val smsItems = valid.filter { it.type == RecordType.SMS && it.fields.optString("type") == "inbox" }
        val filtered = if (smsItems.isNotEmpty()) {
            val minTs = smsItems.minOf { minOf(it.ts, it.fields.optLong("dateSent", it.ts).let { d -> if (d > 0L) d else it.ts }) }
            val maxTs = smsItems.maxOf { maxOf(it.ts, it.fields.optLong("dateSent", it.ts)) }
            val existingWindow = dao.smsInWindow(
                uid = uid,
                fromTs = (minTs - SmsDeduplicator.WINDOW_MS).coerceAtLeast(0L),
                toTs = maxTs + SmsDeduplicator.WINDOW_MS,
            )
            SmsDeduplicator.filterProviderDuplicates(valid, existingWindow)
        } else {
            valid
        }

        if (filtered.isEmpty()) return 0
        val initialState = if (pausedOrUnauthenticated) SyncState.LOCAL_ONLY else SyncState.PENDING
        val rows = filtered.map {
            RecordEntity(
                id = it.id,
                type = it.type.id,
                ts = it.ts,
                json = it.fields.toString(),
                syncState = initialState,
                createdAt = nowMs,
                ownerUid = uid,
                updatedAt = nowMs,
            )
        }
        return dao.insertAll(rows).count { it != -1L }
    }

    /** توافق مع الاستدعاءات والاختبارات المباشرة. */
    suspend fun store(dao: RecordDao, batch: List<PlainRecord>): Int =
        storeLocal(dao = dao, batch = batch, uid = "", pausedOrUnauthenticated = false)

    /** يحفظ السجلات محليًا ثم يجدول رفعها تلقائيًا دون انتظار فتح التطبيق. */
    suspend fun storeAndQueue(
        ctx: Context,
        dao: RecordDao,
        batch: List<PlainRecord>,
        uid: String = activeUidOrEmpty(ctx),
        scheduleUpload: Boolean = true,
    ): Int {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        if (uid.isNotBlank() && Prefs.isPrimaryOwner(uid)) {
            dao.claimLegacyUnownedRecords(uid)
        }
        val holdLocally = Prefs.paused.value || uid.isBlank()
        val inserted = storeLocal(
            dao = dao,
            batch = batch,
            uid = uid,
            pausedOrUnauthenticated = holdLocally,
        )
        if (!holdLocally && scheduleUpload) {
            dao.promoteLocalToPending(uid)
            if (inserted > 0 || dao.pendingOrFailedNowForUser(uid) > 0) {
                Scheduler.enqueueImmediateUpload(appCtx)
            }
        }
        return inserted
    }

    /** يُستدعى فور وصول بث SMS_RECEIVED لحفظ الرسالة بتوقيتها الأصلي وجدولة رفعها حتى والتطبيق مغلق. */
    suspend fun captureIncomingSms(
        ctx: Context,
        address: String,
        body: String,
        pduTimestampMs: Long,
    ): Int {
        val appCtx = ctx.applicationContext
        CloudInit.configure(appCtx)
        Prefs.ensureInit(appCtx)
        if (!Prefs.onboardingDone.value) return 0
        if (RecordType.SMS.id !in Prefs.enabled.value) return 0

        val uid = activeUidOrEmpty(appCtx)
        if (uid.isBlank()) return 0

        val cleanAddr = address.trim()
        if (cleanAddr.isEmpty() || body.isEmpty()) return 0
        val originalTs = pduTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val deviceId = Prefs.deviceIdForUser(uid)
        val rec = Rec.sms(
            deviceId = deviceId,
            address = cleanAddr,
            body = body,
            ts = originalTs,
            type = "inbox",
            dateSent = originalTs,
            fromPdu = true,
        )
        val added = storeAndQueue(
            ctx = appCtx,
            dao = AppDb.get(appCtx).dao(),
            batch = listOf(rec),
            uid = uid,
            scheduleUpload = true,
        )
        Prefs.setLastScan(System.currentTimeMillis())
        return added
    }

    /** فحص المزوّدات المسموح بها وحفظ أي مكالمات أو رسائل جديدة فورًا في Room. */
    suspend fun scan(ctx: Context, dao: RecordDao): Int {
        val appCtx = ctx.applicationContext
        CloudInit.configure(appCtx)
        Prefs.ensureInit(appCtx)
        if (!Prefs.onboardingDone.value) return 0

        val uid = activeUidOrEmpty(appCtx)
        if (uid.isBlank()) return 0
        if (Prefs.isPrimaryOwner(uid)) {
            dao.claimLegacyUnownedRecords(uid)
        }

        val deviceId = Prefs.deviceIdForUser(uid)
        val en = Prefs.enabled.value
        val holdLocally = Prefs.paused.value
        var added = 0
        val notes = mutableListOf<String>()

        if (RecordType.CALL.id in en) {
            if (!Perms.canReadCallLog(appCtx)) {
                notes += "صلاحية قراءة سجل المكالمات غير ممنوحة"
            } else {
                try {
                    var max = Prefs.cursor("call", uid)
                    CallLogCollector.collect(appCtx, deviceId, max) { b ->
                        added += storeLocal(dao, b, uid = uid, pausedOrUnauthenticated = holdLocally)
                        max = maxOf(max, b.maxOf { it.ts })
                    }
                    Prefs.setCursor("call", max, uid)
                } catch (_: SecurityException) {
                    notes += "منع النظام الوصول إلى سجل المكالمات (تحقق من الصلاحيات أو الإعدادات المقيّدة)"
                }
            }
        }

        if (RecordType.SMS.id in en) {
            if (!Perms.canReadSmsProvider(appCtx)) {
                if (!Perms.canReceiveIncomingSms(appCtx)) {
                    notes += "صلاحية الرسائل النصية غير ممنوحة"
                } else {
                    notes += "صلاحية قراءة أرشيف الرسائل الصادرة غير ممنوحة (استقبال الوارد فقط مفعّل)"
                }
            } else {
                try {
                    var max = Prefs.cursor("sms", uid)
                    SmsCollector.collect(appCtx, deviceId, max) { b ->
                        added += storeLocal(dao, b, uid = uid, pausedOrUnauthenticated = holdLocally)
                        max = maxOf(max, b.maxOf { it.ts })
                    }
                    Prefs.setCursor("sms", max, uid)
                } catch (_: SecurityException) {
                    notes += "منع النظام الوصول إلى الرسائل النصية (تحقق من الإعدادات المقيّدة في أندرويد 13+)"
                }
            }
        }

        if (!holdLocally) {
            dao.promoteLocalToPending(uid)
        }
        Prefs.notice.value = notes.joinToString(" • ").ifEmpty { null }
        Prefs.setLastScan(System.currentTimeMillis())
        return added
    }

    /**
     * تنفيذ الرفع المشفّر على دفعات مع عزل الأخطاء الجزئية:
     * - لا يُعلَّم أي سجل كـ SYNCED إلا بعد تأكيد الخادم.
     * - عند فشل دفعة جزئيًا يُعاد رفع كل سجل منفردًا بحيث تُحفظ السجلات الناجحة وتُعلَّم الفاشلة فقط كـ FAILED.
     * - لا يُحدَّث وقت «آخر مزامنة ناجحة» إذا لم يُرفع أي سجل فعليًا.
     */
    suspend fun uploadRecords(
        dao: RecordDao,
        uid: String,
        key: ByteArray,
        deviceId: String,
        deviceName: String,
        deviceModel: String,
        enabledTypes: List<String>,
        uploader: CloudUploader,
        isOnline: () -> Boolean,
        batchTimeoutMs: Long = 60_000L,
        onErrorRecorded: (String, String) -> Unit = { _, _ -> },
        onErrorCleared: () -> Unit = {},
        onLastSyncUpdated: (Long) -> Unit = {},
    ): SyncResult {
        if (!isOnline()) return SyncResult.Offline
        dao.promoteLocalToPending(uid)

        val initialPending = dao.pendingForUser(uid, 100)
        if (initialPending.isEmpty()) {
            onErrorCleared()
            return SyncResult.NothingToUpload
        }

        try {
            withTimeout(batchTimeoutMs) {
                uploader.registerDevice(deviceId, deviceName, deviceModel, enabledTypes)
            }
        } catch (e: TimeoutCancellationException) {
            val (summary, fix) = SyncErrorClassifier.classify(e)
            onErrorRecorded(summary, fix)
            return SyncResult.Failed(initialPending.size, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isOnline()) return SyncResult.Offline
            val (summary, fix) = SyncErrorClassifier.classify(e)
            onErrorRecorded(summary, fix)
            return SyncResult.Failed(initialPending.size, summary)
        }

        var uploadedCount = 0
        var failedCount = 0
        var lastFailure: Pair<String, String>? = null
        val seenFailedIds = HashSet<String>()

        while (true) {
            if (!isOnline()) break
            val candidates = dao.pendingForUser(uid, 150)
                .filter { it.id !in seenFailedIds }
                .take(100)
            if (candidates.isEmpty()) break

            val validPairs = ArrayList<Pair<RecordEntity, EncRecord>>(candidates.size)
            for (row in candidates) {
                try {
                    validPairs += row to RecordCodec.encrypt(key, row.toPlain(deviceId))
                } catch (e: Exception) {
                    val reason = "سجل محلي تالف تعذّر تشفيره (${e.javaClass.simpleName})"
                    dao.markFailed(listOf(row.id), reason)
                    seenFailedIds += row.id
                    failedCount++
                    lastFailure = reason to "يمكنك حذف السجل التالف أو استيراد نسخة سليمة."
                }
            }
            if (validPairs.isEmpty()) continue

            try {
                withTimeout(batchTimeoutMs) {
                    uploader.upload(deviceId, validPairs.map { it.second })
                }
                dao.markSynced(validPairs.map { it.first.id })
                uploadedCount += validPairs.size
            } catch (e: TimeoutCancellationException) {
                if (!isOnline()) break
                val (summary, fix) = SyncErrorClassifier.classify(e)
                lastFailure = summary to fix
                dao.markFailed(validPairs.map { it.first.id }, summary)
                seenFailedIds.addAll(validPairs.map { it.first.id })
                failedCount += validPairs.size
            } catch (e: CancellationException) {
                throw e
            } catch (batchErr: Exception) {
                if (!isOnline()) break
                if (validPairs.size == 1) {
                    val (summary, fix) = SyncErrorClassifier.classify(batchErr)
                    lastFailure = summary to fix
                    val id = validPairs[0].first.id
                    dao.markFailed(listOf(id), summary)
                    seenFailedIds += id
                    failedCount++
                } else {
                    // عزل السجلات الفاشلة فقط وحفظ السجلات السليمة في السحابة دون إعادتها لاحقًا
                    for ((row, enc) in validPairs) {
                        if (!isOnline()) break
                        try {
                            withTimeout(batchTimeoutMs) {
                                uploader.upload(deviceId, listOf(enc))
                            }
                            dao.markSynced(listOf(row.id))
                            uploadedCount++
                        } catch (e: TimeoutCancellationException) {
                            val (summary, fix) = SyncErrorClassifier.classify(e)
                            lastFailure = summary to fix
                            dao.markFailed(listOf(row.id), summary)
                            seenFailedIds += row.id
                            failedCount++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (itemErr: Exception) {
                            val (summary, fix) = SyncErrorClassifier.classify(itemErr)
                            lastFailure = summary to fix
                            dao.markFailed(listOf(row.id), summary)
                            seenFailedIds += row.id
                            failedCount++
                        }
                    }
                }
            }
        }

        if (uploadedCount > 0) {
            val now = System.currentTimeMillis()
            runCatching { uploader.markSynced(deviceId, now) }
            onLastSyncUpdated(now)
        }

        val remainingUnsynced = dao.pendingOrFailedNowForUser(uid)
        return when {
            failedCount == 0 && remainingUnsynced == 0 -> {
                onErrorCleared()
                SyncResult.Uploaded(uploadedCount)
            }
            !isOnline() && failedCount == 0 -> {
                onErrorRecorded(
                    "انقطع الاتصال بالإنترنت أثناء الرفع",
                    "تم تأكيد السجلات المرفوعة ولن تتكرر، وستُستأنف السجلات المتبقية تلقائيًا عند عودة الاتصال.",
                )
                if (uploadedCount > 0) {
                    SyncResult.PartialFailure(uploadedCount, remainingUnsynced, "انقطع الاتصال أثناء الرفع")
                } else {
                    SyncResult.Offline
                }
            }
            else -> {
                val (summary, fix) = lastFailure
                    ?: ("فشل رفع بعض السجلات" to "سيُعاد رفع السجلات الفاشلة فقط تلقائيًا.")
                onErrorRecorded(summary, fix)
                if (uploadedCount > 0) {
                    SyncResult.PartialFailure(uploadedCount, failedCount.coerceAtLeast(remainingUnsynced), summary)
                } else {
                    SyncResult.Failed(failedCount.coerceAtLeast(remainingUnsynced), summary)
                }
            }
        }
    }

    /** نقطة الدخول الرسمية للرفع في بيئة أندرويد. */
    suspend fun upload(ctx: Context, dao: RecordDao): SyncResult {
        val appCtx = ctx.applicationContext
        CloudInit.configure(appCtx)
        Prefs.ensureInit(appCtx)
        if (Prefs.paused.value) return SyncResult.Paused

        val (activeUid, key) = Session.ensureLoaded(appCtx) ?: return SyncResult.NeedsLogin
        if (Prefs.isPrimaryOwner(activeUid)) {
            dao.claimLegacyUnownedRecords(activeUid)
        }
        val deviceId = Prefs.deviceIdForUser(activeUid)
        val uploader = FirestoreCloudUploader(CloudRepo(explicitUid = activeUid))

        return uploadRecords(
            dao = dao,
            uid = activeUid,
            key = key,
            deviceId = deviceId,
            deviceName = Prefs.deviceName.value,
            deviceModel = Build.MODEL,
            enabledTypes = Prefs.enabled.value.toList(),
            uploader = uploader,
            isOnline = { Net.isOnline(appCtx) },
            onErrorRecorded = { summary, fix -> Prefs.recordSyncError(summary, fix) },
            onErrorCleared = { Prefs.clearSyncError() },
            onLastSyncUpdated = { now -> Prefs.setLastSync(now) },
        )
    }
}

private fun buildSyncForegroundInfo(ctx: Context, title: String): ForegroundInfo {
    NotificationChannels.ensureCreated(ctx)
    val notification = NotificationCompat.Builder(ctx, NotificationChannels.SYNC_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(title)
        .setContentText("تُحفظ السجلات الجديدة وتُرفع مشفّرةً إلى حسابك")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            NotificationChannels.SYNC_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    } else {
        ForegroundInfo(NotificationChannels.SYNC_NOTIFICATION_ID, notification)
    }
}

/** عامل فحص محلي فوري يعمل حتى دون إنترنت لحفظ المكالمات والرسائل في Room فورًا. */
class CaptureScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildSyncForegroundInfo(applicationContext, "جاري حفظ السجلات الجديدة محليًا")

    override suspend fun doWork(): Result {
        CloudInit.configure(applicationContext)
        Prefs.ensureInit(applicationContext)
        if (!Prefs.onboardingDone.value) return Result.success()

        val dao = AppDb.get(applicationContext).dao()
        Engine.scan(applicationContext, dao)
        val uid = Engine.activeUidOrEmpty(applicationContext)
        if (!Prefs.paused.value && uid.isNotBlank() && dao.pendingOrFailedNowForUser(uid) > 0) {
            Scheduler.enqueueImmediateUpload(applicationContext)
        }
        return Result.success()
    }
}

/** عامل مراقبة تغييرات مزوّدات CallLog وSms عبر JobScheduler حتى والتطبيق مغلق تمامًا. */
class ContentTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CloudInit.configure(applicationContext)
        Prefs.ensureInit(applicationContext)
        if (!Prefs.onboardingDone.value) return Result.success()

        try {
            val dao = AppDb.get(applicationContext).dao()
            Engine.scan(applicationContext, dao)
            val uid = Engine.activeUidOrEmpty(applicationContext)
            if (!Prefs.paused.value && uid.isNotBlank() && dao.pendingOrFailedNowForUser(uid) > 0) {
                Scheduler.enqueueImmediateUpload(applicationContext)
            }
        } finally {
            if (Prefs.onboardingDone.value && !Prefs.paused.value) {
                Scheduler.rearmContentTriggersNext(applicationContext)
            }
        }
        return Result.success()
    }
}

/** عامل الرفع المشروط بتوفر الإنترنت مع إعادة محاولة تلقائية بتأخير أُسّي متزايد. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildSyncForegroundInfo(applicationContext, "جاري مزامنة النسخة الاحتياطية المشفّرة")

    override suspend fun doWork(): Result {
        CloudInit.configure(applicationContext)
        Prefs.ensureInit(applicationContext)
        if (!Prefs.onboardingDone.value || Prefs.paused.value) return Result.success()

        Prefs.syncInFlight.value = true
        try {
            val dao = AppDb.get(applicationContext).dao()
            Engine.scan(applicationContext, dao)
            var result = Engine.upload(applicationContext, dao)
            val uid = Engine.activeUidOrEmpty(applicationContext)
            if (
                result is SyncResult.Uploaded &&
                uid.isNotBlank() &&
                dao.pendingOrFailedNowForUser(uid) > 0 &&
                Net.isOnline(applicationContext)
            ) {
                result = Engine.upload(applicationContext, dao)
            }

            return when (result) {
                is SyncResult.Uploaded,
                SyncResult.NothingToUpload,
                SyncResult.NeedsLogin,
                SyncResult.Paused -> Result.success()
                SyncResult.Offline,
                is SyncResult.PartialFailure,
                is SyncResult.Failed -> Result.retry()
            }
        } finally {
            Prefs.syncInFlight.value = false
        }
    }
}

/** العامل الدوري الاحتياطي (كل 15 دقيقة) مع الإبقاء على الاسم للتوافق مع المهام المجدولة مسبقًا. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildSyncForegroundInfo(applicationContext, "فحص النسخ الاحتياطي الدوري")

    override suspend fun doWork(): Result {
        CloudInit.configure(applicationContext)
        Prefs.ensureInit(applicationContext)
        if (!Prefs.onboardingDone.value || Prefs.paused.value) return Result.success()

        val dao = AppDb.get(applicationContext).dao()
        Engine.scan(applicationContext, dao)
        val uid = Engine.activeUidOrEmpty(applicationContext)
        if (uid.isBlank()) return Result.success()

        if (dao.pendingOrFailedNowForUser(uid) > 0) {
            if (Net.isOnline(applicationContext)) {
                return when (Engine.upload(applicationContext, dao)) {
                    is SyncResult.Uploaded,
                    SyncResult.NothingToUpload,
                    SyncResult.NeedsLogin,
                    SyncResult.Paused -> Result.success()
                    SyncResult.Offline -> {
                        Scheduler.enqueueImmediateUpload(applicationContext, expedited = false)
                        Result.success()
                    }
                    is SyncResult.PartialFailure,
                    is SyncResult.Failed -> Result.retry()
                }
            } else {
                Scheduler.enqueueImmediateUpload(applicationContext, expedited = false)
            }
        }
        return Result.success()
    }
}

object Scheduler {
    private const val WORK_PERIODIC = "periodic"
    private const val WORK_CONTENT_TRIGGER = "content_uri_watcher"
    private const val WORK_IMMEDIATE_SCAN = "immediate_scan"
    private const val WORK_UPLOAD_QUEUE = "upload_queue"
    private const val WORK_MANUAL_SYNC = "manual_sync_now"

    private val connectedConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun buildContentTriggerRequest(): OneTimeWorkRequest? {
        val en = Prefs.enabled.value
        val watchCalls = RecordType.CALL.id in en
        val watchSms = RecordType.SMS.id in en
        if (!watchCalls && !watchSms) return null

        val constraintsBuilder = Constraints.Builder()
        if (watchCalls) {
            constraintsBuilder.addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
        }
        if (watchSms) {
            constraintsBuilder.addContentUriTrigger(Telephony.Sms.CONTENT_URI, true)
        }
        constraintsBuilder
            .setTriggerContentUpdateDelay(1_500L, TimeUnit.MILLISECONDS)
            .setTriggerContentMaxDelay(10L, TimeUnit.SECONDS)

        return OneTimeWorkRequestBuilder<ContentTriggerWorker>()
            .setConstraints(constraintsBuilder.build())
            .build()
    }

    /** يضمن استمرار الجدولة الخلفية ومراقبة المزوّدات بعد التثبيت أو فتح التطبيق أو إعادة تشغيل الهاتف. */
    fun ensureBackgroundSchedule(ctx: Context) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        val wm = WorkManager.getInstance(appCtx)
        if (!Prefs.onboardingDone.value || Prefs.paused.value) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_CONTENT_TRIGGER)
            QuickClipNotification.sync(appCtx)
            return
        }

        // العمل الدوري يعمل بدون قيد إنترنت ليحفظ السجلات محليًا في Room حتى أثناء الانقطاع
        val periodicReq = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicReq)

        val triggerReq = buildContentTriggerRequest()
        if (triggerReq != null) {
            wm.enqueueUniqueWork(WORK_CONTENT_TRIGGER, ExistingWorkPolicy.KEEP, triggerReq)
        } else {
            wm.cancelUniqueWork(WORK_CONTENT_TRIGGER)
        }

        QuickClipNotification.sync(appCtx)
    }

    /** تحديث مراقب ContentUri عند تغيير الأنواع المفعّلة من الواجهة. */
    fun refreshContentTriggers(ctx: Context) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        val wm = WorkManager.getInstance(appCtx)
        val triggerReq = if (Prefs.onboardingDone.value && !Prefs.paused.value) {
            buildContentTriggerRequest()
        } else {
            null
        }
        if (triggerReq != null) {
            wm.enqueueUniqueWork(WORK_CONTENT_TRIGGER, ExistingWorkPolicy.REPLACE, triggerReq)
        } else {
            wm.cancelUniqueWork(WORK_CONTENT_TRIGGER)
        }
    }

    /** يُستدعى من داخل ContentTriggerWorker لإعادة التسليح فور انتهاء التنفيذ الحالي دون إلغاء الذات. */
    fun rearmContentTriggersNext(ctx: Context) {
        val appCtx = ctx.applicationContext
        val triggerReq = buildContentTriggerRequest() ?: return
        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(WORK_CONTENT_TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, triggerReq)
    }

    /** يجدول مهمة رفع مشروطة بالإنترنت مع تأخير متزايد عند الفشل؛ تعمل فورًا عند توفر الشبكة. */
    fun enqueueImmediateUpload(ctx: Context, expedited: Boolean = true) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        if (Prefs.paused.value) return

        val builder = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connectedConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)

        if (expedited && Net.isOnline(appCtx)) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(WORK_UPLOAD_QUEUE, ExistingWorkPolicy.KEEP, builder.build())
    }

    /** فحص محلي فوري يتبعه رفع تلقائي (مثلاً بعد منح الصلاحيات أو اكتمال الإعداد الأول). */
    fun triggerImmediateScanAndUpload(ctx: Context) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        if (Prefs.paused.value) return

        val scanReq = OneTimeWorkRequestBuilder<CaptureScanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(WORK_IMMEDIATE_SCAN, ExistingWorkPolicy.REPLACE, scanReq)

        enqueueImmediateUpload(appCtx, expedited = true)
    }

    /** جدولة فحص متأخر بالثواني لالتقاط السجلات التي يتأخر نظام الاتصال في كتابتها بعد انتهاء المكالمة أو الرسالة. */
    fun enqueueDelayedScan(ctx: Context, uniqueName: String, delayMs: Long) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        if (!Prefs.onboardingDone.value) return

        val req = OneTimeWorkRequestBuilder<CaptureScanWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, req)
    }

    /** توافق مع الأزرار والاستدعاءات اليدوية: يبدأ فحصًا ورفعًا فوريًا قابلًا لإعادة المحاولة. */
    fun start(ctx: Context) = ensureBackgroundSchedule(ctx)

    fun syncNow(ctx: Context) {
        val appCtx = ctx.applicationContext
        Prefs.ensureInit(appCtx)
        if (Prefs.paused.value) return

        val scanReq = OneTimeWorkRequestBuilder<CaptureScanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(WORK_IMMEDIATE_SCAN, ExistingWorkPolicy.REPLACE, scanReq)

        val uploadBuilder = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connectedConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (Net.isOnline(appCtx)) {
            uploadBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(WORK_MANUAL_SYNC, ExistingWorkPolicy.REPLACE, uploadBuilder.build())
    }

    fun enqueueResume(ctx: Context) = enqueueImmediateUpload(ctx, expedited = false)

    fun stop(ctx: Context) {
        val appCtx = ctx.applicationContext
        WorkManager.getInstance(appCtx).cancelAllWork()
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.cancel(NotificationChannels.QUICK_CLIP_NOTIFICATION_ID)
    }
}
