// Store produksi (Supabase + Telegram) untuk listener-core, plus pembuatan QRIS dinamis dari QRIS statis.
const { config, supabase, tg } = require('./config');
const { grantVipAccess } = require('./vip');
const audit = require('./audit');
const { buildDynamicQris } = require('./gopay-qris');
const { createProcessor } = require('./listener-core');

const esc = (v) => String(v ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const csv = (n) => String(process.env[n] || '').split(',').map((x) => x.trim()).filter(Boolean);

function listenerConfig() {
  // Manual approval wajib. Env LISTENER_AUTO_ACTIVATE sengaja diabaikan.
  return { autoActivate: false, allowedPackages: csv('LISTENER_ALLOWED_PACKAGES') };
}
const providerIsListener = () => String(process.env.PAYMENT_PROVIDER || 'casaku').toLowerCase() === 'gopay_listener';

async function processFulfillment(order) {
  const db = supabase();
  const { data: row, error: readError } = await db.from('xian_pay_fulfillments')
    .select('*').eq('order_id', order.order_id).maybeSingle();
  if (readError || !row) return { ok: false, error: readError?.message || 'fulfillment_not_found' };
  if (row.status === 'COMPLETE') return { ok: true, already: true, status: row.status };
  if (row.status !== 'PENDING') return { ok: false, status: row.status, error: 'fulfillment_requires_owner_review' };

  const { data: claimed, error: claimError } = await db.from('xian_pay_fulfillments')
    .update({ status: 'PROCESSING', processing_started_at: new Date().toISOString(), updated_at: new Date().toISOString(), last_error: null })
    .eq('order_id', order.order_id).eq('status', 'PENDING').select('*').maybeSingle();
  if (claimError || !claimed) return { ok: false, error: claimError?.message || 'fulfillment_claimed_elsewhere' };

  try {
    // PROCESSING tidak otomatis diulang setelah crash/time-out karena Telegram bisa saja
    // sudah menerima invite/message. Kasus ambigu harus diperiksa owner, bukan diduplikasi.
    const result = await grantVipAccess(order);
    const complete = !!result?.invite_link && !result?.invite_error;
    const patch = {
      status: complete ? 'COMPLETE' : 'NEEDS_REVIEW',
      invite_link: result?.invite_link || null,
      last_error: result?.invite_error ? String(result.invite_error).slice(0, 500) : (complete ? null : 'delivery_result_ambiguous'),
      completed_at: complete ? new Date().toISOString() : null,
      updated_at: new Date().toISOString(),
    };
    const { error } = await db.from('xian_pay_fulfillments').update(patch).eq('order_id', order.order_id).eq('status', 'PROCESSING');
    if (error) return { ok: false, error: error.message, status: patch.status };
    return { ok: complete, status: patch.status, error: patch.last_error };
  } catch (e) {
    const message = String(e?.message || e).slice(0, 500);
    await db.from('xian_pay_fulfillments').update({ status: 'NEEDS_REVIEW', last_error: message, updated_at: new Date().toISOString() })
      .eq('order_id', order.order_id).eq('status', 'PROCESSING');
    return { ok: false, status: 'NEEDS_REVIEW', error: message };
  }
}

// Persetujuan pembayaran dan pengurangan stok dilakukan atomik di Postgres.
async function activateOrder(orderId, paymentRef, eventId = null) {
  const db = supabase();
  const { data, error } = await db.rpc('xian_pay_approve_order', {
    p_order_id: orderId,
    p_payment_ref: String(paymentRef || '').slice(0, 160),
    p_event_id: eventId,
  });
  if (error) return { ok: false, error: error.message };
  if (!data?.ok) return { ok: false, error: data?.error || 'approval_failed', status: data?.status };
  if (data.already) return { ok: true, already: true, order: data.order };
  const fulfillment = await processFulfillment(data.order);
  return { ok: true, already: false, order: data.order, fulfillment };
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
        'Pembayaran TIDAK diaktifkan otomatis. Buka Owner Panel → Review Pembayaran untuk pemeriksaan dan persetujuan manual.'].join('\n');
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

module.exports = { listenerConfig, providerIsListener, activateOrder, processFulfillment, productionStore, processor, createListenerQris };
