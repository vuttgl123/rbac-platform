# Thiết kế API backend — RBAC Platform

- Ngày: 2026-11-02
- Phạm vi: API HTTP của ba service (`identity`, `authz`, `audit`), cách xác thực giữa người gọi và service, giữa service với service, và thứ tự làm theo từng lát. Giao diện (FE) làm sau, nhưng API phải đủ để FE dùng.
- Dựa trên: `2026-10-26-rbac-platform-database-design.md` (DB), `2026-10-27-platform-foundation-modules-design.md` (module, role DB theo service), `2026-10-27-async-foundation-design.md` (outbox, batch).
- Quy ước đánh dấu: **[Chủ dự án]** = chủ dự án đã quyết định; **[Quyết định thiết kế]** = người viết tự chọn, chủ dự án có thể phản đối. Chủ dự án đã nói "làm theo đề xuất của bạn" cho toàn bộ hướng đi ở đây, nhưng chưa duyệt từng chi tiết.

---

## 1. Mục đích và lý do

DB, các thư viện nền (log, outbox, batch) và khung ba service đã xong, nhưng chưa có endpoint nào ngoài `/actuator/health`. FE chỉ là trang mẫu của Vite. Chủ dự án muốn **thiết kế API backend trước, giao diện để sau**. **[Chủ dự án]**

Nền tảng có hai nhóm người gọi:

1. **Người quản trị** (sau này qua UI admin): người vận hành tạo tenant; admin của tenant quản lý user, nhóm, vai trò, mô hình phân quyền, xem audit.
2. **Hệ thống của tenant** (CRM, app nội bộ...): hỏi "người này là ai?" (đăng nhập, token) và "người này có được làm X trên Y không?" (kiểm tra quyền). Đường này là đường nóng, cần nhanh.

## 2. Mục tiêu, không làm, phản mục tiêu

### Mục tiêu
1. Danh mục API đầy đủ cho cả ba service, chia thành các **lát** (§11). Mỗi lát chạy được từ đầu đến cuối.
2. **Lát 0** dựng phần core dùng chung trước (lỗi, phân trang, xác thực JWT, gọi service khác, mã hóa, chạy local); chưa có tính năng nghiệp vụ. **[Chủ dự án]** **Lát 1** là một luồng xuyên suốt: người vận hành đăng nhập (có MFA) → tạo tenant → admin của tenant nhận lời mời, đặt mật khẩu → đăng nhập nhận JWT → gọi kiểm tra quyền → thấy sự kiện trong audit. **[Chủ dự án đồng ý với đề xuất]**
3. Một cách xác thực chung cho cả ba service: JWT do identity ký, service khác tự kiểm qua JWKS, không gọi sang identity mỗi request.
4. API quản trị được bảo vệ **bằng chính engine phân quyền của nền tảng** (dogfooding), không có bảng quyền riêng.
5. Nhanh: đường kiểm tra quyền không gọi sang service khác; truy vấn SQL viết tay bằng `JdbcClient`, ít vòng đi về DB nhất có thể.

### Không làm (trong tài liệu này)
- Giao diện FE, SDK cho tenant.
- OAuth authorization code + PKCE cho người dùng của hệ thống tenant, SSO OIDC, SAML, SCIM: có trong danh mục (lát 4–5) nhưng chỉ ở mức đường dẫn, chưa thiết kế chi tiết.
- Redis, Kafka, Elasticsearch: máy chưa có Docker. Mọi thứ lát 1 cần phải chạy được với Postgres và các thư viện hiện có.
- Gửi email thật. Lời mời và đặt lại mật khẩu được ghi ra log và trả về cho người vận hành (§6.8).

### Phản mục tiêu
- Có bất kỳ đường nào để token, URL hay tham số của tenant A đọc hoặc ghi dữ liệu tenant B.
- Tenant được lấy từ URL hay body ở API quản trị. Tenant **chỉ** đến từ token (trừ các endpoint đăng nhập, nơi chưa có token và tenant đến từ `slug`).
- Thông báo lỗi đăng nhập cho biết email có tồn tại hay không.
- Một service đọc hay ghi schema của service khác ngoài các ngoại lệ đã ghi trong V11 và §3.
- Đường kiểm tra quyền gọi HTTP sang service khác.

## 3. Ràng buộc đã có trong code (người xây dựng phải tôn trọng)

| Ràng buộc | Nguồn | Hệ quả cho API |
|---|---|---|
| Mỗi service đăng nhập DB bằng role riêng, chỉ có quyền trên schema của mình: `identity_app` → `tenancy`, `identity`, `apps`; `authz_app` → `authz`; `audit_app` → `audit` | V11 | Mỗi service chỉ phục vụ API trên schema của nó. Identity không ghi được `authz.relationships`; muốn gán vai trò phải gọi authz |
| `tenancy.create_tenant` chỉ `rbac_admin` được `EXECUTE` | V9 | identity-service cần **thêm một pool nhỏ đăng nhập bằng `rbac_admin`**, chỉ dùng cho API của người vận hành (§6.7) **[Quyết định thiết kế]** |
| Chỉ `audit_app` ghi được `audit.events` | V8, V11 | Sự kiện từ identity và authz phải **chuyển sang audit-service** (§8) |
| `authz_app` chỉ đọc được `(tenant_id, id)` của `identity.users` | V11 | Engine không biết user bị khóa hay xóa mềm, trái với DB spec §7.3. Cần cấp thêm `SELECT (status, deleted_at)` (§7.4) |
| Tenant `platform` bắt buộc MFA (`mfa_min = 'required'`) | V10 | Người vận hành không đăng nhập được nếu chưa có TOTP, nên **MFA TOTP thuộc lát 1** |
| Tài khoản vận hành đầu tiên ở trạng thái `invited`, không có token mời | V10 | Cần lệnh bootstrap phát lời mời cho người vận hành (§6.7) |
| `TenantScope.call(tenantId, work)` tự mở transaction và đặt `app.tenant_id` | lib-db | Mọi handler API quản trị chạy công việc DB trong `TenantScope.call(caller.tenantId(), ...)` |
| `OutboxWriter.append` ghi sự kiện trong cùng transaction của thay đổi | lib-outbox | Sự kiện audit được ghi cùng transaction với thay đổi, không mất khi service chết |
| Dữ liệu truy cập bằng `JdbcClient` + SQL viết tay, không ORM | Chủ dự án | Áp dụng cho mọi endpoint |
| Mỗi `Test<Svc>ServiceApplication` tự bật một embedded Postgres riêng | test-support | Ba service chạy local không dùng chung dữ liệu, nên lát 1 cần một DB dev dùng chung (§10) |
| `SecurityConfig` hiện dùng `httpBasic` và không có user | các service | Thay bằng resource server JWT. Bỏ `httpBasic` để 401 không kèm `WWW-Authenticate: Basic` (trình duyệt sẽ bật hộp thoại đăng nhập) |

## 4. Tổng quan

```
                    ┌──────────────────────── identity-service :8081 ───────────────────────┐
 Người dùng/FE ───> │ /t/{slug}/auth/*   đăng nhập, MFA, refresh, lời mời                     │
                    │ /t/{slug}/.well-known/{openid-configuration,jwks.json}  (công khai)    │
                    │ /v1/me, /v1/users, /v1/groups, /v1/sessions ...   (JWT)                │
                    │ /v1/platform/tenants                              (JWT người vận hành) │
                    └───────┬──────────────────────────────┬─────────────────────────────────┘
                            │ POST /v1/check (chuyển tiếp    │ outbox → HTTP
                            │ JWT của người gọi)             ▼
 Hệ thống tenant ─────> ┌── authz-service :8082 ──┐     ┌── audit-service :8083 ────────┐
   (JWT/API key)        │ /v1/check, /v1/check/batch│ ──> │ /internal/v1/events (nhận)    │
                        │ /v1/authz/model, /v1/relationships│ GET /v1/audit/events (JWT)    │
                        └─────────────────────────┘     └───────────────────────────────┘
```

- **Một issuer cho mỗi tenant**: `{IDENTITY_PUBLIC_URL}/t/{slug}`, khớp với DB spec (§5.1 slug dùng trong issuer, §8 mỗi tenant một bộ khóa ký).
- Mọi service là **resource server**: nhận `Authorization: Bearer <JWT>`, kiểm chữ ký bằng JWKS của đúng tenant trong `iss`.

## 5. Quy ước chung của API **[Quyết định thiết kế]**

- **Phiên bản**: tiền tố `/v1`. Riêng nhóm đăng nhập và `.well-known` nằm dưới `/t/{slug}/...` vì đó là issuer, không đổi theo phiên bản.
- **JSON**: `camelCase`. Ngoại lệ: các endpoint chuẩn OAuth/OIDC sau này (`/oauth2/token`, discovery) dùng đúng tên của RFC (`access_token`, `token_type`...).
- **ID**: UUID dạng chuỗi. **Thời gian**: ISO-8601, UTC, có `Z`.
- **Lỗi**: `application/problem+json` (RFC 9457), mở rộng từ `RequestIdErrorAttributes` của lib-core:
  ```json
  { "type": "about:blank", "title": "Invalid request", "status": 400,
    "code": "validation_failed", "detail": "...", "requestId": "…",
    "errors": [ { "field": "email", "code": "invalid_format" } ] }
  ```
  `code` là chuỗi ổn định để FE và hệ thống tenant rẽ nhánh; `title`/`detail` chỉ để đọc. Danh sách mã ở §9.
- **Phân trang**: cursor, không dùng offset. Request `?limit=50&cursor=…` (`limit` 1–200, mặc định 50). Response `{ "items": [...], "nextCursor": "…" | null }`. Cursor là base64url của khóa sắp xếp cuối cùng (ví dụ `(created_at, id)`), người gọi coi như chuỗi mờ. Lý do: offset chậm dần khi trang sâu; UUIDv7 và khóa sắp xếp có chỉ mục nên trang nào cũng nhanh như nhau.
- **Mã trạng thái**: `200` đọc/cập nhật, `201` tạo (kèm `Location`), `204` xóa hoặc thao tác không có body, `400` sai dữ liệu, `401` thiếu hoặc sai token, `403` có token nhưng không đủ quyền, `404` không có (kể cả khi bản ghi thuộc tenant khác: không bao giờ trả `403` cho bản ghi tenant khác), `409` trùng hoặc xung đột phiên bản, `429` quá giới hạn (sau này), `503` phụ thuộc không sẵn sàng.
- **Request id**: mọi response có header `X-Request-Id` (đã có trong `RequestLoggingFilter`). Gọi service khác thì chuyển tiếp header này.

## 6. identity-service: định danh và token

### 6.1 Khóa ký và token **[Quyết định thiết kế]**

- **Thuật toán**: ES256 (khóa nhỏ, ký và kiểm nhanh, thư viện Nimbus trong Spring Security hỗ trợ sẵn).
- **Khóa ký** nằm ở `apps.signing_keys`, mỗi tenant một khóa `active`. Private key được mã hóa AES-256-GCM bằng **KEK** đọc từ biến môi trường `RBAC_KEK` (base64 của 32 byte); `encryption_key_id = 'env-1'`. Khóa được tạo **lười**: lần đầu cần ký cho một tenant chưa có khóa `active` thì tạo một khóa. Hai request cùng lúc sẽ va vào unique index một phần (`status = 'active'`); bên thua đọc lại khóa vừa được tạo. Xoay khóa để sang lát 5.
- **Access token** (JWT, header `typ: at+jwt`, `kid` = id khóa):

  | Claim | Giá trị |
  |---|---|
  | `iss` | `{IDENTITY_PUBLIC_URL}/t/{slug}` |
  | `sub` | `users.id` |
  | `tid` | `tenants.id` |
  | `sid` | `sessions.id` |
  | `amr` | ví dụ `["pwd","otp"]` |
  | `aud` | `rbac-api` |
  | `iat`, `exp`, `jti` | thời hạn **10 phút** (cấu hình `rbac.identity.access-token-ttl`) |

  Token không chứa vai trò hay quyền. Quyền luôn hỏi engine, nên đổi quyền có hiệu lực ngay chứ không đợi token hết hạn.
- **Refresh token**: chuỗi mờ `rbr_<base64url 32 byte>`, lưu SHA-256 ở `identity.refresh_tokens`. **Xoay vòng mỗi lần dùng**: dùng token cũ đã xoay thì thu hồi cả `family_id` và session (dấu hiệu bị đánh cắp). Thời hạn theo `settings.session.absolute_ttl` (mặc định 30 ngày) và `settings.session.idle_ttl` (mặc định 7 ngày).
- **Hệ quả cần chấp nhận**: đăng xuất hay thu hồi session chặn refresh ngay, nhưng access token đã phát vẫn dùng được tới khi hết hạn (tối đa 10 phút). Đây là đánh đổi để service khác không phải gọi identity mỗi request.
- **Challenge token** cho các bước đăng nhập nhiều bước: JWT ký bằng cùng khóa, `typ: challenge+jwt`, `aud: rbac-login`, sống **5 phút**, claim `sub`, `tid`, `step`, `amr` đã đạt. Resource server từ chối nó vì sai `typ` và `aud`. Không lưu trạng thái (chưa có Redis). Có thể dùng lại trong 5 phút, nhưng mỗi bước sau vẫn đòi đúng yếu tố (mã TOTP, mật khẩu mới), nên rủi ro thấp.

### 6.2 Luồng đăng nhập

Mọi endpoint dưới đây công khai (không cần JWT), tenant xác định bằng `slug`. Tenant không tồn tại hoặc không `active` trả `404 tenant_not_found`.

Mọi bước trả về cùng một kiểu `LoginResult`:

```json
{ "status": "authenticated",
  "accessToken": "…", "tokenType": "Bearer", "expiresIn": 600,
  "refreshToken": "rbr_…", "refreshExpiresIn": 2592000 }

{ "status": "password_change_required", "challengeToken": "…" }
{ "status": "mfa_enrollment_required",  "challengeToken": "…" }
{ "status": "mfa_required",             "challengeToken": "…", "methods": ["totp", "recovery_code"] }
```

Thứ tự kiểm tra sau khi mật khẩu đúng: `must_change` → MFA (tenant `required` mà user chưa có factor đã xác nhận ⇒ `mfa_enrollment_required`; có factor ⇒ `mfa_required`) → phát token. Mức MFA hiệu lực là mức cao hơn giữa `settings.mfa.required` và `mfa_min`.

| Method | Đường dẫn | Body | Trả về |
|---|---|---|---|
| POST | `/t/{slug}/auth/login` | `{ identifier, password }` (`identifier` là email hoặc username) | `LoginResult` |
| POST | `/t/{slug}/auth/password/change` | `{ challengeToken, newPassword }` | `LoginResult` (bước tiếp theo) |
| POST | `/t/{slug}/auth/mfa/totp/enroll` | `{ challengeToken }` | `{ factorId, secret, otpauthUri }` |
| POST | `/t/{slug}/auth/mfa/totp/confirm` | `{ challengeToken, factorId, code }` | `LoginResult` kèm `recoveryCodes: [10 mã]`, chỉ hiện **một lần** |
| POST | `/t/{slug}/auth/mfa/verify` | `{ challengeToken, code }` hoặc `{ challengeToken, recoveryCode }` | `LoginResult` |
| POST | `/t/{slug}/auth/token/refresh` | `{ refreshToken }` | `LoginResult` (`authenticated`) |
| POST | `/t/{slug}/auth/logout` | `{ refreshToken }` | `204`, thu hồi session |
| GET | `/t/{slug}/auth/invitations/{token}` | | `{ email, tenantName, expiresAt }` để FE hiện form |
| POST | `/t/{slug}/auth/invitations/accept` | `{ token, password, displayName? }` | `LoginResult` |

Quy tắc:
- **Lỗi đăng nhập luôn là `401 invalid_credentials`**, dù email không tồn tại, sai mật khẩu, user `invited`/`locked`/`disabled` hay đang bị khóa tạm. Khi email không tồn tại vẫn chạy băm Argon2 với một hash giả để thời gian phản hồi như nhau. Lý do thật được ghi vào audit.
- **Khóa tạm**: sai quá `settings.lockout.max_attempts` (mặc định 10) thì đặt `locked_until = now() + lockout.duration` (mặc định 15 phút). Đăng nhập thành công thì đặt `failed_login_count` về 0. Đếm nằm ở DB (chưa có Redis).
- **Mật khẩu**: Argon2id (chuỗi PHC; m = 19 MiB, t = 2, p = 1 theo OWASP), dùng `Argon2PasswordEncoder` của Spring Security (cần BouncyCastle). Độ dài tối thiểu `settings.password.min_length` (mặc định 12), không trùng `password.history_count` mật khẩu gần nhất (mặc định 5).
- **TOTP**: RFC 6238, SHA-1, 6 số, bước 30 giây, chấp nhận lệch ±1 bước. `last_used_step` chặn dùng lại cùng một mã. Secret được mã hóa bằng KEK. Mã khôi phục: 10 mã, mỗi mã 10 ký tự, lưu SHA-256, mỗi mã dùng một lần.
- **Nhận lời mời**: tìm `one_time_tokens` theo `token_hash`, `purpose = 'invitation'`, chưa dùng, chưa hết hạn. Trong một transaction: đặt mật khẩu, `status = 'active'`, `email_verified_at = now()`, đánh dấu token đã dùng. Sau đó đi tiếp như đăng nhập thành công bằng mật khẩu (thường sẽ ra `mfa_enrollment_required` với tenant `platform`).
- Đăng nhập thành công: tạo `identity.sessions` (`amr`, `ip`, `user_agent`, `idle_expires_at`, `expires_at`) và một `refresh_tokens`, cập nhật `last_login_at`, ghi sự kiện audit. Tất cả trong **một** transaction.

### 6.3 Discovery và JWKS (công khai)

| Method | Đường dẫn | Trả về |
|---|---|---|
| GET | `/t/{slug}/.well-known/openid-configuration` | Tối thiểu `issuer`, `jwks_uri`; thêm `token_endpoint`, `authorization_endpoint` ở lát 4 |
| GET | `/t/{slug}/.well-known/jwks.json` | Khóa công khai `next`, `active`, và `retired` còn trong thời gian ân hạn. Header `Cache-Control: max-age=300` |

### 6.4 Resource server dùng chung (cả ba service)

- Thêm `spring-boot-starter-oauth2-resource-server`. Một cấu hình bảo mật dùng chung đặt trong lib mới **`lib-security`** (service phụ thuộc lib, đúng quy ước). **[Quyết định thiết kế]**
- Tin cậy issuer khi `iss` bắt đầu bằng `{IDENTITY_PUBLIC_URL}/t/` (cấu hình `rbac.security.issuer-prefix`). Mỗi issuer có một decoder riêng, được cache, lấy JWKS qua discovery (`JwtIssuerAuthenticationManagerResolver` hoặc tương đương).
- Kiểm tra `aud = rbac-api`, `typ = at+jwt`, `exp`, và **tenant còn `active`** (đọc `tenancy.tenants`, role nào cũng đọc được; cache 30 giây).
- Kết quả là một `Caller` cho controller: `tenantId`, `subjectType` (`user`; `application` từ lát 4), `subjectId`, `sessionId`, `amr`, cùng token gốc để chuyển tiếp.
- Đường công khai: `/actuator/health`, `/t/*/auth/**`, `/t/*/.well-known/**`. Đường `/internal/**` dùng cơ chế riêng (§8).

### 6.5 Phân quyền cho API quản trị (dogfooding) **[Quyết định thiết kế]**

- Endpoint quản trị khai báo quyền nó cần dưới dạng `relation` trên đối tượng `tenant:<caller.tenantId>`. Lát 1 và 2 dùng vai trò dựng sẵn: **`admin`** cho mọi thao tác quản trị tenant; **`operator`** trên `tenant:<platform>` cho API người vận hành. Sau này có thể thêm quyền dựng sẵn chi tiết hơn (`manage_users`, `view_audit`...) mà không đổi API.
- identity-service và audit-service hỏi bằng `POST {AUTHZ_URL}/v1/check` và **chuyển tiếp nguyên JWT của người gọi**, để trống `subject` (nghĩa là "chính tôi", §7.1). Như vậy không cần credential riêng giữa service, và authz tự kiểm token như mọi request khác.
- authz-service tự kiểm cho mình bằng lời gọi trong process, không qua HTTP.
- Lát 1 không cache kết quả kiểm tra quyền ở bên gọi (API quản trị không phải đường nóng, và cache sẽ làm chậm việc thu hồi quyền). Nếu sau này cần, cache theo `(tid, sub, relation, object)` tối đa 5 giây.
- authz không trả lời được: `503 authz_unavailable`, **không bao giờ cho qua** (fail closed).

### 6.6 API "tôi"

| Method | Đường dẫn | Trả về |
|---|---|---|
| GET | `/v1/me` | `{ id, tenant: { id, slug, name }, email, displayName, amr, mfaEnrolled }`. Chỉ cần JWT hợp lệ, không cần quyền |

### 6.7 Người vận hành và bootstrap

| Method | Đường dẫn | Quyền | Body / trả về |
|---|---|---|---|
| POST | `/v1/platform/tenants` | `operator` trên `tenant:<platform>`, và `amr` của phiên **phải có `otp`** | `{ slug, name, adminEmail }` → `201 { tenantId, adminUserId, invitation: { url, expiresAt } }` |
| GET | `/v1/platform/tenants` | như trên | danh sách có phân trang: `{ id, slug, name, status, createdAt }` |

- Tạo tenant: identity sinh token mời (32 byte ngẫu nhiên), băm SHA-256 rồi gọi `tenancy.create_tenant(slug, name, adminEmail, hash, now()+7 ngày, actorId)` **qua pool `rbac_admin`**. Pool này chỉ dùng ở đây, kích thước 2, cấu hình `OPERATOR_DB_USER` / `OPERATOR_DB_PASSWORD`. `slug` theo `^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$`, slug trùng trả `409 slug_taken`. Hàm đã tự ghi audit `tenant.created`.
- **Lời mời trả thẳng trong response** cho người vận hành, đồng thời ghi sự kiện `invitation.created` vào outbox (sau này một handler gửi email). Link có dạng `{FRONTEND_URL}/t/{slug}/invite?token=…`.
- **Bootstrap người vận hành đầu tiên**: chạy identity-service với `--rbac.bootstrap.operator-invitation=true`. Lệnh này tạo token mời cho tài khoản vận hành đang `invited` của tenant `platform` (dùng `identity_app` trong `TenantScope` của platform), in link ra stdout rồi thoát. Nếu tài khoản không ở trạng thái `invited` thì từ chối. Tài khoản vận hành trên Supabase đã có mật khẩu kèm `must_change = true`; đăng nhập bình thường sẽ ra `password_change_required` rồi `mfa_enrollment_required`, nên không cần bootstrap.

### 6.8 Sự kiện audit của identity

Ghi vào outbox trong cùng transaction với thay đổi: `user.login.succeeded`, `user.login.failed` (kèm `reason` trong metadata: `unknown_identifier`, `bad_password`, `locked`, `not_active`), `user.locked`, `user.password.changed`, `mfa.enrolled`, `mfa.verify.failed`, `mfa.recovery_code.used`, `session.revoked`, `refresh_token.reuse_detected`, `invitation.created`, `invitation.accepted`. Đăng nhập thất bại vẫn mở transaction trong tenant (để tăng `failed_login_count`), nên luôn có chỗ để ghi outbox. Payload theo hợp đồng ở §8.

## 7. authz-service: kiểm tra quyền

### 7.1 Kiểm tra (đường nóng)

`POST /v1/check`

```json
{ "subject":    { "type": "user", "id": "0192…" , "relation": null },
  "permission": "edit",
  "object":     { "type": "document", "id": "123" },
  "context":    { "amount": 30000000 } }
```

```json
{ "result": "allowed" | "denied" | "conditional",
  "missingContext": ["amount"],
  "modelRevision": 42 }
```

- `subject` bỏ trống nghĩa là **chính người gọi** (`user:<sub>`). Kiểm tra cho một chủ thể khác cần quyền `admin` trên tenant (lát 1); từ lát 4, ứng dụng có scope `authz:check` cũng được phép. Lý do: không để một user dò quyền của user khác.
- `permission` có thể là **bất kỳ relation nào** của loại đối tượng (`permission`, `role` hay `relationship`), giống SpiceDB. Nhờ vậy kiểm được trực tiếp vai trò, ví dụ `tenant#admin`.
- Loại đối tượng hay relation không tồn tại: `400 unknown_resource_type` / `unknown_relation`. Đây không phải `denied`, để lỗi gõ sai không bị giấu đi.
- `POST /v1/check/batch` với `{ "checks": [ ... ] }` (tối đa 100) trả `{ "results": [ ... ] }` đúng thứ tự. Dùng cho FE ẩn hoặc hiện nút theo quyền. Một lần đọc mô hình, các truy vấn tuple gộp theo từng mức.

### 7.2 Engine đánh giá

Ngữ nghĩa đã chốt ở DB spec §7: luật `computed` / `via` / `tenant`, `allow` trừ `deny` (cấm luôn thắng), userset (`group#member`), wildcard `user:*`, giới hạn độ sâu 25, phát hiện chu trình, bỏ qua tuple đã hết hạn.

Yêu cầu hiệu năng **[Quyết định thiết kế]**:
- Mô hình đã biên dịch được cache trong bộ nhớ theo `(tenant_id, authz_model_revision)`. Đọc `revision` cùng lúc với tuple đầu tiên, nên mô hình đổi là cache tự hết hiệu lực.
- Đánh giá theo **mức (BFS)**: mỗi mức của đồ thị là **một** truy vấn SQL gom mọi đối tượng hoặc tập cần mở rộng ở mức đó (`= ANY(?)`), dùng chỉ mục thuận hoặc ngược đã có (DB spec §7.3). Không truy vấn từng tuple một.
- Mỗi request kiểm tra chạy trong **một** transaction chỉ đọc của tenant (`TenantScope`).
- Mục tiêu: p99 dưới 10 ms cho đồ thị sâu tới 5 mức trên dữ liệu thử 100.000 tuple, khi mô hình đã nằm trong cache. Có test kiểm tra kế hoạch truy vấn (EXPLAIN) dùng chỉ mục, không quét bảng.
- **Điều kiện (CEL)** làm ở lát 3. Trước lát 3 không có API nào tạo được điều kiện, nhưng nếu engine gặp một tuple hay luật có điều kiện thì phải trả `denied` và ghi log WARN (fail closed), không được bỏ qua điều kiện.

### 7.3 Mô hình và quan hệ (lát 2–3, ở mức đường dẫn)

| Method | Đường dẫn | Lát | Ghi chú |
|---|---|---|---|
| POST | `/v1/relationships:write` | 2 | `{ writes: [tuple…], deletes: [filter…] }` trong một transaction, tối đa 500 thao tác. Trả `{ revision }` (`seq` của changelog) để dùng làm consistency token sau này |
| GET | `/v1/relationships` | 2 | Lọc theo `objectType`, `objectId`, `relation`, `subjectType`, `subjectId`; phân trang |
| GET | `/v1/tenant/roles` | 2 | Tiện ích cho UI: vai trò cấp tenant và thành viên. Thực chất là đọc tuple trên `tenant:<tid>` |
| GET | `/v1/authz/model` | 3 | Toàn bộ mô hình dưới dạng **một tài liệu** (loại đối tượng, relation, subject type, luật, điều kiện), kèm `ETag: <revision>` |
| PUT | `/v1/authz/model` | 3 | Thay cả tài liệu, cần `If-Match: <revision>` (sai thì `409 model_revision_conflict`). Kiểm tra hợp lệ trước khi lưu: luật trỏ đúng loại, `deny` của P không phụ thuộc vào chính P, không xóa relation còn tuple |
| POST | `/v1/authz/model:validate` | 3 | Chỉ kiểm tra, không lưu |
| POST | `/v1/lookup/resources`, `/v1/lookup/subjects` | 3 | "user này `view` được những `document` nào", "ai `edit` được `document:123`" |

Lý do sửa mô hình **theo cả tài liệu** thay vì CRUD từng bảng: mô hình chỉ hợp lệ khi xét tổng thể (luật trỏ chéo, chu trình `deny`), và mỗi lần lưu tăng `revision` một lần. Cách này giống SpiceDB/OpenFGA, nên UI cũng có thể soạn mô hình rồi lưu một lần.

Ghi tuple gán vai trò cho user, group hay application **của nền tảng**: authz đã có trigger `validate_relationship` kiểm tra thực thể tồn tại (V11, ngoại lệ 1), nên identity không cần tham gia.

### 7.4 Thay đổi DB cần cho authz

Cấp thêm `SELECT (status, deleted_at) ON identity.users TO authz_app`. Engine loại user có `status <> 'active'` hoặc `deleted_at` khác null khỏi kết quả, theo DB spec §7.3. Hai cột này không nhạy cảm như email hay hash. Nếu V11 vẫn chưa áp lên Supabase lúc làm thì sửa thẳng V11, ngược lại thì thêm V14. **[Quyết định thiết kế]**

## 8. Chuyển sự kiện giữa các service (audit) **[Quyết định thiết kế]**

Hiện outbox chỉ có publisher `log` và `in-process`. Chưa có Kafka, nên lát 1 thêm publisher **`http`** vào lib-outbox:

- identity và authz đặt `rbac.outbox.publisher: http` và `rbac.outbox.http.url: {AUDIT_URL}/internal/v1/events`. Relay gửi theo lô (tối đa `batch-size` sự kiện mỗi request). Cơ chế thử lại và backoff đã có sẵn trong relay. Audit-service tắt thì sự kiện chờ trong outbox, không mất.
- `POST /internal/v1/events`, body `{ "events": [EventEnvelope…] }`, trả `200 { "accepted": [ids] }`. Xác thực bằng header `X-Internal-Token` so với biến môi trường `RBAC_INTERNAL_TOKEN` (so sánh thời gian hằng). Đây là giải pháp tạm; sẽ thay bằng mTLS hoặc Kafka khi có hạ tầng.
- audit-service ghi từng sự kiện bằng `audit_app` trong `TenantScope` của `tenantId` trong envelope: `INSERT … ON CONFLICT DO NOTHING` trên khóa chính `(tenant_id, occurred_at, id)`, với `id` và `occurred_at` lấy từ envelope. Vì vậy giao lại nhiều lần vẫn chỉ có một dòng (outbox giao ít nhất một lần).
- Hợp đồng `payload` cho sự kiện audit: `{ action, actor: { type, id }, target: { type, id }, outcome, ip, userAgent, requestId, metadata }`. `action` = `eventType`. **`metadata` không bao giờ chứa secret.** Sự kiện có `eventType` mà audit không biết vẫn được ghi; payload thiếu `action` thì là `NonRetryableEventException` (bị chôn, không thử lại mãi).
- Đổi publisher sang Kafka sau này chỉ là đổi cấu hình và thêm một publisher; hợp đồng envelope giữ nguyên.

## 9. audit-service

| Method | Đường dẫn | Quyền | Ghi chú |
|---|---|---|---|
| POST | `/internal/v1/events` | `X-Internal-Token` | §8 |
| GET | `/v1/audit/events` | `admin` trên tenant | Lọc `action` (khớp đúng hoặc tiền tố `user.login.*`), `actorId`, `targetType`, `targetId`, `outcome`, `from`, `to`. Sắp `occurred_at DESC, id DESC`, cursor là `(occurred_at, id)` |

`from`/`to` mặc định là 7 ngày gần nhất, khoảng tối đa 31 ngày (vượt thì `400 range_too_large`). Lý do: bảng partition theo tháng, nên khoảng ngắn chỉ chạm 1–2 partition và luôn đi qua chỉ mục `(tenant_id, action, occurred_at DESC)`.

## 10. Mã lỗi và cấu hình

### Mã lỗi (`code`)
`validation_failed`, `unauthenticated`, `token_expired`, `forbidden`, `mfa_step_up_required`, `not_found`, `tenant_not_found`, `invalid_credentials`, `invalid_challenge`, `invalid_mfa_code`, `password_policy_violation`, `invitation_invalid`, `refresh_token_invalid`, `slug_taken`, `email_taken`, `unknown_resource_type`, `unknown_relation`, `model_revision_conflict`, `range_too_large`, `authz_unavailable`, `internal_error`.

### Biến môi trường mới

| Biến | Dùng bởi | Ý nghĩa |
|---|---|---|
| `IDENTITY_PUBLIC_URL` | identity (issuer), mọi service (`issuer-prefix`) | URL công khai của identity, ví dụ `http://localhost:8081` |
| `FRONTEND_URL` | identity | Gốc của link mời |
| `RBAC_KEK` | identity | KEK base64 32 byte cho private key và TOTP secret. Dev: sinh bằng `openssl rand -base64 32` |
| `OPERATOR_DB_USER` / `OPERATOR_DB_PASSWORD` | identity | Đăng nhập `rbac_admin` cho §6.7 |
| `AUTHZ_URL` | identity, audit | Gọi `/v1/check` |
| `AUDIT_URL` | identity, authz | Đích của publisher `http` |
| `RBAC_INTERNAL_TOKEN` | identity, authz, audit | Bí mật chung cho `/internal/**` |

## 11. Thứ tự làm theo lát

| Lát | Nội dung | Xong khi |
|---|---|---|
| **0. Core (nền chung)** | Mọi thứ dùng chung, chưa có tính năng nghiệp vụ, xem §11.1. **[Chủ dự án]** muốn làm core trước | Ba service khởi động trên DB dev dùng chung qua `scripts/dev.sh`; endpoint mẫu trong test chứng minh từng phần của core |
| **1. Luồng xuyên suốt** | identity: khóa ký, JWKS và discovery, đăng nhập, đổi mật khẩu, TOTP và mã khôi phục, refresh/logout, lời mời, `/v1/me`, API người vận hành, lệnh bootstrap; authz: engine và `/v1/check`, `/v1/check/batch`, quyền đọc `status`/`deleted_at` (§7.4); audit: `/internal/v1/events`, `GET /v1/audit/events` | Kịch bản ở §12 chạy được trên `scripts/dev.sh` |
| 2. Quản trị tenant | Users (mời, sửa, khóa, xóa mềm), Groups, thành viên nhóm và vai trò cấp tenant (`/v1/relationships:write`), Sessions (xem, thu hồi), đặt lại mật khẩu, quản lý MFA của chính mình | Admin tenant quản lý được người và vai trò |
| 3. Mô hình phân quyền | `/v1/authz/model` (GET/PUT/validate), điều kiện CEL (`cel-java`), lookup | Tenant tự định nghĩa `document`/`folder` và luật cấm như ví dụ DB spec §7.4 |
| 4. Ứng dụng tích hợp | Applications, client secret, `client_credentials`, API key, OAuth authorization code + PKCE, check bằng ứng dụng | Hệ thống của tenant đăng nhập người dùng và kiểm tra quyền bằng credential của nó |
| 5. Mở rộng | SSO OIDC, cài đặt tenant, xoay khóa ký, người vận hành tạm ngưng tenant, rate limit (Redis), cache kết quả check | — |

Mỗi lát có plan riêng. Tài liệu này đủ chi tiết để lập plan cho lát 0 và lát 1.

### 11.1 Lát 0: core

Mục tiêu: khi làm lát 1, mỗi endpoint chỉ còn phần nghiệp vụ (SQL và luật). Xác thực, lỗi, phân trang, gọi service khác, mã hóa và chạy local đều đã có sẵn và đã được test. Core **không chứa logic nghiệp vụ RBAC**, đúng quy ước "lib không phụ thuộc service, lib không chứa nghiệp vụ".

| Phần | Nằm ở | Nội dung | Test bằng |
|---|---|---|---|
| Mô hình lỗi | lib-core | `ApiException` mang `HttpStatus` + `code` (§10) + `errors[]`; `@RestControllerAdvice` chuyển mọi lỗi (kể cả lỗi validation, JSON hỏng, 404 route, lỗi không lường trước) thành `application/problem+json` có `requestId`; lỗi 5xx không lộ stack trace hay câu SQL | Controller giả trong test |
| Phân trang | lib-core | `Page<T>(items, nextCursor)`, `CursorCodec` mã hóa/giải mã khóa sắp xếp thành base64url, kiểm tra `limit` 1–200; cursor hỏng thì `400 validation_failed` | Unit test |
| Resource server | **lib-security** (module mới) | Cấu hình `SecurityFilterChain` dùng chung thay cho `SecurityConfig` của từng service (bỏ `httpBasic`); tin cậy issuer theo `issuer-prefix`, decoder theo từng issuer có cache; kiểm tra `aud`, `typ`, `exp`, tenant còn `active`; `Caller` được inject vào controller; danh sách đường công khai do service khai báo thêm | JWT do khóa test ký, JWKS giả (MockWebServer hoặc endpoint test) |
| Bảo vệ `/internal/**` | lib-security | Filter `X-Internal-Token`, so sánh thời gian hằng, thiếu cấu hình thì service không khởi động | Test filter |
| Client kiểm tra quyền | lib-security | `AccessChecker.require(caller, relation, object)` gọi `POST {AUTHZ_URL}/v1/check`, chuyển tiếp JWT và `X-Request-Id`; `denied` → `403 forbidden`; lỗi mạng hoặc timeout → `503 authz_unavailable` (fail closed). authz-service thay bằng bản cài đặt trong process | Server giả trả từng kết quả |
| Client giữa service | lib-core | `RestClient` dùng chung: timeout (kết nối 1 giây, đọc 2 giây), chuyển tiếp `X-Request-Id`, log mỗi lời gọi | Server giả |
| Mã hóa | **lib-crypto** (module mới) hoặc trong lib-security | `Kek` đọc `RBAC_KEK`; `EnvelopeCipher` AES-256-GCM (nonce ngẫu nhiên, `encryption_key_id`); `SecureTokens` sinh chuỗi ngẫu nhiên ≥ 256 bit và băm SHA-256; `PasswordHasher` (Argon2id) | Unit test, vector mẫu |
| Publisher `http` cho outbox | lib-outbox | §8: gửi theo lô, cấu hình `rbac.outbox.http.url`, kèm `X-Internal-Token`; mã 5xx hoặc lỗi mạng thì thử lại, 4xx thì chôn | Server giả |
| DB dev dùng chung + `scripts/dev.sh` | test-support, scripts | Xem mục dưới | Chạy tay: ba service lên, `curl` health cả ba |
| Cấu hình | các service | Thêm biến môi trường §10 vào `application.yaml` và `.env.example`; README có mục "Chạy toàn bộ" | — |

Sau lát 0, mỗi service vẫn chưa có endpoint nghiệp vụ. Đó là cố ý: lát 0 chỉ chứng minh phần khung bằng controller giả trong test.

### DB dev dùng chung (thuộc lát 0)
Một `main` nhỏ (`DevDatabase`, trong test-support) bật **một** embedded Postgres ở port cố định (mặc định `54329`), chạy toàn bộ migration, bật LOGIN cho `identity_app`, `authz_app`, `audit_app`, `rbac_admin`, in JDBC URL rồi chạy tới khi bị tắt. `scripts/dev.sh` bật DB này, đợi sẵn sàng, rồi chạy ba service bằng `main` thật với `DB_URL` trỏ vào nó (biến môi trường được ưu tiên hơn `.env`), sinh sẵn `RBAC_KEK` và `RBAC_INTERNAL_TOKEN` cho dev, chạy `npm run dev` của FE. Log có tiền tố `[db]`, `[identity]`, `[authz]`, `[audit]`, `[fe]`. Ctrl+C tắt tất cả. Dữ liệu mất khi tắt.

## 12. Kiểm thử

- **Theo endpoint**: test tích hợp MockMvc trên embedded Postgres, đăng nhập bằng đúng role của service. JWT do một khóa test ký, JWKS giả, nên test của authz và audit không cần identity chạy.
- **Cô lập tenant** (bắt buộc cho mọi endpoint quản trị): token tenant A đọc hoặc sửa bản ghi tenant B thì nhận `404`; token có `iss` ngoài `issuer-prefix` thì `401`; challenge token dùng làm access token thì `401`.
- **Đăng nhập**: lỗi luôn là `invalid_credentials` cho cả năm lý do; khóa tạm sau N lần; refresh token dùng lại thì cả family và session bị thu hồi; TOTP dùng lại cùng bước thì bị từ chối; tenant `platform` bắt buộc qua bước MFA.
- **Người vận hành**: phiên không có `otp` thì `403 mfa_step_up_required`; user không phải `operator` thì `403`; tạo tenant xong thì lời mời dùng được và admin mới đăng nhập được.
- **Engine**: các ví dụ chuẩn trong DB spec §7.4 (kế thừa qua thư mục và nhóm, luật cấm với Bình); chu trình; độ sâu 25; tuple hết hạn; user `disabled` bị loại; EXPLAIN dùng chỉ mục.
- **Audit**: giao cùng một envelope hai lần chỉ ra một dòng; sai `X-Internal-Token` thì `401`; audit tắt thì sự kiện chờ trong outbox và được giao khi audit chạy lại.
- **Kịch bản xuyên suốt lát 1** (thủ công hoặc script `curl` trên `scripts/dev.sh`): bootstrap lời mời vận hành → nhận lời mời → đăng ký TOTP → đăng nhập có MFA → tạo tenant `acme` → admin `acme` nhận lời mời → đăng nhập → `POST /v1/check` `tenant#admin` trả `allowed` → `GET /v1/audit/events` thấy `user.login.succeeded` và `tenant.created`.

## 13. Phần để lại cho người xây dựng

- Tên package và cách chia lớp trong từng service (gợi ý: `api` cho controller và DTO, `app` cho use case, `db` cho truy vấn `JdbcClient`).
- Thư viện TOTP (tự viết theo RFC 6238 hay dùng thư viện nhỏ) và thư viện base32.
- Dùng `RestClient` hay `HttpInterface` cho lời gọi giữa service; timeout (gợi ý: kết nối 1 giây, đọc 2 giây).
- Kích thước cache mô hình và JWKS, cách giới hạn kích thước (Caffeine hay `ConcurrentHashMap` có giới hạn).
- Chi tiết thông báo `title`/`detail` của lỗi.
- Có tách `lib-security` thành module riêng hay đặt trong lib-core (khuyến nghị: module riêng để lib-core không kéo theo Spring Security).
