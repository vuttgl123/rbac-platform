# Thiết kế database — RBAC Platform

- Ngày: 2026-10-26
- Phạm vi: **chỉ database** (PostgreSQL trên Supabase). Service API (đăng nhập, cấp token, endpoint kiểm tra quyền) là giai đoạn sau, nhưng database phải được thiết kế để service đó chạy được.
- Quy ước đánh dấu: **[Chủ dự án]** = chủ dự án đã quyết định; **[Quyết định thiết kế]** = người viết tài liệu tự chọn, chủ dự án có thể phản đối.

---

## 1. Mục đích và lý do

Chủ dự án sẽ xây nhiều hệ thống khác nhau và không muốn hệ thống nào cũng phải tự làm lại phần đăng nhập và phân quyền. Nền tảng này là **dịch vụ định danh và phân quyền trung tâm, dạng SaaS nhiều tenant** (giống Auth0 hoặc Keycloak, cộng với một engine phân quyền kiểu Google Zanzibar / SpiceDB / OpenFGA):

- Mỗi **tổ chức (tenant)** được người vận hành tạo (không có tự đăng ký, xem §5.1) và sau đó tự quản lý người dùng, nhóm, vai trò, quyền và các ứng dụng tích hợp của mình. **[Chủ dự án]**
- Các hệ thống của tenant hỏi nền tảng hai câu: "người này là ai?" (đăng nhập, token) và "người này có được làm X trên đối tượng Y không?" (kiểm tra quyền).
- Phân quyền phải chi tiết tới mức từng đối tượng, có kế thừa qua cây và qua nhóm. Ví dụ chuẩn: *"An được sửa tài liệu 123 vì tài liệu đó nằm trong thư mục Kế toán, thư mục được chia sẻ cho phòng Kế toán, và An thuộc phòng Kế toán."* **[Chủ dự án]**
- Quyền có thể **kèm điều kiện**, ví dụ "kế toán được duyệt hóa đơn khi số tiền dưới 50 triệu", "chỉ trong giờ hành chính". **[Chủ dự án]**

## 2. Mục tiêu, không phải mục tiêu, phản mục tiêu

### Mục tiêu
1. Tách biệt dữ liệu tuyệt đối giữa các tenant, được bảo vệ ở **hai lớp**: code của ứng dụng và Row Level Security của Postgres.
2. Mỗi tenant có kho người dùng riêng. Cùng một email ở hai tenant là **hai tài khoản hoàn toàn khác nhau**. **[Chủ dự án]**
3. Định danh cho bản đầu tiên: email + mật khẩu, SSO qua OIDC, MFA TOTP kèm mã khôi phục, phiên đăng nhập và refresh token có thể thu hồi, ứng dụng tích hợp (OAuth client), API key, audit log. **[Chủ dự án đã đồng ý với đề xuất]**
4. Mô hình phân quyền **do từng tenant tự định nghĩa bằng dữ liệu** (loại đối tượng, vai trò, quan hệ, quyền, luật suy diễn, điều kiện), không cần DDL và không cần deploy.
5. Hỗ trợ RBAC thông thường (vai trò cấp tenant) và ReBAC (quyền trên từng đối tượng, kế thừa) trên **cùng một cơ chế lưu trữ và đánh giá**.
6. Hỗ trợ **luật cấm**: chặn một chủ thể dù họ đã nhận quyền qua đường rộng hơn. Ví dụ cả phòng Kế toán sửa được thư mục, *trừ* Bình trên tài liệu 123. **[Chủ dự án]**
7. Quy mô mục tiêu ban đầu: **khoảng 100.000 người dùng** tổng cộng. Hiện tại chỉ có chủ dự án tự test. **[Chủ dự án]** Thiết kế phải lớn lên được (nhiều triệu người dùng, hàng trăm triệu quan hệ) mà không phải đập đi làm lại.

### Không phải mục tiêu (bản đầu tiên)
- Service API, UI quản trị, SDK (giai đoạn sau).
- SAML (bảng cấu hình IdP có chỗ cho `type = 'saml'`, nhưng chưa làm), WebAuthn/passkey, SCIM provisioning, màn hình consent cho ứng dụng bên thứ ba.
- Lưu log của **từng lần kiểm tra quyền** trong Postgres (quá nhiều; nếu cần thì đưa sang Elasticsearch sau).
- Webhook/event gửi cho tenant, billing, multi-region, data residency.
- Một người dùng thuộc nhiều tenant với cùng một tài khoản.

### Phản mục tiêu (dù chạy được vẫn coi là thất bại)
- Có bất kỳ đường nào để tenant này đọc hoặc ghi dữ liệu của tenant khác.
- Mật khẩu, mã khôi phục, API key, client secret hay refresh token được lưu ở dạng đọc ngược lại được. Những secret cần đọc lại (TOTP secret, client secret của IdP, private key ký token) thì phải được mã hóa.
- Tenant muốn thêm một loại đối tượng hay vai trò mới mà phải đợi migration.
- Schema bị gắn với kiểu tài nguyên của một ứng dụng cụ thể (ví dụ có sẵn bảng `documents`).
- Các bảng của nền tảng lộ ra qua Data API của Supabase (PostgREST) cho role `anon` hoặc `authenticated`.

## 3. Ràng buộc và môi trường

- **Database:** PostgreSQL do Supabase quản lý. Supabase chỉ đóng vai trò cung cấp Postgres. **Không dùng Supabase Auth (`auth.users`)** vì đó là một kho người dùng chung cho một ứng dụng, không hợp với user pool riêng cho từng tenant. **[Quyết định thiết kế]**
- **Dự án hiện có:** Spring Boot 4.1.2, Java 21, Maven. Đã có sẵn PostgreSQL driver, Redis (reactive), Elasticsearch, Spring Batch, Spring Security. Thư mục `frontend/` là React + Vite.
- **Migration:** Flyway, các file SQL thuần đặt ở `src/main/resources/db/migration`. **[Quyết định thiết kế]** Cần thêm dependency Flyway cho Spring Boot 4 cùng module `flyway-database-postgresql`.
- **Thông tin kết nối:** chủ dự án đã dán connection string có mật khẩu vào chat. Mật khẩu này **phải được đổi** trên Supabase. Không bao giờ commit thông tin kết nối: dùng biến môi trường (`DB_URL`, `DB_USER`, `DB_PASSWORD`) và một file `.env` được thêm vào `.gitignore` (hiện `.gitignore` chưa có `.env`).
- **Lưu ý mạng của Supabase:** host kết nối trực tiếp `db.<ref>.supabase.co:5432` chỉ có IPv6 nếu chưa mua add-on IPv4, mà WSL thường không có IPv6. Khi đó dùng Supavisor pooler: cổng 5432 ở session mode cho Flyway, cổng 6543 ở transaction mode cho ứng dụng. Ở transaction mode, ngữ cảnh tenant **bắt buộc** đặt bằng `SET LOCAL` / `set_config(..., true)` bên trong transaction (xem §6).
- **Phiên bản Postgres:** người xây dựng chạy `SELECT version()` trước. Nếu ≥ 18 thì dùng hàm `uuidv7()` có sẵn. Nếu thấp hơn, tạo hàm `util.uuid_v7()` (PL/pgSQL) hoặc sinh ID ở phía Java. Phải có `NULLS NOT DISTINCT` (PG ≥ 15).
- **Extension:** `citext`, `pgcrypto`; `pg_cron` là tùy chọn. Đặt trong schema `extensions` theo quy ước của Supabase.

## 4. Kiến trúc tổng thể của database

### 4.1 Các schema **[Quyết định thiết kế]**

| Schema | Nội dung |
|---|---|
| `tenancy` | tenant, domain của tenant |
| `identity` | người dùng, mật khẩu, MFA, IdP/SSO, phiên đăng nhập, refresh token, token dùng một lần, nhóm |
| `apps` | ứng dụng tích hợp (OAuth client), client secret, API key, khóa ký token |
| `authz` | mô hình phân quyền (loại đối tượng, quan hệ, luật, điều kiện), kho quan hệ (tuple), changelog |
| `audit` | nhật ký kiểm toán |
| `util` | hàm dùng chung (lấy tenant hiện tại, trigger `updated_at`, sinh UUIDv7 nếu cần, tạo partition) |

Không tạo gì trong `public`. Các schema trên **không** được thêm vào danh sách "Exposed schemas" của Data API trên Supabase, và phải `REVOKE ALL ... FROM anon, authenticated`.

### 4.2 Quy ước chung **[Quyết định thiết kế]**
- Tên bảng và cột dùng `snake_case`, tên bảng ở số nhiều.
- ID là `uuid` theo **UUIDv7**: sinh được ở bất kỳ đâu, không lộ số lượng bản ghi, và sắp theo thời gian nên chỉ mục B-tree không bị phân mảnh. Ngoại lệ: các bảng metadata nhỏ của mô hình phân quyền dùng `int` (xem §7.2), và bảng tuple dùng `bigint` identity.
- **Mọi bảng thuộc tenant đều có `tenant_id uuid NOT NULL`, và khóa chính là `(tenant_id, id)`.** Khóa ngoại giữa các bảng con cũng ghép theo `(tenant_id, xxx_id)`. Nhờ vậy database đảm bảo một bản ghi không thể trỏ sang bản ghi của tenant khác. Sau này cũng có thể hash-partition theo `tenant_id` hoặc chuyển sang Citus mà không phải đổi khóa. *Hệ quả cho code Java:* JPA với khóa ghép khá cồng kềnh, nên Spring Data JDBC / `JdbcClient` / jOOQ hợp hơn. Việc chọn thư viện là của người xây dựng.
- Trạng thái và loại dùng `text` + `CHECK (... IN (...))` thay cho enum của Postgres, vì enum khó sửa và khó xóa giá trị.
- Thời gian luôn là `timestamptz`. Có `created_at DEFAULT now()`, và `updated_at` được cập nhật bằng trigger `util.set_updated_at()`.
- Xóa mềm (`deleted_at`) chỉ dùng cho `tenants`, `users`, `applications`. Các bảng còn lại xóa cứng, lịch sử nằm trong audit và changelog.
- Secret dạng **băm** (không cần đọc lại): mật khẩu dùng Argon2id (chuỗi PHC). Token, API key, mã khôi phục, client secret dùng SHA-256 của chuỗi ngẫu nhiên ≥ 256 bit. Secret cần **đọc lại** được lưu thành `bytea` mã hóa kiểu envelope ở phía ứng dụng, kèm cột `encryption_key_id` để xoay khóa. Khóa chính (KEK) nằm ngoài database.

### 4.3 Cái gì nằm ở Redis, không nằm ở Postgres **[Quyết định thiết kế]**
Dữ liệu sống rất ngắn hoặc truy cập rất nóng sẽ nằm ở Redis: authorization code của OAuth (khoảng 60 giây), `state`/`nonce` của SSO, bộ đếm rate limit đăng nhập, cache mô hình phân quyền và kết quả kiểm tra quyền. Postgres vẫn là nguồn sự thật cho mọi thứ khác, kể cả session và refresh token.

## 5. Phần định danh

### 5.1 `tenancy`

**`tenancy.tenants`**: `id`, `slug` (duy nhất toàn cục, dùng trong URL và issuer, ví dụ `/t/{slug}`), `name`, `status` (`active` | `suspended` | `deleted`), `plan`, `settings jsonb` (chính sách mật khẩu, bắt buộc MFA, thời hạn phiên/token; các khóa có sẵn ghi dưới đây), `authz_model_revision bigint` (xem §7.6), `created_at`, `updated_at`, `deleted_at`. Khóa chính là `id`, vì đây là gốc nên không ghép.

Các khóa tối thiểu của `settings` (giá trị mặc định do người xây dựng chọn hợp lý): `password.min_length`, `password.history_count`, `mfa.required` (`off` | `optional` | `required`), `session.idle_ttl`, `session.absolute_ttl`, `lockout.max_attempts`, `lockout.duration`.

**`tenancy.tenant_domains`**: `tenant_id`, `domain` (duy nhất toàn cục), `verified_at`, `verification_token_hash`. Dùng để định tuyến SSO theo đuôi email và cho custom domain sau này.

**Người vận hành nền tảng** (chủ dự án và nhân viên):
- **Chỉ người vận hành mới tạo được tenant. Không có tự đăng ký.** Tạo xong, người vận hành gửi lời mời cho admin đầu tiên của tổ chức. **[Chủ dự án]**
- Người vận hành dùng **một tài khoản riêng**, tách khỏi mọi tài khoản trong các tenant khách hàng. **[Chủ dự án]**
- Cách hiện thực: **[Quyết định thiết kế]** không tạo bảng riêng. Tài khoản vận hành là người dùng của một **tenant hệ thống `platform`** được seed sẵn. Quyền vận hành là vai trò `operator` trên đối tượng `tenant` của tenant `platform`, tức là dùng chính mô hình phân quyền của nền tảng. Tài khoản vận hành đầu tiên được bootstrap tạo ở trạng thái `invited`; email lấy từ biến môi trường hoặc placeholder của Flyway, **không ghi cứng trong migration** và không có mật khẩu. Người vận hành đặt mật khẩu qua link mời, và nên bắt buộc MFA cho tenant `platform`.
- Thao tác xuyên tenant chỉ đi qua DB role `rbac_admin` (§6). Service chỉ dùng kết nối `rbac_admin` sau khi đã xác thực người gọi là `operator` của tenant `platform`.

**Tạo tenant** là một hàm duy nhất, **[Quyết định thiết kế]**: `tenancy.create_tenant(slug, name, admin_email, invitation_token_hash, invitation_expires_at, actor_id)`. Hàm là `SECURITY DEFINER` và **chỉ `rbac_admin` được `EXECUTE`**. Trong **một transaction**, hàm thực hiện:
1. Insert `tenancy.tenants`. Trigger seed sẽ tạo mô hình dựng sẵn (§7.2).
2. Tạo user admin đầu tiên với `status = 'invited'`.
3. Tạo tuple `tenant:<tenant_id>#admin@user:<user_id>`.
4. Tạo `identity.one_time_tokens` với `purpose = 'invitation'`. Service sinh token, gửi email chứa token gốc, còn database chỉ nhận và lưu hash.
5. Ghi `audit.events` (`tenant.created`).

Hàm trả về `tenant_id` và `user_id`. Vì vậy không bao giờ tồn tại một tenant không có admin. `rbac_app` **không có quyền INSERT** vào `tenancy.tenants`.

### 5.2 `identity`

**`identity.users`**
- `tenant_id`, `id`
- `email citext NULL`, `email_verified_at`, `username citext NULL`, `phone NULL`, `phone_verified_at`
- `display_name`, `given_name`, `family_name`, `locale`, `timezone`, `avatar_url`
- `status`: `invited` | `active` | `locked` | `disabled`
- `external_id` (ID của người này trong hệ thống của tenant), `attributes jsonb` (thuộc tính tùy biến, dùng cho claim trong token và làm đầu vào cho điều kiện)
- `last_login_at`, `failed_login_count`, `locked_until`
- `created_at`, `updated_at`, `deleted_at`
- `CHECK (email IS NOT NULL OR username IS NOT NULL OR phone IS NOT NULL)`
- Unique `(tenant_id, email) WHERE deleted_at IS NULL`, tương tự cho `username` và `phone`. Unique `(tenant_id, external_id)` khi `external_id` khác null.

**`identity.password_credentials`** (một dòng cho mỗi user, tách riêng để không vô tình đọc hash cùng với user): `tenant_id`, `user_id` (khóa chính ghép), `password_hash` (chuỗi PHC Argon2id), `must_change`, `changed_at`.

**`identity.password_history`**: `tenant_id`, `id`, `user_id`, `password_hash`, `created_at`. Dùng cho luật "không dùng lại N mật khẩu gần nhất".

**`identity.mfa_factors`**: `tenant_id`, `id`, `user_id`, `type` (`totp`; danh sách CHECK mở rộng được), `label`, `secret_encrypted bytea`, `encryption_key_id`, `last_used_step bigint` (chống dùng lại cùng một mã TOTP), `verified_at` (null nghĩa là đang đăng ký dở), `last_used_at`, `created_at`.

**`identity.mfa_recovery_codes`**: `tenant_id`, `id`, `user_id`, `code_hash`, `used_at`, `created_at`.

**`identity.identity_providers`** (SSO của từng tenant): `tenant_id`, `id`, `type` (`oidc` | `saml`), `name`, `enabled`, `issuer`, `discovery_url`, `client_id`, `client_secret_encrypted`, `encryption_key_id`, `scopes text[]`, `claim_mapping jsonb` (claim của IdP ánh xạ sang trường user hoặc nhóm), `allowed_domains text[]`, `jit_provisioning bool` (tự tạo user ở lần đăng nhập SSO đầu tiên), `config jsonb` (phần riêng của từng loại, ví dụ metadata SAML), `created_at`, `updated_at`.

**`identity.federated_identities`**: `tenant_id`, `id`, `user_id`, `provider_id`, `provider_subject` (claim `sub`), `email_at_link`, `last_login_at`. Unique `(tenant_id, provider_id, provider_subject)`.

**`identity.groups`** (metadata của nhóm hoặc phòng ban; **thành viên nhóm nằm trong kho quan hệ**, xem §7): `tenant_id`, `id`, `key` (unique theo tenant), `name`, `description`, `source` (`local` | `idp`), `external_id`, `created_at`, `updated_at`.

**`identity.sessions`**: `tenant_id`, `id`, `user_id`, `application_id NULL`, `amr text[]` (`pwd`, `otp`, `sso`, ...), `acr`, `ip inet`, `user_agent`, `created_at`, `last_seen_at`, `idle_expires_at`, `expires_at`, `revoked_at`, `revoke_reason`. Có chỉ mục `(tenant_id, user_id) WHERE revoked_at IS NULL`.

**`identity.refresh_tokens`**: `tenant_id`, `id`, `session_id`, `application_id`, `token_hash` (unique), `family_id` (cả chuỗi xoay vòng; khi phát hiện một token đã dùng bị dùng lại thì thu hồi cả family), `parent_id`, `scopes text[]`, `issued_at`, `expires_at`, `rotated_at`, `revoked_at`.

**`identity.one_time_tokens`**: `tenant_id`, `id`, `user_id NULL`, `purpose` (`email_verification` | `password_reset` | `invitation` | `magic_link`), `token_hash` (unique), `metadata jsonb` (ví dụ vai trò ban đầu khi mời), `expires_at`, `consumed_at`, `created_at`.

## 6. Cô lập tenant: role của DB và RLS **[Quyết định thiết kế]**

- **Các DB role:**
  - `rbac_owner`: sở hữu schema, dùng để chạy Flyway.
  - `rbac_app`: role LOGIN mà ứng dụng dùng. Không phải owner, không có `BYPASSRLS`.
  - `rbac_admin`: role LOGIN cho thao tác xuyên tenant của người vận hành.
- **Ngữ cảnh tenant:** đầu mỗi transaction, ứng dụng gọi `SELECT set_config('app.tenant_id', '<uuid>', true)`. Hàm `util.current_tenant_id()` trả về `nullif(current_setting('app.tenant_id', true), '')::uuid`.
- **Policy** cho mọi bảng thuộc tenant: `ENABLE` + `FORCE ROW LEVEL SECURITY`. Policy `TO rbac_app USING (tenant_id = (SELECT util.current_tenant_id())) WITH CHECK (giống USING)`. Viết `SELECT` bọc ngoài để Postgres tính hàm một lần cho mỗi câu truy vấn. Khi chưa đặt ngữ cảnh thì giá trị là null, nên không thấy dòng nào: **mặc định là đóng**. Một policy riêng `TO rbac_admin USING (true)` cho người vận hành, không cần BYPASSRLS.
- **`tenancy.tenants`:** `rbac_app` được `SELECT` mọi dòng, vì cần tra slug lúc đăng nhập và dữ liệu này không nhạy cảm. Chỉ được `UPDATE` dòng có `id = current_tenant_id()`.
- **Các tra cứu trước khi biết tenant:** tra API key theo prefix, tra domain ra tenant. Những việc này đi qua hàm `SECURITY DEFINER` hẹp (ví dụ `apps.resolve_api_key(prefix)` trả về `tenant_id`, `key_hash`, `status`), với `search_path` cố định, chỉ `rbac_app` được `EXECUTE`. Token endpoint của OAuth nằm dưới issuer của từng tenant, nên lúc đó đã biết tenant.

### 6.1 Các điểm thực thi đã chốt **[Quyết định thiết kế]**
- Các DB role (`rbac_owner`, `rbac_app`, `rbac_admin`) và mật khẩu được tạo bằng một script bootstrap chạy tay **ngoài Flyway**, vì mật khẩu không được nằm trong migration. Flyway chỉ `GRANT`.
- Hàm `SECURITY DEFINER` chạy dưới quyền `rbac_owner`. Do bảng có `FORCE ROW LEVEL SECURITY`, owner cũng phải có policy riêng: `TO rbac_owner USING (true)`. Mỗi hàm như vậy tự lọc đúng tenant trong thân hàm.
- Trên Testcontainers (Postgres thuần), migration bootstrap của test tạo sẵn role `anon` và `authenticated` để test §12 chạy được giống Supabase.
- Bảng metadata của Spring Batch (nếu dùng) nằm trong schema riêng `batch`, không nằm trong `public`.
- Khóa ngoại: bảng con của user (credentials, MFA, session, token, federated identity) dùng `ON DELETE CASCADE` theo user. Khóa ngoại tới mô hình phân quyền và tới tenant dùng `RESTRICT`. Xóa tenant là xóa mềm; xóa cứng do job của người vận hành làm.

## 7. Phân quyền (`authz`)

### 7.1 Ý tưởng
Kết hợp Zanzibar với điều kiện, giống SpiceDB caveats hay OpenFGA conditions:

- **Kho quan hệ (tuple):** mỗi dòng là một sự thật dạng `đối tượng#quan_hệ@chủ_thể`, có thể kèm điều kiện. Ví dụ `folder:ke-toan#editor@group:phong-ke-toan#member`.
- **Mô hình của tenant:** các bảng mô tả loại đối tượng nào có quan hệ nào, chủ thể nào được phép giữ quan hệ đó, và **quyền nào được suy ra từ đâu**.
- **RBAC chỉ là một trường hợp riêng:** một "vai trò cấp tenant" là quan hệ trên đối tượng `tenant` (ví dụ `tenant:<id>#editor@user:an`), cộng với luật "ai là `editor` của tenant thì được `edit` mọi `document`". Vai trò trên một đối tượng cụ thể là quan hệ trên đối tượng đó. Có **một kho, một engine đánh giá**.

### 7.2 Bảng mô hình
Đây là các bảng metadata nhỏ. ID là `int GENERATED ALWAYS AS IDENTITY` để dòng tuple gọn (4 byte thay cho chuỗi text). Khóa chính `(tenant_id, id)`.

**`authz.resource_types`**: `tenant_id`, `id`, `key` (ví dụ `document`, unique theo tenant; cho phép tiền tố `crm/document`), `name`, `description`, `application_id NULL` (ứng dụng sở hữu, chỉ để tổ chức), `is_builtin`, `created_at`, `updated_at`.

**`authz.relations`** (một namespace chung cho quan hệ và quyền trong mỗi loại đối tượng, giống SpiceDB): `tenant_id`, `id`, `resource_type_id`, `key` (unique theo `(tenant_id, resource_type_id)`), `kind`:
- `role`: vai trò gán cho chủ thể, ví dụ `editor`, `viewer`.
- `relationship`: liên kết cấu trúc, ví dụ `parent` (tài liệu → thư mục), `member` (nhóm → người).
- `permission`: quyền suy ra, **không bao giờ lưu tuple**, chỉ tính từ luật. Ví dụ `edit`, `view`, `approve`.

Kèm `name`, `description`, `is_builtin`. `role` và `relationship` giống nhau với engine đánh giá; chúng tách riêng để UI quản trị hiển thị (ma trận vai trò × quyền). Có unique `(tenant_id, id, resource_type_id)` để làm đích cho khóa ngoại ghép ở §7.3.

**`authz.relation_subject_types`** (ai được phép giữ một quan hệ): `tenant_id`, `relation_id`, `subject_type_id`, `subject_relation_id NULL` (ví dụ `group#member`), `allow_wildcard` (`user:*` nghĩa là mọi user trong tenant), `allow_condition`. Ví dụ: `folder.editor` cho phép `user` và `group#member`; `document.parent` cho phép `folder`.

**`authz.permission_rules`** (cách một `permission` được suy ra; nhiều luật cùng một quyền thì **cộng dồn**): `tenant_id`, `id`, `permission_id` (phải là relation có `kind = 'permission'`), `kind`, cùng các cột tùy theo `kind`:

| `kind` | Ý nghĩa | Cột dùng | Ví dụ |
|---|---|---|---|
| `computed` | ai có quan hệ/quyền `target_key` **trên cùng đối tượng** | `target_key` | `document.edit ⇐ editor`; `document.view ⇐ edit` |
| `via` | ai có `target_key` **trên đối tượng được trỏ tới qua quan hệ `via_relation_id`** | `via_relation_id`, `target_key` | `document.edit ⇐ parent → edit` |
| `tenant` | ai có `target_key` **trên đối tượng tenant** (vai trò toàn tổ chức) | `target_key` | `document.edit ⇐ tenant → editor` |

Kèm `effect` (`allow` | `deny`, mặc định `allow`). **Luật cấm** **[Chủ dự án]**:
- Ngữ nghĩa: *quyền P = (hợp của mọi luật `allow` của P) **trừ** (hợp của mọi luật `deny` của P)*. **Cấm luôn thắng.** Đây đúng là phép exclusion `-` của SpiceDB.
- Luật `deny` dùng đúng ba `kind` ở trên. Ví dụ `document.edit` có luật `deny computed blocked`, với `blocked` là một relation `kind = 'role'` mà tenant tự tạo, kiểu "danh sách chặn". Hoặc `deny tenant → suspended_user` để chặn ở cấp toàn tổ chức.
- Có điều kiện: một luật `deny` có điều kiện mà request thiếu tham số thì kết quả là `conditional`, không được coi là "không cấm".
- Ràng buộc mô hình, do ứng dụng kiểm tra khi lưu: nhánh `deny` của P không được phụ thuộc (trực tiếp hay gián tiếp) vào chính P. Phép trừ trong vòng lặp không có nghĩa xác định.
- Lưu ý cho người làm service: khi có `deny`, thêm một tuple có thể làm **mất** quyền ở chỗ khác. Vì vậy cache kết quả phải xóa theo changelog của cả relation bị trừ, chứ không chỉ relation cấp quyền.

Kèm `condition_id NULL`: luật chỉ có hiệu lực khi điều kiện đúng, với ngữ cảnh lấy từ request. `target_key` là text, vì ở kiểu `via` đích có thể thuộc nhiều loại đối tượng khác nhau. Ứng dụng kiểm tra tính hợp lệ khi lưu mô hình. Engine đánh giá phải phát hiện chu trình và giới hạn độ sâu (mặc định 25).

**`authz.conditions`**: `tenant_id`, `id`, `key` (unique theo tenant), `expression text` (biểu thức **CEL**, ví dụ `amount < max_amount`, `now.getHours() >= 8 && now.getHours() < 18`), `parameters jsonb` (tên và kiểu của từng tham số, ví dụ `{"amount":"double","max_amount":"double","now":"timestamp"}`), `description`, `created_at`, `updated_at`. **[Quyết định thiết kế: CEL]** vì đây là chuẩn mà SpiceDB và OpenFGA đều dùng và có thư viện Java (`cel-java`). Database chỉ lưu biểu thức; việc biên dịch và đánh giá do service làm.

**Loại dựng sẵn**: được seed bằng trigger `AFTER INSERT ON tenancy.tenants` (gọi `authz.seed_builtin_model(tenant_id)`), có `is_builtin = true` và không xóa được:
- `tenant`, với các vai trò `admin` và `member`.
- `user`.
- `group`, với quan hệ `member`, cho phép `user` và `group#member` (nhóm lồng nhau).
- `application` (chủ thể máy, dùng cho M2M và API key).

### 7.3 Kho quan hệ: `authz.relationships`

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `tenant_id` | uuid | |
| `id` | bigint identity | khóa chính `(tenant_id, id)`, cần cho replication và changelog |
| `object_type_id` | int | lặp lại từ quan hệ để chỉ mục theo đối tượng |
| `object_id` | text | ID do hệ thống của tenant tự đặt (số, UUID, slug...). Tối đa 256 ký tự |
| `relation_id` | int | phải là `role` hoặc `relationship`, không được là `permission` |
| `subject_type_id` | int | |
| `subject_id` | text | `*` nghĩa là wildcard |
| `subject_relation_id` | int NULL | khác null nghĩa là chủ thể là một tập, ví dụ `group:x#member` |
| `condition_id` | int NULL | |
| `condition_context` | jsonb NULL | tham số gắn sẵn, ví dụ `{"max_amount": 50000000}` |
| `expires_at` | timestamptz NULL | quyền tạm thời |
| `created_at` | timestamptz | |

Ràng buộc:
- Unique `NULLS NOT DISTINCT (tenant_id, object_type_id, object_id, relation_id, subject_type_id, subject_id, subject_relation_id)`. Chỉ mục này cũng phục vụ chiều **thuận**: "ai có quan hệ R trên đối tượng O".
- Khóa ngoại ghép `(tenant_id, relation_id, object_type_id)` → `relations(tenant_id, id, resource_type_id)`, đảm bảo quan hệ đúng loại đối tượng. Tương tự cho `(tenant_id, subject_relation_id, subject_type_id)`; khi cột null thì `MATCH SIMPLE` bỏ qua.
- Trigger `BEFORE INSERT/UPDATE` từ chối nếu `relation.kind = 'permission'`, hoặc nếu tổ hợp chủ thể không có trong `relation_subject_types`, hoặc nếu có điều kiện mà `allow_condition = false`. Đây là lớp bảo vệ thứ hai; ứng dụng vẫn kiểm tra trước.
- Chỉ mục **ngược**: `(tenant_id, subject_type_id, subject_id, relation_id, object_type_id) INCLUDE (object_id, subject_relation_id)`. Phục vụ câu "user này có quyền trên những đối tượng nào" và "user thuộc những nhóm nào".
- Chỉ mục một phần `(expires_at) WHERE expires_at IS NOT NULL` cho job dọn dẹp. Engine đánh giá **luôn** bỏ qua tuple đã hết hạn, không đợi job xóa.
- **ID của các thực thể do nền tảng sở hữu** (`user`, `group`, `application`, `tenant`) trong tuple **luôn là UUID nội bộ** (`users.id`, `groups.id`, `applications.id`, `tenants.id`) ở dạng text. Không dùng key, slug, username hay `external_id`. **[Quyết định thiết kế]** Lý do: UUID không bao giờ đổi, nên đổi tên nhóm hay slug không phải viết lại tuple. UUID cũng trùng với claim `sub` trong token, nên hệ thống của tenant gửi luôn `sub` khi gọi kiểm tra quyền. Nếu tenant muốn dùng `external_id` của họ, service sẽ đổi sang UUID trước khi kiểm tra; database không lưu `external_id` trong tuple. Riêng các loại đối tượng do tenant định nghĩa (`document`, `folder`...) thì `object_id` là ID do hệ thống của tenant tự đặt.
- Với các loại dựng sẵn, trigger tuple kiểm tra **bản ghi phải tồn tại**: `group:X` cần có dòng trong `identity.groups`, `user:X` cần có trong `identity.users`, v.v. Wildcard `*` được miễn. Không thể tạo nhóm chỉ bằng tuple. Với các loại do tenant định nghĩa, không kiểm tra tồn tại vì đối tượng nằm ở hệ thống của tenant.
- Không có khóa ngoại từ `object_id`/`subject_id` tới các bảng khác, vì đây là tham chiếu đa hình. Khi xóa cứng `identity.users`, `identity.groups` hoặc `apps.applications`, một trigger sẽ xóa các tuple có chủ thể (hoặc đối tượng) là bản ghi đó. Khi xóa mềm hoặc user bị `disabled`/`locked`, user bị loại ở bước đánh giá: tra `identity.users` theo khóa chính `(tenant_id, subject_id::uuid)`.

### 7.4 Ví dụ chuẩn, ánh xạ vào dữ liệu
*Để dễ đọc, ví dụ dưới đây viết `user:an`, `group:phong-ke-toan`, `tenant:acme`. Trong dữ liệu thật, các ID này là UUID (xem §7.3). `folder:ke-toan`, `document:123`, `invoice` là ID do hệ thống của tenant đặt nên giữ nguyên.*

Mô hình của tenant ACME:
- `folder`: role `editor` (cho phép `user`, `group#member`), permission `edit` ⇐ `computed editor`.
- `document`: relationship `parent` (cho phép `folder`), role `editor` (cho phép `user`, `group#member`), permission `edit` ⇐ `computed editor` ∪ `via parent → edit`, permission `view` ⇐ `computed edit`.
- `invoice`: role `approver` (`allow_condition = true`), permission `approve` ⇐ `computed approver`.

Tuple:
```
group:phong-ke-toan#member   @ user:an
folder:ke-toan#editor        @ group:phong-ke-toan#member
document:123#parent          @ folder:ke-toan
invoice:*  (xem dưới)
```
Câu hỏi "An có `edit` trên `document:123` không?" được giải như sau: đi qua `via parent` tới `folder:ke-toan`, xét `edit` của thư mục, tức `editor`, ra `group:phong-ke-toan#member`, trong đó có `user:an`. Kết quả: **được phép**.

Ví dụ luật cấm: thêm vào `document` role `blocked` (cho phép `user`, `group#member`) và luật `edit: deny computed blocked`, rồi thêm tuple `document:123#blocked@user:binh`. Bình vẫn thuộc phòng Kế toán và vẫn sửa được mọi tài liệu khác trong thư mục, nhưng hỏi "Bình có `edit` trên `document:123` không?" sẽ ra **bị từ chối**, vì nhánh cấm khớp. Muốn Bình cũng không xem được thì phải thêm `view: deny computed blocked`. Luật `view ⇐ computed edit` có tự trả về "không" cho Bình (vì `edit` của Bình đã bị trừ), nhưng Bình vẫn có thể nhận `view` qua đường khác, ví dụ role `viewer`. Vì vậy mỗi quyền cần khai báo luật cấm của riêng nó.

Ví dụ có điều kiện: `tenant:acme#accountant@user:binh` với `condition = under_limit`, `condition_context = {"max_amount": 50000000}`, và luật `invoice.approve ⇐ tenant → accountant`. Khi request gửi `{"amount": 30000000}` thì được phép. Khi request không gửi `amount`, kết quả là **"có điều kiện: thiếu `amount`"** chứ không phải từ chối thẳng (giống SpiceDB). Vì vậy API kiểm tra quyền có 3 kết quả: `allowed`, `denied`, `conditional`.

### 7.5 Changelog: `authz.relationship_changes`
Được ghi bởi trigger `AFTER INSERT/UPDATE/DELETE` trên `relationships`. Các cột: `seq bigint identity`, `changed_at`, `tenant_id`, `op` (`insert` | `delete`; UPDATE được ghi thành delete + insert), các cột của tuple, `actor_type`, `actor_id` (lấy từ `current_setting('app.actor_id', true)`), `txid xid8` (lấy từ `pg_current_xact_id()`).

Dùng để: (1) xóa cache theo tenant hoặc đối tượng, (2) API "watch" cho tenant sau này, (3) consistency token kiểu zookie. **Lưu ý cho người xây dựng:** `seq` cấp theo thứ tự lúc ghi chứ không theo thứ tự commit, nên bên đọc theo `seq` phải dùng `txid` kết hợp `pg_snapshot_xmin(pg_current_snapshot())` để không bỏ sót dòng commit muộn. Bảng được partition theo tháng trên `changed_at`, khóa chính `(changed_at, seq)`, mặc định giữ **30 ngày**.

### 7.6 Phiên bản mô hình
Mọi INSERT/UPDATE/DELETE trên `resource_types`, `relations`, `relation_subject_types`, `permission_rules`, `conditions` sẽ tăng `tenancy.tenants.authz_model_revision` qua trigger. Service cache mô hình đã biên dịch theo cặp `(tenant_id, revision)`. Xóa một quan hệ còn tuple đang tham chiếu sẽ bị khóa ngoại chặn (`RESTRICT`); tenant phải xóa tuple trước.

## 8. Ứng dụng tích hợp (`apps`)

**`apps.applications`** (OAuth/OIDC client): `tenant_id`, `id`, `client_id` (chuỗi công khai, **unique toàn cục**), `name`, `type` (`web` | `spa` | `native` | `m2m`), `token_endpoint_auth_method` (`client_secret_basic` | `client_secret_post` | `private_key_jwt` | `none`), `grant_types text[]`, `redirect_uris text[]`, `post_logout_redirect_uris text[]`, `allowed_origins text[]`, `allowed_scopes text[]`, `require_pkce` (mặc định true), `access_token_ttl interval`, `refresh_token_ttl interval`, `is_first_party`, `enabled`, `metadata jsonb`, `created_at`, `updated_at`, `deleted_at`. Ứng dụng loại `m2m` là chủ thể `application:<id>` trong phân quyền.

**`apps.client_secrets`** (nhiều bản để xoay vòng không gián đoạn): `tenant_id`, `id`, `application_id`, `secret_hash`, `hint` (4 ký tự cuối), `created_at`, `expires_at`, `revoked_at`.

**`apps.api_keys`**: `tenant_id`, `id`, `application_id` (chủ của key), `name`, `prefix` (khoảng 12 ký tự đầu, **unique toàn cục**, dùng để tra cứu), `key_hash`, `scopes text[]`, `created_at`, `expires_at`, `last_used_at`, `revoked_at`. Định dạng key do service đặt, ví dụ `rbk_<prefix>_<secret>`.

**`apps.signing_keys`** (mỗi tenant một bộ khóa, issuer riêng): `tenant_id`, `id` (dùng làm `kid`), `algorithm` (`RS256` | `ES256` | `EdDSA`), `public_jwk jsonb`, `private_key_encrypted`, `encryption_key_id`, `status` (`next` | `active` | `retired`), `created_at`, `activated_at`, `retired_at`. Mỗi tenant tối đa một khóa `active` (unique index một phần). JWKS công khai gồm `next`, `active`, và `retired` trong thời gian ân hạn.

## 9. Audit (`audit.events`)
Bảng được partition theo **range tháng** trên `occurred_at` ngay từ đầu. Các cột: `tenant_id`, `id` (UUIDv7), `occurred_at`, `actor_type` (`user` | `application` | `operator` | `system`), `actor_id`, `action` (tên có chấm, ví dụ `user.login.succeeded`, `user.login.failed`, `mfa.enrolled`, `relationship.created`, `model.updated`, `api_key.revoked`), `target_type`, `target_id`, `outcome` (`success` | `failure`), `ip inet`, `user_agent`, `request_id`, `metadata jsonb` (**không bao giờ chứa secret**). Khóa chính `(tenant_id, occurred_at, id)`, có chỉ mục `(tenant_id, action, occurred_at DESC)`.

Có RLS như mọi bảng khác. `rbac_app` chỉ được `INSERT` và `SELECT`, không được `UPDATE`/`DELETE`, nên audit chỉ ghi thêm. Mặc định giữ **12 tháng** **[Quyết định thiết kế]**; xóa bằng cách drop partition cũ.

## 10. Bảo trì
- Hàm `util.ensure_monthly_partitions(table, months_ahead)` tạo trước partition cho 3 tháng tới, và `util.drop_partitions_older_than(table, interval)`. Cách gọi định kỳ (pg_cron trên Supabase, hoặc job Spring/Spring Batch) do người xây dựng chọn. Migration đầu tiên tạo sẵn partition cho tháng hiện tại và 3 tháng sau, cộng một partition `DEFAULT` để không bao giờ lỗi insert.
- Job dọn dẹp xóa: session/refresh token đã hết hạn quá 30 ngày, `one_time_tokens` đã hết hạn hoặc đã dùng, tuple có `expires_at` đã qua.

## 11. Lộ trình mở rộng (không làm bây giờ, nhưng thiết kế không cản đường)
1. Khi lên tới hàng triệu người dùng: thêm read replica của Supabase cho luồng kiểm tra quyền, và cache Redis theo `(tenant, revision, changelog seq)`.
2. Khi `relationships` vượt khoảng vài trăm triệu dòng: hash-partition theo `tenant_id`. Khóa chính đã có `tenant_id` nên không phải đổi khóa, nhưng cần copy dữ liệu sang bảng mới.
3. Khi vượt khả năng của một Postgres: chuyển sang Postgres tự quản có Citus, phân tán theo `tenant_id`. Đổi engine phân quyền sang SpiceDB cũng là một lối thoát, vì mô hình ở §7 ánh xạ 1-1 sang schema của SpiceDB.

## 12. Kiểm thử (database)
Dùng Testcontainers với Postgres **cùng major version với Supabase**, chạy toàn bộ migration Flyway rồi kiểm tra:
- Kết nối bằng `rbac_app` với tenant A thì không đọc, sửa hay xóa được bất kỳ dòng nào của tenant B, trên **mọi** bảng thuộc tenant. Viết một test sinh tự động cho từng bảng có cột `tenant_id`. Khi chưa đặt `app.tenant_id` thì đọc ra 0 dòng.
- Khóa ngoại ghép chặn được việc trỏ chéo tenant (ví dụ session của tenant A trỏ tới user của tenant B).
- Email trùng trong cùng tenant thì bị từ chối; cùng email ở hai tenant thì được chấp nhận; email của user đã xóa mềm thì được dùng lại.
- Trigger của tuple: từ chối khi `relation` là `permission`, khi chủ thể không hợp lệ, khi relation lệch loại đối tượng.
- Trigger seed: tạo tenant mới thì có đủ các loại dựng sẵn.
- `tenancy.create_tenant` tạo đủ tenant, admin `invited`, tuple `admin` và token mời trong một transaction; nếu một bước lỗi thì không để lại gì. `rbac_app` không gọi được hàm này và không INSERT được vào `tenancy.tenants`.
- Tenant `platform` và tài khoản vận hành đầu tiên tồn tại sau khi chạy migration và bootstrap.
- `permission_rules.effect` chỉ nhận `allow` hoặc `deny`; một luật phải trỏ tới relation có `kind = 'permission'`.
- Changelog ghi đúng mỗi khi insert/delete tuple; `authz_model_revision` tăng khi sửa mô hình.
- `audit.events`: `rbac_app` không UPDATE hay DELETE được; insert vào tháng chưa có partition thì rơi vào `DEFAULT`.
- Các schema không truy cập được từ role `anon` và `authenticated`.

## 13. Phần để lại cho người xây dựng
- Độ dài cột, chi tiết `CHECK`, `fillfactor`, và chỉ mục phụ ngoài các chỉ mục đã nêu.
- Cách chia các file Flyway (gợi ý: extensions và schema → `tenancy` → `identity` → `apps` → `authz` → `audit` → RLS và grant → seed tenant `platform`).
- Sinh UUIDv7 bằng database hay bằng Java (tùy phiên bản Postgres).
- Thư viện mã hóa envelope và nơi giữ KEK cho môi trường dev.
- Cách lập lịch các job partition và dọn dẹp.
- Thư viện truy cập dữ liệu phía Java.
