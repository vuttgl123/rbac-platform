# Multi-Module Split and Per-Service Database Roles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single Spring Boot app into a Maven multi-module repo:
- shared libs `lib-core` and `lib-db`;
- `db-migrations` and `test-support`;
- three runnable service skeletons;
- migration V11, which gives each service its own database role limited to the schemas it owns.

**Architecture:**
- The root `pom.xml` becomes a packaging-`pom` parent.
- Existing code moves into modules unchanged, except for package names.
- The libs become Spring Boot auto-configurations, so services get them by adding a dependency.
- V11 adds `identity_app`, `authz_app` and `audit_app`:
  - their grants are copied from `rbac_app`, but only per owned schema;
  - existing RLS policies are widened to these roles;
  - two deliberate cross-schema exceptions are kept.

**Tech Stack:** Java 21, Spring Boot 4.1.2 (webmvc, jdbc, security, actuator), PostgreSQL 17 (zonky embedded for tests), Flyway, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-27-platform-foundation-modules-design.md`. The DB design it builds on is `docs/superpowers/specs/2026-10-26-rbac-platform-database-design.md`.

## Global Constraints

- Java 21, Spring Boot parent `4.1.2`, Maven wrapper `./mvnw` at the repo root. groupId `com.rbac_platform`, version `0.0.1-SNAPSHOT` for every module. Nothing is published.
- Module paths are exactly:
  - `libs/lib-core`, `libs/lib-db`
  - `db-migrations`, `test-support`
  - `services/identity-service`, `services/authz-service`, `services/audit-service`
- Dependency rules:
  - services depend on libs, never the reverse;
  - `lib-db` may depend on `lib-core`, and libs depend on nothing else of ours;
  - `test-support` is used only with `<scope>test</scope>`.
- **Never edit the content of `V1__…` to `V10__…`.** They are applied on Supabase. Moving them with `git mv` is fine.
- No test may need Docker. `./mvnw test` from the repo root runs every module's tests and all must pass.
- No service runs migrations. There is no Flyway dependency in libs or services.
- Logging stays exactly as configured in the current `src/main/resources/logback-spring.xml`, with one copy in `lib-core`. Surefire keeps setting `LOG_DIR=${project.build.directory}/test-logs`, configured once in the root pom.
- Commit with `git -c user.name=vuttgl123 -c user.email=phamtuanvu1401@gmail.com commit`. Message trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Use `git mv` for moved files.
- Comments in code are English and match the density of the existing files.

## Review Focus

1. **A service started with another service's database login.** It must refuse to start, not run with the wrong data rights. Test: Task 5, `refusesToStartWithAnotherServicesLogin`.
2. **Responses rejected by Spring Security (401).** They must still carry `X-Request-Id`, so a failed call can be traced. Test: Task 5, `rejectedRequestsStillCarryARequestId`.
3. **Anonymous `GET /actuator/health`.** It must answer `UP` but not reveal components such as db details. Test: Task 5, `healthIsPublicButHidesDetails`.
4. **`audit_app` writing events into a month partition created after V11** (by `util.ensure_monthly_partitions`). It must still work, because privileges are checked on the parent. Test: Task 4, `auditRoleWritesIntoPartitionsCreatedLater`.
5. **V11 must not leak anything to the Supabase API roles or to PUBLIC.** Test: Task 4, `newRolesAreNotReachableByApiRolesOrPublic`.

---

### Task 1: Multi-module skeleton and move existing code

Pure relocation. Behavior is unchanged except for the auto-configuration registration that the new package layout requires.

**Files:**
- Modify `pom.xml` into the parent:
  - `<packaging>pom</packaging>`, groupId `com.rbac_platform`, `<modules>` in the order `libs/lib-core`, `libs/lib-db`, `db-migrations`, `test-support`. Services are added in Task 5.
  - `dependencyManagement`: our own modules at `${project.version}`; `db-migrations` with `<type>test-jar</type>`; zonky `embedded-postgres` `2.2.2`; the existing zonky binaries BOM `17.11.0`.
  - Keep the surefire `LOG_DIR` config.
  - Drop all root `<dependencies>` and the batch, elasticsearch, data-redis-reactive and lombok entries.
  - The Flyway plugin moves to `db-migrations/pom.xml`.
- Create `db-migrations/pom.xml`:
  - jar module;
  - Flyway plugin config moved from the root, with location `filesystem:src/main/resources/db/migration`;
  - `maven-jar-plugin` goal `test-jar`;
  - test deps: `spring-boot-starter-test`, `postgresql`, `embedded-postgres`, `flyway-core`, `flyway-database-postgresql`.
- `git mv src/main/resources/db/migration/*` to `db-migrations/src/main/resources/db/migration/`.
- `git mv` to `db-migrations/src/test/java/com/rbac_platform/testsupport/`:
  - `src/test/java/com/rbac_platform/db/MigratedDatabase.java`
  - `src/test/java/com/rbac_platform/db/Sql.java`
  - Change their package to `com.rbac_platform.testsupport`.
- `git mv` the migration tests to `db-migrations/src/test/java/com/rbac_platform/db/`:
  - `AppsSchemaTest`, `AuditTest`, `AuthzModelTest`, `FoundationMigrationTest`, `IdentitySchemaTest`, `PartitionMaintenanceTest`, `RelationshipsTest`, `ScramVerifierTest`, `SupabaseExposureTest`, `TenancySchemaTest`, `TenantIsolationTest`, `TenantProvisioningTest`.
  - Fix their imports.
  - In `ScramVerifierTest`, `SCRIPT` becomes `Path.of("..", "scripts", "db", "scram_verifier.py")`, because surefire's working directory is the module directory.
- Create `test-support/pom.xml`:
  - compile deps: `db-migrations`, `db-migrations` test-jar, `postgresql`, `embedded-postgres`, `flyway-core`, `flyway-database-postgresql`, `spring-test`;
  - `git mv src/test/java/com/rbac_platform/AppDatabase.java` to `test-support/src/main/java/com/rbac_platform/testsupport/AppDatabase.java`.
- Create `libs/lib-core/pom.xml`:
  - deps: `spring-boot-starter-webmvc`;
  - test deps: `spring-boot-starter-test`.
- In `lib-core`:
  - `git mv src/main/resources/logback-spring.xml` to `libs/lib-core/src/main/resources/`;
  - `git mv` `RequestLoggingFilter` to `libs/lib-core/src/main/java/com/rbac_platform/lib/core/logging/`;
  - `git mv` `RequestLoggingFilterTest` and `LogFilesTest` to `libs/lib-core/src/test/java/com/rbac_platform/lib/core/logging/`.
- Create `libs/lib-db/pom.xml`:
  - deps: `lib-core`, `spring-boot-starter-jdbc`, `postgresql` (runtime);
  - test deps: `spring-boot-starter-test`, `test-support`.
- In `lib-db`:
  - `git mv` `DatabaseConnectionCheck` and `TenantScope` to `libs/lib-db/src/main/java/com/rbac_platform/lib/db/`;
  - `git mv` their tests to `libs/lib-db/src/test/java/com/rbac_platform/lib/db/`.
- Delete:
  - `src/main/java/com/rbac_platform/RbacPlatformApplication.java`
  - `src/main/resources/application.yaml`
  - `src/main/resources/application-prod.yaml`
  - `src/test/java/com/rbac_platform/RbacPlatformApplicationTests.java`
  - `src/test/java/com/rbac_platform/TestRbacPlatformApplication.java`
  - Afterwards the root `src/` must be gone.
- Create `libs/lib-core/src/main/java/com/rbac_platform/lib/core/CoreAutoConfiguration.java`, `libs/lib-db/src/main/java/com/rbac_platform/lib/db/DbAutoConfiguration.java`, and one `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` in each lib.
- Test apps:
  - `libs/lib-core/src/test/java/com/rbac_platform/lib/core/CoreTestApplication.java` (a `@SpringBootApplication` with no DataSource on the classpath);
  - `libs/lib-db/src/test/java/com/rbac_platform/lib/db/DbTestApplication.java`.

**Interfaces:**
- Produces, used by every later task:
  - `com.rbac_platform.testsupport.MigratedDatabase`, with the same public API as today: `get()`, `jdbcUrl()`, `dataSource()`, `inTx(String role, UUID tenantId, SqlWork<T>)`, `insertTenant()`, `insertUser(UUID, String)`.
  - `com.rbac_platform.testsupport.Sql`, with the same statics: `count`, `exec`, `one`, `sqlState`, `errorMessage`.
  - `com.rbac_platform.testsupport.AppDatabase`:
    - `static MigratedDatabase enableLogin(String role)` runs `ALTER ROLE <role> LOGIN` after validating the name against `[a-z_][a-z0-9_]*`;
    - `static void register(DynamicPropertyRegistry registry, String role)` registers `spring.datasource.url|username|password` and `rbac.db.expected-role=<role>`.
    - Today's no-arg `enableAppLogin()` and `register(registry)` are replaced. Callers pass `"rbac_app"`.
  - `com.rbac_platform.lib.core.logging.RequestLoggingFilter` keeps `HEADER = "X-Request-Id"` and `MDC_KEY = "requestId"`. It is a `@Bean` of `CoreAutoConfiguration`, not a `@Component`, and keeps `Ordered.HIGHEST_PRECEDENCE`.
  - `com.rbac_platform.lib.db.TenantScope` keeps `MDC_KEY = "tenantId"` and `<T> T call(UUID, Supplier<T>)`. `com.rbac_platform.lib.db.DatabaseConnectionCheck` keeps today's API. Both are `@Bean`s of `DbAutoConfiguration`, ordered after Boot's JdbcClient and TransactionTemplate auto-configurations.

- [ ] **Step 1:** Do the moves and edits listed above. `DbAutoConfiguration` and `CoreAutoConfiguration` hold `@Bean` methods for the moved classes. Remove `@Component` from those classes.
- [ ] **Step 2:** In `RequestLoggingFilterTest`, `LogFilesTest`, `TenantScopeTest` and `DatabaseConnectionCheckTest`, point `@SpringBootTest` at the module's test application.
  - The lib-core tests no longer register a database.
  - The lib-db tests call `AppDatabase.register(registry, "rbac_app")`.
  - `LogFilesTest` expects `[rbac-platform]` in each log line. The lib-core test application sets `spring.application.name=rbac-platform` through `@SpringBootTest(properties = …)` so that assertion keeps meaning something.
- [ ] **Step 3: Run** `./mvnw test`.
  - Expected: BUILD SUCCESS and `Tests run:` totals across modules summing to **183**. That is the 184 from before minus the deleted `contextLoads`.
  - Then `git status` shows no root `src/`.
- [ ] **Step 4: Commit** `refactor: split into lib-core, lib-db, db-migrations and test-support modules`.

### Task 2: lib-core — request id on error responses

**Files:**
- Modify `libs/lib-core/src/main/java/com/rbac_platform/lib/core/logging/RequestLoggingFilter.java`
- Create `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/RequestIdErrorAttributes.java`
- Modify `CoreAutoConfiguration`
- Test: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/error/RequestIdErrorAttributesTest.java`, plus a `@RestController` test fixture in the lib-core test sources

**Interfaces:**
- Produces:
  - `RequestLoggingFilter.REQUEST_ATTRIBUTE = "rbac.requestId"`. The filter stores the id there as well as in the MDC, because the MDC is cleared before Tomcat's error dispatch runs.
  - `RequestIdErrorAttributes extends DefaultErrorAttributes` adds `"requestId"` to Boot's error body. It is registered by `CoreAutoConfiguration`, ordered before Boot's `ErrorMvcAutoConfiguration` so that Boot's `@ConditionalOnMissingBean(ErrorAttributes.class)` backs off.
  - Note: the spec said `ProblemDetail`. It is ruled down to Boot's standard error body plus `requestId`, because there are no controllers yet. Record this in the report.

- [ ] **Step 1: Write the failing test.** `RequestIdErrorAttributesTest` is a `@SpringBootTest(webEnvironment = RANDOM_PORT)` on `CoreTestApplication`, with a test controller `GET /test/boom` that throws `IllegalStateException`.
  - `errorBodyCarriesTheRequestIdOfTheResponseHeader`: send `GET /test/boom` with `X-Request-Id: err-1` using `java.net.http.HttpClient`. Assert status 500, the response header `X-Request-Id` is `err-1`, and the JSON body contains `"requestId":"err-1"`.
  - `errorBodyCarriesAGeneratedIdWhenNoneWasSent`: the same request without the header. The body's `requestId` equals the response header, which matches the UUID pattern.
- [ ] **Step 2: Run** `./mvnw -pl libs/lib-core test -Dtest=RequestIdErrorAttributesTest`. Expected: FAIL, because the body has no `requestId`.
- [ ] **Step 3: Implement** the request attribute in the filter, `RequestIdErrorAttributes`, and its bean.
- [ ] **Step 4: Run** `./mvnw -pl libs/lib-core test`. Expected: all lib-core tests pass.
- [ ] **Step 5: Commit** `feat(lib-core): include the request id in error responses`.

### Task 3: lib-db — expected role check and shared datasource defaults

**Files:**
- Create `libs/lib-db/src/main/java/com/rbac_platform/lib/db/DbProperties.java`
- Modify `DatabaseConnectionCheck`, `DbAutoConfiguration`
- Create `libs/lib-db/src/main/resources/rbac/db-defaults.yaml`
- Test: `libs/lib-db/src/test/java/com/rbac_platform/lib/db/DatabaseConnectionCheckTest.java` (extend) and `ExpectedRoleStartupTest.java` (new)

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("rbac.db") public record DbProperties(String expectedRole)`.
  - `public DatabaseConnectionCheck(JdbcClient jdbc, String expectedRole)` replaces the one-argument constructor.
  - `verify()` still returns `ConnectionInfo(user, database, serverVersion)`. It now throws `IllegalStateException` in three cases:
    - the role bypasses RLS (existing behavior);
    - `current_user` is not `expectedRole`. The message contains `"expects database role '<expected>'"` and the actual role;
    - `expectedRole` is blank. In that case the constructor throws, and the message contains `"rbac.db.expected-role"`.
  - `classpath:rbac/db-defaults.yaml` holds the settings shared by every service:
    - `spring.datasource.url: ${DB_URL}`
    - `spring.datasource.hikari.pool-name: ${spring.application.name}-db`
    - `maximum-pool-size: ${DB_POOL_SIZE:5}`
    - `connection-timeout: 10000`
    - `data-source-properties.ApplicationName: ${spring.application.name}`
    - Its header comment says services import it with `spring.config.import`, and that username, password and `rbac.db.expected-role` come from the service.

- [ ] **Step 1: Write the failing tests.**
  - Update the existing tests to the two-argument constructor. `acceptsAppRoleAndReportsWhereItConnected` passes `"rbac_app"`. `refusesRolesThatBypassRowLevelSecurity` passes `"postgres"`.
  - `refusesAConnectionAsAnotherRole`: connect as `rbac_app` with `expectedRole "identity_app"`. Assert `IllegalStateException` with a message containing `expects database role 'identity_app'` and `rbac_app`.
  - `requiresAnExpectedRole`: `new DatabaseConnectionCheck(jdbc, " ")` throws `IllegalStateException` containing `rbac.db.expected-role`.
  - `ExpectedRoleStartupTest.applicationDoesNotStartWithTheWrongRole`: `new SpringApplicationBuilder(DbTestApplication.class).web(WebApplicationType.NONE)`, with properties for datasource url (`MigratedDatabase.jdbcUrl()`), username `rbac_app`, and `rbac.db.expected-role=authz_app`. `.run()` throws, and the root cause message contains `expects database role 'authz_app'`.
  - `ExpectedRoleStartupTest.applicationDoesNotStartWithoutAnExpectedRole`: the same, without the property. The root cause contains `rbac.db.expected-role`.
- [ ] **Step 2: Run** `./mvnw -pl libs/lib-db -am test -Dtest='DatabaseConnectionCheckTest,ExpectedRoleStartupTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL, as compilation fails on the new constructor.
- [ ] **Step 3: Implement.** Enable `DbProperties` in `DbAutoConfiguration` and pass `expectedRole` into the check. The check compares in its existing query: `current_user`, against `expectedRole`.
- [ ] **Step 4: Run** `./mvnw test`. Expected: BUILD SUCCESS. Every module is green.
- [ ] **Step 5: Commit** `feat(lib-db): refuse to start unless connected as the service's own role`.

### Task 4: V11 — per-service database roles

**Files:**
- Create `db-migrations/src/main/resources/db/migration/V11__service_roles.sql`
- Modify `scripts/db/bootstrap_login_roles.sql`, `.env.example`
- Test: `db-migrations/src/test/java/com/rbac_platform/db/ServiceRolesTest.java`

**Interfaces:**
- Consumes `MigratedDatabase.inTx(role, tenantId, work)` and the `Sql` helpers (Task 1). Existing helpers for relations and tuples are in `RelationshipsTest`; reuse their SQL shapes.
- Produces the roles `identity_app`, `authz_app` and `audit_app`. They are `NOLOGIN` and have `search_path = "$user", extensions`, as V1 sets for `rbac_app`.

V11 contents, in order. Part 1 runs as the migration user, part 2 after `SET LOCAL ROLE rbac_owner`. The header comment states both.
1. Create the three roles if missing (same `DO` pattern as V1), then `ALTER ROLE … SET search_path`.
2. `SET LOCAL ROLE rbac_owner`.
3. Every role gets:
   - `USAGE` on `util` and `tenancy`;
   - `EXECUTE` on `util.current_tenant_id()` and `util.uuid_v7()`;
   - `SELECT` on `tenancy.tenants`.
4. Privilege copy. Copy every privilege `rbac_app` holds on objects in the owned schemas:
   - `identity_app` owns `tenancy`, `identity` and `apps`;
   - `authz_app` owns `authz`;
   - `audit_app` owns `audit`.
   - Copy table privileges (`pg_class.relacl`, relkind `r`/`p`), column privileges (`pg_attribute.attacl`, e.g. `UPDATE (name, settings)` on `tenants`), function `EXECUTE` (`pg_proc.proacl`) and schema `USAGE`.
   - Use `aclexplode`. A plain list of GRANTs is also acceptable if the test below passes.
5. Exception 1:
   - `authz_app` gets `USAGE` on `identity` and `apps`;
   - plus `SELECT (tenant_id, id)` only, on `identity.users`, `identity.groups` and `apps.applications`.
6. Exception 2: `CREATE OR REPLACE FUNCTION authz.delete_entity_relationships()`.
   - The body is identical to V7.
   - Add `SECURITY DEFINER`.
   - Keep `SET search_path = pg_catalog, extensions`.
   - `REVOKE EXECUTE … FROM PUBLIC`.
   - Add a comment giving the AFTER-DELETE/`OLD.tenant_id` safety argument.
7. For every policy where `'rbac_app' = ANY(pg_policies.roles)`, run `ALTER POLICY … TO` with its current roles plus the three new ones.
8. `CREATE OR REPLACE FUNCTION util.enable_tenant_rls(regclass)`.
   - Identical to V1, except `tenant_isolation` is `TO rbac_app, identity_app, authz_app, audit_app`.
   - It still grants table privileges only to `rbac_app` and `rbac_admin`.

- [ ] **Step 1: Write the failing test** `ServiceRolesTest`. Create a tenant `a` and a tenant `b` with `insertTenant()`. `42501` = insufficient_privilege.
  - `serviceRolesExistWithoutLogin`: the three roles exist with `rolcanlogin = false`. `pg_db_role_setting` has their search_path.
  - `identityRoleManagesItsSchemasWithinItsTenant`: as `identity_app` in tenant `a`:
    - insert a user in `identity.users`; select sees it; a user of `b` is invisible;
    - `update tenancy.tenants set name = 'x' where id = a` succeeds; `set status = 'suspended'` gives 42501;
    - `select identity.find_user_by_email(...)` works.
  - `identityRoleCannotTouchAuthzOrAudit`: as `identity_app`, `select` on `authz.relations`, `insert` into `authz.relationships`, and `select` on `audit.events` each give 42501.
  - `authzRoleSeesOnlyIdsOfIdentityEntities`: as `authz_app` in tenant `a`:
    - `select tenant_id, id from identity.users` works (RLS-limited to `a`);
    - `select email from identity.users`, `select * from identity.password_credentials`, `insert into identity.users` and `select * from apps.client_secrets` each give 42501.
  - `authzRoleWritesRelationshipsButStillValidatesEntities`: as `authz_app`, insert a tuple whose builtin `user` subject exists → succeeds. With a random UUID subject → fails with a message containing `builtin_entity_missing`.
  - `deletingAUserAsIdentityRoleRemovesItsRelationships`:
    - set up a tuple with the user as subject (superuser);
    - `delete from identity.users` as `identity_app` in tenant `a` succeeds;
    - the tuple is gone;
    - `authz.relationship_changes` has the delete entry.
  - `auditRoleWritesAndReadsOnlyAuditEvents`: as `audit_app` in `a`:
    - insert into `audit.events` and select it back;
    - `update audit.events` and `delete from audit.events` give 42501;
    - `select` on `identity.users` and `authz.relationships` gives 42501.
  - `auditRoleWritesIntoPartitionsCreatedLater`:
    - as superuser, `select util.ensure_monthly_partitions('audit.events'::regclass, 6)`;
    - as `audit_app`, insert an event with `occurred_at = now() + interval '5 months'` → succeeds.
  - `allServiceRolesReadTheTenantDirectory`: each role selects its tenant row from `tenancy.tenants` and calls `util.uuid_v7()`.
  - `tablesProtectedLaterIncludeServiceRoles`:
    - as `rbac_owner`, create `util.v11_probe (tenant_id uuid not null, id int)`;
    - `select util.enable_tenant_rls('util.v11_probe')`;
    - `pg_policies.roles` for `tenant_isolation` contains all four app roles;
    - drop the table.
  - `newRolesAreNotReachableByApiRolesOrPublic`:
    - for `anon`, `authenticated` and `service_role`, `pg_has_role(api, svc, 'MEMBER')` is false for each service role;
    - `has_function_privilege('public', 'authz.delete_entity_relationships()', 'EXECUTE')` is false;
    - `prosecdef` is true for that function.
- [ ] **Step 2: Run** `./mvnw -pl db-migrations test -Dtest=ServiceRolesTest`. Expected: FAIL, because `role "identity_app" does not exist`.
- [ ] **Step 3: Write** `V11__service_roles.sql` as specified above.
- [ ] **Step 4: Extend** `scripts/db/bootstrap_login_roles.sql` with `ALTER ROLE identity_app|authz_app|audit_app WITH LOGIN PASSWORD :'identity_verifier'|:'authz_verifier'|:'audit_verifier'`. Update its usage comment.
- [ ] **Step 5: Set** `.env.example` to exactly the following, in this order. Lines starting with `#` are comments.
  - `# JDBC Session pooler URL; Flyway uses it as DB_USER, every service as its own role`
  - `DB_URL=`, `PSQL_URL=`, `DB_USER=`, `DB_PASSWORD=`, `PLATFORM_OPERATOR_EMAIL=`
  - `# Per-service database logins (through the Supabase pooler the user is <role>.<project-ref>)`
  - `IDENTITY_DB_USER=`, `IDENTITY_DB_PASSWORD=`, `AUTHZ_DB_USER=`, `AUTHZ_DB_PASSWORD=`, `AUDIT_DB_USER=`, `AUDIT_DB_PASSWORD=`
  - `# Passwords set by scripts/db/bootstrap_login_roles.sql for the shared roles`
  - `DB_APP_PASSWORD=`, `DB_ADMIN_PASSWORD=`
- [ ] **Step 6: Run** `./mvnw test`. Expected: BUILD SUCCESS. `ServiceRolesTest` passes and every pre-existing test still passes.
- [ ] **Step 7: Commit** `feat(db): V11 per-service database roles limited to their own schemas`.

### Task 5: Three service skeletons

The three services are the same shape. Build them in one pass.

**Files**, for each `<svc>` in `identity`, `authz`, `audit`. `<Svc>` is `Identity`, `Authz` or `Audit`; `<pkg>` is `identity`, `authz` or `audit`.
- `services/<svc>-service/pom.xml`:
  - deps: `lib-core`, `lib-db`, `spring-boot-starter-webmvc`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`;
  - test deps: `spring-boot-starter-test`, `test-support`;
  - plugin: `spring-boot-maven-plugin`.
- `src/main/java/com/rbac_platform/<pkg>/<Svc>ServiceApplication.java` (`@SpringBootApplication`).
- `src/main/java/com/rbac_platform/<pkg>/SecurityConfig.java`: a `SecurityFilterChain` that permits `GET /actuator/health` and `/error`, and requires authentication for everything else. Use HTTP Basic, and do not create a session.
- `src/main/resources/application.yaml`, exactly:
  ```yaml
  spring:
    application:
      name: <svc>-service
    config:
      import:
        - optional:file:.env[.properties]
        - optional:file:../../.env[.properties]
        - classpath:rbac/db-defaults.yaml
    datasource:
      username: ${<SVC>_DB_USER:<svc>_app}
      password: ${<SVC>_DB_PASSWORD}
  server:
    port: <8081 identity | 8082 authz | 8083 audit>
  rbac:
    db:
      expected-role: <svc>_app
  ```
- `src/test/java/com/rbac_platform/<pkg>/Test<Svc>ServiceApplication.java`: the dev runner, the same pattern as today's `TestRbacPlatformApplication`, but with `AppDatabase.enableLogin("<svc>_app")` and username `<svc>_app`.
- `src/test/java/com/rbac_platform/<pkg>/<Svc>ServiceApplicationTest.java`.
- Root `pom.xml`: add the three modules.

- [ ] **Step 1: Write the failing tests.** Each `<Svc>ServiceApplicationTest` is a `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `AppDatabase.register(registry, "<svc>_app")`.
  - `connectsAsItsOwnRole`: `jdbc.sql("select current_user")` equals `<svc>_app`.
  - `healthIsPublicButHidesDetails`: anonymous `GET /actuator/health` → 200, body contains `"status":"UP"`, and the body contains neither `components` nor `db`.
  - `rejectedRequestsStillCarryARequestId`: anonymous `GET /anything` → 401, with a response header `X-Request-Id` matching the UUID pattern.
  - `refusesToStartWithAnotherServicesLogin`, only in `IdentityServiceApplicationTest`: `new SpringApplicationBuilder(IdentityServiceApplication.class)` with `server.port=0`, datasource url from `MigratedDatabase.jdbcUrl()`, username `authz_app` (after `AppDatabase.enableLogin("authz_app")`), and `spring.datasource.password=unused`. Without the password the run would fail on the unresolved `${IDENTITY_DB_PASSWORD}` instead of on the role check. `.run()` throws, and the root cause contains `expects database role 'identity_app'`.
- [ ] **Step 2: Run** `./mvnw test`. Expected: FAIL, because the service classes do not exist.
- [ ] **Step 3: Implement** the files listed above.
- [ ] **Step 4: Run** `./mvnw test`. Expected: BUILD SUCCESS. Every module is green.
- [ ] **Step 5: Verify the dev runner by hand.**
  - Run `./mvnw -q install -DskipTests`.
  - Then, for identity: `timeout 150 ./mvnw -pl services/identity-service spring-boot:test-run -Dspring-boot.run.arguments=--server.port=18091`.
  - The log must show `Connected to database 'postgres' as 'identity_app'` and `Started IdentityServiceApplication`.
  - `curl -s localhost:18091/actuator/health` returns `{"status":"UP"}`.
  - Stop it with `pkill -f "[T]estIdentityServiceApplication"`. Put the output in the report.
- [ ] **Step 6: Commit** `feat: add identity, authz and audit service skeletons on their own database roles`.
