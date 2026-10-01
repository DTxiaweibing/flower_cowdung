-- ============================================================
-- fix_round_lifecycle.sql
-- 一局的生命周期：一人走 = 本轮结束但桌子不结束；两人都走 = 整桌清场
--
-- 确认的规则：
--   桌上有至少一个玩家时，棋局不结束（剩下那位进入「等待对手入座」）
--   只有两个玩家都离开了，才把本桌彻底清空并把所有观众清退
--
-- 各时机行为：
--   一人中途退出  -> 判负：对手得分、退出者扣分（服务端立即结算）
--                    清聊天 + 清日志(moves)，保留棋盘快照与结算凭据
--                    保留观众（还有人在等对手）
--   新人坐下      -> 上一局的胜负与日志已全部清空，直接开新局
--   两人都走      -> 聊天/棋谱/game_state 全清 + 踢光本桌全部观众
--
-- ------------------------------------------------------------
-- 为什么结算要挪到服务端
--   原来 finish_game 由客户端在看到 game_state.status='finished' 时触发。
--   改完之后退出方会立刻腾出座位，若新对手在这之前就坐下，
--   game_state 已被重置为 'open'，剩下那位永远轮询不到 finished，
--   分数就丢了。这里在 pvp_leave / room_leave 里直接调用 finish_game，
--   与客户端是否在线无关。finish_game 内部用 game_state.scored 幂等，
--   客户端随后那次调用会因 scored=true 变成空操作，不会重复计分。
--
--   附带修掉一个隐患：原 pvp_leave / room_leave 用 jsonb_build_object
--   整体覆盖 game_state，会把 scored 标记一起抹掉，导致该轮可能被重复结算。
--   下面改成 game_state || jsonb_build_object(...) 合并写。
--
-- ------------------------------------------------------------
-- 为什么 logs 要清、棋盘快照要留
--   moves 清空 => 那一局的落子记录不再存在（也不会被下一位顶替改名）
--   flowers 保留 => 剩下那位屏幕上定格在判负那一刻的棋盘。
--   若连 flowers 一起清，LocalGameActivity 的 finished 分支有
--   `endFlowers.length() == 6` 的保护，不会重绘，于是屏幕停在「最后一手之前」，
--   鲜花明明没被拿走却已经显示胜负图标（该处原有注释防的就是这个坑）。
--
-- ------------------------------------------------------------
-- 为什么棋盘重置必须放在服务端
--   两位玩家和所有观战共用同一份 game_state 渲染日志。若由各客户端判断
--   何时重置，三方可能各显示各的；服务端原子重置才能保证三方逐字一致。
--
-- 幂等：整段可重复执行。
-- Supabase Dashboard > SQL Editor 整段执行
-- ============================================================


-- ============================================================
-- 1. 清场触发器：拆成两层
--    一层「有人走但还有人留」：只清聊天，观众与结算凭据都留着
--    一层「整桌归零」：全清 + 踢观众
--    pve_tables 是单人对机器人，player_id 一空即归零，保持原来的一层逻辑。
-- ============================================================

create or replace function public.purge_table_on_empty()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_scope   text;
  v_empty   boolean := false;   -- 整桌已无玩家
  v_vacated boolean := false;   -- 有座位由有人变成空，但还有人留
begin
  -- ---- 算 scope，并判断归零 / 腾位 ----
  if TG_ARGV[0] = 'pve' then
    v_scope := 'pve:' || new.id;
    if new.player_id is null then
      v_empty := true;
    end if;

  elsif TG_ARGV[0] = 'pvp' then
    v_scope := 'pvp:' || new.id;
    if new.player_a_id is null and new.player_b_id is null then
      v_empty := true;
    elsif (old.player_a_id is not null and new.player_a_id is null)
       or (old.player_b_id is not null and new.player_b_id is null) then
      v_vacated := true;
    end if;

  else                                   -- room
    v_scope := 'room:' || rtrim(new.room_code::text);
    if new.player_a_id is null and new.player_b_id is null then
      v_empty := true;
    elsif (old.player_a_id is not null and new.player_a_id is null)
       or (old.player_b_id is not null and new.player_b_id is null) then
      v_vacated := true;
    end if;
  end if;

  -- ---- 第一层：有人走、还有人留 => 本轮结束，清聊天，其余保留 ----
  --     不能在此清 game_state：剩下那位要靠它结算（虽然已改服务端结算，
  --     但保留 winner/棋盘快照可以让对方看到「本局结束：X 赢，Y 输」）。
  if v_vacated and not v_empty then
    delete from public.chat_messages where table_id = v_scope;
    return new;
  end if;

  -- ---- 无关更新：直接放行 ----
  if not v_empty then
    return new;
  end if;

  -- ---- 第二层：整桌归零 => 彻底清场 ----
  --     1) 聊天：整桌物理删除，不留档
  delete from public.chat_messages where table_id = v_scope;

  --     2) 棋谱：清空 game_state，finished 也不保留
  --        pve_tables 没有 current_turn_id 列，只在人人/房间分支清
  new.game_state := '{}'::jsonb;

  --     3) 观众：踢光本桌全部观战关系
  --        （pve_watchers / pvp_watchers / private_room_watchers
  --          都有 after delete 触发器维护 watcher_count，会自动回正）
  if TG_ARGV[0] = 'pve' then
    delete from public.pve_watchers where table_id = new.id;
  else
    new.current_turn_id := null;          -- pve_tables 无此列
    if TG_ARGV[0] = 'pvp' then
      delete from public.pvp_watchers where table_id = new.id;
    else
      delete from public.private_room_watchers where room_code = new.room_code;
    end if;
  end if;

  return new;
end;
$$;


-- ============================================================
-- 2. pvp_leave：中途退出 = 判负 + 服务端立即结算 + 清日志
-- ============================================================

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
  if a_id is null then
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

  -- 对局中本桌玩家退出 = 判负
  if cstate = 'playing' and my_side is not null and winner_uid is not null then
    -- 用 || 合并而不是整体覆盖：保住 scored（结算幂等标记）与 flowers
    --（棋盘快照，剩下那位屏幕上定格在判负那一刻），只清掉 moves（日志）
    update public.pvp_tables
    set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
          'moves', '[]'::jsonb,
          'turn', '',
          'winner', winner_side,
          'status', 'finished',
          'forfeit', true
        ),
        current_turn_id = null,
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
      -- 这里只在「我走之后一个座位都不剩」时才清空 game_state；
      -- 若对手还在，保留上一条 UPDATE 写好的判负结果
      game_state = case
                     when (player_a_id = uid and player_b_id is null)
                       or (player_b_id = uid and player_a_id is null)
                     then '{}'::jsonb
                     else game_state
                   end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
      last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;


-- ============================================================
-- 3. room_leave：同上，积分按私密房间口径（+10/-2）
-- ============================================================

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
      status = case when player_a_id = null and player_b_id is null then 'open'
                    else status end,
      game_state = case
                     when (player_a_id = uid and player_b_id is null)
                       or (player_b_id = uid and player_a_id is null)
                     then '{}'::jsonb
                     else game_state
                   end,
      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,
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


-- ============================================================
-- 4. 换人入座：旧局已结束就直接重置，新 occupant 不继承旧棋谱
--    不重置的话，新人坐进去会看到上一局被换成他名字的历史，
--    而且 game_state 仍是 finished，他的客户端会误判结算。
-- ============================================================

create or replace function public.pvp_sit_a(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id = uid then
    delete from public.pvp_watchers where user_id = uid;
    return true;
  end if;
  if a_id is not null then
    raise exception 'TABLE_OCCUPIED';
  end if;

  select exists (select 1 from public.pvp_tables
                 where player_a_id = uid or player_b_id = uid) into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pvp_tables
  set player_a_id = uid,
      status = 'seated',
      last_a_at = now(),
      game_state = case
                     when coalesce(game_state->>'status', '') = 'finished'
                     then jsonb_build_object(
                            'turn', '', 'status', 'open', 'winner', '',
                            'flowers', '[]'::jsonb, 'moves', '[]'::jsonb)
                     else game_state
                   end,
      last_active_at = now()
  where id = tid;

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;


create or replace function public.pvp_sit_b(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  sitting_elsewhere boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if not found then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if b_id = uid then
    delete from public.pvp_watchers where user_id = uid;
    return true;
  end if;
  if b_id is not null then
    raise exception 'TABLE_OCCUPIED';
  end if;

  select exists (select 1 from public.pvp_tables
                 where player_a_id = uid or player_b_id = uid) into sitting_elsewhere;
  if sitting_elsewhere then
    raise exception 'ALREADY_SITTING';
  end if;

  update public.pvp_tables
  set player_b_id = uid,
      status = 'seated',
      last_b_at = now(),
      game_state = case
                     when coalesce(game_state->>'status', '') = 'finished'
                     then jsonb_build_object(
                            'turn', '', 'status', 'open', 'winner', '',
                            'flowers', '[]'::jsonb, 'moves', '[]'::jsonb)
                     else game_state
                   end,
      last_active_at = now()
  where id = tid;

  delete from public.pvp_watchers where user_id = uid;

  return true;
end;
$$;


create or replace function public.room_sit(code char(4))
returns text
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  selse boolean;
  my_side text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if not found then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  -- 已经在房里且已占某一座
  if a_id = uid then
    delete from public.private_room_watchers where user_id = uid;
    return 'a';
  end if;
  if b_id = uid then
    delete from public.private_room_watchers where user_id = uid;
    return 'b';
  end if;

  -- 每人一次一座（玩家位或观众位）
  select exists (select 1 from public.private_rooms
                 where player_a_id = uid or player_b_id = uid) into selse;
  if selse then
    raise exception 'ALREADY_SITTING';
  end if;
  select exists (select 1 from public.private_room_watchers where user_id = uid) into selse;
  if selse then
    raise exception 'ALREADY_SITTING';
  end if;

  if a_id is null then
    update public.private_rooms
    set player_a_id = uid,
        status = 'seated',
        last_a_at = now(),
        game_state = case
                       when coalesce(game_state->>'status', '') = 'finished'
                       then jsonb_build_object(
                              'turn', '', 'status', 'open', 'winner', '',
                              'flowers', '[]'::jsonb, 'moves', '[]'::jsonb)
                       else game_state
                     end,
        last_active_at = now()
    where room_code = code;
    my_side := 'a';
  elsif b_id is null then
    update public.private_rooms
    set player_b_id = uid,
        status = 'seated',
        last_b_at = now(),
        game_state = case
                       when coalesce(game_state->>'status', '') = 'finished'
                       then jsonb_build_object(
                              'turn', '', 'status', 'open', 'winner', '',
                              'flowers', '[]'::jsonb, 'moves', '[]'::jsonb)
                       else game_state
                     end,
        last_active_at = now()
    where room_code = code;
    my_side := 'b';
  else
    raise exception 'SEAT_UNAVAILABLE';
  end if;

  return my_side;
end;
$$;


-- ============================================================
-- 5. 按座位记心跳 + 崩溃回收
--
-- 原来的心跳只写全桌一个 last_active_at，后果：
--   甲还活着 -> 桌一直「活」-> 两个整桌回收 cron 都不触发
--   -> 乙崩了以后座位永远不释放 -> 丙永远坐不进来 -> 甲永远卡在等乙
-- 改成每个座位各记一份时间戳，回收粒度才落到座位上。
--
-- 【判负规则：60 秒，无条件】
-- 60 秒内收不到心跳就判负，不分原因：
--   - 进程崩了 / 被系统杀掉
--   - 断网、切基站、地铁没信号
--   - 切后台、锁屏、来电 —— 客户端 onPause 直接停发心跳
-- 故意不给缓冲。给缓冲的代价是对手白等：后台 10 分钟缓冲就是
-- 对手守着空桌等 10 分钟，而崩溃回收用 2 分钟只是把 10 分钟缩成 2 分钟，
-- 问题没解决只是变短。所以阈值就是 60 秒，客户端心跳 20 秒一次，
-- 允许连续丢 3 次。
--
-- 调阈值只改 reap_stale_seats 里的 v_dead_line 一处。
-- （真正的根治是倒计时以服务器为准：服务端记录回合截止时间，
--   客户端只负责显示，超时由服务端判负。那样掉线/切后台/篡改本地时间
--   都不再是问题。见后续改动。）
-- ============================================================

-- ---- 6.1 加列 ----
alter table public.pvp_tables
  add column if not exists last_a_at timestamptz,
  add column if not exists last_b_at timestamptz;

alter table public.private_rooms
  add column if not exists last_a_at timestamptz,
  add column if not exists last_b_at timestamptz;

-- 存量座位用 last_active_at 回填，否则 NULL 永远不会被判为失联
update public.pvp_tables set last_a_at = coalesce(last_a_at, last_active_at) where player_a_id is not null;
update public.pvp_tables set last_b_at = coalesce(last_b_at, last_active_at) where player_b_id is not null;
update public.private_rooms set last_a_at = coalesce(last_a_at, last_active_at) where player_a_id is not null;
update public.private_rooms set last_b_at = coalesce(last_b_at, last_active_at) where player_b_id is not null;

-- ---- 6.2 结算逻辑抽成内部函数 ----
--     finish_game 里有 `if auth.uid() is null then raise` ，而回收任务跑在
--     pg_cron 里没有 JWT，auth.uid() 恒为 NULL，直接调会抛 NOT_AUTHENTICATED。
--     所以把真正的结算体抽成 settle_round（不做鉴权），finish_game 只保留
--     鉴权后转调，客户端与回收任务共用同一段代码，不会漂移。
create or replace function public.settle_round(
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
  if in_winner_id is null or in_loser_id is null then raise exception 'INVALID_RESULT'; end if;
  if in_winner_id = in_loser_id then raise exception 'INVALID_RESULT'; end if;

  -- 以 game_state.scored 认领这一局：谁先到谁结算，后到的自动变空操作
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

-- 内部函数：只允许被 security definer 的函数间接调用，不给前端直接调。
-- 若前端能直接调，就能指定任意 winner/loser 自己加分，必须收回。
-- 整段包 exception：权限回收属于加固步骤，万一语法/角色有问题
-- 只报 warning，绝不能把整份迁移回滚。
do $$
begin
  if to_regprocedure('public.settle_round(text,char,text,uuid,uuid,jsonb)') is null then
    raise warning 'settle_round 签名与预期不符，跳过权限回收';
    return;
  end if;

  begin
    execute 'revoke all on function public.settle_round(text, char, text, uuid, uuid, jsonb) from anon, authenticated';
  exception when others then
    raise warning 'settle_round 回收 anon/authenticated 失败: %', sqlerrm;
  end;

  begin
    execute 'revoke all on function public.settle_round(text, char, text, uuid, uuid, jsonb) from PUBLIC';
  exception when others then
    raise warning 'settle_round 回收 PUBLIC 失败: %', sqlerrm;
  end;
end $$;

-- 客户端入口：保留原来的鉴权与默认参数，转调 settle_round
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
begin
  if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
  return public.settle_round(in_table_id, in_room_code, in_room_type,
                             in_winner_id, in_loser_id, in_moves);
end;
$$;

-- ---- 6.3 心跳只更新自己那个座位 ----
--     签名保持原来的单参数版本，不引入 in_foreground：
--     判负规则就是「60 秒没心跳」，区分前后台只会制造缓冲，
--     而缓冲的代价是对手白等。
--     客户端在 onPause 里直接停发心跳，所以「切后台」自然落到
--     「60 秒收不到心跳」这一档，不需要服务端额外知道前台后台。
drop function if exists public.pvp_heartbeat(text, boolean);
drop function if exists public.room_heartbeat(char(4), boolean);
drop function if exists public.pve_heartbeat(text, boolean);

create or replace function public.pvp_heartbeat(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  -- 只给自己占的那个座位盖时间戳；另一个座位的时间戳不动，
  -- 这样一个座位失联不会连带把同桌的人也算成失联。
  update public.pvp_tables
  set last_active_at = now(),
      last_a_at = case when player_a_id = uid then now() else last_a_at end,
      last_b_at = case when player_b_id = uid then now() else last_b_at end
  where id = tid and (player_a_id = uid or player_b_id = uid);

  -- 观众不参与判负，心跳只刷新存活时间
  update public.pvp_watchers
  set last_active_at = now()
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

create or replace function public.room_heartbeat(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.private_rooms
  set last_active_at = now(),
      last_a_at = case when player_a_id = uid then now() else last_a_at end,
      last_b_at = case when player_b_id = uid then now() else last_b_at end
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  update public.private_room_watchers
  set last_active_at = now()
  where room_code = code and user_id = uid;

  return true;
end;
$$;

-- 人机桌：只有一整张桌的 player_id，没有分座位心跳，
-- 座位判定仍由 pve-release-idle-seats 负责，本次不改它。
drop function if exists public.pve_heartbeat(text);

create or replace function public.pve_heartbeat(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pve_tables
  set last_active_at = now()
  where id = tid and player_id = uid;

  update public.pve_watchers
  set last_active_at = now()
  where table_id = tid and user_id = uid;

  return true;
end;
$$;

-- ---- 6.4 回收失联座位 ----
--     失联 = last_x_at 早于 60 秒前的那个时刻。
--     不分原因：崩了、掉线、切后台（客户端已停发心跳）一律判负。
--     对局中(playing)且只有一个座位失联：判该座位负，对手得分 + 清本轮日志
--     未开局(seated)且只有一个座位失联：直接释放座位，不计分（本来就没在对局）
--     两个座位都失联：无胜负可判，整桌清空（触发器会清聊天 + 踢观众）
create or replace function public.reap_stale_seats()
returns void
language plpgsql security definer set search_path = public
as $$
declare
  r           record;
  v_dead_line interval := interval '60 seconds';
  v_dead_side text;
  v_dead_uid  uuid;
  v_win_side  text;
  v_win_uid   uuid;
begin
  -- ---------- 人人桌 ----------
  for r in
    select t.id, t.player_a_id, t.player_b_id, t.status,
           (t.player_a_id is not null and t.last_a_at is not null
              and t.last_a_at < now() - v_dead_line) as a_dead,
           (t.player_b_id is not null and t.last_b_at is not null
              and t.last_b_at < now() - v_dead_line) as b_dead
      from public.pvp_tables t
     where t.status in ('playing', 'seated')
       and t.player_a_id is not null and t.player_b_id is not null
       and ( (t.last_a_at is not null
              and t.last_a_at < now() - v_dead_line)
          or (t.last_b_at is not null
              and t.last_b_at < now() - v_dead_line) )
  loop
    if r.a_dead and r.b_dead then
      -- 全员掉线：没有胜负可判，直接整桌清空
      update public.pvp_tables
      set player_a_id = null, player_b_id = null,
          last_a_at = null, last_b_at = null,
          ready_a = false, ready_b = false,
          current_turn_id = null,
          game_state = '{}'::jsonb,
          status = 'open', last_active_at = now()
      where id = r.id;

    elsif r.a_dead or r.b_dead then
      if r.a_dead then
        v_dead_side := 'a'; v_dead_uid := r.player_a_id;
        v_win_side  := 'b'; v_win_uid  := r.player_b_id;
      else
        v_dead_side := 'b'; v_dead_uid := r.player_b_id;
        v_win_side  := 'a'; v_win_uid  := r.player_a_id;
      end if;

      -- 对局中才判负结算
      if r.status = 'playing' then
        update public.pvp_tables
        set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
              'moves', '[]'::jsonb, 'turn', '', 'winner', v_win_side,
              'status', 'finished', 'forfeit', true),
            current_turn_id = null,
            status = 'seated',
            last_active_at = now()
        where id = r.id;

        perform public.settle_round(r.id, null, 'lobby', v_win_uid, v_dead_uid, null);
      end if;

      -- 释放失联座位（触发器此时只清聊天，观众保留）
      update public.pvp_tables
      set player_a_id = case when v_dead_side = 'a' then null else player_a_id end,
          player_b_id = case when v_dead_side = 'b' then null else player_b_id end,
          last_a_at   = case when v_dead_side = 'a' then null else last_a_at end,
          last_b_at   = case when v_dead_side = 'b' then null else last_b_at end,
          ready_a = case when v_dead_side = 'a' then false else ready_a end,
          ready_b = case when v_dead_side = 'b' then false else ready_b end,
          status = 'seated',
          last_active_at = now()
      where id = r.id;
    end if;
  end loop;

  -- ---------- 私密房间（口径相同，积分为 +10/-2） ----------
  for r in
    select t.room_code, t.player_a_id, t.player_b_id, t.status,
           (t.player_a_id is not null and t.last_a_at is not null
              and t.last_a_at < now() - v_dead_line) as a_dead,
           (t.player_b_id is not null and t.last_b_at is not null
              and t.last_b_at < now() - v_dead_line) as b_dead
      from public.private_rooms t
     where t.status in ('playing', 'seated')
       and t.player_a_id is not null and t.player_b_id is not null
       and ( (t.last_a_at is not null
              and t.last_a_at < now() - v_dead_line)
          or (t.last_b_at is not null
              and t.last_b_at < now() - v_dead_line) )
  loop
    if r.a_dead and r.b_dead then
      update public.private_rooms
      set player_a_id = null, player_b_id = null,
          last_a_at = null, last_b_at = null,
          ready_a = false, ready_b = false,
          current_turn_id = null,
          game_state = '{}'::jsonb,
          status = 'open', last_active_at = now()
      where room_code = r.room_code;

    elsif r.a_dead or r.b_dead then
      if r.a_dead then
        v_dead_side := 'a'; v_dead_uid := r.player_a_id;
        v_win_side  := 'b'; v_win_uid  := r.player_b_id;
      else
        v_dead_side := 'b'; v_dead_uid := r.player_b_id;
        v_win_side  := 'a'; v_win_uid  := r.player_a_id;
      end if;

      if r.status = 'playing' then
        update public.private_rooms
        set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
              'moves', '[]'::jsonb, 'turn', '', 'winner', v_win_side,
              'status', 'finished', 'forfeit', true),
            current_turn_id = null,
            status = 'seated',
            last_active_at = now()
        where room_code = r.room_code;

        perform public.settle_round(null, r.room_code, 'private', v_win_uid, v_dead_uid, null);
      end if;

      update public.private_rooms
      set player_a_id = case when v_dead_side = 'a' then null else player_a_id end,
          player_b_id = case when v_dead_side = 'b' then null else player_b_id end,
          last_a_at   = case when v_dead_side = 'a' then null else last_a_at end,
          last_b_at   = case when v_dead_side = 'b' then null else last_b_at end,
          ready_a = case when v_dead_side = 'a' then false else ready_a end,
          ready_b = case when v_dead_side = 'b' then false else ready_b end,
          status = 'seated',
          last_active_at = now()
      where room_code = r.room_code;
    end if;
  end loop;
end;
$$;

-- ---- 6.5 换掉原来那几条「整桌」回收任务 ----
--     它们是全表粒度的：甲还在心跳就永不触发，正好漏掉上面这种单边崩溃。
--     私密房间里也有两条同款（room-release-stale-playing / -seats），
--     而且 room-release-stale-seats 更糟：seated 状态 3 分钟没人动就把
--     整个房间重置回 open、两个座位一起清空、不给任何人记分。
--     那样两个人一起切个后台吃个饭，回来发现房间被无声抹掉了 ——
--     和「60 秒无差别判负」直接冲突，所以一并撤掉。
--     （* -purge-offline-watchers 保留：那是清离线观众的，与座位回收无关。）
--     连 reap-stale-seats 自己也先删：cron.schedule 用固定名字，
--     重复执行会再建一个同名任务（pg_cron 允许重名，会显示成 reap-stale-seats (1)），
--     那样回收器就变成每分钟跑两遍。
do $$
declare
  j record;
begin
  for j in
    select jobid from cron.job
     where jobname in ('pvp-release-stale-playing',
                       'pvp-release-stale-seats',
                       'room-release-stale-playing',
                       'room-release-stale-seats',
                       'reap-stale-seats')
  loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('reap-stale-seats', '* * * * *',
  $$ select public.reap_stale_seats(); $$);


-- ============================================================
-- 7. 离席后不许再上报状态（堵一个真实的写回竞态）
--
-- 客户端退出时是并发发两个请求：pvpLeave 和 reportPvpState("finished")，
-- 都在后台线程，没有先后保证。而 pvp_report_state 的成员校验写在 SELECT 里，
-- 最后那条 UPDATE 的 WHERE 只有 `id = tid`：
--
--     select player_a_id, ... ;          -- 这里校验「你还在座位上」
--     ...
--     update public.pvp_tables set game_state = state, ...
--      where id = tid;                   -- 这里没校验
--
-- READ COMMITTED 下，如果 pvp_leave 在 SELECT 之后、UPDATE 之前提交，
-- PostgreSQL 会拿到锁后用新版本重新评估 WHERE —— 但 `id = tid` 依然成立，
-- 于是这条上报照旧生效。而退出者上报的包里带着完整的 flowers 和 moves
-- （LocalGameActivity.reportPvpState），结果就是：
--   - 刚被清空的日志整份复活
--   - pvp_leave 写的 forfeit 标记被抹掉
--   - finish_game 的 scored 幂等标记被抹掉
-- 而触发条件就是最普通的操作：对局中点退出。
--
-- 修法：把座位校验也加进 UPDATE 的 WHERE。
-- UPDATE 在拿到行锁后会对最新版本重新评估 WHERE，所以并发提交后
-- 座位已被清空 → 条件不成立 → 整条上报变成空操作，正好是我们要的。
-- ============================================================

create or replace function public.pvp_report_state(tid text, state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid      uuid := auth.uid();
  a_id     uuid;
  b_id     uuid;
  c_turn   uuid;
  c_status text;
  my_side  text;
  new_turn text;
  st_status text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, current_turn_id, status
       into a_id, b_id, c_turn, c_status
  from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  -- 对局中才轮次校验：不在自己回合不准改状态，也不能替对方结算
  if c_status = 'playing' then
    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
    if my_side is null then
      raise exception 'NOT_YOUR_TABLE';
    end if;
    if c_turn is not null and c_turn = uid then
      null;
    elsif state->>'status' = 'finished' then
      if c_turn = uid then
        null;
      else
        raise exception 'NOT_YOUR_TURN';
      end if;
    else
      raise exception 'NOT_YOUR_TURN';
    end if;
  end if;

  st_status := coalesce(state->>'status', 'ongoing');
  new_turn := coalesce(state->>'turn', '');

  -- and (player_a_id = uid or player_b_id = uid) 是本次新增的防护：
  -- 离席后晚到的上报在这里变成空操作
  update public.pvp_tables
  set game_state = state,
      current_turn_id = case
        when st_status = 'finished' then null
        when new_turn = 'a' then a_id
        when new_turn = 'b' then b_id
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      ready_a = case when st_status = 'finished' then false else ready_a end,
      ready_b = case when st_status = 'finished' then false else ready_b end,
      last_active_at = now()
  where id = tid
    and (player_a_id = uid or player_b_id = uid);

  if not found then
    -- 已经离席（或被踢），这次上报直接忽略，不当作错误
    return true;
  end if;

  return true;
end;
$$;


create or replace function public.room_report_state(code char(4), state jsonb)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid      uuid := auth.uid();
  a_id     uuid;
  b_id     uuid;
  c_turn   uuid;
  c_status text;
  my_side  text;
  new_turn text;
  st_status text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, current_turn_id, status
       into a_id, b_id, c_turn, c_status
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  -- 对局中才轮次校验
  if c_status = 'playing' then
    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
    if my_side is null then
      raise exception 'NOT_YOUR_ROOM';
    end if;
    if c_turn is not null and c_turn = uid then
      null;
    elsif state->>'status' = 'finished' then
      if c_turn = uid then
        null;
      else
        raise exception 'NOT_YOUR_TURN';
      end if;
    else
      raise exception 'NOT_YOUR_TURN';
    end if;
  end if;

  st_status := coalesce(state->>'status', 'ongoing');
  new_turn := coalesce(state->>'turn', '');

  update public.private_rooms
  set game_state = state,
      current_turn_id = case
        when st_status = 'finished' then null
        when new_turn = 'a' then a_id
        when new_turn = 'b' then b_id
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      ready_a = case when st_status = 'finished' then false else ready_a end,
      ready_b = case when st_status = 'finished' then false else ready_b end,
      last_active_at = now()
  where room_code = code
    and (player_a_id = uid or player_b_id = uid);

  if not found then
    return true;
  end if;

  return true;
end;
$$;


-- pvp_end / room_end 同理：双方各自点「结束」时，也要保证点的人还在座位上
create or replace function public.pvp_end(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id from public.pvp_tables where id = tid;
  if a_id is null then
    raise exception 'TABLE_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  update public.pvp_tables
  set status = 'seated',
      ready_a = false,
      ready_b = false,
      current_turn_id = null,
      last_active_at = now()
  where id = tid
    and (player_a_id = uid or player_b_id = uid);

  return true;
end;
$$;


create or replace function public.room_end(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id into a_id, b_id
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  update public.private_rooms
  set status = 'seated',
      current_turn_id = null,
      ready_a = false,
      ready_b = false,
      last_active_at = now()
  where room_code = code
    and (player_a_id = uid or player_b_id = uid);

  return true;
end;
$$;


-- ============================================================
-- 8. 校验
-- ============================================================

select '坐下后仍残留 finished（换人未重置）' as check_name, count(*) as cnt
  from public.pvp_tables
 where game_state->>'status' = 'finished'
   and (player_a_id is not null or player_b_id is not null)
   and (game_state->'moves') = '[]'::jsonb
union all
select '空桌残留棋谱', count(*) from public.pvp_tables
 where player_a_id is null and player_b_id is null
   and game_state <> '{}'::jsonb
union all
select '空房残留棋谱', count(*) from public.private_rooms
 where player_a_id is null and player_b_id is null
   and game_state <> '{}'::jsonb
union all
select '空桌残留聊天', count(*) from public.chat_messages c
 where exists (select 1 from public.pvp_tables t
                where c.table_id = 'pvp:' || t.id
                  and t.player_a_id is null and t.player_b_id is null);

-- 每个座位距判负还剩多少秒。阈值固定 60 秒，负数就是已经该被判负了。
-- 这张表就是「服务器侧倒计时」：客户端只需要照着它显示，
-- 篡改本地时间或直接改界面都判不了负 —— 真正的判负由 reap_stale_seats 执行。
-- private_rooms 没有 id 列（主键是 room_code），所以两边的标识列都统一成表名字符串。
select 'pvp' as tbl, id as tbl_id, 'a' as side,
       60 - extract(epoch from (now() - last_a_at))::int as secs_left
  from public.pvp_tables
 where player_a_id is not null and last_a_at is not null
union all
select 'pvp', id, 'b',
       60 - extract(epoch from (now() - last_b_at))::int
  from public.pvp_tables
 where player_b_id is not null and last_b_at is not null
union all
select 'room', room_code::text, 'a',
       60 - extract(epoch from (now() - last_a_at))::int
  from public.private_rooms
 where player_a_id is not null and last_a_at is not null
union all
select 'room', room_code::text, 'b',
       60 - extract(epoch from (now() - last_b_at))::int
  from public.private_rooms
 where player_b_id is not null and last_b_at is not null
order by secs_left;

-- 手动干跑一次回收（只验证函数能跑通；正常情况应无变化）
-- select public.reap_stale_seats();


-- ============================================================
-- 9. 收尾校验
--
-- 期望输出：
--   第一段（cron）：只剩 reap-stale-seats 一条，active 为 true。
--     4 条 *-release-stale-* 是这次撤掉的，不该再出现。
--     3 条 *-purge-offline-watchers 保持原样、继续用 3 分钟，
--     它们这次没动过，同样不该出现在结果里。
--
--   第二段（函数签名）：三条心跳都是单参数，没有 in_foreground。
--
--   第三段（租约列）：0 行。如果这里有输出，说明之前跑过租约版，
--     那些 add column if not exists 留下的空列还在表里 ——
--     不影响判负（代码已不再读写它们），想清掉就手动：
--       alter table public.pvp_tables            drop column if exists lease_a, drop column if exists lease_b;
--       alter table public.private_rooms         drop column if exists lease_a, drop column if exists lease_b;
--       alter table public.pvp_watchers          drop column if exists lease;
--       alter table public.private_room_watchers drop column if exists lease;
--       alter table public.pve_watchers          drop column if exists lease;
-- ============================================================
select jobname, schedule, active
  from cron.job
 where jobname in ('reap-stale-seats',
                   'reap-offline-watchers',
                   'pvp-release-stale-playing',
                   'pvp-release-stale-seats',
                   'room-release-stale-playing',
                   'room-release-stale-seats',
                   'pvp-purge-offline-watchers',
                   'room-purge-offline-watchers',
                   'pve-purge-offline-watchers')
 order by jobname;

-- 心跳函数签名：应全部是单参数版本，不该残留 in_foreground
select p.proname,
       pg_get_function_arguments(p.oid) as args
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('pvp_heartbeat', 'room_heartbeat', 'pve_heartbeat',
                     'reap_stale_seats', 'reap_offline_watchers')
 order by p.proname;

-- 租约列不该存在（如果之前跑过租约版，加列语句会留下空列）
select c.relname as table_name, a.attname as column_name
  from pg_attribute a
  join pg_class c on c.oid = a.attrelid
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and a.attname in ('lease', 'lease_a', 'lease_b')
   and not a.attisdropped
 order by 1, 2;

