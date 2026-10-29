@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.backup.viewer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.backup.core.AuthMessages
import app.backup.core.CloudRepo
import app.backup.core.DeviceInfo
import app.backup.core.PlainRecord
import app.backup.core.RecordCodec
import app.backup.core.RecordType
import app.backup.core.Session
import app.backup.core.UsernameAuth
import app.backup.core.ui.AuthGate
import app.backup.core.ui.BackupTheme
import app.backup.core.ui.ConfirmDialog
import app.backup.core.ui.Loading
import app.backup.core.ui.PasswordDialog
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackupTheme { AuthGate { Root() } } }
    }
}

private fun fmt(ms: Long?) =
    if (ms == null || ms == 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date(ms))

private fun typeAr(t: RecordType) = when (t) { RecordType.CALL -> "سجل المكالمات"; RecordType.SMS -> "الرسائل"; RecordType.CLIP -> "الحافظة" }
private fun typeAr(id: String) = runCatching { typeAr(RecordType.of(id)) }.getOrDefault(id)
private fun callTypeAr(s: String) = when (s) {
    "incoming" -> "واردة"; "outgoing" -> "صادرة"; "missed" -> "فائتة"; "rejected" -> "مرفوضة"
    "blocked" -> "محظورة"; "voicemail" -> "بريد صوتي"; else -> "أخرى"
}
private fun smsTypeAr(s: String) = when (s) {
    "inbox" -> "واردة"; "sent" -> "مرسلة"; "draft" -> "مسودة"; "outbox" -> "صادر"; "failed" -> "فاشلة"; else -> "أخرى"
}

@Composable
fun Root() {
    var selected by remember { mutableStateOf<DeviceInfo?>(null) }
    var exportFor by remember { mutableStateOf<DeviceInfo?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    val s = selected
    if (s == null) DevicesScreen(onOpen = { selected = it }, onExport = { exportFor = it })
    else DeviceScreen(s, onBack = { selected = null }, onExport = { exportFor = s })
    exportFor?.let { ExportDialog(it) { exportFor = null } }
}

// ---------------------------------------------------------------- قائمة الأجهزة
@Composable
fun DevicesScreen(onOpen: (DeviceInfo) -> Unit, onExport: (DeviceInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<DeviceInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        error = null
        try { devices = CloudRepo().listDevices() } catch (e: Exception) { error = AuthMessages.of(e) }
    }

    val currentUsername = remember { UsernameAuth.savedUsername(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("أجهزتي", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (!currentUsername.isNullOrBlank()) "الحساب النشط: $currentUsername — الأجهزة المسجّلة ضمن حسابك فقط."
            else "الأجهزة المسجّلة ضمن حسابك فقط.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { reload++ }) { Text("تحديث") }
            OutlinedButton(onClick = { UsernameAuth.signOut(ctx) }) { Text("تسجيل الخروج") }
            OutlinedButton(onClick = { confirm = "account" }) { Text("حذف الحساب") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val list = devices
        if (list == null && error == null) Loading()
        if (list != null && list.isEmpty()) Text("لا توجد أجهزة بعد. شغّل تطبيق النسخ الاحتياطي على هاتفك وسجّل الدخول بالحساب نفسه.")
        list?.forEach { d ->
            val counts by produceCounts(d.id)
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Text(d.model, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("عدد السجلات المتاحة: ${counts?.values?.sum() ?: "…"}")
                    Text(
                        "المكالمات: ${counts?.get(RecordType.CALL) ?: "…"} — الرسائل: ${counts?.get(RecordType.SMS) ?: "…"} — الحافظة: ${counts?.get(RecordType.CLIP) ?: "…"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("الأنواع المفعّلة: ${d.enabled.joinToString("، ") { typeAr(it) }.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                    Text("آخر مزامنة ناجحة: ${fmt(d.lastSyncAt)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onOpen(d) }) { Text("استعراض البيانات") }
                        OutlinedButton(onClick = { onExport(d) }) { Text("تنزيل النسخة") }
                    }
                    Row {
                        TextButton(onClick = { target = d; confirm = "cloud" }) { Text("حذف نسخته السحابية") }
                        TextButton(onClick = { target = d; confirm = "device" }) { Text("إزالة الجهاز") }
                    }
                }
            }
        }
    }

    fun run(kind: String, password: String? = null) {
        confirm = null
        scope.launch {
            try {
                val repo = CloudRepo()
                when (kind) {
                    "cloud" -> { repo.deleteRecords(target!!.id); message = "حُذفت النسخة السحابية للجهاز. الملفات المنزَّلة سابقًا لم تتأثر." }
                    "device" -> { repo.deleteDevice(target!!.id); message = "أُزيل الجهاز من الحساب مع بياناته السحابية." }
                    "account" -> { repo.deleteAccount(password!!); UsernameAuth.signOut(ctx) }
                }
                if (kind != "account") reload++
            } catch (e: Exception) { message = AuthMessages.of(e) }
        }
    }
    when (confirm) {
        "cloud" -> ConfirmDialog("حذف النسخة السحابية", "سيُحذف كل ما رُفع من «${target?.name}» من السحابة فقط. الملفات التي نزّلتها إلى ذاكرة هاتفك لن تُحذف.", "حذف", { run("cloud") }, { confirm = null })
        "device" -> ConfirmDialog("إزالة الجهاز", "سيُحذف الجهاز وبياناته السحابية من حسابك. الملفات المنزَّلة لن تُحذف.", "إزالة", { run("device") }, { confirm = null })
        "account" -> PasswordDialog("حذف الحساب", "سيُحذف حسابك وكل نسخك الاحتياطية في السحابة نهائيًا. الملفات المنزَّلة إلى الذاكرة لن تُحذف.", { run("account", it) }, { confirm = null })
    }
}

@Composable
private fun produceCounts(deviceId: String) = androidx.compose.runtime.produceState<Map<RecordType, Long>?>(null, deviceId) {
    value = try {
        val repo = CloudRepo()
        RecordType.entries.associateWith { repo.count(deviceId, it) }
    } catch (e: Exception) { null }
}

// ---------------------------------------------------------------- استعراض بيانات جهاز
private enum class Period(val label: String, val days: Long?) {
    ALL("الكل", null), D7("7 أيام", 7), D30("30 يومًا", 30), Y1("سنة", 365)
}

@Composable
fun DeviceScreen(device: DeviceInfo, onBack: () -> Unit, onExport: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = remember { CloudRepo() }
    var tab by remember { mutableStateOf(0) }
    var period by remember { mutableStateOf(Period.ALL) }
    val items = remember { mutableStateListOf<PlainRecord>() }
    var cursor by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<PlainRecord?>(null) }
    val type = RecordType.entries[tab]

    fun fromTs(): Long? = period.days?.let { System.currentTimeMillis() - it * 86_400_000L }

    fun loadMore() {
        if (loading || exhausted) return
        loading = true
        scope.launch {
            try {
                val key = Session.key ?: throw IllegalStateException("locked")
                val p = repo.page(device.id, type, fromTs(), null, cursor, 50)
                p.items.forEach { items += RecordCodec.decrypt(key, device.id, it) }
                cursor = p.cursor; exhausted = p.exhausted
            } catch (e: Exception) {
                error = if (e is IllegalStateException) "المفتاح غير متاح" else AuthMessages.of(e)
            } finally { loading = false }
        }
    }

    LaunchedEffect(tab, period) { items.clear(); cursor = null; exhausted = false; error = null; loading = false; loadMore() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(device.name, style = MaterialTheme.typography.titleLarge)
            Row { TextButton(onClick = onExport) { Text("تنزيل") }; TextButton(onClick = onBack) { Text("رجوع") } }
        }
        TabRow(selectedTabIndex = tab) {
            RecordType.entries.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(typeAr(t)) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!loading && items.isEmpty() && error == null) Text("لا توجد سجلات في هذا القسم والفترة.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    val f = r.fields
                    when (r.type) {
                        RecordType.CALL -> {
                            Text(f.optString("name").ifBlank { f.optString("number").ifBlank { "رقم مخفي" } }, style = MaterialTheme.typography.bodyLarge)
                            Text("${callTypeAr(f.optString("type"))} — ${f.optLong("durationSec")} ث — ${f.optString("number")}", style = MaterialTheme.typography.bodySmall)
                        }
                        RecordType.SMS -> {
                            Text("${f.optString("address")} (${smsTypeAr(f.optString("type"))})", style = MaterialTheme.typography.bodyLarge)
                            Text(f.optString("body"), style = MaterialTheme.typography.bodyMedium)
                        }
                        RecordType.CLIP -> Text(f.optString("text"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(fmt(r.ts), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { toDelete = r }) { Text("حذف") }
                    }
                    HorizontalDivider()
                }
            }
            item {
                if (loading) Loading2()
                else if (!exhausted && items.isNotEmpty()) {
                    LaunchedEffect(items.size) { loadMore() } // تحميل تدريجي عند الوصول لنهاية القائمة
                }
            }
        }
    }

    toDelete?.let { r ->
        ConfirmDialog("حذف السجل", "سيُحذف هذا السجل من السحابة نهائيًا. أي ملف نزّلته سابقًا لن يتأثر.", "حذف", {
            toDelete = null
            scope.launch {
                try { repo.deleteRecord(device.id, r.id); items.remove(r) } catch (e: Exception) { error = AuthMessages.of(e) }
            }
        }, { toDelete = null })
    }
}

@Composable
private fun Loading2() {
    androidx.compose.material3.CircularProgressIndicator(Modifier.padding(16.dp))
}

// ---------------------------------------------------------------- تنزيل النسخة
@Composable
fun ExportDialog(device: DeviceInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val types = remember { mutableStateListOf(*RecordType.entries.toTypedArray()) }
    val formats = remember { mutableStateListOf("csv", "json") }
    var zip by remember { mutableStateOf(true) }
    var period by remember { mutableStateOf(Period.ALL) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var req by remember { mutableStateOf<Exporter.Req?>(null) }

    val mustZip = types.size * formats.size > 1
    val useZip = zip || mustZip

    fun start(uri: Uri?) {
        val r = req ?: return
        if (uri == null) return
        progress = 0L to 1L; result = null; failed = false
        scope.launch {
            try {
                val key = Session.key ?: throw IllegalStateException("locked")
                result = Exporter.run(ctx, uri, r, key, CloudRepo()) { d, t -> progress = d to t }
            } catch (e: Exception) {
                failed = true
                result = "فشل التنزيل: ${e.message ?: e.javaClass.simpleName}"
            } finally { progress = null }
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { start(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { start(it) }

    AlertDialog(
        onDismissRequest = { if (progress == null) onClose() },
        title = { Text("تنزيل نسخة «${device.name}»") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("البيانات", style = MaterialTheme.typography.titleSmall)
                RecordType.entries.forEach { t ->
                    CheckLine(typeAr(t), t in types) { if (it) types += t else types -= t }
                }
                Text("الصيغة", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                listOf("csv", "json", "txt").forEach { f ->
                    CheckLine(f.uppercase(), f in formats) { if (it) formats += f else formats -= f }
                }
                Text("الفترة", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
                }
                CheckLine("ملف ZIP مجمّع" + if (mustZip) " (مطلوب لتعدد الملفات)" else "", useZip) { if (!mustZip) zip = it }
                progress?.let { (d, t) ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (t == 0L) 0f else (d.toFloat() / t).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("$d / $t سجل", style = MaterialTheme.typography.bodySmall)
                }
                result?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null && types.isNotEmpty() && formats.isNotEmpty(), onClick = {
                val from = period.days?.let { System.currentTimeMillis() - it * 86_400_000L }
                val r = Exporter.Req(device.id, device.name, types.toSet(), formats.toSet(), from, null, useZip)
                req = r
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date())
                if (useZip) zipPicker.launch("backup-${device.name.replace(Regex("[^\\p{L}\\p{N}]+"), "_")}-$stamp.zip")
                else filePicker.launch(r.singleFileName)
            }) { Text("اختيار مكان الحفظ") }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = onClose) { Text("إغلاق") } },
    )
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}
