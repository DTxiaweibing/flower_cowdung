-- ============================================================
-- 观战人数无上限补丁
-- 说明：私房观战 join_room_watcher、大厅观战 watch_lobby_table
--       均不设人数上限（观众再多也不会"满"）。
-- 执行方式：Supabase Dashboard > SQL Editor，一次性全部执行，可重复执行（幂等）。
-- ============================================================

-- 1. 私房观战：仅观战，不限人数（每次加入在 room_members 写入 watcher 记录）
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

-- 2. 大厅观战：计数 +1，不限人数
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

-- 3. 大厅退出观战：计数 -1（不低于 0）
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
  set watcher_count = greatest(0, watcher_count - 1),
      last_active_at = now()
  where id = tid;

  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;
  return true;
end;
$$;