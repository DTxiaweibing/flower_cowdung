-- ============================================================
-- fix_watcher_count_recursion.sql
-- 修复：有观众时玩家无法离座（人机 / 人人 / 私密房 三种桌子全中招）
--
-- 现象：
--   桌子有观众时，玩家点「离开棋局」以为退出去了，其实座位没释放；
--   于是观众看到玩家一直在，观众也永远等不到「桌空 -> 被踢」。
--   没有观众时一切正常。
--
-- 根因（同命令重复修改同一行）：
--   purge_table_on_empty 是 pvp_tables / private_rooms / pve_tables 的
--   BEFORE UPDATE 触发器。桌子归零时它 delete 本桌 watchers 行。
--   watchers 表上的 after delete 触发器 *_watcher_dec() 会回头
--   update 同一张父表（pvp_tables / private_rooms / pve_tables）的同一行，
--   而这一行正是外层离座 UPDATE 正在改的行。
--   PostgreSQL 不允许在同一命令里二次修改同一行，直接报
--     ERROR: tuple to be updated was already modified by an
--            operation triggered by the current command
--   整条离座语句回滚 => 座位没释放 => 观众也踢不掉。
--   无观众时 delete 命中 0 行，dec 不触发，所以只有「有观众」才炸。
--
-- 修法（两步 + 一步收尾，幂等）：
--   1) 归零分支里由触发器自己把 new.watcher_count 置 0（本应就是 0），
--      这样 dec 触发的计数更新在语义上已是多余。
--   2) 三个 *_watcher_dec()/inc() 在被「别的触发器连带触发」时
--      （pg_trigger_depth() > 1）直接返回，不再回头 update 父表，
--      从根上消除同命令重复改同一行。
--   3) 把历史残留歪掉的 watcher_count 对齐真实记录数。
--
-- 执行位置：Supabase Dashboard > SQL Editor 整段执行。
-- 注意：若之后又去重跑 pvp_tables.sql / private_rooms.sql / pve_tables.sql /
--       run_all*.sql，会把 *_watcher_*() 覆盖回旧版，需要重跑本文件。
-- ============================================================


-- ============================================================
-- 1) purge_table_on_empty：归零时顺手把 watcher_count 置 0
--    （其余逻辑与原 fix_round_lifecycle.sql 保持一致）
-- ============================================================

create or replace function public.purge_table_on_empty()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_scope   text;
  v_empty   boolean := false;   -- 整桌已无玩家
  v_vacated boolean := false;   -- 有座位由有人变成空，但还有人留
begin
  -- ---- 算 scope，并判断归零 / 腾位 ----
  if TG_ARGV[0] = 'pve' then
    v_scope := 'pve:' || new.id;
    if new.player_id is null then
      v_empty := true;
    end if;

  elsif TG_ARGV[0] = 'pvp' then
    v_scope := 'pvp:' || new.id;
    if new.player_a_id is null and new.player_b_id is null then
      v_empty := true;
    elsif (old.player_a_id is not null and new.player_a_id is null)
       or (old.player_b_id is not null and new.player_b_id is null) then
      v_vacated := true;
    end if;

  else                                   -- room
    v_scope := 'room:' || rtrim(new.room_code::text);
    if new.player_a_id is null and new.player_b_id is null then
      v_empty := true;
    elsif (old.player_a_id is not null and new.player_a_id is null)
       or (old.player_b_id is not null and new.player_b_id is null) then
      v_vacated := true;
    end if;
  end if;

  -- ---- 第一层：有人走、还有人留 => 本轮结束，清聊天，其余保留 ----
  if v_vacated and not v_empty then
    delete from public.chat_messages where table_id = v_scope;
    return new;
  end if;

  -- ---- 无关更新：直接放行 ----
  if not v_empty then
    return new;
  end if;

  -- ---- 第二层：整桌归零 => 彻底清场 ----
  delete from public.chat_messages where table_id = v_scope;

  new.game_state := '{}'::jsonb;

  -- ★ 新增：本桌已归零，观众数必为 0。在这里写死，
  --   后面 delete watchers 连带触发的 *_watcher_dec() 只管「跳过」即可。
  new.watcher_count := 0;

  if TG_ARGV[0] = 'pve' then
    delete from public.pve_watchers where table_id = new.id;
  else
    new.current_turn_id := null;
    new.turn_deadline_at := null;
    if TG_ARGV[0] = 'pvp' then
      delete from public.pvp_watchers where table_id = new.id;
    else
      delete from public.private_room_watchers where room_code = new.room_code;
    end if;
  end if;

  return new;
end;
$$;


-- ============================================================
-- 2) 计数触发器：被别的触发器连带触发时不再回头改父表
--    （顶层直接增删观众时 depth = 1，照常维护计数）
-- ============================================================

create or replace function public.pvp_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if pg_trigger_depth() > 1 then
    return new;   -- 连带触发：父表正被外层命令修改，不能二次改同一行
  end if;
  update public.pvp_tables
  set watcher_count = watcher_count + 1
  where id = new.table_id;
  return new;
end;
$$;

create or replace function public.pvp_watcher_dec()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if pg_trigger_depth() > 1 then
    return old;   -- 连带触发：purge 已把 watcher_count 置 0，跳过即可
  end if;
  update public.pvp_tables
  set watcher_count = greatest(0, watcher_count - 1)
  where id = old.table_id;
  return old;
end;
$$;

create or replace function public.private_room_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if pg_trigger_depth() > 1 then
    return new;
  end if;
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
  if pg_trigger_depth() > 1 then
    return old;
  end if;
  update public.private_rooms
  set watcher_count = greatest(0, watcher_count - 1)
  where room_code = old.room_code;
  return old;
end;
$$;

create or replace function public.pve_watcher_inc()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if pg_trigger_depth() > 1 then
    return new;
  end if;
  update public.pve_tables
  set watcher_count = watcher_count + 1
  where id = new.table_id;
  return new;
end;
$$;

create or replace function public.pve_watcher_dec()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if pg_trigger_depth() > 1 then
    return old;
  end if;
  update public.pve_tables
  set watcher_count = greatest(0, watcher_count - 1)
  where id = old.table_id;
  return old;
end;
$$;


-- ============================================================
-- 3) 收尾：把历史残留歪掉的 watcher_count 对齐真实记录数
-- ============================================================

update public.pvp_tables t
set watcher_count = (
      select count(*) from public.pvp_watchers w where w.table_id = t.id)
where t.watcher_count is distinct from (
      select count(*) from public.pvp_watchers w where w.table_id = t.id);

update public.private_rooms r
set watcher_count = (
      select count(*) from public.private_room_watchers w
      where w.room_code = r.room_code)
where r.watcher_count is distinct from (
      select count(*) from public.private_room_watchers w
      where w.room_code = r.room_code);

update public.pve_tables t
set watcher_count = (
      select count(*) from public.pve_watchers w where w.table_id = t.id)
where t.watcher_count is distinct from (
      select count(*) from public.pve_watchers w where w.table_id = t.id);


-- ============================================================
-- 4) 验证：下面这条应该【成功、不再报错】
-- ============================================================
-- begin;
-- update public.pvp_tables
-- set player_a_id = null, player_b_id = null
-- where id = (select table_id from public.pvp_watchers limit 1);
-- rollback;
