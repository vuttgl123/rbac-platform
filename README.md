# rbac-platform

Multi-tenant RBAC platform: three Spring Boot services on one PostgreSQL database, with tenant isolation enforced by row-level security.

## Modules

| Path | Purpose |
| --- | --- |
| `libs/lib-core` | Logging (`logback-spring.xml`, request ids), `RequestLoggingFilter`, error responses carrying the request id, the event contracts (`EventHandler`, `EventEnvelope`, `EventDispatcher`) |
| `libs/lib-db` | Shared data source defaults, `TenantScope`, startup check that the service connects as its own non-bypass role |
| `libs/lib-outbox` | Transactional outbox: `OutboxWriter` appends events in the caller's tenant transaction, `OutboxRelay` publishes them asynchronously and deletes them once delivered |
| `libs/lib-batch` | Spring Batch with a per-service job repository, `JobLaunchService`, slot-guarded cron scheduling (`ScheduledJobRunner`), tenant fan-out, `PartitionMaintenanceJob` |
| `db-migrations` | Flyway migrations `V1`-`V13`, the Flyway plugin setup, DB tests with embedded Postgres (also a test-jar) |
| `test-support` | Test-only helpers (`AppDatabase`, ...) used by the other modules |
| `services/identity-service` | Identity service, port 8081, database role `identity_app` |
| `services/authz-service` | Authorization service, port 8082, database role `authz_app` |
| `services/audit-service` | Audit service, port 8083, database role `audit_app` |

Services depend on the libs, never the reverse. No service runs migrations.

## Async

Work is split by how it must behave, not by who calls it.

| Kind | Runs | Mechanism | Use it for |
| --- | --- | --- | --- |
| CRUD | In the request, in one tenant transaction (`TenantScope.call`) | `JdbcClient` and hand-written SQL | Changes the caller waits for |
| Outbox | After commit, on the relay's own thread | `OutboxWriter.append` in the same transaction as the change; `OutboxRelay` publishes and deletes the row | Side effects of a change (events, notifications) that must not be lost or slow the request |
| Batch | On a cron schedule or on demand | A `Job` bean, launched by `ScheduledJobRunner` or `JobLaunchService.launch` | Periodic or bulk work, such as partition maintenance |

identity-service and authz-service have an outbox (`identity.outbox`, `authz.outbox`). authz-service and audit-service run
batch jobs, with the job repository in their own schema (`authz.batch_*`, `audit.batch_*`). Both run `partition-maintenance`
daily at 02:00 UTC.

### `rbac.outbox.*` (lib-outbox)

Active only when `rbac.outbox.schema` is set.

| Property | Default | Meaning |
| --- | --- | --- |
| `schema` | none | Schema that owns the service's `outbox` table; must match `[a-z_][a-z0-9_]*` |
| `enabled` | `true` | Whether this instance runs the relay |
| `publisher` | `log` | `log` writes one INFO line per event; `in-process` delivers to the service's own `EventHandler` beans |
| `batch-size` | `100` | Rows claimed per relay pass (1-1000) |
| `poll-interval` | `500ms` | Longest wait between passes when no writer wakes the relay |
| `lease` | `60s` | How long a claimed row is held before another relay may claim it again |
| `max-attempts` | `10` | Deliveries tried before a row is buried |
| `backoff.initial` / `backoff.max` | `1s` / `5m` | Delay after the first failure, and its ceiling as failures accumulate |

### `rbac.batch.*` (lib-batch)

Active only when `rbac.batch.schema` is set. A service that sets it must also import the batch defaults, otherwise startup
fails:

```yaml
spring:
  config:
    import:
      - classpath:rbac/batch-defaults.yaml
```

| Property | Default | Meaning |
| --- | --- | --- |
| `schema` | none | Schema that holds the job repository and schedule slots; must match `[a-z_][a-z0-9_]*` |
| `scheduler-pool-size` | `2` | Threads that fire scheduled jobs |
| `jobs.<name>.cron` | none | Spring cron expression (six fields, seconds first), evaluated in UTC; a job without one is only launched on demand |
| `jobs.<name>.enabled` | `true` | Whether this job is scheduled |

### `rbac.events.*` (lib-core)

| Property | Default | Meaning |
| --- | --- | --- |
| `unhandled` | `ignore` | What the dispatcher does with an event no `EventHandler` claims: `ignore` or `fail` |

### Adding a handler

Declare one `EventHandler` bean. It names its event types in `eventTypes()` (or `EventHandler.ANY`) and processes them in
`handle`; nothing dispatches on the type. Delivery is at-least-once, so make it idempotent on `EventEnvelope.id()`. Throw
`NonRetryableEventException` to stop retries. Set `rbac.outbox.publisher: in-process` for the service's own handlers to
receive its outbox events.

### Adding a job

Declare a `Job` bean (see `BatchJobsConfig` in authz-service) and add `rbac.batch.jobs.<name>.cron` for it. Launch it on
demand with `JobLaunchService.launch("<name>", Map.of("run", UUID.randomUUID().toString()))`. The job runs on the caller's
thread and returns the finished execution.

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
SQL Editor (the network blocks the database port); `V1`-`V10` are applied by hand there. `V11`-`V13` (service roles, outbox,
batch repository and partition maintenance functions) are not applied to Supabase yet, so the outbox and batch features
need them before a service with these settings can run against it.

New roles start `NOLOGIN`. `scripts/db/bootstrap_login_roles.sql` enables `rbac_app` / `rbac_admin` and
`scripts/db/bootstrap_service_login_roles.sql` enables the three service roles; both take SCRAM verifiers computed by
`scripts/db/scram_verifier.py` (usage in each file's header).

## Tests

```
./mvnw test
```

Runs every module's tests (about four minutes). They use embedded Postgres, so no Docker is needed.
