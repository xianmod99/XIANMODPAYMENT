package com.xianmod.payment

import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Teks notifikasi order baru. Murni Kotlin supaya bisa diuji tanpa perangkat. */
object OrderNotificationText {
    fun title(): String = "Order baru PENDING"

    fun body(order: PaymentApi.PendingOrder): String {
        val rupiah = NumberFormat.getIntegerInstance(Locale("id", "ID")).format(order.amount)
        val pkg = order.packageName.ifBlank { "Paket" }
        return "$pkg • Rp $rupiah\n${order.orderId}"
    }

    data class QrExpiry(val label: String, val expired: Boolean)

    /** Teks masa berlaku QR dalam WIB. Input ISO-8601 dari server (mis. 2026-11-20T07:26:32.459+00:00). */
    fun qrExpiry(iso: String, now: Instant = Instant.now()): QrExpiry {
        if (iso.isBlank()) return QrExpiry("Masa berlaku QR tidak diketahui", false)
        return try {
            val at = OffsetDateTime.parse(iso).toInstant()
            val wib = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale("id", "ID")).withZone(ZoneId.of("Asia/Jakarta")).format(at)
            if (at.isBefore(now)) QrExpiry("QR kedaluwarsa sejak $wib WIB. Setujui hanya jika uang benar-benar sudah masuk.", true)
            else QrExpiry("QR berlaku sampai $wib WIB", false)
        } catch (_: Exception) {
            QrExpiry("QR expires: $iso", false)
        }
    }
}
