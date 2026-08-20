-- ============================================================
-- 鲜花与牛粪 - PvE 棋局同步（观战重放）
-- 在 Supabase Dashboard > SQL Editor 执行（幂等，可重复运行）。
-- 需求：观众进入某桌后，能看到「玩家 vs 电脑」的每一步落子、当前棋盘、
--       当前轮到谁、胜负结果，以及真实玩家是谁（读 pve_tables.player_id / profiles）。
-- 设计：
--   pve_tables 新增 game_state jsonb 列，存完整棋局：
--     {
--       "flowers": [1,2,3,4,5,6],        // 每排剩余数量（0=牛粪排）
--       "turn": "player" | "computer",   // 当前该谁走
--       "moves": [{side,row,count}, ...],// 落子记录（按时间序）
--       "winner": null|"player"|"computer",
--       "status": "ongoing" | "finished"
--     }
--   玩家的设备每落一步（玩家步 + 电脑步）就调用 pve_report_state 整包覆盖；
--   观众设备拉取该桌时读取 game_state 重放。
--   只有该桌的玩家本人能写（RPC 内校验 player_id = auth.uid()）。
-- ============================================================

-- ============================================================
-- 1. pve_tables 新增 game_state 列
-- ============================================================
alter table public.pve_tables
  add column if not exists game_state jsonb not null default '{}'::jsonb;

-- ============================================================
-- 2. RPC：pve_report_state（玩家本人整包上报棋局状态）
-- ============================================================
create or replace function public.pve_report_state(tid text, state jsonb)
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
  set game_state = coalesce(state, '{}'::jsonb),
      last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;
  return true;
end;
$$;

-- ============================================================
-- 3. pve_sit：新玩家入座时清空上一局残留的棋局
--    修复：原脚本 `if occupied is null` 会把"空桌"误判为"表不存在"，
--    因为空桌的 player_id 本就是 NULL。改为先用 exists 判表是否存在。
-- ============================================================
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

  if not exists (select 1 from public.pve_tables where id = tid) then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  select player_id into occupied from public.pve_tables where id = tid;

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
  set player_id = uid, status = 'seated', game_state = '{}'::jsonb, last_active_at = now()
  where id = tid;

  delete from public.pve_watchers where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 4. pve_leave：离桌时清空棋局
-- ============================================================
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
  set player_id = null, status = 'open', game_state = '{}'::jsonb, last_active_at = now()
  where id = tid and player_id = uid;

  delete from public.pve_watchers
  where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 5. pve_start：开局（不清 game_state，避免与玩家开机整包上报竞争；
--    新玩家入座已由 pve_sit 清空上一局残留）
-- ============================================================
create or replace function public.pve_start(tid text)
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
  set status = 'playing', last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;
  return true;
end;
$$;

-- ============================================================
-- 6. pve_end：对局结束转为 seated，保留棋盘供观众看结局
--    （不清 game_state；再次开局由 pve_start 清零）
-- ============================================================
create or replace function public.pve_end(tid text)
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
  set status = 'seated', last_active_at = now()
  where id = tid and player_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 7. 校验
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname in ('pve_report_state','pve_sit','pve_leave','pve_start','pve_end')
order by proname;