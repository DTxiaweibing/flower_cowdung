-- ============================================================
-- 清理历史垃圾昵称：解放被「名字1/名字2/...」占用的昵称
-- 背景：旧版 create_profile 重名时自动加后缀（张三、张三1、张三2...），
--       且早期注册可能只有 profile 没有真正密码账号，导致想用原昵称时总提示被占。
-- 本脚本分两步：
--   第 1 步（先看后删，务必确认）：先查询将删除的数据，确认无误后再执行第 2 步。
--   第 2 步（删除）：
--     a) 删除带数字后缀的垃圾昵称（名字1~N）；
--     b) 删除无对应 Auth 用户、且从未参与对局的孤立 profile（早期无密码注册残留）；
--     c) 保留正常使用中的昵称。
-- 清理后你自己的昵称即可重新注册。
-- ============================================================

-- ---------- 第 1 步：预览将删除的数据（只查询，不删除） ----------
-- 1.1 带数字后缀的昵称（可能是旧自动加后缀残留）
select p.id, p.nickname, p.gender, p.created_at
from public.profiles p
where p.nickname ~ '[0-9]+$';

-- 1.2 没有对应 Auth 账号的 profile（早期无密码注册残留）
select p.id, p.nickname, p.gender, p.created_at
from public.profiles p
left join auth.users u on u.id = p.id
where u.id is null;

-- ---------- 第 2 步：真正删除（确认第 1 步结果无误后执行） ----------
-- 2.1 删除所有带数字后缀的昵称
delete from public.profiles
where nickname ~ '[0-9]+$';

-- 2.2 删除没有 Auth 账号的孤立 profile
delete from public.profiles p
where not exists (select 1 from auth.users u where u.id = p.id);

-- 2.3 顺带清掉残留的观众席位（避免占位）
delete from public.room_members
where user_id not in (select id from auth.users);