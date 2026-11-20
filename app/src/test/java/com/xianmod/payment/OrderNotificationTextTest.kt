package com.xianmod.payment

import org.junit.Assert.*
import org.junit.Test

class OrderNotificationTextTest {
    @Test
    fun bodyShowsPackageAmountAndOrderId() {
        val order = PaymentApi.PendingOrder("XIAN-ABC-123", "VIP 30 HARI", 30166L, "", "")
        val body = OrderNotificationText.body(order)
        assertTrue(body.contains("VIP 30 HARI"))
        assertTrue(body.contains("30.166"))
        assertTrue(body.contains("XIAN-ABC-123"))
    }

    @Test
    fun blankPackageFallsBack() {
        val order = PaymentApi.PendingOrder("X-1", "", 1000L, "", "")
        assertTrue(OrderNotificationText.body(order).startsWith("Paket"))
    }

    @Test
    fun qrExpiryShowsWibAndDetectsExpired() {
        val now = java.time.Instant.parse("2026-11-20T11:00:00Z")
        val old = OrderNotificationText.qrExpiry("2026-11-20T07:26:32.459+00:00", now)
        assertTrue(old.expired)
        assertTrue(old.label.contains("14:26"))
        val live = OrderNotificationText.qrExpiry("2026-11-20T11:10:00+00:00", now)
        assertFalse(live.expired)
        assertTrue(live.label.contains("berlaku"))
    }

    @Test
    fun qrExpiryHandlesGarbage() {
        assertFalse(OrderNotificationText.qrExpiry("bukan-tanggal").expired)
        assertFalse(OrderNotificationText.qrExpiry("").expired)
    }
}
