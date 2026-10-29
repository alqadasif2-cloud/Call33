package app.backup.source

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class SyncErrorInfo(
    val summary: String,
    val remediation: String,
    val occurredAt: Long,
)

object Prefs {
    @Volatile
    private var spRef: SharedPreferences? = null

    private val sp: SharedPreferences
        get() = spRef ?: throw IllegalStateException("Prefs.init(context) must be called first")

    val isInitialized: Boolean
        get() = spRef != null

    val enabled = MutableStateFlow<Set<String>>(emptySet())
    val paused = MutableStateFlow(false)
    val lastSyncAt = MutableStateFlow(0L)
    val lastScanAt = MutableStateFlow(0L)
    val onboardingDone = MutableStateFlow(false)
    val deviceName = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)
    val lastSyncError = MutableStateFlow<SyncErrorInfo?>(null)
    val quickClipNotifEnabled = MutableStateFlow(false)
    val syncInFlight = MutableStateFlow(false)

    val deviceId: String
        get() = sp.getString("deviceId", "")!!

    /**
     * يحافظ على معرّف الجهاز الأصلي للمالك الأول، ويعزل أي مستخدم مختلف يسجّل الدخول على نفس الهاتف
     * بمعرّف جهاز مستقل حتى لا تختلط النسخ الاحتياطية بين الحسابات.
     */
    @Synchronized
    fun isPrimaryOwner(uid: String): Boolean {
        if (uid.isBlank()) return false
        val primaryOwner = sp.getString("primary_owner_uid", null)
        if (primaryOwner == null) {
            sp.edit().putString("primary_owner_uid", uid).apply()
            return true
        }
        return primaryOwner == uid
    }

    @Synchronized
    fun deviceIdForUser(uid: String?): String {
        val baseId = deviceId
        if (uid.isNullOrBlank()) return baseId
        if (isPrimaryOwner(uid)) return baseId
        val scopedKey = "deviceId_for_$uid"
        val existing = sp.getString(scopedKey, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        sp.edit().putString(scopedKey, generated).apply()
        return generated
    }

    @Synchronized
    fun init(ctx: Context) {
        if (spRef != null) return
        spRef = ctx.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)
        load()
    }

    @Synchronized
    fun ensureInit(ctx: Context) {
        if (spRef == null) init(ctx)
    }

    private fun load() {
        if (!sp.contains("deviceId")) {
            sp.edit().putString("deviceId", UUID.randomUUID().toString()).apply()
        }
        enabled.value = sp.getStringSet("enabled", emptySet())?.toSet() ?: emptySet()
        paused.value = sp.getBoolean("paused", false)
        lastSyncAt.value = sp.getLong("lastSync", 0L)
        lastScanAt.value = sp.getLong("lastScan", 0L)
        onboardingDone.value = sp.getBoolean("onboarded", false)
        deviceName.value = sp.getString("deviceName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        quickClipNotifEnabled.value = sp.getBoolean("quickClipNotif", false)

        val errSummary = sp.getString("lastErrSummary", null)
        val errFix = sp.getString("lastErrFix", null)
        val errAt = sp.getLong("lastErrAt", 0L)
        lastSyncError.value = if (!errSummary.isNullOrBlank() && errAt > 0L) {
            SyncErrorInfo(errSummary, errFix.orEmpty(), errAt)
        } else {
            null
        }
    }

    fun setEnabled(typeId: String, on: Boolean) {
        val s = enabled.value.toMutableSet().apply { if (on) add(typeId) else remove(typeId) }
        sp.edit().putStringSet("enabled", s).apply()
        enabled.value = s
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim().ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }.take(100)
        sp.edit().putString("deviceName", trimmed).apply()
        deviceName.value = trimmed
    }

    fun setPaused(v: Boolean) {
        sp.edit().putBoolean("paused", v).apply()
        paused.value = v
    }

    fun setQuickClipNotifEnabled(v: Boolean) {
        sp.edit().putBoolean("quickClipNotif", v).apply()
        quickClipNotifEnabled.value = v
    }

    fun setLastSync(v: Long) {
        sp.edit().putLong("lastSync", v).apply()
        lastSyncAt.value = v
    }

    fun setLastScan(v: Long) {
        sp.edit().putLong("lastScan", v).apply()
        lastScanAt.value = v
    }

    fun recordSyncError(summary: String, remediation: String, at: Long = System.currentTimeMillis()) {
        sp.edit()
            .putString("lastErrSummary", summary)
            .putString("lastErrFix", remediation)
            .putLong("lastErrAt", at)
            .apply()
        lastSyncError.value = SyncErrorInfo(summary, remediation, at)
    }

    fun clearSyncError() {
        sp.edit()
            .remove("lastErrSummary")
            .remove("lastErrFix")
            .remove("lastErrAt")
            .apply()
        lastSyncError.value = null
    }

    fun cursor(type: String, uid: String? = null): Long {
        if (!uid.isNullOrBlank()) {
            val scoped = sp.getLong("cur_${uid}_$type", -1L)
            if (scoped >= 0L) return scoped
        }
        return sp.getLong("cur_$type", 0L)
    }

    fun setCursor(type: String, v: Long, uid: String? = null) {
        val editor = sp.edit().putLong("cur_$type", v)
        if (!uid.isNullOrBlank()) {
            editor.putLong("cur_${uid}_$type", v)
        }
        editor.apply()
    }

    var lastCheckedClipTimestamp: Long
        get() = sp.getLong("lastClipDescTs", 0L)
        set(value) {
            sp.edit().putLong("lastClipDescTs", value).apply()
        }

    var lastCapturedClipHash: String
        get() = sp.getString("lastClipHash", "").orEmpty()
        set(value) {
            sp.edit().putString("lastClipHash", value).apply()
        }

    fun completeOnboarding(name: String, types: Set<String>) {
        val cleanName = name.trim().ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }.take(100)
        sp.edit()
            .putString("deviceName", cleanName)
            .putStringSet("enabled", types)
            .putBoolean("onboarded", true)
            .apply()
        deviceName.value = cleanName
        enabled.value = types
        onboardingDone.value = true
    }

    /** بعد حذف الحساب: تهيئة كاملة بمعرّف جهاز جديد. */
    fun resetAll() {
        sp.edit().clear().apply()
        load()
    }
}
