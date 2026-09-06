package com.lucid47.soheeyagaja.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucid47.soheeyagaja.customers.CustomerDraft
import com.lucid47.soheeyagaja.customers.CustomerManagementRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListEntity
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
class CustomerMediaRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var media: CustomerMediaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        media = CustomerMediaRepository(context, database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun savedPhotoAndAudioAppearInCustomerHistory() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(name = "미디어", sourceName = "test", createdAtEpochMillis = now),
        )
        val customerId = CustomerManagementRepository(database).createCustomer(
            listId,
            CustomerDraft(name = "김소희"),
        )
        val photo = media.newCameraFile(customerId).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val audio = media.newAudioFile(customerId).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        media.saveCapturedPhoto(customerId, photo)
        media.saveAudioMemo(customerId, audio, 12_000L, "상담 내용")

        assertEquals(1, database.attachmentDao().countPhotos(customerId))
        assertEquals(1, database.attachmentDao().countAudio(customerId))
        val history = database.activityDao().observeHistoryForCustomer(customerId).first()
        assertEquals(setOf("PHOTO", "AUDIO"), history.mapNotNull { it.mediaType }.toSet())
        assertTrue(history.any { it.detail == "상담 내용" })

        photo.delete()
        audio.delete()
    }
}
