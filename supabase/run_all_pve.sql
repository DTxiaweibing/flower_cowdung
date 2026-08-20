-- ============================================================
-- 鲜花与牛粪 - 人机游戏大厅（PvE）完整落地脚本（一键粘贴版）
-- 在 Supabase Dashboard > SQL Editor 执行（幂等，可重复运行）。
--
-- 内容 = pve_tables.sql（表/触发器/坐席/心跳/RLS/清理）
--      + fix_pve_game_sync.sql（game_state 列 + pve_report_state + 修复版坐席函数）
--      + fix_pve_forfeit.sql（pve_forfeit 判负离座）
-- 全部整合为一个自洽脚本，避免旧版函数残留。
--
-- 设计：
--   pve_tables      20 张预置桌：status=open(空)/seated(有人坐)/playing(对局中)
--   pve_watchers    观战关系表（一人限观一桌），触发器维护 watcher_count
--   RPC：pve_sit / pve_leave / pve_watch / pve_unwatch / pve_start / pve_end /
--        pve_heartbeat / pve_report_state / pve_forfeit
--   pg_cron：3 分钟无心跳释放座位 + 清理离线观战（兜底，客户端每 20s 心跳）
-- ============================================================

-- ============================================================
-- 1. 表结构
-- ============================================================
create table if not exists public.pve_tables (
  id              text primary key,          -- '1'..'20'
  num             int  not null unique,      -- 桌号（排序/显示用）
  status          text not null default 'open'
                    check (status in ('open', 'seated', 'playing')),
  player_id       uuid references public.profiles (id) on delete set null,
  watcher_count   int  not null default 0,
  game_state      jsonb not null default '{}'::jsonb,  -- 完整棋局（观战重放）
  last_active_at  timestamptz not null default now()
);

create index if not exists pve_tables_num_idx on public.pve_tables (num);

create table if not exists public.pve_watchers (
  table_id        text references public.pve_tables (id) on delete cascade,
  user_id         uuid references public.profiles (id) on delete cascade,
  joined_at       timestamptz not null default now(),
  last_active_at  timestamptz not null default now(),   -- 观战心跳（离线清理依据）
  primary key (table_id, user_id)
);

create index if not exists pve_watchers_user_idx on public.pve_watchers (user_id);

-- 一人限观一桌：同一用户最多一条观战记录
alter table public.pve_watchers drop constraint if exists pve_watchers_user_key;
alter table public.pve_watchers add constraint pve_watchers_user_key unique (user_id);

-- 兼容旧表：补齐 game_state 列（若此前跑过旧版 pve_tables.sql）
alter table public.pve_tables
  add column if not exists game_state jsonb not null default '{}'::jsonb;

-- 预置 20 桌（无此数据则永远没有桌子可显示）
insert into public.pve_tables (id, num, status)
select g::text, g, 'open'
from generate_series(1, 20) g
on conflict (id) do nothing;

-- ============================================================
-- 2. 观察者计数触发器（增删自动 +1 / -1，保持一致性）
-- ============================================================
create or replace function public.pve_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.pve_tables
  set watcher_count = watcher_count + 1
  where id = new.table_id;
  return new;
end;
$$;

create or replace function public.pve_watcher_dec()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.pve_tables
  set watcher_count = greatest(0, watcher_count - 1)
  where id = old.table_id;
  return old;
end;
$$;

drop trigger if exists pve_watchers_inc on public.pve_watchers;
create trigger pve_watchers_inc
  after insert on public.pve_watchers
  for each row execute procedure public.pve_watcher_inc();

drop trigger if exists pve_watchers_dec on public.pve_watchers;
create trigger pve_watchers_dec
  after delete on public.pve_watchers
  for each row execute procedure public.pve_watcher_dec();

-- ============================================================
-- 3. RPC：pve_sit（坐下玩游戏；每人限一桌，空桌才可坐；坐下即退旧观战）
--    修复：空桌的 player_id 本就是 NULL，用 exists 判表是否存在，
--          不再把"空桌"误判为"表不存在"；入座清空上一局残留 game_state。
-- ============================================================
create or replace function public.pve_sit(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  occupied uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.pve_tables where id = tid) then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  select player_id into occupied from public.pve_tables where id = tid;

  if occupied is not null and occupied <> uid then
    raise exception 'TABLE_OCCUPIED';
  end if;
  if occupied = uid then
    delete from public.pve_watchers where user_id = uid;
    return true; -- 已坐在这桌，幂等
  end if;

  select exists (select 1 from public.pve_tables
                 where player_id = uid and id <> tid)
  into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pve_tables
  set player_id = uid, status = 'seated', game_state = '{}'::jsonb, last_active_at = now()
  where id = tid;

  delete from public.pve_watchers where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 4. RPC：pve_leave（离开座位；同时退出全部观战，清空棋局）
-- ============================================================
create or replace function public.pve_leave(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set player_id = null, status = 'open', game_state = '{}'::jsonb, last_active_at = now()
  where id = tid and player_id = uid;

  delete from public.pve_watchers
  where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 5. RPC：pve_watch / pve_unwatch（坐下当观众，不限人数，一人限观一桌）
-- ============================================================
-- pve_watch：玩家不再允许观战（一人一位置）；换桌观战自动先退旧观战
create or replace function public.pve_watch(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.pve_tables where id = tid) then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  -- 已在任意桌作为玩家入座 -> 不能再观战（一人一位置）
  if exists (select 1 from public.pve_tables
             where player_id = uid) then
    raise exception 'ALREADY_PLAYER';
  end if;

  -- 换桌观战：先退旧观战，再坐新桌（唯一约束保证一人一个观众位）
  if exists (select 1 from public.pve_watchers where user_id = uid) then
    delete from public.pve_watchers where user_id = uid;
  end if;

  insert into public.pve_watchers (table_id, user_id)
  values (tid, uid)
  on conflict (table_id, user_id) do nothing;

  return true;
end;
$$;

create or replace function public.pve_unwatch(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  delete from public.pve_watchers
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 6. RPC：pve_start / pve_end（对局状态上报）
-- ============================================================
-- pve_start：开局（不清 game_state，避免与玩家开机整包上报竞争；
--            新玩家入座已由 pve_sit 清空上一局残留）
create or replace function public.pve_start(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set status = 'playing', last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;
  return true;
end;
$$;

-- pve_end：对局结束转为 seated，保留棋盘供观众看结局
--          （不清 game_state；再次开局由 pve_start 清零）
create or replace function public.pve_end(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set status = 'seated', last_active_at = now()
  where id = tid and player_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 7. RPC：pve_report_state（玩家本人整包上报棋局状态，观战重放数据源）
-- ============================================================
create or replace function public.pve_report_state(tid text, state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set game_state = coalesce(state, '{}'::jsonb),
      last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;
  return true;
end;
$$;

-- ============================================================
-- 8. RPC：pve_heartbeat（心跳，统一入口，SeatManager 只调这一个）
--    玩家 -> 刷新座位 last_active_at；观众 -> 刷新观战关系 last_active_at
-- ============================================================
create or replace function public.pve_heartbeat(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set last_active_at = now()
  where id = tid and player_id = uid;

  update public.pve_watchers
  set last_active_at = now()
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 9. RPC：pve_forfeit（玩家中途退出 = 判负，原子写库）
--    规则（开发文档第 25.4/25.7 条）：对局中退出，先判定该玩家输
--    （game_state 置 finished + winner=computer），再释放座位
--    （player_id=null, status='open'），并清空本人全部观战关系。
-- ============================================================
create or replace function public.pve_forfeit(tid text, state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  final_state jsonb := coalesce(state, '{}'::jsonb);
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 覆盖为已结束 + 电脑胜（中途退出 = 判负）
  final_state := final_state
    || jsonb_build_object('status', 'finished', 'winner', 'computer', 'turn', '');

  update public.pve_tables
  set status = 'open',
      player_id = null,
      game_state = final_state,
      last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  -- 退出即清掉本人所有观战关系（含其他桌）
  delete from public.pve_watchers
  where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 10. RLS 策略
-- ============================================================
alter table public.pve_tables enable row level security;
alter table public.pve_watchers enable row level security;

-- pve_tables：登录用户可读；状态变更一律走 RPC（database function 自带安全校验）
drop policy if exists pve_tables_select on public.pve_tables;
create policy pve_tables_select on public.pve_tables
  for select using (auth.role() = 'authenticated');

-- pve_watchers：登录用户可以查看全部（大厅要显示每桌观众数属公开信息）
drop policy if exists pve_watchers_select on public.pve_watchers;
create policy pve_watchers_select on public.pve_watchers
  for select using (auth.role() = 'authenticated');

drop policy if exists pve_watchers_insert on public.pve_watchers;
create policy pve_watchers_insert on public.pve_watchers
  for insert with check (auth.role() = 'authenticated');

drop policy if exists pve_watchers_delete on public.pve_watchers;
create policy pve_watchers_delete on public.pve_watchers
  for delete using (auth.role() = 'authenticated');

-- ============================================================
-- 11. 定期清理（pg_cron，3 分钟兜底）
-- ============================================================
-- 释放 3 分钟无心跳的座位（对局中客户端每 20s 心跳刷新，不会被清）
select cron.schedule('pve-release-idle-seats',
  '* * * * *',
  $$
  update public.pve_tables
  set player_id = null, status = 'open',
      watcher_count = (select count(*) from public.pve_watchers w where w.table_id = public.pve_tables.id)
  where last_active_at < now() - interval '3 minutes'
    and status <> 'open';
  $$);

-- 清理 3 分钟无心跳的观战关系（离线观众自动退场；存活观众由 pve_heartbeat 刷新）
select cron.schedule('pve-purge-offline-watchers',
  '* * * * *',
  $$
  delete from public.pve_watchers w
  where w.last_active_at < now() - interval '3 minutes';
  $$);

-- ============================================================
-- 12. 校验
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname in ('pve_sit','pve_leave','pve_watch','pve_unwatch',
                  'pve_start','pve_end','pve_heartbeat',
                  'pve_report_state','pve_forfeit')
order by proname;
