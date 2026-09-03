package com.lucid47.soheeyagaja.customers

import com.lucid47.soheeyagaja.activities.ActivityRepository
import com.lucid47.soheeyagaja.data.HistoryEntryRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFiltersTest {
    @Test
    fun matchesCallMessageVisitMemoAndStatusTypes() {
        assertTrue(historyMatchesActivity(entry(ActivityRepository.TYPE_CALL_INCOMING), HistoryActivityFilter.CALL))
        assertTrue(historyMatchesActivity(entry(ActivityRepository.TYPE_CALL_TRANSCRIPT), HistoryActivityFilter.CALL))
        assertTrue(historyMatchesActivity(entry(ActivityRepository.TYPE_MMS_OUTGOING), HistoryActivityFilter.MESSAGE))
        assertTrue(historyMatchesActivity(entry(ActivityRepository.VISIT_QUICK), HistoryActivityFilter.VISIT))
        assertTrue(historyMatchesActivity(entry("PHOTO_MEMO"), HistoryActivityFilter.MEMO))
        assertTrue(historyMatchesActivity(entry("PROCESS_STATUS"), HistoryActivityFilter.STATUS))
        assertFalse(historyMatchesActivity(entry(ActivityRepository.VISIT_TEXT_MEMO), HistoryActivityFilter.VISIT))
    }

    @Test
    fun lastMonthUsesPreviousCalendarMonth() {
        val range = requireNotNull(historyDateRange(HistoryDatePreset.LAST_MONTH, LocalDate.of(2026, 9, 3)))

        assertEquals(LocalDate.of(2026, 8, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2026, 8, 31).toEpochDay(), range.last)
    }

    @Test
    fun lastQuarterCrossesYearBoundary() {
        val range = requireNotNull(historyDateRange(HistoryDatePreset.LAST_QUARTER, LocalDate.of(2026, 1, 15)))

        assertEquals(LocalDate.of(2025, 10, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), range.last)
    }

    @Test
    fun lastThirtyDaysIncludesTodayAndTwentyNinePreviousDays() {
        val today = LocalDate.of(2026, 9, 3)
        val range = requireNotNull(historyDateRange(HistoryDatePreset.LAST_30_DAYS, today))

        assertEquals(today.minusDays(29).toEpochDay(), range.first)
        assertEquals(today.toEpochDay(), range.last)
    }

    @Test
    fun allAndCustomHaveNoAutomaticRange() {
        assertNull(historyDateRange(HistoryDatePreset.ALL, LocalDate.of(2026, 9, 3)))
        assertNull(historyDateRange(HistoryDatePreset.CUSTOM, LocalDate.of(2026, 9, 3)))
    }

    private fun entry(type: String) = HistoryEntryRecord(
        stableId = type,
        listId = 1,
        customerId = 1,
        customerName = "테스트 고객",
        category = "CONTACT",
        type = type,
        result = "SAVED",
        detail = "",
        occurredAtEpochMillis = 0,
    )
}
