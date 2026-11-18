# XIANMOD PAYMENT

Aplikasi Android pendamping XIANMOD untuk memantau notifikasi pembayaran merchant.

## Konfigurasi
- Nama: XIANMOD PAYMENT
- Package: `com.xianmod.payment`
- Bahasa: Indonesia
- Kotlin + Jetpack Compose
- Min SDK 26, Compile/Target SDK 35
- Java 17, Gradle 8.11.1
- GitHub Actions: Debug APK dan Release APK (unsigned)

## Perbaikan pada revisi ini
- Deteksi Notification Access menggunakan daftar komponen listener Android yang benar.
- Status izin diperiksa ulang saat Activity kembali ke foreground (`onResume`).
- Status koneksi service dibaca dari state yang ditulis oleh `onListenerConnected` dan `onListenerDisconnected`.
- Logo XIANMOD dari desain terakhir dipasang pada launcher dan header aplikasi.
- Tidak ada integrasi Casaku di source Android ini.

## Build
Buka tab **Actions** pada repository:
- `XIANMOD PAYMENT - Debug APK` menghasilkan artifact `XIANMOD-PAYMENT-DEBUG`.
- `XIANMOD PAYMENT - Release APK` menghasilkan artifact `XIANMOD-PAYMENT-RELEASE-UNSIGNED`.

Release saat ini belum ditandatangani. Untuk distribusi, tambahkan signing dengan keystore melalui GitHub Secrets. Jangan commit keystore atau password.

## Hal yang masih membutuhkan data nyata
1. `MerchantPackageConfig.PACKAGE_NAME` masih kosong. Isi setelah memverifikasi package name aplikasi GoPay Merchant pada perangkat.
2. `NotificationParser` sengaja belum membaca nominal. Diperlukan contoh notifikasi pembayaran asli yang sudah disensor agar parser tidak salah menganggap notifikasi lain sebagai pembayaran.
3. Integrasi XIANMOD API belum diaktifkan karena kontrak endpoint dan source backend belum disertakan. Jangan mengaktifkan auto-activation sebelum server memvalidasi pembayaran, referensi transaksi, nominal, order yang belum kedaluwarsa, serta idempotensi.
4. Aplikasi ini tidak menyimpan Supabase Service Role Key. Seluruh perubahan order/VIP harus dilakukan di backend XIANMOD.

## Pengujian Notification Access
1. Install APK.
2. Buka Pengaturan > Notification Access.
3. Aktifkan XIANMOD PAYMENT.
4. Kembali ke aplikasi. Dashboard harus memperbarui status izin.
5. Status `LISTENER AKTIF` muncul setelah Android memanggil `onListenerConnected`.

Pada HyperOS, aktifkan Autostart dan setel Battery Saver ke No restrictions agar service lebih mungkin bertahan di latar belakang. Android tetap dapat membatasi proses latar belakang.
