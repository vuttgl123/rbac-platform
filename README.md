# rbac-platform

Multi-tenant RBAC platform: three Spring Boot services on one PostgreSQL database, with tenant isolation enforced by row-level security.

## Modules

| Path | Purpose |
| --- | --- |
| `libs/lib-core` | Logging (`logback-spring.xml`, request ids), `RequestLoggingFilter`, error responses carrying the request id |
| `libs/lib-db` | Shared data source defaults, `TenantScope`, startup check that the service connects as its own non-bypass role |
| `db-migrations` | Flyway migrations `V1`-`V11`, the Flyway plugin setup, DB tests with embedded Postgres (also a test-jar) |
| `test-support` | Test-only helpers (`AppDatabase`, ...) used by the other modules |
| `services/identity-service` | Identity service, port 8081, database role `identity_app` |
| `services/authz-service` | Authorization service, port 8082, database role `authz_app` |
| `services/audit-service` | Audit service, port 8083, database role `audit_app` |

Services depend on the libs, never the reverse. No service runs migrations.

## Running a service locally

Each `Test<Svc>ServiceApplication` (in `src/test`) starts the service against a throwaway embedded Postgres with every
migration applied, connected as the service's own role. No Docker, no `.env` needed; data is gone when the process stops.

```
./mvnw -q install -DskipTests && ./mvnw -pl services/identity-service spring-boot:test-run
```

Use `authz-service` or `audit-service` for the others. The install puts the sibling modules (including the
`db-migrations` test-jar) in `~/.m2`, so run it on a fresh checkout and after editing any module other than the service.
To use another port, add `-Dspring-boot.run.arguments=--server.port=18091`.

Check it with `curl localhost:8081/actuator/health` (8082 / 8083 for the others). Everything else answers 401.
Logs land in `services/<svc>-service/logs/` (`<svc>-service.log`, `<svc>-service-error.log`); find one request with
`grep <requestId> services/<svc>-service/logs/*.log`.

## Configuration (`.env`)

Copy `.env.example` to `.env` and fill it in; never commit it. Services read `.env` from their own directory or the repo root.

| Variable | Read by | Meaning |
| --- | --- | --- |
| `DB_URL` | all services, Flyway | JDBC URL (Supabase Session pooler) |
| `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` | identity-service | Login of `identity_app` (user is `<role>.<project-ref>` through the pooler) |
| `AUTHZ_DB_USER` / `AUTHZ_DB_PASSWORD` | authz-service | Login of `authz_app` |
| `AUDIT_DB_USER` / `AUDIT_DB_PASSWORD` | audit-service | Login of `audit_app` |
| `DB_POOL_SIZE` | all services | Hikari maximum pool size (default 5) |
| `LOG_LEVEL_APP` | all services | Level for `com.rbac_platform` (default INFO) |
| `LOG_LEVEL_SQL` | all services | DEBUG logs every SQL statement (default INFO) |
| `LOG_DIR` | all services | Directory for the log files (default `logs`) |

`DB_USER`, `DB_PASSWORD`, `PLATFORM_OPERATOR_EMAIL` and `PSQL_URL` are for migrations and the login-role scripts only.

## Migrations

```
set -a; source .env; set +a
./mvnw -pl db-migrations flyway:migrate
```

The Flyway plugin reads environment variables, hence the export. Supabase currently gets its migrations through the
SQL Editor (the network blocks the database port); `V1`-`V11` are applied by hand there.

New roles start `NOLOGIN`. `scripts/db/bootstrap_login_roles.sql` enables `rbac_app` / `rbac_admin` and
`scripts/db/bootstrap_service_login_roles.sql` enables the three service roles; both take SCRAM verifiers computed by
`scripts/db/scram_verifier.py` (usage in each file's header).

## Tests

```
./mvnw test
```

Runs every module's tests (about four minutes). They use embedded Postgres, so no Docker is needed.
