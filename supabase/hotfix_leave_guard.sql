-- hotfix_leave_guard.sql
-- 修复「后退出玩家退不出去」：pvp_leave / room_leave 的缺席守卫把
-- 「对手先走、a_id 已为空」的合法场景误判成 TABLE_NOT_FOUND / ROOM_NOT_FOUND，
-- 提前 return，导致后离开者的座位永远清不掉。
--   a_id 为空 且 b_id 也空 才算真的不存在；只有一侧为空是正常状态。
-- 判负逻辑不动：判负只属于「先走的人」（对局中退出走 finish_game），
-- 后走的人进来时 status 已是 seated，判负块天然被跳过。
-- 用法：Supabase SQL Editor -> 全选粘贴 -> Run。可安全重复执行。

-- ===== 1. pvp_leave =====
create or replace function public.pvp_leave(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid         uuid := auth.uid();
  a_id        uuid;
  b_id        uuid;
  cstate      text;
  my_side     text;
  winner_side text;
  winner_uid  uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, status
       into a_id, b_id, cstate
    from public.pvp_tables where id = tid;
  if a_id is null and b_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
  -- winner 必须存「座位」而不是 uid：
  -- 客户端 renderPvpState / buildPvpLogText 判断的是 gs.winner 是否等于自己的
  -- 'a'/'b'，存 uuid 会让赢家和输家都被判成平局，且 finish_game 取不到人。
  -- uid 只用于传给 finish_game。
  winner_side := case when my_side = 'a' then 'b' when my_side = 'b' then 'a' else null end;
  winner_uid  := case when my_side = 'a' then b_id when my_side = 'b' then a_id else null end;

  -- 对局中本桌玩家退出 = 判负（只有先走的那位会进到这里）
  if cstate = 'playing' and my_side is not null and winner_uid is not null then
    update public.pvp_tables
    set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
          'moves', '[]'::jsonb,
          'turn', '',
          'winner', winner_side,
             'status', 'finished',
             'forfeit', true
           ),
         current_turn_id = null,
         turn_deadline_at = null,
         status = 'seated',
         last_active_at = now()
    where id = tid;

    -- 服务端立即结算，不依赖剩下那位还在轮询。
    -- finish_game 内部以 game_state.scored 幂等，客户端随后那次调用是空操作。
    perform public.finish_game(tid, null, 'lobby', winner_uid, uid, null);
  end if;

  update public.pvp_tables
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
      last_a_at   = case when player_a_id = uid then null else last_a_at end,
      last_b_at   = case when player_b_id = uid then null else last_b_at end,
      ready_a = case when player_a_id = uid then false else ready_a end,
      ready_b = case when player_b_id = uid then false else ready_b end,
      status = case when player_a_id is null and player_b_id is null then 'open'
                    else status end,
      -- 这里只在「我走之后一个座位都不剩」时才清空 game_state，
      -- 若对手还在，保留上一条 UPDATE 写好的判负结果
      game_state = case
                     when (player_a_id = uid and player_b_id is null)
                       or (player_b_id = uid and player_a_id is null)
                     then '{}'::jsonb
                     else game_state
                   end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      turn_deadline_at = case when current_turn_id = uid then null else turn_deadline_at end,
      last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;

-- ===== 2. room_leave（char(4)，与线上现行版一致）=====
create or replace function public.room_leave(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid         uuid := auth.uid();
  a_id        uuid;
  b_id        uuid;
  cstate      text;
  my_side     text;
  winner_side text;
  winner_uid  uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, status
       into a_id, b_id, cstate
  from public.private_rooms where room_code = code;
  if a_id is null and b_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
  winner_side := case when my_side = 'a' then 'b' when my_side = 'b' then 'a' else null end;
  winner_uid  := case when my_side = 'a' then b_id when my_side = 'b' then a_id else null end;

  if cstate = 'playing' and my_side is not null and winner_uid is not null then
    update public.private_rooms
    set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
          'moves', '[]'::jsonb,
          'turn', '',
          'winner', winner_side,
             'status', 'finished',
             'forfeit', true
           ),
         current_turn_id = null,
         turn_deadline_at = null,
         status = 'seated',
         last_active_at = now()
    where room_code = code;

    perform public.finish_game(null, code, 'private', winner_uid, uid, null);
  end if;

  update public.private_rooms
  set player_a_id = case when player_a_id = uid then null else player_a_id end,
      player_b_id = case when player_b_id = uid then null else player_b_id end,
      last_a_at   = case when player_a_id = uid then null else last_a_at end,
      last_b_at   = case when player_b_id = uid then null else last_b_at end,
      ready_a = case when player_a_id = uid then false else ready_a end,
      ready_b = case when player_b_id = uid then false else ready_b end,
      status = case when (player_a_id is null or player_a_id = uid)
                     and (player_b_id is null or player_b_id = uid)
                    then 'open' else status end,
      game_state = case
                     when (player_a_id = uid and player_b_id is null)
                       or (player_b_id = uid and player_a_id is null)
                     then '{}'::jsonb
                     else game_state
                   end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      turn_deadline_at = case when current_turn_id = uid then null else turn_deadline_at end,
      last_active_at = now()
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if a_id is null and b_id is null then
    delete from public.private_room_watchers where room_code = code;
  end if;

  delete from public.private_room_watchers where user_id = uid;

  return true;
end;
$$;