package com.xianmod.payment

import java.text.NumberFormat
import java.util.Locale

/** Teks notifikasi order baru. Murni Kotlin supaya bisa diuji tanpa perangkat. */
object OrderNotificationText {
    fun title(): String = "Order baru PENDING"

    fun body(order: PaymentApi.PendingOrder): String {
        val rupiah = NumberFormat.getIntegerInstance(Locale("id", "ID")).format(order.amount)
        val pkg = order.packageName.ifBlank { "Paket" }
        return "$pkg • Rp $rupiah\n${order.orderId}"
    }
}
