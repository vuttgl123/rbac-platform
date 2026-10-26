# RBAC Platform Database — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dựng toàn bộ schema PostgreSQL của nền tảng định danh + phân quyền multi-tenant (tenancy, identity, apps, authz kiểu Zanzibar có điều kiện và luật cấm, audit) bằng migration Flyway, có test tự động, rồi áp lên Supabase.

**Architecture:** Mỗi schema là một (hoặc vài) file migration Flyway SQL thuần. Mọi đối tượng thuộc role `rbac_owner`; ứng dụng dùng `rbac_app` bị RLS khóa theo `app.tenant_id`; người vận hành dùng `rbac_admin`. Test chạy JUnit 5 trên Postgres nhúng (zonky, không cần Docker), migrate một lần cho cả bộ test, rồi kiểm tra ràng buộc bằng JDBC thuần với từng role.

**Tech Stack:** PostgreSQL 17 (Supabase) · Flyway 12.4.0 (do Spring Boot 4.1.2 quản lý) · `io.zonky.test:embedded-postgres:2.2.2` + binaries BOM `17.11.0` · JUnit 5 + AssertJ · Java 21 · Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-10-26-rbac-platform-database-design.md` — đọc kèm; plan này chỉ ghi các quyết định thực thi, mọi cột/bảng lấy từ spec.

## Global Constraints

- Tất cả thay đổi làm trên nhánh `feat/database-schema` tách từ `docs/database-design`.
- Không có Docker, không có `psql` trong môi trường; host `db.<ref>.supabase.co` chỉ IPv6 và **không tới được** từ WSL → mọi kết nối Supabase đi qua **Session pooler** (`aws-*.pooler.supabase.com:5432`, user `postgres.wyfuvwrpkqczdgjnzvog`).
- Không bao giờ commit mật khẩu/connection string. Biến môi trường: `DB_URL` (JDBC), `PSQL_URL` (dạng libpq `postgresql://user:pass@host:5432/postgres?sslmode=require`, cùng đích với `DB_URL`), `DB_USER`, `DB_PASSWORD`, `PLATFORM_OPERATOR_EMAIL`, `DB_APP_PASSWORD`, `DB_ADMIN_PASSWORD`; nạp bằng `set -a; source .env; set +a`.
- Migration nằm ở `src/main/resources/db/migration`, tên `V<n>__<snake>.sql`. Lịch sử Flyway ở schema riêng `flyway` (`defaultSchema=flyway`, `createSchemas=true`) — **không** đưa schema của nền tảng vào `schemas` của Flyway.
- Từ V2 trở đi, dòng đầu mỗi file migration là `SET LOCAL ROLE rbac_owner;` để mọi đối tượng thuộc `rbac_owner`. Tham chiếu đối tượng luôn kèm tên schema (`identity.users`).
- Mọi function khai báo `SET search_path = pg_catalog, extensions`. Function `SECURITY DEFINER` luôn `REVOKE EXECUTE ... FROM PUBLIC` rồi grant đích danh.
- Không tạo gì trong `public`. Không dùng enum Postgres — `text` + `CHECK`. Thời gian là `timestamptz`. `email`/`username` là `citext`.
- ID: `uuid DEFAULT util.uuid_v7()` (dùng hàm riêng cho cả PG17 lẫn PG18); bảng mô hình authz dùng `int GENERATED ALWAYS AS IDENTITY`; `authz.relationships.id` là `bigint GENERATED ALWAYS AS IDENTITY`; changelog dùng `bigserial`.
- Khóa chính bảng thuộc tenant: `(tenant_id, id)`; FK giữa bảng con ghép `(tenant_id, x_id)`.
- Mã lỗi do trigger tự raise: `RAISE EXCEPTION '<token>: <chi tiết>' USING ERRCODE = 'check_violation'` — test so khớp `<token>`.
- Không sửa `RbacPlatformApplicationTests` (context Spring cần Redis/ES, ngoài phạm vi). Luôn chạy test DB bằng `-Dtest='com.rbac_platform.db.*Test'`.

## Review Focus

1. `app.tenant_id` bị đặt thành chuỗi không phải UUID → câu truy vấn phải **lỗi**, không trả dòng nào (Task 2, `malformedTenantIdFailsClosed`).
2. Connection được pool tái sử dụng: ngữ cảnh đặt bằng `set_config(..., true)` ở transaction trước **không được** rò sang transaction sau (Task 2, `tenantContextDoesNotLeakAcrossTransactions`).
3. Job tạo partition chạy trễ, đã có dòng rơi vào partition `DEFAULT` cho tháng sắp tạo → không lỗi, dòng được chuyển sang partition mới (Task 6, `movesRowsOutOfDefaultBeforeAttaching`).
4. Email khác hoa/thường (`An@Example.com` vs `an@example.com`) trong cùng tenant → bị từ chối là trùng (Task 3, `emailUniquePerTenantIgnoringCase`).
5. Nhóm lồng nhau tạo vòng (`a` chứa `b`, `b` chứa `a`) → database chấp nhận lưu, và xóa nhóm `a` dọn sạch cả hai chiều mà không lỗi đệ quy (Task 7, `groupCycleIsStoredAndCascadeDeletes`).

---

### Task 1: Test harness, build và migration nền (V1)

**Files:**
- Modify: `pom.xml` (dependencies test + plugin Flyway)
- Modify: `.gitignore` (thêm `.env`)
- Create: `.env.example`
- Create: `src/main/resources/db/migration/V1__foundation.sql`
- Create: `src/test/java/com/rbac_platform/db/MigratedDatabase.java`
- Create: `src/test/java/com/rbac_platform/db/Sql.java`
- Test: `src/test/java/com/rbac_platform/db/FoundationMigrationTest.java`

**Interfaces:**
- Produces (SQL):
  - Schemas `tenancy, identity, apps, authz, audit, util` (`AUTHORIZATION rbac_owner`), `extensions` (IF NOT EXISTS); extensions `citext`, `pgcrypto` `WITH SCHEMA extensions`.
  - Roles NOLOGIN `rbac_owner, rbac_app, rbac_admin` (tạo nếu chưa có); `GRANT rbac_owner TO CURRENT_USER`; `GRANT CREATE ON DATABASE <current_database()> TO rbac_owner`; `USAGE` trên mọi schema trên + `extensions` cho `rbac_app, rbac_admin, rbac_owner`.
  - Nếu role `anon`/`authenticated` tồn tại: `REVOKE ALL` trên các schema của nền tảng. `ALTER DEFAULT PRIVILEGES FOR ROLE rbac_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`.
  - `util.uuid_v7() RETURNS uuid` (VOLATILE)
  - `util.current_tenant_id() RETURNS uuid` (STABLE) = `nullif(current_setting('app.tenant_id', true), '')::uuid`; EXECUTE cho `rbac_app, rbac_admin`.
  - `util.set_updated_at() RETURNS trigger`
  - `util.enable_tenant_rls(p_table regclass) RETURNS void`: `ENABLE` + `FORCE ROW LEVEL SECURITY`; policy `tenant_isolation` FOR ALL TO `rbac_app` USING/WITH CHECK `tenant_id = (SELECT util.current_tenant_id())`; policy `admin_all` TO `rbac_admin` USING/WITH CHECK `true`; policy `owner_all` TO `rbac_owner` USING/WITH CHECK `true`; `GRANT SELECT, INSERT, UPDATE, DELETE` cho `rbac_app, rbac_admin`.
- Produces (Java, test scope):
  ```java
  public final class MigratedDatabase {
    public static MigratedDatabase get();                       // singleton: khởi động PG nhúng, tạo role anon/authenticated (NOLOGIN), chạy Flyway
    public DataSource dataSource();                             // superuser
    public <T> T inTx(String role, UUID tenantId, SqlWork<T> work); // 1 transaction, commit; role null = superuser; SET LOCAL ROLE + set_config('app.tenant_id', id, true) khi khác null
    public UUID insertTenant();                                 // superuser insert tenancy.tenants, slug 't-' + 8 ký tự ngẫu nhiên
    public UUID insertUser(UUID tenantId, String email);        // superuser insert identity.users status 'active'
    @FunctionalInterface public interface SqlWork<T> { T run(Connection c) throws SQLException; }
  }
  public final class Sql {
    public static long count(Connection c, String sql, Object... args);
    public static int exec(Connection c, String sql, Object... args);
    public static <T> T one(Connection c, Class<T> type, String sql, Object... args);
    public static String sqlState(ThrowingRunnable r);          // chạy, bắt SQLException, trả SQLState; fail nếu không ném
    public static String errorMessage(ThrowingRunnable r);      // như trên, trả message
    @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }
  }
  ```
  `insertTenant`/`insertUser` chỉ dùng được sau Task 2/3 — viết sẵn ở đây.

- [ ] **Step 1: Tạo nhánh và cập nhật build**

```bash
git checkout -b feat/database-schema docs/database-design
```
`pom.xml`: thêm `<dependencyManagement>` import `io.zonky.test.postgres:embedded-postgres-binaries-bom:17.11.0`; dependencies scope `test`: `io.zonky.test:embedded-postgres:2.2.2`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (không ghi version — Boot quản lý). Plugin `org.flywaydb:flyway-maven-plugin:${flyway.version}` với `url/user/password = ${env.DB_URL}/${env.DB_USER}/${env.DB_PASSWORD}`, `locations=filesystem:src/main/resources/db/migration`, `defaultSchema=flyway`, `createSchemas=true`, placeholder `platform_operator_email=${env.PLATFORM_OPERATOR_EMAIL}`, plugin dependencies `flyway-database-postgresql` + `org.postgresql:postgresql`. `.env.example` liệt kê 6 biến ở Global Constraints với giá trị rỗng.

- [ ] **Step 2: Viết test thất bại `FoundationMigrationTest`**

```java
@Test void schemasAreOwnedByRbacOwner() {
  // với mỗi s in tenancy, identity, apps, authz, audit, util:
  assertThat(one(c, String.class, "select nspowner::regrole::text from pg_namespace where nspname = ?", s)).isEqualTo("rbac_owner");
}
@Test void uuidV7HasVersionVariantAndTimeOrder() {
  UUID a = one(c, UUID.class, "select util.uuid_v7()"); Thread.sleep(2); UUID b = one(c, UUID.class, "select util.uuid_v7()");
  assertThat(a.version()).isEqualTo(7); assertThat(a.variant()).isEqualTo(2);
  assertThat(a.toString().compareTo(b.toString())).isNegative();
  long ms = a.getMostSignificantBits() >>> 16; assertThat(ms).isCloseTo(System.currentTimeMillis(), within(5000L));
}
@Test void currentTenantIdIsNullWithoutContext() {
  assertThat(db.inTx("rbac_app", null, c -> one(c, UUID.class, "select util.current_tenant_id()"))).isNull();
}
@Test void anonHasNoAccessToPlatformSchemas() {
  // với mỗi schema nền tảng: has_schema_privilege('anon', s, 'USAGE') = false; tương tự 'authenticated'
}
@Test void extensionsAreInstalled() { // citext và pgcrypto có trong pg_extension
}
```

- [ ] **Step 3: Chạy test, xác nhận thất bại**

Run: `./mvnw -q test -Dtest='com.rbac_platform.db.FoundationMigrationTest'`
Expected: FAIL (không có migration / `MigratedDatabase` chưa có).

- [ ] **Step 4: Viết `MigratedDatabase`, `Sql`, `V1__foundation.sql`**

`MigratedDatabase.get()`: `EmbeddedPostgres.start()`, chạy `CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN;` (mô phỏng Supabase), rồi `Flyway.configure().dataSource(...).locations("classpath:db/migration").defaultSchema("flyway").createSchemas(true).placeholders(Map.of("platform_operator_email","operator@platform.test")).load().migrate()`. `util.uuid_v7()`: 48 bit đầu = epoch ms, nibble version = 7, 2 bit variant = `10`, phần còn lại từ `extensions.gen_random_bytes`.

- [ ] **Step 5: Chạy test, xác nhận pass**

Run: `./mvnw -q test -Dtest='com.rbac_platform.db.FoundationMigrationTest'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add pom.xml .gitignore .env.example src/main/resources/db/migration/V1__foundation.sql src/test/java/com/rbac_platform/db
git commit -m "feat(db): add Flyway foundation migration and embedded Postgres test harness"
```

---

### Task 2: Tenancy (V2) và kiểm tra cô lập tenant tổng quát

**Files:**
- Create: `src/main/resources/db/migration/V2__tenancy.sql`
- Test: `src/test/java/com/rbac_platform/db/TenancySchemaTest.java`
- Test: `src/test/java/com/rbac_platform/db/TenantIsolationTest.java`

**Interfaces:**
- Consumes: `util.enable_tenant_rls`, `util.uuid_v7`, `util.set_updated_at`, `MigratedDatabase`, `Sql` (Task 1).
- Produces:
  - `tenancy.tenants` theo spec §5.1; `slug` CHECK `^[a-z0-9][a-z0-9-]{1,62}$`, unique; `status` CHECK `active|suspended|deleted` default `active`; `settings jsonb NOT NULL DEFAULT '{}'`; `authz_model_revision bigint NOT NULL DEFAULT 0`. RLS riêng (không dùng `enable_tenant_rls`): ENABLE + FORCE; `rbac_app` SELECT `true`, UPDATE USING/WITH CHECK `id = (SELECT util.current_tenant_id())`; `rbac_admin` và `rbac_owner` ALL `true`. Grant: `rbac_app` SELECT, UPDATE; `rbac_admin` SELECT, INSERT, UPDATE, DELETE.
  - `tenancy.tenant_domains (tenant_id, id, domain citext UNIQUE, verified_at, verification_token_hash, created_at)` + `enable_tenant_rls`.
  - `tenancy.resolve_domain(p_domain text) RETURNS uuid` — SECURITY DEFINER, chỉ trả tenant khi `verified_at IS NOT NULL`, EXECUTE cho `rbac_app`.

- [ ] **Step 1: Viết test thất bại**

```java
// TenancySchemaTest
@Test void slugMustBeLowercaseKebab()       { assertThat(sqlState(() -> insert tenant slug 'Bad Slug')).isEqualTo("23514"); }
@Test void slugIsUnique()                   { /* chèn cùng slug 2 lần */ assertThat(state).isEqualTo("23505"); }
@Test void appRoleReadsAllTenantsUpdatesOnlyOwn() {
  UUID a = db.insertTenant(), b = db.insertTenant();
  db.inTx("rbac_app", a, c -> { assertThat(exec(c, "update tenancy.tenants set name='x' where id=?", b)).isZero();
                                assertThat(exec(c, "update tenancy.tenants set name='x' where id=?", a)).isEqualTo(1);
                                assertThat(count(c, "select count(*) from tenancy.tenants where id in (?,?)", a, b)).isEqualTo(2); return null; });
}
@Test void appRoleCannotInsertTenant()      { assertThat(state as rbac_app).isEqualTo("42501"); }
@Test void resolveDomainOnlyReturnsVerifiedDomains() {
  // tenant a: 'verified-<rand>.com' verified_at=now(); 'pending-<rand>.com' verified_at null
  // as rbac_app, tenantId null: resolve_domain(verified) = a; resolve_domain(pending) is null
}
// TenantIsolationTest
@Test void everyTenantScopedTableForcesRlsWithStandardPolicy() {
  // mọi bảng thường/partitioned (relkind in 'r','p', không phải partition con) trong tenancy, identity, apps, authz, audit có cột tenant_id:
  // relrowsecurity && relforcerowsecurity; pg_policies có policy cho rbac_app mà qual và with_check đều chứa 'current_tenant_id()'
}
@Test void partitionChildrenAreNotGrantedToAppRole() {
  // mọi relispartition trong các schema trên: has_table_privilege('rbac_app', oid, 'SELECT') = false
}
@Test void noContextMeansNoRows() {
  UUID a = db.insertTenant(); /* chèn tenant_domains cho a */
  assertThat(db.inTx("rbac_app", null, c -> count(c, "select count(*) from tenancy.tenant_domains"))).isZero();
}
@Test void malformedTenantIdFailsClosed() {
  // chèn trước ≥1 dòng tenant_domains (bảng rỗng thì initplan của policy có thể không chạy);
  // as rbac_app: select set_config('app.tenant_id','not-a-uuid',true); select from tenancy.tenant_domains → SQLState "22P02"
}
@Test void tenantContextDoesNotLeakAcrossTransactions() {
  // cùng một Connection: tx1 set_config(...,a,true) + commit; tx2 SET LOCAL ROLE rbac_app → util.current_tenant_id() is null
}
```

- [ ] **Step 2: Chạy, xác nhận thất bại**

Run: `./mvnw -q test -Dtest='com.rbac_platform.db.Tena*Test'`
Expected: FAIL (bảng chưa có).

- [ ] **Step 3: Viết `V2__tenancy.sql`** theo Interfaces; trigger `set_updated_at` cho `tenants`.

- [ ] **Step 4: Chạy, xác nhận pass**

Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'`
Expected: PASS toàn bộ.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V2__tenancy.sql src/test/java/com/rbac_platform/db/Tena*Test.java
git commit -m "feat(db): add tenancy schema with tenant isolation tests"
```

---

### Task 3: Identity (V3)

**Files:**
- Create: `src/main/resources/db/migration/V3__identity.sql`
- Test: `src/test/java/com/rbac_platform/db/IdentitySchemaTest.java`

**Interfaces:**
- Consumes: `tenancy.tenants`, `util.*` (Task 1–2).
- Produces: 11 bảng của spec §5.2 với đúng cột, CHECK và unique ở đó: `identity.users, password_credentials, password_history, mfa_factors, mfa_recovery_codes, identity_providers, federated_identities, groups, sessions, refresh_tokens, one_time_tokens`. Mỗi bảng `enable_tenant_rls`. Bảng con của user FK `(tenant_id, user_id) → identity.users(tenant_id, id) ON DELETE CASCADE`; `sessions.application_id`, `refresh_tokens.application_id` tạo cột **chưa có FK** (Task 4 thêm). FK tới `tenancy.tenants(id)` là `ON DELETE RESTRICT`. Trigger `set_updated_at` cho bảng có `updated_at`. Unique `(tenant_id, email) WHERE deleted_at IS NULL` (tương tự `username`, `phone`), `(tenant_id, external_id) WHERE external_id IS NOT NULL`.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void emailUniquePerTenantIgnoringCase()  { insertUser(a,"an@example.com"); assertThat(sqlState(() -> insertUser(a,"An@Example.com"))).isEqualTo("23505"); }
@Test void sameEmailAllowedInOtherTenant()     { insertUser(a,"x@example.com"); insertUser(b,"x@example.com"); /* không ném */ }
@Test void softDeletedEmailCanBeReused()       { /* update deleted_at=now() rồi insert lại cùng email → ok */ }
@Test void userNeedsEmailUsernameOrPhone()     { assertThat(state insert user cả 3 null).isEqualTo("23514"); }
@Test void sessionCannotReferenceUserOfAnotherTenant() { /* session tenant a, user_id của tenant b */ assertThat(state).isEqualTo("23503"); }
@Test void deletingUserCascadesCredentialsSessionsAndTokens() {
  // tạo password_credentials, mfa_factors, sessions, refresh_tokens cho user; delete user → count mỗi bảng theo user_id = 0
}
@Test void refreshTokenHashIsUnique()          { assertThat(state).isEqualTo("23505"); }
@Test void updatedAtIsMaintained()             { /* update display_name; updated_at > created_at */ }
@Test void appRoleSeesOnlyOwnTenantUsers()     { /* user ở a và b; inTx("rbac_app", a): count identity.users where tenant_id=b = 0, where tenant_id=a ≥ 1 */ }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.IdentitySchemaTest'` · Expected: FAIL.

- [ ] **Step 3: Viết `V3__identity.sql`** theo Interfaces.

- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS (kể cả `TenantIsolationTest` giờ phủ 11 bảng mới).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__identity.sql src/test/java/com/rbac_platform/db/IdentitySchemaTest.java
git commit -m "feat(db): add identity schema"
```

---

### Task 4: Apps (V4)

**Files:**
- Create: `src/main/resources/db/migration/V4__apps.sql`
- Test: `src/test/java/com/rbac_platform/db/AppsSchemaTest.java`

**Interfaces:**
- Consumes: Task 1–3.
- Produces:
  - `apps.applications, client_secrets, api_keys, signing_keys` theo spec §8; `client_id` và `api_keys.prefix` unique **toàn cục**; `require_pkce DEFAULT true`; unique index một phần `(tenant_id) WHERE status = 'active'` trên `signing_keys`. Mỗi bảng `enable_tenant_rls`.
  - `ALTER TABLE identity.sessions / identity.refresh_tokens ADD FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications(tenant_id, id)`.
  - `apps.resolve_api_key(p_prefix text) RETURNS TABLE (tenant_id uuid, api_key_id uuid, application_id uuid, key_hash text, expires_at timestamptz, revoked_at timestamptz)` — SECURITY DEFINER, EXECUTE cho `rbac_app`.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void clientIdIsGloballyUnique()        { /* cùng client_id ở tenant a và b */ assertThat(state).isEqualTo("23505"); }
@Test void apiKeyPrefixIsGloballyUnique()    { assertThat(state).isEqualTo("23505"); }
@Test void onlyOneActiveSigningKeyPerTenant(){ /* active + active → 23505; active + next → ok */ }
@Test void requirePkceDefaultsToTrue()       { }
@Test void resolveApiKeyWorksWithoutTenantContext() {
  // tạo app + api_key cho tenant a; inTx("rbac_app", null): resolve_api_key(prefix) trả tenant_id=a, key_hash đúng;
  // cùng transaction: count(apps.api_keys) = 0
}
@Test void sessionApplicationMustBelongToSameTenant() { assertThat(state).isEqualTo("23503"); }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.AppsSchemaTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V4__apps.sql`.**
- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V4__apps.sql src/test/java/com/rbac_platform/db/AppsSchemaTest.java
git commit -m "feat(db): add apps schema with API key resolution"
```

---

### Task 5: Mô hình phân quyền (V5)

**Files:**
- Create: `src/main/resources/db/migration/V5__authz_model.sql`
- Test: `src/test/java/com/rbac_platform/db/AuthzModelTest.java`

**Interfaces:**
- Consumes: Task 1–4.
- Produces:
  - `authz.resource_types`, `authz.relations`, `authz.relation_subject_types`, `authz.permission_rules`, `authz.conditions` theo spec §7.2. Mỗi bảng `enable_tenant_rls`.
  - `relations.kind` CHECK `role|relationship|permission`; unique `(tenant_id, resource_type_id, key)`, `(tenant_id, id, resource_type_id)`, `(tenant_id, id, resource_type_id, kind)`.
  - `relation_subject_types`: `id int identity`, unique `NULLS NOT DISTINCT (tenant_id, relation_id, subject_type_id, subject_relation_id)`, FK `(tenant_id, subject_relation_id, subject_type_id) → relations(tenant_id, id, resource_type_id)`.
  - `permission_rules`: cột `resource_type_id int NOT NULL`, `permission_kind text NOT NULL DEFAULT 'permission' CHECK (permission_kind = 'permission')`, `effect text NOT NULL DEFAULT 'allow' CHECK (effect IN ('allow','deny'))`, `kind CHECK (kind IN ('computed','via','tenant'))`, `target_key text NOT NULL`, `via_relation_id int NULL`, `condition_id int NULL`; `CHECK ((kind = 'via') = (via_relation_id IS NOT NULL))`; FK `(tenant_id, permission_id, resource_type_id, permission_kind) → relations(tenant_id, id, resource_type_id, kind)`; FK `(tenant_id, via_relation_id, resource_type_id) → relations(tenant_id, id, resource_type_id)`.
  - `authz.bump_model_revision()` trigger AFTER INSERT/UPDATE/DELETE FOR EACH ROW trên cả 5 bảng: `authz_model_revision + 1` cho tenant của dòng.
  - `authz.protect_builtin()` trigger BEFORE DELETE, và BEFORE UPDATE OF `key`, trên `resource_types` và `relations`: nếu `is_builtin` → raise token `builtin_immutable`.
  - `authz.seed_builtin_model(p_tenant_id uuid) RETURNS void` — SECURITY DEFINER; tạo types `tenant` (roles `admin`, `member`; subject types `user`, `group#member`, `application`), `user`, `group` (relationship `member`; subject types `user`, `group#member`), `application`; tất cả `is_builtin = true`. Trigger `AFTER INSERT ON tenancy.tenants` gọi hàm này.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void newTenantGetsBuiltinModel() {
  // insertTenant(); types {tenant,user,group,application} is_builtin;
  // relations: tenant.admin(role), tenant.member(role), group.member(relationship);
  // group.member cho phép (user, null) và (group, member)
}
@Test void builtinsCannotBeDeletedOrRenamed()      { assertThat(errorMessage(() -> delete type 'group')).contains("builtin_immutable"); }
@Test void relationKeyUniquePerResourceType()      { /* 'editor' 2 lần trên folder → 23505; 'editor' trên document → ok */ }
@Test void ruleMustTargetPermissionKind()          { /* rule với permission_id là relation kind 'role' → 23503 */ }
@Test void viaRuleRequiresViaRelation()            { assertThat(state kind='via', via_relation_id null).isEqualTo("23514"); }
@Test void effectIsAllowOrDeny()                   { assertThat(state effect='block').isEqualTo("23514"); }
@Test void denyRuleIsAccepted()                    { /* rule effect='deny' kind='computed' target_key='blocked' → ok */ }
@Test void modelChangeBumpsRevision()              { /* đọc revision; insert resource_type; revision tăng đúng 1 */ }
@Test void modelRowsCannotReferenceAnotherTenant() { /* rule ở tenant a trỏ relation của tenant b → 23503 */ }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.AuthzModelTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V5__authz_model.sql`.**
- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V5__authz_model.sql src/test/java/com/rbac_platform/db/AuthzModelTest.java
git commit -m "feat(db): add tenant-defined authorization model with deny rules"
```

---

### Task 6: Tiện ích partition theo tháng (V6)

**Files:**
- Create: `src/main/resources/db/migration/V6__partition_maintenance.sql`
- Test: `src/test/java/com/rbac_platform/db/PartitionMaintenanceTest.java`

**Interfaces:**
- Produces (tất cả SECURITY DEFINER, EXECUTE chỉ cho `rbac_admin`; cột partition tự đọc từ `pg_partitioned_table`):
  - `util.create_monthly_partition(p_parent regclass, p_month date) RETURNS boolean` — tạo `<parent>_pYYYYMM` cho `[đầu tháng, đầu tháng sau)`; trả `false` nếu đã có. Nếu partition `DEFAULT` đang chứa dòng trong khoảng đó: tạo bảng rời `LIKE parent INCLUDING DEFAULTS INCLUDING CONSTRAINTS`, chuyển dòng (`DELETE ... RETURNING` → `INSERT`), rồi `ATTACH PARTITION`.
  - `util.ensure_monthly_partitions(p_parent regclass, p_months_ahead int) RETURNS int` — gọi hàm trên cho tháng hiện tại và `p_months_ahead` tháng sau; trả số partition mới tạo.
  - `util.drop_partitions_older_than(p_parent regclass, p_keep interval) RETURNS int` — drop partition có cận trên `<= now() - p_keep`; không bao giờ drop `DEFAULT`.

- [ ] **Step 1: Viết test thất bại** (mỗi test tự tạo bảng riêng `util.pt_<rand> (ts timestamptz NOT NULL, v int) PARTITION BY RANGE (ts)` + partition `_default`)

```java
@Test void ensureCreatesCurrentAndFutureMonthsIdempotently() { assertThat(ensure(t,3)).isEqualTo(4); assertThat(ensure(t,3)).isZero(); /* có t_p<yyyymm hiện tại> */ }
@Test void movesRowsOutOfDefaultBeforeAttaching() {
  // insert ts = đầu tháng sau + 1 ngày (rơi vào default); ensure(t,1) → không ném;
  // count từ t_default = 0, từ t_p<tháng sau> = 1
}
@Test void dropsOnlyPartitionsOlderThanKeep() {
  // create_monthly_partition(t, tháng hiện tại - 14 tháng) + ensure(t,0); drop(t,'12 months') = 1; partition hiện tại và _default còn
}
@Test void appRoleCannotRunPartitionMaintenance() { assertThat(state as rbac_app).isEqualTo("42501"); }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.PartitionMaintenanceTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V6__partition_maintenance.sql`.**
- [ ] **Step 4: Chạy, xác nhận pass** — cùng lệnh · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V6__partition_maintenance.sql src/test/java/com/rbac_platform/db/PartitionMaintenanceTest.java
git commit -m "feat(db): add monthly partition maintenance functions"
```

---

### Task 7: Kho quan hệ và changelog (V7)

**Files:**
- Create: `src/main/resources/db/migration/V7__authz_relationships.sql`
- Test: `src/test/java/com/rbac_platform/db/RelationshipsTest.java`

**Interfaces:**
- Consumes: mô hình (Task 5), `util.ensure_monthly_partitions` (Task 6), `identity.users/groups`, `apps.applications`.
- Produces:
  - `authz.relationships` đúng bảng ở spec §7.3; `CHECK (char_length(object_id) BETWEEN 1 AND 256)`, tương tự `subject_id`; unique `NULLS NOT DISTINCT` thuận; FK ghép object/subject; chỉ mục ngược và chỉ mục `expires_at` như spec; `enable_tenant_rls`.
  - `authz.validate_relationship()` BEFORE INSERT/UPDATE, raise các token: `relation_is_permission`, `subject_type_not_allowed` (gồm wildcard khi `allow_wildcard=false`, và `*` đi kèm `subject_relation_id`), `condition_not_allowed`, `builtin_entity_missing` (object/subject thuộc type builtin `user|group|application` mà ID không phải UUID hoặc không có dòng tương ứng; type `tenant` mà ID ≠ `tenant_id::text`).
  - `authz.relationship_changes` partition RANGE theo `changed_at`, PK `(changed_at, seq)`, `seq bigserial`, các cột như spec §7.5, `txid xid8 DEFAULT pg_current_xact_id()`, `actor_type`/`actor_id` lấy từ `current_setting('app.actor_type'|'app.actor_id', true)`; partition `_default` + `SELECT util.ensure_monthly_partitions('authz.relationship_changes', 3)`; `enable_tenant_rls` rồi `REVOKE UPDATE, DELETE ... FROM rbac_app`.
  - `authz.log_relationship_change()` AFTER INSERT/UPDATE/DELETE FOR EACH ROW; UPDATE ghi thành `delete` (giá trị cũ) + `insert` (giá trị mới).
  - `authz.delete_entity_relationships()` AFTER DELETE FOR EACH ROW trên `identity.users` (`TG_ARGV[0]='user'`), `identity.groups` (`'group'`), `apps.applications` (`'application'`): xóa tuple có subject **hoặc** object là bản ghi đó trong cùng tenant.

- [ ] **Step 1: Viết test thất bại** — helper `canonicalModel(UUID tenant)` dựng mô hình ví dụ §7.4 (`folder`, `document` có `parent`/`editor`/`blocked`/`edit`/`view`, `invoice.approver` với `allow_condition=true`, condition `under_limit`).

```java
@Test void storesCanonicalExampleAndLogsInserts() {
  // group:<g>#member@user:<an>, folder:ke-toan#editor@group:<g>#member, document:123#parent@folder:ke-toan → ok;
  // relationship_changes có 3 dòng op='insert' cho tenant
}
@Test void rejectsPermissionRelation()         { assertThat(msg(insert document:123#edit@user)).contains("relation_is_permission"); }
@Test void rejectsDisallowedSubjectType()      { assertThat(msg(document:123#parent@user:x)).contains("subject_type_not_allowed"); }
@Test void rejectsWildcardWhenNotAllowed()     { assertThat(msg(folder:f#editor@user:*)).contains("subject_type_not_allowed"); }
@Test void rejectsConditionWhenNotAllowed()    { assertThat(msg(folder editor kèm condition_id)).contains("condition_not_allowed"); }
@Test void acceptsConditionWithContext()       { /* invoice:*#approver@user:<binh> condition under_limit, context {"max_amount":50000000} → ok */ }
@Test void rejectsRelationOfAnotherObjectType(){ assertThat(state object_type=document, relation_id=folder.editor).isEqualTo("23503"); }
@Test void rejectsMissingOrMalformedBuiltinIds(){ /* user:<uuid ngẫu nhiên> và user:not-a-uuid */ contains("builtin_entity_missing"); }
@Test void rejectsForeignTenantObject()        { /* tenant:<id tenant khác>#admin@user:<u> */ contains("builtin_entity_missing"); }
@Test void duplicateTupleRejectedEvenWithNullSubjectRelation() { assertThat(state).isEqualTo("23505"); }
@Test void objectIdLongerThan256Rejected()     { assertThat(state).isEqualTo("23514"); }
@Test void deletingUserRemovesTheirTuplesAndLogsDeletes() { /* xóa user an → không còn tuple subject user:<an>; changelog có op='delete' */ }
@Test void groupCycleIsStoredAndCascadeDeletes() {
  // group:a#member@group:b#member và group:b#member@group:a#member → ok; delete identity.groups a → count tuple liên quan a = 0
}
@Test void changelogRecordsActorFromSession()  { /* set_config app.actor_type='user', app.actor_id='<u>' rồi insert → changelog có đúng actor */ }
@Test void updateIsLoggedAsDeleteThenInsert()  { /* update expires_at → 2 dòng changelog op delete rồi insert */ }
@Test void appRoleCannotRewriteChangelog()     { assertThat(state delete from changelog as rbac_app).isEqualTo("42501"); }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.RelationshipsTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V7__authz_relationships.sql`.**
- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V7__authz_relationships.sql src/test/java/com/rbac_platform/db/RelationshipsTest.java
git commit -m "feat(db): add relationship tuple store with validation and changelog"
```

---

### Task 8: Audit (V8)

**Files:**
- Create: `src/main/resources/db/migration/V8__audit.sql`
- Test: `src/test/java/com/rbac_platform/db/AuditTest.java`

**Interfaces:**
- Consumes: Task 1, 6.
- Produces: `audit.events` theo spec §9 (partition RANGE `occurred_at`, PK `(tenant_id, occurred_at, id)`, chỉ mục `(tenant_id, action, occurred_at DESC)`, CHECK `actor_type`, `outcome`), partition `_default` + `ensure_monthly_partitions('audit.events', 3)`, `enable_tenant_rls` rồi `REVOKE UPDATE, DELETE ON audit.events FROM rbac_app`.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void appRoleCanInsertAndReadOwnEvents()       { }
@Test void appRoleCannotUpdateOrDeleteEvents()      { assertThat(state update/delete as rbac_app).isEqualTo("42501"); }
@Test void farFutureEventLandsInDefaultPartition()  { /* occurred_at = now()+1 year → count audit.events_default = 1 */ }
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.AuditTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V8__audit.sql`.**
- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V8__audit.sql src/test/java/com/rbac_platform/db/AuditTest.java
git commit -m "feat(db): add append-only partitioned audit log"
```

---

### Task 9: Tạo tenant và tenant `platform` (V9, V10)

**Files:**
- Create: `src/main/resources/db/migration/V9__tenant_provisioning.sql`
- Create: `src/main/resources/db/migration/V10__platform_tenant.sql`
- Test: `src/test/java/com/rbac_platform/db/TenantProvisioningTest.java`

**Interfaces:**
- Consumes: mọi task trước.
- Produces:
  - `tenancy.create_tenant(p_slug text, p_name text, p_admin_email text, p_invitation_token_hash text, p_invitation_expires_at timestamptz, p_actor_id uuid) RETURNS TABLE (tenant_id uuid, admin_user_id uuid)` — SECURITY DEFINER, `REVOKE ... FROM PUBLIC`, EXECUTE chỉ `rbac_admin`. Làm đúng 5 bước spec §5.1 trong một lần gọi; đặt `app.actor_type` = `'operator'` (hoặc `'system'` khi `p_actor_id` null) và `app.actor_id` bằng `set_config(..., true)` để changelog/audit ghi đúng; `p_invitation_token_hash` null → bỏ bước token; hash có mà expiry null → raise token `invitation_expiry_required`. (Cột OUT trùng tên cột bảng: dùng `#variable_conflict use_column` hoặc alias.)
  - `V10__platform_tenant.sql`: `create_tenant('platform', 'Platform', '${platform_operator_email}', NULL, NULL, NULL)`; thêm relation `operator` (kind `role`, không builtin) trên type `tenant` của tenant platform, subject type `user`; tuple `tenant:<platform>#operator@user:<admin>`; `settings = settings || '{"mfa":{"required":"required"}}'`.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void createsTenantAdminTupleInvitationAndAuditAtomically() {
  // as rbac_admin: create_tenant('acme-<rand>', 'ACME', 'admin@acme.test', '<sha256hex>', now()+7d, <operator uuid>)
  // → user status 'invited'; tuple tenant:<id>#admin@user:<admin>; one_time_tokens purpose 'invitation' 1 dòng;
  // audit.events action 'tenant.created' actor_type 'operator'; builtin types có đủ
}
@Test void duplicateSlugLeavesNothingBehind() {
  // gọi lần 2 cùng slug, email 'second@acme.test' → 23505; count identity.users email 'second@acme.test' = 0
}
@Test void invitationHashWithoutExpiryIsRejected() { contains("invitation_expiry_required"); }
@Test void appRoleCannotCreateTenant()             { assertThat(state as rbac_app).isEqualTo("42501"); }
@Test void platformTenantIsSeeded() {
  // tenant slug 'platform'; user 'operator@platform.test' status 'invited';
  // tuple operator tồn tại; settings->'mfa'->>'required' = 'required'
}
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.TenantProvisioningTest'` · Expected: FAIL.
- [ ] **Step 3: Viết `V9__tenant_provisioning.sql` và `V10__platform_tenant.sql`.**
- [ ] **Step 4: Chạy toàn bộ test DB** — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V9__tenant_provisioning.sql src/main/resources/db/migration/V10__platform_tenant.sql src/test/java/com/rbac_platform/db/TenantProvisioningTest.java
git commit -m "feat(db): add atomic tenant provisioning and platform tenant seed"
```

---

### Task 10: Kiểm tra phơi nhiễm, login role và áp lên Supabase

**Files:**
- Create: `scripts/db/bootstrap_login_roles.sql`
- Test: `src/test/java/com/rbac_platform/db/SupabaseExposureTest.java`

**Interfaces:**
- Consumes: toàn bộ migration.
- Produces: `bootstrap_login_roles.sql` dùng biến psql `:'app_password'`, `:'admin_password'`: `ALTER ROLE rbac_app WITH LOGIN PASSWORD :'app_password'; ALTER ROLE rbac_admin WITH LOGIN PASSWORD :'admin_password';` (không chứa mật khẩu thật).

- [ ] **Step 1: Viết test thất bại**

```java
@Test void anonAndAuthenticatedCannotTouchAnyPlatformObject() {
  // với mọi bảng/partition/view trong các schema nền tảng: has_table_privilege(r, oid, 'SELECT,INSERT,UPDATE,DELETE') = false, r ∈ {anon, authenticated}
  // với mọi function SECURITY DEFINER trong các schema đó: has_function_privilege(r, oid, 'EXECUTE') = false
}
@Test void nothingCreatedInPublicSchema() { /* count pg_class ở public có owner rbac_owner = 0 */ }
@Test void appRoleCannotExecuteAdminOnlyFunctions() {
  // has_function_privilege('rbac_app', ...) = false cho create_tenant, create_monthly_partition, ensure_monthly_partitions, drop_partitions_older_than
}
```

- [ ] **Step 2: Chạy; nếu FAIL thì sửa grant trong migration tương ứng** (không thêm migration mới cho lỗi grant chưa từng deploy) — Run: `./mvnw -q test -Dtest='com.rbac_platform.db.*Test'` · Expected: PASS toàn bộ.

- [ ] **Step 3: Commit**

```bash
git add scripts/db/bootstrap_login_roles.sql src/test/java/com/rbac_platform/db/SupabaseExposureTest.java
git commit -m "test(db): verify Supabase exposure and add login role bootstrap"
```

- [ ] **Step 4: Chuẩn bị kết nối Supabase**

Lấy connection string **Session pooler** từ chủ dự án (Supabase Dashboard → Connect → Session pooler) nếu chưa có; ghi vào `.env` (`DB_URL=jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require`, `PSQL_URL` tương ứng, `DB_USER=postgres.wyfuvwrpkqczdgjnzvog`, `DB_PASSWORD`, `PLATFORM_OPERATOR_EMAIL` — hỏi chủ dự án email vận hành). Sinh `DB_APP_PASSWORD`, `DB_ADMIN_PASSWORD` bằng `openssl rand -base64 32`. Cài client nếu thiếu: `sudo apt-get install -y postgresql-client` (nếu cần mật khẩu sudo, nhờ chủ dự án chạy bằng `! ...`).

- [ ] **Step 5: Kiểm tra trước khi migrate**

Run: `psql "$PSQL_URL" -c "select version();" -c "select e.extname, n.nspname from pg_extension e join pg_namespace n on n.oid = e.extnamespace where e.extname in ('citext','pgcrypto');"`
Expected: PostgreSQL 17.x (nếu 18.x: đổi BOM sang `18.6.0`, chạy lại toàn bộ test trước khi tiếp). `citext`/`pgcrypto` không có hoặc nằm trong `extensions` — nếu nằm ở schema khác, dừng và báo chủ dự án.

- [ ] **Step 6: Migrate**

Run: `set -a; source .env; set +a; ./mvnw -q flyway:info && ./mvnw flyway:migrate`
Expected: `Successfully applied 10 migrations to schema "flyway"`.

- [ ] **Step 7: Bật login role**

Run: `psql "$PSQL_URL" -v app_password="$DB_APP_PASSWORD" -v admin_password="$DB_ADMIN_PASSWORD" -f scripts/db/bootstrap_login_roles.sql`
Expected: `ALTER ROLE` ×2.

- [ ] **Step 8: Xác minh trên Supabase**

Run (kết nối bằng `rbac_app.wyfuvwrpkqczdgjnzvog` / `DB_APP_PASSWORD` qua pooler): `select count(*) from identity.users;` → `0` (chưa đặt tenant). Kết nối `postgres`: `select has_schema_privilege('anon','identity','USAGE'), has_schema_privilege('authenticated','authz','USAGE');` → `f | f`; `select slug from tenancy.tenants;` → `platform`. Trong Dashboard → Settings → Data API, "Exposed schemas" **không** chứa schema nào của nền tảng.

- [ ] **Step 9: Push nhánh và nhắc đổi mật khẩu**

```bash
git push -u origin feat/database-schema
```
Nhắc chủ dự án đổi mật khẩu `postgres` trên Supabase (đã lộ trong chat) và cập nhật `.env`.
