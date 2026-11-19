-- XIANMOD PAYMENT listener: tabel TAMBAHAN. Tidak mengubah/menghapus tabel lama (orders, users, dst).
-- Aman dijalankan ulang. Jalankan SEKALI di Supabase SQL Editor (satu Run untuk seluruh isi file).

-- 1) Perangkat yang sudah dipasangkan. Token asli TIDAK disimpan, hanya hash SHA-256.
create table if not exists public.xian_pay_devices (
  id            uuid primary key default gen_random_uuid(),
  name          text not null default 'XIANMOD PAYMENT',
  token_hash    text not null unique,
  created_at    timestamptz not null default now(),
  last_seen_at  timestamptz,
  revoked_at    timestamptz,
  app_version   text,
  listener_connected boolean,
  queue_depth   integer,
  meta          jsonb not null default '{}'::jsonb
);

-- 2) Kode pairing sekali pakai (hash saja, berlaku singkat).
create table if not exists public.xian_pay_pairing_codes (
  code_hash   text primary key,
  expires_at  timestamptz not null,
  used_at     timestamptz,
  device_id   uuid references public.xian_pay_devices(id) on delete set null,
  created_at  timestamptz not null default now()
);

-- 3) Reservasi nominal unik. amount = PRIMARY KEY => dua order aktif tidak mungkin punya nominal sama.
create table if not exists public.xian_pay_amount_locks (
  amount      numeric primary key,
  order_id    text not null,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null
);
create index if not exists xian_pay_amount_locks_order_idx on public.xian_pay_amount_locks (order_id);

-- 4) Event notifikasi dari APK. Isi notifikasi mentah TIDAK disimpan.
create table if not exists public.xian_pay_events (
  id           uuid primary key default gen_random_uuid(),
  device_id    uuid not null references public.xian_pay_devices(id) on delete cascade,
  event_id     text not null,                 -- id unik dari APK (idempotency)
  fingerprint  text not null,                 -- hash(package|amount|referensi atau menit)
  package_name text,
  amount       numeric,
  reference    text,
  posted_at    timestamptz,                   -- waktu notifikasi menurut HP
  received_at  timestamptz not null default now(),
  parsed       boolean not null default false,
  status       text not null,                 -- RECEIVED | MATCHED | PAID | NEEDS_REVIEW | DUPLICATE | REJECTED
  reason       text,
  order_id     text,
  meta         jsonb not null default '{}'::jsonb,
  unique (device_id, event_id)
);
create index if not exists xian_pay_events_status_idx on public.xian_pay_events (status, received_at desc);
create index if not exists xian_pay_events_fp_idx on public.xian_pay_events (fingerprint);
-- Satu fingerprint hanya boleh jadi event "asli" satu kali (selain yang ditandai DUPLICATE/REJECTED).
create unique index if not exists xian_pay_events_fp_unique
  on public.xian_pay_events (fingerprint) where status not in ('DUPLICATE','REJECTED');

-- 5) Rate limit sederhana (jendela tetap) yang atomik.
create table if not exists public.xian_pay_rate (
  key          text primary key,
  window_start timestamptz not null,
  hits         integer not null
);

-- RLS aktif tanpa policy: hanya service role (backend) yang bisa akses.
alter table public.xian_pay_devices        enable row level security;
alter table public.xian_pay_pairing_codes  enable row level security;
alter table public.xian_pay_amount_locks   enable row level security;
alter table public.xian_pay_events         enable row level security;
alter table public.xian_pay_rate           enable row level security;

-- Reservasi nominal unik secara atomik. Mengembalikan nominal total (base + kode unik) atau NULL jika penuh.
-- Kunci kedaluwarsa baru boleh dipakai ulang setelah masa tenggang (p_grace_minutes) supaya pembayaran
-- yang telat sedikit tidak tertukar dengan order baru.
create or replace function public.xian_pay_reserve_amount(
  p_order_id text, p_base numeric, p_expires timestamptz,
  p_max integer default 200, p_grace_minutes integer default 30
) returns numeric
language plpgsql security definer set search_path = public as $$
declare c integer; amt numeric; n integer;
begin
  delete from public.xian_pay_amount_locks where order_id = p_order_id;
  for c in select g from generate_series(1, greatest(p_max, 1)) g order by random() loop
    amt := p_base + c;
    insert into public.xian_pay_amount_locks (amount, order_id, expires_at)
    values (amt, p_order_id, p_expires)
    on conflict (amount) do update
      set order_id = excluded.order_id, created_at = now(), expires_at = excluded.expires_at
      where public.xian_pay_amount_locks.expires_at < now() - make_interval(mins => p_grace_minutes);
    get diagnostics n = row_count;
    if n > 0 then return amt; end if;
  end loop;
  return null;
end $$;

-- Hitung hit dan putuskan boleh/tidak. true = masih dalam batas.
create or replace function public.xian_pay_rate_hit(p_key text, p_window_seconds integer, p_limit integer)
returns boolean
language plpgsql security definer set search_path = public as $$
declare h integer;
begin
  insert into public.xian_pay_rate (key, window_start, hits) values (p_key, now(), 1)
  on conflict (key) do update
    set hits = case when public.xian_pay_rate.window_start < now() - make_interval(secs => p_window_seconds) then 1 else public.xian_pay_rate.hits + 1 end,
        window_start = case when public.xian_pay_rate.window_start < now() - make_interval(secs => p_window_seconds) then now() else public.xian_pay_rate.window_start end
  returning hits into h;
  return h <= p_limit;
end $$;

revoke all on function public.xian_pay_reserve_amount(text, numeric, timestamptz, integer, integer) from public, anon, authenticated;
revoke all on function public.xian_pay_rate_hit(text, integer, integer) from public, anon, authenticated;
grant execute on function public.xian_pay_reserve_amount(text, numeric, timestamptz, integer, integer) to service_role;
grant execute on function public.xian_pay_rate_hit(text, integer, integer) to service_role;

-- 6) Ledger konsumsi stok dan fulfillment VIP. Menjaga histori order tetap utuh.
create table if not exists public.xian_pay_stock_ledger (
  order_id text primary key references public.orders(order_id) on delete restrict,
  package_code text,
  tracked boolean not null default false,
  remaining integer,
  oversold boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.xian_pay_fulfillments (
  order_id text primary key references public.orders(order_id) on delete restrict,
  status text not null default 'PENDING' check (status in ('PENDING','PROCESSING','COMPLETE','NEEDS_REVIEW')),
  invite_link text,
  last_error text,
  processing_started_at timestamptz,
  completed_at timestamptz,
  updated_at timestamptz not null default now()
);
create index if not exists xian_pay_fulfillments_status_idx on public.xian_pay_fulfillments(status, updated_at desc);

alter table public.xian_pay_stock_ledger enable row level security;
alter table public.xian_pay_fulfillments enable row level security;

-- 7) Pairing atomik: kode tidak hangus bila insert perangkat gagal.
create or replace function public.xian_pay_pair_device(
  p_code_hash text, p_token_hash text, p_name text,
  p_app_version text default null, p_model text default null
) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_code text; v_id uuid;
begin
  update public.xian_pay_pairing_codes
     set used_at = now()
   where code_hash = p_code_hash and used_at is null and expires_at > now()
   returning code_hash into v_code;
  if v_code is null then return null; end if;
  insert into public.xian_pay_devices(name, token_hash, app_version, meta)
  values (left(coalesce(nullif(p_name,''),'XIANMOD PAYMENT'),60), p_token_hash,
          nullif(left(coalesce(p_app_version,''),32),''),
          jsonb_build_object('model', left(coalesce(p_model,''),60)))
  returning id into v_id;
  update public.xian_pay_pairing_codes set device_id = v_id where code_hash = v_code;
  return v_id;
end $$;

-- 8) Satu transaksi DB untuk tandai PAID + konsumsi stok + buat antrean fulfillment.
-- Memanggil stock_consume() yang sudah ada tanpa mengubah kontrak/fungsinya.
create or replace function public.xian_pay_approve_order(
  p_order_id text, p_payment_ref text, p_event_id text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_order public.orders%rowtype;
  v_stock record;
  v_has_ledger boolean;
begin
  select * into v_order from public.orders where order_id = p_order_id for update;
  if not found then return jsonb_build_object('ok', false, 'error', 'order_not_found'); end if;
  if v_order.status = 'PAID' then
    return jsonb_build_object('ok', true, 'already', true, 'order', to_jsonb(v_order));
  end if;
  if v_order.status <> 'PENDING' then
    return jsonb_build_object('ok', false, 'error', 'order_not_pending', 'status', v_order.status);
  end if;

  update public.orders
     set status = 'PAID', paid_at = now(), payment_ref = p_payment_ref,
         payment_method = 'GOPAY_QRIS'
   where order_id = p_order_id
   returning * into v_order;

  select exists(select 1 from public.xian_pay_stock_ledger where order_id = p_order_id) into v_has_ledger;
  if not v_has_ledger and v_order.package_code is not null then
    select * into v_stock from public.stock_consume(v_order.package_code);
    insert into public.xian_pay_stock_ledger(order_id, package_code, tracked, remaining, oversold)
    values (p_order_id, v_order.package_code, coalesce(v_stock.tracked,false), v_stock.remaining, coalesce(v_stock.oversold,false));
  end if;

  insert into public.xian_pay_fulfillments(order_id, status)
  values (p_order_id, 'PENDING') on conflict (order_id) do nothing;

  if p_event_id is not null then
    update public.xian_pay_events
       set status = 'PAID', reason = 'manual_approved', order_id = p_order_id
     where id = p_event_id and status = 'NEEDS_REVIEW';
  end if;

  return jsonb_build_object('ok', true, 'already', false, 'order', to_jsonb(v_order),
    'stock', case when v_order.package_code is null then null else to_jsonb(v_stock) end);
end $$;

revoke all on function public.xian_pay_pair_device(text,text,text,text,text) from public, anon, authenticated;
revoke all on function public.xian_pay_approve_order(text,text,text) from public, anon, authenticated;
grant execute on function public.xian_pay_pair_device(text,text,text,text,text) to service_role;
grant execute on function public.xian_pay_approve_order(text,text,text) to service_role;
