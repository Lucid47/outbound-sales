package com.lucid47.soheeyagaja.importing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucid47.soheeyagaja.data.AppDatabase
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerImportRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun tenThousandRowsAreCommittedToRoom() = runBlocking {
        val csv = buildString {
            appendLine("고객명,전화번호,주소")
            repeat(10_000) { index ->
                appendLine("고객$index,010${index.toString().padStart(8, '0')},서울시 $index")
            }
        }

        val result = CustomerImportRepository(database).importCsv(
            source = ByteArrayInputStream(csv.toByteArray()),
            listName = "대용량 테스트",
            sourceName = "customers.csv",
            onProgress = {},
        )

        assertEquals(10_000, result.progress.acceptedRows)
        assertEquals(10_000, database.customerDao().count(result.listId))
    }
}
