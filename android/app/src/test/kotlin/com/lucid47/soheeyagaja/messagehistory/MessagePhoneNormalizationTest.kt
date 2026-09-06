package com.lucid47.soheeyagaja.messagehistory

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePhoneNormalizationTest {
    @Test fun internationalNumbersMatchDomesticNumbers() {
        listOf("+82 10-1234-5678", "00821012345678", "+82 (0)10-1234-5678", "tel:01012345678", "01012345678/TYPE=PLMN")
            .forEach { assertEquals("01012345678", normalizeMessagePhone(it)) }
    }

    @Test fun emailAddressIsNotTreatedAsPhone() {
        assertEquals("", normalizeMessagePhone("01012345678@example.com"))
    }
}
