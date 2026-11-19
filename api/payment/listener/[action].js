// XIANMOD PAYMENT listener API. Satu fungsi serverless untuk semua aksi (hemat kuota fungsi Vercel).
//   POST /api/payment/listener/pair        (kode pairing -> token perangkat)
//   POST /api/payment/listener/event       (token perangkat)
//   POST /api/payment/listener/heartbeat   (token perangkat)
//   GET  /api/payment/listener/status      (token perangkat)
//   POST /api/payment/listener/pair-code   (x-owner-secret)  buat kode pairing
//   GET  /api/payment/listener/devices     (x-owner-secret)  daftar perangkat
//   POST /api/payment/listener/revoke      (x-owner-secret)  cabut token perangkat
//   POST /api/payment/listener/approve     (x-owner-secret)  setujui event NEEDS_REVIEW
const { supabase } = require('../../../lib/config');
const A = require('../../../lib/listener-auth');
const { processor } = require('../../../lib/listener-pay');
const audit = require('../../../lib/audit');

const json = (res, code, body) => res.status(code).json(body);
const readBody = (req) => {
  if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) return req.body;
  try { return JSON.parse(Buffer.isBuffer(req.body) ? req.body.toString('utf8') : String(req.body || '{}')); } catch (_) { return null; }
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function actionOf(req) {
  const q = req.query && req.query.action;
  if (q) return String(Array.isArray(q) ? q[0] : q);
  const m = /\/listener\/([a-z-]+)/.exec(String(req.url || ''));
  return m ? m[1] : '';
}

async function pair(req, res) {
  const ip = A.clientIp(req);
  if (!(await A.rateOk(`pair:${ip}`, 600, 10, { failOpen: false }))) return json(res, 429, { ok: false, error: 'rate_limited' });
  const b = readBody(req); if (!b) return json(res, 400, { ok: false, error: 'body_invalid' });
  if (!A.normalizeCode(b.code)) return json(res, 400, { ok: false, error: 'code_required' });
  const db = supabase();
  const codeHash = A.hashCode(b.code);
  // Konsumsi kode secara atomik: hanya satu permintaan yang bisa mengubah used_at dari NULL.
  const { data: used } = await db.from('xian_pay_pairing_codes')
    .update({ used_at: new Date().toISOString() })
    .eq('code_hash', codeHash).is('used_at', null).gt('expires_at', new Date().toISOString()).select('code_hash').maybeSingle();
  if (!used) return json(res, 401, { ok: false, error: 'code_invalid_or_expired' });
  const token = A.newDeviceToken();
  const name = String(b.device_name || 'XIANMOD PAYMENT').slice(0, 60);
  const { data: dev, error } = await db.from('xian_pay_devices').insert({ name, token_hash: A.sha256(token), app_version: String(b.app_version || '').slice(0, 32) || null, meta: { model: String(b.model || '').slice(0, 60) } }).select('id').single();
  if (error) { console.error('[pair]', error.message); return json(res, 500, { ok: false, error: 'pair_failed' }); }
  await db.from('xian_pay_pairing_codes').update({ device_id: dev.id }).eq('code_hash', codeHash);
  await audit.log('listener_device_paired', 'system', { device_id: dev.id, name });
  return json(res, 200, { ok: true, device_id: dev.id, token, server_time: Date.now() });
}

async function event(req, res, device) {
  if (!(await A.rateOk(`event:${device.id}`, 60, 120))) return json(res, 429, { ok: false, error: 'rate_limited' });
  const b = readBody(req); if (!b) return json(res, 400, { ok: false, error: 'body_invalid' });
  try {
    const r = await processor().processEvent(device, b);
    await supabase().from('xian_pay_devices').update({ last_seen_at: new Date().toISOString() }).eq('id', device.id);
    return json(res, r.http, r.json);
  } catch (e) { console.error('[event]', e); return json(res, 500, { ok: false, error: 'event_failed' }); }
}

async function heartbeat(req, res, device) {
  if (!(await A.rateOk(`hb:${device.id}`, 60, 20))) return json(res, 429, { ok: false, error: 'rate_limited' });
  const b = readBody(req) || {};
  await supabase().from('xian_pay_devices').update({
    last_seen_at: new Date().toISOString(),
    app_version: String(b.app_version || device.app_version || '').slice(0, 32) || null,
    listener_connected: typeof b.listener_connected === 'boolean' ? b.listener_connected : null,
    queue_depth: Number.isInteger(b.queue_depth) ? b.queue_depth : null,
    meta: { ...(device.meta || {}), battery: Number.isFinite(b.battery) ? b.battery : undefined, doze_ignored: typeof b.battery_unrestricted === 'boolean' ? b.battery_unrestricted : undefined },
  }).eq('id', device.id);
  return json(res, 200, { ok: true, server_time: Date.now() });
}

async function status(req, res, device) {
  if (!(await A.rateOk(`st:${device.id}`, 60, 30))) return json(res, 429, { ok: false, error: 'rate_limited' });
  const db = supabase();
  const since = new Date(Date.now() - 30 * 86400000).toISOString();
  const { data } = await db.from('xian_pay_events').select('status').eq('device_id', device.id).gte('received_at', since).limit(5000);
  const counts = {}; for (const r of data || []) counts[r.status] = (counts[r.status] || 0) + 1;
  return json(res, 200, { ok: true, server_time: Date.now(), device: { id: device.id, name: device.name, last_seen_at: device.last_seen_at }, counts_30d: counts });
}

async function pairCode(req, res) {
  const code = A.newPairingCode();
  const expires = new Date(Date.now() + 10 * 60000);
  const { error } = await supabase().from('xian_pay_pairing_codes').insert({ code_hash: A.hashCode(code), expires_at: expires.toISOString() });
  if (error) return json(res, 500, { ok: false, error: 'code_failed' });
  return json(res, 200, { ok: true, code, expires_at: expires.toISOString() });
}
async function devices(req, res) {
  const { data } = await supabase().from('xian_pay_devices').select('id,name,created_at,last_seen_at,revoked_at,app_version,listener_connected,queue_depth').order('created_at', { ascending: false }).limit(50);
  return json(res, 200, { ok: true, devices: data || [] });
}
async function revoke(req, res) {
  const b = readBody(req) || {};
  if (!UUID.test(String(b.device_id || ''))) return json(res, 400, { ok: false, error: 'device_id_invalid' });
  const { data } = await supabase().from('xian_pay_devices').update({ revoked_at: new Date().toISOString() }).eq('id', b.device_id).is('revoked_at', null).select('id').maybeSingle();
  if (!data) return json(res, 404, { ok: false, error: 'device_not_found_or_already_revoked' });
  await audit.log('listener_device_revoked', 'owner', { device_id: b.device_id });
  return json(res, 200, { ok: true });
}
async function approve(req, res) {
  const b = readBody(req) || {};
  if (!UUID.test(String(b.event_id || ''))) return json(res, 400, { ok: false, error: 'event_id_invalid' });
  const r = await processor().approve(b.event_id, b.order_id ? String(b.order_id).slice(0, 64) : null);
  return json(res, r.http, r.json);
}

const DEVICE = { event: ['POST', event], heartbeat: ['POST', heartbeat], status: ['GET', status] };
const OWNER = { 'pair-code': ['POST', pairCode], devices: ['GET', devices], revoke: ['POST', revoke], approve: ['POST', approve] };

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  try {
    const action = actionOf(req);
    if (action === 'pair') { if (req.method !== 'POST') return json(res, 405, { ok: false, error: 'method_not_allowed' }); return await pair(req, res); }
    if (OWNER[action]) {
      const [method, fn] = OWNER[action];
      if (req.method !== method) return json(res, 405, { ok: false, error: 'method_not_allowed' });
      if (!(await A.rateOk(`owner:${A.clientIp(req)}`, 60, 30, { failOpen: false }))) return json(res, 429, { ok: false, error: 'rate_limited' });
      if (!A.ownerAuthorized(req)) return json(res, 401, { ok: false, error: 'unauthorized' });
      return await fn(req, res);
    }
    if (DEVICE[action]) {
      const [method, fn] = DEVICE[action];
      if (req.method !== method) return json(res, 405, { ok: false, error: 'method_not_allowed' });
      const device = await A.authenticateDevice(req);
      if (!device) return json(res, 401, { ok: false, error: 'unauthorized' });
      return await fn(req, res, device);
    }
    return json(res, 404, { ok: false, error: 'not_found' });
  } catch (e) {
    console.error('[listener]', e);
    return json(res, 500, { ok: false, error: 'server_error' });
  }
};
