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
