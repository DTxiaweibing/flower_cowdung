-- ============================================================
-- 积分 / 排名 / 军衔 体系（2025 补丁）
--   规则：人机 赢+1 输0；人人(pvp) 赢+5 输-1；私密(private) 赢+10 输-2
--   排名：score desc, wins desc, score_reached_at asc（最早达到该分者靠前）
--   军衔：客户端按分数映射（新兵/士兵/班长/排长/连长/营长/团长/旅长/司令/军长/元帅）
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

-- finish_game：重写规则 + 幂等（抢占 finished 状态，二次调用直接返回已结算记录）
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

  -- 幂等：仅首次结算可把本局状态翻成 finished
  if in_room_type = 'private' and in_room_code is not null then
    update public.private_rooms set status = 'finished', game_state = '{}'::jsonb
     where room_code = in_room_code and status <> 'finished';
    if found then claimed := true; end if;
  else
    if in_table_id is not null then
      update public.pvp_tables set status = 'finished', game_state = '{}'::jsonb
       where id = in_table_id and status <> 'finished';
      if found then claimed := true; end if;
    end if;
  end if;

  if not claimed then
    select id into gid from public.games
     where (in_table_id is not null and table_id = in_table_id)
        or (in_room_code is not null and room_code = in_room_code)
     order by created_at desc limit 1;
    return gid;
  end if;

  insert into public.games (room_type, table_id, room_code, player_a_id, player_b_id,
                            winner_id, loser_id, score_delta, moves)
  values (in_room_type, in_table_id, in_room_code, in_winner_id, in_loser_id,
          in_winner_id, in_loser_id,
          jsonb_build_object('winner', delta_win, 'loser', delta_lose), in_moves)
  returning id into gid;

  perform 1 from public.profiles where id = in_winner_id for update;
  perform 1 from public.profiles where id = in_loser_id  for update;

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

-- 人机结算（赢+1 胜场+1；输仅 total_games+1，不扣分也不加分）
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

-- get_ranking：排序改为 score desc, wins desc, score_reached_at asc
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

-- 查单人真实排名（与 get_ranking 同一三键比较，保证一致）
create or replace function public.get_user_rank(in_user_id uuid)
returns table (rank int, score int, wins int, losses int, total_games int)
language plpgsql security definer set search_path = public
as $$
declare
  my_score int; my_wins int; my_reached timestamptz; r int;
begin
  select p.score, p.wins, p.score_reached_at into my_score, my_wins, my_reached
  from public.profiles p where p.id = in_user_id;
  if my_score is null then return; end if;
  select count(*) + 1 into r from public.profiles p
   where p.nickname is not null
     and (p.score > my_score
          or (p.score = my_score and p.wins > my_wins)
          or (p.score = my_score and p.wins = my_wins and p.score_reached_at < my_reached));
  return query
  select r, my_score,
    coalesce((select wins from public.profiles where id = in_user_id), 0),
    coalesce((select losses from public.profiles where id = in_user_id), 0),
    coalesce((select total_games from public.profiles where id = in_user_id), 0);
end;
$$;

grant execute on function public.finish_game(text, char(4), text, uuid, uuid, jsonb) to anon, authenticated;
grant execute on function public.pve_finish(uuid, boolean) to anon, authenticated;
grant execute on function public.get_ranking(int) to anon, authenticated;
grant execute on function public.get_user_rank(uuid) to anon, authenticated;
