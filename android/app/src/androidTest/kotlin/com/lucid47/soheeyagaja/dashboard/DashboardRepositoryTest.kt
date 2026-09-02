package com.lucid47.soheeyagaja.dashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucid47.soheeyagaja.customers.CustomerDraft
import com.lucid47.soheeyagaja.customers.CustomerManagementRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerManagementRepository
    private lateinit var dashboard: DashboardRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        customers = CustomerManagementRepository(database)
        dashboard = DashboardRepository(database)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun defaultsResizeAndCustomerStatusArePersisted() = runBlocking {
        dashboard.ensureDefaults()
        assertEquals(5, dashboard.observeStatuses().first().size)

        val now = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(name = "대시보드", sourceName = "test", createdAtEpochMillis = now),
        )
        val customerId = customers.createCustomer(listId, CustomerDraft(name = "김소희"))
        dashboard.setStatusCount(7)
        val statuses = dashboard.observeStatuses().first()
        assertEquals(7, statuses.size)

        dashboard.setCustomerStatus(customerId, statuses.last().id)
        assertEquals(statuses.last().id, database.customerDao().getById(customerId)?.dashboardStatusId)
        assertEquals(1, database.dashboardDao().countProcessLogs(customerId))

        dashboard.setStatusCount(3)
        val reduced = dashboard.observeStatuses().first()
        assertEquals(3, reduced.size)
        assertEquals(reduced.last().id, database.customerDao().getById(customerId)?.dashboardStatusId)
        assertTrue(reduced.map { it.colorHex }.distinct().size > 1)
    }
}
