package app.backup.source

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.backup.core.PlainRecord
import app.backup.core.RecordType
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * حالات المزامنة الواضحة لكل سجل محلي:
 * - [PENDING] (0): بانتظار الرفع (مجدول في قائمة انتظار المزامنة)
 * - [SYNCED] (1): تمت مزامنته (مؤكّد الرفع والحفظ في Firebase)
 * - [FAILED] (2): فشل الرفع (تُعاد محاولة رفعه فقط دون تكرار السجلات الناجحة)
 * - [LOCAL_ONLY] (3): محفوظ محليًا (محفوظ قبل تفعيل الرفع أو أثناء الإيقاف المؤقت)
 */
object SyncState {
    const val PENDING = 0
    const val SYNCED = 1
    const val FAILED = 2
    const val LOCAL_ONLY = 3

    fun labelAr(state: Int): String = when (state) {
        SYNCED -> "تمت مزامنته"
        FAILED -> "فشل الرفع"
        LOCAL_ONLY -> "محفوظ محليًا"
        else -> "بانتظار الرفع"
    }
}

@Entity(
    tableName = "records",
    indices = [
        Index("syncState"),
        Index("type"),
        Index(value = ["ownerUid", "syncState"]),
        Index(value = ["ownerUid", "type", "ts"]),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val type: String,
    val ts: Long,
    val json: String,
    val syncState: Int = SyncState.PENDING,
    val createdAt: Long,
    val ownerUid: String = "",
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = createdAt,
) {
    fun toPlain(deviceId: String) = PlainRecord(id, deviceId, RecordType.of(type), ts, JSONObject(json))
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(r: List<RecordEntity>): List<Long>

    /** ربط السجلات القديمة من الإصدار 1 (التي كانت بلا ownerUid) بالحساب الأساسي الأول لمرة واحدة دون فقدان أي سجل. */
    @Query("UPDATE records SET ownerUid = :uid WHERE ownerUid = ''")
    suspend fun claimLegacyUnownedRecords(uid: String): Int

    /** ترقية السجلات المحفوظة محليًا (LOCAL_ONLY=3) إلى بانتظار الرفع (PENDING=0) عند جدولة المزامنة. */
    @Query("UPDATE records SET syncState = 0, updatedAt = :now WHERE ownerUid = :uid AND syncState = 3")
    suspend fun promoteLocalToPending(uid: String, now: Long = System.currentTimeMillis()): Int

    /** السجلات التي تحتاج رفعًا فعليًا (بانتظار الرفع = 0 أو فشل الرفع سابقًا = 2) مرتّبة زمنيًا. */
    @Query("SELECT * FROM records WHERE ownerUid = :uid AND syncState IN (0, 2) ORDER BY ts ASC LIMIT :n")
    suspend fun pendingForUser(uid: String, n: Int): List<RecordEntity>

    /** توافق مع الاستدعاءات والاختبارات المباشرة عندما لا يُحدَّد حساب. */
    @Query("SELECT * FROM records WHERE syncState IN (0, 2) ORDER BY ts ASC LIMIT :n")
    suspend fun pending(n: Int): List<RecordEntity>

    @Query("UPDATE records SET syncState = 1, lastError = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE records SET syncState = 2, attemptCount = attemptCount + 1, lastError = :reason, updatedAt = :now " +
            "WHERE id IN (:ids) AND syncState != 1"
    )
    suspend fun markFailed(ids: List<String>, reason: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET syncState = 0, lastError = NULL, updatedAt = :now WHERE ownerUid = :uid")
    suspend fun resetSyncForUser(uid: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET syncState = 0, lastError = NULL")
    suspend fun resetSync()

    @Query("UPDATE records SET syncState = 0, updatedAt = :now WHERE ownerUid = :uid AND syncState = 2")
    suspend fun retryFailedNow(uid: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM records")
    fun total(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE ownerUid = :uid")
    fun totalForUser(uid: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE syncState IN (0, 2)")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE ownerUid = :uid AND syncState = :state")
    fun countByStateForUser(uid: String, state: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE ownerUid = :uid AND syncState IN (0, 2)")
    fun pendingOrFailedCountForUser(uid: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE ownerUid = :uid AND syncState IN (0, 2, 3)")
    suspend fun unsyncedCountForUserNow(uid: String): Int

    @Query("SELECT COUNT(*) FROM records WHERE ownerUid = :uid AND syncState IN (0, 2)")
    suspend fun pendingOrFailedNowForUser(uid: String): Int

    @Query("SELECT COUNT(*) FROM records WHERE syncState IN (0, 2)")
    suspend fun pendingNow(): Int

    /** للتحقق من عدم تكرار الرسالة الواردة بين مستقبل البث الفوري ومزوّد النظام Telephony.Sms. */
    @Query(
        "SELECT * FROM records WHERE ownerUid = :uid " +
            "AND type = 'sms' AND ts BETWEEN :fromTs AND :toTs"
    )
    suspend fun smsInWindow(uid: String, fromTs: Long, toTs: Long): List<RecordEntity>

    @Query(
        "SELECT * FROM records WHERE ownerUid = :uid " +
            "AND type = :type ORDER BY ts DESC LIMIT 1"
    )
    fun latestForType(uid: String, type: String): Flow<RecordEntity?>

    @Query(
        "SELECT * FROM records WHERE ownerUid = :uid " +
            "AND type = :type ORDER BY ts DESC LIMIT :n"
    )
    fun recentForUser(uid: String, type: String, n: Int): Flow<List<RecordEntity>>

    @Query(
        "SELECT * FROM records WHERE ownerUid = :uid " +
            "ORDER BY ts DESC LIMIT :n"
    )
    fun recentAllForUser(uid: String, n: Int): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE type = :type ORDER BY ts DESC LIMIT :n")
    fun recent(type: String, n: Int): Flow<List<RecordEntity>>

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM records WHERE ownerUid = :uid")
    suspend fun deleteAllForUser(uid: String)

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}

@Database(entities = [RecordEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE records ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_ownerUid_syncState ON records (ownerUid, syncState)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_ownerUid_type_ts ON records (ownerUid, type, ts)")
            }
        }

        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "backup.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { inst = it }
        }
    }
}
