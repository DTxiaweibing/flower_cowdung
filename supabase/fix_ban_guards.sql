-- ============================================================
-- fix_ban_guards.sql
-- 观众/玩家「临时禁入」（banned_ids）触发器落库
--
-- 背景：
--   线上库存在这套 *_ban_guard 触发器/函数，仓库里没有（来源不明）。
--   本文件按线上 pg_get_functiondef / pg_get_triggerdef 原文照抄，
--   让新环境重建后与线上行为一致。幂等，可重复执行。
--
-- 机制：
--   · 三张桌表各有一个 banned_ids uuid[] 列（被禁者的 user_id 列表）。
--   · sit_ban_guard  ：玩家入座时若在 banned_ids 内 → raise 'BANNED_FROM_TABLE'。
--   · watch_ban_guard：观众加入时若在 banned_ids 内 → raise 'BANNED_FROM_TABLE'。
--   · reset_ban_guard：牌桌清空（全员离席）时把 banned_ids 复位为空。
--
-- 照抄线上的现状说明（非理想实现，保持原样）：
--   1) 本文件只有「校验」侧，没有写入 banned_ids 的函数/RPC；
--      线上是否另有写入口未确认。若无写入口，本机制等于空转（不会误伤）。
--   2) sit_ban_guard 读 OLD.banned_ids：INSERT 分支 OLD 为空 → '{}'，
--      即仅 UPDATE 入座才真正校验，这是线上原样行为。
--   3) room_watch_ban_guard 挂在旧表 room_members 上（当前私房观战表
--      是 private_room_watchers），对当前私房观战不生效；旧表存在时才建。
--   4) 现役的「踢出 / 临时禁入」是 fix_kick_watcher.sql 的独立 bans 表，
--      与本文件是两套并行机制。本文件仅为重建线上状态。
--   5) 原函数均未设 security definer / search_path，此处保持原样。
-- ============================================================

-- ------------------------------------------------------------
-- 0. 补齐 banned_ids 列（线上已有；add if not exists 重跑无害）
-- ------------------------------------------------------------
alter table public.pvp_tables    add column if not exists banned_ids uuid[] default '{}'::uuid[];
alter table public.pve_tables    add column if not exists banned_ids uuid[] default '{}'::uuid[];
alter table public.private_rooms add column if not exists banned_ids uuid[] default '{}'::uuid[];

-- ------------------------------------------------------------
-- 1. 函数
-- ------------------------------------------------------------
create or replace function public.pvp_watch_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if NEW.user_id = ANY(coalesce((select banned_ids from public.pvp_tables where id = NEW.table_id), '{}'::uuid[])) then
    raise exception 'BANNED_FROM_TABLE';
  end if;
  return NEW;
end; $function$;

create or replace function public.pve_watch_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if NEW.user_id = ANY(coalesce((select banned_ids from public.pve_tables where id = NEW.table_id), '{}'::uuid[])) then
    raise exception 'BANNED_FROM_TABLE';
  end if;
  return NEW;
end; $function$;

create or replace function public.room_watch_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if TG_OP = 'INSERT' and NEW.role = 'watcher'
     and NEW.user_id = ANY(coalesce((select banned_ids from public.private_rooms where room_code = NEW.room_code), '{}'::uuid[])) then
    raise exception 'BANNED_FROM_TABLE';
  end if;
  return NEW;
end; $function$;

create or replace function public.pvp_sit_ban_guard()
returns trigger
language plpgsql
as $function$
declare b uuid[] := coalesce(OLD.banned_ids, '{}'::uuid[]);
begin
  if (NEW.player_a_id is not null and NEW.player_a_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;
  if (NEW.player_b_id is not null and NEW.player_b_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;
  return NEW;
end; $function$;

create or replace function public.pve_sit_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if (NEW.player_id is not null and NEW.player_id = ANY(coalesce(OLD.banned_ids, '{}'::uuid[]))) then
    raise exception 'BANNED_FROM_TABLE';
  end if;
  return NEW;
end; $function$;

create or replace function public.room_sit_ban_guard()
returns trigger
language plpgsql
as $function$
declare b uuid[] := coalesce(OLD.banned_ids, '{}'::uuid[]);
begin
  if (NEW.player_a_id is not null and NEW.player_a_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;
  if (NEW.player_b_id is not null and NEW.player_b_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;
  return NEW;
end; $function$;

create or replace function public.pvp_reset_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if (OLD.player_a_id is not null or OLD.player_b_id is not null)
     and NEW.player_a_id is null and NEW.player_b_id is null then
    NEW.banned_ids := '{}'::uuid[];
  end if;
  return NEW;
end; $function$;

create or replace function public.pve_reset_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if OLD.player_id is not null and NEW.player_id is null then
    NEW.banned_ids := '{}'::uuid[];
  end if;
  return NEW;
end; $function$;

create or replace function public.room_reset_ban_guard()
returns trigger
language plpgsql
as $function$
begin
  if (OLD.player_a_id is not null or OLD.player_b_id is not null)
     and NEW.player_a_id is null and NEW.player_b_id is null then
    NEW.banned_ids := '{}'::uuid[];
  end if;
  return NEW;
end; $function$;

-- ------------------------------------------------------------
-- 2. 触发器（先 drop if exists 保证幂等）
-- ------------------------------------------------------------
drop trigger if exists pvp_watch_ban_guard on public.pvp_watchers;
create trigger pvp_watch_ban_guard
  before insert on public.pvp_watchers
  for each row execute function public.pvp_watch_ban_guard();

drop trigger if exists pve_watch_ban_guard on public.pve_watchers;
create trigger pve_watch_ban_guard
  before insert on public.pve_watchers
  for each row execute function public.pve_watch_ban_guard();

drop trigger if exists pvp_sit_ban_guard on public.pvp_tables;
create trigger pvp_sit_ban_guard
  before insert or update on public.pvp_tables
  for each row execute function public.pvp_sit_ban_guard();

drop trigger if exists pve_sit_ban_guard on public.pve_tables;
create trigger pve_sit_ban_guard
  before insert or update on public.pve_tables
  for each row execute function public.pve_sit_ban_guard();

drop trigger if exists room_sit_ban_guard on public.private_rooms;
create trigger room_sit_ban_guard
  before insert or update on public.private_rooms
  for each row execute function public.room_sit_ban_guard();

drop trigger if exists pvp_reset_ban_guard on public.pvp_tables;
create trigger pvp_reset_ban_guard
  before update on public.pvp_tables
  for each row execute function public.pvp_reset_ban_guard();

drop trigger if exists pve_reset_ban_guard on public.pve_tables;
create trigger pve_reset_ban_guard
  before update on public.pve_tables
  for each row execute function public.pve_reset_ban_guard();

drop trigger if exists room_reset_ban_guard on public.private_rooms;
create trigger room_reset_ban_guard
  before update on public.private_rooms
  for each row execute function public.room_reset_ban_guard();

-- room_watch_ban_guard 挂在旧表 room_members 上，仅在旧表存在时建（否则跳过）
do $guard$
begin
  if exists (
    select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'room_members' and c.relkind = 'r'
  ) then
    drop trigger if exists room_watch_ban_guard on public.room_members;
    create trigger room_watch_ban_guard
      before insert or update on public.room_members
      for each row execute function public.room_watch_ban_guard();
  else
    raise notice 'room_members 不存在，跳过 room_watch_ban_guard（旧表机制）';
  end if;
end $guard$;
