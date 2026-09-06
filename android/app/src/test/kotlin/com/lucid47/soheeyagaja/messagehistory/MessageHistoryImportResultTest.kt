package com.lucid47.soheeyagaja.messagehistory

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHistoryImportResultTest {
    @Test
    fun explainsWhenStandardSmsAndMmsAreUnavailable() {
        val result = MessageHistoryImportResult(
            scannedCount = 0,
            importedCount = 0,
            duplicateCount = 0,
            unmatchedCount = 0,
            invalidCount = 0,
        )

        assertEquals(
            "가져올 수 있는 SMS/MMS 기록을 찾지 못했습니다. 채팅+ RCS 대화는 포함되지 않습니다.",
            result.summary(),
        )
    }

    @Test
    fun reportsScannedImportedAndExcludedCounts() {
        val result = MessageHistoryImportResult(
            scannedCount = 8,
            importedCount = 3,
            duplicateCount = 2,
            unmatchedCount = 2,
            invalidCount = 1,
        )

        assertEquals(
            "SMS/MMS 8건을 읽어 3건을 고객 히스토리에 추가했습니다. 중복 2건 제외. 선택한 고객리스트에 일치하는 번호가 없는 기록 2건. 형식 오류 1건.",
            result.summary(),
        )
    }
}
