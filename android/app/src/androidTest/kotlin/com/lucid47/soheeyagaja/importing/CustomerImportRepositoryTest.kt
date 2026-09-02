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

    @Test
    fun contactsAreImportedAndDuplicatePhonesAreSkippedAcrossLists() = runBlocking {
        val repository = CustomerImportRepository(database)
        val contacts = listOf(
            ContactImportRecord("1", "김소희", "010-1234-5678", "서울", "회사 A"),
            ContactImportRecord("2", "김가자", "01012345678", "부산", "회사 B"),
            ContactImportRecord("3", "번호없음", "", "대전", ""),
        )

        val first = repository.importContacts(
            contacts = contacts,
            destination = ContactImportDestination.NewList("연락처"),
            skipDuplicatePhones = true,
        )
        val second = repository.importContacts(
            contacts = listOf(ContactImportRecord("4", "다른 이름", "010 1234 5678", "", "")),
            destination = ContactImportDestination.NewList("추가 연락처"),
            skipDuplicatePhones = true,
        )

        assertEquals(2, first.addedCount)
        assertEquals(1, first.skippedCount)
        assertEquals(2, database.customerDao().count(first.listId))
        assertEquals(0, second.addedCount)
        assertEquals(1, second.skippedCount)
    }
}
