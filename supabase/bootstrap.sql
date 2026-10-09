-- ============================================================================
--  鑺辩墝cowdung 鈥斺€?瀹屾暣寤哄簱鑴氭湰 (bootstrap)
-- ============================================================================
--  鐢ㄩ€旓細鍦ㄣ€愬叏鏂扮殑 Supabase 椤圭洰銆戦噷涓€娆℃€у缓鍑轰笌绾夸笂瀹屽叏涓€鑷寸殑缁撴瀯锛?--        涓嶅惈浠讳綍涓氬姟鏁版嵁銆?--
--  鏉ユ簮锛歞b_backup/schema.sql锛?026-10-03 浠庣嚎涓婂鍑虹殑鐪熷疄缁撴瀯锛?--        + cron 瀹氭椂浠诲姟锛堝浠介噷涓嶅惈锛岄渶浠庤剼鏈ˉ榻愶級
--        + room_leave 淇锛?026-10-05锛屽湪澶囦唤涔嬪悗淇殑锛?--
--  鎵ц鏂瑰紡锛歋upabase 鎺у埗鍙?-> SQL Editor -> 鏂板缓鏌ヨ -> 鍏ㄩ€夌矘璐?-> Run
--            鍙互閲嶅鎵ц锛屼笉浼氱牬鍧忓凡鏈夋暟鎹€?--
--  缁勬垚锛?--    绗?1 閮ㄥ垎  public schema锛氳〃 / 绾︽潫 / 绱㈠紩 / 鍑芥暟 / 瑙﹀彂鍣?/ RLS / 绛栫暐 / 鏉冮檺
--    绗?2 閮ㄥ垎  auth 閽╁瓙锛氭敞鍐屾柊鐢ㄦ埛鏃惰嚜鍔ㄥ缓 profile
--    绗?3 閮ㄥ垎  room_leave 淇锛堣鐩栧浠介噷鐨勬棫鐗堟湰锛?--    绗?4 閮ㄥ垎  12 涓?cron 瀹氭椂浠诲姟
--    绗?5 閮ㄥ垎  鏀跺熬鏍￠獙锛氫换鍔℃暟閲忎笉瀵瑰氨鐩存帴鎶ラ敊
--
--  娉ㄦ剰锛氬浠介噷鐨?auth schema 娈碉紙Supabase 鑷甫鐨?27 寮犺〃 / 183 鏉℃巿鏉冿級
--        鍦ㄦ柊椤圭洰閲屾湰鏉ュ氨瀛樺湪锛岄噸鏀句細鍐茬獊锛屾墍浠ユ病鏈夊寘鍚繘鏉ャ€?-- ============================================================================

-- --------------------------------------------------------------------------
-- PART 1  public schema
--   Extracted verbatim from the live dump (lines 1..6376);
--   line endings normalised to LF.
-- --------------------------------------------------------------------------

-- Supabase schema dump
-- generated: 2026-10-03T12:24:28.617664
-- host: db.uihalfuswgilzzhzmgpv.supabase.co

create extension if not exists "pg_cron" with schema "pg_catalog";
create extension if not exists "pg_stat_statements" with schema "extensions";
create extension if not exists "pgcrypto" with schema "extensions";
create extension if not exists "plpgsql" with schema "pg_catalog";
create extension if not exists "supabase_vault" with schema "vault";
create extension if not exists "uuid-ossp" with schema "extensions";

-- ===== schema: public =====

create table if not exists "public"."chat_messages" (
  "id" bigint generated always as identity not null,
  "table_id" text not null,
  "sender_id" uuid,
  "sender_name" text,
  "message" text not null,
  "created_at" timestamp with time zone default now() not null
);
create table if not exists "public"."games" (
  "id" uuid default gen_random_uuid() not null,
  "room_type" text not null,
  "table_id" text,
  "room_code" character(4),
  "player_a_id" uuid not null,
  "player_b_id" uuid not null,
  "winner_id" uuid,
  "loser_id" uuid,
  "score_delta" jsonb default '{}'::jsonb not null,
  "moves" jsonb,
  "finished_at" timestamp with time zone default now() not null
);
create table if not exists "public"."lobby_tables" (
  "id" text not null,
  "status" text default 'waiting'::text not null,
  "player_a_id" uuid,
  "player_b_id" uuid,
  "current_turn_id" uuid,
  "game_state" jsonb default '{}'::jsonb not null,
  "watcher_count" integer default 0 not null,
  "last_active_at" timestamp with time zone default now() not null,
  "created_at" timestamp with time zone default now() not null
);
create table if not exists "public"."private_room_watcher_bans" (
  "room_code" text not null,
  "user_id" uuid not null,
  "banned_until" timestamp with time zone not null,
  "created_by" uuid,
  "created_at" timestamp with time zone default now() not null
);
create table if not exists "public"."private_room_watchers" (
  "room_code" text not null,
  "user_id" uuid not null,
  "joined_at" timestamp with time zone default now() not null,
  "last_active_at" timestamp with time zone default now() not null
);
create table if not exists "public"."private_rooms" (
  "room_code" character(4) not null,
  "status" text default 'open'::text not null,
  "player_a_id" uuid,
  "player_b_id" uuid,
  "current_turn_id" uuid,
  "ready_a" boolean default false not null,
  "ready_b" boolean default false not null,
  "game_state" jsonb default '{}'::jsonb not null,
  "watcher_count" integer default 0 not null,
  "last_active_at" timestamp with time zone default now() not null,
  "created_at" timestamp with time zone default now() not null,
  "round_no" integer default 0 not null,
  "banned_ids" uuid[] default '{}'::uuid[],
  "last_a_at" timestamp with time zone,
  "last_b_at" timestamp with time zone,
  "turn_deadline_at" timestamp with time zone
);
create table if not exists "public"."profiles" (
  "id" uuid not null,
  "nickname" text,
  "gender" text,
  "score" integer default 0 not null,
  "wins" integer default 0 not null,
  "losses" integer default 0 not null,
  "total_games" integer default 0 not null,
  "last_seen_at" timestamp with time zone,
  "created_at" timestamp with time zone default now() not null,
  "score_reached_at" timestamp with time zone default now() not null
);
create table if not exists "public"."pve_tables" (
  "id" text not null,
  "num" integer not null,
  "status" text default 'open'::text not null,
  "player_id" uuid,
  "watcher_count" integer default 0 not null,
  "last_active_at" timestamp with time zone default now() not null,
  "game_state" jsonb default '{}'::jsonb not null,
  "banned_ids" uuid[] default '{}'::uuid[]
);
create table if not exists "public"."pve_watchers" (
  "table_id" text not null,
  "user_id" uuid not null,
  "joined_at" timestamp with time zone default now() not null,
  "last_active_at" timestamp with time zone default now() not null,
  "kicked" boolean default false not null
);
create table if not exists "public"."pvp_tables" (
  "id" text not null,
  "num" integer not null,
  "status" text default 'open'::text not null,
  "player_a_id" uuid,
  "player_b_id" uuid,
  "current_turn_id" uuid,
  "ready_a" boolean default false not null,
  "ready_b" boolean default false not null,
  "game_state" jsonb default '{}'::jsonb not null,
  "watcher_count" integer default 0 not null,
  "last_active_at" timestamp with time zone default now() not null,
  "created_at" timestamp with time zone default now() not null,
  "round_no" integer default 0 not null,
  "banned_ids" uuid[] default '{}'::uuid[],
  "last_a_at" timestamp with time zone,
  "last_b_at" timestamp with time zone,
  "turn_deadline_at" timestamp with time zone
);
create table if not exists "public"."pvp_watcher_bans" (
  "table_id" text not null,
  "user_id" uuid not null,
  "banned_until" timestamp with time zone not null,
  "created_by" uuid,
  "created_at" timestamp with time zone default now() not null
);
create table if not exists "public"."pvp_watchers" (
  "table_id" text not null,
  "user_id" uuid not null,
  "joined_at" timestamp with time zone default now() not null,
  "last_active_at" timestamp with time zone default now() not null,
  "kicked" boolean default false not null
);
create table if not exists "public"."room_members" (
  "room_code" text not null,
  "user_id" uuid not null,
  "role" text default 'watcher'::text not null,
  "is_muted" boolean default false not null,
  "joined_at" timestamp with time zone default now() not null,
  "kicked" boolean default false not null
);
create table if not exists "public"."rooms" (
  "room_code" character(4) not null,
  "host_id" uuid,
  "status" text default 'waiting'::text not null,
  "player_a_id" uuid,
  "player_b_id" uuid,
  "current_turn_id" uuid,
  "game_state" jsonb default '{}'::jsonb not null,
  "last_active_at" timestamp with time zone default now() not null,
  "created_at" timestamp with time zone default now() not null
);
alter table "public"."profiles" add constraint "profiles_pkey" PRIMARY KEY (id);
alter table "public"."pve_tables" add constraint "pve_tables_pkey" PRIMARY KEY (id);
alter table "public"."private_rooms" add constraint "private_rooms_pkey" PRIMARY KEY (room_code);
alter table "public"."private_room_watcher_bans" add constraint "private_room_watcher_bans_pkey" PRIMARY KEY (room_code, user_id);
alter table "public"."private_room_watchers" add constraint "private_room_watchers_pkey" PRIMARY KEY (room_code, user_id);
alter table "public"."pve_watchers" add constraint "pve_watchers_pkey" PRIMARY KEY (table_id, user_id);
alter table "public"."room_members" add constraint "room_members_pkey" PRIMARY KEY (room_code, user_id);
alter table "public"."rooms" add constraint "rooms_pkey" PRIMARY KEY (room_code);
alter table "public"."pvp_watchers" add constraint "pvp_watchers_pkey" PRIMARY KEY (table_id, user_id);
alter table "public"."pvp_tables" add constraint "pvp_tables_pkey" PRIMARY KEY (id);
alter table "public"."pvp_watcher_bans" add constraint "pvp_watcher_bans_pkey" PRIMARY KEY (table_id, user_id);
alter table "public"."games" add constraint "games_pkey" PRIMARY KEY (id);
alter table "public"."lobby_tables" add constraint "lobby_tables_pkey" PRIMARY KEY (id);
alter table "public"."chat_messages" add constraint "chat_messages_pkey" PRIMARY KEY (id);
alter table "public"."profiles" add constraint "profiles_nickname_key" UNIQUE (nickname);
alter table "public"."private_room_watchers" add constraint "private_room_watchers_user_key" UNIQUE (user_id);
alter table "public"."pve_tables" add constraint "pve_tables_num_key" UNIQUE (num);
alter table "public"."pvp_watchers" add constraint "pvp_watchers_user_key" UNIQUE (user_id);
alter table "public"."pve_watchers" add constraint "pve_watchers_user_key" UNIQUE (user_id);
alter table "public"."pvp_tables" add constraint "pvp_tables_num_key" UNIQUE (num);
alter table "public"."profiles" add constraint "profiles_gender_check" CHECK (gender = ANY (ARRAY['male'::text, 'female'::text]));
alter table "public"."pvp_tables" add constraint "pvp_tables_status_check" CHECK (status = ANY (ARRAY['open'::text, 'seated'::text, 'playing'::text]));
alter table "public"."lobby_tables" add constraint "lobby_table_players_differ" CHECK (NOT (player_a_id IS NOT NULL AND player_b_id IS NOT NULL AND player_a_id = player_b_id));
alter table "public"."pvp_tables" add constraint "pvp_table_players_differ" CHECK (NOT (player_a_id IS NOT NULL AND player_b_id IS NOT NULL AND player_a_id = player_b_id));
alter table "public"."games" add constraint "games_room_type_check" CHECK (room_type = ANY (ARRAY['lobby'::text, 'private'::text]));
alter table "public"."pve_tables" add constraint "pve_tables_status_check" CHECK (status = ANY (ARRAY['open'::text, 'seated'::text, 'playing'::text]));
alter table "public"."private_rooms" add constraint "private_rooms_status_check" CHECK (status = ANY (ARRAY['open'::text, 'seated'::text, 'playing'::text]));
alter table "public"."rooms" add constraint "room_players_differ" CHECK (NOT (player_a_id IS NOT NULL AND player_b_id IS NOT NULL AND player_a_id = player_b_id));
alter table "public"."private_rooms" add constraint "private_room_players_differ" CHECK (NOT (player_a_id IS NOT NULL AND player_b_id IS NOT NULL AND player_a_id = player_b_id));
alter table "public"."rooms" add constraint "rooms_status_check" CHECK (status = ANY (ARRAY['waiting'::text, 'playing'::text, 'finished'::text]));
alter table "public"."room_members" add constraint "room_members_role_check" CHECK (role = ANY (ARRAY['player'::text, 'watcher'::text]));
alter table "public"."lobby_tables" add constraint "lobby_tables_status_check" CHECK (status = ANY (ARRAY['waiting'::text, 'playing'::text, 'finished'::text]));
alter table "public"."lobby_tables" add constraint "lobby_tables_player_b_id_fkey" FOREIGN KEY (player_b_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pvp_watcher_bans" add constraint "pvp_watcher_bans_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."pvp_watcher_bans" add constraint "pvp_watcher_bans_created_by_fkey" FOREIGN KEY (created_by) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."games" add constraint "games_loser_id_fkey" FOREIGN KEY (loser_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pvp_watcher_bans" add constraint "pvp_watcher_bans_table_id_fkey" FOREIGN KEY (table_id) REFERENCES pvp_tables(id) ON DELETE CASCADE;
alter table "public"."games" add constraint "games_player_a_id_fkey" FOREIGN KEY (player_a_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."rooms" add constraint "rooms_current_turn_id_fkey" FOREIGN KEY (current_turn_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pvp_watchers" add constraint "pvp_watchers_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."room_members" add constraint "room_members_room_code_fkey" FOREIGN KEY (room_code) REFERENCES rooms(room_code) ON DELETE CASCADE;
alter table "public"."room_members" add constraint "room_members_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."pvp_watchers" add constraint "pvp_watchers_table_id_fkey" FOREIGN KEY (table_id) REFERENCES pvp_tables(id) ON DELETE CASCADE;
alter table "public"."rooms" add constraint "rooms_player_b_id_fkey" FOREIGN KEY (player_b_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."rooms" add constraint "rooms_host_id_fkey" FOREIGN KEY (host_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."rooms" add constraint "rooms_player_a_id_fkey" FOREIGN KEY (player_a_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."private_rooms" add constraint "private_rooms_current_turn_id_fkey" FOREIGN KEY (current_turn_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."private_room_watchers" add constraint "private_room_watchers_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."private_rooms" add constraint "private_rooms_player_a_id_fkey" FOREIGN KEY (player_a_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."lobby_tables" add constraint "lobby_tables_current_turn_id_fkey" FOREIGN KEY (current_turn_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."private_room_watchers" add constraint "private_room_watchers_room_code_fkey" FOREIGN KEY (room_code) REFERENCES private_rooms(room_code) ON DELETE CASCADE;
alter table "public"."lobby_tables" add constraint "lobby_tables_player_a_id_fkey" FOREIGN KEY (player_a_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."private_room_watcher_bans" add constraint "private_room_watcher_bans_created_by_fkey" FOREIGN KEY (created_by) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."private_room_watcher_bans" add constraint "private_room_watcher_bans_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."private_room_watcher_bans" add constraint "private_room_watcher_bans_room_code_fkey" FOREIGN KEY (room_code) REFERENCES private_rooms(room_code) ON DELETE CASCADE;
alter table "public"."private_rooms" add constraint "private_rooms_player_b_id_fkey" FOREIGN KEY (player_b_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."games" add constraint "games_player_b_id_fkey" FOREIGN KEY (player_b_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."pvp_tables" add constraint "pvp_tables_current_turn_id_fkey" FOREIGN KEY (current_turn_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pvp_tables" add constraint "pvp_tables_player_b_id_fkey" FOREIGN KEY (player_b_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pvp_tables" add constraint "pvp_tables_player_a_id_fkey" FOREIGN KEY (player_a_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."pve_watchers" add constraint "pve_watchers_user_id_fkey" FOREIGN KEY (user_id) REFERENCES profiles(id) ON DELETE CASCADE;
alter table "public"."games" add constraint "games_winner_id_fkey" FOREIGN KEY (winner_id) REFERENCES profiles(id) ON DELETE SET NULL;
alter table "public"."profiles" add constraint "profiles_id_fkey" FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE CASCADE;
alter table "public"."pve_watchers" add constraint "pve_watchers_table_id_fkey" FOREIGN KEY (table_id) REFERENCES pve_tables(id) ON DELETE CASCADE;
alter table "public"."pve_tables" add constraint "pve_tables_player_id_fkey" FOREIGN KEY (player_id) REFERENCES profiles(id) ON DELETE SET NULL;
CREATE INDEX chat_messages_table_idx ON public.chat_messages USING btree (table_id, id);
CREATE INDEX games_room_code_idx ON public.games USING btree (room_code);
CREATE INDEX games_room_type_idx ON public.games USING btree (room_type, finished_at DESC);
CREATE INDEX games_table_id_idx ON public.games USING btree (table_id);
CREATE INDEX lobby_tables_last_active_idx ON public.lobby_tables USING btree (last_active_at);
CREATE INDEX lobby_tables_status_idx ON public.lobby_tables USING btree (status);
CREATE INDEX private_room_watcher_bans_user_idx ON public.private_room_watcher_bans USING btree (user_id);
CREATE INDEX private_rooms_last_active_idx ON public.private_rooms USING btree (last_active_at);
CREATE INDEX pve_tables_num_idx ON public.pve_tables USING btree (num);
CREATE INDEX pve_watchers_user_idx ON public.pve_watchers USING btree (user_id);
CREATE INDEX pvp_tables_num_idx ON public.pvp_tables USING btree (num);
CREATE INDEX pvp_watcher_bans_user_idx ON public.pvp_watcher_bans USING btree (user_id);
CREATE INDEX pvp_watchers_user_idx ON public.pvp_watchers USING btree (user_id);
CREATE INDEX room_members_room_idx ON public.room_members USING btree (room_code);
CREATE INDEX rooms_last_active_idx ON public.rooms USING btree (last_active_at);
CREATE INDEX rooms_status_idx ON public.rooms USING btree (status);
CREATE OR REPLACE FUNCTION public.auth_uid_safe()
 RETURNS uuid
 LANGUAGE sql
 STABLE
AS $function$select auth.uid();$function$;

CREATE OR REPLACE FUNCTION public.check_nickname(n text)
 RETURNS boolean
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

  select not exists (select 1 from public.profiles where nickname = n)

$function$;

CREATE OR REPLACE FUNCTION public.cleanup_stale_rooms()
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  cleaned_count int := 0;

begin

  -- 娓呯悊瓒呰繃1灏忔椂娌℃湁娲昏穬鐨勬埧闂达紙浠呴檺绛夊緟鐘舵€侊級

  delete from public.room_members

  where room_code in (

    select room_code 

    from public.rooms 

    where status = 'waiting' 

      and last_active_at < now() - interval '1 hour'

  );

  

  -- 娓呯悊鎴块棿

  delete from public.rooms

  where status = 'waiting' 

    and last_active_at < now() - interval '1 hour';

    

  get diagnostics cleaned_count = ROW_COUNT;

  

  return cleaned_count;

end;

$function$;

CREATE OR REPLACE FUNCTION public.clear_table_chat(p_table_id text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

  delete from public.chat_messages where table_id = p_table_id;

$function$;

CREATE OR REPLACE FUNCTION public.create_lobby_table()
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid   uuid := auth.uid();

  tid   text;

  alive int;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  select count(*) into alive

  from public.lobby_tables

  where status in ('waiting', 'playing');



  if alive >= 200 then

    raise exception 'TABLE_LIMIT_REACHED';

  end if;



  -- 鐢熸垚鍞竴妗屽彿

  loop

    tid := public.random_table_id();

    exit when not exists (select 1 from public.lobby_tables where id = tid);

  end loop;



  insert into public.lobby_tables (id, player_a_id, status)

  values (tid, uid, 'waiting');



  return tid;

end;

$function$;

CREATE OR REPLACE FUNCTION public.create_profile(nick text, g text)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid   uuid := auth.uid();

  clean text;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  clean := btrim(nick);

  if clean = '' then

    raise exception 'NICKNAME_EMPTY';

  end if;

  if char_length(clean) > 4 then

    raise exception 'NICKNAME_TOO_LONG';

  end if;

  if g is null or g not in ('male', 'female') then

    raise exception 'INVALID_GENDER';

  end if;

  if exists (select 1 from public.profiles

             where nickname = clean and id <> uid) then

    raise exception 'NICKNAME_TAKEN';

  end if;



  insert into public.profiles (id, nickname, gender)

  values (uid, clean, g)

  on conflict (id) do update set

    nickname = excluded.nickname,

    gender   = excluded.gender;



  return uid;

end;

$function$;

CREATE OR REPLACE FUNCTION public.create_room()
 RETURNS character
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid  uuid := auth.uid();

  code char(4);

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  loop

    code := public.random_room_code();

    exit when not exists (select 1 from public.rooms where room_code = code);

  end loop;



  insert into public.rooms (room_code, host_id, status)

  values (code, uid, 'waiting');



  return code;

end;

$function$;

CREATE OR REPLACE FUNCTION public.finish_game(in_table_id text DEFAULT NULL::text, in_room_code character DEFAULT NULL::bpchar, in_room_type text DEFAULT 'lobby'::text, in_winner_id uuid DEFAULT NULL::uuid, in_loser_id uuid DEFAULT NULL::uuid, in_moves jsonb DEFAULT NULL::jsonb)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.get_game_state(in_table_id text DEFAULT NULL::text, in_room_code character DEFAULT NULL::bpchar)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  gs jsonb;

begin

  if in_table_id is not null then

    select game_state into gs from public.lobby_tables where id = in_table_id;

  elsif in_room_code is not null then

    select game_state into gs from public.rooms where room_code = in_room_code;

  else

    raise exception 'NO_TARGET';

  end if;

  if gs is null then

    raise exception 'NOT_FOUND';

  end if;

  return gs;

end;

$function$;

CREATE OR REPLACE FUNCTION public.get_ranking(limit_n integer DEFAULT 100)
 RETURNS TABLE(id uuid, nickname text, gender text, score integer, wins integer, losses integer, total_games integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  return query

  select p.id, p.nickname, p.gender, p.score, p.wins, p.losses, p.total_games

  from public.profiles p

  where p.nickname is not null

  order by p.score desc, p.wins desc, p.score_reached_at asc

  limit limit_n;

end;

$function$;

CREATE OR REPLACE FUNCTION public.get_user_rank(in_user_id uuid)
 RETURNS TABLE(rank integer, score integer, wins integer, losses integer, total_games integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;


CREATE OR REPLACE FUNCTION public.host_kick(code character, target uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;

  -- 浠呮埧涓伙紝涓斾笉鑳借涪妫嬫墜

  if not exists (select 1 from public.rooms

                 where room_code = code and host_id = uid) then

    raise exception 'NOT_HOST';

  end if;

  if exists (select 1 from public.rooms

             where room_code = code and (player_a_id = target or player_b_id = target)) then

    raise exception 'CANNOT_KICK_PLAYER';

  end if;

  delete from public.room_members where room_code = code and user_id = target;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.host_mute(code character, target uuid, mute boolean)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;

  if not exists (select 1 from public.rooms

                 where room_code = code and host_id = uid) then

    raise exception 'NOT_HOST';

  end if;

  update public.room_members set is_muted = mute

  where room_code = code and user_id = target;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.join_room(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.rooms where room_code = code and status <> 'finished') then

    raise exception 'ROOM_NOT_FOUND';

  end if;



  -- 浣滀负鐜╁鍔犲叆绌轰綅锛堜紭鍏?B 浣嶏級锛屼絾涓嶆敼鍙樼姸鎬?
  update public.rooms

  set player_b_id = uid,

      last_active_at = now()

  where room_code = code

    and player_b_id is null

    and player_a_id is distinct from uid;



  -- 鏃犺濡備綍閮芥槸鎴块棿鎴愬憳锛堝叆搴ф垚鍔熷垯涓?player锛屽惁鍒?watcher锛?
  insert into public.room_members (room_code, user_id, role)

  values (code, uid, case when found then 'player' else 'watcher' end)

  on conflict (room_code, user_id) do update set

    role = case when public.room_members.role <> 'player' then 'watcher' else public.room_members.role end;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.join_room_watcher(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.rooms where room_code = code and status <> 'finished') then

    raise exception 'ROOM_NOT_FOUND';

  end if;



  insert into public.room_members (room_code, user_id, role)

  values (code, uid, 'watcher')

  on conflict (room_code, user_id) do update set role = 'watcher';



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.join_table(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.lobby_tables

  set player_b_id = uid,

      status = case when player_a_id is not null then 'playing' else 'waiting' end,

      last_active_at = now()

  where id = tid

    and player_b_id is null

    and player_a_id is distinct from uid;



  if not found then

    raise exception 'TABLE_UNAVAILABLE';

  end if;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.kick_watcher(in_mode text, in_id text, in_target uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  cnt int := 0;

begin

  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;



  if in_mode = 'pvp' then

    if not exists (

      select 1 from public.pvp_tables t

      where t.id = in_id and (t.player_a_id = uid or t.player_b_id = uid)

    ) then

      raise exception 'NOT_ALLOWED';

    end if;

    update public.pvp_watchers

       set kicked = true

     where table_id = in_id and user_id = in_target and kicked = false;

    get diagnostics cnt = row_count;

    if cnt > 0 then

      update public.pvp_tables set watcher_count = greatest(0, watcher_count - 1) where id = in_id;

    end if;



  elsif in_mode = 'pve' then

    if not exists (

      select 1 from public.pve_tables t

      where t.id = in_id and t.player_id = uid

    ) then

      raise exception 'NOT_ALLOWED';

    end if;

    update public.pve_watchers

       set kicked = true

     where table_id = in_id and user_id = in_target and kicked = false;

    get diagnostics cnt = row_count;

    if cnt > 0 then

      update public.pve_tables set watcher_count = greatest(0, watcher_count - 1) where id = in_id;

    end if;



  elsif in_mode = 'room' then

    if not exists (

      select 1 from public.private_rooms r where r.room_code = in_id and r.owner_id = uid

    ) and not exists (

      select 1 from public.room_members rm

      where rm.room_code = in_id and rm.user_id = uid and rm.role <> 'watcher'

    ) then

      raise exception 'NOT_ALLOWED';

    end if;

    update public.room_members

       set kicked = true

     where room_code = in_id and user_id = in_target and role = 'watcher' and kicked = false;

    get diagnostics cnt = row_count;

    if cnt > 0 then

      update public.private_rooms set watcher_count = greatest(0, watcher_count - 1) where room_code = in_id;

    end if;



  else

    raise exception 'BAD_MODE';

  end if;



  return cnt > 0;

end;

$function$;

CREATE OR REPLACE FUNCTION public.leave_room(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid    uuid := auth.uid();

  remain int;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.rooms

  set player_a_id = case when player_a_id = uid then null else player_a_id end,

      player_b_id = case when player_b_id = uid then null else player_b_id end,

      host_id = case when host_id = uid then null else host_id end,

      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,

      last_active_at = now()

  where room_code = code;



  delete from public.room_members

  where room_code = code and user_id = uid;



  select count(*) into remain

  from public.rooms

  where room_code = code

    and (player_a_id is not null or player_b_id is not null);



  -- 鍙屾柟鐜╁閮藉凡閫€鍑?-> 鎴块棿閿€姣侊紙鎴愬憳/瑙備紬闅忎箣绾ц仈閫€鍑猴級

  if remain = 0 then

    delete from public.room_members where room_code = code;

    delete from public.rooms where room_code = code;

  end if;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.leave_table(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.lobby_tables

  set player_a_id = case when player_a_id = uid then null else player_a_id end,

      player_b_id = case when player_b_id = uid then null else player_b_id end,

      status = case when player_a_id = uid or player_b_id = uid then 'waiting'

                    else status end,

      current_turn_id = case when current_turn_id = uid then null else current_turn_id end,

      last_active_at = now()

  where id = tid

    and (player_a_id = uid or player_b_id = uid);



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.mark_ready(in_table_id text DEFAULT NULL::text, in_room_code character DEFAULT NULL::bpchar)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid    uuid := auth.uid();

  gs     jsonb;

  a      uuid;

  b      uuid;

  target text;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if in_table_id is not null then

    select game_state, player_a_id, player_b_id into gs, a, b

    from public.lobby_tables where id = in_table_id for update;

    if gs is null then raise exception 'NOT_FOUND'; end if;

    target := in_table_id;

  elsif in_room_code is not null then

    select game_state, player_a_id, player_b_id into gs, a, b

    from public.rooms where room_code = in_room_code for update;

    if gs is null then raise exception 'NOT_FOUND'; end if;

    target := in_room_code;

  else

    raise exception 'NO_TARGET';

  end if;



  -- 蹇呴』鏄?A 鎴?B 搴х帺瀹?
  if uid <> a and uid <> b then

    raise exception 'NOT_PLAYER';

  end if;



  -- 灏氭湭鍒濆鍖栵紙濡傚厛鎵嬫湭鍐欏簱锛夛細琛ヤ竴浠界瓑寰呬腑鐨勫垵濮嬫鐩?
  if gs is null or gs = '{}'::jsonb then

    gs := '{"flowers":[1,2,3,4,5,6],"readyA":false,"readyB":false,"status":"waiting","winnerId":null,"moves":[]}'::jsonb;

  end if;



  if uid = a then

    gs := jsonb_set(gs, '{readyA}', 'true'::jsonb);

  else

    gs := jsonb_set(gs, '{readyB}', 'true'::jsonb);

  end if;



  -- 鍙屾柟閮藉噯澶囧ソ鎵嶅紑濮嬫父鎴?
  if (gs->>'readyA') = 'true' and (gs->>'readyB') = 'true' then

    gs := jsonb_set(gs, '{status}', 'playing'::jsonb);

    gs := jsonb_set(gs, '{turnUserId}', a::text::jsonb); -- A 鍏堟墜

  end if;



  -- 鏇存柊鏁版嵁搴?
  if in_table_id is not null then

    update public.lobby_tables

    set game_state = gs,

        last_active_at = now()

    where id = in_table_id;

  elsif in_room_code is not null then

    update public.rooms

    set game_state = gs,

        last_active_at = now()

    where room_code = in_room_code;

  end if;



  return gs;

end;

$function$;

CREATE OR REPLACE FUNCTION public.private_room_watcher_dec()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return old;

  end if;

  update public.private_rooms

  set watcher_count = greatest(0, watcher_count - 1)

  where room_code = old.room_code;

  return old;

end;

$function$;

CREATE OR REPLACE FUNCTION public.private_room_watcher_inc()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return new;

  end if;

  update public.private_rooms

  set watcher_count = watcher_count + 1

  where room_code = new.room_code;

  return new;

end;

$function$;

CREATE OR REPLACE FUNCTION public.purge_table_on_empty()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  v_scope   text;

  v_empty   boolean := false;   -- 鏁存宸叉棤鐜╁

  v_vacated boolean := false;   -- 鏈夊骇浣嶇敱鏈変汉鍙樻垚绌猴紝浣嗚繕鏈変汉鐣?
begin

  -- ---- 绠?scope锛屽苟鍒ゆ柇褰掗浂 / 鑵句綅 ----

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



  -- ---- 绗竴灞傦細鏈変汉璧般€佽繕鏈変汉鐣?=> 鏈疆缁撴潫锛屾竻鑱婂ぉ锛屽叾浣欎繚鐣?----

  if v_vacated and not v_empty then

    delete from public.chat_messages where table_id = v_scope;

    return new;

  end if;



  -- ---- 鏃犲叧鏇存柊锛氱洿鎺ユ斁琛?----

  if not v_empty then

    return new;

  end if;



  -- ---- 绗簩灞傦細鏁存褰掗浂 => 褰诲簳娓呭満 ----

  delete from public.chat_messages where table_id = v_scope;



  new.game_state := '{}'::jsonb;



  -- 鈽?鏂板锛氭湰妗屽凡褰掗浂锛岃浼楁暟蹇呬负 0銆傚湪杩欓噷鍐欐锛?
  --   鍚庨潰 delete watchers 杩炲甫瑙﹀彂鐨?*_watcher_dec() 鍙銆岃烦杩囥€嶅嵆鍙€?
  new.watcher_count := 0;



  if TG_ARGV[0] = 'pve' then

    delete from public.pve_watchers where table_id = new.id;

  else

    new.current_turn_id := null;

    new.turn_deadline_at := null;

    if TG_ARGV[0] = 'pvp' then

      delete from public.pvp_watchers where table_id = new.id;

    else

      delete from public.private_room_watchers where room_code = new.room_code;

    end if;

  end if;



  return new;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_end(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.pve_tables

  set status = 'seated', last_active_at = now()

  where id = tid and player_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_finish(in_player_id uuid, in_won boolean)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.pve_forfeit(tid text, state jsonb)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  final_state jsonb := coalesce(state, '{}'::jsonb);

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



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



  delete from public.pve_watchers

  where user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_heartbeat(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.pve_leave(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.pve_tables

  set player_id = null, status = 'open', last_active_at = now()

  where id = tid and player_id = uid;



  delete from public.pve_watchers

  where user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_report_state(tid text, state jsonb)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.pve_tables

  set game_state = coalesce(state, '{}'::jsonb),

      last_active_at = now()

  where id = tid and player_id = uid;



  if not found then

    raise exception 'NOT_YOUR_TABLE';

  end if;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_reset_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if OLD.player_id is not null and NEW.player_id is null then

    NEW.banned_ids := '{}'::uuid[];

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pve_sit(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  occupied uuid;

  sitting_elsewhere boolean;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.pve_tables where id = tid) then

    raise exception 'TABLE_NOT_FOUND';

  end if;



  select player_id into occupied from public.pve_tables where id = tid;



  if occupied is not null and occupied <> uid then

    raise exception 'TABLE_OCCUPIED';

  end if;

  if occupied = uid then

    delete from public.pve_watchers where user_id = uid;

    return true; -- 宸插潗鍦ㄨ繖妗岋紝骞傜瓑

  end if;



  select exists (select 1 from public.pve_tables

                 where player_id = uid and id <> tid)

  into sitting_elsewhere;

  if sitting_elsewhere then

    raise exception 'ALREADY_SITTING';

  end if;



  update public.pve_tables

  set player_id = uid, status = 'seated', game_state = '{}'::jsonb, last_active_at = now()

  where id = tid;



  delete from public.pve_watchers where user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_sit_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if (NEW.player_id is not null and NEW.player_id = ANY(coalesce(OLD.banned_ids, '{}'::uuid[]))) then

    raise exception 'BANNED_FROM_TABLE';

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pve_start(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.pve_tables

  set status = 'playing', last_active_at = now()

  where id = tid and player_id = uid;



  if not found then

    raise exception 'NOT_YOUR_TABLE';

  end if;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_unwatch(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  delete from public.pve_watchers

  where table_id = tid and user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_watch(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.pve_tables where id = tid) then

    raise exception 'TABLE_NOT_FOUND';

  end if;



  -- 宸插湪浠绘剰妗屼綔涓虹帺瀹跺叆搴?-> 涓嶈兘鍐嶈鎴橈紙涓€浜轰竴浣嶇疆锛?
  if exists (select 1 from public.pve_tables

             where player_id = uid) then

    raise exception 'ALREADY_PLAYER';

  end if;



  -- 鎹㈡瑙傛垬锛氬厛閫€鏃ц鎴橈紝鍐嶅潗鏂版锛堝敮涓€绾︽潫淇濊瘉涓€浜轰竴涓浼椾綅锛?
  if exists (select 1 from public.pve_watchers where user_id = uid) then

    delete from public.pve_watchers where user_id = uid;

  end if;



  insert into public.pve_watchers (table_id, user_id)

  values (tid, uid)

  on conflict (table_id, user_id) do nothing;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_watch_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if NEW.user_id = ANY(coalesce((select banned_ids from public.pve_tables where id = NEW.table_id), '{}'::uuid[])) then

    raise exception 'BANNED_FROM_TABLE';

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pve_watcher_dec()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return old;

  end if;

  update public.pve_tables

  set watcher_count = greatest(0, watcher_count - 1)

  where id = old.table_id;

  return old;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pve_watcher_inc()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return new;

  end if;

  update public.pve_tables

  set watcher_count = watcher_count + 1

  where id = new.table_id;

  return new;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_end(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.pvp_heartbeat(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  -- 鍙粰鑷繁鍗犵殑閭ｄ釜搴т綅鐩栨椂闂存埑锛涘彟涓€涓骇浣嶇殑鏃堕棿鎴充笉鍔紝

  -- 杩欐牱涓€涓骇浣嶅け鑱斾笉浼氳繛甯︽妸鍚屾鐨勪汉涔熺畻鎴愬け鑱斻€?
  update public.pvp_tables

  set last_active_at = now(),

      last_a_at = case when player_a_id = uid then now() else last_a_at end,

      last_b_at = case when player_b_id = uid then now() else last_b_at end

  where id = tid and (player_a_id = uid or player_b_id = uid);



  -- 瑙備紬涓嶅弬涓庡垽璐燂紝蹇冭烦鍙埛鏂板瓨娲绘椂闂?
  update public.pvp_watchers

  set last_active_at = now()

  where table_id = tid and user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_kick_watcher(tid text, target uuid, ban_minutes integer)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid  uuid := auth.uid();

  a_id uuid;

  b_id uuid;

  mins integer;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;

  if target is null then

    raise exception 'INVALID_TARGET';

  end if;



  select player_a_id, player_b_id into a_id, b_id

  from public.pvp_tables where id = tid;

  if a_id is null then

    raise exception 'TABLE_NOT_FOUND';

  end if;



  -- 閴存潈锛氬繀椤绘槸鏈鐜╁锛圓 鎴?B锛夈€傝浼椾箣闂淬€佽浼楀瑙備紬涓€寰嬫嫆缁濄€?
  if a_id <> uid and (b_id is null or b_id <> uid) then

    raise exception 'NOT_YOUR_TABLE';

  end if;



  if target = uid then

    raise exception 'CANNOT_KICK_SELF';

  end if;



  -- 鐩爣蹇呴』銆屽綋鍓嶇‘瀹炴槸鏈瑙備紬銆嶏紝鍚﹀垯涓嶅厑璁歌涪

  -- 锛堥『甯︽尅浣忚涪鐜╁銆佽涪涓嶅湪鍦虹殑浜恒€侀噸澶嶈涪锛?
  if not exists (select 1 from public.pvp_watchers

                 where table_id = tid and user_id = target) then

    raise exception 'NOT_A_WATCHER';

  end if;



  -- 绂佸叆鏃堕暱澶瑰埌 [0, 1440] 鍒嗛挓锛岄槻姝紶杩涙潵璐熸暟鎴栬秴澶у€?
  mins := least(greatest(coalesce(ban_minutes, 0), 0), 1440);



  -- 璁板綍绂佸叆锛坲psert锛氶噸澶嶈涪浜哄埛鏂版椂闀胯€屼笉鏄姤涓婚敭鍐茬獊锛?
  insert into public.pvp_watcher_bans (table_id, user_id, banned_until, created_by)

  values (tid, target, now() + make_interval(mins => mins), uid)

  on conflict (table_id, user_id) do update

    set banned_until = excluded.banned_until,

        created_by   = excluded.created_by,

        created_at   = now();



  -- 绉诲嚭瑙備紬锛沺vp_watcher_dec 瑙﹀彂鍣ㄨ嚜鍔ㄦ妸 watcher_count 鍑?1

  delete from public.pvp_watchers where table_id = tid and user_id = target;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_leave(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- winner 蹇呴』瀛樸€屽骇浣嶃€嶈€屼笉鏄?uid锛?
  -- 瀹㈡埛绔?renderPvpState / buildPvpLogText 鍒ゆ柇鐨勬槸 gs.winner 鏄惁绛変簬鑷繁鐨?
  -- 'a'/'b'锛屽瓨 uuid 浼氳璧㈠鍜岃緭瀹堕兘琚垽鎴愬钩灞€锛屼笖 finish_game 鍙栦笉鍒颁汉銆?
  -- uid 鍙敤浜庝紶缁?finish_game銆?
  winner_side := case when my_side = 'a' then 'b' when my_side = 'b' then 'a' else null end;

  winner_uid  := case when my_side = 'a' then b_id when my_side = 'b' then a_id else null end;



  -- 瀵瑰眬涓湰妗岀帺瀹堕€€鍑?= 鍒よ礋

  if cstate = 'playing' and my_side is not null and winner_uid is not null then

    -- 鐢?|| 鍚堝苟鑰屼笉鏄暣浣撹鐩栵細淇濅綇 scored锛堢粨绠楀箓绛夋爣璁帮級涓?flowers

    --锛堟鐩樺揩鐓э紝鍓╀笅閭ｄ綅灞忓箷涓婂畾鏍煎湪鍒よ礋閭ｄ竴鍒伙級锛屽彧娓呮帀 moves锛堟棩蹇楋級

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



    -- 鏈嶅姟绔珛鍗崇粨绠楋紝涓嶄緷璧栧墿涓嬮偅浣嶈繕鍦ㄨ疆璇€?
    -- finish_game 鍐呴儴浠?game_state.scored 骞傜瓑锛屽鎴风闅忓悗閭ｆ璋冪敤鏄┖鎿嶄綔銆?
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

      -- 杩欓噷鍙湪銆屾垜璧颁箣鍚庝竴涓骇浣嶉兘涓嶅墿銆嶆椂鎵嶆竻绌?game_state锛?
      -- 鑻ュ鎵嬭繕鍦紝淇濈暀涓婁竴鏉?UPDATE 鍐欏ソ鐨勫垽璐熺粨鏋?
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

$function$;

CREATE OR REPLACE FUNCTION public.pvp_my_ban_seconds(tid text)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  left_sec integer;

begin

  if uid is null then

    return 0;

  end if;

  select greatest(0, floor(extract(epoch from (banned_until - now()))))::integer

    into left_sec

  from public.pvp_watcher_bans

  where table_id = tid and user_id = uid;

  if left_sec is null then

    return 0;

  end if;

  return left_sec;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_ready(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- 鍙屾柟灏辩华涓斾笉鍦ㄥ灞€涓墠鍏佽寮€灞€锛?
  --   闃叉瀵瑰眬涓噸澶嶅紑灞€閲嶇疆妫嬬洏锛涘紑灞€鍗虫竻 ready锛屼笅涓€灞€闇€閲嶆柊鍑嗗

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

        -- 銆岃疆鍒?first_is_a銆嶇殑閭ｄ竴鍒昏捣绠楁湰杞?60 绉?
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

$function$;

CREATE OR REPLACE FUNCTION public.pvp_report_state(tid text, state jsonb)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- 瀵瑰眬涓墠杞鏍￠獙锛氫笉鍦ㄨ嚜宸卞洖鍚堜笉鍑嗘敼鐘舵€侊紝涔熶笉鑳芥浛瀵规柟缁撶畻

  if c_status = 'playing' then

    my_side := case when a_id = uid then 'a' when b_id = uid then 'b' else null end;

    if my_side is null then

      raise exception 'NOT_YOUR_TABLE';

    end if;

    if c_turn is null or c_turn <> uid then

      raise exception 'NOT_YOUR_TURN';

    end if;



    -- finished 鍒嗕袱绉嶏紝闈?winner 鏄笉鏄€屾垜銆嶅尯鍒嗭細

    --   winner = 鎴?  -> 姝ｅ父鑳滆礋锛堝鎵嬪凡鏃犺姳鍙嬁锛夛紝鎸夊師鏍锋斁琛?
    --   winner = 瀵规墜 -> 鍒よ礋澹版槑銆傚鎴风鐨勮秴鏃跺€掕鏃跺氨鏄蛋杩欐潯

    --     锛圠ocalGameActivity:2015锛夛紝鑰岃繖鏉′互鍓嶅彧鏍￠獙銆屾垜鏄綋鍓嶅洖鍚堣€呫€嶏紝

    --     鏀瑰寘灏辫兘鍦ㄤ换浣曟椂鍒诲甯冭嚜宸辫耽銆傜幇鍦ㄥ繀椤荤‘璁?deadline 鐪熺殑杩囦簡锛?
    --     鍒よ礋鏉冩敹褰掓湇鍔＄锛歝ron锛坮eap_turn_timeouts锛夋墠鏄秴鏃跺垽璐熺殑鎵ц鑰咃紝

    --     杩欓噷鍙槸璁╁鎴风鍒扮偣鏃堕偅涓€娆′笂鎶ヨ兘绔嬪埢鐢熸晥锛岀帺瀹朵笉鐢ㄥ共绛変竴鍒嗛挓銆?
    --

    -- 銆愬凡鐭ヤ俊浠昏竟鐣岋紝鏈湪鏈淇銆戞甯歌儨璐熼偅鏉′粛鐒朵俊瀹㈡埛绔笂鎶ョ殑妫嬬洏锛?
    -- checkGameEnd() 鍙槸鏈湴鎶?remainingFlowers[1..5] 鍔犳€荤湅鏄惁涓?0锛?
    -- 鏈嶅姟绔笉鏍￠獙 moves锛屾墍浠ヤ吉閫?flowers 鏁扮粍浠嶅彲鎻愬墠瀹ｅ竷鑾疯儨銆?
    -- 瑕佹牴娌诲緱璁╂湇鍔＄閲嶆斁骞舵牎楠屾瘡涓€姝ワ紙鎹㈡墜鏉冦€佹嬁鑺辨暟銆佽儨璐熸潯浠讹級锛?
    -- 閭ｆ槸鍙︿竴浠朵簨锛屼笉灞炰簬銆?0 绉掑洖鍚堝€掕鏃朵互鏈嶅姟绔负鍑嗐€嶇殑鑼冨洿銆?
    if state->>'status' = 'finished' and state->>'winner' <> my_side then

      if c_deadline is null or c_deadline > now() then

        raise exception 'NOT_EXPIRED';

      end if;

    end if;

  end if;



  st_status := coalesce(state->>'status', 'ongoing');

  new_turn := coalesce(state->>'turn', '');



  -- and (player_a_id = uid or player_b_id = uid) 鏄湰娆℃柊澧炵殑闃叉姢锛?
  -- 绂诲腑鍚庢櫄鍒扮殑涓婃姤鍦ㄨ繖閲屽彉鎴愮┖鎿嶄綔

  update public.pvp_tables

  set game_state = state,

      current_turn_id = case

        when st_status = 'finished' then null

        when new_turn = 'a' then a_id

        when new_turn = 'b' then b_id

        else null

      end,

      -- 鎹㈡墜灏辨妸鏈疆 60 绉掔粰鏂拌鍔ㄦ柟锛涚粨鏉熸竻绌恒€俤eadline 瀛樺湪鐙珛鍒楄€屼笉鏄?
      -- game_state 閲岋紝姝ｆ槸鍥犱负涓婇潰閭ｅ彞 game_state = state 鏄暣鍖呰鐩栥€?
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

    -- 宸茬粨绠楄繃灏变笉鍐嶈鐩栥€?
    -- 鍥炲悎瓒呮椂鏃?reap_turn_timeouts 鍏堝垽璐熷苟缁撶畻锛坰cored=true锛夛紝鑰岃秴鏃惰€?
    -- 鎵嬩笂鍙兘姝ｅソ鏈変竴鎵嬪湪璺笂鐨勪笂鎶ヨ繖鏃舵墠鍒帮細c_status 宸叉槸 'seated'锛?
    -- 涓婇潰閭ｆ杞鏍￠獙鏁村潡琚烦杩囷紝鑻ヤ笉鍔犺繖涓€鏉★紝game_state = state 浼氭妸

    -- finished 鍜?scored 涓€璧锋姽鎺夛紝瀵瑰眬澶嶆椿鎴?playing锛屼笖 scored 涓㈠け鍙兘瀵艰嚧

    -- 閲嶅璁″垎銆傝繖鍜屼笂闈€岀甯悗鏅氬埌鐨勪笂鎶ャ€嶆槸鍚屼竴绫诲啓鍥炵珵鎬併€?
    and (game_state->>'scored') is distinct from 'true';



  if not found then

    -- 宸茬粡绂诲腑锛堟垨琚涪锛夈€佹垨鏈眬宸茬粨绠楋紝杩欐涓婃姤鐩存帴蹇界暐锛屼笉褰撲綔閿欒

    return true;

  end if;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_reset_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if (OLD.player_a_id is not null or OLD.player_b_id is not null)

     and NEW.player_a_id is null and NEW.player_b_id is null then

    NEW.banned_ids := '{}'::uuid[];

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pvp_sit_a(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

      -- 涓婁竴灞€宸茬粨鏉熷氨椤烘墜娓呮帀鍙兘娈嬬暀鐨勬埅姝㈡椂闂达紙姝ｅ父璺緞涓婂畠宸茶鍒よ礋閫昏緫

      -- 缃┖锛岃繖閲屾槸銆岃杈撲笌瓒呮椂鍒よ礋鍚屾椂鍒拌揪銆嶈繖绫昏竟鐣屼笅鐨勫箓绛変繚闄╋級

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

$function$;

CREATE OR REPLACE FUNCTION public.pvp_sit_b(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.pvp_sit_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

declare b uuid[] := coalesce(OLD.banned_ids, '{}'::uuid[]);

begin

  if (NEW.player_a_id is not null and NEW.player_a_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;

  if (NEW.player_b_id is not null and NEW.player_b_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pvp_turn_seconds()
 RETURNS integer
 LANGUAGE sql
 IMMUTABLE
AS $function$ select 60 $function$;

CREATE OR REPLACE FUNCTION public.pvp_unwatch(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  delete from public.pvp_watchers

  where table_id = tid and user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_watch(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.pvp_tables where id = tid) then

    raise exception 'TABLE_NOT_FOUND';

  end if;



  -- 绂佸叆鏍￠獙

  if exists (select 1 from public.pvp_watcher_bans

             where table_id = tid and user_id = uid and banned_until > now()) then

    raise exception 'BANNED_FROM_TABLE';

  end if;



  -- 宸插湪浠绘剰妗屼綔涓虹帺瀹跺叆搴?-> 涓嶈兘鍐嶈鎴橈紙涓€浜轰竴浣嶇疆锛?
  if exists (select 1 from public.pvp_tables

             where player_a_id = uid or player_b_id = uid) then

    raise exception 'ALREADY_PLAYER';

  end if;



  -- 鎹㈡瑙傛垬锛氬厛閫€鏃ц鎴橈紝鍐嶅潗鏂版

  if exists (select 1 from public.pvp_watchers where user_id = uid) then

    delete from public.pvp_watchers where user_id = uid;

  end if;



  insert into public.pvp_watchers (table_id, user_id)

  values (tid, uid)

  on conflict (table_id, user_id) do nothing;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_watch_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if NEW.user_id = ANY(coalesce((select banned_ids from public.pvp_tables where id = NEW.table_id), '{}'::uuid[])) then

    raise exception 'BANNED_FROM_TABLE';

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.pvp_watcher_dec()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return old;   -- 杩炲甫瑙﹀彂锛歱urge 宸叉妸 watcher_count 缃?0锛岃烦杩囧嵆鍙?
  end if;

  update public.pvp_tables

  set watcher_count = greatest(0, watcher_count - 1)

  where id = old.table_id;

  return old;

end;

$function$;

CREATE OR REPLACE FUNCTION public.pvp_watcher_inc()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if pg_trigger_depth() > 1 then

    return new;   -- 杩炲甫瑙﹀彂锛氱埗琛ㄦ琚灞傚懡浠や慨鏀癸紝涓嶈兘浜屾鏀瑰悓涓€琛?
  end if;

  update public.pvp_tables

  set watcher_count = watcher_count + 1

  where id = new.table_id;

  return new;

end;

$function$;

CREATE OR REPLACE FUNCTION public.random_room_code()
 RETURNS character
 LANGUAGE sql
 STABLE
AS $function$

  select lpad((floor(random() * 9000) + 1000)::text, 4, '0')::char(4)

$function$;

CREATE OR REPLACE FUNCTION public.random_table_id()
 RETURNS text
 LANGUAGE sql
 STABLE
AS $function$

  select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', (random() * 31)::int + 1, 1), '')

  from generate_series(1, 6)

$function$;

CREATE OR REPLACE FUNCTION public.reap_stale_seats()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  r           record;

  v_dead_line interval := interval '30 seconds';

  v_dead_side text;

  v_dead_uid  uuid;

  v_win_side  text;

  v_win_uid   uuid;

begin

  -- ---------- 浜轰汉妗?----------

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

      -- 鍏ㄥ憳鎺夌嚎锛氭病鏈夎儨璐熷彲鍒わ紝鐩存帴鏁存娓呯┖

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



      -- 瀵瑰眬涓墠鍒よ礋缁撶畻

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



      -- 閲婃斁澶辫仈搴т綅锛堣Е鍙戝櫒姝ゆ椂鍙竻鑱婂ぉ锛岃浼椾繚鐣欙級

      update public.pvp_tables

      set player_a_id = case when v_dead_side = 'a' then null else player_a_id end,

          player_b_id = case when v_dead_side = 'b' then null else player_b_id end,

          last_a_at   = case when v_dead_side = 'a' then null else last_a_at end,

          last_b_at   = case when v_dead_side = 'b' then null else last_b_at end,

          ready_a = case when v_dead_side = 'a' then false else ready_a end,

          ready_b = case when v_dead_side = 'b' then false else ready_b end,

          -- 娌℃湁琛屽姩鏂瑰氨娌℃湁鍥炲悎鍙秴鏃躲€傝繖涓€鍙ュ拰涓婇潰鍒よ礋閭ｅ彞閲嶅涓嶅啿绐侊細

          -- 涓婇潰绠?playing 鍒嗘敮锛岃繖閲岀 seated 鍒嗘敮锛堟湰鏉ュ氨娌″湪璧版锛?
          -- 浣嗗彲鑳芥畫鐣欑潃涓婁竴灞€鐨?deadline锛宺eap_turn_timeouts 浼氭嬁瀹冭鍒わ級銆?
          turn_deadline_at = null,

          status = 'seated',

          last_active_at = now()

      where id = r.id;

    end if;

  end loop;



  -- ---------- 绉佸瘑鎴块棿锛堝彛寰勭浉鍚岋紝绉垎涓?+10/-2锛?----------

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



  -- ---------- 鍗曡竟鍗犲骇锛氫竴涓汉璧颁簡/宕╀簡锛屽彟涓€涓汉杩樿禆鍦ㄥ骇浣嶄笂 ----------

  -- 涓婇潰涓や釜寰幆閮借姹傘€屼袱涓骇浣嶉兘鏈変汉銆嶏紝鎵€浠ヨ繖绉嶈〃涓€涓兘鎵笉鍒般€?
  -- 杩欐鏄€屾湁浜洪€€涓嶅嚭妗屽瓙銆佹涓婁竴鐩存寕鐫€涓婁竴灞€ finished銆嶇殑鏉ユ簮锛?
  -- 瀵规墜璧颁簡浠ュ悗 pvp_leave 鎶?status 鐣欏湪 seated锛?
  -- 鑰屽洖鏀跺櫒鍥犱负灏戜簡涓€涓汉鑰屾案杩滀笉纰板畠銆?
  --

  -- 鍙湪銆屽敮涓€鍗犲骇鑰呰嚜宸变篃鍋滀簡蹇冭烦銆嶆椂鎵嶅姩锛屾墍浠ユ椿浜轰笉浼氳璇竻锛?
  -- 浠栧湪 App 閲屽氨涓€鐩村湪鍙戝績璺筹紝last_*_at 姘歌繙鏄柊鐨勩€?
  -- 琚喕/琚潃/寮辩綉鏂簡鎵嶄細钀藉埌杩欓噷銆?
  --

  -- 瀵规墜宸茬粡涓嶅湪锛屾病鏈夎儨璐熷彲鍒わ紝涔熶笉璁″垎 鈥斺€?鍗曠函鎶婂骇浣嶆斁鎺夈€?
  -- game_state 涓€璧锋竻绌猴細娈嬬暀鐨勪笂涓€灞€ finished/winner 浼氳涓嬩竴涓繘鏉?
  -- 鐨勪汉鍏堢湅鍒颁笂涓€灞€鐨勭粨鏋溿€?
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



  -- 绉佸瘑鎴垮悓涓€鍙ｅ緞

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

$function$;

CREATE OR REPLACE FUNCTION public.reap_turn_timeouts()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  r          record;

  v_dead_side text;

  v_dead_uid uuid;

  v_win_side text;

  v_win_uid  uuid;

begin

  -- ---------- 浜轰汉妗?----------

  for r in

    select t.id, t.player_a_id, t.player_b_id, t.current_turn_id,

           case when t.current_turn_id = t.player_a_id then 'a' else 'b' end as turn_side

      from public.pvp_tables t

     where t.status = 'playing'

       and t.current_turn_id is not null

       and t.turn_deadline_at is not null

       and t.turn_deadline_at < now()

       -- 瀵规墜蹇冭烦蹇呴』鍦?60 绉掑唴锛屽惁鍒欎氦缁?reap_stale_seats 鏁存娓呯┖

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



    -- status / current_turn_id / turn_deadline_at 閮芥斁杩?WHERE锛?
    -- 瀹㈡埛绔彲鑳藉垰濂藉湪杩欐湡闂磋惤浜嗗瓙鎹簡鎵嬶紝閲嶈瘎浼板悗鏉′欢涓嶆垚绔?-> 绌烘搷浣溿€?
    -- 鍙垽銆屾湰杞紑濮嬫椂杞埌鐨勯偅涓汉鐨?deadline 宸茶繃銆嶏紝涓嶅垽琛屾暟鍙樺寲銆?
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



  -- ---------- 绉佸瘑鎴块棿锛堝彛寰勭浉鍚岋紝绉垎涓?+10/-2锛?----------

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

$function$;

CREATE OR REPLACE FUNCTION public.request_turn_timeout_check(p_table_id text DEFAULT NULL::text, p_room_code character DEFAULT NULL::bpchar)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  v_due boolean;

begin

  if p_table_id is null and p_room_code is null then

    return false;

  end if;



  -- 鍏堝彧璇诲湴纭銆岃繖灞€纭疄鍒版湡涓旇鍒ゃ€嶏紝鎷夸笉鍒板氨涓嶅啓銆?
  -- 涓?reap_turn_timeouts 鐨勭瓫閫夋潯浠朵繚鎸佷竴鑷达紝閬垮厤涓ゆ潯璺緞鍙ｅ緞涓嶅悓銆?
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



  -- 鏉′欢鎴愮珛锛屼氦缁欏敮涓€鐨勫垽璐熷疄鐜板幓鍐欙紙瀹冨唴閮ㄨ繕浼氭寜 current_turn_id 閲嶆柊

  -- 鏍￠獙涓€娆★紝鎵€浠ヨ繖閲屼笉瀛樺湪 TOCTOU锛氬苟鍙戞崲鎵嬫椂閭ｆ update 浼氳惤绌猴級銆?
  perform public.reap_turn_timeouts();

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.reset_room_status(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  is_host boolean;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  -- 妫€鏌ユ槸鍚︽槸鎴夸富

  select host_id = uid into is_host

  from public.rooms 

  where room_code = code

    and status <> 'finished';



  if not is_host then

    raise exception 'NOT_HOST';

  end if;



  -- 閲嶇疆鎴块棿鐘舵€佸埌绛夊緟鐘舵€?
  update public.rooms

  set status = 'waiting',

      current_turn_id = null,

      last_active_at = now()

  where room_code = code;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.reset_watcher_kick(in_mode text, in_id text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  if in_mode = 'pvp' then

    delete from public.pvp_watchers where table_id = in_id and kicked = true;

  elsif in_mode = 'pve' then

    delete from public.pve_watchers where table_id = in_id and kicked = true;

  elsif in_mode = 'room' then

    delete from public.room_members where room_code = in_id and kicked = true;

  end if;

end;

$function$;

CREATE OR REPLACE FUNCTION public.rls_auto_enable()
 RETURNS event_trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog'
AS $function$
DECLARE
  cmd record;
BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
        RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      END;
     ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;
     END IF;
  END LOOP;
END;
$function$;

CREATE OR REPLACE FUNCTION public.room_create()
 RETURNS character
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid  uuid := auth.uid();

  code text;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  loop

    code := ltrim(((floor(random() * 9000) + 1000)::int)::text);

    exit when not exists (select 1 from public.private_rooms where room_code = code);

  end loop;



  insert into public.private_rooms (room_code, status)

  values (code, 'open');



  return code::char(4);

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_end(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.room_heartbeat(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.room_join(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.private_rooms

                 where room_code = code) then

    raise exception 'ROOM_NOT_FOUND';

  end if;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_kick_watcher(code character, target uuid, ban_minutes integer)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid  uuid := auth.uid();

  a_id uuid;

  b_id uuid;

  mins integer;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;

  if target is null then

    raise exception 'INVALID_TARGET';

  end if;



  select player_a_id, player_b_id into a_id, b_id

  from public.private_rooms where room_code = code;

  if a_id is null then

    raise exception 'ROOM_NOT_FOUND';

  end if;



  if a_id <> uid and (b_id is null or b_id <> uid) then

    raise exception 'NOT_YOUR_TABLE';

  end if;



  if target = uid then

    raise exception 'CANNOT_KICK_SELF';

  end if;



  if not exists (select 1 from public.private_room_watchers

                 where room_code = code and user_id = target) then

    raise exception 'NOT_A_WATCHER';

  end if;



  mins := least(greatest(coalesce(ban_minutes, 0), 0), 1440);



  insert into public.private_room_watcher_bans (room_code, user_id, banned_until, created_by)

  values (code, target, now() + make_interval(mins => mins), uid)

  on conflict (room_code, user_id) do update

    set banned_until = excluded.banned_until,

        created_by   = excluded.created_by,

        created_at   = now();



  -- private_room_watcher_dec 瑙﹀彂鍣ㄨ嚜鍔ㄦ妸 watcher_count 鍑?1

  delete from public.private_room_watchers

  where room_code = code and user_id = target;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_leave(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

  -- 鍚?pvp_leave锛歸inner 瀛樺骇浣嶏紝uid 鍙粰 finish_game

  winner_side := case when my_side = 'a' then 'b' when my_side = 'b' then 'a' else null end;

  winner_uid  := case when my_side = 'a' then b_id when my_side = 'b' then a_id else null end;



  -- 瀵瑰眬涓湰鎴跨帺瀹堕€€鍑?= 鍒よ礋

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



  -- 鍙湁鏁存娌′汉浜嗘墠娓呯┖瑙傛垬鍚嶅崟锛堣繕鏈変汉鐣欏湪鎴块噷绛夊鎵嬫椂锛岃鎴樹綅淇濈暀锛?
  select player_a_id, player_b_id into a_id, b_id

  from public.private_rooms where room_code = code;

  if a_id is null and b_id is null then

    delete from public.private_room_watchers where room_code = code;

  end if;



  delete from public.private_room_watchers where user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_my_ban_seconds(code character)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

  left_sec integer;

begin

  if uid is null then

    return 0;

  end if;

  select greatest(0, floor(extract(epoch from (banned_until - now()))))::integer

    into left_sec

  from public.private_room_watcher_bans

  where room_code = code and user_id = uid;

  if left_sec is null then

    return 0;

  end if;

  return left_sec;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_ready(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- 鍙屾柟灏辩华涓斾笉鍦ㄥ灞€涓墠鍏佽寮€灞€

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

$function$;

CREATE OR REPLACE FUNCTION public.room_report_state(code character, state jsonb)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- 瀵瑰眬涓墠杞鏍￠獙銆傚垽璐熷０鏄庡繀椤绘牳瀵?deadline锛岀悊鐢卞悓 pvp_report_state銆?
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

    -- 宸茬粨绠楄繃灏变笉鍐嶈鐩栵紝鐞嗙敱鍚?pvp_report_state锛?
    -- reap_turn_timeouts 鍒よ礋缁撶畻鍚庯紝瓒呮椂鑰呮墜涓婂湪閫旂殑 ongoing 涓婃姤浼氳蛋鍒拌繖閲岋紝

    -- 涓嶆尅浣忓氨浼氭妸 finished / scored 鎶规帀骞舵妸妗屽瓙鏀瑰洖 playing銆?
    and (game_state->>'scored') is distinct from 'true';



  if not found then

    -- 宸茬粡绂诲腑锛堟垨琚涪锛夈€佹垨鏈眬宸茬粨绠?
    return true;

  end if;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_reset_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if (OLD.player_a_id is not null or OLD.player_b_id is not null)

     and NEW.player_a_id is null and NEW.player_b_id is null then

    NEW.banned_ids := '{}'::uuid[];

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.room_sit(code character)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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



  -- 宸茬粡鍦ㄦ埧閲屼笖宸插崰鏌愪竴搴?
  if a_id = uid then

    delete from public.private_room_watchers where user_id = uid;

    return 'a';

  end if;

  if b_id = uid then

    delete from public.private_room_watchers where user_id = uid;

    return 'b';

  end if;



  -- 姣忎汉涓€娆′竴搴э紙鐜╁浣嶆垨瑙備紬浣嶏級

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

$function$;

CREATE OR REPLACE FUNCTION public.room_sit_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

declare b uuid[] := coalesce(OLD.banned_ids, '{}'::uuid[]);

begin

  if (NEW.player_a_id is not null and NEW.player_a_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;

  if (NEW.player_b_id is not null and NEW.player_b_id = ANY(b)) then raise exception 'BANNED_FROM_TABLE'; end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.room_unwatch(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  delete from public.private_room_watchers

  where room_code = code and user_id = uid;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_watch(code character)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if not exists (select 1 from public.private_rooms where room_code = code) then

    raise exception 'ROOM_NOT_FOUND';

  end if;



  -- 绂佸叆鏍￠獙

  if exists (select 1 from public.private_room_watcher_bans

             where room_code = code and user_id = uid and banned_until > now()) then

    raise exception 'BANNED_FROM_ROOM';

  end if;



  -- 宸插湪浠绘剰鎴块棿浣滀负鐜╁鍏ュ骇 -> 涓嶈兘鍐嶈鎴橈紙涓€浜轰竴浣嶇疆锛?
  if exists (select 1 from public.private_rooms

             where player_a_id = uid or player_b_id = uid) then

    raise exception 'ALREADY_SITTING';

  end if;



  -- 鎹㈡埧闂磋鎴橈細鍏堥€€鏃ц鎴橈紝鍐嶅潗鏂版埧闂?
  if exists (select 1 from public.private_room_watchers where user_id = uid) then

    delete from public.private_room_watchers where user_id = uid;

  end if;



  insert into public.private_room_watchers (room_code, user_id)

  values (code, uid)

  on conflict (room_code, user_id) do nothing;



  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.room_watch_ban_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$

begin

  if TG_OP = 'INSERT' and NEW.role = 'watcher'

     and NEW.user_id = ANY(coalesce((select banned_ids from public.private_rooms where room_code = NEW.room_code), '{}'::uuid[])) then

    raise exception 'BANNED_FROM_TABLE';

  end if;

  return NEW;

end; $function$

CREATE OR REPLACE FUNCTION public.settle_round(in_table_id text DEFAULT NULL::text, in_room_code character DEFAULT NULL::bpchar, in_room_type text DEFAULT 'lobby'::text, in_winner_id uuid DEFAULT NULL::uuid, in_loser_id uuid DEFAULT NULL::uuid, in_moves jsonb DEFAULT NULL::jsonb)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  gid        uuid;

  delta_win  int := case when in_room_type = 'private' then 10 else 5 end;

  delta_lose int := case when in_room_type = 'private' then -2 else -1 end;

  claimed    boolean := false;

begin

  if in_winner_id is null or in_loser_id is null then raise exception 'INVALID_RESULT'; end if;

  if in_winner_id = in_loser_id then raise exception 'INVALID_RESULT'; end if;



  -- 浠?game_state.scored 璁ら杩欎竴灞€锛氳皝鍏堝埌璋佺粨绠楋紝鍚庡埌鐨勮嚜鍔ㄥ彉绌烘搷浣?
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

$function$;

CREATE OR REPLACE FUNCTION public.sit_lobby_table(tid text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid  uuid := auth.uid();

  seat text;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  -- 宸插湪璇ユ鐨勭帺瀹剁洿鎺ヨ繑鍥炲叾搴т綅锛堥噸杩?鏂嚎鎭㈠锛?
  select case

           when player_a_id = uid then 'a'

           when player_b_id = uid then 'b'

         end into seat

  from public.lobby_tables

  where id = tid;



  if seat is not null then

    update public.lobby_tables set last_active_at = now() where id = tid;

    return seat;

  end if;



  -- 鍧?A 浣?
  update public.lobby_tables

  set player_a_id = uid,

      last_active_at = now()

  where id = tid and player_a_id is null;



  if found then

    return 'a';

  end if;



  -- 鍧?B 浣?
  update public.lobby_tables

  set player_b_id = uid,

      status = case when player_a_id is not null then 'playing' else 'waiting' end,

      last_active_at = now()

  where id = tid

    and player_b_id is null

    and player_a_id is distinct from uid;



  if found then

    return 'b';

  end if;



  raise exception 'TABLE_FULL';

end;

$function$;

CREATE OR REPLACE FUNCTION public.sit_room(code character)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid   uuid := auth.uid();

  seat  text;

  a     uuid;

  b     uuid;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  -- 鑾峰彇褰撳墠鎴块棿鐘舵€?
  select player_a_id, player_b_id into a, b

  from public.rooms 

  where room_code = code 

    and status <> 'finished'

  for update;



  if a is null and b is null then

    -- 绌烘埧锛氬潗 A 浣嶏紙鎴夸富锛?
    update public.rooms

    set player_a_id = uid,

        status = 'waiting',

        last_active_at = now()

    where room_code = code;

    seat := 'a';

    

    -- 娣诲姞鍒版垚鍛樺垪琛?
    insert into public.room_members (room_code, user_id, role)

    values (code, uid, 'player')

    on conflict (room_code, user_id) do nothing;

    

  elsif a is not null and b is null and a <> uid then

    -- A 琚崰锛孊 浣嶇┖锛氬潗 B 浣?
    update public.rooms

    set player_b_id = uid,

        status = 'playing',

        last_active_at = now()

    where room_code = code;

    seat := 'b';

    

    -- 娣诲姞鍒版垚鍛樺垪琛?
    insert into public.room_members (room_code, user_id, role)

    values (code, uid, 'player')

    on conflict (room_code, user_id) do nothing;

    

  elsif a = uid and b is null then

    -- 宸茬粡鍦?A 浣嶏紝灏濊瘯閲嶅鍏ュ骇

    seat := 'a';

    

  elsif b = uid and a is not null then

    -- 宸茬粡鍦?B 浣嶏紝灏濊瘯閲嶅鍏ュ骇

    seat := 'b';

    

  else

    -- 宸叉弧鎴栬€呮槸鑷繁

    raise exception 'SEAT_UNAVAILABLE';

  end if;



  return seat;

end;

$function$;

CREATE OR REPLACE FUNCTION public.turn_secs_left(p_table_id text DEFAULT NULL::text, p_room_code character DEFAULT NULL::bpchar)
 RETURNS integer
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

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

$function$;

CREATE OR REPLACE FUNCTION public.unwatch_lobby_table(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.lobby_tables

  set watcher_count = greatest(0, watcher_count - 1),

      last_active_at = now()

  where id = tid;



  if not found then

    raise exception 'TABLE_NOT_FOUND';

  end if;

  return true;

end;

$function$;

CREATE OR REPLACE FUNCTION public.update_game_state(in_table_id text DEFAULT NULL::text, in_room_code character DEFAULT NULL::bpchar, new_state jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid    uuid := auth.uid();

  gs     jsonb;

  target text;

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  if in_table_id is not null then

    select game_state into gs from public.lobby_tables where id = in_table_id for update;

    if gs is null then raise exception 'NOT_FOUND'; end if;

    if (gs->>'turnUserId') is not null and (gs->>'turnUserId')::uuid <> uid then

      raise exception 'NOT_YOUR_TURN';

    end if;

    update public.lobby_tables

    set game_state = new_state,

        last_active_at = now()

    where id = in_table_id;

    target := in_table_id;

  elsif in_room_code is not null then

    select game_state into gs from public.rooms where room_code = in_room_code for update;

    if gs is null then raise exception 'NOT_FOUND'; end if;

    if (gs->>'turnUserId') is not null and (gs->>'turnUserId')::uuid <> uid then

      raise exception 'NOT_YOUR_TURN';

    end if;

    update public.rooms

    set game_state = new_state,

        last_active_at = now()

    where room_code = in_room_code;

    target := in_room_code;

  else

    raise exception 'NO_TARGET';

  end if;



  return new_state;

end;

$function$;

CREATE OR REPLACE FUNCTION public.watch_lobby_table(tid text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

declare

  uid uuid := auth.uid();

begin

  if uid is null then

    raise exception 'NOT_AUTHENTICATED';

  end if;



  update public.lobby_tables

  set watcher_count = watcher_count + 1,

      last_active_at = now()

  where id = tid;



  if not found then

    raise exception 'TABLE_NOT_FOUND';

  end if;

  return true;

end;

$function$;

drop trigger if exists "private_room_watchers_dec" on "public"."private_room_watchers";
CREATE TRIGGER private_room_watchers_dec AFTER DELETE ON public.private_room_watchers FOR EACH ROW EXECUTE FUNCTION private_room_watcher_dec();
drop trigger if exists "private_room_watchers_inc" on "public"."private_room_watchers";
CREATE TRIGGER private_room_watchers_inc AFTER INSERT ON public.private_room_watchers FOR EACH ROW EXECUTE FUNCTION private_room_watcher_inc();
drop trigger if exists "room_purge_on_empty" on "public"."private_rooms";
CREATE TRIGGER room_purge_on_empty BEFORE UPDATE ON public.private_rooms FOR EACH ROW EXECUTE FUNCTION purge_table_on_empty('room');
drop trigger if exists "room_reset_ban_guard" on "public"."private_rooms";
CREATE TRIGGER room_reset_ban_guard BEFORE UPDATE ON public.private_rooms FOR EACH ROW EXECUTE FUNCTION room_reset_ban_guard();
drop trigger if exists "room_sit_ban_guard" on "public"."private_rooms";
CREATE TRIGGER room_sit_ban_guard BEFORE INSERT OR UPDATE ON public.private_rooms FOR EACH ROW EXECUTE FUNCTION room_sit_ban_guard();
drop trigger if exists "pve_purge_on_empty" on "public"."pve_tables";
CREATE TRIGGER pve_purge_on_empty BEFORE UPDATE ON public.pve_tables FOR EACH ROW EXECUTE FUNCTION purge_table_on_empty('pve');
drop trigger if exists "pve_reset_ban_guard" on "public"."pve_tables";
CREATE TRIGGER pve_reset_ban_guard BEFORE UPDATE ON public.pve_tables FOR EACH ROW EXECUTE FUNCTION pve_reset_ban_guard();
drop trigger if exists "pve_sit_ban_guard" on "public"."pve_tables";
CREATE TRIGGER pve_sit_ban_guard BEFORE INSERT OR UPDATE ON public.pve_tables FOR EACH ROW EXECUTE FUNCTION pve_sit_ban_guard();
drop trigger if exists "pve_watch_ban_guard" on "public"."pve_watchers";
CREATE TRIGGER pve_watch_ban_guard BEFORE INSERT ON public.pve_watchers FOR EACH ROW EXECUTE FUNCTION pve_watch_ban_guard();
drop trigger if exists "pve_watchers_dec" on "public"."pve_watchers";
CREATE TRIGGER pve_watchers_dec AFTER DELETE ON public.pve_watchers FOR EACH ROW EXECUTE FUNCTION pve_watcher_dec();
drop trigger if exists "pve_watchers_inc" on "public"."pve_watchers";
CREATE TRIGGER pve_watchers_inc AFTER INSERT ON public.pve_watchers FOR EACH ROW EXECUTE FUNCTION pve_watcher_inc();
drop trigger if exists "pvp_purge_on_empty" on "public"."pvp_tables";
CREATE TRIGGER pvp_purge_on_empty BEFORE UPDATE ON public.pvp_tables FOR EACH ROW EXECUTE FUNCTION purge_table_on_empty('pvp');
drop trigger if exists "pvp_reset_ban_guard" on "public"."pvp_tables";
CREATE TRIGGER pvp_reset_ban_guard BEFORE UPDATE ON public.pvp_tables FOR EACH ROW EXECUTE FUNCTION pvp_reset_ban_guard();
drop trigger if exists "pvp_sit_ban_guard" on "public"."pvp_tables";
CREATE TRIGGER pvp_sit_ban_guard BEFORE INSERT OR UPDATE ON public.pvp_tables FOR EACH ROW EXECUTE FUNCTION pvp_sit_ban_guard();
drop trigger if exists "pvp_watch_ban_guard" on "public"."pvp_watchers";
CREATE TRIGGER pvp_watch_ban_guard BEFORE INSERT ON public.pvp_watchers FOR EACH ROW EXECUTE FUNCTION pvp_watch_ban_guard();
drop trigger if exists "pvp_watchers_dec" on "public"."pvp_watchers";
CREATE TRIGGER pvp_watchers_dec AFTER DELETE ON public.pvp_watchers FOR EACH ROW EXECUTE FUNCTION pvp_watcher_dec();
drop trigger if exists "pvp_watchers_inc" on "public"."pvp_watchers";
CREATE TRIGGER pvp_watchers_inc AFTER INSERT ON public.pvp_watchers FOR EACH ROW EXECUTE FUNCTION pvp_watcher_inc();
drop trigger if exists "room_watch_ban_guard" on "public"."room_members";
CREATE TRIGGER room_watch_ban_guard BEFORE INSERT OR UPDATE ON public.room_members FOR EACH ROW EXECUTE FUNCTION room_watch_ban_guard();
alter table "public"."chat_messages" enable row level security;
alter table "public"."games" enable row level security;
alter table "public"."lobby_tables" enable row level security;
alter table "public"."private_room_watcher_bans" enable row level security;
alter table "public"."private_room_watchers" enable row level security;
alter table "public"."private_rooms" enable row level security;
alter table "public"."profiles" enable row level security;
alter table "public"."pve_tables" enable row level security;
alter table "public"."pve_watchers" enable row level security;
alter table "public"."pvp_tables" enable row level security;
alter table "public"."pvp_watcher_bans" enable row level security;
alter table "public"."pvp_watchers" enable row level security;
alter table "public"."room_members" enable row level security;
alter table "public"."rooms" enable row level security;
create policy "chat_messages_delete" on "public"."chat_messages" as PERMISSIVE for delete to public using ((auth.role() = 'authenticated'::text));
create policy "chat_messages_insert" on "public"."chat_messages" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "chat_messages_select" on "public"."chat_messages" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "games_insert" on "public"."games" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "games_select" on "public"."games" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "lobby_tables_insert" on "public"."lobby_tables" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "lobby_tables_select" on "public"."lobby_tables" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "lobby_tables_update" on "public"."lobby_tables" as PERMISSIVE for update to public using ((auth.role() = 'authenticated'::text)) with check ((auth.role() = 'authenticated'::text));
create policy "private_room_watcher_bans_select" on "public"."private_room_watcher_bans" as PERMISSIVE for select to public using ((auth.uid() = user_id));
create policy "private_room_watchers_delete" on "public"."private_room_watchers" as PERMISSIVE for delete to public using ((auth.uid() = user_id));
create policy "private_room_watchers_insert" on "public"."private_room_watchers" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "private_room_watchers_select" on "public"."private_room_watchers" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "private_rooms_select" on "public"."private_rooms" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "profiles_select" on "public"."profiles" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "profiles_update" on "public"."profiles" as PERMISSIVE for update to public using ((auth.uid() = id));
create policy "pve_tables_select" on "public"."pve_tables" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "pve_watchers_delete" on "public"."pve_watchers" as PERMISSIVE for delete to public using ((auth.role() = 'authenticated'::text));
create policy "pve_watchers_insert" on "public"."pve_watchers" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "pve_watchers_no_kicked_rejoin" on "public"."pve_watchers" as RESTRICTIVE for insert to authenticated with check ((NOT (EXISTS ( SELECT 1
   FROM pve_watchers w
  WHERE ((w.table_id = pve_watchers.table_id) AND (w.user_id = auth.uid()) AND (w.kicked = true))))));
create policy "pve_watchers_select" on "public"."pve_watchers" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "pvp_tables_select" on "public"."pvp_tables" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "pvp_watcher_bans_select" on "public"."pvp_watcher_bans" as PERMISSIVE for select to public using ((auth.uid() = user_id));
create policy "pvp_watchers_delete" on "public"."pvp_watchers" as PERMISSIVE for delete to public using ((auth.uid() = user_id));
create policy "pvp_watchers_insert" on "public"."pvp_watchers" as PERMISSIVE for insert to public with check ((auth.role() = 'authenticated'::text));
create policy "pvp_watchers_no_kicked_rejoin" on "public"."pvp_watchers" as RESTRICTIVE for insert to authenticated with check ((NOT (EXISTS ( SELECT 1
   FROM pvp_watchers w
  WHERE ((w.table_id = pvp_watchers.table_id) AND (w.user_id = auth.uid()) AND (w.kicked = true))))));
create policy "pvp_watchers_select" on "public"."pvp_watchers" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
create policy "room_members_delete" on "public"."room_members" as PERMISSIVE for delete to public using ((auth.uid() = user_id));
create policy "room_members_insert" on "public"."room_members" as PERMISSIVE for insert to public with check ((auth.uid() = user_id));
create policy "room_members_no_kicked_rejoin" on "public"."room_members" as RESTRICTIVE for insert to authenticated with check ((NOT (EXISTS ( SELECT 1
   FROM room_members w
  WHERE ((w.room_code = room_members.room_code) AND (w.user_id = auth.uid()) AND (w.kicked = true))))));
create policy "room_members_select" on "public"."room_members" as PERMISSIVE for select to public using ((EXISTS ( SELECT 1
   FROM room_members m2
  WHERE ((m2.room_code = room_members.room_code) AND (m2.user_id = auth.uid())))));
create policy "rooms_insert" on "public"."rooms" as PERMISSIVE for insert to public with check ((auth.uid() = host_id));
create policy "rooms_select" on "public"."rooms" as PERMISSIVE for select to public using ((auth.role() = 'authenticated'::text));
grant DELETE on "public"."chat_messages" to anon;
grant INSERT on "public"."chat_messages" to anon;
grant REFERENCES on "public"."chat_messages" to anon;
grant SELECT on "public"."chat_messages" to anon;
grant TRIGGER on "public"."chat_messages" to anon;
grant TRUNCATE on "public"."chat_messages" to anon;
grant UPDATE on "public"."chat_messages" to anon;
grant DELETE on "public"."chat_messages" to authenticated;
grant INSERT on "public"."chat_messages" to authenticated;
grant REFERENCES on "public"."chat_messages" to authenticated;
grant SELECT on "public"."chat_messages" to authenticated;
grant TRIGGER on "public"."chat_messages" to authenticated;

grant TRUNCATE on "public"."chat_messages" to authenticated;
grant UPDATE on "public"."chat_messages" to authenticated;
grant DELETE on "public"."chat_messages" to postgres;
grant INSERT on "public"."chat_messages" to postgres;
grant REFERENCES on "public"."chat_messages" to postgres;
grant SELECT on "public"."chat_messages" to postgres;
grant TRIGGER on "public"."chat_messages" to postgres;
grant TRUNCATE on "public"."chat_messages" to postgres;
grant UPDATE on "public"."chat_messages" to postgres;
grant DELETE on "public"."chat_messages" to service_role;
grant INSERT on "public"."chat_messages" to service_role;
grant REFERENCES on "public"."chat_messages" to service_role;
grant SELECT on "public"."chat_messages" to service_role;
grant TRIGGER on "public"."chat_messages" to service_role;
grant TRUNCATE on "public"."chat_messages" to service_role;
grant UPDATE on "public"."chat_messages" to service_role;
grant DELETE on "public"."games" to anon;
grant INSERT on "public"."games" to anon;
grant REFERENCES on "public"."games" to anon;
grant SELECT on "public"."games" to anon;
grant TRIGGER on "public"."games" to anon;
grant TRUNCATE on "public"."games" to anon;
grant UPDATE on "public"."games" to anon;
grant DELETE on "public"."games" to authenticated;
grant INSERT on "public"."games" to authenticated;
grant REFERENCES on "public"."games" to authenticated;
grant SELECT on "public"."games" to authenticated;
grant TRIGGER on "public"."games" to authenticated;
grant TRUNCATE on "public"."games" to authenticated;
grant UPDATE on "public"."games" to authenticated;
grant DELETE on "public"."games" to postgres;
grant INSERT on "public"."games" to postgres;
grant REFERENCES on "public"."games" to postgres;
grant SELECT on "public"."games" to postgres;
grant TRIGGER on "public"."games" to postgres;
grant TRUNCATE on "public"."games" to postgres;
grant UPDATE on "public"."games" to postgres;
grant DELETE on "public"."games" to service_role;
grant INSERT on "public"."games" to service_role;
grant REFERENCES on "public"."games" to service_role;
grant SELECT on "public"."games" to service_role;
grant TRIGGER on "public"."games" to service_role;
grant TRUNCATE on "public"."games" to service_role;
grant UPDATE on "public"."games" to service_role;
grant DELETE on "public"."lobby_tables" to anon;
grant INSERT on "public"."lobby_tables" to anon;
grant REFERENCES on "public"."lobby_tables" to anon;
grant SELECT on "public"."lobby_tables" to anon;
grant TRIGGER on "public"."lobby_tables" to anon;
grant TRUNCATE on "public"."lobby_tables" to anon;
grant UPDATE on "public"."lobby_tables" to anon;
grant DELETE on "public"."lobby_tables" to authenticated;
grant INSERT on "public"."lobby_tables" to authenticated;
grant REFERENCES on "public"."lobby_tables" to authenticated;
grant SELECT on "public"."lobby_tables" to authenticated;
grant TRIGGER on "public"."lobby_tables" to authenticated;
grant TRUNCATE on "public"."lobby_tables" to authenticated;
grant UPDATE on "public"."lobby_tables" to authenticated;
grant DELETE on "public"."lobby_tables" to postgres;
grant INSERT on "public"."lobby_tables" to postgres;
grant REFERENCES on "public"."lobby_tables" to postgres;
grant SELECT on "public"."lobby_tables" to postgres;
grant TRIGGER on "public"."lobby_tables" to postgres;
grant TRUNCATE on "public"."lobby_tables" to postgres;
grant UPDATE on "public"."lobby_tables" to postgres;
grant DELETE on "public"."lobby_tables" to service_role;
grant INSERT on "public"."lobby_tables" to service_role;
grant REFERENCES on "public"."lobby_tables" to service_role;
grant SELECT on "public"."lobby_tables" to service_role;
grant TRIGGER on "public"."lobby_tables" to service_role;
grant TRUNCATE on "public"."lobby_tables" to service_role;
grant UPDATE on "public"."lobby_tables" to service_role;
grant DELETE on "public"."private_room_watcher_bans" to anon;
grant INSERT on "public"."private_room_watcher_bans" to anon;
grant REFERENCES on "public"."private_room_watcher_bans" to anon;
grant SELECT on "public"."private_room_watcher_bans" to anon;
grant TRIGGER on "public"."private_room_watcher_bans" to anon;
grant TRUNCATE on "public"."private_room_watcher_bans" to anon;
grant UPDATE on "public"."private_room_watcher_bans" to anon;
grant DELETE on "public"."private_room_watcher_bans" to authenticated;
grant INSERT on "public"."private_room_watcher_bans" to authenticated;
grant REFERENCES on "public"."private_room_watcher_bans" to authenticated;
grant SELECT on "public"."private_room_watcher_bans" to authenticated;
grant TRIGGER on "public"."private_room_watcher_bans" to authenticated;
grant TRUNCATE on "public"."private_room_watcher_bans" to authenticated;
grant UPDATE on "public"."private_room_watcher_bans" to authenticated;
grant DELETE on "public"."private_room_watcher_bans" to postgres;
grant INSERT on "public"."private_room_watcher_bans" to postgres;
grant REFERENCES on "public"."private_room_watcher_bans" to postgres;
grant SELECT on "public"."private_room_watcher_bans" to postgres;
grant TRIGGER on "public"."private_room_watcher_bans" to postgres;
grant TRUNCATE on "public"."private_room_watcher_bans" to postgres;
grant UPDATE on "public"."private_room_watcher_bans" to postgres;
grant DELETE on "public"."private_room_watcher_bans" to service_role;
grant INSERT on "public"."private_room_watcher_bans" to service_role;
grant REFERENCES on "public"."private_room_watcher_bans" to service_role;
grant SELECT on "public"."private_room_watcher_bans" to service_role;
grant TRIGGER on "public"."private_room_watcher_bans" to service_role;
grant TRUNCATE on "public"."private_room_watcher_bans" to service_role;
grant UPDATE on "public"."private_room_watcher_bans" to service_role;
grant DELETE on "public"."private_room_watchers" to anon;
grant INSERT on "public"."private_room_watchers" to anon;
grant REFERENCES on "public"."private_room_watchers" to anon;
grant SELECT on "public"."private_room_watchers" to anon;
grant TRIGGER on "public"."private_room_watchers" to anon;
grant TRUNCATE on "public"."private_room_watchers" to anon;
grant UPDATE on "public"."private_room_watchers" to anon;
grant DELETE on "public"."private_room_watchers" to authenticated;
grant INSERT on "public"."private_room_watchers" to authenticated;
grant REFERENCES on "public"."private_room_watchers" to authenticated;
grant SELECT on "public"."private_room_watchers" to authenticated;
grant TRIGGER on "public"."private_room_watchers" to authenticated;
grant TRUNCATE on "public"."private_room_watchers" to authenticated;
grant UPDATE on "public"."private_room_watchers" to authenticated;
grant DELETE on "public"."private_room_watchers" to postgres;
grant INSERT on "public"."private_room_watchers" to postgres;
grant REFERENCES on "public"."private_room_watchers" to postgres;
grant SELECT on "public"."private_room_watchers" to postgres;
grant TRIGGER on "public"."private_room_watchers" to postgres;
grant TRUNCATE on "public"."private_room_watchers" to postgres;
grant UPDATE on "public"."private_room_watchers" to postgres;
grant DELETE on "public"."private_room_watchers" to service_role;
grant INSERT on "public"."private_room_watchers" to service_role;
grant REFERENCES on "public"."private_room_watchers" to service_role;
grant SELECT on "public"."private_room_watchers" to service_role;
grant TRIGGER on "public"."private_room_watchers" to service_role;
grant TRUNCATE on "public"."private_room_watchers" to service_role;
grant UPDATE on "public"."private_room_watchers" to service_role;
grant DELETE on "public"."private_rooms" to anon;
grant INSERT on "public"."private_rooms" to anon;
grant REFERENCES on "public"."private_rooms" to anon;
grant SELECT on "public"."private_rooms" to anon;
grant TRIGGER on "public"."private_rooms" to anon;
grant TRUNCATE on "public"."private_rooms" to anon;
grant UPDATE on "public"."private_rooms" to anon;
grant DELETE on "public"."private_rooms" to authenticated;
grant INSERT on "public"."private_rooms" to authenticated;
grant REFERENCES on "public"."private_rooms" to authenticated;
grant SELECT on "public"."private_rooms" to authenticated;
grant TRIGGER on "public"."private_rooms" to authenticated;
grant TRUNCATE on "public"."private_rooms" to authenticated;
grant UPDATE on "public"."private_rooms" to authenticated;
grant DELETE on "public"."private_rooms" to postgres;
grant INSERT on "public"."private_rooms" to postgres;
grant REFERENCES on "public"."private_rooms" to postgres;
grant SELECT on "public"."private_rooms" to postgres;
grant TRIGGER on "public"."private_rooms" to postgres;
grant TRUNCATE on "public"."private_rooms" to postgres;
grant UPDATE on "public"."private_rooms" to postgres;
grant DELETE on "public"."private_rooms" to service_role;
grant INSERT on "public"."private_rooms" to service_role;
grant REFERENCES on "public"."private_rooms" to service_role;
grant SELECT on "public"."private_rooms" to service_role;
grant TRIGGER on "public"."private_rooms" to service_role;
grant TRUNCATE on "public"."private_rooms" to service_role;
grant UPDATE on "public"."private_rooms" to service_role;
grant DELETE on "public"."profiles" to anon;
grant INSERT on "public"."profiles" to anon;
grant REFERENCES on "public"."profiles" to anon;
grant SELECT on "public"."profiles" to anon;
grant TRIGGER on "public"."profiles" to anon;
grant TRUNCATE on "public"."profiles" to anon;
grant UPDATE on "public"."profiles" to anon;
grant DELETE on "public"."profiles" to authenticated;
grant INSERT on "public"."profiles" to authenticated;
grant REFERENCES on "public"."profiles" to authenticated;
grant SELECT on "public"."profiles" to authenticated;
grant TRIGGER on "public"."profiles" to authenticated;
grant TRUNCATE on "public"."profiles" to authenticated;
grant UPDATE on "public"."profiles" to authenticated;
grant DELETE on "public"."profiles" to postgres;
grant INSERT on "public"."profiles" to postgres;
grant REFERENCES on "public"."profiles" to postgres;
grant SELECT on "public"."profiles" to postgres;
grant TRIGGER on "public"."profiles" to postgres;
grant TRUNCATE on "public"."profiles" to postgres;
grant UPDATE on "public"."profiles" to postgres;
grant DELETE on "public"."profiles" to service_role;
grant INSERT on "public"."profiles" to service_role;
grant REFERENCES on "public"."profiles" to service_role;
grant SELECT on "public"."profiles" to service_role;
grant TRIGGER on "public"."profiles" to service_role;
grant TRUNCATE on "public"."profiles" to service_role;
grant UPDATE on "public"."profiles" to service_role;
grant DELETE on "public"."pve_tables" to anon;
grant INSERT on "public"."pve_tables" to anon;
grant REFERENCES on "public"."pve_tables" to anon;
grant SELECT on "public"."pve_tables" to anon;
grant TRIGGER on "public"."pve_tables" to anon;
grant TRUNCATE on "public"."pve_tables" to anon;
grant UPDATE on "public"."pve_tables" to anon;
grant DELETE on "public"."pve_tables" to authenticated;
grant INSERT on "public"."pve_tables" to authenticated;
grant REFERENCES on "public"."pve_tables" to authenticated;
grant SELECT on "public"."pve_tables" to authenticated;
grant TRIGGER on "public"."pve_tables" to authenticated;
grant TRUNCATE on "public"."pve_tables" to authenticated;
grant UPDATE on "public"."pve_tables" to authenticated;
grant DELETE on "public"."pve_tables" to postgres;
grant INSERT on "public"."pve_tables" to postgres;
grant REFERENCES on "public"."pve_tables" to postgres;
grant SELECT on "public"."pve_tables" to postgres;
grant TRIGGER on "public"."pve_tables" to postgres;
grant TRUNCATE on "public"."pve_tables" to postgres;
grant UPDATE on "public"."pve_tables" to postgres;
grant DELETE on "public"."pve_tables" to service_role;
grant INSERT on "public"."pve_tables" to service_role;
grant REFERENCES on "public"."pve_tables" to service_role;
grant SELECT on "public"."pve_tables" to service_role;
grant TRIGGER on "public"."pve_tables" to service_role;
grant TRUNCATE on "public"."pve_tables" to service_role;
grant UPDATE on "public"."pve_tables" to service_role;
grant DELETE on "public"."pve_watchers" to anon;
grant INSERT on "public"."pve_watchers" to anon;
grant REFERENCES on "public"."pve_watchers" to anon;
grant SELECT on "public"."pve_watchers" to anon;
grant TRIGGER on "public"."pve_watchers" to anon;
grant TRUNCATE on "public"."pve_watchers" to anon;
grant UPDATE on "public"."pve_watchers" to anon;
grant DELETE on "public"."pve_watchers" to authenticated;
grant INSERT on "public"."pve_watchers" to authenticated;
grant REFERENCES on "public"."pve_watchers" to authenticated;
grant SELECT on "public"."pve_watchers" to authenticated;
grant TRIGGER on "public"."pve_watchers" to authenticated;
grant TRUNCATE on "public"."pve_watchers" to authenticated;
grant UPDATE on "public"."pve_watchers" to authenticated;
grant DELETE on "public"."pve_watchers" to postgres;
grant INSERT on "public"."pve_watchers" to postgres;
grant REFERENCES on "public"."pve_watchers" to postgres;
grant SELECT on "public"."pve_watchers" to postgres;
grant TRIGGER on "public"."pve_watchers" to postgres;
grant TRUNCATE on "public"."pve_watchers" to postgres;
grant UPDATE on "public"."pve_watchers" to postgres;
grant DELETE on "public"."pve_watchers" to service_role;
grant INSERT on "public"."pve_watchers" to service_role;
grant REFERENCES on "public"."pve_watchers" to service_role;
grant SELECT on "public"."pve_watchers" to service_role;
grant TRIGGER on "public"."pve_watchers" to service_role;
grant TRUNCATE on "public"."pve_watchers" to service_role;
grant UPDATE on "public"."pve_watchers" to service_role;
grant DELETE on "public"."pvp_tables" to anon;
grant INSERT on "public"."pvp_tables" to anon;
grant REFERENCES on "public"."pvp_tables" to anon;
grant SELECT on "public"."pvp_tables" to anon;
grant TRIGGER on "public"."pvp_tables" to anon;
grant TRUNCATE on "public"."pvp_tables" to anon;
grant UPDATE on "public"."pvp_tables" to anon;
grant DELETE on "public"."pvp_tables" to authenticated;
grant INSERT on "public"."pvp_tables" to authenticated;
grant REFERENCES on "public"."pvp_tables" to authenticated;
grant SELECT on "public"."pvp_tables" to authenticated;
grant TRIGGER on "public"."pvp_tables" to authenticated;
grant TRUNCATE on "public"."pvp_tables" to authenticated;
grant UPDATE on "public"."pvp_tables" to authenticated;
grant DELETE on "public"."pvp_tables" to postgres;
grant INSERT on "public"."pvp_tables" to postgres;
grant REFERENCES on "public"."pvp_tables" to postgres;
grant SELECT on "public"."pvp_tables" to postgres;
grant TRIGGER on "public"."pvp_tables" to postgres;
grant TRUNCATE on "public"."pvp_tables" to postgres;
grant UPDATE on "public"."pvp_tables" to postgres;
grant DELETE on "public"."pvp_tables" to service_role;
grant INSERT on "public"."pvp_tables" to service_role;
grant REFERENCES on "public"."pvp_tables" to service_role;
grant SELECT on "public"."pvp_tables" to service_role;
grant TRIGGER on "public"."pvp_tables" to service_role;
grant TRUNCATE on "public"."pvp_tables" to service_role;
grant UPDATE on "public"."pvp_tables" to service_role;
grant DELETE on "public"."pvp_watcher_bans" to anon;
grant INSERT on "public"."pvp_watcher_bans" to anon;
grant REFERENCES on "public"."pvp_watcher_bans" to anon;
grant SELECT on "public"."pvp_watcher_bans" to anon;
grant TRIGGER on "public"."pvp_watcher_bans" to anon;
grant TRUNCATE on "public"."pvp_watcher_bans" to anon;
grant UPDATE on "public"."pvp_watcher_bans" to anon;
grant DELETE on "public"."pvp_watcher_bans" to authenticated;
grant INSERT on "public"."pvp_watcher_bans" to authenticated;
grant REFERENCES on "public"."pvp_watcher_bans" to authenticated;
grant SELECT on "public"."pvp_watcher_bans" to authenticated;
grant TRIGGER on "public"."pvp_watcher_bans" to authenticated;
grant TRUNCATE on "public"."pvp_watcher_bans" to authenticated;
grant UPDATE on "public"."pvp_watcher_bans" to authenticated;
grant DELETE on "public"."pvp_watcher_bans" to postgres;
grant INSERT on "public"."pvp_watcher_bans" to postgres;
grant REFERENCES on "public"."pvp_watcher_bans" to postgres;
grant SELECT on "public"."pvp_watcher_bans" to postgres;
grant TRIGGER on "public"."pvp_watcher_bans" to postgres;
grant TRUNCATE on "public"."pvp_watcher_bans" to postgres;
grant UPDATE on "public"."pvp_watcher_bans" to postgres;
grant DELETE on "public"."pvp_watcher_bans" to service_role;
grant INSERT on "public"."pvp_watcher_bans" to service_role;
grant REFERENCES on "public"."pvp_watcher_bans" to service_role;
grant SELECT on "public"."pvp_watcher_bans" to service_role;
grant TRIGGER on "public"."pvp_watcher_bans" to service_role;
grant TRUNCATE on "public"."pvp_watcher_bans" to service_role;
grant UPDATE on "public"."pvp_watcher_bans" to service_role;
grant DELETE on "public"."pvp_watchers" to anon;
grant INSERT on "public"."pvp_watchers" to anon;
grant REFERENCES on "public"."pvp_watchers" to anon;
grant SELECT on "public"."pvp_watchers" to anon;
grant TRIGGER on "public"."pvp_watchers" to anon;
grant TRUNCATE on "public"."pvp_watchers" to anon;
grant UPDATE on "public"."pvp_watchers" to anon;
grant DELETE on "public"."pvp_watchers" to authenticated;
grant INSERT on "public"."pvp_watchers" to authenticated;
grant REFERENCES on "public"."pvp_watchers" to authenticated;
grant SELECT on "public"."pvp_watchers" to authenticated;
grant TRIGGER on "public"."pvp_watchers" to authenticated;
grant TRUNCATE on "public"."pvp_watchers" to authenticated;
grant UPDATE on "public"."pvp_watchers" to authenticated;
grant DELETE on "public"."pvp_watchers" to postgres;
grant INSERT on "public"."pvp_watchers" to postgres;
grant REFERENCES on "public"."pvp_watchers" to postgres;
grant SELECT on "public"."pvp_watchers" to postgres;
grant TRIGGER on "public"."pvp_watchers" to postgres;
grant TRUNCATE on "public"."pvp_watchers" to postgres;
grant UPDATE on "public"."pvp_watchers" to postgres;
grant DELETE on "public"."pvp_watchers" to service_role;
grant INSERT on "public"."pvp_watchers" to service_role;
grant REFERENCES on "public"."pvp_watchers" to service_role;
grant SELECT on "public"."pvp_watchers" to service_role;
grant TRIGGER on "public"."pvp_watchers" to service_role;
grant TRUNCATE on "public"."pvp_watchers" to service_role;
grant UPDATE on "public"."pvp_watchers" to service_role;
grant DELETE on "public"."room_members" to anon;
grant INSERT on "public"."room_members" to anon;
grant REFERENCES on "public"."room_members" to anon;
grant SELECT on "public"."room_members" to anon;
grant TRIGGER on "public"."room_members" to anon;
grant TRUNCATE on "public"."room_members" to anon;
grant UPDATE on "public"."room_members" to anon;
grant DELETE on "public"."room_members" to authenticated;
grant INSERT on "public"."room_members" to authenticated;
grant REFERENCES on "public"."room_members" to authenticated;
grant SELECT on "public"."room_members" to authenticated;
grant TRIGGER on "public"."room_members" to authenticated;
grant TRUNCATE on "public"."room_members" to authenticated;
grant UPDATE on "public"."room_members" to authenticated;
grant DELETE on "public"."room_members" to postgres;
grant INSERT on "public"."room_members" to postgres;
grant REFERENCES on "public"."room_members" to postgres;
grant SELECT on "public"."room_members" to postgres;
grant TRIGGER on "public"."room_members" to postgres;
grant TRUNCATE on "public"."room_members" to postgres;
grant UPDATE on "public"."room_members" to postgres;
grant DELETE on "public"."room_members" to service_role;
grant INSERT on "public"."room_members" to service_role;
grant REFERENCES on "public"."room_members" to service_role;
grant SELECT on "public"."room_members" to service_role;
grant TRIGGER on "public"."room_members" to service_role;
grant TRUNCATE on "public"."room_members" to service_role;
grant UPDATE on "public"."room_members" to service_role;
grant DELETE on "public"."rooms" to anon;
grant INSERT on "public"."rooms" to anon;
grant REFERENCES on "public"."rooms" to anon;
grant SELECT on "public"."rooms" to anon;
grant TRIGGER on "public"."rooms" to anon;
grant TRUNCATE on "public"."rooms" to anon;
grant UPDATE on "public"."rooms" to anon;
grant DELETE on "public"."rooms" to authenticated;
grant INSERT on "public"."rooms" to authenticated;
grant REFERENCES on "public"."rooms" to authenticated;
grant SELECT on "public"."rooms" to authenticated;
grant TRIGGER on "public"."rooms" to authenticated;
grant TRUNCATE on "public"."rooms" to authenticated;
grant UPDATE on "public"."rooms" to authenticated;
grant DELETE on "public"."rooms" to postgres;
grant INSERT on "public"."rooms" to postgres;
grant REFERENCES on "public"."rooms" to postgres;
grant SELECT on "public"."rooms" to postgres;
grant TRIGGER on "public"."rooms" to postgres;
grant TRUNCATE on "public"."rooms" to postgres;
grant UPDATE on "public"."rooms" to postgres;
grant DELETE on "public"."rooms" to service_role;
grant INSERT on "public"."rooms" to service_role;
grant REFERENCES on "public"."rooms" to service_role;
grant SELECT on "public"."rooms" to service_role;
grant TRIGGER on "public"."rooms" to service_role;
grant TRUNCATE on "public"."rooms" to service_role;
grant UPDATE on "public"."rooms" to service_role;


-- --------------------------------------------------------------------------
-- PART 2  auth hook
--   New signups must get a profiles row, otherwise every RPC that reads
--   profiles returns nothing. The trigger lives in the auth section of the
--   dump, which we otherwise skip because the rest of that section is
--   Supabase-managed and would collide on a fresh project.
-- --------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.handle_new_user()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$

begin

  insert into public.profiles (id) values (new.id)

  on conflict (id) do nothing;

  return new;

end;

$function$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- --------------------------------------------------------------------------
-- PART 3  room_leave fix (2026-10-05, newer than the dump)
--   The dump still carries the version where a room whose last occupant
--   leaves keeps status='playing' forever, so the table never resets.
--
--   It also still defines an older overload, room_leave(character), which is
--   char(1). Two overloads under one name leave PostgREST guessing which one
--   to dispatch to, and char(1) cannot hold a 4-character room code anyway.
--   It goes; only the char(4) version survives.
-- --------------------------------------------------------------------------

drop function if exists public.room_leave(character);

-- 热修复：只重写 public.room_leave 这一个函数
-- 用法：Supabase SQL Editor -> 全选粘贴 -> Run
-- 可以安全重复执行（create or replace）

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

-- --------------------------------------------------------------------------
-- PART 4  cron jobs
--   pg_cron is not part of a schema dump, so these are recovered from the
--   scripts. Each job is unscheduled by name first: cron.schedule happily
--   creates a duplicate when the name already exists, and a duplicate means
--   the reaper runs twice per minute.
--
--   Deliberately NOT scheduled (fix_round_lifecycle.sql unschedules them):
--     pvp-release-stale-playing, pvp-release-stale-seats,
--     room-release-stale-playing, room-release-stale-seats
--   Those reclaim whole tables, so they fire whenever one side of a live
--   game stalls. They are replaced by reap_stale_seats().
-- --------------------------------------------------------------------------

-- purge-offline-members   [*/5 * * * *]   from schema.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'purge-offline-members' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('purge-offline-members', '*/5 * * * *', $cron$
delete from public.room_members m
  using public.profiles p
  where m.user_id = p.id
    and p.last_seen_at < now() - interval '10 minutes';
$cron$);

-- purge-old-rooms   [*/5 * * * *]   from schema.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'purge-old-rooms' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('purge-old-rooms', '*/5 * * * *', $cron$
delete from public.rooms
  where status = 'finished' and last_active_at < now() - interval '30 minutes';
$cron$);

-- purge-old-tables   [*/5 * * * *]   from schema.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'purge-old-tables' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('purge-old-tables', '*/5 * * * *', $cron$
delete from public.lobby_tables
  where status = 'finished' and last_active_at < now() - interval '30 minutes';
$cron$);

-- release-idle-rooms   [* * * * *]   from schema.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'release-idle-rooms' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('release-idle-rooms', '* * * * *', $cron$
update public.rooms
  set player_a_id = null, player_b_id = null, current_turn_id = null,
      status = 'waiting', game_state = '{}'::jsonb
  where last_active_at < now() - interval '10 minutes'
    and status <> 'finished';
$cron$);

-- release-idle-tables   [* * * * *]   from schema.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'release-idle-tables' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('release-idle-tables', '* * * * *', $cron$
update public.lobby_tables
  set player_a_id = null, player_b_id = null, current_turn_id = null,
      status = 'waiting', watcher_count = 0, game_state = '{}'::jsonb
  where last_active_at < now() - interval '10 minutes'
    and status <> 'finished';
$cron$);

-- pve-purge-offline-watchers   [* * * * *]   from pve_tables.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'pve-purge-offline-watchers' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('pve-purge-offline-watchers', '* * * * *', $cron$
delete from public.pve_watchers w
  where w.last_active_at < now() - interval '3 minutes';
$cron$);

-- pve-release-idle-seats   [* * * * *]   from pve_tables.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'pve-release-idle-seats' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('pve-release-idle-seats', '* * * * *', $cron$
update public.pve_tables
  set player_id = null, status = 'open',
      watcher_count = (select count(*) from public.pve_watchers w where w.table_id = public.pve_tables.id)
  where last_active_at < now() - interval '3 minutes'
    and status <> 'open';
$cron$);

-- pvp-purge-offline-watchers   [* * * * *]   from pvp_tables.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'pvp-purge-offline-watchers' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('pvp-purge-offline-watchers', '* * * * *', $cron$
delete from public.pvp_watchers w
  where w.last_active_at < now() - interval '3 minutes';
$cron$);

-- pvp-purge-expired-watcher-bans   [* * * * *]   from fix_kick_watcher.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'pvp-purge-expired-watcher-bans' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('pvp-purge-expired-watcher-bans', '* * * * *', $cron$
delete from public.pvp_watcher_bans where banned_until <= now();
    delete from public.private_room_watcher_bans where banned_until <= now();
$cron$);

-- room-purge-offline-watchers   [* * * * *]   from private_rooms.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'room-purge-offline-watchers' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('room-purge-offline-watchers', '* * * * *', $cron$
delete from public.private_room_watchers w
  where w.last_active_at < now() - interval '3 minutes';
$cron$);

-- reap-stale-seats   [* * * * *]   from fix_round_lifecycle.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'reap-stale-seats' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('reap-stale-seats', '* * * * *', $cron$
select public.reap_stale_seats();
$cron$);

-- reap-turn-timeouts   [* * * * *]   from fix_round_lifecycle.sql
do $$
declare
  j record;
begin
  for j in select jobid from cron.job where jobname = 'reap-turn-timeouts' loop
    perform cron.unschedule(j.jobid);
  end loop;
end $$;

select cron.schedule('reap-turn-timeouts', '* * * * *', $cron$
select public.reap_turn_timeouts();
$cron$);

-- --------------------------------------------------------------------------
-- PART 5  verify
--   A silently missing job is worse than a loud failure: the table looks
--   healthy while stale seats pile up forever.
-- --------------------------------------------------------------------------
do $$
declare
  v_expected constant integer := 12;
  v_found   integer;
begin
  select count(*) into v_found
    from cron.job
   where jobname in ('purge-offline-members', 'purge-old-rooms', 'purge-old-tables', 'release-idle-rooms', 'release-idle-tables', 'pve-purge-offline-watchers', 'pve-release-idle-seats', 'pvp-purge-offline-watchers', 'pvp-purge-expired-watcher-bans', 'room-purge-offline-watchers', 'reap-stale-seats', 'reap-turn-timeouts');

  if v_found <> v_expected then
    raise exception 'bootstrap incomplete: expected % cron jobs, found %',
      v_expected, v_found;
  end if;
end $$;
