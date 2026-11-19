// Autentikasi perangkat APK, kode pairing, dan pembatasan laju (rate limit).
const crypto = require('crypto');
const { config, supabase } = require('./config');

const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
const TOKEN_PREFIX = 'xpd_';
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // tanpa 0/O/1/I

function newDeviceToken() { return TOKEN_PREFIX + crypto.randomBytes(32).toString('base64url'); }
function newPairingCode() {
  let c = '';
  const bytes = crypto.randomBytes(8);
  for (let i = 0; i < 8; i++) c += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return `${c.slice(0, 4)}-${c.slice(4)}`;
}
const normalizeCode = (c) => String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const hashCode = (c) => sha256('pair:' + normalizeCode(c));

function safeEqual(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  if (x.length !== y.length) return false;
  return crypto.timingSafeEqual(x, y);
}
function ownerAuthorized(req) {
  const s = req.headers['x-owner-secret'] || '';
  return !!config.ownerDashboardSecret && !!s && safeEqual(s, config.ownerDashboardSecret);
}

function bearer(req) {
  const h = String(req.headers.authorization || '');
  const m = /^Bearer\s+(\S+)$/i.exec(h);
  return m ? m[1] : null;
}

// Mengembalikan baris perangkat bila token valid & belum dicabut, selain itu null.
async function authenticateDevice(req) {
  const token = bearer(req);
  if (!token || !token.startsWith(TOKEN_PREFIX) || token.length > 128) return null;
  const db = supabase(); if (!db) return null;
  const { data, error } = await db.from('xian_pay_devices').select('*').eq('token_hash', sha256(token)).maybeSingle();
  if (error || !data || data.revoked_at) return null;
  return data;
}

function clientIp(req) {
  const xf = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return xf || req.socket?.remoteAddress || 'unknown';
}

// true = masih diizinkan. failOpen: perilaku bila RPC error.
async function rateOk(key, windowSeconds, limit, { failOpen = true } = {}) {
  const db = supabase(); if (!db) return failOpen;
  try {
    const { data, error } = await db.rpc('xian_pay_rate_hit', { p_key: key, p_window_seconds: windowSeconds, p_limit: limit });
    if (error) { console.error('[rate]', error.message); return failOpen; }
    return data === true;
  } catch (e) { console.error('[rate]', e.message); return failOpen; }
}

module.exports = { sha256, newDeviceToken, newPairingCode, normalizeCode, hashCode, safeEqual, ownerAuthorized, bearer, authenticateDevice, clientIp, rateOk, TOKEN_PREFIX };
