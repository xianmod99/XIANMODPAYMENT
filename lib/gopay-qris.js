// QRIS statis -> QRIS dinamis bernominal (standar EMV QRCPS / QRIS).
// Input: payload teks QRIS statis milik merchant sendiri (isi QR GoPay Merchant).
// Tidak ada kredensial atau scraping: hanya menyisipkan tag 54 (nominal) dan menghitung ulang CRC16.

function crc16ccitt(str) {
  let crc = 0xFFFF;
  for (let i = 0; i < str.length; i++) {
    crc ^= str.charCodeAt(i) << 8;
    for (let j = 0; j < 8; j++) crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
  }
  return crc.toString(16).toUpperCase().padStart(4, '0');
}

function parseTlv(payload) {
  const out = [];
  let i = 0;
  while (i < payload.length) {
    if (i + 4 > payload.length) throw new Error('QRIS: TLV terpotong');
    const tag = payload.slice(i, i + 2);
    const len = Number(payload.slice(i + 2, i + 4));
    if (!/^\d{2}$/.test(tag) || !Number.isInteger(len)) throw new Error('QRIS: TLV tidak valid');
    const value = payload.slice(i + 4, i + 4 + len);
    if (value.length !== len) throw new Error('QRIS: panjang TLV tidak cocok');
    out.push({ tag, value });
    i += 4 + len;
  }
  return out;
}

const tlv = (tag, value) => `${tag}${String(value.length).padStart(2, '0')}${value}`;

function isValidQris(payload) {
  try {
    const s = String(payload || '').trim();
    if (!s.startsWith('000201')) return false;
    const idx = s.length - 8;
    if (s.slice(idx, idx + 4) !== '6304') return false;
    return crc16ccitt(s.slice(0, s.length - 4)) === s.slice(-4).toUpperCase();
  } catch (_) { return false; }
}

// amount: bilangan bulat rupiah > 0
function buildDynamicQris(staticPayload, amount) {
  const s = String(staticPayload || '').trim();
  if (!isValidQris(s)) throw new Error('QRIS statis tidak valid (CRC salah atau format bukan QRIS).');
  const amt = Number(amount);
  if (!Number.isInteger(amt) || amt <= 0 || amt > 999999999) throw new Error('Nominal QRIS tidak valid.');
  const tags = parseTlv(s.slice(0, s.length - 8)); // tanpa tag 63 (CRC)
  const out = [];
  let amountInserted = false;
  for (const t of tags) {
    if (t.tag === '54') continue;                         // buang nominal lama kalau ada
    if (t.tag === '01') { out.push({ tag: '01', value: '12' }); continue; } // 11 = statis, 12 = dinamis
    if (!amountInserted && Number(t.tag) > 54) { out.push({ tag: '54', value: String(amt) }); amountInserted = true; }
    out.push(t);
  }
  if (!amountInserted) out.push({ tag: '54', value: String(amt) });
  const body = out.map((t) => tlv(t.tag, t.value)).join('') + '6304';
  return body + crc16ccitt(body);
}

module.exports = { crc16ccitt, parseTlv, isValidQris, buildDynamicQris };
