-- Enables login for the per-service roles created NOLOGIN by migration V11 (identity_app, authz_app, audit_app).
-- Independent of bootstrap_login_roles.sql, so it can be run on a database whose shared roles already have logins.
-- Run once per database, as a role allowed to ALTER ROLE (e.g. the Supabase postgres user).
--
-- Only precomputed SCRAM-SHA-256 verifiers are sent, never cleartext passwords. Compute them locally with
-- scripts/db/scram_verifier.py, then pass them as psql variables:
--
--   IDENTITY_VERIFIER=$(printf '%s' "$IDENTITY_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   AUTHZ_VERIFIER=$(printf '%s' "$AUTHZ_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   AUDIT_VERIFIER=$(printf '%s' "$AUDIT_DB_PASSWORD" | python3 scripts/db/scram_verifier.py)
--   psql "$PSQL_URL" -v identity_verifier="$IDENTITY_VERIFIER" -v authz_verifier="$AUTHZ_VERIFIER" \
--        -v audit_verifier="$AUDIT_VERIFIER" -f scripts/db/bootstrap_service_login_roles.sql
--
-- Each verifier looks like SCRAM-SHA-256$4096:<salt>$<StoredKey>:<ServerKey>; PostgreSQL stores it as is.
-- Through a network that blocks psql, paste the same ALTER ROLE statements into the Supabase SQL Editor
-- with the verifier strings inlined instead of the :'...' variables.
-- Never put real values in this file.
\set ON_ERROR_STOP on

ALTER ROLE identity_app WITH LOGIN PASSWORD :'identity_verifier';
ALTER ROLE authz_app WITH LOGIN PASSWORD :'authz_verifier';
ALTER ROLE audit_app WITH LOGIN PASSWORD :'audit_verifier';
