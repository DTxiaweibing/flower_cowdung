-- ============================================================
-- 观众/玩家长时间无心跳未清理 修复脚本（幂等，可重复执行）
-- 场景：清理 APP / 杀进程 / 断网，心跳停止，但座位一直保留。
-- 原因：pg_cron 未启用，或同名 job 重复注册/残留冲突，
--       或 job 已注册但每次执行报错（cron.job_run_details 可见）。
-- 修复：启用扩展 → 清旧 job → 重建 → 给出执行历史诊断。
-- 执行：Supabase Dashboard > SQL Editor，全选一次执行。
-- ============================================================

-- ============================================================
-- 0. 确认 pg_cron 扩展已启用（Supabase 默认提供，重跑无害）
-- ============================================================
create extension if not exists pg_cron with schema cron;

-- ============================================================
-- 0.5 表结构迁移：线上 pve_watchers 可能是旧版表，缺 last_active_at
--      列 -> 观众清理 job 每次执行都报「column does not exist」失败。
--      与 pve_tables 的 game_state 补列同理，这里补一次。
-- ============================================================
alter table public.pve_watchers
  add column if not exists last_active_at timestamptz not null default now();

-- ============================================================
-- 1. 清掉已注册的同名 job，避免重复注册 / 残留冲突
--    （cron.unschedule 未命中则返回 0 行，无副作用）
-- ============================================================
select cron.unschedule(jobid) from cron.job
where jobname in ('pve-release-idle-seats', 'pve-purge-offline-watchers');

-- ============================================================
-- 2. 重建：释放 3 分钟无心跳的玩家座位
--    （客户端每 20s 心跳刷新，存活玩家不会被误清）
-- ============================================================
select cron.schedule('pve-release-idle-seats',
  '* * * * *',
  $$
  update public.pve_tables
  set player_id = null, status = 'open',
      watcher_count = (select count(*) from public.pve_watchers w where w.table_id = public.pve_tables.id)
  where last_active_at < now() - interval '3 minutes'
    and status <> 'open';
  $$);

-- ============================================================
-- 3. 重建：清理 3 分钟无心跳的观众观战关系（与玩家同规则）
--    pve_heartbeat 同时刷新 pve_tables.last_active_at 和
--    pve_watchers.last_active_at，存活观众不会被误清。
-- ============================================================
select cron.schedule('pve-purge-offline-watchers',
  '* * * * *',
  $$
  delete from public.pve_watchers w
  where w.last_active_at < now() - interval '3 minutes';
  $$);

-- ============================================================
-- 4. 诊断：job 是否真的在执行？每次跑了没？报错了没？
--    返回到 start_time。若无 job_run_details 记录 = cron 没在跑。
-- ============================================================
select jobid, status, return_message, start_time, end_time
from cron.job_run_details
where start_time > now() - interval '24 hours'
order by start_time desc
limit 30;

-- ============================================================
-- 5. 验证
-- ============================================================
-- 5.1 查看当前已注册的清理 job
select jobid, jobname, schedule, command
from cron.job
where jobname like 'pve-%'
order by jobid;

-- 5.2 手动立即执行一次观众清理，确认语句可跑（返回删除行数 = 0 属正常）
delete from public.pve_watchers w
where w.last_active_at < now() - interval '3 minutes';

-- 5.3 若需立即清掉全部"当前已离线"观众（含注册前老数据），单独执行：
-- delete from public.pve_watchers w
-- where w.last_active_at < now() - interval '1 second';