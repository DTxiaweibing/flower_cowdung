-- ============================================================
-- chat_messages：对战/人机/私密房间 共用聊天表
--   按 table_id 区分（私密房间用 room_code 作为 table_id）
--   玩家 + 观众都能收发；通过 id 增量游标拉取新消息
-- ============================================================

create table if not exists public.chat_messages (
  id          bigint generated always as identity primary key,
  table_id    text        not null,
  sender_id   uuid,
  sender_name text,
  message     text        not null,
  created_at  timestamptz not null default now()
);

create index if not exists chat_messages_table_idx
  on public.chat_messages (table_id, id);

alter table public.chat_messages enable row level security;

-- 读取：登录用户可读取任意桌聊天（本桌隔离由客户端按 table_id 查询保证；
--       不坐下/不进房则拿不到该桌 table_id，等价于“仅本桌可见”）
drop policy if exists chat_messages_select on public.chat_messages;
create policy chat_messages_select on public.chat_messages
  for select using (auth.role() = 'authenticated');

-- 写入：登录用户可发送聊天
drop policy if exists chat_messages_insert on public.chat_messages;
create policy chat_messages_insert on public.chat_messages
  for insert with check (auth.role() = 'authenticated');
