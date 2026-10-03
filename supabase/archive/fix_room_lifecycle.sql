-- ============================================================
-- 修复：私房生命周期改造
--   * create_room 只生成房号，不入座（坐下才算真正创建）
--   * sit_room：坐下 = 建桌（空房坐A，占A坐B）
--   * join_room_watcher：仅观战，不限人数
--   * leave_room：双方玩家都离开 -> 销毁房间，观众自动退出
--   * finish_game（私房）：标记 finished 保留棋谱供观战，玩家退出后销毁
-- 在 Supabase Dashboard > SQL Editor 整段执行，可重复执行（幂等）
-- ============================================================

-- 1. create_room：只生成 4 位房号，创建空房间（无人入座）
create or replace function public.create_room()
returns char(4)
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  code char(4);
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  loop
    code := public.random_room_code();
    exit when not exists (select 1 from public.rooms where room_code = code);
  end loop;

  insert into public.rooms (room_code, host_id, status)
  values (code, uid, 'waiting');

  return code;
end;
$$;

-- 2. sit_room：坐下 = 真正的创建动作。空房坐 A，A 被占坐 B，满房报错
create or replace function public.sit_room(code char(4))
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

  if not exists (select 1 from public.rooms where room_code = code and status <> 'finished') then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  -- 已坐过的玩家直接返回其座位（重进/断线恢复）
  select case when player_a_id = uid then 'a'
              when player_b_id = uid then 'b' end into seat
  from public.rooms
  where room_code = code;

  if seat is not null then
    update public.rooms set last_active_at = now() where room_code = code;
    return seat;
  end if;

  -- 坐 A 位
  update public.rooms
  set player_a_id = uid, last_active_at = now()
  where room_code = code and player_a_id is null;

  if found then
    seat := 'a';
  else
    -- 坐 B 位
    update public.rooms
    set player_b_id = uid,
        status = 'playing',
        last_active_at = now()
    where room_code = code
      and player_b_id is null
      and player_a_id is distinct from uid;

    if not found then
      raise exception 'ROOM_FULL';
    end if;
    seat := 'b';
  end if;

  insert into public.room_members (room_code, user_id, role)
  values (code, uid, 'player')
  on conflict (room_code, user_id) do update set role = 'player';

  return seat;
end;
$$;

-- 3. join_room_watcher：仅观战，不限人数
create or replace function public.join_room_watcher(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.rooms where room_code = code and status <> 'finished') then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  insert into public.room_members (room_code, user_id, role)
  values (code, uid, 'watcher')
  on conflict (room_code, user_id) do update set role = 'watcher';

  return true;
end;
$$;

-- 4. 删除旧的 join_room（已被 sit_room / join_room_watcher 取代）
drop function if exists public.join_room(char(4));

-- 5. leave_room：清除本座 + 移除成员；双方玩家都离开 -> 销毁房间，观众自动退出
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

  select count(*) into remain
  from public.rooms
  where room_code = code
    and (player_a_id is not null or player_b_id is not null);

  -- 双方玩家都已退出 -> 房间销毁（成员/观众随之级联退出）
  if remain = 0 then
    delete from public.room_members where room_code = code;
    delete from public.rooms where room_code = code;
  end if;

  return true;
end;
$$;

-- 6. finish_game（私房）：标记 finished，保留 game_state 供结算展示；
--    销毁交由双方玩家退出时 leave_room 完成
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

  -- 私房：标记 finished，保留对局状态；玩家退出时销毁
  if in_room_code is not null then
    update public.rooms
    set status = 'finished',
        last_active_at = now()
    where room_code = in_room_code;
  end if;

  return gid;
end;
$$;

-- ============================================================
-- 校验
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname in ('create_room','sit_room','join_room_watcher','leave_room','finish_game')
order by proname;
