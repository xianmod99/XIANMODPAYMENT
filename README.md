# XIANMOD PAYMENT

Android companion scaffold for XIANMOD payment notification monitoring.

## Konfigurasi

- App: XIANMOD PAYMENT
- Package: `com.xianmod.payment`
- Bahasa UI: Indonesia
- Kotlin + Jetpack Compose
- Min SDK: 26
- Compile/Target SDK: 35
- Java: 17
- Gradle: 8.11.1
- CI: GitHub Actions

## Build melalui GitHub

1. Buat repository GitHub baru, misalnya `XIANMOD-PAYMENT-COMPANION`.
2. Upload seluruh isi folder ini ke branch `main`.
3. Buka tab **Actions**.
4. Pilih workflow **XIANMOD PAYMENT - Debug APK**.
5. Tekan **Run workflow** atau push commit ke `main`.
6. Setelah selesai, buka workflow run dan unduh artifact `XIANMOD-PAYMENT-DEBUG`.

Release workflow menghasilkan APK unsigned. Untuk distribusi permanen, tambahkan proses signing memakai keystore yang disimpan sebagai GitHub Secrets. Jangan commit keystore atau password ke repository.

## Notification Listener

1. Install APK debug.
2. Buka Settings aplikasi.
3. Aktifkan Notification Access untuk XIANMOD PAYMENT.
4. Konfirmasi paket aplikasi merchant GoPay yang benar sebelum mengisi `MerchantPackageConfig.PACKAGE_NAME`.

Parser sengaja mengembalikan `null` sampai contoh notifikasi merchant yang sudah disensor tersedia. Ini mencegah notifikasi yang tidak dikenal diperlakukan sebagai pembayaran.

## Status implementasi

Termasuk:
- Dashboard Compose awal.
- Bottom navigation.
- Transactions, Monitor, Settings.
- NotificationListenerService scaffold.
- Workflow GitHub Actions Debug dan Release.

Belum termasuk:
- Parser GoPay final.
- Device pairing dan autentikasi backend.
- Vercel API integration.
- Supabase event queue dan payment matcher.
- Export logs dan device health diagnostics lengkap.
- Release signing.

Jangan aktifkan auto-activation sebelum sumber notifikasi dan status pembayaran dapat diverifikasi secara independen.
# XIANMODPAYMENT
