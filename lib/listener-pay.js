// Store produksi (Supabase + Telegram) untuk listener-core, plus pembuatan QRIS dinamis dari QRIS statis.
const { config, supabase, tg } = require('./config');
const { grantVipAccess } = require('./vip');
const { consumeStock } = require('./stock');
const audit = require('./audit');
const { buildDynamicQris } = require('./gopay-qris');
const { createProcessor } = require('./listener-core');

const esc = (v) => String(v ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const bool = (n, d = false) => String(process.env[n] ?? String(d)).toLowerCase() === 'true';
const csv = (n) => String(process.env[n] || '').split(',').map((x) => x.trim()).filter(Boolean);

function listenerConfig() {
  return {
    autoActivate: bool('LISTENER_AUTO_ACTIVATE', false),
    allowedPackages: csv('LISTENER_ALLOWED_PACKAGES'),
  };
}
const providerIsListener = () => String(process.env.PAYMENT_PROVIDER || 'casaku').toLowerCase() === 'gopay_listener';

// Sama seperti markOrderPaidByTransaction (lib/casaku.js) tetapi berdasarkan order_id, dan tanpa Casaku.
async function activateOrder(orderId, paymentRef) {
  const db = supabase();
  const { data: updated, error } = await db.from('orders')
    .update({ status: 'PAID', paid_at: new Date().toISOString(), payment_ref: paymentRef, payment_method: 'QRIS' })
    .eq('order_id', orderId).eq('status', 'PENDING').select('*').maybeSingle();
  if (error) return { ok: false, error: error.message };
  if (!updated) {
    const { data: cur } = await db.from('orders').select('status').eq('order_id', orderId).maybeSingle();
    return { ok: cur?.status === 'PAID', already: cur?.status === 'PAID' };
  }
  await consumeStock(updated);
  await grantVipAccess(updated);
  return { ok: true, already: false, order: updated };
}

function productionStore() {
  const db = () => supabase();
  return {
    async insertEvent(row) {
      const { data, error } = await db().from('xian_pay_events').insert(row).select('*').single();
      if (!error) return { row: data };
      if (error.code === '23505') {
        const { data: ex } = await db().from('xian_pay_events').select('*').eq('device_id', row.device_id).eq('event_id', row.event_id).maybeSingle();
        if (ex) return { existing: ex };
        return { fingerprintConflict: true };
      }
      throw error;
    },
    async updateEvent(id, patch) {
      const { data, error } = await db().from('xian_pay_events').update(patch).eq('id', id).select('*').single();
      if (error) throw error; return data;
    },
    async getEvent(id) { const { data } = await db().from('xian_pay_events').select('*').eq('id', id).maybeSingle(); return data; },
    async getLock(amount) { const { data } = await db().from('xian_pay_amount_locks').select('*').eq('amount', amount).maybeSingle(); return data; },
    async getOrder(id) { const { data } = await db().from('orders').select('*').eq('order_id', id).maybeSingle(); return data; },
    activate: activateOrder,
    audit: (a, d) => audit.log(a, 'system', d),
    async notifyReview(ev) {
      const ids = [...new Set([...(config.ownerTelegramIds || []), config.ownerId].filter(Boolean).map(String))];
      const text = ['🔎 <b>XIANMOD PAYMENT — PERLU REVIEW</b>', '<blockquote>',
        `💰 Nominal : ${ev.amount == null ? '-' : 'Rp ' + Number(ev.amount).toLocaleString('id-ID')}`,
        `📌 Alasan  : <code>${esc(ev.reason)}</code>`,
        `🧾 Order   : <code>${esc(ev.order_id || '-')}</code>`,
        `🆔 Event   : <code>${esc(ev.id)}</code>`, '</blockquote>',
        'Pembayaran TIDAK diaktifkan otomatis. Setujui lewat endpoint /api/payment/listener/approve bila sudah dicek.'].join('\n');
      for (const id of ids) { try { await tg('sendMessage', { chat_id: id, text, parse_mode: 'HTML' }); } catch (_) {} }
    },
  };
}

function processor() { return createProcessor(productionStore(), listenerConfig()); }

// ---- Pembuatan QRIS untuk order (dipakai telegram.js saat PAYMENT_PROVIDER=gopay_listener) ----
async function createListenerQris(order) {
  const staticQris = String(process.env.GOPAY_QRIS_STATIC || '').trim();
  if (!staticQris) throw new Error('GOPAY_QRIS_STATIC belum diatur.');
  const minutes = Number(process.env.LISTENER_ORDER_MINUTES || 15) || 15;
  const maxCode = Number(process.env.LISTENER_UNIQUE_MAX || 200) || 200;
  const expiresAt = new Date(Date.now() + minutes * 60000);
  const db = supabase();
  const { data: total, error } = await db.rpc('xian_pay_reserve_amount', { p_order_id: order.order_id, p_base: Number(order.price_idr), p_expires: expiresAt.toISOString(), p_max: maxCode, p_grace_minutes: 30 });
  if (error) throw new Error(`Gagal reservasi nominal: ${error.message}`);
  if (total == null) throw new Error('Semua nominal unik sedang terpakai. Coba lagi beberapa menit lagi.');
  const qrString = buildDynamicQris(staticQris, Number(total));
  return { totalAmount: Number(total), qrString, expiresAt: expiresAt.toISOString(), minutes };
}

module.exports = { listenerConfig, providerIsListener, activateOrder, productionStore, processor, createListenerQris };
