-- ============================================================
-- fix_kick_watcher.sql —— 观众「查看资料 / 踢出 + 临时禁入」
--
-- 覆盖范围：人人大厅（pvp_tables / pvp_watchers）
--           私密房间（private_rooms / private_room_watchers）
-- 人机（pve_*）本次不做。
--
-- 背景与动机：
--   1) 原来根本没有「踢人」能力。pvp_unwatch / room_unwatch 的删除条件
--      把 user_id = auth.uid() 写死在 where 里，只能退自己，踢不了别人。
--   2) 老函数 host_kick 动的是早已废弃的 rooms / room_members 老表，
--      客户端从未调用，等于不存在。
--   3) 【安全漏洞】三张 watchers 表的 delete 策略是
--      `for delete using (auth.role() = 'authenticated')`，
--      任何登录用户都能直接 DELETE 任意桌任意观众的观战记录。
--      如果只加带鉴权的 RPC 而不收紧这条 policy，鉴权等于白写：
--      绕开 RPC 直接发 REST DELETE 照样能踢。本文件一并收紧。
--
-- 设计要点：
--   · 禁入记录放「独立的 bans 表」而不是加在 watchers 表上。
--     原因：watchers 表有行就会触发 watcher_inc/dec 维护 watcher_count，
--     把被踢的人留在表里再打个标记会让计数错乱、也会让观众列表出现幽灵条目。
--     独立 bans 表 + 真删 watchers 行，两边都干净，计数由触发器自动回正。
--   · bans 表随桌级联删除（on delete cascade），桌没了禁入自动失效。
--   · banned_until 过期即视为无禁入（判断一律用 banned_until > now()），
--     残留的过期行由末尾 cron 清理。
--
-- 可重复执行（幂等）。
-- ============================================================


-- ============================================================
-- 1. 禁入表
-- ============================================================

-- 人人大厅
create table if not exists public.pvp_watcher_bans (
  table_id     text not null references public.pvp_tables (id) on delete cascade,
  user_id      uuid not null references public.profiles (id) on delete cascade,
  banned_until timestamptz not null,
  created_by   uuid references public.profiles (id) on delete set null,
  created_at   timestamptz not null default now(),
  primary key (table_id, user_id)
);

create index if not exists pvp_watcher_bans_user_idx
  on public.pvp_watcher_bans (user_id);

-- 私密房间
create table if not exists public.private_room_watcher_bans (
  room_code    text not null references public.private_rooms (room_code) on delete cascade,
  user_id      uuid not null references public.profiles (id) on delete cascade,
  banned_until timestamptz not null,
  created_by   uuid references public.profiles (id) on delete set null,
  created_at   timestamptz not null default now(),
  primary key (room_code, user_id)
);

create index if not exists private_room_watcher_bans_user_idx
  on public.private_room_watcher_bans (user_id);


-- ============================================================
-- 2. 踢人 RPC：只有本桌 A/B 玩家可踢，且只能踢观众
--    ban_minutes = 0 表示只踢出、不禁入（当下仍会写一条 banned_until=now()
--    的记录，供客户端区分「被踢」与「自己退出了」；该记录不阻断重新观战）
-- ============================================================

create or replace function public.pvp_kick_watcher(
  tid text, target uuid, ban_minutes integer
)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  mins integer;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;
  if target is null then
    raise exception 'INVALID_TARGET';
  end if;

  select player_a_id, player_b_id into a_id, b_id
  from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  -- 鉴权：必须是本桌玩家（A 或 B）。观众之间、观众对观众一律拒绝。
  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  if target = uid then
    raise exception 'CANNOT_KICK_SELF';
  end if;

  -- 目标必须「当前确实是本桌观众」，否则不允许踢
  -- （顺带挡住踢玩家、踢不在场的人、重复踢）
  if not exists (select 1 from public.pvp_watchers
                 where table_id = tid and user_id = target) then
    raise exception 'NOT_A_WATCHER';
  end if;

  -- 禁入时长夹到 [0, 1440] 分钟，防止传进来负数或超大值
  mins := least(greatest(coalesce(ban_minutes, 0), 0), 1440);

  -- 记录禁入（upsert：重复踢人刷新时长而不是报主键冲突）
  insert into public.pvp_watcher_bans (table_id, user_id, banned_until, created_by)
  values (tid, target, now() + make_interval(mins => mins), uid)
  on conflict (table_id, user_id) do update
    set banned_until = excluded.banned_until,
        created_by   = excluded.created_by,
        created_at   = now();

  -- 移出观众；pvp_watcher_dec 触发器自动把 watcher_count 减 1
  delete from public.pvp_watchers where table_id = tid and user_id = target;

  return true;
end;
$$;

create or replace function public.room_kick_watcher(
  code char(4), target uuid, ban_minutes integer
)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  mins integer;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;
  if target is null then
    raise exception 'INVALID_TARGET';
  end if;

  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  if target = uid then
    raise exception 'CANNOT_KICK_SELF';
  end if;

  if not exists (select 1 from public.private_room_watchers
                 where room_code = code and user_id = target) then
    raise exception 'NOT_A_WATCHER';
  end if;

  mins := least(greatest(coalesce(ban_minutes, 0), 0), 1440);

  insert into public.private_room_watcher_bans (room_code, user_id, banned_until, created_by)
  values (code, target, now() + make_interval(mins => mins), uid)
  on conflict (room_code, user_id) do update
    set banned_until = excluded.banned_until,
        created_by   = excluded.created_by,
        created_at   = now();

  -- private_room_watcher_dec 触发器自动把 watcher_count 减 1
  delete from public.private_room_watchers
  where room_code = code and user_id = target;

  return true;
end;
$$;


-- ============================================================
-- 3. 观众自查：我在本桌还剩多少秒禁入
--    返回 > 0 = 我被踢了且仍在禁入期（客户端据此自动退回大厅）
--    返回 0   = 没有禁入（时间戳算术全放服务端，客户端不必解析 ISO8601）
-- ============================================================

create or replace function public.pvp_my_ban_seconds(tid text)
returns integer
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  left_sec integer;
begin
  if uid is null then
    return 0;
  end if;
  select greatest(0, floor(extract(epoch from (banned_until - now()))))::integer
    into left_sec
  from public.pvp_watcher_bans
  where table_id = tid and user_id = uid;
  if left_sec is null then
    return 0;
  end if;
  return left_sec;
end;
$$;

create or replace function public.room_my_ban_seconds(code char(4))
returns integer
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  left_sec integer;
begin
  if uid is null then
    return 0;
  end if;
  select greatest(0, floor(extract(epoch from (banned_until - now()))))::integer
    into left_sec
  from public.private_room_watcher_bans
  where room_code = code and user_id = uid;
  if left_sec is null then
    return 0;
  end if;
  return left_sec;
end;
$$;


-- ============================================================
-- 4. 重定义观战入口：加入禁入校验
--    禁入期内重新点「观战」会被 BANNED_FROM_TABLE 挡住。
--    （ban_minutes=0 写出的 banned_until=now() 记录不会命中 > now()，
--      所以「只踢不ban」不会误伤重新观战。）
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

  -- 禁入校验
  if exists (select 1 from public.pvp_watcher_bans
             where table_id = tid and user_id = uid and banned_until > now()) then
    raise exception 'BANNED_FROM_TABLE';
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

  -- 禁入校验
  if exists (select 1 from public.private_room_watcher_bans
             where room_code = code and user_id = uid and banned_until > now()) then
    raise exception 'BANNED_FROM_ROOM';
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


-- ============================================================
-- 5. 收紧 watchers 表的 DELETE 策略（安全修复）
--    原来 `auth.role() = 'authenticated'` = 任何登录用户可删任意人的观战记录。
--    改为只能删自己的；踢人一律走上面带鉴权的 security definer RPC。
-- ============================================================

drop policy if exists pvp_watchers_delete on public.pvp_watchers;
create policy pvp_watchers_delete on public.pvp_watchers
  for delete using (auth.uid() = user_id);

drop policy if exists private_room_watchers_delete on public.private_room_watchers;
create policy private_room_watchers_delete on public.private_room_watchers
  for delete using (auth.uid() = user_id);


-- ============================================================
-- 6. bans 表 RLS
--    只允许读自己的禁入记录（客户端就是靠它判断「我被踢了」）。
--    不开 insert/delete —— 写入只能经由 security definer 的踢人 RPC。
-- ============================================================

alter table public.pvp_watcher_bans enable row level security;
alter table public.private_room_watcher_bans enable row level security;

drop policy if exists pvp_watcher_bans_select on public.pvp_watcher_bans;
create policy pvp_watcher_bans_select on public.pvp_watcher_bans
  for select using (auth.uid() = user_id);

drop policy if exists private_room_watcher_bans_select on public.private_room_watcher_bans;
create policy private_room_watcher_bans_select on public.private_room_watcher_bans
  for select using (auth.uid() = user_id);


-- ============================================================
-- 7. 授权：只给已登录用户执行权，anon 一律拒绝
--    （函数体内的 auth.uid() 判空是第二道防线，这里是第一道）
-- ============================================================

do $$
begin
  execute 'revoke all on function public.pvp_kick_watcher(text, uuid, integer) from anon';
  execute 'revoke all on function public.room_kick_watcher(character, uuid, integer) from anon';
  execute 'revoke all on function public.pvp_my_ban_seconds(text) from anon';
  execute 'revoke all on function public.room_my_ban_seconds(character) from anon';
end $$;

grant execute on function public.pvp_kick_watcher(text, uuid, integer) to authenticated;
grant execute on function public.room_kick_watcher(character, uuid, integer) to authenticated;
grant execute on function public.pvp_my_ban_seconds(text) to authenticated;
grant execute on function public.room_my_ban_seconds(character) to authenticated;


-- ============================================================
-- 8. 清理过期禁入记录（纯卫生，逻辑判断本来就只看 banned_until > now()）
--    包在 DO + exception 里：pg_cron 不可用时静默跳过，绝不让整个迁移失败。
-- ============================================================

do $$
begin
  begin
    perform cron.unschedule('pvp-purge-expired-watcher-bans');
  exception when others then
    null;  -- 任务不存在，忽略
  end;

  perform cron.schedule('pvp-purge-expired-watcher-bans', '* * * * *', $cron$
    delete from public.pvp_watcher_bans where banned_until <= now();
    delete from public.private_room_watcher_bans where banned_until <= now();
  $cron$);
exception when others then
  null;  -- 没装 pg_cron 就跳过，不影响功能
end;
$$;
