-- ============================================================
-- 鲜花与牛粪 - PvE 玩家中途退出 = 判负（原子写库）
-- 在 Supabase Dashboard > SQL Editor 执行（幂等，可重复运行）。
-- 规则（开发文档第 25.4/25.7 条）：
--   玩家对局中退出：先判定该玩家输（game_state 置 finished + winner=computer），
--   再释放座位（player_id=null, status='open'），并清空该用户全部观战关系。
--   一步 RPC 原子完成，保证大厅立即可见空座。
-- ============================================================

create or replace function public.pve_forfeit(tid text, state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  final_state jsonb := coalesce(state, '{}'::jsonb);
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 覆盖为已结束 + 电脑胜（中途退出 = 判负）
  final_state := final_state
    || jsonb_build_object('status', 'finished', 'winner', 'computer', 'turn', '');

  update public.pve_tables
  set status = 'open',
      player_id = null,
      game_state = final_state,
      last_active_at = now()
  where id = tid and player_id = uid;

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  -- 退出即清掉本人所有观战关系（含其他桌）
  delete from public.pve_watchers
  where user_id = uid;

  return true;
end;
$$;

-- ============================================================
-- 校验
-- ============================================================
select proname,
       pg_get_function_identity_arguments(p.oid) as args
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and proname = 'pve_forfeit';
