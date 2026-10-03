-- ============================================================
-- 鲜花与牛粪 - 后端修复补丁（连通性自测发现）
-- 在 Supabase Dashboard > SQL Editor 执行即可。
-- ============================================================

-- 1) 修复 handle_new_user 建档失败：
--    原 schema 中 nickname 与 gender 均为 NOT NULL，但触发器只插入 id，
--    导致任何新用户（含匿名登录）建档必报 500。
--    两者都去掉 NOT NULL，昵称/性别在 create_profile 时再填充。
alter table public.profiles
  alter column nickname drop not null;
alter table public.profiles
  alter column gender drop not null;

-- 2) 加固：handle_new_user 显式只插入 id，忽略其他字段
create or replace function public.handle_new_user()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  insert into public.profiles (id) values (new.id)
  on conflict (id) do nothing;
  return new;
end;
$$;

-- 3) 校验：nickname/gender 都应显示 YES（可为空）
select
  column_name,
  is_nullable
from information_schema.columns
where table_schema = 'public'
  and table_name = 'profiles'
  and column_name in ('nickname', 'gender');