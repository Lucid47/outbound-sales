package com.lucid47.soheeyagaja.activities

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
class ActivityRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerManagementRepository
    private lateinit var activities: ActivityRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        customers = CustomerManagementRepository(database)
        activities = ActivityRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun contactVisitCompletionAndScheduleShareOneCustomerHistory() = runBlocking {
        val listId = createList("활동 테스트")
        val customerId = customers.createCustomer(
            listId,
            CustomerDraft(name = "김소희", phone = "010-1234-5678"),
        )

        activities.recordCallAttempt(customerId)
        activities.recordSmsAttempt(customerId)
        activities.recordTextMemo(customerId, "상담 약속")
        activities.recordQuickVisit(customerId)
        val dateKey = ActivityRepository.todayKey()
        activities.addToTodaySchedule(customerId, dateKey)
        activities.setCustomerCompleted(customerId, completed = true)

        val history = activities.observeHistoryForCustomer(customerId).first()
        assertEquals(5, history.size)
        assertTrue(history.any { it.type == ActivityRepository.TYPE_CALL })
        assertTrue(history.any { it.type == ActivityRepository.VISIT_TEXT_MEMO })
        assertTrue(history.any { it.type == ActivityRepository.VISIT_QUICK })
        assertEquals(
            CustomerManagementRepository.STATUS_DONE,
            database.customerDao().getById(customerId)?.status,
        )

        val schedule = activities.observeTodaySchedule(listId, dateKey).first()
        assertEquals(1, schedule.size)
        assertEquals(ActivityRepository.SCHEDULE_COMPLETED, schedule.single().scheduleStatus)
    }

    @Test
    fun deletingCustomerCascadesActivityAndScheduleItems() = runBlocking {
        val listId = createList("연쇄 삭제")
        val customerId = customers.createCustomer(listId, CustomerDraft(name = "삭제 고객"))
        activities.recordCallAttempt(customerId)
        activities.recordQuickVisit(customerId)
        activities.addToTodaySchedule(customerId, TEST_DATE)

        customers.deleteCustomer(customerId)

        assertEquals(0, database.activityDao().countContactLogs(customerId))
        assertEquals(0, database.activityDao().countVisitLogs(customerId))
        assertEquals(0, database.activityDao().countScheduleItems(customerId))
    }

    private suspend fun createList(name: String): Long {
        val now = System.currentTimeMillis()
        return database.customerListDao().insert(
            CustomerListEntity(name = name, sourceName = "test", createdAtEpochMillis = now),
        )
    }

    companion object {
        private const val TEST_DATE = "2030-01-02"
    }
}
