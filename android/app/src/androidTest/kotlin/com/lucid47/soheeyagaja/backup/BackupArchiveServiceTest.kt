package com.lucid47.soheeyagaja.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucid47.soheeyagaja.customers.CustomerDraft
import com.lucid47.soheeyagaja.customers.CustomerManagementRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListEntity
import com.lucid47.soheeyagaja.media.CustomerMediaRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveServiceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var archive: BackupArchiveService
    private lateinit var backupFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        archive = BackupArchiveService(context, database)
        backupFile = File(context.cacheDir, "backup-test-${System.nanoTime()}.zip")
    }

    @After
    fun tearDown() {
        database.close()
        backupFile.delete()
    }

    @Test
    fun selectedBackupRestoresListCustomerAndMediaAsCopy() = runBlocking {
        val now = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(name = "2026 하반기", sourceName = "test", createdAtEpochMillis = now),
        )
        val customerId = CustomerManagementRepository(database).createCustomer(
            listId,
            CustomerDraft(name = "김소희", phone = "01012345678", notes = "상담 고객"),
        )
        val media = CustomerMediaRepository(context, database)
        val photo = media.newCameraFile(customerId).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val audio = media.newAudioFile(customerId).apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        media.saveCapturedPhoto(customerId, photo)
        media.saveAudioMemo(customerId, audio, 5_000L, "복원 테스트")

        val preview = archive.writeBackup(Uri.fromFile(backupFile), setOf(listId))
        assertEquals(1, preview.lists.size)
        assertTrue(backupFile.length() > 0L)

        val restored = archive.restore(Uri.fromFile(backupFile), setOf(listId), RestoreMode.MERGE_SELECTED)
        assertEquals(1, restored)
        val lists = database.customerListDao().observeSummaries().first()
        assertEquals(2, lists.size)
        val restoredList = lists.first { it.id != listId }
        val restoredCustomer = database.customerDao().observeByList(restoredList.id).first().single()
        assertEquals("김소희", restoredCustomer.customer.name)
        assertEquals(1, database.attachmentDao().countPhotos(restoredCustomer.customer.id))
        assertEquals(1, database.attachmentDao().countAudio(restoredCustomer.customer.id))

        photo.delete()
        audio.delete()
    }
}
