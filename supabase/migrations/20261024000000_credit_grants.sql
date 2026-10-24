-- One row per device that has received the one-time free-credit grant.
-- device_key is SHA-256("instaget:" + Android ID), computed on-device; the raw Android ID never
-- leaves the phone. No balances, URLs or user content are stored here.
create table if not exists public.credit_grants (
    device_key  text primary key check (device_key ~ '^[0-9a-f]{64}$'),
    granted     integer not null check (granted >= 0),
    claimed_at  timestamptz not null default now()
);

-- Only the claim-credits Edge Function (service role) touches this table.
alter table public.credit_grants enable row level security;
revoke all on public.credit_grants from anon, authenticated;

-- Atomic claim: returns p_grant the first time a device asks, 0 on every later call.
create or replace function public.claim_initial_grant(p_device_key text, p_grant integer)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    inserted integer;
begin
    insert into public.credit_grants (device_key, granted)
    values (p_device_key, p_grant)
    on conflict (device_key) do nothing;
    get diagnostics inserted = row_count;
    return case when inserted = 1 then p_grant else 0 end;
end;
$$;

revoke all on function public.claim_initial_grant(text, integer) from public, anon, authenticated;
grant execute on function public.claim_initial_grant(text, integer) to service_role;
