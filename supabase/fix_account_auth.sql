-- ============================================================
-- 鲜花与牛粪 - 账号体系（注册/登录/注销）
-- 方案：Supabase Auth 的 email+password，email 由昵称派生（唯一映射）；
--       昵称唯一（profiles.nickname），注册先查重，重名必须改名。
-- 在 Supabase Dashboard > SQL Editor 整段执行，可重复执行（幂等）
-- ============================================================

-- ============================================================
-- 1. check_nickname 保持：昵称是否可用（false=已被占用）
-- ============================================================
create or replace function public.check_nickname(n text)
returns boolean
language sql security definer set search_path = public
as $$
  select not exists (select 1 from public.profiles where nickname = n)
$$;

-- ============================================================
-- 2. create_profile：严格建档（用于注册成功后绑定昵称/性别）
--    不再自动加后缀：
--      * 昵称为空          -> NICKNAME_EMPTY
--      * 昵称超过 4 字符   -> NICKNAME_TOO_LONG
--      * 性别非法          -> INVALID_GENDER
--      * 昵称已被他人占用  -> NICKNAME_TAKEN（必须改名）
--    返回新 user_id（uuid）
-- ============================================================
create or replace function public.create_profile(nick text, g text)
returns uuid
language plpgsql security definer set search_path = public
as $$
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
$$;

-- ============================================================
-- 3. 清理旧的自动加后缀建档数据（可选，视现场情况执行）
--    保留已有有效资料，仅清空匿名占位与超长昵称
-- ============================================================
delete from public.profiles
where nickname is null or gender is null;
