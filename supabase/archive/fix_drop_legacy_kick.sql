-- 清理旧版「踢观众」机制（已被 fix_kick_watcher.sql 的 bans 表机制取代）
-- 背景：线上残留 kick_watcher / reset_watcher_kick 两个函数，靠 pvp_watchers /
--       pve_watchers / room_members 上的 kicked 列 + 三条 RESTRICTIVE 策略
--       （*_no_kicked_rejoin）实现。App 端只调用 pvp_kick_watcher / room_kick_watcher，
--       上述对象均为死代码，本篇将其彻底移除。
-- 幂等，可整段重复执行。执行后建议：notify pgrst, 'reload schema';

begin;

-- 1. 旧函数（其函数体引用 kicked 列，必须先删）
drop function if exists public.kick_watcher(text, text, uuid);
drop function if exists public.reset_watcher_kick(text, text);

-- 2. 旧 RESTRICTIVE 策略（依赖 kicked 列，必须先于列删除）
drop policy if exists "pvp_watchers_no_kicked_rejoin" on public.pvp_watchers;
drop policy if exists "pve_watchers_no_kicked_rejoin" on public.pve_watchers;
drop policy if exists "room_members_no_kicked_rejoin" on public.room_members;

-- 3. 旧 kicked 列
alter table if exists public.pvp_watchers drop column if exists kicked;
alter table if exists public.pve_watchers drop column if exists kicked;
alter table if exists public.room_members drop column if exists kicked;

commit;
