const fs = require('fs');
const path = require('path');
const { supabase, tg, config } = require('../lib/config');
const { createQris, checkStatus, qrImageUrl, markOrderPaidByTransaction } = require('../lib/casaku');
const { providerIsListener, createListenerQris } = require('../lib/listener-pay');
const { fancy } = require('../lib/style');
const { postMedia, loadSettings, saveSettings, norm: normEmoji } = require('../lib/emoji');
const stock = require('../lib/stock');
const packages = require('../lib/packages');
const ownerManage = require('../lib/owner-manage');
const ops = require('../lib/ops');
const audit = require('../lib/audit');
const settings = require('../lib/settings');
const migrate = require('../lib/migrate');

const OWNER_IDS = new Set([...(config.ownerTelegramIds || []), config.ownerId].filter(Boolean).map(String));
const MENU = { inline_keyboard: [
  [{ text: '💎 ORDER VIP', callback_data: 'vip_order' }],
  [{ text: '📘 TUTORIAL PEMASANGAN', callback_data: 'tutorial_menu' }],
  [{ text: '💬 CHAT OWNER', url: `https://t.me/${config.ownerUsername || 'XIANMOD'}` }],
] };
const TUTORIAL_MENU = { inline_keyboard: [
  [{ text: '🎬 TUTORIAL ORDER VIP VIA BOT', url: config.tutorialOrderBotUrl }],
  [{ text: '🎬 TUTORIAL ORDER VIP VIA WEB', url: config.tutorialOrderWebUrl }],
  [{ text: '🎬 TUTORIAL PASANG LUA MOD', url: config.tutorialLuaModUrl }],
  [{ text: '⬅️ MENU UTAMA', callback_data: 'menu' }],
] };
const EMOJI_HELP = '✨ <b>PREMIUM EMOJI</b>\nKirim <code>/setemoji</code> diikuti emoji premium. Command: <code>/emojilist</code>, <code>/emojion</code>, <code>/emojioff</code>, <code>/emojireset</code>.';
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
const isOwner = (uid) => OWNER_IDS.has(String(uid));
const formatIDR = (n) => `Rp ${Number(n || 0).toLocaleString('id-ID')}`;
const formatWIB = (date = new Date()) => new Intl.DateTimeFormat('id-ID', { timeZone:'Asia/Jakarta', day:'2-digit', month:'2-digit', year:'numeric', hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false }).format(date).replace(/\./g, ':') + ' WIB';

async function setAction(uid, action) { const db=supabase(); if(db) await db.from('users').update({pending_action:action}).eq('telegram_id',String(uid)); }
async function getAction(uid) { const db=supabase(); if(!db)return null; const {data}=await db.from('users').select('pending_action').eq('telegram_id',String(uid)).maybeSingle(); return data?.pending_action || null; }
async function clearAction(uid) { return setAction(uid, null); }
async function upsertUser(u) {
  const db=supabase(); if(!db || !u?.id)return;
  await db.from('users').upsert({ telegram_id:String(u.id), username:u.username||null, first_name:u.first_name||null, last_active:new Date().toISOString() }, { onConflict:'telegram_id' });
}
async function isBlocked(uid) { const db=supabase(); if(!db)return false; const {data}=await db.from('users').select('is_blocked').eq('telegram_id',String(uid)).maybeSingle(); return !!data?.is_blocked; }

async function sendPhoto(chatId, caption, markup) {
  try {
    const p = path.join(__dirname, '..', 'public', 'vip-menu.png');
    const buf = fs.readFileSync(p);
    const r = await postMedia({ method:'sendPhoto', field:'photo', buf, mime:'image/png', filename:'vip-menu.png', chatId, caption, markup });
    if (r?.ok) return r;
  } catch (e) { console.error('[telegram photo]', e?.message || e); }
  return tg('sendMessage', { chat_id:chatId, text:caption, parse_mode:'HTML', reply_markup:markup });
}
function startCaption(user) {
  const name = user?.username ? `@${esc(user.username)}` : esc(user?.first_name || 'Pengguna');
  return fancy([
    '💎 <b>XIANMOD VIP STORE™</b>', '<i>Premium access • Secure order</i>', '',
    '<blockquote>', `👤 <b>User</b> : ${name}`, `🆔 <b>ID</b> : <code>${esc(user?.id || '-')}</code>`, `🕒 <b>Waktu</b> : ${formatWIB()}`, '</blockquote>', '',
    '<b>LAYANAN</b>', '<blockquote>💎 ORDER VIP\n📘 TUTORIAL PEMASANGAN\n💬 CHAT OWNER</blockquote>', '', '👇 <b>Silakan pilih menu</b>'
  ].join('\n'));
}
async function sendStart(chat, user) { return sendPhoto(chat, startCaption(user), MENU); }

async function handleEmojiCommand(msg) {
  const chat=msg.chat.id, text=String(msg.text||''), cmd=(text.trim().split(/\s+/)[0]||'').split('@')[0].toLowerCase();
  try {
    const st=await loadSettings(true); st.map=st.map||{};
    if(cmd==='/emojion'||cmd==='/emojioff') { st.enabled=cmd==='/emojion'; await saveSettings(st); return tg('sendMessage',{chat_id:chat,text:`✨ Premium emoji ${st.enabled?'AKTIF':'NONAKTIF'}.`}); }
    if(cmd==='/emojireset') { st.map={}; await saveSettings(st); return tg('sendMessage',{chat_id:chat,text:'🧹 Mapping premium emoji dihapus.'}); }
    if(cmd==='/emojilist') { const rows=Object.entries(st.map); return tg('sendMessage',{chat_id:chat,text:`✨ <b>PREMIUM EMOJI</b>\n${st.enabled?'AKTIF':'NONAKTIF'}\n${rows.length?rows.map(([k,v])=>`${k} → <code>${v}</code>`).join('\n'):'Belum ada mapping.'}`,parse_mode:'HTML'}); }
    if(cmd==='/setemoji') {
      const ents=(msg.entities||[]).filter(e=>e.type==='custom_emoji'&&e.custom_emoji_id);
      if(!ents.length)return tg('sendMessage',{chat_id:chat,text:EMOJI_HELP,parse_mode:'HTML'});
      const saved=[];
      for(const e of ents){const base=normEmoji(text.substr(e.offset,e.length));if(base){st.map[base]=e.custom_emoji_id;saved.push(base);}}
      await saveSettings(st);
      return tg('sendMessage',{chat_id:chat,text:`✅ ${saved.length} premium emoji tersimpan.`,parse_mode:'HTML'});
    }
  } catch(e) { return tg('sendMessage',{chat_id:chat,text:`❌ Gagal mengatur emoji: ${esc(e.message)}`,parse_mode:'HTML'}); }
}

async function ownerPanel(chat) {
  const db=supabase(); if(!db)return tg('sendMessage',{chat_id:chat,text:'⚠️ Supabase belum diatur.'});
  const [{count:users},{count:orders},{count:paid},{count:pending}] = await Promise.all([
    db.from('users').select('*',{count:'exact',head:true}),
    db.from('orders').select('*',{count:'exact',head:true}),
    db.from('orders').select('*',{count:'exact',head:true}).eq('status','PAID'),
    db.from('orders').select('*',{count:'exact',head:true}).eq('status','PENDING'),
  ]);
  const text=`👑 <b>XIANMOD OWNER PANEL</b>\n━━━━━━━━━━━━━━━━━━\n👥 Pengguna: ${users||0}\n🧾 Total order: ${orders||0}\n✅ Lunas: ${paid||0}\n⏳ Pending: ${pending||0}\n\n<i>Kelola toko dan operasional bot.</i>`;
  return sendPhoto(chat,fancy(text),{inline_keyboard:[
    [{text:'📢 Broadcast Semua User',callback_data:'bc_all'}],
    [{text:'💎 Paket & Harga VIP',callback_data:'vpk:list'}],
    [{text:'🏷️ Promo & Slot',callback_data:'owner_stock'}],
    [{text:'🧾 Manajemen Transaksi',callback_data:'owner_orders'}],
    [{text:'🔎 Cari Buyer / Order',callback_data:'ops:find'}],
    [{text:'📊 Statistik',callback_data:'ops:stats'},{text:'💾 Backup',callback_data:'ops:backup'}],
    [{text:'✨ Premium Emoji',callback_data:'owner_emoji'}],
    [{text:'🛡️ Riwayat Aktivitas',callback_data:'owner_audit'}],
    [{text:'⚙️ Pengaturan Bot',callback_data:'owner_settings'}],
    [{text:'🚚 Migrasi dari Supabase Lama',callback_data:'mig'}],
    [{text:'🔙 Menu Utama',callback_data:'menu'}],
  ]});
}

const isStockManaged = (code) => !!stock.itemByCode(String(code||'').toUpperCase());
async function packagePrice(pkg) {
  await stock.loadItems();
  if (isStockManaged(pkg.code)) return stock.getPrice(pkg.code,{idr:pkg.idr,usdt:0});
  const promos=await stock.getPromoMap().catch(()=>({}));
  if (packages.hasPromo(promos,pkg.code)) return stock.getPrice(pkg.code,{idr:pkg.idr,usdt:0});
  return {available:true,idr:Number(pkg.idr),usdt:0};
}
async function vipMenu(chat) {
  const pkgs=await packages.list();
  const promos=await stock.getPromoMap().catch(()=>({}));
  const rows=[];
  for(const p of pkgs){
    let price;
    if(isStockManaged(p.code) || packages.hasPromo(promos,p.code)){
      const pr=await packagePrice(p); if(!pr.available)continue; price=stock.priceLabel(pr);
    } else price=formatIDR(p.idr);
    rows.push([{text:`${packages.iconFor(p.code)} ${packages.label(p)} • ${price}`,callback_data:`vip_buy:${p.code}`}]);
  }
  rows.push([{text:'🧾 RIWAYAT ORDER SAYA',callback_data:'vip_orders'}],[{text:'⬅️ MENU UTAMA',callback_data:'menu'}]);
  return sendPhoto(chat,fancy('💎 <b>ORDER VIP</b>\n━━━━━━━━━━━━━━━━━━\nPilih paket akses grup VIP. Setelah pembayaran berhasil, bot akan mengirim link undangan khusus 1 kali masuk.\n\n💳 Pembayaran otomatis melalui <b>QRIS</b>.'),{inline_keyboard:rows});
}
async function createOrder(uid, username, pkg) {
  const db=supabase(); if(!db)throw new Error('Supabase belum diatur.');
  const pr=await packagePrice(pkg); if(!pr.available)throw new Error('Paket sedang tidak tersedia.');
  const since=new Date(Date.now()-10*60*1000).toISOString();
  const {data:recent}=await db.from('orders').select('*').eq('telegram_id',String(uid)).eq('package_code',pkg.code).eq('status','PENDING').gte('created_at',since).order('created_at',{ascending:false}).limit(1).maybeSingle();
  if(recent)return recent;
  const orderId=`XIAN-${Date.now().toString(36).toUpperCase()}-${Math.floor(Math.random()*100000).toString().padStart(5,'0')}`;
  const {data,error}=await db.from('orders').insert({order_id:orderId,telegram_id:String(uid),username:username||null,package_code:pkg.code,package_name:pkg.name,price_idr:pr.idr,price_usdt:0,payment_method:'QRIS',status:'PENDING'}).select('*').single();
  if(error)throw error; return data;
}
async function sendQris(chat, order) {
  const total=Number(order.total_amount_idr||order.price_idr);
  const validMinutes=order.qr_expires_at?Math.max(1,Math.round((new Date(order.qr_expires_at).getTime()-Date.now())/60000)):config.casakuExpiredMinutes;
  const text=fancy(`📲 <b>PEMBAYARAN QRIS</b>\n━━━━━━━━━━━━━━━━━━\n🧾 <b>Order</b> : <code>${esc(order.order_id)}</code>\n📦 <b>Paket</b> : ${esc(order.package_name)}\n💰 <b>Total</b> : ${formatIDR(total)}\n⏳ <b>Berlaku</b> : ${validMinutes} menit\n\nScan QRIS di bawah. ${providerIsListener()?'<b>Bayar TEPAT sesuai total di atas</b> (termasuk 3 digit terakhir) agar terdeteksi otomatis. ':''}Status pembayaran akan diperbarui otomatis.`);
  if(order.qr_string) await tg('sendPhoto',{chat_id:chat,photo:qrImageUrl(order.qr_string),caption:text,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'🔄 CEK STATUS',callback_data:`vip_check:${order.order_id}`}],[{text:'⬅️ MENU',callback_data:'menu'}]]}});
  else await tg('sendMessage',{chat_id:chat,text,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'🔄 CEK STATUS',callback_data:`vip_check:${order.order_id}`}],[{text:'⬅️ MENU',callback_data:'menu'}]]}});
}
async function createOrReuseQris(chat, uid, orderId) {
  const db=supabase();
  const {data:order,error}=await db.from('orders').select('*').eq('order_id',orderId).eq('telegram_id',String(uid)).maybeSingle();
  if(error||!order)return tg('sendMessage',{chat_id:chat,text:'⚠️ Order tidak ditemukan.'});
  if(order.status==='PAID')return tg('sendMessage',{chat_id:chat,text:'✅ Order ini sudah LUNAS. Link grup VIP sudah dikirim melalui bot.'});
  if(order.status!=='PENDING')return tg('sendMessage',{chat_id:chat,text:`⚠️ Order berstatus ${esc(order.status)}. Silakan buat order baru.`});
  const listenerMode=providerIsListener();
  if((listenerMode?!order.casaku_transaction_id:!!order.casaku_transaction_id) && order.qr_expires_at && new Date(order.qr_expires_at)>new Date() && order.qr_string){ return sendQris(chat,order); }
  const pkg=await packages.get(order.package_code);
  if(pkg){ const pr=await packagePrice(pkg); if(!pr.available){await db.from('orders').update({status:'CANCELLED'}).eq('order_id',orderId).eq('status','PENDING');return tg('sendMessage',{chat_id:chat,text:'⛔ Paket sedang tidak tersedia. Silakan pilih paket lain.'});} if(Number(order.price_idr)!==Number(pr.idr)){await db.from('orders').update({price_idr:pr.idr,price_usdt:0}).eq('order_id',orderId).eq('status','PENDING');order.price_idr=pr.idr;order.price_usdt=0;} }
  if(listenerMode){
    try {
      const q=await createListenerQris(order);
      const {data:updated,error:updateError}=await db.from('orders').update({payment_method:'QRIS',casaku_transaction_id:null,qr_string:q.qrString,total_amount_idr:q.totalAmount,qr_expires_at:q.expiresAt}).eq('order_id',orderId).eq('status','PENDING').select('*').single();
      if(updateError)throw updateError;
      return sendQris(chat,updated);
    } catch(e) { console.error('[listener qris]',e.message||e); return tg('sendMessage',{chat_id:chat,text:`❌ Gagal membuat QRIS.\n${esc(e.message||e)}`}); }
  }
  try {
    const q=await createQris(order,{prefix:'XIAN'});
    const expires=new Date(Date.now()+config.casakuExpiredMinutes*60000).toISOString();
    const {data:updated,error:updateError}=await db.from('orders').update({payment_method:'QRIS',casaku_transaction_id:q.transactionId,qr_string:q.qrString,total_amount_idr:q.totalAmount,qr_expires_at:expires}).eq('order_id',orderId).eq('status','PENDING').select('*').single();
    if(updateError)throw updateError;
    return sendQris(chat,updated);
  } catch(e) { return tg('sendMessage',{chat_id:chat,text:`❌ Gagal membuat QRIS.\n${esc(e.message||e)}`}); }
}
async function buyerOrders(chat, uid) {
  const db=supabase(); const {data=[]}=await db.from('orders').select('order_id,package_name,status,payment_method,created_at').eq('telegram_id',String(uid)).order('created_at',{ascending:false}).limit(10);
  const body=data.length?data.map(o=>`<code>${esc(o.order_id)}</code> • <b>${esc(o.package_name)}</b>\n${esc(o.status)} • ${formatWIB(new Date(o.created_at))}`).join('\n\n'):'Belum ada riwayat pembelian.';
  return tg('sendMessage',{chat_id:chat,text:fancy(`🧾 <b>RIWAYAT ORDER</b>\n━━━━━━━━━━━━━━━━━━\n${body}`),parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'💎 ORDER VIP',callback_data:'vip_order'}],[{text:'⬅️ MENU',callback_data:'menu'}]]}});
}
async function broadcastAll(msg) {
  const chat=msg.chat.id, uid=msg.from.id, isText=!!msg.text;
  if(isText && /^\/batal$/i.test(msg.text.trim())){await clearAction(uid);return tg('sendMessage',{chat_id:chat,text:'❎ Broadcast dibatalkan.'});}
  const db=supabase(); const {data:targets=[],error}=await db.from('users').select('telegram_id').order('last_active',{ascending:false});
  if(error)return tg('sendMessage',{chat_id:chat,text:`❌ Gagal mengambil daftar user: ${esc(error.message)}`});
  await clearAction(uid);
  await audit.log('broadcast_started',uid,{audience:'all',count:targets.length});
  await tg('sendMessage',{chat_id:chat,text:`📤 Broadcast dimulai ke ${targets.length} user (VIP + FREE lama).`});
  let sent=0,failed=0,done=0; const started=Date.now();
  for(let i=0;i<targets.length;i+=12){
    if(Date.now()-started>45000)break;
    const batch=targets.slice(i,i+12);
    const results=await Promise.allSettled(batch.map(async t=>{
      if(isText){let r=await tg('sendMessage',{chat_id:t.telegram_id,text:msg.text,parse_mode:'HTML'});if(!r?.ok&&/parse entities|can't parse/i.test(String(r?.description||'')))r=await tg('sendMessage',{chat_id:t.telegram_id,text:msg.text});return !!r?.ok;}
      const r=await tg('copyMessage',{chat_id:t.telegram_id,from_chat_id:chat,message_id:msg.message_id});return !!r?.ok;
    }));
    for(const r of results){if(r.status==='fulfilled'&&r.value)sent++;else failed++;}
    done=Math.min(i+batch.length,targets.length);
    await new Promise(r=>setTimeout(r,1000));
  }
  const remaining=targets.length-done;
  await db.from('broadcast_logs').insert({audience:'all',message:isText?msg.text:`[MEDIA] ${msg.caption||''}`,total:targets.length,sent,failed});
  await tg('sendMessage',{chat_id:chat,text:`✅ <b>HASIL BROADCAST</b>\nTarget: ${targets.length}\nTerkirim: ${sent}\nGagal: ${failed}\nBelum diproses: ${remaining}${remaining?' (ulang broadcast untuk mengirim ke semua user)':''}`,parse_mode:'HTML'});
}

async function ownerAudit(chat) {
  const db=supabase(); const {data=[],error}=await db.from('xian_audit_log').select('action,actor_telegram_id,details,created_at').order('created_at',{ascending:false}).limit(15);
  if(error)return tg('sendMessage',{chat_id:chat,text:'⚠️ Jalankan supabase/migration_audit_log.sql terlebih dahulu.'});
  const rows=data.map(x=>`• <b>${esc(x.action)}</b>\n${formatWIB(new Date(x.created_at))}\n<code>${esc(x.actor_telegram_id||'system')}</code> — ${esc(x.details?.order_id||x.details?.package_code||'')}`);
  return tg('sendMessage',{chat_id:chat,text:`🛡️ <b>RIWAYAT AKTIVITAS</b>\n━━━━━━━━━━━━━━━━━━\n${rows.length?rows.join('\n\n'):'Belum ada aktivitas.'}`,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'🔄 REFRESH',callback_data:'owner_audit'}],[{text:'🔙 OWNER PANEL',callback_data:'owner'}]]}});
}
async function handleVpkCallback(data, chat, uid) {
  const [,act,code]=data.split(':');
  try {
    if(act==='list'){
      const list=await packages.list({includeInactive:true});
      const rows=list.map(p=>[{text:`${p.active?'🟢':'⚫'} ${packages.label(p)} • ${formatIDR(p.idr)}`,callback_data:`vpk:edit:${p.code}`}]);
      rows.push([{text:'➕ Tambah Paket',callback_data:'vpk:add'}],[{text:'🔙 Owner Panel',callback_data:'owner'}]);
      return tg('sendMessage',{chat_id:chat,text:'💎 <b>PAKET VIP</b>\nHarga dan label paket bisa diubah kapan saja.',parse_mode:'HTML',reply_markup:{inline_keyboard:rows}});
    }
    if(act==='add'){await setAction(uid,'vpk_add');return tg('sendMessage',{chat_id:chat,text:'Kirim format: <code>NAMA | LABEL PAKET | HARGA</code>\nContoh: <code>VIP 30 HARI | 30 HARI | 30000</code>\nLabel hanya nama produk, bukan masa aktif. /batal untuk membatalkan.',parse_mode:'HTML'});}
    const p=await packages.get(code); if(!p)return tg('sendMessage',{chat_id:chat,text:'Paket tidak ditemukan.'});
    if(act==='edit')return tg('sendMessage',{chat_id:chat,text:`✏️ <b>${esc(p.name)}</b>\nHarga: <b>${formatIDR(p.idr)}</b>\nStatus: ${p.active?'TAMPIL':'DISEMBUNYIKAN'}`,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'💰 Ubah Harga',callback_data:`vpk:price:${code}`}],[{text:p.active?'🙈 Sembunyikan':'👁️ Tampilkan',callback_data:`vpk:toggle:${code}`}],[{text:'🗑️ Hapus Paket',callback_data:`vpk:delete:${code}`}],[{text:'⬅️ Daftar Paket',callback_data:'vpk:list'}]]}});
    if(act==='price'){await setAction(uid,`vpk_price:${code}`);return tg('sendMessage',{chat_id:chat,text:`Kirim harga baru untuk ${esc(p.name)} dalam Rupiah. Contoh <code>30000</code>. /batal untuk membatalkan.`,parse_mode:'HTML'});}
    if(act==='toggle'){await packages.save({...p,active:!p.active});await audit.log('package_visibility_updated',uid,{package_code:code,active:!p.active});return handleVpkCallback('vpk:edit:'+code,chat,uid);}
    if(act==='delete'){await packages.remove(code);try{await stock.clearLimit(code);}catch(_){}await audit.log('package_deleted',uid,{package_code:code});return handleVpkCallback('vpk:list',chat,uid);}
  } catch(e){return tg('sendMessage',{chat_id:chat,text:`❌ ${esc(e.message||e)}`});}
}
async function handleVpkText(msg) {
  const uid=msg.from.id,chat=msg.chat.id,action=await getAction(uid); if(!action||!action.startsWith('vpk_'))return false;
  const text=String(msg.text||'').trim(); if(/^\/batal$/i.test(text)){await clearAction(uid);await tg('sendMessage',{chat_id:chat,text:'❎ Dibatalkan.'});return true;}
  const [kind,code]=action.split(':');
  if(kind==='vpk_price'){
    const price=packages.parsePrice(text); if(!price)return tg('sendMessage',{chat_id:chat,text:'Harga tidak valid. Minimal Rp1.000.'});
    const p=await packages.get(code); if(!p)return clearAction(uid);
    await packages.save({...p,idr:price.idr,usdt:0});
    await audit.log('package_price_updated',uid,{package_code:code,price_idr:price.idr}); await clearAction(uid); await tg('sendMessage',{chat_id:chat,text:`✅ Harga ${esc(p.name)} diubah menjadi ${formatIDR(price.idr)}.`});return true;
  }
  if(kind==='vpk_add'){
    const f=text.split('|').map(x=>x.trim()); if(f.length!==3)return tg('sendMessage',{chat_id:chat,text:'Format: <code>NAMA | LABEL PAKET | HARGA</code>',parse_mode:'HTML'});
    const name=packages.parsePackageName(f[0]), label=packages.parseDuration(f[1]), price=packages.parsePrice(f[2]);
    if(!name||!label||!price)return tg('sendMessage',{chat_id:chat,text:'Nama, label paket, atau harga tidak valid.'});
    const code=packages.codeFromName(name); if(await packages.get(code))return tg('sendMessage',{chat_id:chat,text:'Kode paket sudah digunakan.'});
    await packages.save({code,name:name.startsWith('VIP ')?name:`VIP ${name}`,days:label.days,idr:price.idr,usdt:0,active:true});
    await audit.log('package_created',uid,{package_code:code,price_idr:price.idr}); await clearAction(uid); await tg('sendMessage',{chat_id:chat,text:`✅ Paket ${esc(name)} ditambahkan dengan harga ${formatIDR(price.idr)}.`});return true;
  }
  return false;
}

async function handleUpdate(req,res) {
  if(req.method!=='POST')return res.status(200).send('OK');
  if(process.env.TELEGRAM_WEBHOOK_SECRET && req.headers['x-telegram-bot-api-secret-token']!==process.env.TELEGRAM_WEBHOOK_SECRET)return res.status(403).send('forbidden');
  if(!config.botToken)return res.status(500).send('BOT_TOKEN belum diatur');
  const mcm=req.body?.my_chat_member;
  if(mcm){
    const st=mcm.new_chat_member?.status;
    if(['member','administrator'].includes(st)&&mcm.chat?.type!=='private'&&config.autoLeaveUnknownGroups){
      const allowed=new Set([await settings.getVipGroupId(),config.publicGroupId,...config.allowedGroupIds].filter(Boolean).map(String));
      if(allowed.size&&!allowed.has(String(mcm.chat.id))){await tg('leaveChat',{chat_id:mcm.chat.id});for(const id of OWNER_IDS)await tg('sendMessage',{chat_id:id,text:`🛡️ Bot keluar dari grup asing: ${esc(mcm.chat.title||'-')} (<code>${mcm.chat.id}</code>).`,parse_mode:'HTML'});}
    }
    return res.status(200).json({ok:true});
  }
  if(req.body?.chat_member)return res.status(200).json({ok:true});
  const msg=req.body?.message, cb=req.body?.callback_query;
  if(msg&&msg.chat?.type!=='private')return res.status(200).json({ok:true});
  if(cb&&cb.message?.chat?.type!=='private'){await tg('answerCallbackQuery',{callback_query_id:cb.id,text:'Buka bot lewat chat pribadi ya.',show_alert:true});return res.status(200).json({ok:true});}
  const user=msg?.from||cb?.from; if(user){await upsertUser(user);if(await isBlocked(user.id))return res.status(200).json({ok:true});}
  if(msg?.text && isOwner(msg.from.id) && /^\/(setemoji|emojilist|emojireset|emojion|emojioff)(@\w+)?(\s|$)/i.test(msg.text)){await handleEmojiCommand(msg);return res.status(200).json({ok:true});}
  if(msg?.text==='/start'){await clearAction(msg.from.id);await sendStart(msg.chat.id,msg.from);return res.status(200).json({ok:true});}
  if(msg?.text==='/owner'&&isOwner(msg.from.id)){await ownerPanel(msg.chat.id);return res.status(200).json({ok:true});}
  if(msg?.text&&isOwner(msg.from.id)&&await getAction(msg.from.id)==='set_vip_group'){
    const value=msg.text.trim();
    if(/^\/batal$/i.test(value)){await clearAction(msg.from.id);await tg('sendMessage',{chat_id:msg.chat.id,text:'❎ Pengaturan dibatalkan.'});return res.status(200).json({ok:true});}
    if(!/^-100\d{8,13}$/.test(value)){await tg('sendMessage',{chat_id:msg.chat.id,text:'ID grup tidak valid. Contoh: <code>-1001234567890</code>.',parse_mode:'HTML'});return res.status(200).json({ok:true});}
    await settings.setSetting('vip_group_id',value);await audit.log('vip_group_updated',msg.from.id,{vip_group_id:value});await clearAction(msg.from.id);await tg('sendMessage',{chat_id:msg.chat.id,text:`✅ ID grup VIP diperbarui: <code>${esc(value)}</code>`,parse_mode:'HTML'});return res.status(200).json({ok:true});
  }
  if(msg?.text&&isOwner(msg.from.id)&&await ownerManage.handleText(msg))return res.status(200).json({ok:true});
  if(msg?.text&&isOwner(msg.from.id)&&await handleVpkText(msg))return res.status(200).json({ok:true});
  if(msg?.text&&isOwner(msg.from.id)&&await ops.handleText(msg,{getAction,setAction,clearAction}))return res.status(200).json({ok:true});
  if(msg?.text&&isOwner(msg.from.id)&&await getAction(msg.from.id)==='broadcast_all'){
    if(/^\/batal$/i.test(msg.text.trim())){await clearAction(msg.from.id);await tg('sendMessage',{chat_id:msg.chat.id,text:'❎ Broadcast dibatalkan.'});return res.status(200).json({ok:true});}
    await broadcastAll(msg);return res.status(200).json({ok:true});
  }
  if(msg&&!msg.text&&isOwner(msg.from.id)&&await getAction(msg.from.id)==='broadcast_all'){await broadcastAll(msg);return res.status(200).json({ok:true});}
  if(cb){
    await tg('answerCallbackQuery',{callback_query_id:cb.id}); const chat=cb.message.chat.id,uid=cb.from.id;
    if(await ownerManage.handleCallback(cb,isOwner(uid)))return res.status(200).json({ok:true});
    if(cb.data==='mig'||cb.data==='mig_go'){if(isOwner(uid))await migrate.handleCallback(cb.data,chat,cb,uid);return res.status(200).json({ok:true});}
    if(cb.data.startsWith('ops:')){if(isOwner(uid))await ops.handleCallback(cb.data,chat,uid,{getAction,setAction,clearAction});return res.status(200).json({ok:true});}
    if(cb.data.startsWith('vpk:')){if(isOwner(uid))await handleVpkCallback(cb.data,chat,uid);return res.status(200).json({ok:true});}
    if(cb.data==='vip_order'){await vipMenu(chat);return res.status(200).json({ok:true});}
    if(cb.data==='vip_orders'){await buyerOrders(chat,uid);return res.status(200).json({ok:true});}
    if(cb.data==='tutorial_menu'){await sendPhoto(chat,fancy('📘 <b>TUTORIAL PEMASANGAN</b>\n━━━━━━━━━━━━━━━━━━\nPilih panduan yang ingin kamu buka.'),TUTORIAL_MENU);return res.status(200).json({ok:true});}
    if(cb.data==='menu'){await clearAction(uid);await sendStart(chat,cb.from);return res.status(200).json({ok:true});}
    if(cb.data==='owner'&&isOwner(uid)){await ownerPanel(chat);return res.status(200).json({ok:true});}
    if(cb.data==='owner_audit'&&isOwner(uid)){await ownerAudit(chat);return res.status(200).json({ok:true});}
    if(cb.data==='owner_settings'&&isOwner(uid)){const groupId=await settings.getVipGroupId();await tg('sendMessage',{chat_id:chat,text:`⚙️ <b>PENGATURAN BOT</b>\n━━━━━━━━━━━━━━━━━━\nGrup VIP: <code>${esc(groupId||'BELUM DIATUR')}</code>\nOwner: @${esc(config.ownerUsername)}\nPembayaran: <b>Casaku QRIS otomatis</b>\n\nTutorial dan kontak owner tetap menggunakan konfigurasi lama. ID grup VIP dapat diubah melalui tombol di bawah.`,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'✏️ UBAH ID GRUP VIP',callback_data:'owner_group'}],[{text:'🔙 OWNER PANEL',callback_data:'owner'}]]}});return res.status(200).json({ok:true});}
    if(cb.data==='owner_group'&&isOwner(uid)){await setAction(uid,'set_vip_group');return tg('sendMessage',{chat_id:chat,text:'Kirim ID grup VIP baru (contoh: <code>-1001234567890</code>). Bot harus menjadi admin grup dengan izin mengundang anggota. /batal untuk membatalkan.',parse_mode:'HTML'});}
    if(cb.data==='owner_emoji'&&isOwner(uid)){const st=await loadSettings(true);return tg('sendMessage',{chat_id:chat,text:`${EMOJI_HELP}\n\nStatus: <b>${st.enabled?'AKTIF':'MATI'}</b> • Mapping: ${Object.keys(st.map||{}).length}`,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'🔙 OWNER PANEL',callback_data:'owner'}]]}});}
    if(cb.data==='bc_all'&&isOwner(uid)){await setAction(uid,'broadcast_all');return tg('sendMessage',{chat_id:chat,text:'📢 <b>BROADCAST SEMUA USER</b>\nKirim teks, foto, video, GIF, atau dokumen yang ingin disebarkan ke semua user bot (VIP + FREE lama).\nKetik /batal untuk membatalkan.',parse_mode:'HTML'});}
    if(cb.data.startsWith('vip_buy:')){
      const pkg=await packages.get(cb.data.slice(8)); if(!pkg||!pkg.active)return tg('sendMessage',{chat_id:chat,text:'Paket tidak tersedia.'});
      try { const pr=await packagePrice(pkg); if(!pr.available)return tg('sendMessage',{chat_id:chat,text:'Paket sedang habis.'}); const order=await createOrder(uid,cb.from.username,pkg); await createOrReuseQris(chat,uid,order.order_id); }
      catch(e){await tg('sendMessage',{chat_id:chat,text:`❌ ${esc(e.message||e)}`});}
      return res.status(200).json({ok:true});
    }
    if(cb.data.startsWith('vip_qris:')){await createOrReuseQris(chat,uid,cb.data.slice(9));return res.status(200).json({ok:true});}
    if(cb.data.startsWith('vip_check:')){
      const id=cb.data.slice(10),db=supabase(); const {data:o}=await db.from('orders').select('order_id,status,casaku_transaction_id').eq('order_id',id).eq('telegram_id',String(uid)).maybeSingle();
      if(!o)return tg('sendMessage',{chat_id:chat,text:'Order tidak ditemukan.'});
      if(o.status==='PENDING'&&o.casaku_transaction_id){try{const st=await checkStatus(o.casaku_transaction_id);if(String(st).toLowerCase()==='paid')await markOrderPaidByTransaction(o.casaku_transaction_id,{paymentRef:o.casaku_transaction_id});}catch(e){console.error('[check payment]',e.message);}}
      const {data:fresh}=await db.from('orders').select('status').eq('order_id',id).maybeSingle();
      return tg('sendMessage',{chat_id:chat,text:`🔎 <b>STATUS ORDER</b>\nOrder: <code>${esc(id)}</code>\nStatus: <b>${esc(fresh?.status||o.status)}</b>`,parse_mode:'HTML',reply_markup:{inline_keyboard:[[{text:'🔄 CEK LAGI',callback_data:`vip_check:${id}`}],[{text:'💎 ORDER VIP',callback_data:'vip_order'}]]}});
    }
  }
  return res.status(200).json({ok:true});
}

module.exports = async function handler(req,res){
  try{return await handleUpdate(req,res);}catch(e){console.error('[telegram webhook]',e);return res.status(200).json({ok:true});}
};
