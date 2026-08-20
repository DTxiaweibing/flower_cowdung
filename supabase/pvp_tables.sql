-- ============================================================
-- 鲜花与牛粪 - 人人游戏大厅（PvP）桌状态同步
-- 在 Supabase Dashboard > SQL Editor 执行（幂等，可重复运行）
-- 目标：与 PvE 大厅零差异体验，右侧"电脑"AIBOT 换成第二名真人。
--       双玩家位 player_a(左,先手) / player_b(右,后手)，整包 game_state 轮询同步。
-- 设计：
--   pvp_tables      20 张预置桌：status=open(空)/seated(有人)/playing(对局中)
--   pvp_watchers    观战关系表（一人最多观一桌），触发器维护 watcher_count
--   RPC：pvp_sit_a / pvp_sit_b / pvp_leave / pvp_watch / pvp_unwatch
--        pvp_ready / pvp_report_state / pvp_end / pvp_forfeit / pvp_heartbeat
-- 对局约定：A 先手；双方就绪自动置 playing；game_state 由落子方整包上报，
--           服务端用 current_turn 校验"轮到谁"，回合权威在服务端。
-- ============================================================

-- ============================================================
-- 1. 表结构
-- ============================================================
create table if not exists public.pvp_tables (
  id              text primary key,          -- '1'..'20'
  num             int  not null unique,      -- 桌号（排列显示用）
  status          text not null default 'open'
                    check (status in ('open', 'seated', 'playing')),
  player_a_id     uuid references public.profiles (id) on delete set null,
  player_b_id     uuid references public.profiles (id) on delete set null,
  current_turn_id uuid references public.profiles (id) on delete set null,
  ready_a         boolean not null default false,
  ready_b         boolean not null default false,
  game_state      jsonb not null default '{}'::jsonb,
  watcher_count   int  not null default 0,
  last_active_at  timestamptz not null default now(),
  created_at      timestamptz not null default now(),
  constraint pvp_table_players_differ check (
    not (player_a_id is not null and player_b_id is not null and player_a_id = player_b_id)
  )
);

create index if not exists pvp_tables_num_idx on public.pvp_tables (num);

create table if not exists public.pvp_watchers (
  table_id      text references public.pvp_tables (id) on delete cascade,
  user_id       uuid references public.profiles (id) on delete cascade,
  joined_at     timestamptz not null default now(),
  last_active_at timestamptz not null default now(),
  primary key (table_id, user_id)
);

create index if not exists pvp_watchers_user_idx on public.pvp_watchers (user_id);

-- 一人限观一桌：同一用户最多一条观战记录
alter table public.pvp_watchers drop constraint if exists pvp_watchers_user_key;
alter table public.pvp_watchers add constraint pvp_watchers_user_key unique (user_id);

-- 预置 20 桌（无此数据则永远没有桌子可显示）
insert into public.pvp_tables (id, num, status)
select g::text, g, 'open'
from generate_series(1, 20) g
on conflict (id) do nothing;

-- 复用现有安全函数（PvE 已定义；此处兜底确保存在）
create or replace function public.auth_uid_safe()
returns uuid
language sql stable
as 'select auth.uid();';

-- ============================================================
-- 2. 观察者计数触发器（增删自动 +1 / -1，保持一致性）
-- ============================================================
create or replace function public.pvp_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.pvp_tables
  set watcher_count = watcher_count + 1
  where id = new.table_id;
  return new;
end;
$$;

create or replace function public.pvp_watcher_dec()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.pvp_tables
  set watcher_count = greatest(0, watcher_count - 1)
  where id = old.table_id;
  return old;
end;
$$;

drop trigger if exists pvp_watchers_inc on public.pvp_watchers;
create trigger pvp_watchers_inc
  after insert on public.pvp_watchers
  for each row execute procedure public.pvp_watcher_inc();

drop trigger if exists pvp_watchers_dec on public.pvp_watchers;
create trigger pvp_watchers_dec
  after delete on public.pvp_watchers
  for each row execute procedure public.pvp_watcher_dec();

-- ============================================================
-- 3. RPC：pvp_sit_a / pvp_sit_b（坐下；先坐=左=A=先手；每人限一桌）
-- ============================================================
create or replace function public.pvp_sit_a(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id = uid then
    delete from public.pvp_watchers where user_id = uid;
    return true; -- 已坐 A 位，幂等
  end if;
  if a_id is not null then
    raise exception 'TABLE_OCCUPIED';
  end if;

  select exists (select 1 from public.pvp_tables
                 where player_a_id = uid or player_b_id = uid) into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pvp_tables
  set player_a_id = uid,
      status = case when b_id is not null then 'seated' else 'seated' end,
      last_active_at = now()
  where id = tid;

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;

create or replace function public.pvp_sit_b(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if b_id = uid then
    delete from public.pvp_watchers where user_id = uid;
    return true; -- 已坐 B 位，幂等
  end if;
  if b_id is not null then
    raise exception 'TABLE_OCCUPIED';
  end if;

  select exists (select 1 from public.pvp_tables
                 where player_a_id = uid or player_b_id = uid) into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pvp_tables
  set player_b_id = uid, status = 'seated', last_active_at = now()
  where id = tid;

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 4. RPC：pvp_leave（离开座位；对局中离开=判对方胜；同时退出全部观战）
-- ============================================================
create or replace function public.pvp_leave(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid      uuid := auth.uid();
  a_id     uuid;
  b_id     uuid;
  cstate   text;
  my_side  text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, status
       into a_id, b_id, cstate
  from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;

  -- 对局中离开 = 判对方胜并结算
  if cstate = 'playing' and my_side is not null then
    update public.pvp_tables
    set game_state = jsonb_build_object(
          'flowers', (game_state->>'flowers')::jsonb,
          'moves', (game_state->>'moves')::jsonb,
          'turn', '',
          'winner', case when my_side = 'a' then 'b' else 'a' end,
          'status', 'finished'
        ),
        current_turn_id = null,
        status = 'seated',
        last_active_at = now()
    where id = tid;
  end if;

  update public.pvp_tables
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
      -- 我这一侧清空；若对方本就为空（最后一人离场），整桌双 ready 一起重置
      ready_a = case
                  when player_a_id = uid then false
                  when (player_a_id = uid and player_b_id is null)
                    or (player_b_id = uid and player_a_id is null) then false
                  else ready_a
                end,
      ready_b = case
                  when player_b_id = uid then false
                  when (player_a_id = uid and player_b_id is null)
                    or (player_b_id = uid and player_a_id is null) then false
                  else ready_b
                end,
      status = case when player_a_id is null and player_b_id is null then 'open'
                    else status end,
      game_state = case
                     when (player_a_id = uid and player_b_id is null)
                       or (player_b_id = uid and player_a_id is null)
                     then '{}'::jsonb
                     else game_state
                   end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 5. RPC：pvp_watch / pvp_unwatch（坐下当观众；玩家不能观战）
-- ============================================================
create or replace function public.pvp_watch(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.pvp_tables where id = tid) then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  -- 已在任意桌作为玩家入座 -> 不能再观战（一人一位置）
  if exists (select 1 from public.pvp_tables
             where player_a_id = uid or player_b_id = uid) then
    raise exception 'ALREADY_PLAYER';
  end if;

  -- 换桌观战：先退旧观战，再坐新桌
  if exists (select 1 from public.pvp_watchers where user_id = uid) then
    delete from public.pvp_watchers where user_id = uid;
  end if;

  insert into public.pvp_watchers (table_id, user_id)
  values (tid, uid)
  on conflict (table_id, user_id) do nothing;

  return true;
end;
$$;

create or replace function public.pvp_unwatch(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  delete from public.pvp_watchers
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 6. RPC：pvp_ready（按"准备好了"；双方就绪且都有人 -> 开局）
-- ============================================================
create or replace function public.pvp_ready(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pvp_tables
  set ready_a = case when player_a_id = uid then true else ready_a end,
      ready_b = case when player_b_id = uid then true else ready_b end,
      last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;

  if a_id is not null and b_id is not null
     and exists (select 1 from public.pvp_tables
                 where id = tid and ready_a and ready_b) then
    update public.pvp_tables
    set status = 'playing',
        current_turn_id = a_id,             -- A 先手
        game_state = '{"turn":"a","status":"ongoing","flowers":[1,2,3,4,5,6],"moves":[]}'::jsonb,
        last_active_at = now()
    where id = tid;
  end if;

  return true;
end;
$$;

-- ============================================================
-- 7. RPC：pvp_report_state（整包上报；服务端校验回合=当前玩家）
--    回合权威在服务端：非当前回合者写入抛 NOT_YOUR_TURN。
-- ============================================================
create or replace function public.pvp_report_state(tid text, state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid      uuid := auth.uid();
  a_id     uuid;
  b_id     uuid;
  c_turn   uuid;
  c_status text;
  my_side  text;
  new_turn text;
  st_status text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, current_turn_id, status
       into a_id, b_id, c_turn, c_status
  from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  -- 仅对局中限制回合；结束状态允许本桌任一玩家结算
  if c_status = 'playing' then
    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
    if my_side is null then
      raise exception 'NOT_YOUR_TABLE';
    end if;
    if c_turn is not null and c_turn = uid then
      -- 允许：当前回合者写入
      null;
    elsif state->>'status' = 'finished' then
      -- 对局已在客户端判定结束，当前回合者可结算
      if c_turn = uid then
        null;
      else
        raise exception 'NOT_YOUR_TURN';
      end if;
    else
      raise exception 'NOT_YOUR_TURN';
    end if;
  end if;

  st_status := coalesce(state->>'status', 'ongoing');
  new_turn := coalesce(state->>'turn', '');

  update public.pvp_tables
  set game_state = state,
      current_turn_id = case
        when st_status = 'finished' then null
        when new_turn = 'a' then a_id
        when new_turn = 'b' then b_id
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      last_active_at = now()
  where id = tid;

  return true;
end;
$$;

-- ============================================================
-- 8. RPC：pvp_end（本局结算完成，双方回到"已入座"，可再来一局）
-- ============================================================
create or replace function public.pvp_end(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  update public.pvp_tables
  set status = 'seated',
      current_turn_id = null,
      ready_a = false,
      ready_b = false,
      last_active_at = now()
  where id = tid;

  return true;
end;
$$;

-- ============================================================
-- 9. RPC：pvp_heartbeat（心跳：刷新座位 / 观战关系）
-- ============================================================
create or replace function public.pvp_heartbeat(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pvp_tables
  set last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  update public.pvp_watchers
  set last_active_at = now()
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 10. RLS 策略
-- ============================================================
alter table public.pvp_tables enable row level security;
alter table public.pvp_watchers enable row level security;

drop policy if exists pvp_tables_select on public.pvp_tables;
create policy pvp_tables_select on public.pvp_tables
  for select using (auth.role() = 'authenticated');

drop policy if exists pvp_watchers_select on public.pvp_watchers;
create policy pvp_watchers_select on public.pvp_watchers
  for select using (auth.role() = 'authenticated');

drop policy if exists pvp_watchers_insert on public.pvp_watchers;
create policy pvp_watchers_insert on public.pvp_watchers
  for insert with check (auth.role() = 'authenticated');

drop policy if exists pvp_watchers_delete on public.pvp_watchers;
create policy pvp_watchers_delete on public.pvp_watchers
  for delete using (auth.role() = 'authenticated');

-- ============================================================
-- 11. 定期清理（pg_cron）：对局中 3 分钟无心跳 = 判对方胜；否则释放
-- ============================================================
select cron.schedule('pvp-release-stale-playing',
  '* * * * *',
  $$
  update public.pvp_tables
  set status = 'seated',
      current_turn_id = null,
      ready_a = false,
      ready_b = false,
      game_state = jsonb_build_object(
          'flowers', (game_state->>'flowers')::jsonb,
          'moves', (game_state->>'moves')::jsonb,
          'turn', '',
          'winner', case
                      when player_a_id is null and player_b_id is not null then 'b'
                      when player_b_id is null and player_a_id is not null then 'a'
                      else null
                    end,
          'status', 'finished'
        ),
      last_active_at = now()
  where status = 'playing'
    and last_active_at < now() - interval '3 minutes';
  $$);

-- seated 桌超时：整桌释放回 open（会一并清掉对局中一方留下的座位）
select cron.schedule('pvp-release-stale-seats',
  '* * * * *',
  $$
  update public.pvp_tables
  set player_a_id = null,
      player_b_id = null,
      current_turn_id = null,
      ready_a = false,
      ready_b = false,
      game_state = '{}'::jsonb,
      status = 'open',
      last_active_at = now()
  where status = 'seated'
    and last_active_at < now() - interval '3 minutes';
  $$);

-- 清理 3 分钟无心跳的观战关系（离线观众自动退场）
select cron.schedule('pvp-purge-offline-watchers',
  '* * * * *',
  $$
  delete from public.pvp_watchers w
  where w.last_active_at < now() - interval '3 minutes';
  $$);