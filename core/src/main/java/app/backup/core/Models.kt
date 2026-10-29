package app.backup.core

import org.json.JSONObject

enum class RecordType(val id: String) {
    CALL("call"), SMS("sms"), CLIP("clip");

    companion object {
        fun of(id: String): RecordType = entries.first { it.id == id }
    }
}

/** سجل بصيغته الأصلية (قبل التشفير). */
data class PlainRecord(
    val id: String,
    val deviceId: String,
    val type: RecordType,
    val ts: Long,
    val fields: JSONObject,
)

/** سجل مشفّر كما يُخزَّن في Firestore. type وts بدون تشفير لأجل الترتيب والتصفية. */
data class EncRecord(val id: String, val type: String, val ts: Long, val iv: String, val data: String)

data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String,
    val enabled: List<String>,
    val lastSyncAt: Long?,
)

object RecordIds {
    /** معرّف حتمي: نفس السجل = نفس المعرّف، فلا يتكرر في Room ولا في Firestore. */
    fun make(deviceId: String, type: RecordType, vararg parts: Any?): String =
        Crypto.sha256Hex((listOf(deviceId, type.id) + parts.map { it?.toString() ?: "" }).joinToString("\u001f"))
            .take(40)
}

object RecordCodec {
    private fun aad(id: String, type: String, ts: Long) = "$id|$type|$ts".toByteArray(Charsets.UTF_8)

    fun encrypt(key: ByteArray, r: PlainRecord): EncRecord {
        val box = Crypto.encrypt(key, r.fields.toString().toByteArray(Charsets.UTF_8), aad(r.id, r.type.id, r.ts))
        return EncRecord(r.id, r.type.id, r.ts, Crypto.b64(box.iv), Crypto.b64(box.data))
    }

    fun decrypt(key: ByteArray, deviceId: String, e: EncRecord): PlainRecord {
        val plain = Crypto.decrypt(key, Crypto.unb64(e.iv), Crypto.unb64(e.data), aad(e.id, e.type, e.ts))
        return PlainRecord(e.id, deviceId, RecordType.of(e.type), e.ts, JSONObject(String(plain, Charsets.UTF_8)))
    }
}
