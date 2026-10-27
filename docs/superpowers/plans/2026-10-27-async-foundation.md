# Async Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the platform's async foundation: a per-service transactional outbox with a pluggable relay, shared event-handler contracts, Spring Batch with per-service job repositories and config-driven scheduling, and a first real job that maintains the monthly partitions.

**Architecture:**
- V12 and V13 add per-schema outbox and batch objects through owner-only `util.create_*` functions. Service roles reach rows of other tenants only through `SECURITY DEFINER` functions.
- `lib-core` gains the event contracts: envelope, handler, registry, dispatcher.
- `lib-outbox` holds the writer, the store (JDBC over the definer functions) and a lease-based relay. The relay's variable parts are strategies: publisher, retry policy, interceptors.
- `lib-batch` holds the Spring Batch JDBC repository config, a cron runner guarded by a slot table, a launch/restart/recover service, tenant fan-out, and the partition-maintenance job factory.
- Services opt in with configuration.

**Tech Stack:**
- Java 21, Spring Boot 4.1.2, Spring Batch 6.0.6 (`spring-boot-starter-batch-jdbc`), Jackson 3 (`tools.jackson.*`).
- Spring JDBC `JdbcClient`.
- PostgreSQL 17 (zonky embedded), Flyway, JUnit 5, AssertJ, Awaitility.

**Spec:** `docs/superpowers/specs/2026-10-27-async-foundation-design.md`

## Global Constraints

- Data access only through `JdbcClient`, `JdbcTemplate` or `TransactionTemplate` with hand-written SQL. No JPA or Hibernate.
- Never edit V1–V11. New SQL goes in `V12__outbox.sql` and `V13__batch_repository.sql`, starting with `SET LOCAL ROLE rbac_owner;` like V2–V10.
- Every `SECURITY DEFINER` function has `SET search_path = pg_catalog, extensions`, and `REVOKE EXECUTE ... FROM PUBLIC` before its targeted `GRANT`.
- No `switch` or `if` on event type or job name in relay, dispatcher or runner code. Behaviour varies through beans and configuration only.
- Libs may depend on `lib-core`, `lib-db` and their own technology. Never on a service.
- No service runs migrations. `spring.batch.jdbc.initialize-schema` is `never`.
- Schema names that come from configuration (`rbac.outbox.schema`, `rbac.batch.schema`) must match `[a-z_][a-z0-9_]*`, otherwise startup fails. They are the only identifiers concatenated into SQL.
- Tests run on the shared embedded Postgres (`MigratedDatabase` / `AppDatabase`). No Docker.
- Commit as `git -c user.name=vuttgl123 -c user.email=phamtuanvu1401@gmail.com commit ...`. Messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Full build is green after every task: `./mvnw -q verify`.

## Review Focus

1. **Database down while the relay polls.** A claim that throws (connection refused, timeout) must not kill the relay thread. It logs an ERROR, waits `poll-interval`, and tries again. Test: Task 5, `relaySurvivesAStoreFailureAndKeepsPolling`.
2. **One bad event in a batch.** The other events of that batch still complete, and only the failing one is rescheduled. Test: Task 5, `oneFailingEventDoesNotHoldBackTheRestOfItsBatch`.
3. **`append` called in a transaction without a tenant.** This happens when someone uses `TransactionTemplate` directly instead of `TenantScope`. It must fail with a message naming `TenantScope.call`, not a bare NOT NULL violation. Test: Task 4, `appendWithoutATenantFailsWithAClearMessage`.
4. **Shutdown while a batch is being published.** The current batch completes and is acknowledged before the relay stops, so nothing waits a full lease to be re-sent. Test: Task 5, `stopFinishesTheBatchInFlight`.
5. **Config typos in jobs.** A job name with no bean, an invalid cron, or `enabled` without a cron fails startup with the property name in the message. A job that throws at run time does not cancel its future runs. Tests: Task 6, `misconfiguredJobsFailStartup` and `aFailingRunDoesNotStopLaterRuns`.

---

### Task 1: V12 outbox schema

**Files:**
- Create: `db-migrations/src/main/resources/db/migration/V12__outbox.sql`
- Create: `db-migrations/src/test/java/com/rbac_platform/db/OutboxSchemaTest.java`
- Modify: `db-migrations/src/test/java/com/rbac_platform/db/ServiceRolesTest.java`
  - adds the outbox entries to the exact-ACL matrix;
  - adds the `rbac_admin` outbox check.
- Modify: `db-migrations/src/test/java/com/rbac_platform/db/TenantIsolationTest.java`
  - `everyTenantScopedTableForcesRlsWithStandardPolicy` excludes tables named `outbox`, which `OutboxSchemaTest` covers instead.
- Modify: `db-migrations/src/test/java/com/rbac_platform/db/SupabaseExposureTest.java`
  - add `create_outbox` to the names checked in `appRoleCannotExecuteAdminOnlyFunctions`.

**Interfaces:**
- Produces, for SQL callers in Tasks 4–5. In the signatures below, `<s>` is `identity` or `authz`.
  - `util.create_outbox(p_schema text, p_role regrole) RETURNS void` is plpgsql and **not** a definer. It is owner-only: EXECUTE is revoked from PUBLIC.
  - Table `<s>.outbox`:
    - `tenant_id uuid NOT NULL`
    - `id uuid PK DEFAULT util.uuid_v7()`
    - `aggregate_type text NOT NULL`
    - `aggregate_id text NOT NULL`
    - `event_type text NOT NULL`
    - `payload jsonb NOT NULL`
    - `headers jsonb NOT NULL DEFAULT '{}'`
    - `created_at timestamptz NOT NULL DEFAULT now()`
    - `status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','dead'))`
    - `attempts int NOT NULL DEFAULT 0`
    - `next_attempt_at timestamptz NOT NULL DEFAULT now()`
    - `claim_token uuid`
    - `last_error text`
    - There is no FK to `tenancy.tenants`, for the same reason as `audit.events` (V8).
    - The table is created with `WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 50)`.
    - Partial index `outbox_pending_idx ON <s>.outbox (next_attempt_at, id) WHERE status = 'pending'`.
  - RLS: `ENABLE` and `FORCE`. Exactly two policies:
    - `tenant_isolation FOR INSERT TO <role> WITH CHECK (tenant_id = (SELECT util.current_tenant_id()))`;
    - `owner_all FOR ALL TO rbac_owner USING (true) WITH CHECK (true)`.
  - `GRANT INSERT ON <s>.outbox TO <role>`. Nothing else on the table, for anyone.
  - Definer functions, all `LANGUAGE sql` or `plpgsql SECURITY DEFINER`:
    - `<s>.outbox_claim(p_limit int, p_lease interval, p_token uuid) RETURNS SETOF <s>.outbox`
      - Raises `invalid_parameter_value` unless `p_limit` is between 1 and 1000, `p_lease > interval '0'` and `p_token` is not null.
      - `UPDATE ... SET attempts = attempts + 1, next_attempt_at = now() + p_lease, claim_token = p_token WHERE id IN (SELECT id FROM <s>.outbox WHERE status = 'pending' AND next_attempt_at <= now() ORDER BY next_attempt_at, id LIMIT p_limit FOR UPDATE SKIP LOCKED) RETURNING *`.
      - EXECUTE is granted to `<role>`.
    - `<s>.outbox_complete(p_token uuid, p_ids uuid[]) RETURNS int`
      - Deletes rows with `id = ANY(p_ids) AND claim_token = p_token AND status = 'pending'` and returns the count.
      - EXECUTE is granted to `<role>`.
    - `<s>.outbox_reschedule(p_token uuid, p_id uuid, p_error text, p_next_attempt_at timestamptz, p_dead boolean) RETURNS boolean`
      - Only touches a row where `id = p_id AND claim_token = p_token AND status = 'pending'`. It sets:
        - `status = CASE WHEN p_dead THEN 'dead' ELSE 'pending' END`
        - `next_attempt_at = coalesce(p_next_attempt_at, now())`
        - `last_error = left(p_error, 2000)`
        - `claim_token = NULL`
      - Returns whether a row changed.
      - EXECUTE is granted to `<role>`.
    - `<s>.outbox_dead(p_limit int) RETURNS SETOF <s>.outbox`
      - Returns dead rows of every tenant, `ORDER BY created_at, id LIMIT p_limit`, with `p_limit` between 1 and 1000.
      - EXECUTE is granted to `rbac_admin`.
    - `<s>.outbox_requeue(p_ids uuid[]) RETURNS int`
      - For dead rows in `p_ids`, sets `status = 'pending', attempts = 0, next_attempt_at = now(), claim_token = NULL, last_error = NULL`, and returns the count.
      - EXECUTE is granted to `rbac_admin`.
  - Calls: `SELECT util.create_outbox('identity', 'identity_app'); SELECT util.create_outbox('authz', 'authz_app');`
  - `create_outbox` raises `invalid_parameter_value` when the schema does not exist or is not owned by `rbac_owner`.

- [ ] **Step 1: Write `OutboxSchemaTest`.**
  Use the `MigratedDatabase` and `Sql` helpers and the style of `ServiceRolesTest`. Insert as the service role inside a tenant with `db.inTx("identity_app", t, ...)`. Tests:
  - `serviceRoleInsertsOnlyIntoItsTenant`
    - An insert with `tenant_id = t` succeeds.
    - An insert with another tenant's id fails `42501`.
    - An insert with no tenant set fails, with sqlState `23502` or `42501`.
  - `serviceRoleCannotReadUpdateOrDeleteTheTable`
    - `select`, `update` and `delete` on `identity.outbox` as `identity_app` each fail `42501`.
  - `otherServiceRolesCannotTouchTheOutboxOrItsFunctions`
    - `authz_app` and `audit_app` cannot insert into `identity.outbox` and cannot execute `identity.outbox_claim`. `identity_app` cannot do either on `authz.outbox`.
    - Every one of these fails `42501`.
  - `claimSeesEveryTenantInOrderAndLeasesTheRows`
    - Set up: events in tenants A and B.
    - `identity_app` with no tenant set calls `outbox_claim(10, '30 seconds', tok)`. It gets both rows, with `attempts = 1`, `claim_token = tok` and `next_attempt_at > now()`.
    - A second claim right after returns 0 rows.
  - `concurrentClaimsNeverReturnTheSameRow`
    - Two connections each open a transaction and claim 5 out of 10 pending rows before either commits.
    - The two id sets are disjoint and their union has size 10.
  - `completeAndRescheduleRequireTheCurrentToken`
    - With a stale token, `outbox_complete` returns 0 and `outbox_reschedule` returns false.
    - With the right token:
      - `outbox_reschedule(..., p_dead => false)` leaves the row pending, with `claim_token` null and `last_error` set;
      - `outbox_complete` deletes the row and returns 1.
  - `rescheduleCanBuryAndAdminCanListAndRequeue`
    - After `p_dead => true`, the row has status `dead`.
    - `rbac_admin` sees it via `outbox_dead(10)`.
    - `outbox_requeue` returns 1, and the row is pending again with `attempts = 0`.
    - `identity_app` executing `outbox_dead` fails `42501`.
  - `claimRejectsInvalidArguments`
    - `p_limit` of 0 or 1001, a lease of `'0 seconds'`, or a null token each fail `22023`.
  - `outboxPoliciesAreExactlyInsertForTheRoleAndOwnerAll`
    - `pg_policies` for `identity.outbox` is exactly `{tenant_isolation INSERT {identity_app}, owner_all ALL {rbac_owner}}`.
    - RLS is enabled and forced.
  - `pendingIndexIsUsedByTheClaimQuery`
    - Set up: insert 2,000 rows as superuser, all with `next_attempt_at = now() + interval '1 day'`, then `ANALYZE identity.outbox`.
    - Run `EXPLAIN (FORMAT JSON)` on the claim subquery `SELECT id FROM identity.outbox WHERE status = 'pending' AND next_attempt_at <= now() ORDER BY next_attempt_at, id LIMIT 100`.
    - The plan mentions `outbox_pending_idx`.
    - Clean up: delete the rows.
  - `createOutboxRejectsAForeignSchema`
    - `select util.create_outbox('public', 'identity_app')` as superuser fails `22023`.

- [ ] **Step 2: Extend `ServiceRolesTest.privilegesAreExactlyRbacAppsOnOwnedSchemasPlusSharedAndExceptions`.**
  Add explicit lists `IDENTITY_OUTBOX` and `AUTHZ_OUTBOX` to the expected sets. For identity:
  ```java
  "table identity.outbox INSERT",
  "function identity.outbox_claim(integer,interval,uuid) EXECUTE",
  "function identity.outbox_complete(uuid,uuid[]) EXECUTE",
  "function identity.outbox_reschedule(uuid,uuid,text,timestamp with time zone,boolean) EXECUTE"
  ```
  `AUTHZ_OUTBOX` is the same with `authz.`.
  Add the test `adminGetsOnlyTheOutboxOperatorFunctions`:
  - Collect `rbac_admin`'s ACL entries on objects whose name contains `outbox`.
  - They are exactly the `EXECUTE` entries for `identity.outbox_dead(integer)`, `identity.outbox_requeue(uuid[])`, `authz.outbox_dead(integer)` and `authz.outbox_requeue(uuid[])`.

- [ ] **Step 3: Run the tests.**
  Run `./mvnw -q -pl db-migrations test -Dtest='OutboxSchemaTest,ServiceRolesTest'`. Expect FAIL, because the relations do not exist.

- [ ] **Step 4: Write `V12__outbox.sql`.**
  `util.create_outbox` builds each object with `EXECUTE format(...)`, using `%I` for the schema and `%s` for `p_role::text`. The function bodies use their own dollar-quote tag, for example `$fn$`, inside the outer `$$`. Write a header comment explaining:
  - INSERT-only, so the writer cannot use `RETURNING` or `ON CONFLICT`;
  - the lease;
  - why there is no FK.

- [ ] **Step 5: Adjust `TenantIsolationTest` and `SupabaseExposureTest`.**
  Make the two edits listed under Files.

- [ ] **Step 6: Run the module.**
  Run `./mvnw -q -pl db-migrations verify`. Expect every test to PASS, the old 182 and the new ones.

- [ ] **Step 7: Commit.** Message: `feat(db): V12 per-service transactional outbox`.

---

### Task 2: V13 batch repositories and partition maintenance functions

**Files:**
- Create: `db-migrations/src/main/resources/db/migration/V13__batch_repository.sql`
- Create: `db-migrations/src/test/java/com/rbac_platform/db/BatchRepositorySchemaTest.java`
- Modify: `db-migrations/src/test/java/com/rbac_platform/db/ServiceRolesTest.java`
  - batch and maintenance entries go into the exact-ACL matrix.
- Modify: `db-migrations/src/test/java/com/rbac_platform/db/SupabaseExposureTest.java`
  - add `create_batch_repository` to the names list.

**Interfaces:**
- Produces, for Task 6:
  - `util.create_batch_repository(p_schema text, p_role regrole) RETURNS void`
    - Owner-only and not a definer. Raises `invalid_parameter_value` like `create_outbox`.
    - Creates in `p_schema` the DDL of `org/springframework/batch/core/schema-postgresql.sql` from spring-batch-core 6.0.6, verbatim apart from the `batch_` prefix on every table and sequence name: `batch_job_instance`, `batch_job_execution`, `batch_job_execution_params`, `batch_step_execution`, `batch_step_execution_context`, `batch_job_execution_context`, and the sequences `batch_step_execution_seq`, `batch_job_execution_seq`, `batch_job_instance_seq`.
    - Also creates `batch_schedule_slot (job_name text NOT NULL, scheduled_at timestamptz NOT NULL, claimed_by text NOT NULL, claimed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (job_name, scheduled_at))`.
    - Grants to `p_role`:
      - `SELECT, INSERT, UPDATE` on the 6 batch tables;
      - `SELECT, INSERT` on `batch_schedule_slot`;
      - `USAGE, SELECT` on the 3 sequences.
    - No RLS, no other grants.
  - Calls for `('identity','identity_app')`, `('authz','authz_app')` and `('audit','audit_app')`.
  - `audit.maintain_partitions() RETURNS int` is `LANGUAGE sql SECURITY DEFINER`.
    - Body: `SELECT util.ensure_monthly_partitions('audit.events'::regclass, 3)`.
    - EXECUTE is revoked from PUBLIC and granted to `audit_app` only.
  - `authz.maintain_partitions() RETURNS int` does the same for `'authz.relationship_changes'` and `authz_app`.

- [ ] **Step 1: Write `BatchRepositorySchemaTest`.**
  - `eachServiceSchemaHasTheSpringBatchTables`
    - In each of `identity`, `authz` and `audit`, `to_regclass` is not null for all 9 objects and for `batch_schedule_slot`.
    - The column list of `audit.batch_step_execution`, read from `information_schema.columns` in ordinal order, equals the 6.0.6 DDL's list: `step_execution_id, version, step_name, job_execution_id, create_time, start_time, end_time, status, commit_count, read_count, filter_count, write_count, read_skip_count, write_skip_count, process_skip_count, rollback_count, exit_code, exit_message, last_updated`.
  - `serviceRoleRunsItsOwnRepositoryOnly`
    - As `audit_app`, these succeed:
      - `select nextval('audit.batch_job_instance_seq')`;
      - an insert into `audit.batch_job_instance`;
      - an update of it.
    - Deleting the row fails `42501`.
    - As `audit_app`, `select` from `identity.batch_job_instance` fails `42501`.
  - `scheduleSlotAcceptsTheFirstClaimOnly`
    - As `authz_app`, two `insert ... on conflict do nothing` for the same `(job_name, scheduled_at)` affect 1 and then 0 rows.
  - `maintainPartitionsIsOwnedPerService`
    - Set up: as superuser, drop the empty `audit.events` partition for UTC month +3. Compute its name with `to_char(date_trunc('month', now() at time zone 'utc') + interval '3 months', 'YYYYMM')`.
    - As `audit_app`, `select audit.maintain_partitions()` returns 1 and the partition exists again.
    - As `audit_app`, `select authz.maintain_partitions()` fails `42501`. As `authz_app`, `select audit.maintain_partitions()` fails `42501`.
    - Also: `identity_app` calling `util.ensure_monthly_partitions` fails `42501`.

- [ ] **Step 2: Extend the `ServiceRolesTest` expected sets.**
  - Add a helper `batchEntries(String schema)` that returns the 26 entries:
    - `table <s>.batch_* SELECT/INSERT/UPDATE` for the 6 tables;
    - `table <s>.batch_schedule_slot SELECT/INSERT`;
    - `table <s>.batch_*_seq USAGE/SELECT` for the 3 sequences.
  - Add the result for each role.
  - Add `function audit.maintain_partitions() EXECUTE` for `audit_app`, and the authz one for `authz_app`.

- [ ] **Step 3: Confirm the tests fail.** Run `./mvnw -q -pl db-migrations test -Dtest='BatchRepositorySchemaTest,ServiceRolesTest'` and expect FAIL.

- [ ] **Step 4: Write `V13__batch_repository.sql`.**
  The header comment cites the source file and version of the DDL, and says why the tables have no RLS: they hold job metadata, not tenant data.

- [ ] **Step 5: Run the module.** Run `./mvnw -q -pl db-migrations verify` and expect PASS.

- [ ] **Step 6: Commit.** Message: `feat(db): V13 per-service Spring Batch repositories and partition maintenance functions`.

---

### Task 3: Event contracts in lib-core

**Files:**
- Create in `libs/lib-core/src/main/java/com/rbac_platform/lib/core/event/`:
  - `EventEnvelope.java`
  - `EventHandler.java`
  - `EventHandlerRegistry.java`
  - `EventDispatcher.java`
  - `UnhandledEventPolicy.java`
  - `UnhandledEventException.java`
  - `NonRetryableEventException.java`
  - `EventProperties.java`
- Modify: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/CoreAutoConfiguration.java`
- Test: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/event/EventHandlerRegistryTest.java` and `EventDispatcherTest.java`
- Modify: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/CoreAutoConfigurationTest.java`

**Interfaces:**
- Produces, used by Task 5, by a later `lib-kafka`, and by services:
  ```java
  public record EventEnvelope(UUID id, UUID tenantId, String aggregateType, String aggregateId,
                              String eventType, tools.jackson.databind.JsonNode payload,
                              Map<String, String> headers, Instant occurredAt)
  // Compact constructor: requireNonNull on every component; headers = Map.copyOf(headers).

  public interface EventHandler {
      String ANY = "*";
      Set<String> eventTypes();
      void handle(EventEnvelope event) throws Exception;
  }
  // Javadoc states: delivery is at-least-once, handlers must be idempotent on event.id().

  public final class EventHandlerRegistry {
      public EventHandlerRegistry(List<EventHandler> handlers);
      public List<EventHandler> handlersFor(String eventType); // specific handlers, then ANY handlers
      public boolean hasSpecificHandler(String eventType);
  }

  public enum UnhandledEventPolicy { IGNORE, FAIL }

  public class NonRetryableEventException extends RuntimeException { /* (String), (String, Throwable) */ }

  public class UnhandledEventException extends RuntimeException { /* retryable */ }

  public final class EventDispatcher {
      public EventDispatcher(EventHandlerRegistry registry, UnhandledEventPolicy policy);
      public void dispatch(EventEnvelope event) throws Exception;
  }

  @ConfigurationProperties("rbac.events")
  public record EventProperties(@DefaultValue("ignore") UnhandledEventPolicy unhandled) {}
  ```
  - `handlersFor` returns the specific handlers and then the ANY handlers, each group in the order the list was given. That is bean order, so `@Order` is honoured.
  - The registry constructor throws `IllegalArgumentException` when a handler returns null or empty `eventTypes()`, or a blank type. The message names the handler class.
- Behaviour of `dispatch`:
  - The handlers run in order. The first exception propagates and later handlers do not run.
  - With no specific handler and `IGNORE`, it logs WARN `No handler for event type '{}' (event {})`, then runs the ANY handlers.
  - With no specific handler and `FAIL`, it throws `UnhandledEventException` before running anything.
- `CoreAutoConfiguration` adds three beans, each `@ConditionalOnMissingBean`:
  - `EventHandlerRegistry` built from `ObjectProvider<EventHandler>.orderedStream()`;
  - `EventDispatcher`;
  - `@EnableConfigurationProperties(EventProperties.class)`.

- [ ] **Step 1: Write `EventHandlerRegistryTest`.**
  - `handlersAreGroupedByTypeWithWildcardsLast`
    - Handlers: h1 for `user.updated`, h2 for `*`, h3 for `user.updated` and `user.deleted`.
    - `handlersFor("user.updated")` is `[h1, h3, h2]`.
    - `handlersFor("user.deleted")` is `[h3, h2]`.
    - `handlersFor("x")` is `[h2]`.
    - `hasSpecificHandler("x")` is false.
  - `rejectsAHandlerWithoutTypes`
    - Empty `eventTypes()` and a blank type each throw `IllegalArgumentException` containing the handler's class name.

- [ ] **Step 2: Write `EventDispatcherTest`.**
  - `dispatchRunsEveryMatchingHandlerInOrder`
  - `firstFailureStopsTheRemainingHandlers`
  - `ignorePolicyStillRunsWildcardHandlers`
  - `failPolicyThrowsUnhandledEventExceptionAndRunsNothing`

- [ ] **Step 3: Extend `CoreAutoConfigurationTest`.**
  - `eventBeansAreAutoConfiguredAndBackOff`
    - Uses `ApplicationContextRunner`.
    - With two `EventHandler` beans, the registry has both.
    - With `rbac.events.unhandled=fail`, the dispatcher fails on an unknown type.
    - A user-defined `EventDispatcher` bean replaces the default one.

- [ ] **Step 4: Run the tests.** Run `./mvnw -q -pl libs/lib-core test` and expect FAIL.

- [ ] **Step 5: Implement the classes listed in Interfaces.**

- [ ] **Step 6: Run the tests again.** Run `./mvnw -q -pl libs/lib-core verify` and expect PASS.

- [ ] **Step 7: Commit.** Message: `feat(core): event envelope, handler registry and dispatcher`.

---

### Task 4: lib-outbox module, writer and store

**Files:**
- Modify: root `pom.xml`
  - add `<module>libs/lib-outbox</module>` after `libs/lib-db`;
  - add a `dependencyManagement` entry for `lib-outbox`.
- Create: `libs/lib-outbox/pom.xml`
  - depends on `lib-core` and `lib-db`;
  - test dependencies: `spring-boot-starter-test`, `test-support`, `org.awaitility:awaitility`.
- Create in `libs/lib-outbox/src/main/java/com/rbac_platform/lib/outbox/`:
  - `OutboxProperties.java`
  - `OutboxMessage.java`
  - `OutboxRecord.java`
  - `OutboxWriter.java`
  - `OutboxWakeup.java`
  - `OutboxStore.java`
  - `OutboxAutoConfiguration.java`
- Create: `libs/lib-outbox/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test, in `libs/lib-outbox/src/test/java/com/rbac_platform/lib/outbox/`: `OutboxTestApplication.java`, `OutboxWriterTest.java`, `OutboxStoreTest.java`
  - Tests log in as `identity_app` with `rbac.outbox.schema=identity` and `rbac.outbox.enabled=false`.

**Interfaces:**
- Consumes:
  - `TenantScope.call(UUID, Supplier<T>)` (lib-db);
  - the V12 functions (Task 1);
  - `EventEnvelope` (Task 3);
  - the MDC key `RequestLoggingFilter.MDC_KEY` (`"requestId"`, lib-core).
- Produces:
  ```java
  @ConfigurationProperties("rbac.outbox")
  public record OutboxProperties(String schema,
          @DefaultValue("true") boolean enabled,
          @DefaultValue("log") String publisher,
          @DefaultValue("100") int batchSize,
          @DefaultValue("500ms") Duration pollInterval,
          @DefaultValue("60s") Duration lease,
          @DefaultValue("10") int maxAttempts,
          @DefaultValue Backoff backoff) {
      public record Backoff(@DefaultValue("1s") Duration initial, @DefaultValue("5m") Duration max) {}
  }
  // Validation: schema matches [a-z_][a-z0-9_]*, batchSize between 1 and 1000, the durations are positive,
  // maxAttempts >= 1. A violation fails startup and the message names the property.

  public record OutboxMessage(String aggregateType, String aggregateId, String eventType,
                              JsonNode payload, Map<String, String> headers) {
      public static OutboxMessage of(String aggregateType, String aggregateId, String eventType, JsonNode payload);
  }
  // Every component is non-null, and the first three are not blank. headers defaults to an empty map.

  public record OutboxRecord(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                             JsonNode payload, Map<String, String> headers, Instant createdAt, int attempts) {
      public EventEnvelope toEnvelope(); // occurredAt = createdAt
  }

  public class OutboxWriter {
      public void append(OutboxMessage message);
      public void appendAll(List<OutboxMessage> messages); // one database round trip
  }

  public class OutboxWakeup {
      public void signal();
      public void await(Duration max) throws InterruptedException; // returns early once signalled
  }

  public class OutboxStore {
      public List<OutboxRecord> claim(int limit, Duration lease, UUID token); // sorted by id
      public int complete(UUID token, Collection<UUID> ids);                   // empty ids: no call, returns 0
      public boolean reschedule(UUID token, UUID id, String error, Instant nextAttemptAt, boolean dead);
  }
  ```
- Writer rules:
  - Outside an actual transaction it throws `IllegalStateException`. The message contains `TenantScope.call`.
  - The SQL inserts `tenant_id` as `(select util.current_tenant_id())`. A null tenant raises a NOT NULL violation (23502). The writer translates it into `IllegalStateException`, again with a message containing `TenantScope.call`.
  - Headers are the message headers plus `requestId` from MDC when present and not already set by the message.
  - JSON goes in with `?::jsonb`, using Boot's Jackson 3 `JsonMapper`.
  - The writer registers one `TransactionSynchronization` per transaction, not one per append. Its `afterCommit` calls `OutboxWakeup.signal()`.
- `OutboxStore` runs each method as one statement on the caller's connection. It joins the caller's transaction when there is one, and autocommits otherwise.
- `OutboxAutoConfiguration` is `@AutoConfiguration(after = DbAutoConfiguration.class)` and `@ConditionalOnProperty("rbac.outbox.schema")`. In this task its beans are `OutboxWakeup`, `OutboxWriter` and `OutboxStore`, each `@ConditionalOnMissingBean`.

- [ ] **Step 1: Write `OutboxWriterTest`.**
  Use `@SpringBootTest(classes = OutboxTestApplication.class)`, and read rows back as superuser through `MigratedDatabase`.
  - `appendCommitsWithTheBusinessWrite`
    - Inside `tenantScope.call(t, ...)`: insert an `identity.users` row and append one message. Before that, put MDC `requestId=req-1`.
    - The outbox has 1 row with `tenant_id = t`, `event_type`, the payload, `headers ->> 'requestId' = 'req-1'`, and status `pending`.
  - `rollbackLeavesNoEvent`
    - Same as above, but the supplier throws after the append. No user row and no outbox row.
  - `appendAllWritesEveryMessageInOneCall`
    - Three messages in one call give 3 rows.
  - `appendOutsideATransactionFails`
    - `IllegalStateException` with a message containing `TenantScope.call`.
  - `appendWithoutATenantFailsWithAClearMessage`
    - Run `append` inside `TransactionTemplate.execute`, with no tenant. `IllegalStateException` with a message containing `TenantScope.call`.
  - `commitSignalsTheWakeup`
    - A spy or counting `OutboxWakeup` bean shows exactly one `signal()` after a transaction with two appends, and none after a rollback.
  - `messageRejectsMissingFields`
    - A null payload or a blank `eventType` throws.

- [ ] **Step 2: Write `OutboxStoreTest`.**
  - `claimCompleteAndRescheduleRoundTrip`
    - Rows inserted through the writer are claimed sorted by id, with `attempts = 1`.
    - `complete` with the token removes them.
    - `reschedule` with the token and `dead=true` makes the row dead.
    - A stale token gives `false` or `0`.
  - `completeWithNoIdsDoesNotCallTheDatabase`
    - Returns 0.
  - `invalidSchemaPropertyFailsStartup`
    - `ApplicationContextRunner` with `rbac.outbox.schema=identity;drop` fails, and the failure message contains `rbac.outbox.schema`.

- [ ] **Step 3: Run the tests.** Run `./mvnw -q -pl libs/lib-outbox -am test`. Expect FAIL, since the classes do not exist yet.

- [ ] **Step 4: Implement.** `make_interval(secs => ?)` is a good way to bind the lease.

- [ ] **Step 5: Run the module.** Run `./mvnw -q -pl libs/lib-outbox -am verify` and expect PASS.

- [ ] **Step 6: Commit.** Message: `feat(outbox): lib-outbox writer and store over the V12 functions`.

---

### Task 5: Outbox relay and its strategies

**Files:**
- Create in `libs/lib-outbox/src/main/java/com/rbac_platform/lib/outbox/`:
  - `publish/OutboxPublisher.java`
  - `publish/LoggingOutboxPublisher.java`
  - `publish/InProcessOutboxPublisher.java`
  - `retry/RetryPolicy.java`
  - `retry/RetryDecision.java`
  - `retry/ExponentialBackoffRetryPolicy.java`
  - `relay/OutboxInterceptor.java`
  - `relay/MdcOutboxInterceptor.java`
  - `relay/TimingOutboxInterceptor.java`
  - `relay/OutboxRelay.java`
- Modify: `OutboxAutoConfiguration.java`
- Test: `ExponentialBackoffRetryPolicyTest.java`, `OutboxRelayTest.java`, `OutboxAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `OutboxStore`, `OutboxWakeup`, `OutboxRecord.toEnvelope()` (Task 4); `EventDispatcher`, `NonRetryableEventException` (Task 3).
- Produces:
  ```java
  public interface OutboxPublisher { void publish(EventEnvelope event) throws Exception; }

  public sealed interface RetryDecision {
      record RetryAt(Instant at) implements RetryDecision {}
      record Bury(String reason) implements RetryDecision {}
  }

  public interface RetryPolicy { RetryDecision decide(OutboxRecord record, Throwable failure, Instant now); }

  public class ExponentialBackoffRetryPolicy implements RetryPolicy {
      public ExponentialBackoffRetryPolicy(Duration initial, Duration max, int maxAttempts, RandomGenerator random);
  }

  public interface OutboxInterceptor {
      default void before(EventEnvelope event) {}
      default void after(EventEnvelope event, Duration took) {}
      default void onError(EventEnvelope event, Throwable failure) {}
  }

  public class OutboxRelay implements SmartLifecycle {
      public int runOnce(); // one claim/publish/acknowledge cycle; returns the number of claimed rows
  }
  ```
- Retry policy:
  - Bury when the cause chain contains `NonRetryableEventException`, or when `record.attempts() >= maxAttempts`.
  - Otherwise `RetryAt(now + delay)`, with `d = min(max, initial × 2^(attempts−1))` and the delay in `[0.8·d, d]`.
- Publishers:
  - `LoggingOutboxPublisher` logs INFO `Outbox event {eventType} {aggregateType}/{aggregateId} (event {id})`.
  - `InProcessOutboxPublisher` delegates to `EventDispatcher.dispatch`.
- Interceptors:
  - `MdcOutboxInterceptor` (`@Order(HIGHEST_PRECEDENCE)`) puts MDC `requestId` from `headers.requestId` when present, plus `tenantId` and `eventId`. The relay restores the previous MDC after each event, whatever the interceptors do.
  - `TimingOutboxInterceptor` logs DEBUG for each success with its duration, and WARN with the event id and type for each error.
  - Interceptors run in bean order for `before`, and in reverse order for `after` and `onError`.
- `OutboxRelay.runOnce()`:
  1. `token = UUID.randomUUID()`, then `store.claim(batchSize, lease, token)`, which autocommits.
  2. Outside any transaction, for each record:
     - if `attempts > maxAttempts`, record a failure that is buried with reason `max attempts exceeded without completing`, and do not publish;
     - otherwise run `before`, `publisher.publish(record.toEnvelope())` and `after`;
     - on any `Exception`, run `onError` and record the failure with `retryPolicy.decide(...)`.
  3. In one `TransactionTemplate` transaction: `store.complete(token, successIds)`, then `store.reschedule(token, id, message, at, dead)` for each failure. `message` is `failure.getClass().getName() + ": " + failure.getMessage()`.
  4. When the cycle took longer than `lease`, log WARN that the events may have been claimed again by another instance.
- Relay loop:
  - It runs on one daemon thread named `outbox-relay-<schema>`.
  - A full batch repeats immediately. Otherwise the loop waits on `wakeup.await(pollInterval)`.
  - An exception from `runOnce` is logged at ERROR and the loop waits `pollInterval`. The thread never dies.
- Lifecycle:
  - `isAutoStartup()` returns `properties.enabled()`.
  - `stop(Runnable)` sets a flag, signals the wakeup, lets the current `runOnce` finish (bounded by `lease`), then runs the callback.
- Autoconfig:
  - **Publisher.** `LoggingOutboxPublisher` when `rbac.outbox.publisher=log` (the default). `InProcessOutboxPublisher` when it is `in-process`. Both are `@ConditionalOnMissingBean(OutboxPublisher.class)`.
  - **Unknown publisher value.** If `rbac.outbox.publisher` names neither and no other `OutboxPublisher` bean exists, startup fails with `No OutboxPublisher for rbac.outbox.publisher='<value>'`.
  - **Retry policy.** An `ExponentialBackoffRetryPolicy` from the properties, `@ConditionalOnMissingBean(RetryPolicy.class)`.
  - **Interceptors.** Both built-in interceptors are always registered, and services may add more.
  - **Relay.** One `OutboxRelay`.

- [ ] **Step 1: Write `ExponentialBackoffRetryPolicyTest`.** Use `initial` 1s, `max` 5m, `maxAttempts` 10 and a seeded random.
  - `delaysDoubleWithinTheJitterBand`
    - attempts 1 gives a delay in [0.8s, 1s], attempts 2 in [1.6s, 2s] and attempts 4 in [6.4s, 8s].
    - attempts 9 gives [204.8s, 256s], and attempts 10 is buried.
  - `delayIsCappedAtMax`
    - With `maxAttempts` 20, attempts 15 gives [240s, 300s].
  - `nonRetryableCauseIsBuriedImmediately`
    - A `RuntimeException` wrapping a `NonRetryableEventException` at attempts 1 gives `Bury`.

- [ ] **Step 2: Write `OutboxRelayTest`.**
  - Use `@SpringBootTest` with `enabled=false`, so the tests drive `runOnce()` themselves, except the lifecycle tests.
  - Inject a test `OutboxPublisher` bean that records envelopes and can be told to fail on given event types or to block on a latch.
  - Tests:
    - `publishedEventsAreDeleted`
      - Two appended events give `runOnce() == 2`. The publisher saw both, in id order, and the outbox is empty afterwards.
    - `failedEventIsRescheduledWithBackoffAndError`
      - The row stays pending with `attempts = 1`, `next_attempt_at` between `now()+0.8s` and `now()+1s`, `last_error` containing the exception message, and `claim_token` null.
    - `oneFailingEventDoesNotHoldBackTheRestOfItsBatch`
      - Of three events, the middle one fails. Two are deleted and one is pending.
    - `nonRetryableFailureBuriesTheEvent`
      - The status becomes `dead`.
    - `eventPastMaxAttemptsIsBuriedWithoutPublishing`
      - As superuser, set `attempts = 10` on the row, then run `runOnce()`. The claim makes it 11, the publisher is not called, and the status is `dead`.
    - `eventIsRetriedAfterItsLeaseExpires`
      - Claim with `store.claim(1, 1s, randomToken)` and never complete. The row is invisible to an immediate `runOnce()`. After 1.2s, `runOnce()` publishes it and leaves `attempts = 2` before deleting it.
    - `inProcessPublisherReachesEveryHandler`
      - A nested `@SpringBootTest` with `publisher=in-process` and two `EventHandler` beans, one for `user.updated` and one for `*`. Both handlers receive the event.
    - `handlerCanUseTenantScope`
      - With `in-process` publishing, a handler for `user.updated` calls `tenantScope.call(event.tenantId(), ...)` and updates `identity.users.display_name`. After `runOnce()` the user row shows the change.
    - `mdcCarriesTheRequestIdIntoThePublisher`
      - The publisher captures `MDC.get("requestId")` and `MDC.get("eventId")` during `publish`. They equal the event's header and id. After `runOnce()`, the MDC no longer has `eventId`.
    - `relaySurvivesAStoreFailureAndKeepsPolling`
      - Start a relay with `enabled=true` and a store that throws on its first `claim` and then delegates.
      - An appended event is published within 3s (Awaitility).
    - `commitWakesTheRunningRelay`
      - `enabled=true` with `poll-interval=30s`. An event appended through `TenantScope` is published within 2s.
    - `stopFinishesTheBatchInFlight`
      - `enabled=true`. The publisher blocks on a latch.
      - Call `relay.stop(callback)` from another thread, then release the latch.
      - The callback runs only after the publish returns, and the row is deleted.

- [ ] **Step 3: Write `OutboxAutoConfigurationTest`.** Use `ApplicationContextRunner`.
  - `publisherIsChosenByConfiguration`
    - `log` gives `LoggingOutboxPublisher`, and `in-process` gives `InProcessOutboxPublisher`.
  - `unknownPublisherFailsStartup`
    - `kafka` with no bean fails, and the message contains `rbac.outbox.publisher='kafka'`.
  - `customPublisherAndRetryPolicyReplaceTheDefaults`
  - `nothingIsConfiguredWithoutASchema`
    - No `rbac.outbox.schema` means no `OutboxRelay` bean.

- [ ] **Step 4: Run the tests.** Run `./mvnw -q -pl libs/lib-outbox -am test` and expect FAIL.

- [ ] **Step 5: Implement the classes and autoconfig described above.**

- [ ] **Step 6: Run the module.** Run `./mvnw -q -pl libs/lib-outbox -am verify` and expect PASS.

- [ ] **Step 7: Commit.** Message: `feat(outbox): lease-based relay with pluggable publisher, retry policy and interceptors`.

---

### Task 6: lib-batch

**Files:**
- Modify: root `pom.xml`
  - add `<module>libs/lib-batch</module>` after `libs/lib-outbox`;
  - add a `dependencyManagement` entry.
- Create: `libs/lib-batch/pom.xml`
  - depends on `lib-core`, `lib-db` and `spring-boot-starter-batch-jdbc`;
  - test dependencies: `spring-boot-starter-test`, `spring-boot-starter-batch-jdbc-test` (check that it exists in Boot 4.1.2; otherwise use `spring-batch-test`), `test-support`, `awaitility`.
- Create in `libs/lib-batch/src/main/java/com/rbac_platform/lib/batch/`:
  - `BatchProperties.java`
  - `BatchAutoConfiguration.java`
  - `ScheduleSlots.java`
  - `ScheduledJobRunner.java`
  - `JobLaunchService.java`
  - `TenantPartitioner.java`
  - `TenantScopedTransactionManager.java`
  - `TenantStepListener.java`
  - `PartitionMaintenanceJob.java`
- Create: `libs/lib-batch/src/main/resources/rbac/batch-defaults.yaml` and the autoconfig `.imports` file.
- Test, in `libs/lib-batch/src/test/java/com/rbac_platform/lib/batch/`:
  - `BatchTestApplication.java`
  - `BatchRepositoryLocationTest.java`
  - `ScheduledJobRunnerTest.java`
  - `JobLaunchServiceTest.java`
  - `TenantPartitionTest.java`
  - `PartitionMaintenanceJobTest.java`
- Tests log in as `identity_app` with `rbac.batch.schema=identity`. `PartitionMaintenanceJobTest` logs in as `audit_app` with `rbac.batch.schema=audit`.

**Interfaces:**
- Consumes: the V13 objects (Task 2), `TenantScope`'s convention `set_config('app.tenant_id', ?, true)` and MDC key `TenantScope.MDC_KEY`.
- Produces:
  ```java
  @ConfigurationProperties("rbac.batch")
  public record BatchProperties(String schema, @DefaultValue("2") int schedulerPoolSize,
                                Map<String, JobSchedule> jobs) {
      public record JobSchedule(@DefaultValue("true") boolean enabled, String cron) {}
  }

  public class ScheduleSlots {
      public boolean claim(String jobName, Instant scheduledAt, String claimedBy); // insert ... on conflict do nothing
  }

  public class JobLaunchService {
      public JobExecution launch(String jobName, Map<String, String> identifyingParameters);
      public JobExecution restart(long executionId);
      public JobExecution recover(long executionId); // marks an abandoned STARTED execution so restart works
  }

  public class TenantPartitioner implements Partitioner {
      public static final String TENANT_ID_KEY = "tenantId";
      // One partition "tenant-<uuid>" per tenancy.tenants row with status 'active', ordered by id.
  }

  public class TenantScopedTransactionManager extends DataSourceTransactionManager {
      // Each new transaction runs set_config('app.tenant_id', <tenantId from the current step's
      // ExecutionContext>, true). Without a tenantId it throws IllegalStateException naming TenantPartitioner.
  }

  public class TenantStepListener implements StepExecutionListener { /* MDC tenantId for the step's duration */ }

  public final class PartitionMaintenanceJob {
      public static final String NAME = "partition-maintenance";
      public static Job create(JobRepository repository, PlatformTransactionManager tx, JdbcClient jdbc,
                               String maintenanceFunction); // e.g. "audit.maintain_partitions"
  }
  ```
- `batch-defaults.yaml`:
  - `spring.batch.jdbc.table-prefix: ${rbac.batch.schema}.batch_`
  - `spring.batch.jdbc.initialize-schema: never`
  - `spring.batch.job.enabled: false`
  - Check these names against the Boot 4.1.2 `spring-boot-batch` / `spring-boot-batch-jdbc` configuration metadata and use the real names.
- **Do not** use `@EnableBatchProcessing`. Keep Boot's batch auto-configuration.
- `ScheduledJobRunner` does its startup validation in `afterSingletonsInstantiated`. Each of these fails startup with an `IllegalStateException` that names `rbac.batch.jobs.<name>`:
  - a configured job with no `Job` bean whose `getName()` matches;
  - `enabled` with a missing or blank cron;
  - an invalid cron.
- After validation it schedules every enabled job on its own `ThreadPoolTaskScheduler` (thread prefix `batch-scheduler-`), with `CronTrigger(cron, ZoneOffset.UTC)`. On each fire:
  1. `scheduledAt = now` truncated to minutes.
  2. If `slots.claim(name, scheduledAt, claimedBy)` succeeds, start the job with the identifying parameter `scheduledAt` (an `Instant` or ISO string). Otherwise log DEBUG and return.
     - `claimedBy` is `<spring.application.name>@<hostname>:<pid>`.
  3. A FAILED execution is logged at ERROR with the job name and execution id.
  4. Any exception is logged at ERROR and swallowed, so later fires still happen.
- The schema name in `ScheduleSlots`' SQL comes from `rbac.batch.schema`, validated with the same pattern as the outbox schema.
- `PartitionMaintenanceJob.create`:
  - builds one tasklet step `partition-maintenance-step` that runs `select <maintenanceFunction>()`;
  - logs INFO `Partition maintenance created {} partition(s)`;
  - puts the count in the step's `ExecutionContext` under `created`.
  - `maintenanceFunction` must match `[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*`.

- [ ] **Step 1: Write `BatchRepositoryLocationTest`.**
  - `jobMetadataIsWrittenIntoTheServiceSchema`
    - Launch a trivial job, a no-op tasklet named `noop`, through `JobLaunchService`.
    - As superuser, `identity.batch_job_instance` has a row with `job_name = 'noop'`.
    - `audit.batch_job_instance` has none.

- [ ] **Step 2: Write `ScheduledJobRunnerTest`.**
  - `configuredJobRunsOnSchedule`
    - Cron `* * * * * *` (every second) for `noop`.
    - Within 3s, at least one COMPLETED execution exists. Each `scheduledAt` has a matching row in `identity.batch_schedule_slot`.
  - `disabledOrUnconfiguredJobsAreNotScheduled`
    - `enabled: false` gives no executions after 2s.
  - `secondInstanceSkipsAClaimedSlot`
    - Pre-insert a slot `(noop, T)`. Then the runner's fire method for `T` returns without starting a job. Expose the fire method package-private for the test as `fire(String jobName, Instant scheduledAt)`.
  - `failedRunIsNotRepeatedForTheSameSlot`
    - A job whose tasklet throws. `fire("boom", T)` twice gives exactly one FAILED execution.
  - `aFailingRunDoesNotStopLaterRuns`
    - Same job on cron `* * * * * *`. At least two FAILED executions with different `scheduledAt` within 3s.
  - `misconfiguredJobsFailStartup`
    - Uses `ApplicationContextRunner`.
    - An unknown job name, a missing cron and the cron `not a cron` each fail, and the message contains `rbac.batch.jobs.`.

- [ ] **Step 3: Write `JobLaunchServiceTest`.**
  - `launchRestartAndRecover`
    - A job that fails on its first run and succeeds afterwards: `launch` gives FAILED, `restart(executionId)` gives COMPLETED.
    - Simulate a crash: as superuser, set an execution's status to STARTED with `end_time` null. `restart` on it is refused. After `recover(id)`, `restart` succeeds.

- [ ] **Step 4: Write `TenantPartitionTest`.**
  - Set up: two active tenants, each with one user, inserted through `MigratedDatabase`.
  - `taskletPartitionsSeeOnlyTheirTenant`
    - A partitioned job: `TenantPartitioner` as manager; the worker step is a tasklet with `TenantScopedTransactionManager` and `TenantStepListener`.
    - The worker records `select string_agg(email::text, ',') from identity.users` and `MDC.get("tenantId")`.
    - Each partition saw only its own tenant's email, and its MDC equalled its tenant.
    - Partitions exist for at least those two tenants. Other tests' tenants may exist too.
  - `chunkTransactionsSeeOnlyTheirTenant`
    - The same, with a chunk step: `ListItemReader` of 3 items, chunk size 2, a writer that queries `identity.users`.
    - Every chunk saw only the partition's tenant.
  - `stepWithoutATenantFails`
    - A non-partitioned step using `TenantScopedTransactionManager` ends FAILED, with an exit message mentioning `TenantPartitioner`.

- [ ] **Step 5: Write `PartitionMaintenanceJobTest`.**
  - Log in as `audit_app`. Register the job with `@Bean Job partitionMaintenance(...) { return PartitionMaintenanceJob.create(..., "audit.maintain_partitions"); }`.
  - `recreatesAMissingFuturePartition`
    - Drop the month +3 partition as in Task 2.
    - `launch(PartitionMaintenanceJob.NAME, Map.of("run", uuid))` gives COMPLETED, `created = 1`, and the partition exists.
  - `rejectsAnUnsafeFunctionName`
    - `create(..., "audit.maintain_partitions(); drop")` throws `IllegalArgumentException`.

- [ ] **Step 6: Run the tests.** Run `./mvnw -q -pl libs/lib-batch -am test` and expect FAIL.

- [ ] **Step 7: Implement.**
  - Use the Batch 6 `JobOperator` for start, restart and recover. Check the 6.0.6 signatures in the jar.
  - `TenantScopedTransactionManager` can override `prepareTransactionalConnection(Connection, TransactionDefinition)` and read the tenant from `StepSynchronizationManager.getContext()`.

- [ ] **Step 8: Run the module.** Run `./mvnw -q -pl libs/lib-batch -am verify` and expect PASS.

- [ ] **Step 9: Commit.** Message: `feat(batch): lib-batch with per-service job repository, slot-guarded scheduling and tenant fan-out`.

---

### Task 7: Wire the services, close the parked security-test gap, document

**Files:**
- Modify:
  - `services/identity-service/pom.xml`: add `lib-outbox`.
  - `services/authz-service/pom.xml`: add `lib-outbox` and `lib-batch`.
  - `services/audit-service/pom.xml`: add `lib-batch`.
- Modify: `services/{identity,authz,audit}-service/src/main/resources/application.yaml`.
- Create:
  - `services/authz-service/src/main/java/com/rbac_platform/authz/batch/BatchJobsConfig.java`
  - `services/audit-service/src/main/java/com/rbac_platform/audit/batch/BatchJobsConfig.java`
  - Each declares `@Bean Job partitionMaintenance(JobRepository, PlatformTransactionManager, JdbcClient)` that calls `PartitionMaintenanceJob.create` with `authz.maintain_partitions` or `audit.maintain_partitions`.
- Modify tests:
  - `services/identity-service/src/test/java/com/rbac_platform/identity/IdentityServiceApplicationTest.java`
  - `services/authz-service/src/test/java/com/rbac_platform/authz/AuthzServiceApplicationTest.java`
  - `services/audit-service/src/test/java/com/rbac_platform/audit/AuditServiceApplicationTest.java`
- Modify: `README.md`, adding the module map entries and an "Async" section with the property tables.

**Interfaces:**
- Consumes: Tasks 4–6.
- `application.yaml` additions:
  - identity:
    ```yaml
    rbac:
      outbox:
        schema: identity
        publisher: log
    ```
  - authz: the same with `schema: authz`, plus:
    ```yaml
    spring.config.import:
      - classpath:rbac/batch-defaults.yaml   # appended to the existing list
    rbac:
      batch:
        schema: authz
        jobs:
          partition-maintenance:
            cron: "0 0 2 * * *"
    ```
  - audit: the batch block with `schema: audit` and the same job. No outbox.

- [ ] **Step 1: Add the service tests.**
  - identity: `outboxEventIsRelayedAndRemoved`
    - Inside `tenantScope.call`, append one message with `OutboxWriter`.
    - Within 3s (Awaitility), `identity.outbox` is empty, checked as superuser.
  - authz: `outboxEventIsRelayedAndRemoved` against `authz.outbox`, and `partitionMaintenanceJobIsScheduledAndRuns`.
    - The second test calls `JobLaunchService.launch("partition-maintenance", Map.of("run", uuid))` and expects COMPLETED.
    - It also checks that a `ScheduledJobRunner` bean exists.
  - audit: `partitionMaintenanceJobIsScheduledAndRuns`, as for authz.
  - authz and audit: add `noGeneratedLoginExistsAndAnyBasicCredentialIsRejected`, copied in shape from identity's test. It closes the gap parked as Ruling 8 of the multi-module plan. Also remove the unused imports in those two test classes.

- [ ] **Step 2: Run the tests.** Run `./mvnw -q -pl services/authz-service,services/audit-service,services/identity-service -am test` and expect FAIL.

- [ ] **Step 3: Wire the services.** Make the pom, `application.yaml` and `BatchJobsConfig` changes.

- [ ] **Step 4: Update `README.md`.** Cover:
  - the module map, adding `lib-outbox` and `lib-batch`;
  - the CRUD, outbox and batch split, in one table;
  - the `rbac.outbox.*`, `rbac.batch.*` and `rbac.events.unhandled` properties;
  - how to add a handler: one `EventHandler` bean;
  - how to add a job: a `Job` bean plus `rbac.batch.jobs.<name>.cron`;
  - that V11–V13 are not yet applied to Supabase.

- [ ] **Step 5: Run the full build.** Run `./mvnw -q verify` and expect every module green.

- [ ] **Step 6: Commit.** Message: `feat(services): wire outbox and batch into the services; partition maintenance runs daily`.
