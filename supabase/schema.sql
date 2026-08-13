-- ============================================================
-- 鲜花与牛粪 - Supabase 建库脚本
-- 一次性在 Supabase Dashboard > SQL Editor 执行。
-- 对应《开发文档.md》第 14/15/16/21 章。
-- ============================================================

-- ============================================================
-- 1. 扩展
-- ============================================================
create extension if not exists pgcrypto;   -- gen_random_uuid()

-- ============================================================
-- 2. profiles（用户资料 + 排名统计）
-- ============================================================
create table if not exists public.profiles (
  id            uuid primary key references auth.users (id) on delete cascade,
  nickname      text not null unique,
  gender        text not null check (gender in ('male', 'female')),
  score         int  not null default 0,
  wins          int  not null default 0,
  losses        int  not null default 0,
  total_games   int  not null default 0,
  last_seen_at  timestamptz,
  created_at    timestamptz not null default now()
);

-- 新 auth 用户自动建 profile 行（占位，昵称由 RPC 填充）
create or replace function public.handle_new_user()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  insert into public.profiles (id) values (new.id)
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_user();

-- ============================================================
-- 3. lobby_tables（公开大厅桌子）
-- ============================================================
create table if not exists public.lobby_tables (
  id              text primary key,          -- 随机短桌号
  status          text not null default 'waiting'
                    check (status in ('waiting', 'playing', 'finished')),
  player_a_id     uuid references public.profiles (id) on delete set null,
  player_b_id     uuid references public.profiles (id) on delete set null,
  current_turn_id uuid references public.profiles (id) on delete set null,
  game_state      jsonb not null default '{}'::jsonb,
  watcher_count   int  not null default 0,
  last_active_at  timestamptz not null default now(),
  created_at      timestamptz not null default now(),
  constraint lobby_table_players_differ check (player_a_id is distinct from player_b_id)
);

create index if not exists lobby_tables_status_idx on public.lobby_tables (status);
create index if not exists lobby_tables_last_active_idx on public.lobby_tables (last_active_at);

-- ============================================================
-- 4. rooms（私人房间）
-- ============================================================
create table if not exists public.rooms (
  room_code       char(4) primary key,       -- 4 位数字房间号
  host_id         uuid references public.profiles (id) on delete set null,
  status          text not null default 'waiting'
                    check (status in ('waiting', 'playing', 'finished')),
  player_a_id     uuid references public.profiles (id) on delete set null,
  player_b_id     uuid references public.profiles (id) on delete set null,
  current_turn_id uuid references public.profiles (id) on delete set null,
  game_state      jsonb not null default '{}'::jsonb,
  last_active_at  timestamptz not null default now(),
  created_at      timestamptz not null default now(),
  constraint room_players_differ check (player_a_id is distinct from player_b_id)
);

create index if not exists rooms_status_idx on public.rooms (status);
create index if not exists rooms_last_active_idx on public.rooms (last_active_at);

-- ============================================================
-- 5. room_members（私房在线成员/观战名单）
-- ============================================================
create table if not exists public.room_members (
  room_code  text references public.rooms (room_code) on delete cascade,
  user_id    uuid references public.profiles (id) on delete cascade,
  role       text not null default 'watcher' check (role in ('player', 'watcher')),
  is_muted   boolean not null default false,
  joined_at  timestamptz not null default now(),
  primary key (room_code, user_id)
);

create index if not exists room_members_room_idx on public.room_members (room_code);

-- ============================================================
-- 6. games（对局记录 / 排名数据来源）
-- ============================================================
create table if not exists public.games (
  id           uuid primary key default gen_random_uuid(),
  room_type    text not null check (room_type in ('lobby', 'private')),
  table_id     text,
  room_code    char(4),
  player_a_id  uuid not null references public.profiles (id) on delete cascade,
  player_b_id  uuid not null references public.profiles (id) on delete cascade,
  winner_id    uuid references public.profiles (id) on delete set null,
  loser_id     uuid references public.profiles (id) on delete set null,
  score_delta  jsonb not null default '{}'::jsonb,
  moves        jsonb,
  finished_at  timestamptz not null default now()
);

create index if not exists games_room_type_idx on public.games (room_type, finished_at desc);
create index if not exists games_table_id_idx on public.games (table_id);
create index if not exists games_room_code_idx on public.games (room_code);

-- ============================================================
-- 7. 常用辅助函数
-- ============================================================

-- 随机 4 位数字房间号
create or replace function public.random_room_code()
returns char(4)
language sql stable
as $$
  select lpad((floor(random() * 9000) + 1000)::text, 4, '0')::char(4)
$$;

-- 随机短桌号（6 位字母数字，去除易混淆字符）
create or replace function public.random_table_id()
returns text
language sql stable
as $$
  select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', (random() * 31)::int + 1, 1), '')
  from generate_series(1, 6)
$$;

-- ============================================================
-- 8. RPC：check_nickname（昵称重名校验）
-- ============================================================
create or replace function public.check_nickname(n text)
returns boolean
language sql security definer set search_path = public
as $$
  select not exists (select 1 from public.profiles where nickname = n)
$$;

-- ============================================================
-- 9. RPC：create_profile（创建资料，重名自动加后缀）
--   返回新 user_id（uuid）
-- ============================================================
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

  base := left(btrim(nick), 12);            -- 昵称最长 12 字符
  cand := base;
  -- 昵称已存在则依次追加后缀 名称2、名称3…
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

-- ============================================================
-- 10. RPC：create_lobby_table（建桌，超上限报错）
-- ============================================================
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

  -- 生成唯一桌号
  loop
    tid := public.random_table_id();
    exit when not exists (select 1 from public.lobby_tables where id = tid);
  end loop;

  insert into public.lobby_tables (id, player_a_id, status)
  values (tid, uid, 'waiting');

  return tid;
end;
$$;

-- ============================================================
-- 11. RPC：join_table / leave_table（大厅入座/离座）
-- ============================================================
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

-- ============================================================
-- 12. RPC：create_room / join_room / leave_room（私房生命周期）
-- ============================================================
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

  -- 作为玩家加入空位（优先 B 位）
  update public.rooms
  set player_b_id = uid,
      status = 'playing',
      last_active_at = now()
  where room_code = code
    and player_b_id is null
    and player_a_id is distinct from uid;

  -- 无论如何都是房间成员（入座成功则为 player，否则 watcher）
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

-- ============================================================
-- 13. RPC：get_game_state / update_game_state（棋盘权威读写）
-- ============================================================
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

-- 落子：校验当前回合者、更新棋盘 + 切换回合 + 写 turnDeadlineAt + 更新 last_active_at
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

-- ============================================================
-- 14. RPC：finish_game（结算：写 games + 原子更新双方积分/胜败场）
--   room_type: 'lobby' 胜+1，'private' 胜+2，败方一律 -1
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

  -- 原子更新两位玩家（用 FOR UPDATE 锁定，避免并发叠加）
  perform 1 from public.profiles where id = in_winner_id for update;
  perform 1 from public.profiles where id = in_loser_id  for update;

  update public.profiles
  set score = score + delta, wins = wins + 1, total_games = total_games + 1
  where id = in_winner_id;

  update public.profiles
  set score = score - 1, losses = losses + 1, total_games = total_games + 1
  where id = in_loser_id;

  -- 对局表状态复位
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

-- ============================================================
-- 15. RPC：get_ranking（排行榜 Top N）
-- ============================================================
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

-- ============================================================
-- 16. RPC：host_kick / host_mute（房主权限）
-- ============================================================
create or replace function public.host_kick(code char(4), target uuid)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;
  -- 仅房主，且不能踢棋手
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
-- 17. RLS 策略（第 21 章）
-- ============================================================
alter table public.profiles enable row level security;
alter table public.lobby_tables enable row level security;
alter table public.rooms enable row level security;
alter table public.room_members enable row level security;
alter table public.games enable row level security;

-- profiles：登录用户可查看全部（排行需要）；只能改自己
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles
  for select using (auth.role() = 'authenticated');

drop policy if exists profiles_update on public.profiles;
create policy profiles_update on public.profiles
  for update using (auth.uid() = id);

-- lobby_tables：登录用户可读/建/更新
drop policy if exists lobby_tables_select on public.lobby_tables;
create policy lobby_tables_select on public.lobby_tables
  for select using (auth.role() = 'authenticated');

drop policy if exists lobby_tables_insert on public.lobby_tables;
create policy lobby_tables_insert on public.lobby_tables
  for insert with check (auth.role() = 'authenticated');

drop policy if exists lobby_tables_update on public.lobby_tables;
create policy lobby_tables_update on public.lobby_tables
  for update using (auth.role() = 'authenticated')
  with check (auth.role() = 'authenticated');

-- rooms：登录用户可读/建
drop policy if exists rooms_select on public.rooms;
create policy rooms_select on public.rooms
  for select using (auth.role() = 'authenticated');

drop policy if exists rooms_insert on public.rooms;
create policy rooms_insert on public.rooms
  for insert with check (auth.uid() = host_id);

-- room_members：房间内成员可读；本人可插入/删除
drop policy if exists room_members_select on public.room_members;
create policy room_members_select on public.room_members
  for select using (
    exists (select 1 from public.room_members m2
            where m2.room_code = room_members.room_code and m2.user_id = auth.uid())
  );

drop policy if exists room_members_insert on public.room_members;
create policy room_members_insert on public.room_members
  for insert with check (auth.uid() = user_id);

drop policy if exists room_members_delete on public.room_members;
create policy room_members_delete on public.room_members
  for delete using (auth.uid() = user_id);

-- games：登录用户可读/写
drop policy if exists games_select on public.games;
create policy games_select on public.games
  for select using (auth.role() = 'authenticated');

drop policy if exists games_insert on public.games;
create policy games_insert on public.games
  for insert with check (auth.role() = 'authenticated');

-- ============================================================
-- 18. 定时清理（空闲桌 / 空房 / 离线座位释放）
--    借助 pg_cron（Supabase 已内置扩展），每分钟执行一次
-- ============================================================
create extension if not exists pg_cron;

-- 释放 10 分钟无心跳的座位
select cron.schedule('release-idle-tables',
  '* * * * *',
  $$
  update public.lobby_tables
  set player_a_id = null, player_b_id = null, current_turn_id = null,
      status = 'waiting', watcher_count = 0, game_state = '{}'::jsonb
  where last_active_at < now() - interval '10 minutes'
    and status <> 'finished';
  $$);

-- 清理 30 分钟未活跃的 finished 桌
select cron.schedule('purge-old-tables',
  '*/5 * * * *',
  $$
  delete from public.lobby_tables
  where status = 'finished' and last_active_at < now() - interval '30 minutes';
  $$);

-- 清理 10 分钟无心跳且无成员的空房
select cron.schedule('release-idle-rooms',
  '* * * * *',
  $$
  update public.rooms
  set player_a_id = null, player_b_id = null, current_turn_id = null,
      status = 'waiting', game_state = '{}'::jsonb
  where last_active_at < now() - interval '10 minutes'
    and status <> 'finished';
  $$);

select cron.schedule('purge-old-rooms',
  '*/5 * * * *',
  $$
  delete from public.rooms
  where status = 'finished' and last_active_at < now() - interval '30 minutes';
  $$);

-- 清理离线的 room_members（10 分钟无心跳）
select cron.schedule('purge-offline-members',
  '*/5 * * * *',
  $$
  delete from public.room_members m
  using public.profiles p
  where m.user_id = p.id
    and p.last_seen_at < now() - interval '10 minutes';
  $$);
