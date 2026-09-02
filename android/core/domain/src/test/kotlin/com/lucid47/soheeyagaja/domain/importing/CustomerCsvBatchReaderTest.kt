package com.lucid47.soheeyagaja.domain.importing

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCsvBatchReaderTest {
    @Test
    fun `quoted comma escaped quote and multiline note stay in one record`() {
        val csv = """
            고객명,휴대폰,주소,비고
            김소희,010-1234-5678,"서울시 중구, 101호","첫 줄
            둘째 ""인용"" 줄"
        """.trimIndent()

        val batch = CustomerCsvBatchReader(StringReader(csv)).readBatch()!!

        assertEquals(1, batch.customers.size)
        assertEquals("서울시 중구, 101호", batch.customers.single().address)
        assertEquals("첫 줄\n둘째 \"인용\" 줄", batch.customers.single().notes)
    }

    @Test
    fun `Korean real estate headers are mapped separately`() {
        val csv = """
            성명,연락처,주소,소유,소유지번,지번,메모
            이가자,01011112222,서울시 강남구,상가 A,123-4,567-8,상담 필요
        """.trimIndent()

        val reader = CustomerCsvBatchReader(StringReader(csv))
        val customer = reader.readBatch()!!.customers.single()

        assertEquals("상가 A", customer.ownedAddress)
        assertEquals("123-4 | 567-8", customer.parcelAddress)
        assertEquals("상담 필요", customer.notes)
    }

    @Test
    fun `duplicate phone is accepted once`() {
        val csv = """
            이름,전화번호
            첫번째,010-1234-5678
            두번째,01012345678
        """.trimIndent()

        val reader = CustomerCsvBatchReader(StringReader(csv))
        val batch = reader.readBatch()!!

        assertEquals(1, batch.customers.size)
        assertEquals(1, batch.progress.duplicateRows)
    }

    @Test
    fun `ten thousand customers never exceed two hundred rows per batch`() {
        val csv = buildString {
            appendLine("고객명,전화번호,주소")
            repeat(10_000) { index ->
                appendLine("고객$index,010${index.toString().padStart(8, '0')},서울시 $index")
            }
        }
        val reader = CustomerCsvBatchReader(StringReader(csv), batchSize = 200)
        val sizes = mutableListOf<Int>()

        while (true) {
            val batch = reader.readBatch() ?: break
            sizes += batch.customers.size
        }

        assertEquals(10_000, sizes.sum())
        assertEquals(50, sizes.size)
        assertTrue(sizes.all { it in 1..200 })
        assertEquals(10_000, reader.currentProgress().acceptedRows)
    }
}
