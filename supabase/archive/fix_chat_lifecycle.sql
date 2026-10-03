-- ============================================================
-- fix_chat_lifecycle.sql
-- 聊天按桌隔离 + 末位离席清场（棋谱与聊天一起物理删除）
--
-- 需求：
--   1. 每桌聊天相互隔离，桌身份区分三种来源：
--        人机第 N 桌  -> 'pve:N'
--        人人第 N 桌  -> 'pvp:N'
--        私密房间 C  -> 'room:CCCC'
--      （旧版本直接把 '1'..'20' / 4 位房号塞进 table_id，
--        导致人机 3 桌和人人 3 桌共用同一个聊天室，必须改键。）
--
--   2. 末位玩家离席 = 该桌生命周期结束：
--        离开 / 判负退出 / 断线超时被服务端回收，都会让座位归零。
--        座位归零的那一瞬间：
--          - 删掉本桌全部聊天记录（不归档、不留待清）
--          - game_state 清空（棋谱不保留，finished 也不留）
--          - 踢掉本桌全部观众（他们客户端轮询到桌空即退回大厅）
--
--   3. 之后再有玩家坐上这张桌开始游戏，聊天重新开启且是空白的。
--
-- 实现方式：BEFORE UPDATE 触发器 + RPC 双保险。
--   BEFORE UPDATE 可以改写 NEW.game_state，且删除聊天/观众不会递归触发本表，
--   所以单个触发器就能完成三件事，不用改 pve_leave / pvp_leave / room_leave 的函数体。
--
-- 破坏性：会清空历史遗留聊天数据（旧键名无前缀，且无法判定属于哪张桌）。
--         按需求「不保留」执行，属预期行为。
--
-- Supabase Dashboard > SQL Editor 整段执行，可重复运行（幂等）
-- ============================================================


-- ============================================================
-- 0. 前置校验
--     `drop trigger if exists x on public.pve_tables` 里的 IF EXISTS 只管触发器，
--     表不存在时会直接抛 42P01 让整段回滚，看不出哪张表缺。这里先查一遍并给出人话提示。
-- ============================================================

do $$
declare
  v_missing text;
begin
  select string_agg(t, ', ') into v_missing
  from unnest(array['profiles','chat_messages','pve_tables','pve_watchers',
                    'pvp_tables','pvp_watchers','private_rooms','private_room_watchers']) t
  where to_regclass('public.' || t) is null;

  if v_missing is not null then
    raise exception
      '当前库缺少这些表: %。请先执行 supabase/run_all.sql 建好表，再重跑本脚本。',
      v_missing;
  end if;
end $$;


-- ============================================================
-- 0.5 拆掉上一版留下的坏触发器
--      fix_chat_auto_delete.sql 用 TG_TABLE_ID 当变量，它不是 plpgsql 变量，
--      函数体一执行就报错；AFTER UPDATE 触发器报错会连带让 pve_leave / pvp_leave 失败。
-- ============================================================

drop trigger if exists pve_clear_chat on public.pve_tables;
drop trigger if exists pvp_clear_chat on public.pvp_tables;
drop function if exists public.auto_clear_chat();


-- ============================================================
-- 1. 清场函数：TG_ARGV[0] 传前缀（'pve' / 'pvp' / 'room'）
--    触发条件：该行更新后「玩家归零」
-- ============================================================

create or replace function public.purge_table_on_empty()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_scope text;
begin
  -- ---- 判断是否已归零，并算出聊天 scope ----
  if TG_ARGV[0] = 'pve' then
    if new.player_id is not null then
      return new;                      -- 仍有人坐，桌未结束
    end if;
    v_scope := 'pve:' || new.id;

  elsif TG_ARGV[0] = 'pvp' then
    if new.player_a_id is not null or new.player_b_id is not null then
      return new;
    end if;
    v_scope := 'pvp:' || new.id;

  else                                   -- room
    if new.player_a_id is not null or new.player_b_id is not null then
      return new;
    end if;
    v_scope := 'room:' || rtrim(new.room_code::text);
  end if;

  -- ---- 1) 聊天：整桌物理删除，不留档 ----
  delete from public.chat_messages where table_id = v_scope;

  -- ---- 2) 棋谱：清空 game_state，finished 也不保留 ----
  --     pve_tables 没有 current_turn_id 列，只在人人/房间分支清
  new.game_state := '{}'::jsonb;

  -- ---- 3) 观众：踢光本桌全部观战关系 ----
  --     （pve_watchers / pvp_watchers / private_room_watchers
  --       都有 after delete 触发器维护 watcher_count，会自动回正）
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

-- 人机桌
drop trigger if exists pve_purge_on_empty on public.pve_tables;
create trigger pve_purge_on_empty
  before update on public.pve_tables
  for each row
  execute procedure public.purge_table_on_empty('pve');

-- 人人桌
drop trigger if exists pvp_purge_on_empty on public.pvp_tables;
create trigger pvp_purge_on_empty
  before update on public.pvp_tables
  for each row
  execute procedure public.purge_table_on_empty('pvp');

-- 私密房间
drop trigger if exists room_purge_on_empty on public.private_rooms;
create trigger room_purge_on_empty
  before update on public.private_rooms
  for each row
  execute procedure public.purge_table_on_empty('room');


-- ============================================================
-- 2. 保留一个手动清理 RPC（客户端不再依赖，仅作运维/兜底）
-- ============================================================

drop function if exists public.clear_table_chat(text);
create or replace function public.clear_table_chat(p_table_id text)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.chat_messages where table_id = p_table_id;
$$;

grant execute on function public.clear_table_chat(text) to authenticated;


-- ============================================================
-- 3. 一次性清理历史脏数据
--    3.1 旧键名聊天：'3' / '1234' 这类无前缀的历史数据无法归属桌号，直接清空
--    3.2 空桌残留：当前无人但仍有聊天/棋谱的桌，一并清掉
-- ============================================================

delete from public.chat_messages
where table_id not like 'pve:%'
  and table_id not like 'pvp:%'
  and table_id not like 'room:%';

-- 空桌残留聊天
-- 注意：chat_messages.table_id 带模式前缀（pve:1 / pvp:1 / room:1234），
-- 与 t.id（'1'）/ room_code（'1234'）不相等，必须拼接后再比较，否则这条是空跑
delete from public.chat_messages c
where exists (select 1 from public.pve_tables t
              where c.table_id = 'pve:' || t.id and t.player_id is null)
   or exists (select 1 from public.pvp_tables t
              where c.table_id = 'pvp:' || t.id
                and t.player_a_id is null and t.player_b_id is null);

delete from public.chat_messages c
where exists (select 1 from public.private_rooms r
              where c.table_id = 'room:' || rtrim(r.room_code::text)
                and r.player_a_id is null and r.player_b_id is null);

-- 空桌残留棋谱
-- pve_tables 没有 current_turn_id 列，只清 game_state
update public.pve_tables
set game_state = '{}'::jsonb
where player_id is null and game_state <> '{}'::jsonb;

update public.pvp_tables
set game_state = '{}'::jsonb, current_turn_id = null
where player_a_id is null and player_b_id is null
  and game_state <> '{}'::jsonb;

update public.private_rooms
set game_state = '{}'::jsonb, current_turn_id = null
where player_a_id is null and player_b_id is null
  and game_state <> '{}'::jsonb;


-- ============================================================
-- 4. 补齐 RLS 策略（RLS 拦截 DELETE 会导致清场静默失败）
-- ============================================================

drop policy if exists chat_messages_delete on public.chat_messages;
create policy chat_messages_delete on public.chat_messages
  for delete using (auth.role() = 'authenticated');


-- ============================================================
-- 5. 校验：应全部为 0 / 空
-- ============================================================

select '残留旧键聊天' as check_name, count(*) as cnt
  from public.chat_messages
 where table_id not like 'pve:%'
   and table_id not like 'pvp:%'
   and table_id not like 'room:%'
union all
select '空桌残留聊天' as check_name, count(*) from public.chat_messages c
 where exists (select 1 from public.pve_tables t
               where c.table_id = 'pve:' || t.id and t.player_id is null)
    or exists (select 1 from public.pvp_tables t
               where c.table_id = 'pvp:' || t.id
                 and t.player_a_id is null and t.player_b_id is null)
union all
select '空桌残留棋谱', count(*) from (
  select 1 from public.pve_tables
   where player_id is null and game_state <> '{}'::jsonb
  union all
  select 1 from public.pvp_tables
   where player_a_id is null and player_b_id is null
     and game_state <> '{}'::jsonb
  union all
  select 1 from public.private_rooms
   where player_a_id is null and player_b_id is null
     and game_state <> '{}'::jsonb
) x
union all
select '坏触发器残留', count(*) from pg_trigger
 where tgname in ('pve_clear_chat', 'pvp_clear_chat');
