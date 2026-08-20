-- ============================================================
-- 【开发环境专用】完全重置：清空全部可重建业务数据 + 本应用派生账号
-- 适用场景：数据库里残留大量历史账号/昵称/桌椅/对局，分不清哪些该留。
-- 执行后果：所有玩家昵称、大厅桌子、私房、对局记录、已注册账号全部清空，
--           项目回到"全新状态"。执行后重启 App 重新注册即可。
-- 请在 Supabase 控制台 > SQL Editor 整段执行（顺序固定）。
-- ============================================================

-- 1) 对局记录
delete from public.games;

-- 2) 私房成员 / 私房
delete from public.room_members;
delete from public.rooms;

-- 3) 大厅桌子（含玩家站位）
delete from public.lobby_tables;

-- 4) 玩家档案（昵称/性别）
delete from public.profiles;

-- 5) 本应用派生的 Auth 账号（email 均为 xxx@cowdung.local 或 xxx@cowdung.com）
--    删掉后这些邮箱全部释放，可用任意昵称重新注册。
delete from auth.users where email like '%@cowdung.local' or email like '%@cowdung.com';

-- 6) 收尾：重置自增序号（如有）
select setval(pg_get_serial_sequence('public.games', 'id'), 1, false)
where exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'games' and column_name = 'id');