package app.backup.source

import app.backup.core.Crypto
import app.backup.core.EncRecord
import app.backup.core.RecordCodec
import app.backup.core.RecordType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * اختبارات تكاملية آلية شاملة تغطي جميع السيناريوهات التسعة المطلوبة للمزامنة الخلفية
 * والتقاط المكالمات والرسائل والحافظة وعزل الفشل الجزئي واستعادة الاتصال ومنع التكرار.
 */
class SyncScenariosTest {

    private val testVaultKey = ByteArray(32) { (it + 7).toByte() }
    private val userA = "uid_user_alpha"
    private val userB = "uid_user_beta"
    private val devA = "dev_phone_alpha"

    // -------------------------------------------------------------------------
    // السيناريو 1: وصول رسالة نصية والتطبيق مغلق + منع الازدواج بين PDU ومزوّد النظام
    // -------------------------------------------------------------------------
    @Test
    fun scenario1_incomingSmsClosedApp_pduCapturedWithOriginalTimeAndProviderDeduplicated() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()
        val pduTime = 1_715_000_100_000L

        // 1) وصول بث SMS_RECEIVED والتطبيق مغلق (رقم بصيغة دولية +966)
        val pduSms = Rec.sms(
            deviceId = devA,
            address = "+966501234567",
            body = "مرحبا، موعد الاجتماع الساعة 4 عصرًا",
            ts = pduTime,
            type = "inbox",
            dateSent = pduTime,
            fromPdu = true,
        )
        val insertedPdu = Engine.storeLocal(dao, listOf(pduSms), uid = userA)
        assertEquals(1, insertedPdu)

        // 2) بعد 3 ثوانٍ يكتب تطبيق الرسائل الافتراضي نفس الرسالة في Telephony.Sms بصيغة رقم محلية وتوقيت محلي مختلف
        val providerSms = Rec.sms(
            deviceId = devA,
            address = "0501234567",
            body = "مرحبا، موعد الاجتماع الساعة 4 عصرًا",
            ts = pduTime + 3_200L,
            type = "inbox",
            dateSent = pduTime,
            fromPdu = false,
        )
        val insertedProvider = Engine.storeLocal(dao, listOf(providerSms), uid = userA)
        assertEquals("يجب منع تكرار نفس الرسالة عند قراءتها لاحقًا من المزوّد", 0, insertedProvider)
        assertEquals(1, dao.totalForUser(userA).first())

        // 3) التحقق من الاحتفاظ بالتوقيت الأصلي للرسالة ورفعها مشفّرةً إلى السحابة
        val res = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Pixel",
            deviceModel = "Pixel 8",
            enabledTypes = listOf("call", "sms", "clip"),
            uploader = cloud,
            isOnline = { true },
        )
        assertEquals(SyncResult.Uploaded(1), res)
        assertEquals(1, cloud.serverDocsForDevice(devA).size)
        val decrypted = RecordCodec.decrypt(testVaultKey, devA, cloud.serverDocsForDevice(devA).single())
        assertEquals(pduTime, decrypted.ts)
        assertEquals("مرحبا، موعد الاجتماع الساعة 4 عصرًا", decrypted.fields.getString("body"))
    }

    // -------------------------------------------------------------------------
    // السيناريو 2: تسجيل مكالمات واردة وصادرة وفائتة والتطبيق مغلق + نافذة الرجوع للمكالمات الطويلة
    // -------------------------------------------------------------------------
    @Test
    fun scenario2_callsCapturedClosedApp_incomingOutgoingMissedAndLongCallLookback() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()
        val t10_00 = 1_715_010_000_000L
        val t10_05 = t10_00 + 5 * 60_000L

        // مكالمة فائتة قصيرة عند 10:05 تقدّم المؤشر قبل انتهاء مكالمة طويلة بدأت 10:00
        val missedCall = Rec.call(devA, "+966550001111", t10_05, "missed", 0L, null)
        assertEquals(1, Engine.storeLocal(dao, listOf(missedCall), uid = userA))

        // عند 10:25 تنتهي المكالمة الصادرة الطويلة التي بدأ توقيتها عند 10:00 (ضمن نافذة الرجوع 6 ساعات)
        val querySince = (t10_05 - CallLogCollector.LOOKBACK_MS).coerceAtLeast(0L)
        assertTrue("نافذة الرجوع تغطي بداية المكالمة الطويلة", t10_00 >= querySince)

        val longOutgoingCall = Rec.call(devA, "+966550002222", t10_00, "outgoing", 1500L, "خالد")
        val incomingCall = Rec.call(devA, "+966550003333", t10_05 + 60_000L, "incoming", 95L, "سارة")

        // إعادة تمرير المكالمة الفائتة مع المكالمتين الجديدتين لا تكرر الفائتة وتلتقط الجديدتين
        val added = Engine.storeLocal(dao, listOf(longOutgoingCall, missedCall, incomingCall), uid = userA)
        assertEquals(2, added)
        assertEquals(3, dao.totalForUser(userA).first())

        val uploadRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Galaxy",
            deviceModel = "SM-S928B",
            enabledTypes = listOf("call"),
            uploader = cloud,
            isOnline = { true },
        )
        assertEquals(SyncResult.Uploaded(3), uploadRes)
        assertEquals(3, dao.countByStateForUser(userA, SyncState.SYNCED).first())
    }

    // -------------------------------------------------------------------------
    // السيناريو 3: الحفظ محليًا بدون إنترنت ثم الرفع التلقائي فور عودة الاتصال
    // -------------------------------------------------------------------------
    @Test
    fun scenario3_offlineQueueingThenAutomaticUploadOnReconnect() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()
        var online = false
        var recordedSyncTimestamp = 0L

        val r1 = Rec.call(devA, "0501112222", 1_715_020_000_000L, "incoming", 42L, "أحمد")
        val r2 = Rec.sms(devA, "0501112222", "سأتصل بك لاحقًا", 1_715_020_050_000L, "inbox")
        assertEquals(2, Engine.storeLocal(dao, listOf(r1, r2), uid = userA))
        assertEquals(2, dao.countByStateForUser(userA, SyncState.PENDING).first())

        // محاولة الرفع بدون إنترنت: لا تضيع السجلات ولا يُزعم نجاح المزامنة
        val offlineRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "Model",
            enabledTypes = listOf("call", "sms"),
            uploader = cloud,
            isOnline = { online },
            onLastSyncUpdated = { recordedSyncTimestamp = it },
        )
        assertEquals(SyncResult.Offline, offlineRes)
        assertEquals("لا يجوز تحديث وقت آخر مزامنة عند انقطاع الإنترنت", 0L, recordedSyncTimestamp)
        assertEquals(2, dao.countByStateForUser(userA, SyncState.PENDING).first())
        assertEquals(0, cloud.uploadCallCount)

        // عودة الاتصال بالإنترنت: تُرفع قائمة الانتظار تلقائيًا بالكامل
        online = true
        val onlineRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "Model",
            enabledTypes = listOf("call", "sms"),
            uploader = cloud,
            isOnline = { online },
            onLastSyncUpdated = { recordedSyncTimestamp = it },
        )
        assertEquals(SyncResult.Uploaded(2), onlineRes)
        assertTrue(recordedSyncTimestamp > 0L)
        assertEquals(0, dao.countByStateForUser(userA, SyncState.PENDING).first())
        assertEquals(2, dao.countByStateForUser(userA, SyncState.SYNCED).first())
    }

    // -------------------------------------------------------------------------
    // السيناريو 4: إعادة تشغيل الهاتف — استئناف غير المتزامن فقط وعدم إعادة رفع المتزامن سابقًا
    // -------------------------------------------------------------------------
    @Test
    fun scenario4_phoneReboot_doesNotReuploadSyncedRecordsAndUploadsOnlyNewOnes() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()

        val oldCall = Rec.call(devA, "0559990001", 1_715_030_000_000L, "incoming", 15L, null)
        val oldSms = Rec.sms(devA, "0559990002", "رسالة قبل إعادة التشغيل", 1_715_030_010_000L, "inbox")
        Engine.storeLocal(dao, listOf(oldCall, oldSms), uid = userA)

        // مزامنة قبل إعادة تشغيل الهاتف
        assertEquals(
            SyncResult.Uploaded(2),
            Engine.uploadRecords(dao, userA, testVaultKey, devA, "Phone", "M", listOf("call", "sms"), cloud, { true }),
        )
        val uploadsBeforeReboot = cloud.totalRecordsUploadedInCalls

        // محاكاة إعادة تشغيل الهاتف: فحص نافذة الـ 6 ساعات يعيد قراءة السجلين القديمين + سجل جديد واحد وصل أثناء الإقلاع
        val bootSms = Rec.sms(devA, "0559990003", "وصلت أثناء إعادة التشغيل", 1_715_030_500_000L, "inbox")
        val newlyInserted = Engine.storeLocal(dao, listOf(oldCall, oldSms, bootSms), uid = userA)
        assertEquals("السجلان القديمان متجاهلان ولا يُعادان إلى قائمة الانتظار", 1, newlyInserted)

        val postBootRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "M",
            enabledTypes = listOf("call", "sms"),
            uploader = cloud,
            isOnline = { true },
        )
        assertEquals("يُرفع السجل الجديد فقط دون إعادة رفع السجلات المتزامنة سابقًا", SyncResult.Uploaded(1), postBootRes)
        assertEquals(uploadsBeforeReboot + 1, cloud.totalRecordsUploadedInCalls)
        assertEquals(3, cloud.serverDocsForDevice(devA).size)
    }

    // -------------------------------------------------------------------------
    // السيناريو 5: إغلاق الواجهة وإعادة فتحها + ترقية سجلات الإصدار 1 وعزل الحسابات المتعددة
    // -------------------------------------------------------------------------
    @Test
    fun scenario5_uiClosedAndReopened_preservesFourStatesAndIsolatesMultipleAccounts() = runBlocking {
        val dao = InMemoryRecordDao()

        // سجل قديم من الإصدار 1 بلا ownerUid
        dao.insertAll(
            listOf(
                RecordEntity(
                    id = "legacy_v1_rec",
                    type = "call",
                    ts = 1_715_000_000_000L,
                    json = """{"number":"123","type":"incoming","durationSec":10}""",
                    syncState = SyncState.SYNCED,
                    createdAt = 1_715_000_000_000L,
                    ownerUid = "",
                )
            )
        )

        // المالك الأول يسترد السجل القديم، بينما يُحفظ سجل جديد أثناء الإيقاف المؤقت كـ LOCAL_ONLY
        assertEquals(1, dao.claimLegacyUnownedRecords(userA))
        val pausedRec = Rec.clip(devA, "ملاحظة محفوظة أثناء الإيقاف المؤقت", 1_715_040_000_000L)
        Engine.storeLocal(dao, listOf(pausedRec), uid = userA, pausedOrUnauthenticated = true)

        // حساب ثانٍ على نفس الهاتف يحفظ سجله الخاص
        val userBRec = Rec.clip("dev_beta", "ملاحظة خاصة بالحساب الثاني", 1_715_040_100_000L)
        Engine.storeLocal(dao, listOf(userBRec), uid = userB, pausedOrUnauthenticated = false)

        // التحقق من بقاء الحالات الأربع دقيقة ومعزولة تمامًا بعد إعادة قراءة الحالة من قاعدة البيانات
        assertEquals(2, dao.totalForUser(userA).first())
        assertEquals(1, dao.countByStateForUser(userA, SyncState.SYNCED).first())
        assertEquals(1, dao.countByStateForUser(userA, SyncState.LOCAL_ONLY).first())
        assertEquals(0, dao.countByStateForUser(userA, SyncState.PENDING).first())

        assertEquals(1, dao.totalForUser(userB).first())
        assertEquals(1, dao.countByStateForUser(userB, SyncState.PENDING).first())
    }

    // -------------------------------------------------------------------------
    // السيناريو 6: رفض الصلاحية أو عدم وجود سجلات ثم منحها لاحقًا
    // -------------------------------------------------------------------------
    @Test
    fun scenario6_permissionDeniedThenGrantedLater_neverFakesLastSyncWhenNothingUploaded() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()
        var lastSyncAt = 0L

        // قبل منح الصلاحية: لا توجد سجلات ملتقطة -> لا يجوز ادعاء نجاح رفع سحابي
        val emptyRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "M",
            enabledTypes = emptyList(),
            uploader = cloud,
            isOnline = { true },
            onLastSyncUpdated = { lastSyncAt = it },
        )
        assertEquals(SyncResult.NothingToUpload, emptyRes)
        assertEquals(0L, lastSyncAt)

        // بعد منح الصلاحية لاحقًا: تُلتقط السجلات وتُرفع ويُحدَّث وقت المزامنة الحقيقي
        val capturedAfterGrant = Rec.call(devA, "+966500112233", 1_715_050_000_000L, "incoming", 88L, "عمل")
        Engine.storeLocal(dao, listOf(capturedAfterGrant), uid = userA)

        val afterGrantRes = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "M",
            enabledTypes = listOf("call"),
            uploader = cloud,
            isOnline = { true },
            onLastSyncUpdated = { lastSyncAt = it },
        )
        assertEquals(SyncResult.Uploaded(1), afterGrantRes)
        assertTrue(lastSyncAt > 0L)
    }

    // -------------------------------------------------------------------------
    // السيناريو 7: فشل بعض السجلات في الدفعة وانقطاع الشبكة ثم إعادة محاولة الفاشل فقط
    // -------------------------------------------------------------------------
    @Test
    fun scenario7_partialBatchFailure_marksSucceededAsSyncedAndRetriesOnlyFailedRecords() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()
        var lastErrSummary: String? = null
        var lastErrFix: String? = null

        val ok1 = Rec.sms(devA, "1001", "رسالة ناجحة 1", 1_715_060_001_000L, "inbox")
        val bad2 = Rec.sms(devA, "1002", "رسالة تتعثر مؤقتًا", 1_715_060_002_000L, "inbox")
        val ok3 = Rec.sms(devA, "1003", "رسالة ناجحة 3", 1_715_060_003_000L, "sent")
        Engine.storeLocal(dao, listOf(ok1, bad2, ok3), uid = userA)

        // ضبط الخادم ليرفض bad2 فقط في المحاولة الأولى
        cloud.failSpecificRecordIds += bad2.id

        val firstAttempt = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "M",
            enabledTypes = listOf("sms"),
            uploader = cloud,
            isOnline = { true },
            onErrorRecorded = { s, f ->
                lastErrSummary = s
                lastErrFix = f
            },
        )
        assertTrue(firstAttempt is SyncResult.PartialFailure)
        val pf = firstAttempt as SyncResult.PartialFailure
        assertEquals("يجب نجاح السجلين السليمين", 2, pf.uploadedCount)
        assertEquals("يجب تسجيل سجل فاشل واحد فقط", 1, pf.failedCount)
        assertNotNull(lastErrSummary)
        assertFalse(lastErrFix.isNullOrBlank())

        assertEquals(2, dao.countByStateForUser(userA, SyncState.SYNCED).first())
        assertEquals(1, dao.countByStateForUser(userA, SyncState.FAILED).first())

        // في المحاولة التالية بعد زوال العارض: يُعاد رفع bad2 وحده فقط دون إعادة رفع ok1 أو ok3!
        cloud.failSpecificRecordIds.clear()
        cloud.uploadedRecordIdsHistory.clear()

        val retryAttempt = Engine.uploadRecords(
            dao = dao,
            uid = userA,
            key = testVaultKey,
            deviceId = devA,
            deviceName = "Phone",
            deviceModel = "M",
            enabledTypes = listOf("sms"),
            uploader = cloud,
            isOnline = { true },
            onErrorCleared = {
                lastErrSummary = null
                lastErrFix = null
            },
        )
        assertEquals(SyncResult.Uploaded(1), retryAttempt)
        assertEquals("أُعيد إرسال السجل الفاشل وحده فقط", listOf(bad2.id), cloud.uploadedRecordIdsHistory)
        assertEquals(3, dao.countByStateForUser(userA, SyncState.SYNCED).first())
        assertEquals(0, dao.countByStateForUser(userA, SyncState.FAILED).first())
        assertNull(lastErrSummary)
    }

    // -------------------------------------------------------------------------
    // السيناريو 8: عدم تكرار السجلات عند إعادة المحاولة + حفظ رسالتين حقيقيتين متطابقتين زمنيًا
    // -------------------------------------------------------------------------
    @Test
    fun scenario8_idempotentRetriesAndDistinctRepeatedMessagesPreserved() = runBlocking {
        val dao = InMemoryRecordDao()
        val cloud = FakeCloudUploader()

        // صديق يرسل نفس النص «نعم» مرتين بفاصل 45 ثانية -> يجب حفظ الرسالتين معًا وعدم اعتبارهما مكررتين!
        val t1 = 1_715_070_000_000L
        val t2 = t1 + 45_000L
        val pdu1 = Rec.sms(devA, "+966509998877", "نعم", t1, "inbox", dateSent = t1, fromPdu = true)
        val pdu2 = Rec.sms(devA, "+966509998877", "نعم", t2, "inbox", dateSent = t2, fromPdu = true)

        assertEquals(1, Engine.storeLocal(dao, listOf(pdu1), uid = userA))
        assertEquals(1, Engine.storeLocal(dao, listOf(pdu2), uid = userA))

        // قراءة المزوّد للرسالتين لاحقًا لا تضيف نسخًا مكررة
        val prov1 = Rec.sms(devA, "0509998877", "نعم", t1 + 1_500L, "inbox", dateSent = t1, fromPdu = false)
        val prov2 = Rec.sms(devA, "0509998877", "نعم", t2 + 1_800L, "inbox", dateSent = t2, fromPdu = false)
        assertEquals(0, Engine.storeLocal(dao, listOf(prov1, prov2), uid = userA))
        assertEquals(2, dao.totalForUser(userA).first())

        // حتى لو أُعيد رفع السجلات يدويًا عبر resetSyncForUser، تظل وثائق السحابة 2 فقط دون تكرار
        Engine.uploadRecords(dao, userA, testVaultKey, devA, "Phone", "M", listOf("sms"), cloud, { true })
        dao.resetSyncForUser(userA)
        Engine.uploadRecords(dao, userA, testVaultKey, devA, "Phone", "M", listOf("sms"), cloud, { true })
        assertEquals(2, cloud.serverDocsForDevice(devA).size)
    }

    // -------------------------------------------------------------------------
    // السيناريو 9: التقاط نصوص الحافظة، منع التكرار، فلترة النصوص الحساسة، واحترام مفتاح التعطيل
    // -------------------------------------------------------------------------
    @Test
    fun scenario9_clipboardAutoValidation_savesValidBlocksSensitiveDeduplicatesAndRespectsSwitch() = runBlocking {
        val dao = InMemoryRecordDao()
        val enabledAll = setOf(RecordType.CALL.id, RecordType.SMS.id, RecordType.CLIP.id)

        // 1) نص عادي يُقبل ويُحفظ محليًا ويُجدول للرفع
        val (s1, rec1) = ClipSaver.validateAndBuild(devA, enabledAll, "مقال مهم للقراءة لاحقًا https://example.com", 1_715_080_000_000L)
        assertEquals(ClipResult.SAVED, s1)
        assertNotNull(rec1)
        assertEquals(1, Engine.storeLocal(dao, listOf(rec1!!), uid = userA))

        // 2) نسخ نفس النص مرة ثانية في وقت مختلف ينتج نفس المعرّف الحتمي فلا يتكرر في Room
        val (_, recDup) = ClipSaver.validateAndBuild(devA, enabledAll, "  مقال مهم للقراءة لاحقًا https://example.com  ", 1_715_080_999_000L)
        assertEquals(rec1.id, recDup!!.id)
        assertEquals(0, Engine.storeLocal(dao, listOf(recDup), uid = userA))

        // 3) رموز التحقق وكلمات المرور تُحجب تلقائيًا
        val (sOtp, recOtp) = ClipSaver.validateAndBuild(devA, enabledAll, "رمز التحقق الخاص بك OTP: 482910")
        assertEquals(ClipResult.SENSITIVE, sOtp)
        assertNull(recOtp)

        // 4) عند إيقاف مفتاح الحافظة من الواجهة لا يُلتقط أي نص
        val (sDis, recDis) = ClipSaver.validateAndBuild(devA, setOf(RecordType.CALL.id), "نص عادي بعد التعطيل")
        assertEquals(ClipResult.DISABLED, sDis)
        assertNull(recDis)

        // التحقق من تشفير وفك تشفير سجل الحافظة المحفوظ بسلامة تامة
        val enc = RecordCodec.encrypt(testVaultKey, rec1)
        val dec = RecordCodec.decrypt(testVaultKey, devA, enc)
        assertEquals(Crypto.sha256Hex("مقال مهم للقراءة لاحقًا https://example.com"), Crypto.sha256Hex(dec.fields.getString("text")))
    }
}

/** تنفيذ محلي مطابق لـ Room RecordDao لأغراض الاختبار الفوري الحتمي على JVM. */
private class InMemoryRecordDao : RecordDao {
    private val rows = LinkedHashMap<String, RecordEntity>()
    private val state = MutableStateFlow<List<RecordEntity>>(emptyList())

    private fun emit() {
        state.value = rows.values.toList()
    }

    override suspend fun insertAll(r: List<RecordEntity>): List<Long> {
        val out = ArrayList<Long>(r.size)
        for (item in r) {
            if (rows.containsKey(item.id)) {
                out += -1L
            } else {
                rows[item.id] = item
                out += 1L
            }
        }
        emit()
        return out
    }

    override suspend fun claimLegacyUnownedRecords(uid: String): Int {
        var c = 0
        for ((k, v) in rows.entries) {
            if (v.ownerUid == "") {
                rows[k] = v.copy(ownerUid = uid)
                c++
            }
        }
        if (c > 0) emit()
        return c
    }

    override suspend fun promoteLocalToPending(uid: String, now: Long): Int {
        var c = 0
        for ((k, v) in rows.entries) {
            if (v.ownerUid == uid && v.syncState == SyncState.LOCAL_ONLY) {
                rows[k] = v.copy(syncState = SyncState.PENDING, updatedAt = now)
                c++
            }
        }
        if (c > 0) emit()
        return c
    }

    override suspend fun pendingForUser(uid: String, n: Int): List<RecordEntity> =
        rows.values
            .filter { it.ownerUid == uid && (it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED) }
            .sortedBy { it.ts }
            .take(n)

    override suspend fun pending(n: Int): List<RecordEntity> =
        rows.values
            .filter { it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED }
            .sortedBy { it.ts }
            .take(n)

    override suspend fun markSynced(ids: List<String>, now: Long) {
        val idSet = ids.toSet()
        for (id in idSet) {
            rows[id]?.let { rows[id] = it.copy(syncState = SyncState.SYNCED, lastError = null, updatedAt = now) }
        }
        emit()
    }

    override suspend fun markFailed(ids: List<String>, reason: String, now: Long) {
        for (id in ids) {
            rows[id]?.let {
                if (it.syncState != SyncState.SYNCED) {
                    rows[id] = it.copy(
                        syncState = SyncState.FAILED,
                        attemptCount = it.attemptCount + 1,
                        lastError = reason,
                        updatedAt = now,
                    )
                }
            }
        }
        emit()
    }

    override suspend fun resetSyncForUser(uid: String, now: Long) {
        for ((k, v) in rows.entries) {
            if (v.ownerUid == uid) {
                rows[k] = v.copy(syncState = SyncState.PENDING, lastError = null, updatedAt = now)
            }
        }
        emit()
    }

    override suspend fun resetSync() {
        for ((k, v) in rows.entries) {
            rows[k] = v.copy(syncState = SyncState.PENDING, lastError = null)
        }
        emit()
    }

    override suspend fun retryFailedNow(uid: String, now: Long): Int {
        var c = 0
        for ((k, v) in rows.entries) {
            if (v.ownerUid == uid && v.syncState == SyncState.FAILED) {
                rows[k] = v.copy(syncState = SyncState.PENDING, updatedAt = now)
                c++
            }
        }
        if (c > 0) emit()
        return c
    }

    override fun total(): Flow<Int> = state.map { it.size }

    override fun totalForUser(uid: String): Flow<Int> =
        state.map { list -> list.count { it.ownerUid == uid } }

    override fun pendingCount(): Flow<Int> =
        state.map { list -> list.count { it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED } }

    override fun countByStateForUser(uid: String, stateCode: Int): Flow<Int> =
        state.map { list -> list.count { it.ownerUid == uid && it.syncState == stateCode } }

    override fun pendingOrFailedCountForUser(uid: String): Flow<Int> =
        state.map { list ->
            list.count { it.ownerUid == uid && (it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED) }
        }

    override suspend fun unsyncedCountForUserNow(uid: String): Int =
        rows.values.count {
            it.ownerUid == uid &&
                (it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED || it.syncState == SyncState.LOCAL_ONLY)
        }

    override suspend fun pendingOrFailedNowForUser(uid: String): Int =
        rows.values.count {
            it.ownerUid == uid && (it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED)
        }

    override suspend fun pendingNow(): Int =
        rows.values.count { it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED }

    override suspend fun smsInWindow(uid: String, fromTs: Long, toTs: Long): List<RecordEntity> =
        rows.values.filter { it.ownerUid == uid && it.type == "sms" && it.ts in fromTs..toTs }

    override fun latestForType(uid: String, type: String): Flow<RecordEntity?> =
        state.map { list -> list.filter { it.ownerUid == uid && it.type == type }.maxByOrNull { it.ts } }

    override fun recentForUser(uid: String, type: String, n: Int): Flow<List<RecordEntity>> =
        state.map { list -> list.filter { it.ownerUid == uid && it.type == type }.sortedByDescending { it.ts }.take(n) }

    override fun recentAllForUser(uid: String, n: Int): Flow<List<RecordEntity>> =
        state.map { list -> list.filter { it.ownerUid == uid }.sortedByDescending { it.ts }.take(n) }

    override fun recent(type: String, n: Int): Flow<List<RecordEntity>> =
        state.map { list -> list.filter { it.type == type }.sortedByDescending { it.ts }.take(n) }

    override suspend fun delete(id: String) {
        rows.remove(id)
        emit()
    }

    override suspend fun deleteAllForUser(uid: String) {
        val keys = rows.filterValues { it.ownerUid == uid }.keys.toList()
        keys.forEach { rows.remove(it) }
        emit()
    }

    override suspend fun deleteAll() {
        rows.clear()
        emit()
    }
}

/** خادم سحابي تجريبي يحاكي Firestore للتحقق من الدفعات الذرية، منع التكرار، وعزل الفشل الجزئي. */
private class FakeCloudUploader : CloudUploader {
    private val serverStore = LinkedHashMap<String, LinkedHashMap<String, EncRecord>>()
    val failSpecificRecordIds = HashSet<String>()
    val uploadedRecordIdsHistory = ArrayList<String>()
    var uploadCallCount = 0
    var totalRecordsUploadedInCalls = 0
    var lastMarkedSyncAt = 0L

    override suspend fun registerDevice(deviceId: String, name: String, model: String, enabled: List<String>) {
        serverStore.getOrPut(deviceId) { LinkedHashMap() }
    }

    override suspend fun upload(deviceId: String, recs: List<EncRecord>) {
        uploadCallCount++
        if (recs.any { it.id in failSpecificRecordIds }) {
            throw IOException("Simulated server failure for targeted record")
        }
        val bucket = serverStore.getOrPut(deviceId) { LinkedHashMap() }
        for (r in recs) {
            bucket[r.id] = r
            uploadedRecordIdsHistory += r.id
            totalRecordsUploadedInCalls++
        }
    }

    override suspend fun markSynced(deviceId: String, at: Long) {
        lastMarkedSyncAt = at
    }

    fun serverDocsForDevice(deviceId: String): List<EncRecord> =
        serverStore[deviceId]?.values?.toList().orEmpty()
}
