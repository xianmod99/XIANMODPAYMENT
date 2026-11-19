// Jalankan: node --test test/
const test = require('node:test');
const assert = require('node:assert');
const { crc16ccitt, isValidQris, buildDynamicQris, parseTlv } = require('../lib/gopay-qris');
const { createProcessor, decide, fingerprintOf, validateBody } = require('../lib/listener-core');

// ---- QRIS ----
// Payload statis SINTETIS (bukan merchant asli), CRC dihitung dari algoritma standar.
function synthStatic() {
  const tlv = (t, v) => t + String(v.length).padStart(2, '0') + v;
  const body = tlv('00', '01') + tlv('01', '11') + tlv('26', tlv('00', 'ID.CO.EXAMPLE.WWW') + tlv('01', 'ID0000000000001')) + tlv('52', '5411') + tlv('53', '360') + tlv('58', 'ID') + tlv('59', 'TOKO UJI') + tlv('60', 'JAKARTA') + '6304';
  return body + crc16ccitt(body);
}
test('CRC16-CCITT cocok dengan vektor uji baku ("123456789" -> 29B1)', () => {
  assert.strictEqual(crc16ccitt('123456789'), '29B1');
});
test('QRIS statis sintetis valid; payload rusak ditolak', () => {
  const s = synthStatic();
  assert.ok(isValidQris(s));
  assert.ok(!isValidQris(s.slice(0, -1) + (s.endsWith('0') ? '1' : '0')));
  assert.throws(() => buildDynamicQris('000201abc', 1000));
});
test('buildDynamicQris: tag 01=12, tag 54 benar & berurutan, CRC valid, tidak mengubah tag lain', () => {
  const s = synthStatic();
  const d = buildDynamicQris(s, 20123);
  assert.ok(isValidQris(d));
  const tags = parseTlv(d.slice(0, -8));
  const m = Object.fromEntries(tags.map((t) => [t.tag, t.value]));
  assert.strictEqual(m['01'], '12');
  assert.strictEqual(m['54'], '20123');
  const order = tags.map((t) => Number(t.tag));
  assert.deepStrictEqual(order, [...order].sort((a, b) => a - b), 'urutan tag naik');
  assert.strictEqual(m['59'], 'TOKO UJI');
  assert.strictEqual(m['26'], parseTlv(s.slice(0, -8)).find((t) => t.tag === '26').value);
  // nominal lama diganti, bukan digandakan
  const d2 = buildDynamicQris(d, 5555);
  assert.strictEqual(parseTlv(d2.slice(0, -8)).filter((t) => t.tag === '54').length, 1);
  assert.throws(() => buildDynamicQris(s, 0));
  assert.throws(() => buildDynamicQris(s, 10.5));
});

// ---- Store tiruan di memori ----
function memStore({ orders = [], locks = [] } = {}) {
  const events = []; const notified = []; const audits = []; let seq = 0;
  const S = {
    events, notified, audits, orders,
    async insertEvent(row) {
      const byEvent = events.find((e) => e.device_id === row.device_id && e.event_id === row.event_id);
      if (byEvent) return { existing: byEvent };
      const live = !['DUPLICATE', 'REJECTED'].includes(row.status);
      if (live && events.some((e) => e.fingerprint === row.fingerprint && !['DUPLICATE', 'REJECTED'].includes(e.status))) return { fingerprintConflict: true };
      const r = { id: `ev-${++seq}`, ...row }; events.push(r); return { row: r };
    },
    async updateEvent(id, patch) { const e = events.find((x) => x.id === id); Object.assign(e, patch); return e; },
    async getEvent(id) { return events.find((e) => e.id === id) || null; },
    async getLock(amount) { return locks.find((l) => Number(l.amount) === Number(amount)) || null; },
    async getOrder(id) { return orders.find((o) => o.order_id === id) || null; },
    async activate(id) { const o = orders.find((x) => x.order_id === id); if (!o) return { ok: false }; if (o.status === 'PAID') return { ok: true, already: true }; if (o.status !== 'PENDING') return { ok: false }; o.status = 'PAID'; return { ok: true, already: false }; },
    async audit(a, d) { audits.push([a, d]); },
    async notifyReview(ev) { notified.push(ev); },
  };
  return S;
}
const NOW = Date.UTC(2026, 10, 19, 4, 0, 0);
const dev = { id: 'dev-1' };
const mkOrder = (o = {}) => ({ order_id: 'XIAN-A', status: 'PENDING', total_amount_idr: 20123, price_idr: 20000, ...o });
const mkLock = (o = {}) => ({ amount: 20123, order_id: 'XIAN-A', created_at: new Date(NOW - 5 * 60000).toISOString(), expires_at: new Date(NOW + 10 * 60000).toISOString(), ...o });
const ev = (o = {}) => ({ event_id: 'evt-0000001', package_name: 'com.example.merchant', amount: 20123, posted_at: NOW - 30000, parsed: true, reference: null, ...o });

test('validateBody menolak input buruk', () => {
  assert.strictEqual(validateBody(ev()), null);
  assert.ok(validateBody(ev({ event_id: 'x' })));
  assert.ok(validateBody(ev({ amount: -5 })));
  assert.ok(validateBody(ev({ amount: 10.5 })));
  assert.ok(validateBody(ev({ package_name: '' })));
  assert.ok(validateBody(null));
});

test('auto-activate OFF (default): cocok sempurna tetap NEEDS_REVIEW, order TIDAK jadi PAID', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const r = await createProcessor(st).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.status, 'NEEDS_REVIEW');
  assert.strictEqual(r.json.reason, 'auto_activate_disabled');
  assert.strictEqual(st.orders[0].status, 'PENDING');
  assert.strictEqual(st.notified.length, 1);
});

test('auto-activate ON: nominal unik cocok -> PAID dan order diaktifkan', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.status, 'PAID');
  assert.strictEqual(st.orders[0].status, 'PAID');
  assert.strictEqual(st.notified.length, 0);
});

test('idempotensi: event_id yang sama dikirim dua kali -> aktivasi hanya sekali', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const p = createProcessor(st, { autoActivate: true });
  const a = await p.processEvent(dev, ev(), NOW);
  const b = await p.processEvent(dev, ev(), NOW);
  assert.strictEqual(a.json.status, 'PAID');
  assert.strictEqual(b.json.duplicate, true);
  assert.strictEqual(b.json.status, 'PAID');
  assert.strictEqual(st.events.length, 1);
});

test('duplikat: event_id berbeda tapi fingerprint sama -> DUPLICATE, tidak memproses lagi', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const p = createProcessor(st, { autoActivate: true });
  await p.processEvent(dev, ev(), NOW);
  const r = await p.processEvent(dev, ev({ event_id: 'evt-0000002' }), NOW);
  assert.strictEqual(r.json.status, 'DUPLICATE');
  assert.strictEqual(st.orders[0].status, 'PAID');
  assert.strictEqual(st.notified.length, 0);
});

test('transaksi ambigu / tanpa order: nominal tidak punya reservasi -> NEEDS_REVIEW', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev({ amount: 20999 }), NOW);
  assert.strictEqual(r.json.status, 'NEEDS_REVIEW');
  assert.strictEqual(r.json.reason, 'no_matching_order');
  assert.strictEqual(st.orders[0].status, 'PENDING');
});

test('parser belum memvalidasi (parsed=false / amount null) -> NEEDS_REVIEW, tidak pernah auto-aktif', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const p = createProcessor(st, { autoActivate: true });
  const a = await p.processEvent(dev, ev({ parsed: false }), NOW);
  const b = await p.processEvent(dev, ev({ event_id: 'evt-0000003', amount: null, posted_at: NOW - 1 }), NOW);
  assert.strictEqual(a.json.reason, 'unparsed');
  assert.strictEqual(b.json.reason, 'unparsed');
  assert.strictEqual(st.orders[0].status, 'PENDING');
});

test('bayar setelah QR kedaluwarsa -> NEEDS_REVIEW (after_expiry)', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock({ expires_at: new Date(NOW - 20 * 60000).toISOString() })] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.reason, 'after_expiry');
  assert.strictEqual(st.orders[0].status, 'PENDING');
});

test('notifikasi lebih awal dari order dibuat -> NEEDS_REVIEW (before_order)', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev({ posted_at: NOW - 3600000 }), NOW);
  assert.strictEqual(r.json.reason, 'before_order');
});

test('order sudah PAID (mis. lewat jalur lain) -> DUPLICATE, tidak diaktifkan dua kali', async () => {
  const st = memStore({ orders: [mkOrder({ status: 'PAID' })], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.status, 'DUPLICATE');
});

test('order CANCELLED/EXPIRED -> NEEDS_REVIEW', async () => {
  const st = memStore({ orders: [mkOrder({ status: 'CANCELLED' })], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.reason, 'order_cancelled');
});

test('total_amount order tidak sama dengan nominal event -> NEEDS_REVIEW (amount_mismatch)', async () => {
  const st = memStore({ orders: [mkOrder({ total_amount_idr: 20124 })], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.reason, 'amount_mismatch');
});

test('timestamp: masa depan dan terlalu lama -> REJECTED, tidak memicu apa pun', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const p = createProcessor(st, { autoActivate: true });
  const f = await p.processEvent(dev, ev({ posted_at: NOW + 3600000 }), NOW);
  const o = await p.processEvent(dev, ev({ event_id: 'evt-0000009', posted_at: NOW - 30 * 86400000 }), NOW);
  assert.strictEqual(f.json.status, 'REJECTED'); assert.strictEqual(f.json.reason, 'future_timestamp');
  assert.strictEqual(o.json.status, 'REJECTED'); assert.strictEqual(o.json.reason, 'too_old');
  assert.strictEqual(st.orders[0].status, 'PENDING');
});

test('daftar package: package asing -> NEEDS_REVIEW', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const r = await createProcessor(st, { autoActivate: true, allowedPackages: ['com.real.merchant'] }).processEvent(dev, ev(), NOW);
  assert.strictEqual(r.json.reason, 'package_not_allowed');
});

test('approve manual: NEEDS_REVIEW -> PAID sekali; kedua kali ditolak', async () => {
  const st = memStore({ orders: [mkOrder()], locks: [mkLock()] });
  const p = createProcessor(st); // auto OFF
  await p.processEvent(dev, ev(), NOW);
  const id = st.events[0].id;
  const a = await p.approve(id);
  assert.strictEqual(a.http, 200);
  assert.strictEqual(st.orders[0].status, 'PAID');
  const b = await p.approve(id);
  assert.strictEqual(b.http, 409);
  assert.strictEqual(st.audits.length, 1);
});

test('approve: event tanpa order butuh order_id eksplisit; order bukan PENDING ditolak', async () => {
  const st = memStore({ orders: [mkOrder({ order_id: 'XIAN-B', status: 'PAID' })], locks: [] });
  const p = createProcessor(st);
  await p.processEvent(dev, ev(), NOW); // no_matching_order
  const id = st.events[0].id;
  assert.strictEqual((await p.approve(id)).json.error, 'order_id_required');
  assert.strictEqual((await p.approve(id, 'XIAN-B')).json.error, 'order_paid');
  assert.strictEqual((await p.approve(id, 'XIAN-Z')).json.error, 'order_not_found');
});

test('decide() murni: dua order nominal sama tidak mungkin, tapi lock milik order lain tetap ditolak', () => {
  const d = decide({ event: { ...ev(), parsed: true }, lock: mkLock({ order_id: 'XIAN-OTHER' }), order: mkOrder() });
  assert.strictEqual(d.reason, 'lock_order_mismatch');
});

test('fingerprint stabil, dan memakai referensi bila ada', () => {
  const a = fingerprintOf({ package_name: 'p', amount: 1000, reference: null, posted_at: NOW });
  const b = fingerprintOf({ package_name: 'p', amount: 1000, reference: null, posted_at: NOW + 1000 });
  const c = fingerprintOf({ package_name: 'p', amount: 1000, reference: 'R1', posted_at: NOW });
  const d2 = fingerprintOf({ package_name: 'p', amount: 1000, reference: 'R1', posted_at: NOW + 999999 });
  assert.strictEqual(a, b); assert.notStrictEqual(a, c); assert.strictEqual(c, d2);
});

test('regresi: waktu yang tak terbaca tidak boleh meloloskan pembayaran', () => {
  const d = decide({ event: { ...ev(), posted_at: 'bukan-tanggal', parsed: true }, lock: mkLock(), order: mkOrder() });
  assert.strictEqual(d.reason, 'timestamp_invalid');
  const d2 = decide({ event: { ...ev(), posted_at: new Date(NOW - 30000).toISOString(), parsed: true }, lock: mkLock({ expires_at: 'x' }), order: mkOrder() });
  assert.strictEqual(d2.reason, 'timestamp_invalid');
});
