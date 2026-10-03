-- ============================================================
-- 14. RPC：sit_room（私房入座）
--   空房坐 A、A 被占坐 B；返回 'a'/'b'，null 表示失败或已满
-- ============================================================
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
    
  else
    -- 已满或者是自己
    raise exception 'SEAT_UNAVAILABLE';
  end if;

  return seat;
end;
$$;