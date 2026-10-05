-- 热修复：只重建 public.room_leave 这一个函数
-- 来源：supabase/fix_round_lifecycle.sql 第 305-393 行
-- 修的问题：status 判断用了 "= null"（SQL 里永远为假），
--          导致最后一人离开后房间状态卡在 seated，房间变成不可加入的僵尸房。
-- 用法：Supabase 控制台 -> SQL Editor -> 全选粘贴 -> Run。
-- 可以安全重复执行（create or replace）。

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
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
  -- 同 pvp_leave：winner 存座位，uid 只给 finish_game
  winner_side := case when my_side = 'a' then 'b' when my_side = 'b' then 'a' else null end;
  winner_uid  := case when my_side = 'a' then b_id when my_side = 'b' then a_id else null end;

  -- 对局中本房玩家退出 = 判负
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
      -- 判断 NULL 必须写 is null，写 = null 条件永远为假。
      -- 另外 Postgres 的 SET 右侧全部按【旧行值】求值，所以不能直接问
      -- "两人是不是都空了"——离开者本来就占着一个位（见下方 where）。
      -- "这桌没人了" 的正确判据：每个位要么本来就空，要么就是离开者本人。
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

  -- 只有整桌没人了才清空观战名单（还有人留在房里等对手时，观战位保留）
  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if a_id is null and b_id is null then
    delete from public.private_room_watchers where room_code = code;
  end if;

  delete from public.private_room_watchers where user_id = uid;

  return true;
end;
$$;
