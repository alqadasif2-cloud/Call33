package app.backup.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** جلسة الحساب النشطة ومفتاح التشفير في الذاكرة أثناء التشغيل. */
object Session {
    private val _uidFlow = MutableStateFlow<String?>(null)
    val uidFlow: StateFlow<String?> = _uidFlow.asStateFlow()

    var uid: String?
        get() = _uidFlow.value
        set(value) {
            _uidFlow.value = value
        }

    @Volatile
    var key: ByteArray? = null

    fun activate(accountUid: String, vaultKey: ByteArray) {
        key = vaultKey
        uid = accountUid
    }

    fun clear() {
        key?.fill(0)
        key = null
        uid = null
    }

    /**
     * يستعيد الجلسة المحفوظة بأمان عبر Android Keystore عند فتح التطبيق أو عمل المزامنة الخلفية،
     * فلا يُطلب من المستخدم تسجيل الدخول مجددًا ما لم يسجّل الخروج صراحةً.
     */
    fun ensureLoaded(context: Context): Pair<String, ByteArray>? {
        val memUid = uid ?: runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val memKey = key
        if (!memUid.isNullOrBlank() && memKey != null) {
            if (uid != memUid) uid = memUid
            return memUid to memKey
        }

        val vault = KeyVault(context.applicationContext)
        val stored = vault.loadSession()
        if (stored != null) {
            activate(stored.first, stored.second)
            return stored
        }

        val fbUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val legacyKey = vault.load()
        if (!fbUid.isNullOrBlank() && legacyKey != null) {
            activate(fbUid, legacyKey)
            return fbUid to legacyKey
        }
        return null
    }
}

/**
 * يحفظ معرّف الجلسة ومفتاح التشفير على هذا الجهاز مغلَّفَين بمفتاح Android Keystore (AES-256-GCM غير قابل للتصدير)،
 * ليبقى المستخدم مسجّل الدخول بأمان وتعمل المزامنة الخلفية دون تخزين أي كلمة مرور.
 */
class KeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("key_vault", Context.MODE_PRIVATE)

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun save(key: ByteArray) {
        val secret = wrapKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret)
        val ct = c.doFinal(key)
        prefs.edit()
            .putString("iv", Crypto.b64(c.iv))
            .putString("k", Crypto.b64(ct))
            .apply()
    }

    fun saveSession(uid: String, key: ByteArray) {
        val secret = wrapKey()
        val keyCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }
        val keyCt = keyCipher.doFinal(key)

        val uidCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }
        val uidCt = uidCipher.doFinal(uid.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString("iv", Crypto.b64(keyCipher.iv))
            .putString("k", Crypto.b64(keyCt))
            .putString("uid_iv", Crypto.b64(uidCipher.iv))
            .putString("uid_ct", Crypto.b64(uidCt))
            .apply()
    }

    fun load(): ByteArray? = try {
        val iv = prefs.getString("iv", null)
        val k = prefs.getString("k", null)
        if (iv == null || k == null) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Crypto.unb64(iv)))
            c.doFinal(Crypto.unb64(k))
        }
    } catch (_: Exception) {
        null
    }

    fun loadSession(): Pair<String, ByteArray>? = try {
        val iv = prefs.getString("iv", null)
        val k = prefs.getString("k", null)
        val uidIv = prefs.getString("uid_iv", null)
        val uidCt = prefs.getString("uid_ct", null)
        if (iv == null || k == null || uidIv == null || uidCt == null) {
            null
        } else {
            val secret = wrapKey()
            val keyPlain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, Crypto.unb64(iv)))
                doFinal(Crypto.unb64(k))
            }
            val uidBytes = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, Crypto.unb64(uidIv)))
                doFinal(Crypto.unb64(uidCt))
            }
            val uidStr = String(uidBytes, Charsets.UTF_8)
            if (uidStr.isBlank()) null else (uidStr to keyPlain)
        }
    } catch (_: Exception) {
        null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ALIAS = "backup_wrap_key_v1"
    }
}
