package app.backup.core

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object CloudInit {
    internal val DEFAULT_PROJECT_ID: String = BuildConfig.FIREBASE_PROJECT_ID
    private val DEFAULT_APP_ID: String = BuildConfig.FIREBASE_APP_ID
    private val DEFAULT_API_KEY: String = BuildConfig.FIREBASE_API_KEY
    private val DEFAULT_SENDER_ID: String = BuildConfig.FIREBASE_SENDER_ID
    private val DEFAULT_STORAGE_BUCKET: String = BuildConfig.FIREBASE_STORAGE_BUCKET
    private const val CLOUD_FUNCTION_REGION = "us-central1"
    val PROVISIONED_DB_ID: String = BuildConfig.FIREBASE_DATABASE_ID

    @Volatile
    var databaseId: String = PROVISIONED_DB_ID

    fun projectCustomAuthEndpoint(): String {
        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }
            .getOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { DEFAULT_PROJECT_ID }
        return "https://$CLOUD_FUNCTION_REGION-$projectId.cloudfunctions.net/authUsername"
    }

    fun firestore(): FirebaseFirestore {
        val app = FirebaseApp.getInstance()
        val useNamedDb = app.options.projectId == DEFAULT_PROJECT_ID &&
            databaseId.isNotBlank() &&
            databaseId != "(default)"
        return if (useNamedDb) {
            FirebaseFirestore.getInstance(app, databaseId)
        } else {
            FirebaseFirestore.getInstance(app)
        }
    }

    /** نستخدم Room كمخزن محلي وحيد، لذا نعطّل كاش Firestore الدائم كي لا تُعلَّق الكتابات بصمت دون تأكيد الخادم. */
    fun configure(ctx: Context? = null) {
        runCatching {
            if (ctx != null && FirebaseApp.getApps(ctx).isEmpty()) {
                val opts = FirebaseOptions.Builder()
                    .setProjectId(DEFAULT_PROJECT_ID)
                    .setApplicationId(DEFAULT_APP_ID)
                    .setApiKey(DEFAULT_API_KEY)
                    .setGcmSenderId(DEFAULT_SENDER_ID)
                    .setStorageBucket(DEFAULT_STORAGE_BUCKET)
                    .build()
                FirebaseApp.initializeApp(ctx, opts)
            }
            firestore().firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }
}

object AuthMessages {
    fun of(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            e is UsernameAuthException -> raw.ifBlank { "تعذّر إتمام المصادقة." }
            e is FirebaseAuthUserCollisionException -> "اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر."
            e is FirebaseAuthInvalidCredentialsException || e is FirebaseAuthInvalidUserException ->
                "اسم المستخدم أو كلمة المرور غير صحيحة."
            e is FirebaseAuthWeakPasswordException -> "يجب أن تتكون كلمة المرور من 8 أحرف على الأقل."
            e is FirebaseTooManyRequestsException ->
                "تم إيقاف المحاولات مؤقتًا لكثرة المحاولات الخاطئة. يرجى الانتظار قليلًا ثم المحاولة مجددًا."
            e is FirebaseAuthRecentLoginRequiredException ->
                "يلزم تسجيل دخول حديث. سجّل الخروج ثم الدخول وأعد المحاولة."
            e is FirebaseNetworkException -> "لا يوجد اتصال بالإنترنت. تحقق من الشبكة وأعد المحاولة."
            e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
                "لا يوجد اتصال بالإنترنت حاليًا. تحقق من الشبكة وأعد المحاولة."
            e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "رفضت قواعد الأمان السحابية هذه العملية. تأكد من صلاحية الجلسة وأن البيانات تتبع لحسابك."
            e is FirebaseFirestoreException -> "تعذّرت العملية السحابية (${e.code.name})"
            raw.isNotBlank() && raw.any { it in '\u0600'..'\u06FF' } -> raw
            else -> "تعذّر إتمام العملية السحابية. تحقق من الاتصال وأعد المحاولة."
        }
    }
}

data class PageResult(val items: List<EncRecord>, val cursor: DocumentSnapshot?, val exhausted: Boolean)

class CloudRepo(
    private val db: FirebaseFirestore = CloudInit.firestore(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val explicitUid: String? = null,
) {
    private val uid: String
        get() = explicitUid
            ?: Session.uid
            ?: auth.currentUser?.uid
            ?: throw IllegalStateException("not signed in")

    private fun user() = db.collection("users").document(uid)
    private fun usernameDoc(usernameId: String) = db.collection("usernames").document(usernameId)
    private fun cryptoDoc() = user().collection("meta").document("crypto")
    private fun devices() = user().collection("devices")
    private fun records(deviceId: String) = devices().document(deviceId).collection("records")

    // ---------- تسجيل حساب سحابي ذري ومنع تكرار اسم المستخدم ----------
    suspend fun registerCapabilityAccount(norm: NormalizedUsername, pass: CharArray): ByteArray {
        val salt = Crypto.randomBytes(16)
        val key = withContext(Dispatchers.Default) { Crypto.deriveKey(pass, salt) }
        val box = Crypto.encrypt(key, CHECK_PLAIN, CHECK_AAD)

        val b = db.batch()
        b.set(
            usernameDoc(norm.usernameId),
            mapOf(
                "uid" to uid,
                "usernameLower" to norm.usernameLower,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        )
        b.set(
            user(),
            mapOf(
                "uid" to uid,
                "username" to norm.username,
                "usernameLower" to norm.usernameLower,
                "usernameId" to norm.usernameId,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        )
        b.set(
            cryptoDoc(),
            mapOf(
                "uid" to uid,
                "salt" to Crypto.b64(salt),
                "iter" to Crypto.ITERATIONS,
                "checkIv" to Crypto.b64(box.iv),
                "check" to Crypto.b64(box.data),
                "v" to 1,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        )

        try {
            b.commit().await()
            return key
        } catch (e: FirebaseFirestoreException) {
            key.fill(0)
            if (
                e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS
            ) {
                throw UsernameAuthException("اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر.")
            }
            throw e
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
    }

    /** يتحقق من وجود ملف الحساب السحابي على Firestore ويعيد اسم المستخدم المحفوظ أو null إذا لم يطابق. */
    suspend fun lookupCapabilityAccountUsername(): String? {
        val snap = user().get().await()
        if (!snap.exists()) return null
        return snap.getString("username")?.takeIf { it.length >= 3 }
    }

    // ---------- ملف الحساب واسم المستخدم ----------
    suspend fun getCurrentUsername(): String? {
        val snap = user().get().await()
        return if (snap.exists()) snap.getString("username") else auth.currentUser?.displayName
    }

    suspend fun ensureUserProfile(norm: NormalizedUsername, isNewRegistration: Boolean) {
        val existing = user().get().await()
        if (existing.exists()) return

        if (isNewRegistration) {
            try {
                usernameDoc(norm.usernameId).set(
                    mapOf(
                        "uid" to uid,
                        "usernameLower" to norm.usernameLower,
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    runCatching { auth.currentUser?.delete()?.await() }
                    throw UsernameAuthException("اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر.")
                }
                throw e
            }
        } else {
            runCatching {
                usernameDoc(norm.usernameId).set(
                    mapOf(
                        "uid" to uid,
                        "usernameLower" to norm.usernameLower,
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
        }

        user().set(
            mapOf(
                "uid" to uid,
                "username" to norm.username,
                "usernameLower" to norm.usernameLower,
                "usernameId" to norm.usernameId,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    // ---------- التشفير ----------
    suspend fun hasCrypto(): Boolean = cryptoDoc().get().await().exists()

    suspend fun createCrypto(pass: CharArray): ByteArray {
        val salt = Crypto.randomBytes(16)
        val key = withContext(Dispatchers.Default) { Crypto.deriveKey(pass, salt) }
        val box = Crypto.encrypt(key, CHECK_PLAIN, CHECK_AAD)
        cryptoDoc().set(
            mapOf(
                "uid" to uid,
                "salt" to Crypto.b64(salt),
                "iter" to Crypto.ITERATIONS,
                "checkIv" to Crypto.b64(box.iv),
                "check" to Crypto.b64(box.data),
                "v" to 1,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        return key
    }

    /** يعيد المفتاح، أو null إذا كانت كلمة المرور خاطئة. */
    suspend fun unlock(pass: CharArray): ByteArray? {
        val d = cryptoDoc().get().await()
        if (!d.exists()) return null
        val salt = Crypto.unb64(d.getString("salt")!!)
        val iter = (d.getLong("iter") ?: Crypto.ITERATIONS.toLong()).toInt()
        val key = withContext(Dispatchers.Default) { Crypto.deriveKey(pass, salt, iter) }
        return try {
            val plain = Crypto.decrypt(
                key,
                Crypto.unb64(d.getString("checkIv")!!),
                Crypto.unb64(d.getString("check")!!),
                CHECK_AAD,
            )
            if (plain.contentEquals(CHECK_PLAIN)) key else {
                key.fill(0)
                null
            }
        } catch (_: Exception) {
            key.fill(0)
            null
        }
    }

    // ---------- الأجهزة ----------
    suspend fun registerDevice(id: String, name: String, model: String, enabled: List<String>) {
        val safeName = name.trim().ifEmpty { "هاتف أندرويد" }.take(100)
        val safeModel = model.trim().ifEmpty { "Android" }.take(100)
        val safeEnabled = enabled.filter { it in ALLOWED_TYPES }.distinct().take(3)
        val ref = devices().document(id)
        val snap = ref.get().await()
        if (!snap.exists()) {
            ref.set(
                mapOf(
                    "ownerId" to uid,
                    "name" to safeName,
                    "model" to safeModel,
                    "enabled" to safeEnabled,
                    "lastSyncAt" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        } else {
            ref.update(
                mapOf(
                    "name" to safeName,
                    "model" to safeModel,
                    "enabled" to safeEnabled,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
    }

    suspend fun markSynced(deviceId: String, at: Long) {
        devices().document(deviceId).update(
            mapOf(
                "lastSyncAt" to at.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    suspend fun listDevices(): List<DeviceInfo> =
        devices().whereEqualTo("ownerId", uid).get().await().documents.map {
            val sync = it.getLong("lastSyncAt")
            DeviceInfo(
                id = it.id,
                name = it.getString("name") ?: it.id,
                model = it.getString("model") ?: "",
                enabled = (it.get("enabled") as? List<*>)?.map { x -> x.toString() } ?: emptyList(),
                lastSyncAt = if (sync != null && sync > 0L) sync else null,
            )
        }.sortedByDescending { it.lastSyncAt ?: 0L }

    suspend fun count(deviceId: String, type: RecordType? = null, from: Long? = null, to: Long? = null): Long {
        val docs = records(deviceId).whereEqualTo("ownerId", uid).get().await().documents
        return docs.count { d ->
            val t = d.getString("type")
            val ts = d.getLong("ts") ?: 0L
            (type == null || t == type.id) &&
                (from == null || ts >= from) &&
                (to == null || ts <= to)
        }.toLong()
    }

    // ---------- السجلات ----------
    /** يُستدعى بدفعات ≤ 400. يتحقق من وجود معرّفات الدفعة الحالية فقط بدلاً من قراءة كل السجلات السابقة. */
    suspend fun upload(deviceId: String, recs: List<EncRecord>) {
        require(recs.size <= 400)
        if (recs.isEmpty()) return
        val uniqueRecs = recs.associateBy { it.id }.values.toList()
        val existingIds = HashSet<String>(uniqueRecs.size)
        for (chunk in uniqueRecs.map { it.id }.chunked(30)) {
            val snap = records(deviceId)
                .whereEqualTo("ownerId", uid)
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                .get()
                .await()
            snap.documents.forEach { existingIds.add(it.id) }
        }
        val b = db.batch()
        uniqueRecs.forEach { rec ->
            val ref = records(deviceId).document(rec.id)
            if (rec.id in existingIds) {
                b.update(
                    ref,
                    mapOf(
                        "iv" to rec.iv,
                        "data" to rec.data,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
            } else {
                b.set(
                    ref,
                    mapOf(
                        "ownerId" to uid,
                        "deviceId" to deviceId,
                        "type" to rec.type,
                        "ts" to rec.ts,
                        "iv" to rec.iv,
                        "data" to rec.data,
                        "v" to 1,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
            }
        }
        b.commit().await()
    }

    suspend fun page(
        deviceId: String, type: RecordType, from: Long?, to: Long?, after: DocumentSnapshot?, size: Long = 50,
    ): PageResult {
        val allMatching = records(deviceId).whereEqualTo("ownerId", uid).get().await().documents
            .filter { d ->
                val t = d.getString("type")
                val ts = d.getLong("ts") ?: 0L
                t == type.id &&
                    (from == null || ts >= from) &&
                    (to == null || ts <= to)
            }
            .sortedWith(compareByDescending<DocumentSnapshot> { it.getLong("ts") ?: 0L }.thenBy { it.id })

        val startIdx = if (after == null) {
            0
        } else {
            val idx = allMatching.indexOfFirst { it.id == after.id }
            if (idx >= 0) idx + 1 else allMatching.size
        }

        val slice = allMatching.drop(startIdx).take(size.toInt())
        val items = slice.mapNotNull { d ->
            val t = d.getString("type") ?: return@mapNotNull null
            val ts = d.getLong("ts") ?: return@mapNotNull null
            val iv = d.getString("iv") ?: return@mapNotNull null
            val data = d.getString("data") ?: return@mapNotNull null
            EncRecord(d.id, t, ts, iv, data)
        }
        val exhausted = (startIdx + slice.size) >= allMatching.size
        return PageResult(items, slice.lastOrNull(), exhausted)
    }

    // ---------- الحذف ----------
    suspend fun deleteRecord(deviceId: String, id: String) {
        records(deviceId).document(id).delete().await()
    }

    suspend fun deleteRecords(deviceId: String) {
        while (true) {
            val snap = records(deviceId).whereEqualTo("ownerId", uid).limit(350).get().await()
            if (snap.isEmpty) break
            val b = db.batch()
            snap.documents.forEach { b.delete(it.reference) }
            b.commit().await()
        }
    }

    suspend fun deleteDevice(deviceId: String) {
        deleteRecords(deviceId)
        devices().document(deviceId).delete().await()
    }

    /** يتحقق من كلمة المرور، يحذف كل بيانات السحابة، ثم يحذف الحساب وسجل الاسم ذريًا. */
    suspend fun deleteAccount(password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) throw UsernameAuthException("يرجى إدخال كلمة المرور لتأكيد الحذف.")
        val fbUser = auth.currentUser

        if (fbUser != null && fbUser.email != null && fbUser.providerData.any { it.providerId == "password" }) {
            fbUser.reauthenticate(EmailAuthProvider.getCredential(fbUser.email!!, trimmed)).await()
        } else if (hasCrypto()) {
            val verifiedKey = unlock(trimmed.toCharArray())
                ?: throw UsernameAuthException("كلمة المرور غير صحيحة.")
            verifiedKey.fill(0)
        }

        val profileSnap = user().get().await()
        val claimedUsernameId = profileSnap.getString("usernameId")

        listDevices().forEach { deleteDevice(it.id) }
        runCatching { cryptoDoc().delete().await() }

        val deletedViaCustomFn = if (fbUser != null) {
            UsernameAuth.deleteCustomAuthAccountIfConfigured(trimmed, auth)
        } else {
            false
        }
        if (!deletedViaCustomFn) {
            val finalBatch = db.batch()
            finalBatch.delete(user())
            if (!claimedUsernameId.isNullOrBlank()) {
                finalBatch.delete(usernameDoc(claimedUsernameId))
            }
            finalBatch.commit().await()
            fbUser?.delete()?.await()
        }
        Session.clear()
    }

    private companion object {
        val CHECK_PLAIN = "backup-check-v1".toByteArray()
        val CHECK_AAD = "check".toByteArray()
        val ALLOWED_TYPES = setOf("call", "sms", "clip")
    }
}
