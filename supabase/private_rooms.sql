-- ============================================================
-- 鲜花与牛粪 - 私密房间（全新实现，镜像 pvp 逻辑，不复用旧 rooms 表）
-- 在 Supabase Dashboard > SQL Editor 执行（幂等，可重复运行）
-- 设计：
--   private_rooms        房间表（room_code 4 位数字主键）
--   private_room_watchers 观战关系表（一人最多观一房间），触发器维护 watcher_count
--   RPC：room_create / room_join / room_sit / room_ready / room_report_state
--        room_end / room_leave / room_watch / room_unwatch / room_heartbeat
-- 对局约定：A 先手；双方就绪自动置 playing；game_state 由落子方整包上报，
--           服务端用 current_turn 校验"轮到谁"，回合权威在服务端。
-- 入座规则：先到先得（创建者不占座；第一个入座的为 A/先手）
-- ============================================================

-- ============================================================
-- 1. 表结构
-- ============================================================
create table if not exists public.private_rooms (
  room_code       char(4) primary key,      -- 4 位数字房间号
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
  constraint private_room_players_differ check (
    not (player_a_id is not null and player_b_id is not null and player_a_id = player_b_id)
  )
);

create index if not exists private_rooms_last_active_idx on public.private_rooms (last_active_at);

create table if not exists public.private_room_watchers (
  room_code     text references public.private_rooms (room_code) on delete cascade,
  user_id       uuid references public.profiles (id) on delete cascade,
  joined_at     timestamptz not null default now(),
  last_active_at timestamptz not null default now(),
  primary key (room_code, user_id)
);

-- 一人限观一房间：同一用户最多一条观战记录
alter table public.private_room_watchers drop constraint if exists private_room_watchers_user_key;
alter table public.private_room_watchers add constraint private_room_watchers_user_key unique (user_id);

-- 复用现有安全函数（PvE 已定义；此处兜底确保存在）
create or replace function public.auth_uid_safe()
returns uuid
language sql stable
as 'select auth.uid();';

-- ============================================================
-- 2. 观战计数触发器
-- ============================================================
create or replace function public.private_room_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.private_rooms
  set watcher_count = watcher_count + 1
  where room_code = new.room_code;
  return new;
end;
$$;

create or replace function public.private_room_watcher_dec()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.private_rooms
  set watcher_count = greatest(0, watcher_count - 1)
  where room_code = old.room_code;
  return old;
end;
$$;

drop trigger if exists private_room_watchers_inc on public.private_room_watchers;
create trigger private_room_watchers_inc
  after insert on public.private_room_watchers
  for each row execute procedure public.private_room_watcher_inc();

drop trigger if exists private_room_watchers_dec on public.private_room_watchers;
create trigger private_room_watchers_dec
  after delete on public.private_room_watchers
  for each row execute procedure public.private_room_watcher_dec();

-- ============================================================
-- 3. RPC：room_create（创建房间；创建者不占座，返回 4 位房间号）
-- ============================================================
create or replace function public.room_create()
returns char(4)
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  code text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  loop
    code := ltrim(((floor(random() * 9000) + 1000)::int)::text);
    exit when not exists (select 1 from public.private_rooms where room_code = code);
  end loop;

  insert into public.private_rooms (room_code, status)
  values (code, 'open');

  return code::char(4);
end;
$$;

-- ============================================================
-- 4. RPC：room_join（用房间号进入；不存在或已结束则报错）
-- ============================================================
create or replace function public.room_join(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.private_rooms
                 where room_code = code) then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  return true;
end;
$$;

-- ============================================================
-- 5. RPC：room_sit（入座；先到先得，A 空则 A，否则 B；每人限一房间）
-- ============================================================
create or replace function public.room_sit(code char(4))
returns text
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  selse boolean;
  my_side text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if not found then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  -- 已在房间内：返回我的座位
  if a_id = uid then
    delete from public.private_room_watchers where user_id = uid;
    return 'a';
  end if;
  if b_id = uid then
    delete from public.private_room_watchers where user_id = uid;
    return 'b';
  end if;

  -- 每人限一房间（无论玩家还是观战）
  select exists (select 1 from public.private_rooms
                 where player_a_id = uid or player_b_id = uid) into selse;
  if selse then
    raise exception 'ALREADY_SITTING';
  end if;
  select exists (select 1 from public.private_room_watchers where user_id = uid) into selse;
  if selse then
    raise exception 'ALREADY_SITTING';
  end if;

  if a_id is null then
    update public.private_rooms
    set player_a_id = uid, status = 'seated', last_active_at = now()
    where room_code = code;
    my_side := 'a';
  elsif b_id is null then
    update public.private_rooms
    set player_b_id = uid, status = 'seated', last_active_at = now()
    where room_code = code;
    my_side := 'b';
  else
    raise exception 'SEAT_UNAVAILABLE';
  end if;

  return my_side;
end;
$$;

-- ============================================================
-- 6. RPC：room_ready（按"准备好了"；双方就绪 -> 开局）
--    先后手轮换：第 1 局 A（先入座）先手，之后每局交换（round_no 奇偶）
-- ============================================================
alter table public.private_rooms add column if not exists round_no int not null default 0;

create or replace function public.room_ready(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  c_status text;
  new_round int;
  first_is_a boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.private_rooms
  set ready_a = case when player_a_id = uid then true else ready_a end,
      ready_b = case when player_b_id = uid then true else ready_b end,
      last_active_at = now()
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  if not found then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  select player_a_id, player_b_id, status, round_no
       into a_id, b_id, c_status, new_round
  from public.private_rooms where room_code = code;

  -- 双方就绪且不在对局中才允许开局：
  --   防止对局中重复开局重置棋盘；开局即清 ready，下一局需重新准备
  if a_id is not null and b_id is not null
     and coalesce(c_status, 'open') <> 'playing'
     and exists (select 1 from public.private_rooms
                 where room_code = code and ready_a and ready_b) then
    new_round := new_round + 1;
    first_is_a := (new_round % 2) = 1;

    update public.private_rooms
    set status = 'playing',
        round_no = new_round,
        current_turn_id = case when first_is_a then a_id else b_id end,
        game_state = jsonb_build_object(
          'turn', case when first_is_a then 'a' else 'b' end,
          'status', 'ongoing',
          'flowers', '[1,2,3,4,5,6]'::jsonb,
          'moves', '[]'::jsonb),
        ready_a = false,
        ready_b = false,
        last_active_at = now()
    where room_code = code;
  end if;

  return true;
end;
$$;

-- ============================================================
-- 7. RPC：room_report_state（整包上报；服务端校验回合=当前玩家）
-- ============================================================
create or replace function public.room_report_state(code char(4), state jsonb)
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
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  -- 仅对局中限制回合；结束状态允许本桌任一玩家结算
  if c_status = 'playing' then
    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
    if my_side is null then
      raise exception 'NOT_YOUR_ROOM';
    end if;
    if c_turn is not null and c_turn = uid then
      null;
    elsif state->>'status' = 'finished' then
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

  update public.private_rooms
  set game_state = state,
      current_turn_id = case
        when st_status = 'finished' then null
        when new_turn = 'a' then a_id
        when new_turn = 'b' then b_id
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      ready_a = case when st_status = 'finished' then false else ready_a end,
      ready_b = case when st_status = 'finished' then false else ready_b end,
      last_active_at = now()
  where room_code = code;

  return true;
end;
$$;

-- ============================================================
-- 8. RPC：room_end（本局结算完成，双方回到"已入座"，可再来一局）
-- ============================================================
create or replace function public.room_end(code char(4))
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

  select player_a_id, player_b_id into a_id, b_id from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  update public.private_rooms
  set status = 'seated',
      current_turn_id = null,
      ready_a = false,
      ready_b = false,
      last_active_at = now()
  where room_code = code;

  return true;
end;
$$;

-- ============================================================
-- 9. RPC：room_leave（离开房间；对局中离开 = 判对方胜；同时退出全部观战）
-- ============================================================
create or replace function public.room_leave(code char(4))
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
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;

  -- 对局中离开 = 判对方胜并结算
  if cstate = 'playing' and my_side is not null then
    update public.private_rooms
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
    where room_code = code;
  end if;

  update public.private_rooms
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
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
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  -- 如果房间没有玩家了，踢出所有观战者（退到初始状态）
  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if a_id is null and b_id is null then
    delete from public.private_room_watchers where room_code = code;
  end if;

  delete from public.private_room_watchers where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 10. RPC：room_watch / room_unwatch（坐下当观众；玩家不能观战）
-- ============================================================
create or replace function public.room_watch(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.private_rooms where room_code = code) then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  -- 已在任意房间作为玩家入座 -> 不能再观战（一人一位置）
  if exists (select 1 from public.private_rooms
             where player_a_id = uid or player_b_id = uid) then
    raise exception 'ALREADY_SITTING';
  end if;

  -- 换房间观战：先退旧观战，再坐新房间
  if exists (select 1 from public.private_room_watchers where user_id = uid) then
    delete from public.private_room_watchers where user_id = uid;
  end if;

  insert into public.private_room_watchers (room_code, user_id)
  values (code, uid)
  on conflict (room_code, user_id) do nothing;

  return true;
end;
$$;

create or replace function public.room_unwatch(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  delete from public.private_room_watchers
  where room_code = code and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 11. RPC：room_heartbeat（心跳：刷新座位 / 观战关系）
-- ============================================================
create or replace function public.room_heartbeat(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.private_rooms
  set last_active_at = now()
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  update public.private_room_watchers
  set last_active_at = now()
  where room_code = code and user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 12. RLS 策略
-- ============================================================
alter table public.private_rooms enable row level security;
alter table public.private_room_watchers enable row level security;

drop policy if exists private_rooms_select on public.private_rooms;
create policy private_rooms_select on public.private_rooms
  for select using (auth.role() = 'authenticated');

drop policy if exists private_room_watchers_select on public.private_room_watchers;
create policy private_room_watchers_select on public.private_room_watchers
  for select using (auth.role() = 'authenticated');

drop policy if exists private_room_watchers_insert on public.private_room_watchers;
create policy private_room_watchers_insert on public.private_room_watchers
  for insert with check (auth.role() = 'authenticated');

drop policy if exists private_room_watchers_delete on public.private_room_watchers;
create policy private_room_watchers_delete on public.private_room_watchers
  for delete using (auth.role() = 'authenticated');

-- ============================================================
-- 13. 定期清理（pg_cron）：对局中 3 分钟无心跳 = 判对方胜；否则释放
-- ============================================================
select cron.schedule('room-release-stale-playing',
  '* * * * *',
  $$
  update public.private_rooms
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

-- seated 房间超时：整房间释放回 open（会一并清掉对局中一方留下的座位）
select cron.schedule('room-release-stale-seats',
  '* * * * *',
  $$
  update public.private_rooms
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
select cron.schedule('room-purge-offline-watchers',
  '* * * * *',
  $$
  delete from public.private_room_watchers w
  where w.last_active_at < now() - interval '3 minutes';
  $$);