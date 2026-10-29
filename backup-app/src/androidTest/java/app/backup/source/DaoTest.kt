package app.backup.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** اختبار أجهزة حقيقي (يتطلب محاكيًا/هاتفًا): connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDb

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun duplicatesIgnoredAndSyncStateTracked() = runBlocking {
        val dao = db.dao()
        val e = RecordEntity("a", "sms", 1L, "{}", 0, 1L)
        assertEquals(1L > 0, dao.insertAll(listOf(e))[0] != -1L)
        assertEquals(-1L, dao.insertAll(listOf(e))[0])
        assertEquals(1, dao.pendingCount().first())
        dao.markSynced(listOf("a"))
        assertEquals(0, dao.pendingCount().first())
        assertEquals(1, dao.total().first())
        dao.resetSync()
        assertEquals(1, dao.pendingCount().first())
    }

    @Test fun offlineDataSurvivesAndKeepsOrder() = runBlocking {
        val dao = db.dao()
        dao.insertAll(listOf(RecordEntity("b", "call", 20L, "{}", 0, 1L), RecordEntity("c", "call", 10L, "{}", 0, 1L)))
        assertEquals(listOf("c", "b"), dao.pending(10).map { it.id })
    }
}
