-- Enables login for the shared application roles created NOLOGIN by migration V1 (rbac_app, rbac_admin).
-- The per-service roles from V11 are enabled by bootstrap_service_login_roles.sql.
-- Run once per database, as a role allowed to ALTER ROLE (e.g. the Supabase postgres user).
--
-- Only precomputed SCRAM-SHA-256 verifiers are sent, never cleartext passwords (rbac_admin can see every
-- tenant). Compute them locally with scripts/db/scram_verifier.py, then pass them as psql variables:
--
--   APP_VERIFIER=$(printf '%s' "$DB_APP_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   ADMIN_VERIFIER=$(printf '%s' "$DB_ADMIN_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   psql "$PSQL_URL" -v app_verifier="$APP_VERIFIER" -v admin_verifier="$ADMIN_VERIFIER" \
--        -f scripts/db/bootstrap_login_roles.sql
--
-- Each verifier looks like SCRAM-SHA-256$4096:<salt>$<StoredKey>:<ServerKey>; PostgreSQL stores it as is.
-- Through a network that blocks psql, paste the same ALTER ROLE statements into the Supabase SQL Editor
-- with the verifier strings inlined instead of the :'...' variables.
-- Never put real values in this file.
\set ON_ERROR_STOP on

ALTER ROLE rbac_app WITH LOGIN PASSWORD :'app_verifier';
ALTER ROLE rbac_admin WITH LOGIN PASSWORD :'admin_verifier';
