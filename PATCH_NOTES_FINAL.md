# XIANMOD PAYMENT — PATCH FINAL

## Perubahan
- DeviceTokenStore menghapus ciphertext dan alias Android Keystore yang invalid agar pairing ulang dapat membuat kunci baru.
- Feed order APK disinkronkan setiap 5 detik melalui API terautentikasi (polling, bukan push realtime).
- Feed API hanya mengembalikan order PENDING dengan `payment_method = GOPAY_QRIS`.
- Backend listener dan rute API disinkronkan dengan proyek XIANMOD_VERCEL.
- Gradle Wrapper tetap memakai Gradle 8.11.1 dan `gradlew` sudah diberi executable permission.

## Build APK
Workflow GitHub Actions:
- `.github/workflows/android-debug.yml` untuk Debug APK.
- `.github/workflows/android-release.yml` untuk Release APK unsigned.

Build lokal tidak dapat diverifikasi di lingkungan penyuntingan ini karena Android SDK tidak tersedia dan Gradle Wrapper tidak dapat mengunduh distribusi saat jaringan offline.

## Batasan
- `NotificationParser.parse()` tetap mengembalikan null. Parser tidak boleh ditebak tanpa contoh notifikasi GoPay Merchant asli yang sudah disamarkan.
- Karena parser nonaktif, APK belum meneruskan kandidat pembayaran dari notifikasi GoPay. Dashboard, pairing, heartbeat, dan daftar order tetap dapat digunakan setelah backend dan database siap.
- APK tidak pernah mengubah order menjadi PAID. Persetujuan tetap melalui Owner.

## Parser update – GoPay Merchant static QR notification
- Added a strict parser for the user-confirmed notification title `Pembayaran QRIS statis diterima` and body format `Rp 1 di DIM QR.`
- Parses positive integer rupiah values, including Indonesian dot thousands separators (for example `Rp 1.000`).
- Rejects unrelated titles, malformed bodies, zero/overflow amounts, and conflicting amounts between Android `text` and `bigText`.
- The observed notification contains no transaction reference, so the candidate reference remains null.
- Bumped Android app version to 1.2.0 (versionCode 3).
- Parsed notifications remain untrusted candidate signals; Owner approval is still required.
