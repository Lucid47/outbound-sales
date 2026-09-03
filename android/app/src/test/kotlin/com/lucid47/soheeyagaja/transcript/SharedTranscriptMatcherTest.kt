package com.lucid47.soheeyagaja.transcript

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTranscriptMatcherTest {
    @Test
    fun extractsSamsungCallRecordingTimestamp() {
        val timestamp = SharedTranscriptMatcher.timestampFrom(
            listOf("통화 고객님 김태희_260903_080037.m4a"),
        )

        val local = Instant.ofEpochMilli(requireNotNull(timestamp)).atZone(ZoneId.systemDefault())
        assertEquals(2026, local.year)
        assertEquals(9, local.monthValue)
        assertEquals(3, local.dayOfMonth)
        assertEquals(8, local.hour)
        assertEquals(0, local.minute)
        assertEquals(37, local.second)
    }

    @Test
    fun recommendsUniqueCustomerFromRecordingNameAndCallTime() {
        val ranked = SharedTranscriptMatcher.rankCustomers(
            customers = listOf(
                SharedTranscriptMatcher.CustomerInput(1, "김태희", "01012345678", "7월 고객"),
                SharedTranscriptMatcher.CustomerInput(2, "김민수", "01087654321", "7월 고객"),
            ),
            sourceNames = listOf("통화 고객님 김태희_260903_080037.m4a"),
            transcript = "상담 내용",
            nearestCallCustomerId = 1,
        )

        assertEquals(1L, SharedTranscriptMatcher.recommendedCustomerId(ranked))
    }

    @Test
    fun doesNotRecommendWhenDuplicateNamesTie() {
        val ranked = SharedTranscriptMatcher.rankCustomers(
            customers = listOf(
                SharedTranscriptMatcher.CustomerInput(1, "김태희", "01012345678", "A"),
                SharedTranscriptMatcher.CustomerInput(2, "김태희", "01087654321", "B"),
            ),
            sourceNames = listOf("통화 김태희_260903_080037.txt"),
            transcript = "상담 내용",
            nearestCallCustomerId = null,
        )

        assertNull(SharedTranscriptMatcher.recommendedCustomerId(ranked))
    }
}
