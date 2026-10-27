-- Enables login for the application roles created NOLOGIN by migrations V1 (rbac_app, rbac_admin) and
-- V11 (identity_app, authz_app, audit_app: one per service).
-- Run once per database, as a role allowed to ALTER ROLE (e.g. the Supabase postgres user).
--
-- Only precomputed SCRAM-SHA-256 verifiers are sent, never cleartext passwords (rbac_admin can see every
-- tenant). Compute them locally with scripts/db/scram_verifier.py, then pass them as psql variables:
--
--   APP_VERIFIER=$(printf '%s' "$DB_APP_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   ADMIN_VERIFIER=$(printf '%s' "$DB_ADMIN_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   IDENTITY_VERIFIER=$(printf '%s' "$IDENTITY_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   AUTHZ_VERIFIER=$(printf '%s' "$AUTHZ_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   AUDIT_VERIFIER=$(printf '%s' "$AUDIT_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   psql "$PSQL_URL" -v app_verifier="$APP_VERIFIER" -v admin_verifier="$ADMIN_VERIFIER" \
--        -v identity_verifier="$IDENTITY_VERIFIER" -v authz_verifier="$AUTHZ_VERIFIER" \
--        -v audit_verifier="$AUDIT_VERIFIER" \
--        -f scripts/db/bootstrap_login_roles.sql
--
-- Each verifier looks like SCRAM-SHA-256$4096:<salt>$<StoredKey>:<ServerKey>; PostgreSQL stores it as is.
-- Never put real values in this file.
\set ON_ERROR_STOP on

ALTER ROLE rbac_app WITH LOGIN PASSWORD :'app_verifier';
ALTER ROLE rbac_admin WITH LOGIN PASSWORD :'admin_verifier';
ALTER ROLE identity_app WITH LOGIN PASSWORD :'identity_verifier';
ALTER ROLE authz_app WITH LOGIN PASSWORD :'authz_verifier';
ALTER ROLE audit_app WITH LOGIN PASSWORD :'audit_verifier';
