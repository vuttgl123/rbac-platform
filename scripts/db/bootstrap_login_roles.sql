-- Enables login for the two application roles created (NOLOGIN) by migration V1.
-- Run once per database, as a role allowed to ALTER ROLE (e.g. the Supabase postgres user):
--   psql "$PSQL_URL" -v app_password="$DB_APP_PASSWORD" -v admin_password="$DB_ADMIN_PASSWORD" \
--        -f scripts/db/bootstrap_login_roles.sql
-- Passwords come from psql variables only; never put real values in this file.
\set ON_ERROR_STOP on

ALTER ROLE rbac_app WITH LOGIN PASSWORD :'app_password';
ALTER ROLE rbac_admin WITH LOGIN PASSWORD :'admin_password';
