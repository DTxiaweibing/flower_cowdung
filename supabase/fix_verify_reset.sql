-- ============================================================
-- 验证：重置后各表剩余数据（用于确认 fix_reset_all.sql 是否真的清空）
-- 若全部为 0，则说明重置成功；Profile 数 = 剩余账号数（应为 0）
-- ============================================================

select 'games'        as tbl, count(*)::text as cnt from public.games
union all
select 'room_members' as tbl, count(*)::text as cnt from public.room_members
union all
select 'rooms'        as tbl, count(*)::text as cnt from public.rooms
union all
select 'lobby_tables' as tbl, count(*)::text as cnt from public.lobby_tables
union all
select 'profiles'     as tbl, count(*)::text as cnt from public.profiles
union all
select 'cowdung_auth_accounts' as tbl, count(*)::text as cnt
  from auth.users
  where email like '%@cowdung.local' or email like '%@cowdung.com'
union all
select 'all_auth_accounts' as tbl, count(*)::text as cnt
  from auth.users;
