-- ============================================================
-- fix_tables_20.sql
-- 大厅桌数由 30 调整为 20（人人大厅一页放得满）
-- 在 Supabase Dashboard > SQL Editor 整段执行，可重复执行（幂等）
-- ============================================================

-- 1. 删除原有预置桌（T01~T29 以上旧桌）
delete from public.lobby_tables where id like 'T%';

-- 2. 预置 20 张大厅空桌（永久存在，不销毁）
--    id = T01 ~ T20，全部空位、等待状态
insert into public.lobby_tables (id, status)
select 'T' || lpad(i::text, 2, '0'), 'waiting'
from generate_series(1, 20) as i
on conflict (id) do nothing;

-- 3. 校验：应有 20 行
select count(*) as tables_count from public.lobby_tables where id like 'T%';