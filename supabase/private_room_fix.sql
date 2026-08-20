-- ============================================================
-- 私人房间完整修复方案
-- 解决：创建房间后座位显示异常、坐下失败、退出后无法进入等问题
-- ============================================================

-- 1. 修复 sit_room 函数（主要问题）
create or replace function public.sit_room(code char(4))
returns text
language plpgsql security definer set search_path = public
as $$
declare
  uid   uuid := auth.uid();
  seat  text;
  a     uuid;
  b     uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 获取当前房间状态
  select player_a_id, player_b_id into a, b
  from public.rooms 
  where room_code = code 
    and status <> 'finished'
  for update;

  if a is null and b is null then
    -- 空房：坐 A 位（房主）
    update public.rooms
    set player_a_id = uid,
        status = 'waiting',
        last_active_at = now()
    where room_code = code;
    seat := 'a';
    
    -- 添加到成员列表
    insert into public.room_members (room_code, user_id, role)
    values (code, uid, 'player')
    on conflict (room_code, user_id) do nothing;
    
  elsif a is not null and b is null and a <> uid then
    -- A 被占，B 位空：坐 B 位
    update public.rooms
    set player_b_id = uid,
        status = 'playing',
        last_active_at = now()
    where room_code = code;
    seat := 'b';
    
    -- 添加到成员列表
    insert into public.room_members (room_code, user_id, role)
    values (code, uid, 'player')
    on conflict (room_code, user_id) do nothing;
    
  elsif a = uid and b is null then
    -- 已经在 A 位，尝试重复入座
    seat := 'a';
    
  elsif b = uid and a is not null then
    -- 已经在 B 位，尝试重复入座
    seat := 'b';
    
  else
    -- 已满或者是自己
    raise exception 'SEAT_UNAVAILABLE';
  end if;

  return seat;
end;
$$;

-- 2. 修复 join_room 函数，避免过早将状态设为 'playing'
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

  -- 作为玩家加入空位（优先 B 位），但不改变状态
  update public.rooms
  set player_b_id = uid,
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

-- 3. 添加重置房间状态的函数（用于修复异常状态）
create or replace function public.reset_room_status(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  is_host boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 检查是否是房主
  select host_id = uid into is_host
  from public.rooms 
  where room_code = code
    and status <> 'finished';

  if not is_host then
    raise exception 'NOT_HOST';
  end if;

  -- 重置房间状态到等待状态
  update public.rooms
  set status = 'waiting',
      current_turn_id = null,
      last_active_at = now()
  where room_code = code;

  return true;
end;
$$;

-- 4. 添加清理异常房间的函数
create or replace function public.cleanup_stale_rooms()
returns int
language plpgsql security definer set search_path = public
as $$
declare
  cleaned_count int := 0;
begin
  -- 清理超过1小时没有活跃的房间（仅限等待状态）
  delete from public.room_members
  where room_code in (
    select room_code 
    from public.rooms 
    where status = 'waiting' 
      and last_active_at < now() - interval '1 hour'
  );
  
  -- 清理房间
  delete from public.rooms
  where status = 'waiting' 
    and last_active_at < now() - interval '1 hour';
    
  get diagnostics cleaned_count = ROW_COUNT;
  
  return cleaned_count;
end;
$$;

-- 5. 修复 mark_ready 函数，确保正确处理私房
create or replace function public.mark_ready(
  in_table_id text default null,
  in_room_code char(4) default null
)
returns jsonb
language plpgsql security definer set search_path = public
as $$
declare
  uid    uuid := auth.uid();
  gs     jsonb;
  a      uuid;
  b      uuid;
  target text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if in_table_id is not null then
    select game_state, player_a_id, player_b_id into gs, a, b
    from public.lobby_tables where id = in_table_id for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
    target := in_table_id;
  elsif in_room_code is not null then
    select game_state, player_a_id, player_b_id into gs, a, b
    from public.rooms where room_code = in_room_code for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
    target := in_room_code;
  else
    raise exception 'NO_TARGET';
  end if;

  -- 必须是 A 或 B 座玩家
  if uid <> a and uid <> b then
    raise exception 'NOT_PLAYER';
  end if;

  -- 尚未初始化（如先手未写库）：补一份等待中的初始棋盘
  if gs is null or gs = '{}'::jsonb then
    gs := '{"flowers":[1,2,3,4,5,6],"readyA":false,"readyB":false,"status":"waiting","winnerId":null,"moves":[]}'::jsonb;
  end if;

  if uid = a then
    gs := jsonb_set(gs, '{readyA}', 'true'::jsonb);
  else
    gs := jsonb_set(gs, '{readyB}', 'true'::jsonb);
  end if;

  -- 双方都准备好才开始游戏
  if (gs->>'readyA') = 'true' and (gs->>'readyB') = 'true' then
    gs := jsonb_set(gs, '{status}', 'playing'::jsonb);
    gs := jsonb_set(gs, '{turnUserId}', a::text::jsonb); -- A 先手
  end if;

  -- 更新数据库
  if in_table_id is not null then
    update public.lobby_tables
    set game_state = gs,
        last_active_at = now()
    where id = in_table_id;
  elsif in_room_code is not null then
    update public.rooms
    set game_state = gs,
        last_active_at = now()
    where room_code = in_room_code;
  end if;

  return gs;
end;
$$;