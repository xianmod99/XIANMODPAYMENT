# XIANMOD PAYMENT — Listener API & Deploy

Backend memutuskan semua pembayaran. APK hanya melaporkan notifikasi.

## Alur
Pembeli → bot → order PENDING → QRIS dinamis (nominal unik) → bayar GoPay → notifikasi di HP →
APK `POST /event` → backend: auth perangkat, dedup, cocokkan nominal unik, validasi waktu & expiry →
(auto-activate ON) PAID + invite VIP + konfirmasi bot, atau NEEDS_REVIEW + notifikasi ke owner.

## Environment variables BARU (nama saja; isi sendiri di Vercel)
| Nama | Wajib | Default | Keterangan |
|---|---|---|---|
| `PAYMENT_PROVIDER` | ya | `casaku` | Ganti ke `gopay_listener` hanya saat siap pindah. Selama `casaku`, perilaku lama 100% sama. |
| `GOPAY_QRIS_STATIC` | ya (jika gopay_listener) | - | Teks isi QRIS statis GoPay Merchant milikmu (diawali `000201`). |
| `LISTENER_AUTO_ACTIVATE` | tidak | `false` | `true` = cocok sempurna langsung PAID. `false` = semua ke NEEDS_REVIEW (aman untuk masa uji). |
| `LISTENER_ALLOWED_PACKAGES` | disarankan | kosong | Package GoPay Merchant, pisahkan koma. Event dari package lain → NEEDS_REVIEW. |
| `LISTENER_ORDER_MINUTES` | tidak | `15` | Masa berlaku QRIS/order. |
| `LISTENER_UNIQUE_MAX` | tidak | `200` | Kode unik 1..N ditambahkan ke harga. |

Yang sudah ada dan dipakai ulang: `OWNER_DASHBOARD_SECRET`, `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `BOT_TOKEN`, `OWNER_ID`/`OWNER_TELEGRAM_IDS`.
Service Role Key dan token bot hanya ada di Vercel. Tidak pernah masuk APK.

## Deploy (dari Termux)
1. Salin file dari ZIP ke folder backend (struktur folder sudah sama), timpa `api/telegram.js` dan `vercel.json`.
2. Supabase → SQL Editor → tempel seluruh isi `supabase/migration_listener.sql` → Run (sekali; aman diulang). Hanya membuat tabel baru.
3. Vercel → Environment Variables → tambahkan variabel di atas (biarkan `PAYMENT_PROVIDER=casaku` dulu).
4. `vercel --prod`
5. Cek rute hidup: `curl -s https://DOMAIN/api/payment/listener/status` → harus JSON `{"ok":false,"error":"unauthorized"}`.
   Jika 404 HTML, beri tahu saya (fallback: rewrite di vercel.json).

## Pairing
```bash
# 1) buat kode (berlaku 10 menit)
curl -s -X POST https://DOMAIN/api/payment/listener/pair-code -H "x-owner-secret: $OWNER_DASHBOARD_SECRET"
# 2) masukkan kode di APK → APK memanggil /pair → menerima token (disimpan terenkripsi di HP, hanya hash di server)
# daftar & cabut perangkat
curl -s https://DOMAIN/api/payment/listener/devices -H "x-owner-secret: $OWNER_DASHBOARD_SECRET"
curl -s -X POST https://DOMAIN/api/payment/listener/revoke -H "x-owner-secret: $OWNER_DASHBOARD_SECRET" -H "content-type: application/json" -d '{"device_id":"UUID"}'
```

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
`parsed:false` atau `amount:null` → selalu NEEDS_REVIEW. Isi notifikasi mentah tidak dikirim dan tidak disimpan.

**POST /heartbeat** `{listener_connected, queue_depth, app_version, battery?, battery_unrestricted?}` → `{ok, server_time}` (20/menit).

**GET /status** → `{ok, server_time, device, counts_30d:{PAID:n,NEEDS_REVIEW:n,...}}`.

**POST /approve** (owner) `{event_id, order_id?}` → menyetujui event NEEDS_REVIEW (order harus PENDING).

## Aturan keputusan (backend)
Auto-aktif hanya jika SEMUA benar: `LISTENER_AUTO_ACTIVATE=true`, parser menandai valid, package diizinkan,
nominal punya reservasi unik milik order itu, order PENDING, `total_amount_idr` = nominal, waktu notifikasi
dalam jendela [order dibuat −2 mnt, kedaluwarsa +1 mnt], bukan duplikat. Selain itu NEEDS_REVIEW / DUPLICATE / REJECTED.
Aktivasi memakai compare-and-set `PENDING→PAID` yang sama dengan jalur Casaku, lalu stok + invite VIP lama.

## Status
Dipakai: `PENDING`, `PAID`, `CANCELLED` (lama). Event: `RECEIVED/MATCHED/PAID/NEEDS_REVIEW/DUPLICATE/REJECTED`.
Status order `EXPIRED` belum diubah otomatis; kedaluwarsa ditangani lewat `qr_expires_at` dan kunci nominal.

## Uji
`node --test test/listener.test.js test/http.smoke.test.js`
