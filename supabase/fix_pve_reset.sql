-- ============================================================
-- fix_pve_reset.sql
-- 人机大厅（PvE）直接清场复位版
-- 用途：观战/座位被搞乱（一人多桌、一人多观众、又坐又看）时，
--       把所有 PvE 桌子一键复位为空闲，清掉全部观战，并打上
--       「一人一位」约束 + RPC 补丁，今后不会再乱。
-- 效果：
--   1) pve_watchers 全清（观众全部清退）
--   2) pve_tables 全部复位：无人坐、状态=open、watcher_count=0
--   3) 重新打上唯一约束 + 加固 RPC（玩家不可观战/坐下退旧观战/换桌观战迁移）
-- 执行：Supabase Dashboard > SQL Editor 整段执行（幂等，可重复运行）
-- ============================================================

-- ============================================================
-- 1. 清空全部观战（观众席一次性清退）
-- ============================================================
delete from public.pve_watchers;

-- ============================================================
-- 2. 复位全部 PvE 桌：无人坐、空闲、无观众
-- ============================================================
update public.pve_tables
set player_id = null,
    status    = 'open',
    watcher_count = 0,
    last_active_at = now();

-- ============================================================
-- 3. 一人限观一桌：唯一约束（清空后必然成功）
-- ============================================================
alter table public.pve_watchers drop constraint if exists pve_watchers_user_key;
alter table public.pve_watchers add constraint pve_watchers_user_key unique (user_id);

-- ============================================================
-- 4. 加固 RPC：一人一位置
-- ============================================================

-- pve_watch：已在任意桌当玩家 -> 拒观战；换桌观战 -> 先退旧观战
create or replace function public.pve_watch(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if not exists (select 1 from public.pve_tables where id = tid) then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if exists (select 1 from public.pve_tables
             where player_id = uid) then
    raise exception 'ALREADY_PLAYER';
  end if;

  if exists (select 1 from public.pve_watchers where user_id = uid) then
    delete from public.pve_watchers where user_id = uid;
  end if;

  insert into public.pve_watchers (table_id, user_id)
  values (tid, uid)
  on conflict (table_id, user_id) do nothing;

  return true;
end;
$$;

-- pve_sit：坐下当玩家 -> 自动退掉自己的全部观战（一人一位置）
create or replace function public.pve_sit(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  occupied uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_id into occupied from public.pve_tables where id = tid;
  if occupied is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if occupied is not null and occupied <> uid then
    raise exception 'TABLE_OCCUPIED';
  end if;
  if occupied = uid then
    delete from public.pve_watchers where user_id = uid;
    return true;
  end if;

  select exists (select 1 from public.pve_tables
                 where player_id = uid and id <> tid)
  into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pve_tables
  set player_id = uid, status = 'seated', last_active_at = now()
  where id = tid;

  delete from public.pve_watchers where user_id = uid;

  return true;
end;
$$;

-- pve_leave：离桌 -> 同时清掉自己的观战（避免残留交叉角色）
create or replace function public.pve_leave(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set player_id = null, status = 'open', last_active_at = now()
  where id = tid and player_id = uid;

  delete from public.pve_watchers
  where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 5. 校验：全应为 0 行 / 0 条
-- ============================================================
select '剩余观战' as check_name, count(*) as cnt from public.pve_watchers
union all
select '非空闲桌', count(*) from public.pve_tables where status <> 'open'
union all
select '有人坐', count(*) from public.pve_tables where player_id is not null
union all
select '观众数漂移', count(*) from public.pve_tables
  where watcher_count <> (select count(*) from public.pve_watchers w
                          where w.table_id = public.pve_tables.id);