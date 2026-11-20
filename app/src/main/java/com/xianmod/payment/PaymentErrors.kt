package com.xianmod.payment

/**
 * Error API yang membawa kode mesin dari backend (mis. "code_invalid_or_expired")
 * dan status HTTP asli. Status HTTP tidak pernah disamarkan menjadi error lain.
 */
class ApiException(
    val code: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null
) : Exception(code, cause)

/** Pesan untuk pengguna (Indonesia). Murni Kotlin sehingga bisa diuji tanpa perangkat. */
object PaymentErrors {
    fun describe(e: Throwable): String {
        if (e !is ApiException) return "Terjadi kesalahan tak terduga: ${e.javaClass.simpleName}"
        val base = when (e.code) {
            "code_required" -> "Kode pairing masih kosong."
            "code_invalid_or_expired" ->
                "Kode salah, sudah dipakai, atau kedaluwarsa (berlaku 10 menit). Minta kode baru di bot Telegram: Pair APK."
            "rate_limited" -> "Terlalu banyak percobaan. Tunggu beberapa menit lalu coba lagi."
            "rate_limiter_unavailable" ->
                "Server belum bisa memeriksa batas percobaan. Coba lagi sebentar; jika berulang, Owner perlu cek log Vercel dan fungsi xian_pay_rate_hit di Supabase."
            "server_not_configured" ->
                "Server belum dikonfigurasi (SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY di Vercel). Hubungi Owner."
            "pair_failed" ->
                "Server gagal memproses pairing. Kode kemungkinan belum terpakai; coba lagi. Jika berulang, Owner cek log Vercel."
            "body_invalid" -> "Server tidak bisa membaca permintaan (body_invalid). Perbarui aplikasi."
            "unauthorized" -> "Token perangkat ditolak server. Perangkat mungkin sudah dicabut; pasangkan ulang dengan kode baru."
            "order_id_required", "confirm_amount_invalid" -> "Data order tidak valid. Muat ulang daftar order lalu coba lagi."
            "order_not_found" -> "Order tidak ditemukan di server."
            "order_not_gopay" -> "Order ini bukan order GoPay QRIS, jadi tidak bisa disetujui dari APK."
            "amount_mismatch" -> "Nominal di layar tidak sama dengan nominal di server. Muat ulang daftar order."
            "order_not_pending" -> "Order sudah tidak berstatus PENDING (sudah dibayar, kedaluwarsa, atau dibatalkan)."
            "apk_approve_disabled" -> "Approve dari APK dinonaktifkan di server (LISTENER_APK_APPROVE=false)."
            "approve_failed" -> "Server gagal memproses approve. Cek status order di bot sebelum mencoba lagi."
            "device_not_paired" -> "Perangkat belum dipasangkan."
            "pair_response_incomplete" ->
                "Server membalas sukses tetapi data perangkat tidak lengkap. Minta kode baru dan coba lagi."
            "token_not_saved" ->
                "Pairing berhasil di server, tetapi token gagal disimpan aman di HP (Android Keystore). Kode itu sudah terpakai: minta kode baru lalu coba lagi."
            "invalid_server_response" ->
                "Server membalas dengan data yang bukan JSON. Periksa alamat server dan status deployment Vercel."
            "network_dns" -> "Alamat server tidak ditemukan. Periksa koneksi internet / DNS."
            "network_timeout" -> "Koneksi ke server melewati batas waktu. Periksa sinyal lalu coba lagi."
            "network_connect" -> "Tidak bisa terhubung ke server. Periksa koneksi internet."
            "network_ssl" -> "Koneksi aman (HTTPS) ke server gagal. Periksa tanggal & jam HP dan jaringan."
            "network_io" -> "Koneksi terputus saat berkomunikasi dengan server. Coba lagi."
            else -> if (e.httpStatus > 0) "Server membalas error: ${e.code}" else "Gagal: ${e.code}"
        }
        return if (e.httpStatus > 0) "$base [HTTP ${e.httpStatus}]" else base
    }
}
