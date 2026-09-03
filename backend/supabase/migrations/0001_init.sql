-- ============================================================================
-- 家庭共享智能冰箱 —— Supabase 初始化迁移
-- 在 Supabase Dashboard → SQL Editor 直接执行, 或:
--   supabase db push
-- ============================================================================

-- ---------- 表结构 ----------

-- 家庭空间
create table if not exists public.families (
  id          uuid primary key default gen_random_uuid(),
  name        text not null default '我的家庭',
  invite_code text not null unique,
  created_at  timestamptz not null default now()
);

-- 家庭成员 (一个用户至少属于一个家庭; owner 可更改邀请码等)
create table if not exists public.family_members (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  family_id    uuid not null references public.families(id) on delete cascade,
  display_name text not null default '',
  role         text not null default 'member' check (role in ('owner', 'member')),
  created_at   timestamptz not null default now()
);

-- 食材存货 (与客户端 Ingredient 模型逐字段对应)
create table if not exists public.ingredients (
  id                uuid primary key default gen_random_uuid(),
  family_id         uuid not null references public.families(id) on delete cascade,
  name              text not null,
  category          text not null default '其他',
  quantity          real not null default 1 check (quantity >= 0),
  unit              text not null default '份',
  zone              text not null check (zone in ('FRIDGE', 'FREEZER', 'PANTRY')),
  purchased_at      timestamptz not null default now(),
  shelf_life_days   integer not null check (shelf_life_days between 1 and 3650),
  added_by_user_name text not null default '',
  updated_at        timestamptz not null default now()
);

create index if not exists idx_ingredients_family
  on public.ingredients (family_id, zone, updated_at desc);

-- ---------- RLS ----------

alter table public.families        enable row level security;
alter table public.family_members  enable row level security;
alter table public.ingredients     enable row level security;

-- families: 仅家庭成员可读
create policy "families_select_members" on public.families
  for select to authenticated
  using (id in (select family_id from public.family_members where user_id = auth.uid()));

-- family_members: 仅本人可见自己的成员关系
create policy "members_select_self" on public.family_members
  for select to authenticated
  using (user_id = auth.uid());

-- ingredients: 家庭成员完整 CRUD (所有读写自动限定在本家庭)
create policy "ingredients_select" on public.ingredients
  for select to authenticated
  using (family_id in (select family_id from public.family_members where user_id = auth.uid()));

create policy "ingredients_insert" on public.ingredients
  for insert to authenticated
  with check (family_id in (select family_id from public.family_members where user_id = auth.uid()));

create policy "ingredients_update" on public.ingredients
  for update to authenticated
  using (family_id in (select family_id from public.family_members where user_id = auth.uid()))
  with check (family_id in (select family_id from public.family_members where user_id = auth.uid()));

create policy "ingredients_delete" on public.ingredients
  for delete to authenticated
  using (family_id in (select family_id from public.family_members where user_id = auth.uid()));

-- ---------- RPC (家庭创建/加入走 security definer, 避免暴露邀请码查询) ----------

-- 返回当前用户所在家庭 (无则 null)
create or replace function public.get_my_family()
returns jsonb
language sql stable security definer set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'family_id',   m.family_id,
    'name',        f.name,
    'invite_code', f.invite_code,
    'display_name', m.display_name,
    'role',        m.role
  )
  from public.family_members m
  join public.families f on f.id = m.family_id
  where m.user_id = auth.uid()
  limit 1;
$$;

-- 创建家庭 (首个成员为 owner)
create or replace function public.create_family(family_name text, display_name text)
returns jsonb
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_code text;
  v_id   uuid;
begin
  -- 生成 6 位邀请码 (撞码重试, 耗尽则报错)
  for i in 1..5 loop
    v_code := upper(substr(md5(gen_random_uuid()::text), 1, 6));
    exit when not exists (select 1 from public.families where invite_code = v_code);
  end loop;

  if exists (select 1 from public.families where invite_code = v_code) then
    raise exception '邀请码生成失败，请重试';
  end if;

  insert into public.families (name, invite_code)
  values (coalesce(nullif(trim(family_name), ''), '我的家庭'), v_code)
  returning id into v_id;

  insert into public.family_members (user_id, family_id, display_name, role)
  values (auth.uid(), v_id, coalesce(nullif(trim(display_name), ''), ''), 'owner');

  return public.get_my_family();
end;
$$;

-- 用邀请码加入家庭
create or replace function public.join_family(code text)
returns jsonb
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_fam record;
begin
  select * into v_fam from public.families
  where invite_code = upper(trim(coalesce(code, '')));
  if v_fam.id is null then
    raise exception '邀请码无效';
  end if;

  insert into public.family_members (user_id, family_id, display_name, role)
  values (
    auth.uid(),
    v_fam.id,
    coalesce(
      (select raw_user_meta_data->>'display_name' from auth.users where id = auth.uid()),
      ''
    ),
    'member'
  )
  on conflict (user_id) do update set family_id = excluded.family_id, role = 'member'; -- 允许切换家庭

  return public.get_my_family();
end;
$$;

-- 仅 authenticated 可执行 RPC
revoke execute on function public.get_my_family() from public, anon;
revoke execute on function public.create_family(text, text) from public, anon;
revoke execute on function public.join_family(text) from public, anon;
grant execute on function public.get_my_family() to authenticated;
grant execute on function public.create_family(text, text) to authenticated;
grant execute on function public.join_family(text) to authenticated;

-- ---------- Realtime (实时同步) ----------

-- 注意: updated_at 完全由客户端写入 (LWW 唯一权威), 服务端不加触发器覆盖
alter table public.ingredients replica identity full;
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and tablename = 'ingredients'
  ) then
    alter publication supabase_realtime add table public.ingredients;
  end if;
end $$;
