-- ============================================================
-- 积分 / 排名 / 军衔 体系（最终版，幂等、只更新真实存在的表）
--   规则：人机 赢+1 输0；人人(pvp/lobby) 赢+5 输-1；私密(private) 赢+10 输-2
--   排名：score desc, wins desc, score_reached_at asc（同分先到者靠前）
--   军衔：客户端按分数映射（每 200 分一档）
--   说明：仓库里多份历史脚本都定义过 finish_game 且引用已删除的 lobby_tables/rooms，
--        本脚本先 DROP 再重建，确保线上函数为此版本。
-- ============================================================

do $$
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema='public' and table_name='profiles' and column_name='score_reached_at'
  ) then
    alter table public.profiles add column score_reached_at timestamptz not null default now();
  end if;
end $$;

-- 先清理可能冲突的旧函数（含不同签名的重载），避免 404 / 调错版本
drop function if exists public.finish_game(text, char(4), text, uuid, uuid, jsonb) cascade;
drop function if exists public.pve_finish(uuid, boolean) cascade;
drop function if exists public.get_ranking(int) cascade;
drop function if exists public.get_user_rank(uuid) cascade;

-- 结算：人人/私密。用 game_state.scored 做每局幂等，重开后会重置，不会漏算/重算
create or replace function public.finish_game(
  in_table_id text default null,
  in_room_code char(4) default null,
  in_room_type text default 'lobby',
  in_winner_id uuid default null,
  in_loser_id  uuid default null,
  in_moves jsonb default null
)
returns uuid
language plpgsql security definer set search_path = public
as $$
declare
  gid        uuid;
  delta_win  int := case when in_room_type = 'private' then 10 else 5 end;
  delta_lose int := case when in_room_type = 'private' then -2 else -1 end;
  claimed    boolean := false;
begin
  if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
  if in_winner_id is null or in_loser_id is null then raise exception 'INVALID_RESULT'; end if;
  if in_winner_id = in_loser_id then raise exception 'INVALID_RESULT'; end if;

  if in_room_type = 'private' and in_room_code is not null then
    update public.private_rooms
       set game_state = jsonb_set(coalesce(game_state, '{}'::jsonb), '{scored}', 'true'::jsonb)
     where room_code = in_room_code
       and (game_state->>'scored') is distinct from 'true';
    if found then claimed := true; end if;
  else
    if in_table_id is not null then
      update public.pvp_tables
         set game_state = jsonb_set(coalesce(game_state, '{}'::jsonb), '{scored}', 'true'::jsonb)
       where id = in_table_id
         and (game_state->>'scored') is distinct from 'true';
      if found then claimed := true; end if;
    end if;
  end if;

  if not claimed then
    select id into gid from public.games
     where (in_table_id is not null and table_id = in_table_id)
        or (in_room_code is not null and room_code = in_room_code)
     order by id desc limit 1;
    return gid;
  end if;

  insert into public.games (room_type, table_id, room_code, player_a_id, player_b_id,
                            winner_id, loser_id, score_delta, moves)
  values (in_room_type, in_table_id, in_room_code, in_winner_id, in_loser_id,
          in_winner_id, in_loser_id,
          jsonb_build_object('winner', delta_win, 'loser', delta_lose), in_moves)
  returning id into gid;

  update public.profiles
   set score = score + delta_win, wins = wins + 1, total_games = total_games + 1,
       score_reached_at = now()
   where id = in_winner_id;

  update public.profiles
   set score = score + delta_lose, losses = losses + 1, total_games = total_games + 1,
       score_reached_at = now()
   where id = in_loser_id;

  return gid;
end;
$$;

-- 人机结算：赢 +1（胜场+1）；输仅总场次+1（不扣分也不加分）
create or replace function public.pve_finish(in_player_id uuid, in_won boolean)
returns void
language plpgsql security definer set search_path = public
as $$
begin
  if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
  if in_won then
    update public.profiles
     set score = score + 1, wins = wins + 1, total_games = total_games + 1,
         score_reached_at = now()
     where id = in_player_id;
  else
    update public.profiles
     set total_games = total_games + 1
     where id = in_player_id;
  end if;
end;
$$;

-- 排行榜 Top N（score desc, wins desc, score_reached_at asc）
create or replace function public.get_ranking(limit_n int default 100)
returns table (id uuid, nickname text, gender text, score int, wins int, losses int, total_games int)
language plpgsql security definer set search_path = public
as $$
begin
  return query
  select p.id, p.nickname, p.gender, p.score, p.wins, p.losses, p.total_games
  from public.profiles p
  where p.nickname is not null
  order by p.score desc, p.wins desc, p.score_reached_at asc
  limit limit_n;
end;
$$;

-- 单人真实排名（与 get_ranking 同一三键比较，保证一致）
create or replace function public.get_user_rank(in_user_id uuid)
returns table (rank int, score int, wins int, losses int, total_games int)
language plpgsql security definer set search_path = public
as $$
declare
  my_score int; my_wins int; my_losses int; my_total int; my_reached timestamptz; r int;
begin
  select p.score, p.wins, p.losses, p.total_games, p.score_reached_at
    into my_score, my_wins, my_losses, my_total, my_reached
  from public.profiles p where p.id = in_user_id;
  if my_score is null then return; end if;
  select count(*) + 1 into r from public.profiles p
   where p.nickname is not null
     and (p.score > my_score
          or (p.score = my_score and p.wins > my_wins)
          or (p.score = my_score and p.wins = my_wins and p.score_reached_at < my_reached));
  return query
  select r, my_score, my_wins, my_losses, my_total;
end;
$$;

grant execute on function public.finish_game(text, char(4), text, uuid, uuid, jsonb) to anon, authenticated;
grant execute on function public.pve_finish(uuid, boolean) to anon, authenticated;
grant execute on function public.get_ranking(int) to anon, authenticated;
grant execute on function public.get_user_rank(uuid) to anon, authenticated;
