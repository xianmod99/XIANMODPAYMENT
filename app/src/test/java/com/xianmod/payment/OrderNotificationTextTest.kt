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
}
