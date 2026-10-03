-- ============================================================
-- 修复：大厅预置 30 张空桌 + 直接入座 RPC + 观战计数 + 私房销毁
-- 在 Supabase Dashboard > SQL Editor 整段执行，可重复执行（幂等）
-- ============================================================

-- ============================================================
-- 1. 预置 30 张大厅空桌（永久存在，不销毁）
--    id = T01 ~ T30，全部空位、等待状态
-- ============================================================
insert into public.lobby_tables (id, status)
select 'T' || lpad(i::text, 2, '0'), 'waiting'
from generate_series(1, 30) as i
on conflict (id) do nothing;

-- ============================================================
-- 2. RPC：sit_lobby_table（直接入座）
--    空桌 -> 坐 A 位；A 已被占 -> 坐 B 位；两人都在 -> 报错 TABLE_FULL
--    返回坐的座位 'a' / 'b'（文本标量）
-- ============================================================
create or replace function public.sit_lobby_table(tid text)
returns text
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  seat text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 已在该桌的玩家直接返回其座位（重进/断线恢复）
  select case
           when player_a_id = uid then 'a'
           when player_b_id = uid then 'b'
         end into seat
  from public.lobby_tables
  where id = tid;

  if seat is not null then
    update public.lobby_tables set last_active_at = now() where id = tid;
    return seat;
  end if;

  -- 坐 A 位
  update public.lobby_tables
  set player_a_id = uid,
      last_active_at = now()
  where id = tid and player_a_id is null;

  if found then
    return 'a';
  end if;

  -- 坐 B 位
  update public.lobby_tables
  set player_b_id = uid,
      status = case when player_a_id is not null then 'playing' else 'waiting' end,
      last_active_at = now()
  where id = tid
    and player_b_id is null
    and player_a_id is distinct from uid;

  if found then
    return 'b';
  end if;

  raise exception 'TABLE_FULL';
end;
$$;

-- ============================================================
-- 3. RPC：watch_lobby_table / unwatch_lobby_table（观战人数计数，不限人数）
-- ============================================================
create or replace function public.watch_lobby_table(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.lobby_tables
  set watcher_count = watcher_count + 1,
      last_active_at = now()
  where id = tid;

  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;
  return true;
end;
$$;

create or replace function public.unwatch_lobby_table(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.lobby_tables
  set watcher_count = greatest(watcher_count - 1, 0),
      last_active_at = now()
  where id = tid;

  return true;
end;
$$;

-- ============================================================
-- 4. 私房销毁：leave_room 改为「双方玩家都离开/空房 -> 直接删除房间」
-- ============================================================
create or replace function public.leave_room(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid    uuid := auth.uid();
  remain int;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.rooms
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
      host_id = case when host_id = uid then null else host_id end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      last_active_at = now()
  where room_code = code;

  delete from public.room_members
  where room_code = code and user_id = uid;

  -- 双方玩家都离开 -> 销毁房间（连同房间号与桌子）
  select count(*) into remain
  from public.rooms
  where room_code = code
    and (player_a_id is not null or player_b_id is not null);

  if remain = 0 then
    delete from public.room_members where room_code = code;
    delete from public.rooms where room_code = code;
  end if;

  return true;
end;
$$;

-- ============================================================
-- 5. 私房对局结束后销毁：finish_game 中私房 -> 标记 finished 并删除
-- ============================================================
create or replace function public.finish_game(
  in_table_id text default null,
  in_room_code char(4) default null,
  in_room_type text default 'lobby',
  in_winner_id uuid default null,
  in_loser_id  uuid default null,
  in_moves jsonb default null
)
returns uuid
language plpgsql security definer set search_path = public
as $$
declare
  gid       uuid;
  delta     int := case when in_room_type = 'private' then 2 else 1 end;
begin
  if auth.uid() is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;
  if in_winner_id is null or in_loser_id is null then
    raise exception 'INVALID_RESULT';
  end if;
  if in_winner_id = in_loser_id then
    raise exception 'INVALID_RESULT';
  end if;

  insert into public.games (room_type, table_id, room_code, player_a_id, player_b_id,
                            winner_id, loser_id, score_delta, moves)
  values (in_room_type, in_table_id, in_room_code, in_winner_id, in_loser_id,
          in_winner_id, in_loser_id, jsonb_build_object('winner', delta, 'loser', -1), in_moves)
  returning id into gid;

  perform 1 from public.profiles where id = in_winner_id for update;
  perform 1 from public.profiles where id = in_loser_id  for update;

  update public.profiles
  set score = score + delta, wins = wins + 1, total_games = total_games + 1
  where id = in_winner_id;

  update public.profiles
  set score = score - 1, losses = losses + 1, total_games = total_games + 1
  where id = in_loser_id;

  -- 大厅桌：复位等待（30 桌永久保留，不清除）
  if in_table_id is not null then
    update public.lobby_tables
    set status = 'waiting',
        player_a_id = null,
        player_b_id = null,
        current_turn_id = null,
        game_state = '{}'::jsonb,
        watcher_count = 0,
        last_active_at = now()
    where id = in_table_id;
  end if;

  -- 私房：对局结束后直接销毁
  if in_room_code is not null then
    delete from public.room_members where room_code = in_room_code;
    delete from public.rooms where room_code = in_room_code;
  end if;

  return gid;
end;
$$;

-- ============================================================
-- 校验：列出新增 RPC
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname in ('sit_lobby_table','watch_lobby_table','unwatch_lobby_table',
                  'leave_room','finish_game')
order by proname;
