# Thiết kế: tách RBAC platform thành nhiều module và tách quyền database theo service

Ngày: 2026-10-27. Spec database (V1–V10) đã có ở `docs/superpowers/specs/2026-10-26-rbac-platform-database-design.md`.

## 1. Mục đích

Chủ dự án muốn chia hệ thống theo hướng **microservice** chứ không phải một khối monolith, và làm phần **nền móng** trước khi viết bất kỳ chức năng nghiệp vụ nào. Phần công nghệ (Redis, Kafka, Elasticsearch, Batch) sẽ được thêm vào sau, khi máy đã có Docker. Lúc này mục tiêu là **chia cấu trúc cho hợp lý** để các phần đó cắm vào được về sau, và **tách quyền database theo từng service**. Phần công nghệ chỉ dùng trong repo RBAC này.

## 2. Mục tiêu, không làm, không được làm

**Mục tiêu**
- Một repo Maven nhiều module. Phần dùng chung nằm trong `libs/*`, phần nghiệp vụ nằm trong `services/*`.
- Code đã có được chuyển vào module mới mà không mất test nào:
  - log, `RequestLoggingFilter` → `lib-core`;
  - kết nối DB, `DatabaseConnectionCheck`, `TenantScope` → `lib-db`;
  - migration V1–V10 cùng các test DB → `db-migrations`;
  - Postgres nhúng dùng cho test → `test-support`.
- Ba service ở dạng **khung chạy được**: khởi động được, kết nối DB bằng role riêng của mình, có health check. Chưa có chức năng nghiệp vụ.
- Migration **V11** tạo một role database cho mỗi service. Mỗi role chỉ có quyền trên schema mà service đó sở hữu.

**Không làm lúc này (giai đoạn sau)**
- Các module `lib-redis`, `lib-kafka`, `lib-elasticsearch`, `lib-batch` và `docker/compose.yaml`. Chúng sẽ được làm khi máy có Docker, theo đúng chỗ đã chừa ở mục 4.
- Chức năng nghiệp vụ, API, giao tiếp giữa các service, API gateway, service discovery, Kubernetes, CI/CD, frontend.
- Áp V11 lên Supabase. Đây là bước riêng làm sau, qua SQL Editor như lần trước.
- Gỡ quyền đăng nhập của `rbac_app` trên Supabase. Đây là việc vận hành, làm sau khi các service đã chuyển sang role riêng.

**Không được làm (nếu xảy ra thì coi như hỏng, dù code vẫn chạy)**
- Lib phụ thuộc vào service, hoặc lib chứa logic nghiệp vụ RBAC.
- Một service tự chạy migration database.
- Sửa nội dung V1–V10, vì chúng đã được áp lên Supabase.
- Role của một service đọc hoặc ghi được dữ liệu của service khác ngoài các ngoại lệ ghi ở mục 6. Ví dụ không được để `authz` đọc email user hay `audit` đọc mật khẩu.
- Để việc tách quyền làm hỏng tính năng đang có: tách tenant bằng RLS, kiểm tra thực thể khi ghi quan hệ, tự xóa quan hệ khi xóa user.
- Test DB phải cần Docker mới chạy được.

## 3. Ràng buộc

- Java 21, Spring Boot 4.1.2, Maven wrapper (`./mvnw`).
- Postgres là Supabase. Mạng của chủ dự án chặn cổng 5432/6543, nên mọi việc chạy local và test dùng Postgres nhúng (zonky, PG 17).
- Máy phát triển chưa có Docker.
- Migration V1–V10 đã được áp lên Supabase bằng tay. Database chưa có lịch sử Flyway (baseline V10 vẫn đang chờ làm).

## 4. Cấu trúc module

```
rbac-platform/                  pom cha (packaging pom): Spring Boot parent, version thư viện, danh sách module
├── libs/
│   ├── lib-core                logback-spring.xml, RequestLoggingFilter (requestId), lỗi chung
│   └── lib-db                  DataSource, DatabaseConnectionCheck, TenantScope
│   (sau này: lib-redis, lib-kafka, lib-elasticsearch, lib-batch)
├── db-migrations               V1–V11, plugin Flyway, các test DB
├── test-support                Postgres nhúng đã áp migration, dùng chung cho test
├── services/
│   ├── identity-service        schema tenancy, identity, apps
│   ├── authz-service           schema authz
│   └── audit-service           schema audit
└── frontend/                   giữ nguyên
```

**Quy tắc phụ thuộc**
- Service dùng lib. Lib không dùng service.
- Mỗi lib chỉ được phụ thuộc vào `lib-core`, cộng thêm thư viện của chính công nghệ đó.
- `test-support` phụ thuộc `db-migrations` để lấy file SQL, và chỉ được dùng ở scope `test`.
- Tất cả module có cùng version (version của project). Không publish artifact nào.

Ứng dụng gốc `com.rbac_platform.RbacPlatformApplication` và thư mục `src/` ở gốc repo biến mất. Các starter chưa dùng tới (batch, elasticsearch, data-redis-reactive) bị bỏ khỏi pom và sẽ quay lại cùng lib tương ứng ở giai đoạn sau.

## 5. Từng module làm gì

- **lib-core**
  - `logback-spring.xml` hiện có, một bản duy nhất. Tên service trên mỗi dòng log lấy từ `spring.application.name`.
  - `RequestLoggingFilter`, `X-Request-Id`.
  - Lỗi trả về dạng `ProblemDetail` có kèm `requestId`. *(quyết định của tôi)*
- **lib-db**
  - Cấu hình DataSource như hiện tại.
  - `TenantScope`.
  - `DatabaseConnectionCheck` dừng khởi động khi kết nối hỏng hoặc role vượt RLS. Nó còn kiểm tra thêm rằng **role đang đăng nhập đúng là role service đã khai báo**, qua thuộc tính `rbac.db.expected-role`. Nhờ vậy, nếu `identity-service` lỡ cấu hình bằng thông tin đăng nhập của `authz`, service sẽ dừng ngay. *(quyết định của tôi)*
- **db-migrations**
  - Chứa V1–V10 như hiện tại, thêm V11 (mục 6).
  - Có plugin Flyway và toàn bộ test DB.
  - Không phải ứng dụng Spring Boot.
- **test-support**: `MigratedDatabase`, `Sql`, `AppDatabase` hiện có, mở rộng để đăng nhập được bằng từng role của service.
- **Các service.** Mỗi service là một Spring Boot app riêng, có port riêng, `application.yaml` riêng, và `rbac.db.expected-role` là role của chính nó. Mỗi service có lệnh chạy local trên Postgres nhúng, tương tự `spring-boot:test-run` hiện nay.
  - Chưa có controller nghiệp vụ.
  - Spring Security chỉ mở `/actuator/health`, còn lại chặn hết. *(quyết định của tôi)*
  - Cả ba service dùng `lib-core` và `lib-db`.
- **Cấu hình.** Có một file `.env` duy nhất ở gốc repo, dùng chung cho mọi service. `DB_URL` cũng dùng chung. Mỗi service có cặp biến user/mật khẩu riêng: `IDENTITY_DB_USER`/`IDENTITY_DB_PASSWORD`, `AUTHZ_DB_USER`/`AUTHZ_DB_PASSWORD`, `AUDIT_DB_USER`/`AUDIT_DB_PASSWORD`. Service phải đọc được `.env` này dù được chạy từ thư mục module của nó.

## 6. V11: role database cho từng service

Ba role mới là `identity_app`, `authz_app` và `audit_app`.
- Chúng đều là `NOLOGIN`. Quyền đăng nhập được cấp sau, bằng SCRAM verifier, như `rbac_app` đã làm. Script `scripts/db/bootstrap_login_roles.sql` được mở rộng cho ba role này.
- `search_path` được đặt giống `rbac_app`.

**Tách tenant (RLS)**
- Mọi policy hiện đang áp cho `rbac_app` (`tenant_isolation`, cùng `app_select` và `app_update` trên `tenancy.tenants`) được mở rộng để áp cho cả ba role mới.
- `util.enable_tenant_rls` được cập nhật để bảng tạo sau này cũng có policy cho ba role.
- Hàm này **không** cấp quyền bảng cho các role service. Migration nào tạo bảng thì tự cấp quyền rõ ràng cho đúng service sở hữu bảng đó.

**Quyền của từng role**

| Role | Được dùng | Quyền |
|---|---|---|
| cả ba | `util`, `tenancy.tenants` | EXECUTE `util.current_tenant_id()`, `util.uuid_v7()`; SELECT `tenancy.tenants` (danh bạ tenant dùng chung) |
| `identity_app` | `tenancy`, `identity`, `apps` | Đúng những quyền `rbac_app` đang có trên bảng, cột và hàm của ba schema này, ví dụ UPDATE chỉ `(name, settings)` trên `tenants`, `resolve_domain`, `find_user_by_*`, `resolve_api_key` |
| `authz_app` | `authz` | Đúng những quyền `rbac_app` đang có trên schema `authz` (changelog chỉ đọc) |
| `audit_app` | `audit` | Đúng những quyền `rbac_app` đang có trên `audit.events` |

**Hai ngoại lệ có chủ đích, để giữ tính năng hiện có**
1. **Kiểm tra thực thể khi ghi quan hệ.** `authz.validate_relationship` chạy với quyền của người gọi. Nó cần biết một user, nhóm hoặc ứng dụng có tồn tại trong tenant hay không. `authz_app` được SELECT **chỉ trên hai cột `tenant_id, id`** của `identity.users`, `identity.groups` và `apps.applications`, cộng USAGE trên schema `identity` và `apps`. RLS vẫn áp dụng. Role này không đọc được email, tên hay bất kỳ cột nào khác.
2. **Tự xóa quan hệ khi xóa user, nhóm hoặc ứng dụng.** `authz.delete_entity_relationships` được chuyển sang `SECURITY DEFINER`, cố định `search_path`, và thu hồi EXECUTE khỏi PUBLIC. Nhờ vậy `identity_app` xóa được user mà không cần quyền DELETE trên `authz.relationships`. Cách này an toàn vì đây là trigger AFTER DELETE: dòng `OLD` đã qua RLS, và lệnh xóa giới hạn theo `OLD.tenant_id`. Lý do giống hệt với trigger ghi changelog ở V7.

**Không đổi**
- `rbac_app` giữ nguyên quyền. Các test DB hiện có vẫn dùng nó, nhưng không service nào dùng.
- `rbac_admin` và `rbac_owner` giữ nguyên.

**Test cho V11** phải chứng minh:
- Mỗi role làm được đúng việc của nó trong tenant của nó, và RLS vẫn chặn tenant khác.
- Từng ô "không được" trong ma trận đều bị chặn, ví dụ:
  - `authz_app` không đọc được `identity.users.email` hay `identity.password_credentials`;
  - `audit_app` không đọc được `identity` hay `authz`;
  - `identity_app` không ghi được `authz.relationships`.
- Ghi quan hệ bằng `authz_app` vẫn bị từ chối khi user không tồn tại.
- `identity_app` xóa user thì các quan hệ của user đó biến mất, và changelog vẫn được ghi.
- Bảng tạo bằng `util.enable_tenant_rls` sau V11 có policy cho cả ba role.

## 7. Kiểm thử chung

- Toàn bộ 184 test hiện có vẫn còn sau khi chuyển chỗ và vẫn pass, không cần Docker.
- Mỗi service có một test khởi động: context loads, đăng nhập bằng đúng role của nó.
- Có test cho trường hợp `rbac.db.expected-role` không khớp: service phải dừng khởi động.

## 8. Phần để người làm tự quyết

- Tên package. Gợi ý: `com.rbac_platform.lib.<tên>` cho lib, `com.rbac_platform.<service>` cho service.
- Port của từng service.
- Cách cấp quyền trong V11: viết từng câu GRANT, hoặc sao chép tự động từ ACL của `rbac_app` theo schema. Miễn kết quả đúng như bảng ở mục 6 và có test chứng minh.
- Chuyển file bằng `git mv` để giữ lịch sử.
