-- ============================================================
-- fix_pve_cleanup.sql
-- 人机大厅（PvE）脏数据清理 + 一人一位强制
-- 背景：早期版本没有「一人限一桌 / 一人限一观众」约束时，出现过
--       同一个账号：①同时坐多张桌子 ②同时在多张桌子当观众
--       ③一边坐着当玩家一边还在别的桌当观众。本次脚本负责：
--   A. 扫描残留脏数据（先看）
--   B. 清理（一人只保留一个位置：优先保留玩家座位，多余观战/座位清掉）
--   C. 重算每桌 watcher_count
--   D. 重新打上约束（pve_watchers.unique(user_id)），并补齐 RPC 漏洞：
--       坐着当玩家的人不能再观战；坐下玩游戏时自动退掉旧观战
-- 位置：Supabase Dashboard > SQL Editor 整段执行（幂等，可重复运行）
-- ============================================================

-- ============================================================
-- A. 预览：先看看现在乱成什么样（只查询，不删除）
-- ============================================================

-- A1. 一人坐多桌（同一 player_id 出现在多张 pve_tables）
select t.player_id, count(*) as tables_sat
from public.pve_tables t
where t.player_id is not null
group by t.player_id
having count(*) > 1;

-- A2. 一人在多张桌当观众（同一 user_id 多条观战记录）
select w.user_id, count(*) as tables_watched
from public.pve_watchers w
group by w.user_id
having count(*) > 1;

-- A3. 一边坐着当玩家、一边还在别桌当观众（交叉角色）
select distinct w.user_id
from public.pve_watchers w
where exists (select 1 from public.pve_tables t
              where t.player_id = w.user_id);

-- A4. 观战人数与实际记录不一致的桌（watcher_count 漂移）
select t.id, t.watcher_count,
       (select count(*) from public.pve_watchers w where w.table_id = t.id) as real_watchers
from public.pve_tables t
where t.watcher_count <> (select count(*) from public.pve_watchers w where w.table_id = t.id);

-- A5. 有人坐但状态不是 seated/playing、或没人坐状态却不对的桌
select t.id, t.status, t.player_id
from public.pve_tables t
where (t.player_id is null and t.status <> 'open')
   or (t.player_id is not null and t.status = 'open');

-- ============================================================
-- B. 清理：一人只留一个位置（玩家优先，日期新的优先）
-- ============================================================

-- B1. 同一个人重复当观众 -> 每 user 只保留 joined_at 最新的一条
delete from public.pve_watchers w
using public.pve_watchers w2
where w.user_id = w2.user_id
  and w.joined_at < w2.joined_at;

-- B2. 同一个人坐多桌 -> 每 player 只保留 last_active_at 最新的一桌
delete from public.pve_tables t
using public.pve_tables t2
where t.player_id is not null
  and t.player_id = t2.player_id
  and t.last_active_at < t2.last_active_at;

-- B3. 交叉角色 -> 已作为玩家入座的用户，删掉其全部观战记录（玩家优先）
delete from public.pve_watchers w
where exists (select 1 from public.pve_tables t
              where t.player_id = w.user_id);

-- B4. 清掉「该桌已没有人坐」的观众（防止站在空桌当观众）
delete from public.pve_watchers w
where not exists (select 1 from public.pve_tables t
                  where t.id = w.table_id and t.player_id is not null);

-- ============================================================
-- C. 重算 watcher_count（以真实记录为准），并对齐 status
-- ============================================================

update public.pve_tables t
set watcher_count = (select count(*) from public.pve_watchers w where w.table_id = t.id);

-- 没人坐 -> open；有人坐 -> seated（若正 playing 由 pve_start 维持，这里不动 playing）
update public.pve_tables
set status = 'open'
where player_id is null and status <> 'open';

update public.pve_tables
set status = 'seated'
where player_id is not null and status = 'open';

-- ============================================================
-- D. 重新打上约束：一人最多观一桌（B 段清完冲突后才能成功）
-- ============================================================
alter table public.pve_watchers drop constraint if exists pve_watchers_user_key;
alter table public.pve_watchers add constraint pve_watchers_user_key unique (user_id);

-- ============================================================
-- E. 补齐 RPC 漏洞：玩家不可再观战；坐下自动退旧观战
-- ============================================================

-- pve_watch：已在任意桌坐着当玩家 -> 拒绝观战（而不是只挡同一桌）
--   已在别的桌观战 -> 换桌（先删旧观战再坐新桌，一人仍只占一个观众位）
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

  -- 已在任意桌作为玩家入座 -> 不能再观战（一人一位置）
  if exists (select 1 from public.pve_tables
             where player_id = uid) then
    raise exception 'ALREADY_PLAYER';
  end if;

  -- 换桌观战：先退旧观战，再坐新桌（唯一约束保证一人一个观众位）
  if exists (select 1 from public.pve_watchers where user_id = uid) then
    delete from public.pve_watchers where user_id = uid;
  end if;

  -- 已在该桌当观众 -> 幂等返回
  insert into public.pve_watchers (table_id, user_id)
  values (tid, uid)
  on conflict (table_id, user_id) do nothing;

  return true;
end;
$$;

-- pve_sit：入座成功后自动退掉该用户的全部观战（一人一位置）
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
    return true; -- 已坐在这桌，幂等
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

-- pve_leave：离桌同时清掉该用户的观战（避免残留交叉角色）
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
-- F. 收尾校验：执行后应全部为 0 行 / 无残留
-- ============================================================
select '一人多桌' as check_name, count(*) from (
  select player_id from public.pve_tables
  where player_id is not null group by player_id having count(*) > 1
) x
union all
select '一人多观众', count(*) from (
  select user_id from public.pve_watchers group by user_id having count(*) > 1
) x
union all
select '玩家兼观众', count(*) from (
  select distinct w.user_id from public.pve_watchers w
  where exists (select 1 from public.pve_tables t where t.player_id = w.user_id)
) x
union all
select '观众数漂移', count(*) from (
  select t.id from public.pve_tables t
  where t.watcher_count <> (select count(*) from public.pve_watchers w where w.table_id = t.id)
) x;