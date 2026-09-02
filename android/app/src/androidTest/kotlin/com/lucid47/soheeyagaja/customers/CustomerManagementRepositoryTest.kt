package com.lucid47.soheeyagaja.customers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerManagementRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CustomerManagementRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = CustomerManagementRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun customerCanBeCreatedUpdatedAndDeletedWithCustomFields() = runBlocking {
        val now = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(name = "관리 테스트", sourceName = "manual", createdAtEpochMillis = now),
        )
        val customerId = repository.createCustomer(
            listId,
            CustomerDraft(
                name = "김소희",
                phone = "010-1234-5678",
                address = "서울",
                customFields = listOf(CustomFieldDraft("등급", "VIP")),
            ),
        )

        var record = repository.observeCustomer(customerId).first()!!
        assertEquals("김소희", record.customer.name)
        assertEquals("VIP", record.customFields.single().value)

        repository.updateCustomer(
            customerId,
            CustomerDraft(
                name = "김소희 수정",
                phone = "010-1234-5678",
                address = "부산",
                customFields = listOf(CustomFieldDraft("담당", "이대희")),
            ),
        )
        record = repository.observeCustomer(customerId).first()!!
        assertEquals("김소희 수정", record.customer.name)
        assertEquals(CustomerManagementRepository.STATUS_OPEN, record.customer.status)
        assertEquals("담당", record.customFields.single().label)

        repository.deleteCustomer(customerId)
        assertEquals(0, database.customerDao().count(listId))
    }

    @Test
    fun deletingListCascadesToCustomers() = runBlocking {
        val now = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(name = "삭제 테스트", sourceName = "manual", createdAtEpochMillis = now),
        )
        repository.createCustomer(listId, CustomerDraft(name = "삭제 고객"))

        repository.deleteCustomerList(listId)

        assertFalse(database.customerListDao().exists(listId))
        assertEquals(0, database.customerDao().count(listId))
    }
}
