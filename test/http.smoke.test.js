// Smoke test routing + autentikasi handler HTTP dengan Supabase tiruan (tanpa jaringan).
const test = require('node:test');
const assert = require('node:assert');
const Module = require('module');

const tables = { xian_pay_devices: [], xian_pay_pairing_codes: [], xian_pay_events: [] };
let rateAllowed = true;
function builder(name) {
  let rows = tables[name] || [], filters = [], op = 'select', patch = null, ins = null;
  const api = {
    select() { return api; }, order() { return api; }, limit() { return api; },
    insert(v) { op = 'insert'; ins = v; return api; },
    update(v) { op = 'update'; patch = v; return api; },
    eq(c, v) { filters.push((r) => r[c] === v); return api; },
    is(c, v) { filters.push((r) => (r[c] ?? null) === v); return api; },
    gt(c, v) { filters.push((r) => r[c] > v); return api; },
    gte() { return api; },
    _run() {
      const m = rows.filter((r) => filters.every((f) => f(r)));
      if (op === 'insert') { const r = { id: `id-${rows.length + 1}`, ...ins }; rows.push(r); return [r]; }
      if (op === 'update') { m.forEach((r) => Object.assign(r, patch)); return m; }
      return m;
    },
    maybeSingle() { return Promise.resolve({ data: api._run()[0] || null, error: null }); },
    single() { return Promise.resolve({ data: api._run()[0] || null, error: null }); },
    then(res) { return Promise.resolve({ data: api._run(), error: null }).then(res); },
  };
  return api;
}
const origLoad = Module._load;
Module._load = function (req, ...a) {
  if (req === '@supabase/supabase-js') return { createClient: () => ({ from: builder, rpc: async (fn) => ({ data: fn === 'xian_pay_rate_hit' ? rateAllowed : null, error: null }) }) };
  return origLoad.call(this, req, ...a);
};
process.env.SUPABASE_URL = 'http://x'; process.env.SUPABASE_SERVICE_ROLE_KEY = 'k';
process.env.OWNER_DASHBOARD_SECRET = 'owner-secret-test'; process.env.BOT_TOKEN = '';
const handler = require('../api/payment/listener/[action].js');

function call(method, action, { headers = {}, body } = {}) {
  return new Promise((resolve) => {
    const res = { code: 200, headers: {}, setHeader(k, v) { this.headers[k] = v; }, status(c) { this.code = c; return this; }, json(b) { resolve({ code: this.code, body: b }); return this; } };
    handler({ method, query: { action }, headers: { 'x-forwarded-for': '1.2.3.4', ...headers }, body, socket: {} }, res);
  });
}

test('rute: aksi tak dikenal 404; method salah 405', async () => {
  assert.strictEqual((await call('GET', 'nope')).code, 404);
  assert.strictEqual((await call('GET', 'pair')).code, 405);
  assert.strictEqual((await call('GET', 'event', { headers: { authorization: 'Bearer xpd_abc' } })).code, 405);
});
test('endpoint perangkat tanpa/ salah token -> 401', async () => {
  assert.strictEqual((await call('POST', 'event', { body: {} })).code, 401);
  assert.strictEqual((await call('POST', 'event', { headers: { authorization: 'Bearer salah' }, body: {} })).code, 401);
  assert.strictEqual((await call('POST', 'heartbeat', { headers: { authorization: 'Bearer xpd_tidakada' }, body: {} })).code, 401);
});
test('endpoint owner tanpa/ salah secret -> 401; secret benar -> berhasil', async () => {
  assert.strictEqual((await call('POST', 'pair-code')).code, 401);
  assert.strictEqual((await call('POST', 'pair-code', { headers: { 'x-owner-secret': 'salah' } })).code, 401);
  const r = await call('POST', 'pair-code', { headers: { 'x-owner-secret': 'owner-secret-test' } });
  assert.strictEqual(r.code, 200); assert.match(r.body.code, /^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
});
let token, code;
test('pairing penuh: kode -> token; kode tidak bisa dipakai dua kali; token dipakai untuk heartbeat', async () => {
  code = (await call('POST', 'pair-code', { headers: { 'x-owner-secret': 'owner-secret-test' } })).body.code;
  const p = await call('POST', 'pair', { body: { code: code.toLowerCase().replace('-', ' '), device_name: 'Poco X6 Pro', app_version: '1.0.0' } });
  assert.strictEqual(p.code, 200); assert.ok(p.body.token.startsWith('xpd_'));
  token = p.body.token;
  assert.ok(!JSON.stringify(tables.xian_pay_devices).includes(token), 'token asli tidak disimpan, hanya hash');
  assert.strictEqual((await call('POST', 'pair', { body: { code } })).code, 401);
  const hb = await call('POST', 'heartbeat', { headers: { authorization: `Bearer ${token}` }, body: { listener_connected: true, queue_depth: 0 } });
  assert.strictEqual(hb.code, 200);
  assert.strictEqual(tables.xian_pay_devices[0].listener_connected, true);
});
test('revoke: token dicabut -> 401 pada request berikutnya', async () => {
  const id = tables.xian_pay_devices[0].id.replace(/^id-/, '');
  const bad = await call('POST', 'revoke', { headers: { 'x-owner-secret': 'owner-secret-test' }, body: { device_id: 'bukan-uuid' } });
  assert.strictEqual(bad.code, 400);
  tables.xian_pay_devices[0].id = '11111111-2222-3333-4444-555555555555';
  const r = await call('POST', 'revoke', { headers: { 'x-owner-secret': 'owner-secret-test' }, body: { device_id: '11111111-2222-3333-4444-555555555555' } });
  assert.strictEqual(r.code, 200);
  assert.strictEqual((await call('POST', 'heartbeat', { headers: { authorization: `Bearer ${token}` }, body: {} })).code, 401);
});
test('rate limit: pair dan owner gagal-tertutup (429) saat batas terlampaui', async () => {
  rateAllowed = false;
  assert.strictEqual((await call('POST', 'pair', { body: { code: 'ABCD-EFGH' } })).code, 429);
  assert.strictEqual((await call('POST', 'pair-code', { headers: { 'x-owner-secret': 'owner-secret-test' } })).code, 429);
  rateAllowed = true;
});
