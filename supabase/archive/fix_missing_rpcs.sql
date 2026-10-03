-- ============================================================
-- 修复：补齐缺失的 RPC 函数（check_nickname 及之后全部函数）
-- 在 Supabase Dashboard > SQL Editor 整段执行，可重复执行（幂等）
-- ============================================================

-- 8. RPC：check_nickname（昵称重名校验）
create or replace function public.check_nickname(n text)
returns boolean
language sql security definer set search_path = public
as $$
  select not exists (select 1 from public.profiles where nickname = n)
$$;

-- 9. RPC：create_profile（创建资料，重名自动加后缀）
create or replace function public.create_profile(nick text, g text)
returns uuid
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  base text;
  cand text;
  i    int  := 1;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;
  if nick is null or btrim(nick) = '' then
    raise exception 'NICKNAME_EMPTY';
  end if;
  if g is null or g not in ('male', 'female') then
    raise exception 'INVALID_GENDER';
  end if;

  base := left(btrim(nick), 12);
  cand := base;
  while exists (select 1 from public.profiles where nickname = cand) loop
    i    := i + 1;
    cand := left(base, 10) || i::text;
  end loop;

  insert into public.profiles (id, nickname, gender)
  values (uid, cand, g)
  on conflict (id) do update set
    nickname = excluded.nickname,
    gender   = excluded.gender;

  return uid;
end;
$$;

-- 10. RPC：create_lobby_table（建桌，超上限报错）
create or replace function public.create_lobby_table()
returns text
language plpgsql security definer set search_path = public
as $$
declare
  uid   uuid := auth.uid();
  tid   text;
  alive int;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select count(*) into alive
  from public.lobby_tables
  where status in ('waiting', 'playing');

  if alive >= 200 then
    raise exception 'TABLE_LIMIT_REACHED';
  end if;

  loop
    tid := public.random_table_id();
    exit when not exists (select 1 from public.lobby_tables where id = tid);
  end loop;

  insert into public.lobby_tables (id, player_a_id, status)
  values (tid, uid, 'waiting');

  return tid;
end;
$$;

-- 11. RPC：join_table / leave_table（大厅入座/离座）
create or replace function public.join_table(tid text)
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
  set player_b_id = uid,
      status = case when player_a_id is not null then 'playing' else 'waiting' end,
      last_active_at = now()
  where id = tid
    and player_b_id is null
    and player_a_id is distinct from uid;

  if not found then
    raise exception 'TABLE_UNAVAILABLE';
  end if;
  return true;
end;
$$;

create or replace function public.leave_table(tid text)
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
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
      status = case when player_a_id = uid or player_b_id = uid then 'waiting'
                    else status end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      last_active_at = now()
  where id = tid
    and (player_a_id = uid or player_b_id = uid);

  return true;
end;
$$;

-- 12. RPC：create_room / join_room / leave_room（私房生命周期）
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

  insert into public.rooms (room_code, host_id, player_a_id)
  values (code, uid, uid);

  insert into public.room_members (room_code, user_id, role)
  values (code, uid, 'player');

  return code;
end;
$$;

create or replace function public.join_room(code char(4))
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

  update public.rooms
  set player_b_id = uid,
      status = 'playing',
      last_active_at = now()
  where room_code = code
    and player_b_id is null
    and player_a_id is distinct from uid;

  insert into public.room_members (room_code, user_id, role)
  values (code, uid, case when found then 'player' else 'watcher' end)
  on conflict (room_code, user_id) do update set
    role = case when public.room_members.role <> 'player' then 'watcher' else public.room_members.role end;

  return true;
end;
$$;

create or replace function public.leave_room(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
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

  return true;
end;
$$;

-- 13. RPC：get_game_state / update_game_state（棋盘权威读写）
create or replace function public.get_game_state(
  in_table_id text default null,
  in_room_code char(4) default null
)
returns jsonb
language plpgsql security definer set search_path = public
as $$
declare
  gs jsonb;
begin
  if in_table_id is not null then
    select game_state into gs from public.lobby_tables where id = in_table_id;
  elsif in_room_code is not null then
    select game_state into gs from public.rooms where room_code = in_room_code;
  else
    raise exception 'NO_TARGET';
  end if;
  if gs is null then
    raise exception 'NOT_FOUND';
  end if;
  return gs;
end;
$$;

create or replace function public.update_game_state(
  in_table_id text default null,
  in_room_code char(4) default null,
  new_state jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql security definer set search_path = public
as $$
declare
  uid    uuid := auth.uid();
  gs     jsonb;
  target text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if in_table_id is not null then
    select game_state into gs from public.lobby_tables where id = in_table_id for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
    if (gs->>'turnUserId') is not null and (gs->>'turnUserId')::uuid <> uid then
      raise exception 'NOT_YOUR_TURN';
    end if;
    update public.lobby_tables
    set game_state = new_state,
        last_active_at = now()
    where id = in_table_id;
    target := in_table_id;
  elsif in_room_code is not null then
    select game_state into gs from public.rooms where room_code = in_room_code for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
    if (gs->>'turnUserId') is not null and (gs->>'turnUserId')::uuid <> uid then
      raise exception 'NOT_YOUR_TURN';
    end if;
    update public.rooms
    set game_state = new_state,
        last_active_at = now()
    where room_code = in_room_code;
    target := in_room_code;
  else
    raise exception 'NO_TARGET';
  end if;

  return new_state;
end;
$$;

-- 14. RPC：finish_game（结算：写 games + 原子更新双方积分/胜败场）
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

  if in_table_id is not null then
    update public.lobby_tables set status = 'finished', game_state = '{}'::jsonb
    where id = in_table_id;
  end if;
  if in_room_code is not null then
    update public.rooms set status = 'finished', game_state = '{}'::jsonb
    where room_code = in_room_code;
  end if;

  return gid;
end;
$$;

-- 15. RPC：get_ranking（排行榜 Top N）
create or replace function public.get_ranking(limit_n int default 50)
returns table (id uuid, nickname text, gender text, score int,
               wins int, losses int, total_games int)
language plpgsql security definer set search_path = public
as $$
begin
  return query
  select p.id, p.nickname, p.gender, p.score, p.wins, p.losses, p.total_games
  from public.profiles p
  where p.nickname is not null
  order by p.score desc, p.created_at asc
  limit limit_n;
end;
$$;

-- 16. RPC：host_kick / host_mute（房主权限）
create or replace function public.host_kick(code char(4), target uuid)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;
  if not exists (select 1 from public.rooms
                 where room_code = code and host_id = uid) then
    raise exception 'NOT_HOST';
  end if;
  if exists (select 1 from public.rooms
             where room_code = code and (player_a_id = target or player_b_id = target)) then
    raise exception 'CANNOT_KICK_PLAYER';
  end if;
  delete from public.room_members where room_code = code and user_id = target;
  return true;
end;
$$;

create or replace function public.host_mute(code char(4), target uuid, mute boolean)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;
  if not exists (select 1 from public.rooms
                 where room_code = code and host_id = uid) then
    raise exception 'NOT_HOST';
  end if;
  update public.room_members set is_muted = mute
  where room_code = code and user_id = target;
  return true;
end;
$$;

-- ============================================================
-- 校验：列出 13 个应存在的函数
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname in ('check_nickname','create_profile','create_lobby_table',
                  'join_table','leave_table','create_room','join_room',
                  'leave_room','get_game_state','update_game_state',
                  'finish_game','get_ranking','host_kick','host_mute')
order by proname;
