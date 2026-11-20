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


## XIANMOD PAYMENT 1.1.0

- APK memakai `https://xianmodbot.vercel.app` sebagai API production.
- Pairing device token menggunakan Android Keystore AES-GCM.
- Daftar order PENDING dimuat dari API setiap 10 detik.
- Aktivasi pembayaran selalu memerlukan persetujuan Owner; tidak ada auto-activation dari notifikasi.
- Package GoPay Merchant: `com.gojek.gopaymerchant`; parser nominal masih dinonaktifkan sampai format notifikasi aktual diverifikasi.
- Jalankan workflow GitHub Actions **XIANMOD PAYMENT - Debug APK** untuk APK uji yang dapat dipasang.

## XIANMOD PAYMENT 1.2.1 — perbaikan pairing
- Error HTTP asli (mis. 503, 429, 401) kini ditampilkan beserta kode dari server; tidak lagi disamarkan.
- Kegagalan jaringan dibedakan: DNS, timeout, koneksi, SSL.
- Penyimpanan token (Android Keystore) diverifikasi dengan baca-balik. Bila gagal, UI menjelaskan bahwa kode sudah terpakai dan perlu kode baru.
- Token tidak lagi dihapus karena error sementara Keystore; hanya dihapus bila kunci/ciphertext memang rusak.
- Polling order membedakan "TOKEN DITOLAK" (401) dari "OFFLINE".
- Salinan lama backend (api/, lib/, test/, vercel.json) dihapus dari repo Android; backend hanya ada di proyek XIANMOD_VERCEL.

## XIANMOD PAYMENT 1.3.0
- Tab **Order**: tombol "Setujui manual" per order PENDING dengan dialog konfirmasi (order, paket, nominal). Server memeriksa ulang nominal dan status.
- Logo/ikon dibuat ulang simetris dan masuk safe zone adaptive icon (tidak terpotong lagi).

## XIANMOD PAYMENT 1.4.0
- Notifikasi HP saat ada order PENDING baru (layanan pemantau, polling 8 detik, tetap jalan walau aplikasi ditutup). Bisa dimatikan di Pengaturan.
- Izin notifikasi diminta saat aplikasi dibuka; pemantau dijalankan ulang otomatis setelah HP restart.

## XIANMOD PAYMENT 1.5.0 — update tanpa uninstall
- APK ditandatangani dengan kunci tetap dari GitHub Secrets `XIANMOD_KEYSTORE_BASE64` dan `XIANMOD_KEYSTORE_PASSWORD` (tidak ada kunci di source code).
- versionCode naik otomatis mengikuti nomor run GitHub Actions.
- Sekali saja: uninstall APK lama (kunci berbeda), pasang build pertama yang bertanda tangan kunci tetap, pairing ulang. Setelah itu cukup install di atasnya.
- Daftar order menampilkan masa berlaku QR dalam WIB dan penanda "QR kedaluwarsa" (oranye) bila sudah lewat.
