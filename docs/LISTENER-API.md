# XIANMOD PAYMENT — Listener API & Deploy

Backend memutuskan semua pembayaran. APK hanya melaporkan notifikasi.

## Alur
Pembeli → bot → order PENDING → QRIS dinamis (nominal unik) → bayar GoPay → notifikasi di HP →
APK `POST /event` → backend: autentikasi perangkat, deduplikasi, pencocokan nominal unik, validasi waktu/expiry → seluruh event menjadi NEEDS_REVIEW → Owner memeriksa transaksi di GoPay Merchant dan menyetujui melalui Owner Panel.

## Environment variables BARU (nama saja; isi sendiri di Vercel)
| Nama | Wajib | Default | Keterangan |
|---|---|---|---|
| `PAYMENT_PROVIDER` | ya | `casaku` | Ganti ke `gopay_listener` hanya saat siap pindah. Selama `casaku`, perilaku lama 100% sama. |
| `GOPAY_QRIS_STATIC` | ya (jika gopay_listener) | - | Teks isi QRIS statis GoPay Merchant milikmu (diawali `000201`). |
| `LISTENER_AUTO_ACTIVATE` | tidak dipakai | - | Aktivasi otomatis dinonaktifkan permanen dalam konfigurasi produksi. Semua event wajib review Owner. |
| `LISTENER_ALLOWED_PACKAGES` | disarankan | kosong | Package GoPay Merchant, pisahkan koma. Event dari package lain → NEEDS_REVIEW. |
| `LISTENER_ORDER_MINUTES` | tidak | `15` | Masa berlaku QRIS/order. |
| `LISTENER_UNIQUE_MAX` | tidak | `200` | Kode unik 1..N ditambahkan ke harga. |

Yang sudah ada dan dipakai ulang: `OWNER_DASHBOARD_SECRET`, `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `BOT_TOKEN`, `OWNER_ID`/`OWNER_TELEGRAM_IDS`.
Service Role Key dan token bot hanya ada di Vercel. Tidak pernah masuk APK.

## Deploy (dari Termux)
1. Salin file dari ZIP ke folder backend (struktur folder sudah sama), timpa `api/telegram.js` dan `vercel.json`.
2. Supabase → SQL Editor → tempel seluruh isi `supabase/migration_xianmod_payment.sql` pada project production; file awal `migration_listener.sql` di source repo hanya berisi tabel listener dasar. → Run (sekali; aman diulang). Hanya membuat tabel baru.
3. Vercel → Environment Variables → siapkan variabel di atas tetapi pertahankan `PAYMENT_PROVIDER=casaku` sampai parser notifikasi dan QRIS diuji.
4. Deploy staging, uji pairing, feed PENDING, QRIS nominal kecil, review Owner, ledger stok, dan delivery VIP.
5. Setelah semua uji lulus, ubah `PAYMENT_PROVIDER=gopay_listener` dan deploy production.
6. `vercel --prod`
7. Cek rute hidup: `curl -s https://DOMAIN/api/payment/listener/status` → harus JSON `{"ok":false,"error":"unauthorized"}`.
   Jika 404 HTML, periksa konfigurasi Vercel routing untuk dynamic API route.

## Pairing
1. Owner buka Telegram → `/owner` → **Pair APK** untuk membuat kode pairing 10 menit.
2. Masukkan kode pada APK → Pengaturan → Hubungkan perangkat.
3. Token disimpan terenkripsi di Android Keystore; server hanya menyimpan hash token.
4. Cabut perangkat melalui endpoint Owner `/devices` dan `/revoke` bila HP hilang.

## Kontrak endpoint
Semua JSON. Perangkat: header `Authorization: Bearer xpd_...`. Owner: header `x-owner-secret`.

**POST /pair** `{code, device_name?, app_version?, model?}` → `{ok, device_id, token, server_time}` (token hanya tampil sekali). 10 percobaan/10 menit/IP.

**POST /event** (120/menit/perangkat)
```json
{"event_id":"uuid-atau-id-unik-8..64","package_name":"...","amount":20123,"posted_at":1763500000000,
 "parsed":true,"reference":null,"text_hash":"sha256-opsional","app_version":"1.0.0","queued":false}
```
Respons `{ok, duplicate, status, reason, order_id}`; status ∈ `PAID | NEEDS_REVIEW | DUPLICATE | REJECTED | MATCHED`.
HTTP 200 untuk semua keputusan (APK tidak perlu retry). Retry hanya untuk 429/5xx/jaringan.
`parsed:false` atau `amount:null` → selalu NEEDS_REVIEW. Parser GoPay saat ini sengaja belum mengirim event karena format notifikasi aktual belum diverifikasi. Isi notifikasi mentah tidak dikirim dan tidak disimpan.

**POST /heartbeat** `{listener_connected, queue_depth, app_version, battery?, battery_unrestricted?}` → `{ok, server_time}` (20/menit).

**GET /status** → `{ok, server_time, device, counts_30d:{PAID:n,NEEDS_REVIEW:n,...}}`.

**GET /orders** → daftar order `PENDING` minimal (order_id, package_name, total_amount_idr, qr_expires_at, status, created_at), hanya untuk device yang sudah dipasangkan; APK polling 10 detik.

**POST /approve** (Owner API) `{event_id, order_id?}` → menyetujui event NEEDS_REVIEW (order harus PENDING). Jalur utama persetujuan tersedia di Telegram Owner Panel → Review Pembayaran.

## Aturan keputusan (backend)
Tidak ada auto-aktivasi. Jika parser valid dan nominal/waktu/order cocok, event tetap masuk `NEEDS_REVIEW`. Owner harus memeriksa pembayaran langsung pada GoPay Merchant. Persetujuan memakai RPC Postgres yang mengunci order, mengubah PENDING→PAID, mengurangi stok melalui `stock_consume()` dalam transaksi yang sama, dan membuat ledger fulfillment. Pengiriman VIP punya status tersendiri agar kegagalan/timeout tidak memicu pengiriman ganda.

## Status
Dipakai: `PENDING`, `PAID`, `CANCELLED` (lama). Event: `RECEIVED/MATCHED/PAID/NEEDS_REVIEW/DUPLICATE/REJECTED`.
Status order `EXPIRED` belum diubah otomatis; kedaluwarsa ditangani lewat `qr_expires_at` dan kunci nominal.

## Uji
`node --test test/listener.test.js test/http.smoke.test.js`
