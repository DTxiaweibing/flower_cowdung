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
-- 0. 回合截止时间（turn_deadline_at）
--
-- ------------------------------------------------------------
-- 要解决的问题
--   倒计时原本是客户端的 int 每秒自减（LocalGameActivity.startCountdown），
--   到点由客户端自己调 reportPvpState("finished", "", 对手) 上报判负。
--   服务端只校验「调用者是当前回合者」，不校验是否真的超时。两个方向都坏：
--     - 改包可以在任何时刻宣布自己赢
--     - Handler 被系统冻结 / Doez 掐掉时倒计时永远不到点，于是永不判负
--   两条都和「60 秒不走就输」相反。
--
-- 做法
--   服务端在「轮到某人」那一刻记下 turn_deadline_at = now() + 60 秒。之后：
--     - 客户端到点调 request_turn_timeout_check 触发判负，cron 每分钟兜底（reap_turn_timeouts）
--     - 客户端只读剩余秒数负责显示（turn_secs_left）
--     - 客户端到点仍可上报，但服务端核对是否真超时后才接受
--   判负只发生在服务端：客户端改不了结果，也拦不住。
--
-- 为什么心跳 60 秒和回合 60 秒不冲突
--   阈值相同但管的事不同、判负方也不同：
--     心跳失联：谁的 last_x_at 过期谁输 —— 对手可能是「正在走棋」那个人，
--                也就是掉线/切后台/崩溃。判的是「人不在」。
--     回合超时：只有 current_turn_id 那个人过期才输 —— 判的是「人不走」。
--   同一时刻可能同时命中同一个人，结果都是他输；settle_round 以
--   game_state.scored 幂等，重复调用不会重复计分。
--
-- 一个必须处理的冲突
--   reap_stale_seats 的既有语义：两个座位都失联 -> 整桌清空、不开胜负
--   （没人能证明对手还在，谁也不该赢）。
--   reap_turn_timeouts 若不看对手心跳就判负，会在「双方都掉线且正轮到
--   其中一人」时凭空判一个人赢，把上面那条语义顶掉。
--   所以下面判负前强制要求：对手 last_x_at 在 60 秒内；不满足就跳过，
--   留给 reap_stale_seats 处理。
--
-- 秒数：人人桌 / 私密房间 60 秒，与心跳阈值一致，只在 pvp_turn_seconds 改一处。
--   人机桌不纳入：电脑走子完全在客户端（ComputerAI），服务端既不知道轮到谁
--   也不知道电脑会不会动，没有可判负的对象。人机倒计时继续走客户端本地 180 秒。
--
-- 放独立列而不是塞进 game_state jsonb：pvp_report_state / room_report_state
-- 是整包覆盖 game_state（客户端传的 jsonb 原样存），塞进 jsonb 会被下一次
-- 上报冲掉。
--
-- 这一段必须排在第 1 节之前：purge_table_on_empty 是触发器，里面要写
-- new.turn_deadline_at，列得先存在。
-- ============================================================

alter table public.pvp_tables
  add column if not exists turn_deadline_at timestamptz;

alter table public.private_rooms
  add column if not exists turn_deadline_at timestamptz;

create or replace function public.pvp_turn_seconds()
returns int
language sql immutable
as $$ select 60 $$;


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
    new.turn_deadline_at := null;         -- 同上：没有行动方就没有回合可超时
    if TG_ARGV[0] = 'pvp' then
      delete from public.pvp_watchers where table_id = new.id;
    else
      delete from public.private_room_watchers where room_code = new.room_code;
    end if;
  end if;

  return new;
end;
$$;

-- 触发器本体也在这里补建，不依赖 fix_chat_lifecycle.sql 先跑过。
-- 少了它，座位虽然被回收器放掉了，但聊天记录留着、观战人数不回正 ——
-- 表现就是「桌子空了但还有一堆观众和上一局聊天」。
-- create or replace function 已经保住了旧绑定，这里 drop + create 是为了
-- 幂等：重复执行不会报「触发器已存在」。
drop trigger if exists pvp_purge_on_empty on public.pvp_tables;
create trigger pvp_purge_on_empty
  before update on public.pvp_tables
  for each row
  execute procedure public.purge_table_on_empty('pvp');

drop trigger if exists room_purge_on_empty on public.private_rooms;
create trigger room_purge_on_empty
  before update on public.private_rooms
  for each row
  execute procedure public.purge_table_on_empty('room');


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
      -- 这里只在「我走之后一个座位都不剩」时才清空 game_state；
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
      status = case when player_a_id = null and player_b_id is null then 'open'
                    else status end,
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
      -- 上一局已结束就顺手清掉可能残留的截止时间（正常路径上它已被判负逻辑
      -- 置空，这里是「认输与超时判负同时到达」这类边界下的幂等保险）
      turn_deadline_at = case
                          when coalesce(game_state->>'status', '') = 'finished'
                          then null
                          else turn_deadline_at
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
      turn_deadline_at = case
                          when coalesce(game_state->>'status', '') = 'finished'
                          then null
                          else turn_deadline_at
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
        turn_deadline_at = case
                            when coalesce(game_state->>'status', '') = 'finished'
                            then null
                            else turn_deadline_at
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
        turn_deadline_at = case
                            when coalesce(game_state->>'status', '') = 'finished'
                            then null
                            else turn_deadline_at
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
          turn_deadline_at = null,
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
            turn_deadline_at = null,
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
          -- 没有行动方就没有回合可超时。这一句和上面判负那句重复不冲突：
          -- 上面管 playing 分支，这里管 seated 分支（本来就没在走棋，
          -- 但可能残留着上一局的 deadline，reap_turn_timeouts 会拿它误判）。
          turn_deadline_at = null,
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
          current_turn_id = null, turn_deadline_at = null,
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
            turn_deadline_at = null,
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
          turn_deadline_at = null,
          status = 'seated',
          last_active_at = now()
      where room_code = r.room_code;
    end if;
  end loop;

  -- ---------- 单边占座：一个人走了/崩了，另一个人还赖在座位上 ----------
  -- 上面两个循环都要求「两个座位都有人」，所以这种表一个都扫不到。
  -- 这正是「有人退不出桌子、桌上一直挂着上一局 finished」的来源：
  -- 对手走了以后 pvp_leave 把 status 留在 seated，
  -- 而回收器因为少了一个人而永远不碰它。
  --
  -- 只在「唯一占座者自己也停了心跳」时才动，所以活人不会被误清：
  -- 他在 App 里就一直在发心跳，last_*_at 永远是新的。
  -- 被冻/被杀/弱网断了才会落到这里。
  --
  -- 对手已经不在，没有胜负可判，也不计分 —— 单纯把座位放掉。
  -- game_state 一起清空：残留的上一局 finished/winner 会让下一个进来
  -- 的人先看到上一局的结果。
  for r in
    select t.id, t.player_a_id, t.player_b_id,
           case when t.player_a_id is not null then 'a' else 'b' end as side,
           case when t.player_a_id is not null then t.last_a_at else t.last_b_at end as last_seen
      from public.pvp_tables t
     where t.status in ('playing', 'seated')
       and ( (t.player_a_id is not null)::int + (t.player_b_id is not null)::int ) = 1
       and case when t.player_a_id is not null then t.last_a_at else t.last_b_at end
             is not null
       and case when t.player_a_id is not null then t.last_a_at else t.last_b_at end
             < now() - v_dead_line
  loop
    update public.pvp_tables
    set player_a_id = case when r.side = 'a' then null else player_a_id end,
        player_b_id = case when r.side = 'b' then null else player_b_id end,
        last_a_at   = case when r.side = 'a' then null else last_a_at end,
        last_b_at   = case when r.side = 'b' then null else last_b_at end,
        ready_a = case when r.side = 'a' then false else ready_a end,
        ready_b = case when r.side = 'b' then false else ready_b end,
        current_turn_id = null,
        turn_deadline_at = null,
        game_state = '{}'::jsonb,
        status = 'open',
        last_active_at = now()
    where id = r.id;
  end loop;

  -- 私密房同一口径
  for r in
    select t.room_code,
           case when t.player_a_id is not null then 'a' else 'b' end as side,
           case when t.player_a_id is not null then t.last_a_at else t.last_b_at end as last_seen
      from public.private_rooms t
     where t.status in ('playing', 'seated')
       and ( (t.player_a_id is not null)::int + (t.player_b_id is not null)::int ) = 1
       and case when t.player_a_id is not null then t.last_a_at else t.last_b_at end
             is not null
       and case when t.player_a_id is not null then t.last_a_at else t.last_b_at end
             < now() - v_dead_line
  loop
    update public.private_rooms
    set player_a_id = case when r.side = 'a' then null else player_a_id end,
        player_b_id = case when r.side = 'b' then null else player_b_id end,
        last_a_at   = case when r.side = 'a' then null else last_a_at end,
        last_b_at   = case when r.side = 'b' then null else last_b_at end,
        ready_a = case when r.side = 'a' then false else ready_a end,
        ready_b = case when r.side = 'b' then false else ready_b end,
        current_turn_id = null,
        turn_deadline_at = null,
        game_state = '{}'::jsonb,
        status = 'open',
        last_active_at = now()
    where room_code = r.room_code;
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

-- 座位回收器必须真的建出来。和 reap-turn-timeouts 同样的理由：
-- unschedule 成功但 schedule 失败（权限、pg_cron 被禁用）会让回收彻底停摆，
-- 而回收一停，掉线的座位就会永远挂着 —— 那正是这次要修的问题。
-- 所以这里当场确认，没建出来就报错，不让脚本「看着成功」地过去。
do $$
declare
  v_new_job integer;
  v_found   integer;
begin
  perform cron.schedule('reap-stale-seats', '* * * * *',
    $cron$ select public.reap_stale_seats(); $cron$);
  v_new_job := lastval();

  select count(*) into v_found
    from cron.job
   where jobid = v_new_job
     and jobname = 'reap-stale-seats'
     and active;

  if v_found = 0 then
    raise exception 'reap-stale-seats 排程失败：旧任务已删除且新任务未建出，座位回收将不会执行';
  end if;

  raise notice 'reap-stale-seats 已排定，jobid=%', v_new_job;
end $$;


-- ============================================================
-- 6.7 回合超时判负
--
--     与 reap_stale_seats 的分工：
--       reap_stale_seats（每分钟）：判「人不在」—— 掉线、崩溃、切后台
--       reap_turn_timeouts（每分钟兜底）：判「人不走」—— 人在线但不动棋
--     判负本身由客户端到点调 public.request_turn_timeout_check() 触发
--     （秒级），本 cron 只是没人戳时的兜底，最多多等一分钟。
--
--     判负前强制要求对手心跳在 60 秒内：否则说明对手也失联了，此时该走
--     reap_stale_seats「双方都失联 -> 整桌清空、不开胜负」的语义，而不是
--     凭空判一个人赢。
--
--     放在 settle_round 之后：本函数要调用它。
-- ============================================================

create or replace function public.reap_turn_timeouts()
returns void
language plpgsql security definer set search_path = public
as $$
declare
  r          record;
  v_dead_side text;
  v_dead_uid uuid;
  v_win_side text;
  v_win_uid  uuid;
begin
  -- ---------- 人人桌 ----------
  for r in
    select t.id, t.player_a_id, t.player_b_id, t.current_turn_id,
           case when t.current_turn_id = t.player_a_id then 'a' else 'b' end as turn_side
      from public.pvp_tables t
     where t.status = 'playing'
       and t.current_turn_id is not null
       and t.turn_deadline_at is not null
       and t.turn_deadline_at < now()
       -- 对手心跳必须在 60 秒内，否则交给 reap_stale_seats 整桌清空
       and case when t.current_turn_id = t.player_a_id
                then t.last_b_at >= now() - interval '60 seconds'
                else t.last_a_at >= now() - interval '60 seconds' end
  loop
    if r.turn_side = 'a' then
      v_dead_side := 'a'; v_dead_uid := r.player_a_id;
      v_win_side  := 'b'; v_win_uid  := r.player_b_id;
    else
      v_dead_side := 'b'; v_dead_uid := r.player_b_id;
      v_win_side  := 'a'; v_win_uid  := r.player_a_id;
    end if;

    -- status / current_turn_id / turn_deadline_at 都放进 WHERE：
    -- 客户端可能刚好在这期间落了子换了手，重评估后条件不成立 -> 空操作。
    -- 只判「本轮开始时轮到的那个人的 deadline 已过」，不判行数变化。
    update public.pvp_tables
    set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
          'moves', '[]'::jsonb, 'turn', '', 'winner', v_win_side,
          'status', 'finished', 'forfeit', true, 'timeout', true),
        current_turn_id = null,
        turn_deadline_at = null,
        status = 'seated',
        ready_a = false, ready_b = false,
        last_active_at = now()
    where id = r.id
      and status = 'playing'
      and current_turn_id = r.current_turn_id
      and turn_deadline_at is not null
      and turn_deadline_at < now();

    if found then
      perform public.settle_round(r.id, null, 'lobby', v_win_uid, v_dead_uid, null);
    end if;
  end loop;

  -- ---------- 私密房间（口径相同，积分为 +10/-2） ----------
  for r in
    select t.room_code, t.player_a_id, t.player_b_id, t.current_turn_id,
           case when t.current_turn_id = t.player_a_id then 'a' else 'b' end as turn_side
      from public.private_rooms t
     where t.status = 'playing'
       and t.current_turn_id is not null
       and t.turn_deadline_at is not null
       and t.turn_deadline_at < now()
       and case when t.current_turn_id = t.player_a_id
                then t.last_b_at >= now() - interval '60 seconds'
                else t.last_a_at >= now() - interval '60 seconds' end
  loop
    if r.turn_side = 'a' then
      v_dead_side := 'a'; v_dead_uid := r.player_a_id;
      v_win_side  := 'b'; v_win_uid  := r.player_b_id;
    else
      v_dead_side := 'b'; v_dead_uid := r.player_b_id;
      v_win_side  := 'a'; v_win_uid  := r.player_a_id;
    end if;

    update public.private_rooms
    set game_state = coalesce(game_state, '{}'::jsonb) || jsonb_build_object(
          'moves', '[]'::jsonb, 'turn', '', 'winner', v_win_side,
          'status', 'finished', 'forfeit', true, 'timeout', true),
        current_turn_id = null,
        turn_deadline_at = null,
        status = 'seated',
        ready_a = false, ready_b = false,
        last_active_at = now()
    where room_code = r.room_code
      and status = 'playing'
      and current_turn_id = r.current_turn_id
      and turn_deadline_at is not null
      and turn_deadline_at < now();

    if found then
      perform public.settle_round(null, r.room_code, 'private', v_win_uid, v_dead_uid, null);
    end if;
  end loop;
end;
$$;

-- 回收器由 cron 以超级用户身份跑，客户端不需要也不该直接调。
-- 整段包 exception：权限回收属于加固步骤，万一语法/角色有问题只报 warning，
-- 绝不能把整份迁移回滚（沿用 6.2 里对 settle_round 的处理方式）。
do $$
begin
  if to_regprocedure('public.reap_turn_timeouts()') is null then
    raise warning 'reap_turn_timeouts 签名与预期不符，跳过权限回收';
    return;
  end if;

  begin
    execute 'revoke all on function public.reap_turn_timeouts() from anon, authenticated';
  exception when others then
    raise warning 'reap_turn_timeouts 回收 anon/authenticated 失败: %', sqlerrm;
  end;

  begin
    execute 'revoke all on function public.reap_turn_timeouts() from PUBLIC';
  exception when others then
    raise warning 'reap_turn_timeouts 回收 PUBLIC 失败: %', sqlerrm;
  end;
end $$;

-- ============================================================
-- 6b. 客户端到点请求判负（秒级主路径）
--
-- 为什么需要：reap_turn_timeouts 原本想靠 cron 每 10 秒跑一遍，但本实例的
-- pg_cron 不支持秒位（见下方排程段注释），60 秒的回合超时不能等 10 分钟。
--
-- 为什么安全：这个函数不做任何判定决策，只是「请服务端看一眼这局该不该判」。
-- 真正判负的 WHERE 条件全在服务端（deadline 已过 + current_turn 未变 +
-- 对手心跳在 60 秒内），和 reap_turn_timeouts 用的是同一套条件。
-- 客户端传什么 id 都改变不了结果：
--   - 局面没过期        -> 不判
--   - 回合已经换过      -> 不判（对手刚好在这期间落了子）
--   - 对手也掉线了      -> 不判，交给 reap_stale_seats 整桌清空
-- 所以对手无法靠调它来抢判或作弊：它只能让服务端去检查一个本来就该判的局面。
--
-- 之所以允许 anon：这是「让服务端检查该不该判」的入口，不含任何特权写入，
-- 写入路径仍走 security definer 的 settle_round。
-- ============================================================
create or replace function public.request_turn_timeout_check(
  p_table_id text default null,
  p_room_code char(4) default null
)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  v_due boolean;
begin
  if p_table_id is null and p_room_code is null then
    return false;
  end if;

  -- 先只读地确认「这局确实到期且该判」，拿不到就不写。
  -- 与 reap_turn_timeouts 的筛选条件保持一致，避免两条路径口径不同。
  if p_table_id is not null then
    select exists (
      select 1 from public.pvp_tables t
       where t.id = p_table_id
         and t.status = 'playing'
         and t.current_turn_id is not null
         and t.turn_deadline_at is not null
         and t.turn_deadline_at < now()
         and case when t.current_turn_id = t.player_a_id
                  then t.last_b_at >= now() - interval '60 seconds'
                  else t.last_a_at >= now() - interval '60 seconds' end
    ) into v_due;
  else
    select exists (
      select 1 from public.private_rooms t
       where t.room_code = p_room_code
         and t.status = 'playing'
         and t.current_turn_id is not null
         and t.turn_deadline_at is not null
         and t.turn_deadline_at < now()
         and case when t.current_turn_id = t.player_a_id
                  then t.last_b_at >= now() - interval '60 seconds'
                  else t.last_a_at >= now() - interval '60 seconds' end
    ) into v_due;
  end if;

  if not coalesce(v_due, false) then
    return false;
  end if;

  -- 条件成立，交给唯一的判负实现去写（它内部还会按 current_turn_id 重新
  -- 校验一次，所以这里不存在 TOCTOU：并发换手时那次 update 会落空）。
  perform public.reap_turn_timeouts();
  return true;
end;
$$;

do $$
begin
  if to_regprocedure('public.request_turn_timeout_check(text,character)') is null then
    raise warning 'request_turn_timeout_check 签名与预期不符，跳过授权';
    return;
  end if;

  begin
    execute 'grant execute on function public.request_turn_timeout_check(text, character) to authenticated, anon';
  exception when others then
    raise warning 'request_turn_timeout_check 授权失败: %', sqlerrm;
  end;
end $$;


-- 排掉同名旧任务再排新的：cron.schedule 用固定名字，重复执行会再建一个同名
-- 任务（pg_cron 允许重名，会显示成 reap-turn-timeouts (1)），那就跑两遍。
--
-- unschedule 成功但 schedule 失败（权限、pg_cron 被禁用）会让这个任务彻底消失，
-- 而回超时已经把旧任务删掉了 —— 服务端就再也不会判超时，而且不会报错。
-- 所以排完必须当场确认真的建出来了，没有就明确报错。
do $$
declare
  j         record;
  v_new_job integer;
  v_found   integer;
begin
  for j in
    select jobid from cron.job where jobname = 'reap-turn-timeouts'
  loop
    perform cron.unschedule(j.jobid);
  end loop;

  -- 每分钟兜底，不是主判负路径。
  --
  -- 这里原本排的是六段式 '*/10 * * * * *'（想做到每 10 秒），但实测本实例的
  -- pg_cron 只按五段解析：秒位被当成分钟位，六段式退化成「每 10 分钟的第 0 秒」。
  -- 探针证据：cron.schedule('sec-probe','* * * * * *','select 1') 静置 15 秒后
  -- cron.job_run_details 只有 1 条（若支持秒位应为 ~15 条）。
  -- 60 秒的回合超时等不了 10 分钟，所以秒级判负改由客户端到点调
  -- public.request_turn_timeout_check() 触发（见该函数注释）。
  -- 本任务只负责兜底：客户端被冻结、切后台、进程被杀时没人戳，
  -- 由它每分钟扫一次，最多让结果晚一分钟出现。
  --
  -- 内层定界符要用和外层 do 块不同的名字（如 $cron$）：同名会把外层块
  -- 提前截断，报 42601 语法错误。注释里也不能出现定界符本身。
  perform cron.schedule('reap-turn-timeouts', '* * * * *',
    $cron$ select public.reap_turn_timeouts(); $cron$);
  v_new_job := lastval();

  select count(*) into v_found
    from cron.job
   where jobid = v_new_job
     and jobname = 'reap-turn-timeouts'
     and active;

  if v_found = 0 then
    raise exception 'reap-turn-timeouts 排程失败：旧任务已删除且新任务未建出，回超时判负将不会执行';
  end if;

  raise notice 'reap-turn-timeouts 已排定，jobid=%', v_new_job;
end $$;


-- ============================================================
-- 6.8 开局写第一手的截止时间
--
--     pvp_ready / room_ready 原本定义在 pvp_tables.sql / private_rooms.sql，
--     不在本文件里。直接改那两个基础文件的风险是：它们是全量建库脚本，
--     顺序依赖多（触发器、watcher 计数都在前面），单独重跑未必安全。
--     所以在这里重新定义，只加 turn_deadline_at 一行，其余逐字照抄。
--
--     房间那份以 private_rooms.sql 的当前版本为准（含「开局即清 ready、
--     下一局需重新准备」那条注释对应的 case 判断）。
-- ============================================================

create or replace function public.pvp_ready(tid text)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  c_status text;
  new_round int;
  first_is_a boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.pvp_tables
  set ready_a = case when player_a_id = uid then true else ready_a end,
      ready_b = case when player_b_id = uid then true else ready_b end,
      last_active_at = now()
  where id = tid and (player_a_id = uid or player_b_id = uid);

  if not found then
    raise exception 'NOT_YOUR_TABLE';
  end if;

  select player_a_id, player_b_id, status, round_no
       into a_id, b_id, c_status, new_round
  from public.pvp_tables where id = tid;

  -- 双方就绪且不在对局中才允许开局：
  --   防止对局中重复开局重置棋盘；开局即清 ready，下一局需重新准备
  if a_id is not null and b_id is not null
     and coalesce(c_status, 'open') <> 'playing'
     and exists (select 1 from public.pvp_tables
                 where id = tid and ready_a and ready_b) then
    new_round := new_round + 1;
    first_is_a := (new_round % 2) = 1;

    update public.pvp_tables
    set status = 'playing',
        round_no = new_round,
        current_turn_id = case when first_is_a then a_id else b_id end,
        -- 「轮到 first_is_a」的那一刻起算本轮 60 秒
        turn_deadline_at = now() + make_interval(secs => public.pvp_turn_seconds()),
        game_state = jsonb_build_object(
          'turn', case when first_is_a then 'a' else 'b' end,
          'status', 'ongoing',
          'flowers', '[1,2,3,4,5,6]'::jsonb,
          'moves', '[]'::jsonb),
        ready_a = false,
        ready_b = false,
        last_active_at = now()
    where id = tid;
  end if;

  return true;
end;
$$;

create or replace function public.room_ready(code char(4))
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
  uid  uuid := auth.uid();
  a_id uuid;
  b_id uuid;
  c_status text;
  new_round int;
  first_is_a boolean;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  update public.private_rooms
  set ready_a = case when player_a_id = uid then true else ready_a end,
      ready_b = case when player_b_id = uid then true else ready_b end,
      last_active_at = now()
  where room_code = code and (player_a_id = uid or player_b_id = uid);

  if not found then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  select player_a_id, player_b_id, status, round_no
       into a_id, b_id, c_status, new_round
  from public.private_rooms where room_code = code;

  -- 双方就绪且不在对局中才允许开局
  if a_id is not null and b_id is not null
     and coalesce(c_status, 'open') <> 'playing'
     and exists (select 1 from public.private_rooms
                 where room_code = code and ready_a and ready_b) then
    new_round := new_round + 1;
    first_is_a := (new_round % 2) = 1;

    update public.private_rooms
    set status = 'playing',
        round_no = new_round,
        current_turn_id = case when first_is_a then a_id else b_id end,
        turn_deadline_at = now() + make_interval(secs => public.pvp_turn_seconds()),
        game_state = jsonb_build_object(
          'turn', case when first_is_a then 'a' else 'b' end,
          'status', 'ongoing',
          'flowers', '[1,2,3,4,5,6]'::jsonb,
          'moves', '[]'::jsonb),
        ready_a = false,
        ready_b = false,
        last_active_at = now()
    where room_code = code;
  end if;

  return true;
end;
$$;


-- ============================================================
-- 6.9 客户端读剩余秒数
--
--     单独一个函数而不是让客户端直读 turn_deadline_at 再自己减：直读拿到的是
--     绝对时间戳，客户端一减就又依赖设备时钟（可被改、也会被 Handler 冻结）。
--     返回服务端算好的秒数，客户端只负责显示。
--     返回 null = 没有行动方 / 不在倒计时（未开局、已结束、人机桌）。
-- ============================================================

create or replace function public.turn_secs_left(
  p_table_id text default null,
  p_room_code char(4) default null
)
returns int
language plpgsql stable security definer set search_path = public
as $$
declare
  v_deadline timestamptz;
  v_turn uuid;
begin
  if p_table_id is not null then
    select turn_deadline_at, current_turn_id into v_deadline, v_turn
      from public.pvp_tables where id = p_table_id;
  else
    select turn_deadline_at, current_turn_id into v_deadline, v_turn
      from public.private_rooms where room_code = p_room_code;
  end if;

  if v_turn is null or v_deadline is null then
    return null;
  end if;

  return greatest(0, ceil(extract(epoch from (v_deadline - now()))));
end;
$$;


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
  c_deadline timestamptz;
  my_side  text;
  new_turn text;
  st_status text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, current_turn_id, status, turn_deadline_at
       into a_id, b_id, c_turn, c_status, c_deadline
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
    if c_turn is null or c_turn <> uid then
      raise exception 'NOT_YOUR_TURN';
    end if;

    -- finished 分两种，靠 winner 是不是「我」区分：
    --   winner = 我   -> 正常胜负（对手已无花可拿），按原样放行
    --   winner = 对手 -> 判负声明。客户端的超时倒计时就是走这条
    --     （LocalGameActivity:2015），而这条以前只校验「我是当前回合者」，
    --     改包就能在任何时刻宣布自己赢。现在必须确认 deadline 真的过了，
    --     判负权收归服务端：cron（reap_turn_timeouts）才是超时判负的执行者，
    --     这里只是让客户端到点时那一次上报能立刻生效，玩家不用干等一分钟。
    --
    -- 【已知信任边界，未在本次修复】正常胜负那条仍然信客户端上报的棋盘：
    -- checkGameEnd() 只是本地把 remainingFlowers[1..5] 加总看是否为 0，
    -- 服务端不校验 moves，所以伪造 flowers 数组仍可提前宣布获胜。
    -- 要根治得让服务端重放并校验每一步（换手权、拿花数、胜负条件），
    -- 那是另一件事，不属于「60 秒回合倒计时以服务端为准」的范围。
    if state->>'status' = 'finished' and state->>'winner' <> my_side then
      if c_deadline is null or c_deadline > now() then
        raise exception 'NOT_EXPIRED';
      end if;
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
      -- 换手就把本轮 60 秒给新行动方；结束清空。deadline 存在独立列而不是
      -- game_state 里，正是因为上面那句 game_state = state 是整包覆盖。
      turn_deadline_at = case
        when st_status = 'finished' then null
        when new_turn in ('a', 'b')
          then now() + make_interval(secs => public.pvp_turn_seconds())
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      ready_a = case when st_status = 'finished' then false else ready_a end,
      ready_b = case when st_status = 'finished' then false else ready_b end,
      last_active_at = now()
  where id = tid
    and (player_a_id = uid or player_b_id = uid)
    -- 已结算过就不再覆盖。
    -- 回合超时时 reap_turn_timeouts 先判负并结算（scored=true），而超时者
    -- 手上可能正好有一手在路上的上报这时才到：c_status 已是 'seated'，
    -- 上面那段轮次校验整块被跳过，若不加这一条，game_state = state 会把
    -- finished 和 scored 一起抹掉，对局复活成 playing，且 scored 丢失可能导致
    -- 重复计分。这和上面「离席后晚到的上报」是同一类写回竞态。
    and (game_state->>'scored') is distinct from 'true';

  if not found then
    -- 已经离席（或被踢）、或本局已结算，这次上报直接忽略，不当作错误
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
  c_deadline timestamptz;
  my_side  text;
  new_turn text;
  st_status text;
begin
  if uid is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select player_a_id, player_b_id, current_turn_id, status, turn_deadline_at
       into a_id, b_id, c_turn, c_status, c_deadline
  from public.private_rooms where room_code = code;
  if a_id is null then
    raise exception 'ROOM_NOT_FOUND';
  end if;

  if a_id <> uid and (b_id is null or b_id <> uid) then
    raise exception 'NOT_YOUR_ROOM';
  end if;

  -- 对局中才轮次校验。判负声明必须核对 deadline，理由同 pvp_report_state。
  if c_status = 'playing' then
    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;
    if my_side is null then
      raise exception 'NOT_YOUR_ROOM';
    end if;
    if c_turn is null or c_turn <> uid then
      raise exception 'NOT_YOUR_TURN';
    end if;
    if state->>'status' = 'finished' and state->>'winner' <> my_side then
      if c_deadline is null or c_deadline > now() then
        raise exception 'NOT_EXPIRED';
      end if;
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
      turn_deadline_at = case
        when st_status = 'finished' then null
        when new_turn in ('a', 'b')
          then now() + make_interval(secs => public.pvp_turn_seconds())
        else null
      end,
      status = case when st_status = 'finished' then 'seated' else 'playing' end,
      ready_a = case when st_status = 'finished' then false else ready_a end,
      ready_b = case when st_status = 'finished' then false else ready_b end,
      last_active_at = now()
  where room_code = code
    and (player_a_id = uid or player_b_id = uid)
    -- 已结算过就不再覆盖，理由同 pvp_report_state：
    -- reap_turn_timeouts 判负结算后，超时者手上在途的 ongoing 上报会走到这里，
    -- 不挡住就会把 finished / scored 抹掉并把桌子改回 playing。
    and (game_state->>'scored') is distinct from 'true';

  if not found then
    -- 已经离席（或被踢）、或本局已结算
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
      turn_deadline_at = null,
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
      ready_a = false,
      ready_b = false,
      current_turn_id = null,
      turn_deadline_at = null,
      last_active_at = now()
  where room_code = code
    and (player_a_id = uid or player_b_id = uid);

  return true;
end;
$$;


-- ============================================================
-- 8. 校验
-- ============================================================

-- 列必须在（0.7 里的加列跑过了）
select c.relname as table_name, a.attname as column_name, format_type(a.atttypid, a.atttypmod) as data_type
  from pg_attribute a
  join pg_class c on c.oid = a.attrelid
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and a.attname = 'turn_deadline_at'
   and not a.attisdropped
 order by 1;

-- 秒数来源：客户端和服务端读的是同一个值
select public.pvp_turn_seconds() as turn_seconds;

-- 对局中每一行的截止时间和剩余秒数。
-- 人机桌不在其中（服务端无 current_turn_id），预期 0 行。
-- secs_left 为 null = 没有行动方或不在倒计时；< 0 = 已过期，
-- reap_turn_timeouts 会在一分钟内判负。
select 'pvp' as tbl, id::text as tbl_id, status, current_turn_id is not null as has_turn,
       turn_deadline_at,
       public.turn_secs_left(id::text, null) as secs_left
  from public.pvp_tables
 where status = 'playing'
union all
select 'room', room_code::text, status, current_turn_id is not null,
       turn_deadline_at,
       public.turn_secs_left(null, room_code)
  from public.private_rooms
 where status = 'playing'
order by secs_left;

-- 残留截止时间：非对局中却有 deadline 的话，下一次 reap_turn_timeouts
-- 可能误判。预期 0 行；非 0 说明某个清场分支漏了置空。
select 'pvp' as tbl, id::text as tbl_id, status
  from public.pvp_tables
 where turn_deadline_at is not null
   and (status <> 'playing' or current_turn_id is null)
union all
select 'room', room_code::text, status
  from public.private_rooms
 where turn_deadline_at is not null
   and (status <> 'playing' or current_turn_id is null);

-- 本次刚堵的写回竞态：已结算的桌子又变回 playing，说明还有别的上报路径
-- 能覆盖 scored（预期 0 行；这两列都不是 playing 才算通过）
select 'pvp 结算后复活' as check_name, count(*) as cnt
  from public.pvp_tables
 where status = 'playing'
   and (game_state->>'scored') = 'true'
union all
select 'room 结算后复活', count(*)
  from public.private_rooms
 where status = 'playing'
   and (game_state->>'scored') = 'true';

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
--   第一段（cron）：只剩 reap-stale-seats 和 reap-turn-timeouts 两条，
--     都是每分钟（* * * * *），active 为 true。
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
-- 期望：reap-stale-seats（每分钟）+ reap-turn-timeouts（每分钟），
-- 其余 *-release-stale-* 这次撤掉的、不该再出现。
select jobname, schedule, active
  from cron.job
 where jobname in ('reap-stale-seats',
                   'reap-turn-timeouts',
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
                     'reap_stale_seats', 'reap_offline_watchers',
                     'reap_turn_timeouts', 'turn_secs_left', 'pvp_turn_seconds')
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

