@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.backup.source

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.backup.core.AuthMessages
import app.backup.core.CloudRepo
import app.backup.core.Net
import app.backup.core.RecordType
import app.backup.core.Session
import app.backup.core.UsernameAuth
import app.backup.core.ui.AuthGate
import app.backup.core.ui.BackupTheme
import app.backup.core.ui.ConfirmDialog
import app.backup.core.ui.InfoRow
import app.backup.core.ui.PasswordDialog
import app.backup.core.ui.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val activityResumeTick = MutableStateFlow(0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackupTheme {
                AuthGate {
                    val done by Prefs.onboardingDone.collectAsState()
                    if (!done) Onboarding() else Home()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumeTick.value += 1
        LiveContentObservers.register(this)
        if (Prefs.isInitialized && Prefs.onboardingDone.value && !Prefs.paused.value) {
            Scheduler.ensureBackgroundSchedule(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ClipboardAutoMonitor.onAppWindowFocused(this)
        }
    }
}

private fun fmt(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(ms))

private fun typeLabel(t: RecordType): String = when (t) {
    RecordType.CALL -> "سجل المكالمات"
    RecordType.SMS -> "الرسائل النصية"
    RecordType.CLIP -> "نصوص الحافظة"
}

private fun callKindAr(kind: String): String = when (kind) {
    "incoming" -> "واردة"
    "outgoing" -> "صادرة"
    "missed" -> "فائتة"
    "rejected" -> "مرفوضة"
    "blocked" -> "محظورة"
    "voicemail" -> "بريد صوتي"
    else -> kind
}

private fun smsKindAr(kind: String): String = when (kind) {
    "inbox" -> "واردة"
    "sent" -> "صادرة"
    "draft" -> "مسودة"
    else -> kind
}

private fun summarizeRecord(entity: RecordEntity?): String {
    if (entity == null) return "لم يُلتقط أي سجل بعد"
    val obj = runCatching { JSONObject(entity.json) }.getOrNull() ?: JSONObject()
    val detail = when (entity.type) {
        RecordType.CALL.id -> {
            val num = obj.optString("number").ifBlank { "رقم مخفي" }
            val name = obj.optString("name")
            val kind = callKindAr(obj.optString("type"))
            val dur = obj.optLong("durationSec", 0L)
            val who = if (name.isNotBlank()) "$name ($num)" else num
            "$who • $kind (${dur}ث)"
        }
        RecordType.SMS.id -> {
            val addr = obj.optString("address").ifBlank { "غير معروف" }
            val kind = smsKindAr(obj.optString("type"))
            val body = obj.optString("body").replace('\n', ' ').take(45)
            "$addr • $kind: $body"
        }
        else -> {
            obj.optString("text").replace('\n', ' ').take(55).ifBlank { "نص محفوظ" }
        }
    }
    return "${fmt(entity.ts)} — $detail (${SyncState.labelAr(entity.syncState)})"
}

// ---------------------------------------------------------------- الإعداد الأول
@Composable
fun Onboarding() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.deviceName.value) }
    var calls by remember { mutableStateOf(true) }
    var sms by remember { mutableStateOf(true) }
    var clip by remember { mutableStateOf(true) }

    fun finishSetup(types: Set<String>) {
        Prefs.completeOnboarding(name.trim().ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }, types)
        LiveContentObservers.register(ctx)
        Scheduler.ensureBackgroundSchedule(ctx)
        Scheduler.triggerImmediateScanAndUpload(ctx)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val en = mutableSetOf<String>()
        if (calls && res[Manifest.permission.READ_CALL_LOG] == true) {
            en += RecordType.CALL.id
        }
        if (sms && (res[Manifest.permission.READ_SMS] == true || res[Manifest.permission.RECEIVE_SMS] == true)) {
            en += RecordType.SMS.id
        }
        if (clip) {
            en += RecordType.CLIP.id
        }
        finishSetup(en)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("إعداد النسخ الاحتياطي التلقائي", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "يكتشف التطبيق المكالمات الجديدة والرسائل النصية الواردة والصادرة تلقائيًا في الخلفية، " +
                "ويحفظها فورًا في قاعدة البيانات المحلية (Room) حتى دون إنترنت، ثم يرفعها مشفّرةً بـ AES-256-GCM إلى حسابك السحابي.\n\n" +
                "لا يعمل إلا بالأنواع التي تفعّلها أدناه وبعد منح صلاحيات أندرويد الرسمية، ويمكنك إيقافه أو حذف بياناتك في أي وقت.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("اسم هذا الجهاز") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionTitle("أنواع البيانات المراد نسخها تلقائيًا")
        CheckRow("سجل المكالمات تلقائيًا (يتطلب صلاحيتي سجل المكالمات وحالة الهاتف)", calls) { calls = it }
        CheckRow("الرسائل النصية تلقائيًا (يتطلب صلاحيتي استقبال وقراءة الرسائل)", sms) { sms = it }
        CheckRow("نصوص الحافظة (التقاط تلقائي حسب إصدار أندرويد + مشاركة وحفظ سريع)", clip) { clip = it }
        Spacer(Modifier.height(8.dp))
        Text(
            "ملاحظة لأجهزة Android 13+ وواجهات Samsung: إذا منع النظام منح صلاحية الرسائل أو سجل المكالمات لتطبيق مثبّت عبر APK، " +
                "يمكنك فتح «معلومات التطبيق ← القائمة العلوية (⋮) ← السماح بالإعدادات المقيّدة» ثم تفعيل المفتاح من الشاشة الرئيسية.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val perms = Perms.onboardingPermissions(calls = calls, sms = sms, includeNotifications = true)
                if (perms.isEmpty()) {
                    finishSetup(if (clip) setOf(RecordType.CLIP.id) else emptySet())
                } else {
                    launcher.launch(perms.toTypedArray())
                }
            },
        ) {
            Text("بدء التفعيل والمزامنة الأولية")
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- الشاشة الرئيسية
@Composable
fun Home() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }

    val activeUidState by Session.uidFlow.collectAsState()
    val activeUid = activeUidState.orEmpty()
    val resumeTick by activityResumeTick.collectAsState()

    val total by remember(activeUid) { dao.totalForUser(activeUid) }.collectAsState(0)
    val localOnlyCount by remember(activeUid) { dao.countByStateForUser(activeUid, SyncState.LOCAL_ONLY) }.collectAsState(0)
    val pendingCount by remember(activeUid) { dao.countByStateForUser(activeUid, SyncState.PENDING) }.collectAsState(0)
    val syncedCount by remember(activeUid) { dao.countByStateForUser(activeUid, SyncState.SYNCED) }.collectAsState(0)
    val failedCount by remember(activeUid) { dao.countByStateForUser(activeUid, SyncState.FAILED) }.collectAsState(0)

    val latestCall by remember(activeUid) { dao.latestForType(activeUid, RecordType.CALL.id) }.collectAsState(null)
    val latestSms by remember(activeUid) { dao.latestForType(activeUid, RecordType.SMS.id) }.collectAsState(null)
    val latestClip by remember(activeUid) { dao.latestForType(activeUid, RecordType.CLIP.id) }.collectAsState(null)
    val recentAll by remember(activeUid) { dao.recentAllForUser(activeUid, 20) }.collectAsState(emptyList())

    val online by remember { Net.flow(ctx) }.collectAsState(Net.isOnline(ctx))
    val enabled by Prefs.enabled.collectAsState()
    val paused by Prefs.paused.collectAsState()
    val syncInFlight by Prefs.syncInFlight.collectAsState()
    val lastSync by Prefs.lastSyncAt.collectAsState()
    val lastScan by Prefs.lastScanAt.collectAsState()
    val name by Prefs.deviceName.collectAsState()
    val notice by Prefs.notice.collectAsState()
    val lastSyncError by Prefs.lastSyncError.collectAsState()
    val quickClipNotif by Prefs.quickClipNotifEnabled.collectAsState()

    var permVersion by remember { mutableIntStateOf(0) }
    val bgHealth = remember(resumeTick, permVersion) { BackgroundHealth.inspect(ctx) }
    val activeDeviceId = remember(activeUid) { Prefs.deviceIdForUser(activeUid.ifBlank { null }) }

    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var pendingToggleType by remember { mutableStateOf<RecordType?>(null) }

    // ربط سجلات الإصدار القديم بالحساب الأساسي لمرة واحدة وتأكيد الجدولة الخلفية دون الاعتماد على مؤقت واجهة
    LaunchedEffect(activeUid) {
        if (activeUid.isNotBlank() && Prefs.isPrimaryOwner(activeUid)) {
            dao.claimLegacyUnownedRecords(activeUid)
        }
        Scheduler.ensureBackgroundSchedule(ctx)
        if (!paused && activeUid.isNotBlank()) {
            Scheduler.triggerImmediateScanAndUpload(ctx)
        }
    }

    // عند منح صلاحية كانت مرفوضة سابقًا من إعدادات النظام والعودة للتطبيق، يبدأ الفحص والرفع تلقائيًا
    LaunchedEffect(resumeTick) {
        if (resumeTick > 0 && !paused && activeUid.isNotBlank()) {
            val canNowScan = (RecordType.CALL.id in enabled && Perms.canReadCallLog(ctx)) ||
                (RecordType.SMS.id in enabled && Perms.canReadSmsProvider(ctx))
            if (canNowScan) {
                Scheduler.triggerImmediateScanAndUpload(ctx)
            }
            QuickClipNotification.sync(ctx)
        }
    }

    LaunchedEffect(enabled, name, activeUid) {
        if (activeUid.isNotBlank() && online) {
            runCatching {
                CloudRepo(explicitUid = activeUid).registerDevice(
                    activeDeviceId,
                    name,
                    Build.MODEL,
                    enabled.toList(),
                )
            }
        }
        Scheduler.refreshContentTriggers(ctx)
        QuickClipNotification.sync(ctx)
    }

    val typePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        permVersion += 1
        val target = pendingToggleType
        pendingToggleType = null
        if (target != null) {
            val allowed = when (target) {
                RecordType.CALL -> res[Manifest.permission.READ_CALL_LOG] == true || Perms.canReadCallLog(ctx)
                RecordType.SMS -> res[Manifest.permission.READ_SMS] == true ||
                    res[Manifest.permission.RECEIVE_SMS] == true ||
                    Perms.typeAllowed(ctx, RecordType.SMS)
                RecordType.CLIP -> true
            }
            if (allowed) {
                Prefs.setEnabled(target.id, true)
                LiveContentObservers.register(ctx)
                Scheduler.ensureBackgroundSchedule(ctx)
                Scheduler.triggerImmediateScanAndUpload(ctx)
                message = "تم تفعيل «${typeLabel(target)}» وبدأ الفحص والمزامنة التلقائية."
            } else {
                message = "لم تُمنح الصلاحية لـ «${typeLabel(target)}». على أندرويد 13+ افتح معلومات التطبيق واسمح بالإعدادات المقيّدة ثم امنح الصلاحية."
            }
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        permVersion += 1
        if (ok) {
            Prefs.setQuickClipNotifEnabled(true)
            QuickClipNotification.sync(ctx)
            message = "تم تفعيل إشعار الحفظ السريع للحافظة."
        } else {
            Prefs.setQuickClipNotifEnabled(false)
            message = "يلزم السماح بالإشعارات لإظهار زر الحفظ السريع في شريط الإشعارات."
        }
    }

    val callsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { message = Importer.importUri(ctx, it, calls = true) } }
    }
    val smsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { message = Importer.importUri(ctx, it, calls = false) } }
    }

    fun toggleType(t: RecordType, turnOn: Boolean) {
        if (!turnOn) {
            Prefs.setEnabled(t.id, false)
            Scheduler.refreshContentTriggers(ctx)
            QuickClipNotification.sync(ctx)
            message = "تم إيقاف التقاط «${typeLabel(t)}» الجديدة. السجلات السابقة محفوظة."
            return
        }
        val needed = Perms.permissionsForType(t)
        if (needed.isEmpty() || Perms.typeAllowed(ctx, t)) {
            Prefs.setEnabled(t.id, true)
            LiveContentObservers.register(ctx)
            Scheduler.ensureBackgroundSchedule(ctx)
            Scheduler.triggerImmediateScanAndUpload(ctx)
            message = "تم تفعيل «${typeLabel(t)}»."
        } else {
            pendingToggleType = t
            typePermLauncher.launch(needed.toTypedArray())
        }
    }

    val waitingCount = pendingCount + failedCount
    val overallStatus = when {
        paused -> "متوقف مؤقتًا بأمر المستخدم"
        syncInFlight -> "جاري تشفير ورفع السجلات إلى Firebase..."
        failedCount > 0 && !online -> "فشل رفع $failedCount سجل (بانتظار عودة الإنترنت لإعادة المحاولة)"
        failedCount > 0 -> "فشل رفع $failedCount سجل (مجدول لإعادة المحاولة التلقائية)"
        pendingCount > 0 && !online -> "محفوظ في قائمة الانتظار: $pendingCount سجل بانتظار الإنترنت"
        pendingCount > 0 -> "$pendingCount سجل في قائمة الانتظار بانتظار الرفع"
        localOnlyCount > 0 -> "$localOnlyCount سجل محفوظ محليًا (لم يُرفع بعد)"
        syncedCount > 0 -> "متزامن بالكامل ($syncedCount سجل مؤكّد في Firebase)"
        else -> "جاهز للمراقبة التلقائية (لا توجد سجلات ملتقطة بعد)"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("النسخ الاحتياطي التلقائي", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = {
                Scheduler.stop(ctx)
                UsernameAuth.signOut(ctx)
            }) {
                Text("تسجيل الخروج")
            }
        }
        Text(
            "يعمل التطبيق تلقائيًا في الخلفية لالتقاط المكالمات والرسائل المفعّلة وحفظها محليًا في Room ثم رفعها مشفّرةً إلى Firebase دون الحاجة لفتح التطبيق.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        // ---------------- بطاقة الحالة والعدادات الدقيقة ----------------
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                UsernameAuth.savedUsername(ctx)?.let { InfoRow("الحساب النشط", it) }
                InfoRow("اسم الجهاز", name)
                InfoRow("حالة المزامنة الفعلية", overallStatus)
                InfoRow("الاتصال بالإنترنت", if (online) "متصل" else "غير متصل (حفظ محلي في قائمة الانتظار)")
                InfoRow("العمل في الخلفية", bgHealth.summaryAr)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                InfoRow("إجمالي السجلات في الجهاز (Room)", total.toString())
                InfoRow("تمت مزامنته في السحابة", syncedCount.toString())
                InfoRow("بانتظار الرفع", pendingCount.toString())
                InfoRow("فشل الرفع (قيد الإعادة)", failedCount.toString())
                InfoRow("محفوظ محليًا فقط", localOnlyCount.toString())
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                InfoRow("آخر رفع فعلي مؤكّد للسحابة", fmt(lastSync))
                InfoRow("آخر فحص / التقاط محلي", fmt(lastScan))
                notice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------------- تشخيص أخطاء المزامنة وكيفية معالجتها ----------------
        lastSyncError?.let { err ->
            Spacer(Modifier.height(10.dp))
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "آخر خطأ مزامنة (${fmt(err.occurredAt)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(err.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (err.remediation.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("كيفية المعالجة: ${err.remediation}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (failedCount > 0 && !paused) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            scope.launch {
                                val retried = dao.retryFailedNow(activeUid)
                                Scheduler.syncNow(ctx)
                                message = "تمت جدولة إعادة محاولة $retried سجل فاشل فقط."
                            }
                        }) {
                            Text("إعادة محاولة السجلات الفاشلة فقط ($failedCount)")
                        }
                    }
                }
            }
        }

        // ---------------- تشخيص قيود البطارية والخلفية وإعدادات أندرويد ----------------
        bgHealth.guidanceAr?.let { guidance ->
            Spacer(Modifier.height(10.dp))
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("تحسين استمرارية العمل في الخلفية", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(guidance, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { ctx.startActivity(BackgroundHealth.appDetailsSettingsIntent(ctx)) }
                        }) {
                            Text("إعدادات التطبيق والبطارية")
                        }
                        if (!bgHealth.isIgnoringBatteryOptimizations) {
                            TextButton(onClick = {
                                runCatching { ctx.startActivity(BackgroundHealth.batteryOptimizationSettingsIntent()) }
                            }) {
                                Text("قائمة تحسين البطارية")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = !paused && !syncInFlight,
                onClick = {
                    scope.launch {
                        val added = Engine.scan(ctx, dao)
                        val unsyncedNow = dao.pendingOrFailedNowForUser(activeUid)
                        if (unsyncedNow == 0) {
                            message = if (added == 0) {
                                "تم فحص الجهاز: لا توجد سجلات جديدة بانتظار الرفع."
                            } else {
                                "تم التقاط $added سجل جديد."
                            }
                        } else if (!online) {
                            Scheduler.enqueueImmediateUpload(ctx, expedited = false)
                            message = "تم حفظ السجلات محليًا ($unsyncedNow بانتظار الرفع)؛ ستُرفع تلقائيًا فور توفر الإنترنت."
                        } else {
                            Scheduler.syncNow(ctx)
                            message = "بدأت مزامنة $unsyncedNow سجل مع السحابة..."
                        }
                    }
                },
            ) {
                Text(if (syncInFlight) "جاري الرفع..." else "فحص ومزامنة الآن")
            }

            if (paused) {
                OutlinedButton(onClick = {
                    Prefs.setPaused(false)
                    scope.launch {
                        dao.promoteLocalToPending(activeUid)
                        Scheduler.ensureBackgroundSchedule(ctx)
                        Scheduler.triggerImmediateScanAndUpload(ctx)
                        message = "تم استئناف المراقبة الخلفية والمزامنة التلقائية."
                    }
                }) {
                    Text("استئناف المزامنة")
                }
            } else {
                OutlinedButton(onClick = {
                    Prefs.setPaused(true)
                    Scheduler.ensureBackgroundSchedule(ctx)
                    message = "أُوقفت المزامنة السحابية مؤقتًا؛ أي سجل جديد سيُحفظ محليًا فقط حتى الاستئناف."
                }) {
                    Text("إيقاف مؤقت")
                }
            }
        }

        message?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---------------- آخر سجل تم التقاطه لكل نوع ----------------
        SectionTitle("آخر سجل تم التقاطه لكل نوع")
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text("سجل المكالمات", style = MaterialTheme.typography.labelLarge)
                    Text(summarizeRecord(latestCall), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Column {
                    Text("الرسائل النصية", style = MaterialTheme.typography.labelLarge)
                    Text(summarizeRecord(latestSms), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Column {
                    Text("نصوص الحافظة", style = MaterialTheme.typography.labelLarge)
                    Text(summarizeRecord(latestClip), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------------- مفاتيح أنواع البيانات المفعّلة ----------------
        SectionTitle("التحكم في أنواع النسخ التلقائي")
        RecordType.entries.forEach { t ->
            val permOk = remember(resumeTick, permVersion, enabled) { Perms.typeAllowed(ctx, t) }
            val extraDetail = when (t) {
                RecordType.CALL -> if (permOk && !Perms.canDetectPhoneState(ctx)) {
                    "مفعّل عبر مراقبة السجل (امنح صلاحية الهاتف لاكتشاف انتهاء المكالمة لحظيًا)"
                } else null
                RecordType.SMS -> if (permOk && !Perms.canReceiveIncomingSms(ctx)) {
                    "مفعّل لقراءة الرسائل (امنح صلاحية استقبال الرسائل للالتقاط اللحظي)"
                } else null
                RecordType.CLIP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "يلتقط تلقائيًا عند فتح النافذة أو عبر قائمة تحديد النص وإشعار الحفظ السريع"
                } else {
                    "يلتقط النصوص المنسوخة تلقائيًا في الواجهة والخلفية"
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(typeLabel(t), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (t.id in enabled && !permOk) {
                        Text(
                            "الصلاحية غير ممنوحة — اضغط لفتح إعدادات التطبيق ومنحها",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            runCatching { ctx.startActivity(BackgroundHealth.appDetailsSettingsIntent(ctx)) }
                        }) {
                            Text("فتح إعدادات الصلاحيات")
                        }
                    } else if (extraDetail != null) {
                        Text(extraDetail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = t.id in enabled && permOk,
                    onCheckedChange = { toggleType(t, it) },
                )
            }
        }

        // ---------------- قسم الحافظة وتوضيح قيود أندرويد بصراحة ----------------
        SectionTitle("نسخ الحافظة التلقائي والبدائل الرسمية")
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val sdkInfo = "إصدار جهازك الحالي: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                Text(sdkInfo, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                val clipExplanation = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    "يدعم إصدار أندرويد على جهازك (Android 8–9) التقاط النصوص المنسوخة تلقائيًا في الواجهة والخلفية فور نسخها، مع استبعاد كلمات المرور وأكواد التحقق تلقائيًا."
                } else {
                    "بدءًا من Android 10 فما فوق، يمنع نظام أندرويد جميع التطبيقات رسميًا من قراءة الحافظة بصمت أثناء وجودها في الخلفية لحماية الخصوصية.\n" +
                        "• يلتقط التطبيق النص المنسوخ تلقائيًا فور ظهور نافذته دون الضغط على أي زر.\n" +
                        "• لحفظ أي نص وأنت داخل تطبيق آخر بضغطة واحدة دون فتح التطبيق: حدّد النص واختر «حفظ في نسخي الاحتياطي»، أو شاركه للتطبيق، أو فعّل «إشعار الحفظ السريع» أدناه."
                }
                Text(clipExplanation, style = MaterialTheme.typography.bodySmall)

                if (RecordType.CLIP.id in enabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("إشعار الحفظ السريع للحافظة (ضغطة واحدة)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "إشعار هادئ لا يستهلك البطارية يتيح حفظ النص المنسوخ فورًا على Android 10+",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = quickClipNotif && QuickClipNotification.canPostNotifications(ctx),
                            onCheckedChange = { wantOn ->
                                if (!wantOn) {
                                    Prefs.setQuickClipNotifEnabled(false)
                                    QuickClipNotification.sync(ctx)
                                } else if (Build.VERSION.SDK_INT >= 33 && !QuickClipNotification.canPostNotifications(ctx)) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    Prefs.setQuickClipNotifEnabled(true)
                                    QuickClipNotification.sync(ctx)
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val text = ClipSaver.readCurrentClipboardText(ctx)
                        scope.launch { message = clipMessage(ClipSaver.save(ctx, text, scheduleUpload = true), ctx) }
                    },
                ) {
                    Text("حفظ محتوى الحافظة الحالي الآن")
                }
            }
        }

        // ---------------- أحدث السجلات الملتقطة وحالة كل سجل ----------------
        if (recentAll.isNotEmpty()) {
            SectionTitle("أحدث السجلات الملتقطة وحالة كل سجل")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    recentAll.forEachIndexed { idx, item ->
                        val typeTitle = runCatching { typeLabel(RecordType.of(item.type)) }.getOrDefault(item.type)
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(typeTitle, style = MaterialTheme.typography.labelLarge)
                                SyncBadge(item.syncState)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(summarizeRecord(item), style = MaterialTheme.typography.bodySmall)
                            if (item.syncState == SyncState.FAILED && !item.lastError.isNullOrBlank()) {
                                Text(
                                    "سبب الفشل: ${item.lastError}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.type == RecordType.CLIP.id) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                if (item.syncState == SyncState.SYNCED && activeUid.isNotBlank()) {
                                                    CloudRepo(explicitUid = activeUid).deleteRecord(activeDeviceId, item.id)
                                                }
                                                dao.delete(item.id)
                                            } catch (_: Exception) {
                                                message = "تعذّر حذف النسخة السحابية للسجل حاليًا، لذا أُبقي محليًا."
                                            }
                                        }
                                    }) {
                                        Text("حذف")
                                    }
                                }
                            }
                        }
                        if (idx < recentAll.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        SectionTitle("استيراد يدوي (بديل إضافي دون صلاحيات)")
        Text(
            "يمكنك استيراد أرشيف سابق صدّرته بنفسك. المكالمات: CSV بأعمدة number,type,date,duration,name. الرسائل: XML من SMS Backup & Restore أو CSV بأعمدة address,body,date,type.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { callsPicker.launch(arrayOf("*/*")) }) { Text("استيراد مكالمات") }
            OutlinedButton(onClick = { smsPicker.launch(arrayOf("*/*")) }) { Text("استيراد رسائل") }
        }

        SectionTitle("التحكم في البيانات والحساب")
        if (waitingCount > 0) {
            ActionButton("إعادة محاولة رفع السجلات المعلّقة والفاشلة ($waitingCount)") {
                scope.launch {
                    dao.retryFailedNow(activeUid)
                    Scheduler.syncNow(ctx)
                    message = "بدأت إعادة محاولة رفع السجلات غير المتزامنة فقط."
                }
            }
        }
        ActionButton("إعادة رفع كل السجلات المحلية لهذا الحساب") {
            scope.launch {
                dao.resetSyncForUser(activeUid)
                Scheduler.syncNow(ctx)
                message = "أُعيدت السجلات المحلية إلى قائمة انتظار الرفع."
            }
        }
        ActionButton("حذف النسخة السحابية لهذا الجهاز") { confirm = "cloud" }
        ActionButton("حذف السجلات المحلية من هذا الهاتف") { confirm = "local" }
        ActionButton("إزالة هذا الجهاز من الحساب وتسجيل الخروج") { confirm = "device" }
        ActionButton("حذف الحساب وكل بياناته نهائيًا") { confirm = "account" }
        Spacer(Modifier.height(24.dp))
    }

    fun runConfirmedAction(kind: String, password: String? = null) {
        confirm = null
        scope.launch {
            try {
                val repo = CloudRepo(explicitUid = activeUid.ifBlank { null })
                when (kind) {
                    "cloud" -> {
                        repo.deleteRecords(activeDeviceId)
                        message = "حُذفت النسخة السحابية لهذا الجهاز. السجلات المحلية باقية على هاتفك ولن تُرفع مجددًا إلا إذا اخترت «إعادة رفع»."
                    }
                    "local" -> {
                        dao.deleteAllForUser(activeUid)
                        message = "حُذفت السجلات المحلية لهذا الحساب من الهاتف، والنسخة السحابية باقية."
                    }
                    "device" -> {
                        Scheduler.stop(ctx)
                        repo.deleteDevice(activeDeviceId)
                        UsernameAuth.signOut(ctx)
                    }
                    "account" -> {
                        Scheduler.stop(ctx)
                        repo.deleteAccount(password!!)
                        dao.deleteAllForUser(activeUid)
                        Prefs.resetAll()
                        UsernameAuth.signOut(ctx)
                    }
                }
            } catch (e: Exception) {
                message = AuthMessages.of(e)
            }
        }
    }

    when (confirm) {
        "cloud" -> ConfirmDialog(
            "حذف النسخة السحابية",
            "سيُحذف كل ما رُفع من هذا الجهاز في السحابة. لن تُحذف السجلات المحلية على هذا الهاتف.",
            "حذف",
            { runConfirmedAction("cloud") },
            { confirm = null },
        )
        "local" -> ConfirmDialog(
            "حذف السجلات المحلية",
            "ستُحذف السجلات من قاعدة بيانات التطبيق على هذا الهاتف فقط دون المساس بالنسخة السحابية.",
            "حذف",
            { runConfirmedAction("local") },
            { confirm = null },
        )
        "device" -> ConfirmDialog(
            "إزالة الجهاز",
            "ستُحذف بيانات هذا الجهاز من السحابة ويُسجَّل خروجك.",
            "إزالة",
            { runConfirmedAction("device") },
            { confirm = null },
        )
        "account" -> PasswordDialog(
            "حذف الحساب",
            "سيُحذف حسابك وكل نسخك الاحتياطية في السحابة نهائيًا.",
            { runConfirmedAction("account", it) },
            { confirm = null },
        )
    }
}

@Composable
private fun SyncBadge(state: Int) {
    val (bg, fg) = when (state) {
        SyncState.SYNCED -> Color(0xFFE6F4EA) to Color(0xFF137333)
        SyncState.FAILED -> Color(0xFFFCE8E6) to Color(0xFFC5221F)
        SyncState.LOCAL_ONLY -> Color(0xFFF1F3F4) to Color(0xFF3C4043)
        else -> Color(0xFFFEF7E0) to Color(0xFFB06000)
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = SyncState.labelAr(state),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(label)
    }
}
