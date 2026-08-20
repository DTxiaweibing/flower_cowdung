-- ============================================================
-- 增量修复：在线对局「双方准备好了才开局」
-- 在 Supabase SQL Editor 中执行本文件即可（只新增 RPC，幂等）。
-- 已同步进 schema.sql / run_all.sql。
-- ============================================================

create or replace function public.mark_ready(
  in_table_id text default null,
  in_room_code char(4) default null
)
returns jsonb
language plpgsql security definer set search_path = public
as $$
declare
  uid    uuid := auth.uid();
  gs     jsonb;
  a      uuid;
  b      uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  if in_table_id is not null then
    select game_state, player_a_id, player_b_id into gs, a, b
    from public.lobby_tables where id = in_table_id for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
  elsif in_room_code is not null then
    select game_state, player_a_id, player_b_id into gs, a, b
    from public.rooms where room_code = in_room_code for update;
    if gs is null then raise exception 'NOT_FOUND'; end if;
  else
    raise exception 'NO_TARGET';
  end if;

  -- 必须是 A 或 B 座玩家
  if uid <> a and uid <> b then
    raise exception 'NOT_PLAYER';
  end if;

  -- 尚未初始化（如先手未写库）：补一份等待中的初始棋盘
  if gs is null or gs = '{}'::jsonb then
    gs := '{"flowers":[1,2,3,4,5,6],"readyA":false,"readyB":false,"status":"waiting","winnerId":null,"moves":[]}'::jsonb;
  end if;

  if uid = a then
    gs := jsonb_set(gs, '{readyA}', 'true'::jsonb);
  else
    gs := jsonb_set(gs, '{readyB}', 'true'::jsonb);
  end if;

  -- 双方都就绪 -> 开局（先手 = A 房主）；否则仍等待
  if (gs->>'readyA') = 'true' and (gs->>'readyB') = 'true' then
    gs := jsonb_set(gs, '{status}', '"playing"'::jsonb);
    gs := jsonb_set(gs, '{turnUserId}', to_jsonb(a));
  else
    gs := jsonb_set(gs, '{status}', '"waiting"'::jsonb);
  end if;

  if in_table_id is not null then
    update public.lobby_tables set game_state = gs, last_active_at = now() where id = in_table_id;
  else
    update public.rooms set game_state = gs, last_active_at = now() where room_code = in_room_code;
  end if;

  return gs;
end;
$$;
