// Inti keputusan pembayaran dari event notifikasi APK.
// Semua keputusan dibuat di sini (backend). APK tidak pernah mengaktifkan VIP.
// Dependensi database/Telegram disuntik lewat `store` agar bisa diuji tanpa Supabase.
const crypto = require('crypto');

const STATUS = { RECEIVED: 'RECEIVED', MATCHED: 'MATCHED', PAID: 'PAID', NEEDS_REVIEW: 'NEEDS_REVIEW', DUPLICATE: 'DUPLICATE', REJECTED: 'REJECTED' };

const DEFAULT_CFG = {
  autoActivate: false,               // aman: default semua masuk NEEDS_REVIEW sampai owner mengaktifkan
  futureSkewMs: 5 * 60 * 1000,       // notifikasi dari "masa depan" > 5 menit ditolak
  maxAgeMs: 7 * 24 * 3600 * 1000,    // event lebih tua dari 7 hari ditolak
  beforeOrderSlackMs: 2 * 60 * 1000, // toleransi selisih jam HP sebelum order dibuat
  afterExpirySlackMs: 60 * 1000,     // toleransi setelah QR kedaluwarsa
  allowedPackages: [],               // kosong = tidak membatasi package di backend
};

const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

function validateBody(b) {
  if (!b || typeof b !== 'object') return 'body_invalid';
  if (typeof b.event_id !== 'string' || !/^[A-Za-z0-9._:-]{8,64}$/.test(b.event_id)) return 'event_id_invalid';
  if (typeof b.package_name !== 'string' || b.package_name.length < 3 || b.package_name.length > 120) return 'package_invalid';
  if (!Number.isFinite(Number(b.posted_at)) || Number(b.posted_at) <= 0) return 'posted_at_invalid';
  if (b.amount !== null && b.amount !== undefined) {
    const a = Number(b.amount);
    if (!Number.isInteger(a) || a <= 0 || a > 999999999) return 'amount_invalid';
  }
  if (b.reference != null && (typeof b.reference !== 'string' || b.reference.length > 80)) return 'reference_invalid';
  return null;
}

function fingerprintOf({ package_name, amount, reference, posted_at }) {
  const key = reference ? `ref:${reference}` : `min:${Math.floor(Number(posted_at) / 60000)}`;
  return sha256(`${package_name}|${amount ?? 'null'}|${key}`);
}

// Murni (tanpa I/O): menentukan apakah event cocok dengan order. Tidak pernah mengaktifkan apa pun.
function decide({ event, lock, order, cfg = DEFAULT_CFG }) {
  if (!event.parsed || event.amount == null) return { status: STATUS.NEEDS_REVIEW, reason: 'unparsed' };
  if (cfg.allowedPackages?.length && !cfg.allowedPackages.includes(event.package_name)) return { status: STATUS.NEEDS_REVIEW, reason: 'package_not_allowed' };
  if (!lock) return { status: STATUS.NEEDS_REVIEW, reason: 'no_matching_order' };
  if (!order) return { status: STATUS.NEEDS_REVIEW, reason: 'order_not_found', order_id: lock.order_id };
  const base = { order_id: order.order_id };
  if (lock.order_id !== order.order_id) return { ...base, status: STATUS.NEEDS_REVIEW, reason: 'lock_order_mismatch' };
  if (order.status === 'PAID') return { ...base, status: STATUS.DUPLICATE, reason: 'order_already_paid' };
  if (order.status !== 'PENDING') return { ...base, status: STATUS.NEEDS_REVIEW, reason: `order_${String(order.status).toLowerCase()}` };
  if (Number(order.total_amount_idr) !== Number(event.amount)) return { ...base, status: STATUS.NEEDS_REVIEW, reason: 'amount_mismatch' };
  const t = typeof event.posted_at === 'number' ? event.posted_at : new Date(event.posted_at).getTime();
  const lockFrom = new Date(lock.created_at).getTime(), lockTo = new Date(lock.expires_at).getTime();
  if (![t, lockFrom, lockTo].every(Number.isFinite)) return { ...base, status: STATUS.NEEDS_REVIEW, reason: 'timestamp_invalid' }; // jangan pernah meloloskan waktu yang tak terbaca
  if (t < lockFrom - cfg.beforeOrderSlackMs) return { ...base, status: STATUS.NEEDS_REVIEW, reason: 'before_order' };
  if (t > lockTo + cfg.afterExpirySlackMs) return { ...base, status: STATUS.NEEDS_REVIEW, reason: 'after_expiry' };
  return { ...base, status: STATUS.MATCHED, reason: 'exact_unique_amount' };
}

function createProcessor(store, cfgIn = {}) {
  const cfg = { ...DEFAULT_CFG, ...cfgIn };

  async function processEvent(device, body, now = Date.now()) {
    const bad = validateBody(body);
    if (bad) return { http: 400, json: { ok: false, error: bad } };

    const postedAt = Number(body.posted_at);
    const amount = body.amount == null ? null : Number(body.amount);
    const parsed = body.parsed === true && amount != null;
    const base = {
      device_id: device.id, event_id: body.event_id, package_name: body.package_name,
      amount, reference: body.reference || null, posted_at: new Date(postedAt).toISOString(),
      parsed, fingerprint: fingerprintOf({ package_name: body.package_name, amount, reference: body.reference, posted_at: postedAt }),
      meta: { app_version: body.app_version || null, text_hash: typeof body.text_hash === 'string' ? body.text_hash.slice(0, 64) : null, queued: body.queued === true },
    };

    // Penolakan timestamp: dicatat sebagai REJECTED agar APK tidak mengulang kirim.
    if (postedAt > now + cfg.futureSkewMs) return finish(await store.insertEvent({ ...base, status: STATUS.REJECTED, reason: 'future_timestamp', fingerprint: base.fingerprint + ':r:' + base.event_id }));
    if (postedAt < now - cfg.maxAgeMs) return finish(await store.insertEvent({ ...base, status: STATUS.REJECTED, reason: 'too_old', fingerprint: base.fingerprint + ':r:' + base.event_id }));

    const ins = await store.insertEvent({ ...base, status: STATUS.RECEIVED, reason: null });
    // Idempotent: event_id yang sama sudah diputuskan -> kembalikan hasil lama.
    // Pengecualian: tersangkut di RECEIVED (fungsi sempat crash) -> proses ulang; aktivasi memakai compare-and-set sehingga aman.
    if (ins.existing && ins.existing.status !== STATUS.RECEIVED) return { http: 200, json: { ok: true, duplicate: true, status: ins.existing.status, reason: ins.existing.reason || null, order_id: ins.existing.order_id || null } };
    if (ins.fingerprintConflict) {
      const dup = await store.insertEvent({ ...base, status: STATUS.DUPLICATE, reason: 'duplicate_fingerprint', fingerprint: base.fingerprint + ':d:' + base.event_id });
      return finish(dup);
    }
    const ev = ins.row || ins.existing;

    const lock = amount != null ? await store.getLock(amount) : null;
    const order = lock ? await store.getOrder(lock.order_id) : null;
    let d = decide({ event: { ...base, amount, parsed }, lock, order, cfg });

    if (d.status === STATUS.MATCHED) {
      if (cfg.autoActivate) {
        const r = await store.activate(order.order_id, `gopay-listener:${ev.id}`);
        if (r.ok && !r.already) d = { ...d, status: STATUS.PAID, reason: 'auto_activated' };
        else if (r.ok && r.already) d = { ...d, status: STATUS.DUPLICATE, reason: 'order_already_paid' };
        else d = { ...d, status: STATUS.NEEDS_REVIEW, reason: 'activation_failed' };
      } else {
        d = { ...d, status: STATUS.NEEDS_REVIEW, reason: 'auto_activate_disabled' };
      }
    }
    const saved = await store.updateEvent(ev.id, { status: d.status, reason: d.reason, order_id: d.order_id || null });
    if (d.status === STATUS.NEEDS_REVIEW) await store.notifyReview({ ...ev, ...saved, amount, order_id: d.order_id || null, reason: d.reason });
    return { http: 200, json: { ok: true, duplicate: false, status: d.status, reason: d.reason, order_id: d.order_id || null } };
  }

  function finish(ins) {
    const r = ins.row || ins.existing || {};
    return { http: 200, json: { ok: true, duplicate: !!ins.existing, status: r.status, reason: r.reason || null, order_id: r.order_id || null } };
  }

  // Persetujuan manual owner terhadap event NEEDS_REVIEW.
  async function approve(eventId, orderIdOverride = null) {
    const ev = await store.getEvent(eventId);
    if (!ev) return { http: 404, json: { ok: false, error: 'event_not_found' } };
    if (ev.status !== STATUS.NEEDS_REVIEW) return { http: 409, json: { ok: false, error: `event_status_${ev.status}` } };
    const orderId = orderIdOverride || ev.order_id;
    if (!orderId) return { http: 400, json: { ok: false, error: 'order_id_required' } };
    const order = await store.getOrder(orderId);
    if (!order) return { http: 404, json: { ok: false, error: 'order_not_found' } };
    if (order.status !== 'PENDING') return { http: 409, json: { ok: false, error: `order_${String(order.status).toLowerCase()}` } };
    const r = await store.activate(orderId, `gopay-listener:${ev.id}:manual`, ev.id);
    if (!r.ok || r.already) return { http: 409, json: { ok: false, error: 'activation_failed' } };
    await store.updateEvent(ev.id, { status: STATUS.PAID, reason: 'manual_approved', order_id: orderId });
    await store.audit('listener_event_approved', { event_id: ev.id, order_id: orderId });
    return { http: 200, json: { ok: true, status: STATUS.PAID, order_id: orderId } };
  }

  return { processEvent, approve, cfg };
}

module.exports = { STATUS, DEFAULT_CFG, validateBody, fingerprintOf, decide, createProcessor };
