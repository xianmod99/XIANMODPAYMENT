package com.xianmod.payment.listener

import org.junit.Assert.*
import org.junit.Test

class NotificationParserTest {

    private val title = "Pembayaran QRIS statis diterima"

    @Test
    fun parsesSingleRupiahAmount() {
        val result = NotificationParser.parse(
            title, "Rp 1 di DIM QR.", ""
        )
        assertNotNull(result)
        assertEquals(1L, result!!.amount)
        assertNull(result.transactionReference)
    }

    @Test
    fun parsesThousandSeparators() {
        val result = NotificationParser.parse(
            title, "Rp 1.250.000 di DIM QR.", ""
        )
        assertNotNull(result)
        assertEquals(1250000L, result!!.amount)
    }

    @Test
    fun rejectsInvalidNumberFormat() {
        assertNull(NotificationParser.parse(title, "Rp 1.2 di DIM QR.", ""))
        assertNull(NotificationParser.parse(title, "Rp 1..000 di DIM QR.", ""))
    }

    @Test
    fun rejectsWrongTitle() {
        assertNull(NotificationParser.parse(
            "Pembayaran berhasil",
            "Rp 1 di DIM QR.",
            ""
        ))
    }

    @Test
    fun rejectsConflictingAmounts() {
        assertNull(NotificationParser.parse(
            title,
            "Rp 1 di DIM QR.",
            "Rp 2 di DIM QR."
        ))
    }

    @Test
    fun acceptsMirroredNotificationContent() {
        val result = NotificationParser.parse(
            title,
            "Rp 1 di DIM QR.",
            "Rp 1 di DIM QR."
        )
        assertNotNull(result)
        assertEquals(1L, result!!.amount)
    }

    @Test
    fun rejectsMissingAmount() {
        assertNull(NotificationParser.parse(title, "Pembayaran diterima", ""))
    }
}
