# Thiết kế: nền móng xử lý bất đồng bộ (outbox + Spring Batch)

Ngày: 2026-10-27. Tài liệu này nối tiếp `2026-10-27-platform-foundation-modules-design.md`, bản thiết kế đã chia repo thành `libs/*` và `services/*`, mỗi service một role database.

## 1. Mục đích

Chủ dự án muốn có phần công nghệ xử lý bất đồng bộ trước khi viết chức năng nghiệp vụ. Ba yêu cầu đi kèm:

- **Tốc độ là yêu cầu sản phẩm.** Chỉ dùng SQL viết tay qua `JdbcClient`, không dùng ORM, và mỗi request gọi database càng ít lượt càng tốt.
- **Code công nghệ phải rất linh hoạt.** Thêm một loại việc mới hoặc đổi cách xử lý (ghi log, xử lý ngay trong service, Kafka về sau) chỉ cần thêm một class hoặc đổi cấu hình. Không được phải sửa code lõi hay thêm nhánh `switch`/`if` theo loại.
- **Xử lý được nhiều tình huống.** Lỗi tạm thời, lỗi vĩnh viễn, chạy nhiều instance cùng lúc, service tắt giữa chừng, gặp loại sự kiện chưa ai xử lý.

Việc bất đồng bộ có hai loại khác nhau, nên có hai công cụ:

| Việc | Loại | Công cụ |
|---|---|---|
| CRUD | đồng bộ, người dùng thấy kết quả ngay | SQL viết tay, không đổi gì |
| Việc phát sinh sau mỗi thay đổi (cập nhật ES, xóa cache, gửi sự kiện) | gần như tức thời, từng sự kiện | **outbox** (`lib-outbox`) |
| Việc hàng loạt hoặc theo lịch (index lại toàn bộ, import, bảo trì partition) | theo lô, cần chạy tiếp được khi bị dừng | **Spring Batch** (`lib-batch`) |

## 2. Mục tiêu, không làm, không được làm

**Mục tiêu**

- `lib-outbox`:
  - ghi sự kiện cùng transaction với CRUD;
  - một tiến trình nền lấy sự kiện ra và gửi đi, có thử lại và có chỗ chứa sự kiện hỏng;
  - lớp gửi đi cắm thay được.
- Bộ "hợp đồng sự kiện" dùng chung (`EventEnvelope`, `EventHandler`, registry) đặt trong `lib-core`. Handler viết bây giờ chạy trong service; về sau chạy sau Kafka mà không phải sửa handler.
- `lib-batch`:
  - Spring Batch với nơi lưu lịch sử job nằm trong schema của từng service;
  - lập lịch và bật/tắt job bằng cấu hình;
  - chạy an toàn khi có nhiều instance;
  - công cụ chạy một job lần lượt cho từng tenant.
- Một job thật đầu tiên: **bảo trì partition theo tháng**. Hiện chưa có gì chạy việc này. V7 và V8 chỉ tạo trước partition cho 3 tháng (đến hết tháng 1/2027). Sau đó mọi dòng của `audit.events` và `authz.relationship_changes` sẽ dồn vào partition DEFAULT.
- Migration V12 (outbox) và V13 (bảng của Spring Batch, cùng quyền bảo trì partition cho từng service).
- Tất cả chạy và test được trên Postgres nhúng, không cần Docker.

**Không làm lúc này**

- `lib-kafka`, `lib-elasticsearch`, `lib-redis` và Docker. Khi làm, mỗi lib chỉ cần thêm một `OutboxPublisher` hoặc consumer mới (xem mục 4.5).
- Sự kiện nghiệp vụ cụ thể (`user.created`…), trigger sinh sự kiện tự động, API quản trị job, LISTEN/NOTIFY, việc dọn sự kiện hỏng cũ.
- Job xóa partition cũ (retention). Đó là quyết định nghiệp vụ, `rbac_admin` vẫn làm bằng tay qua `util.drop_partitions_older_than`.
- Áp V11–V13 lên Supabase. Đây là bước riêng, làm qua SQL Editor như trước.

**Không được làm (nếu xảy ra thì coi như hỏng, dù code vẫn chạy)**

- DB đã commit mà sự kiện bị mất, hoặc sự kiện được gửi mà DB đã rollback.
- Role của một service đọc, lấy hoặc xử lý được outbox, lịch sử batch hay partition của service khác.
- Role service đọc thẳng được sự kiện của tenant khác. Tiến trình nền chỉ đi qua hàm `SECURITY DEFINER`.
- Sửa V1–V11.
- Lõi relay hoặc lõi batch có `switch`/`if` theo loại sự kiện hay tên job.
- Hai instance xử lý cùng một sự kiện cùng lúc trong thời hạn giữ chỗ (lease) của nó.
- Hai instance cùng chạy một lần chạy theo lịch của một job. Một lần chạy theo lịch bị FAILED cũng không được instance khác tự chạy lại.
- Test phải cần Docker mới chạy được.

## 3. Ràng buộc

- Java 21, Spring Boot 4.1.2, Spring Batch 6.0.6 (theo Boot BOM), starter `spring-boot-starter-batch-jdbc`, Jackson 3 (Boot 4).
- Truy cập DB chỉ qua `JdbcClient` với SQL viết tay.
- Pool kết nối mặc định là 5 (`DB_POOL_SIZE`). Relay không được giữ kết nối hay transaction trong lúc handler chạy (mục 4.3).
- Kết nối tới Supabase qua Supavisor. LISTEN/NOTIFY không dùng được ở chế độ transaction, nên relay phải polling.
- Tenant trong DB lấy từ `app.tenant_id`, đặt bằng `TenantScope.call` (lib-db). `TenantScope` tự mở transaction và từ chối chạy bên trong một transaction khác.
- Quy tắc phụ thuộc giữa các lib được **nới ra**: một lib được phụ thuộc vào `lib-core`, `lib-db` và thư viện của chính công nghệ đó. Outbox và batch đều sống trên database nên cần `lib-db`. *(quyết định của tôi)*

## 4. lib-outbox

### 4.1 Bảng (V12)

Hàm `util.create_outbox(p_schema text, p_role regrole)` chỉ owner mới gọi được. Nó tạo một bảng `<schema>.outbox` và các hàm của bảng đó cho đúng một role. Mỗi service có một outbox nên dùng hàm này để thêm outbox mới chỉ tốn một dòng. V12 gọi hàm cho `identity` (identity_app) và `authz` (authz_app). Audit hiện chỉ nhận sự kiện, không phát ra, nên chưa có outbox. *(quyết định của tôi)*

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `tenant_id` | uuid NOT NULL | RLS qua `util.enable_tenant_rls` |
| `id` | uuid, mặc định `util.uuid_v7()` | khóa chính. Đồng thời là khóa idempotency mà consumer dùng để bỏ qua sự kiện trùng |
| `aggregate_type`, `aggregate_id` | text NOT NULL | ví dụ `user`, `<uuid>`. Kafka về sau dùng `aggregate_id` làm key |
| `event_type` | text NOT NULL | ví dụ `user.updated`. Registry dựa vào cột này để chọn handler |
| `payload` | jsonb NOT NULL | nên nhỏ (mục 4.6) |
| `headers` | jsonb NOT NULL, mặc định `{}` | có `requestId` (để lần vết từ request tới consumer), `actor` |
| `created_at` | timestamptz | |
| `status` | `pending` \| `dead` | |
| `attempts` | int, mặc định 0 | tăng **lúc lấy việc** (claim), không phải lúc báo lỗi |
| `next_attempt_at` | timestamptz, mặc định now() | khi được lấy, cột này lùi tới `now() + lease`, nên nó cũng là khóa giữ chỗ |
| `claim_token` | uuid | token của lần lấy việc gần nhất |
| `last_error` | text | đã cắt ngắn, không chứa dữ liệu nhạy cảm |

- Có partial index `(next_attempt_at, id) WHERE status = 'pending'`, nên câu lấy việc của relay chỉ quét đúng những dòng đang chờ.
- Đặt autovacuum mạnh tay cho bảng này, vì dòng gửi thành công bị xóa ngay và bảng luôn nhỏ.

**Quyền.** `util.create_outbox` tự bật RLS và tạo policy. Nó **không** gọi `util.enable_tenant_rls`, vì hàm đó cấp toàn quyền DML cho `rbac_app` và `rbac_admin` và tạo thêm policy `admin_all`.

- Role service chỉ được `INSERT`, qua policy `tenant_isolation` FOR INSERT WITH CHECK tenant hiện tại. Không có `SELECT`/`UPDATE`/`DELETE` thẳng trên bảng.
  - Vì vậy writer không dùng được `RETURNING` hay `ON CONFLICT`, và `id` do DB sinh.
- Tiến trình nền dùng ba hàm `SECURITY DEFINER` (owner `rbac_owner`, chạy dưới policy `owner_all`). EXECUTE thu hồi khỏi PUBLIC, chỉ cấp cho đúng role:
  - `<schema>.outbox_claim(p_limit int, p_lease interval, p_token uuid) RETURNS SETOF <schema>.outbox`
    - Chọn các dòng `pending` có `next_attempt_at <= now()`, sắp theo `next_attempt_at, id`, bằng `FOR UPDATE SKIP LOCKED`, trong một CTE `MATERIALIZED` (không dùng `WHERE id IN (subquery LIMIT)`: planner có thể quét lại subquery theo từng dòng và lấy quá `p_limit`).
    - Trong cùng một câu lệnh, đặt `attempts + 1`, `next_attempt_at = now() + p_lease`, `claim_token = p_token`.
    - `p_lease` phải lớn hơn 0 và không quá 1 giờ.
    - Commit ngay sau đó.
  - `<schema>.outbox_complete(p_token uuid, p_ids uuid[])` xóa các dòng đã gửi xong.
  - `<schema>.outbox_reschedule_many(p_token uuid, p_ids uuid[], p_errors text[], p_next timestamptz[], p_dead boolean[]) RETURNS int` hẹn lần thử sau hoặc chuyển sang `dead` cho cả lô trong một câu lệnh (các mảng song song, cùng độ dài).
    - Chỉ có tác dụng khi `claim_token` còn khớp. Một instance đã hết lease thì không ghi đè được kết quả của instance đã lấy lại việc.
- Vận hành viên (`rbac_admin`) có hai hàm `SECURITY DEFINER` cho mỗi outbox. *(quyết định của tôi: V12 không sửa được về sau, nên phải có ngay)*
  - `<schema>.outbox_dead(p_limit int)` xem sự kiện hỏng của mọi tenant.
  - `<schema>.outbox_requeue(p_ids uuid[])` đưa sự kiện hỏng về `pending`, `attempts = 0`.
- Role `rbac_app` không được thêm quyền gì. `rbac_admin` chỉ có EXECUTE trên hai hàm vận hành, không có quyền thẳng trên bảng.
- Bất biến V11 trong `ServiceRolesTest` ("role service có đúng những quyền `rbac_app` có trên schema của nó") phải được **viết lại**: so sánh mọi đối tượng trừ outbox và bảng batch. Quyền trên outbox và bảng batch được kiểm tra bằng một danh sách riêng, ghi rõ từng quyền.

### 4.2 Ghi sự kiện

`OutboxWriter.append(OutboxMessage)` và `appendAll(List<OutboxMessage>)`. `appendAll` ghi tất cả bằng một lượt gọi DB (JDBC batch).

- Ghi phải nằm trong transaction đang mở và đã có tenant, nghĩa là gọi bên trong `TenantScope.call`. Gọi ngoài transaction thì ném lỗi ngay, không lặng lẽ mở transaction riêng, vì như thế mất tính "cùng commit".
- `headers.requestId` được tự điền từ MDC `requestId` (lib-core).
- Sau khi transaction commit, writer **đánh thức** relay của chính instance đó, nhờ `TransactionSynchronization.afterCommit`. Sự kiện vì thế đi gần như ngay mà không cần chờ đến lần polling sau, lại không cần LISTEN/NOTIFY.

### 4.3 Relay (vòng lặp cố định, phần thay đổi cắm qua strategy)

Mỗi vòng lặp dùng **lease (giữ chỗ)**, không giữ transaction trong lúc gửi:
1. **Transaction ngắn:** gọi `outbox_claim(batch-size, lease, token)`, rồi commit.
2. **Ngoài mọi transaction**, với từng sự kiện:
   - nếu `attempts > max-attempts` thì không gửi, ghi nhận là `dead`;
   - còn lại thì gọi `OutboxPublisher`.
3. **Transaction ngắn:**
   - xóa những sự kiện thành công bằng một lần gọi `outbox_complete`;
   - với sự kiện lỗi, hỏi `RetryPolicy` rồi gọi `outbox_reschedule_many` một lần cho cả lô;
   - commit.

Các tình huống được xử lý:

- **Service chết giữa chừng.** Sự kiện xuất hiện lại khi lease hết hạn. Đó là đảm bảo **at-least-once**.
- **Sự kiện làm service sập hoặc treo mỗi lần chạy.** `attempts` vẫn tăng mỗi lần lấy việc, nên cuối cùng sự kiện chuyển sang `dead` chứ không lặp vô hạn.
- **Handler không bị chặn dùng DB.** Handler chạy ngoài transaction và không chiếm kết nối của relay. Cần dữ liệu tenant thì handler tự gọi `TenantScope.call(envelope.tenantId(), …)`. Relay không tự mở tenant scope, vì handler ES hay cache không cần DB. Consumer Kafka về sau gọi handler theo đúng cách này, nên handler giữ nguyên.
- **Một lô chạy lâu hơn `lease`.** Relay ghi log WARN, vì sự kiện có thể đã bị instance khác lấy lại. Idempotency của handler bảo vệ trường hợp này. `lease` mặc định là 60s và phải đủ cho cả lô.

- **Template Method.** Vòng lặp trên là cố định, không ai override. Những chỗ thay đổi được là các strategy bên dưới.
- **Strategy `OutboxPublisher`**, chọn bằng `rbac.outbox.publisher`:
  - `log`, mặc định: chỉ ghi log sự kiện;
  - `in-process`: đưa sự kiện cho các `EventHandler` trong chính service (mục 4.5);
  - về sau `kafka` trong `lib-kafka`.
  Thêm một kiểu gửi mới chỉ cần thêm một bean, không sửa relay.
- **Strategy `RetryPolicy`**. Mặc định là exponential backoff 1s, 2s, 4s…, tối đa 5 phút, kèm jitter, `max-attempts` 10. Lỗi kiểu `NonRetryableEventException` chuyển thẳng sang `dead`. Muốn chính sách khác thì service khai báo bean riêng.
- **Chain `OutboxInterceptor`**, gồm before, after và onError, chạy quanh mỗi lần gửi:
  - lib có sẵn interceptor đặt MDC `requestId`, `tenantId` và `eventId` để log của consumer nối được với request gốc;
  - interceptor ghi log thời gian xử lý;
  - metrics về sau thêm bằng một interceptor mới.
- Chạy nhiều instance an toàn nhờ `SKIP LOCKED`. Polling thích ứng: lô vừa lấy đầy thì lặp ngay, rỗng thì chờ `poll-interval`. Lúc tắt service: ngừng lấy lô mới, gửi xong lô đang dở rồi mới dừng (`SmartLifecycle`).

Cấu hình, prefix `rbac.outbox`:

| Thuộc tính | Ý nghĩa |
|---|---|
| `enabled` | bật/tắt relay |
| `schema` | ví dụ `identity` |
| `publisher` | `log`, `in-process`, sau này `kafka` |
| `batch-size` | mặc định 100 |
| `poll-interval` | mặc định 500ms |
| `max-attempts` | số lần thử tối đa |
| `backoff.initial`, `backoff.max` | thời gian chờ giữa các lần thử |
| `lease` | thời hạn giữ chỗ một lô, mặc định 60s |

### 4.4 Thứ tự sự kiện

- Thứ tự chỉ là **cố gắng**: trong một lô, sự kiện được sắp theo `id` (uuid v7, tăng theo thời gian).
- Có nhiều instance, hoặc có sự kiện phải thử lại, thì thứ tự có thể đảo.
- Hợp đồng với consumer: **idempotent theo `id`**, và coi sự kiện là tín hiệu "có thay đổi" để đọc lại trạng thái hiện tại nếu cần, chứ không phải nguồn sự thật. Đây là cách làm cho việc cập nhật ES và xóa cache.

### 4.5 Hợp đồng sự kiện dùng chung (lib-core, package `com.rbac_platform.lib.core.event`)

- `EventEnvelope` (record) gồm `id`, `tenantId`, `aggregateType`, `aggregateId`, `eventType`, `payload` (JSON), `headers`, `occurredAt`.
- `EventHandler`: `Set<String> eventTypes()` và `void handle(EventEnvelope)`. Kiểu đặc biệt `*` nghĩa là nhận mọi sự kiện, dùng cho ghi log hoặc metrics.
- `EventHandlerRegistry` là **Registry + Open/Closed**. Nó gom mọi bean `EventHandler` lúc khởi động thành một bảng tra `eventType → handlers`. Một loại sự kiện có thể có nhiều handler, ví dụ `user.updated` vừa cập nhật ES vừa xóa cache. Thêm một loại xử lý mới chỉ cần viết một class `@Component`, không ai phải sửa `switch`.
- Sự kiện **không có handler nào** được xử lý bằng **strategy `UnhandledEventPolicy`**:
  - `ignore`, mặc định: ghi log WARN rồi coi như xong;
  - `fail`: coi là lỗi để thử lại hoặc chuyển sang `dead`.
- Publisher `in-process` và consumer Kafka về sau dùng **cùng** registry này. Vì vậy khi chuyển từ `in-process` sang `kafka`, handler giữ nguyên.
- Khi có nhiều handler cho một sự kiện mà một handler lỗi, cả sự kiện được thử lại, các handler đã chạy rồi sẽ chạy lại. Vì vậy mọi handler phải idempotent. Điều này ghi rõ trong Javadoc của `EventHandler`.

### 4.6 Hướng dẫn hiệu năng cho phía ghi

- Payload nhỏ, ví dụ id cộng các trường đã đổi, để INSERT nhanh và bảng nhẹ.
- Ghi outbox tốn thêm một câu lệnh trong cùng transaction với CRUD. Nhiều sự kiện thì gom lại bằng `appendAll`.
- Khi viết chức năng, có thể dùng trigger DB để sinh sự kiện, khỏi tốn thêm lượt gọi. Việc này để cho từng chức năng tự quyết.

## 5. lib-batch

### 5.1 Nơi lưu lịch sử job (V13)

- Hàm `util.create_batch_repository(p_schema text, p_role regrole)`, cũng chỉ owner gọi được, tạo 6 bảng và 3 sequence của Spring Batch 6.0.6 trong schema của service với tiền tố `batch_`, ví dụ `audit.batch_job_instance`.
  - DDL được chép đúng từ `org/springframework/batch/core/schema-postgresql.sql` của spring-batch-core 6.0.6.
  - Role của service được SELECT/INSERT/UPDATE trên các bảng đó và USAGE/SELECT trên sequence.
  - Đây là siêu dữ liệu của job, không phải dữ liệu tenant, nên không có RLS. Schema đã bị chặn với các role API của Supabase.
- V13 gọi hàm cho cả ba schema `identity`, `authz` và `audit`.
- Cấu hình dùng chung `rbac/batch-defaults.yaml` (cùng kiểu với `db-defaults.yaml`):
  - `spring.batch.jdbc.table-prefix: ${rbac.batch.schema}.batch_`;
  - `spring.batch.jdbc.initialize-schema: never`, vì service không tự tạo bảng;
  - `spring.batch.job.enabled: false`, để job không tự chạy lúc khởi động.

### 5.2 Đăng ký và lập lịch job (theo cấu hình)

- Job là bean `Job` bình thường của Spring Batch, nên ai đọc tài liệu Spring Batch cũng viết được.
- `lib-batch` có `ScheduledJobRunner` (**Registry + Strategy**).
  - Nó đọc `rbac.batch.jobs.<tên-job>.enabled` và `.cron` rồi lập lịch bằng `TaskScheduler`.
  - Job không có cấu hình thì không chạy theo lịch. Cấu hình trỏ tới một job không tồn tại thì service **dừng khởi động**.
  - Thêm hoặc tắt một job chỉ cần đổi cấu hình.
- **Chống chạy trùng khi có nhiều instance.** Không dựa vào lỗi của Spring Batch: Batch 6.0.6 tạo execution ở mức SERIALIZABLE, nên khi tranh nhau sẽ ra lỗi 40001 hoặc lỗi trùng khóa, và một lần FAILED vẫn bị instance khác chạy lại. Thay vào đó:
  - `util.create_batch_repository` tạo thêm bảng `<schema>.batch_schedule_slot(job_name text, scheduled_at timestamptz, claimed_by text, claimed_at timestamptz, PRIMARY KEY (job_name, scheduled_at))`. Role service có INSERT/SELECT trên bảng này.
  - Trước khi chạy theo lịch, instance chạy `INSERT ... ON CONFLICT DO NOTHING`. `scheduledAt` là thời điểm lịch kích hoạt, cắt về phút.
  - Instance chèn được dòng thì chạy job, với `scheduledAt` là tham số định danh. Instance không chèn được thì ghi log DEBUG rồi bỏ qua.
  - Kết quả: một slot chỉ chạy một lần, kể cả khi FAILED.
- `JobLaunchService` dùng để chạy tay, sau này cho API quản trị:
  - `launch(String jobName, Map<String, ?> params)`;
  - `restart(long executionId)` để chạy tiếp một lần FAILED;
  - `recover(long executionId)` cho một lần STARTED bị bỏ dở vì service chết: đánh dấu nó là bỏ dở qua `JobOperator` của Batch 6, để `restart` dùng được.
- Lỗi job (FAILED) được ghi log ERROR kèm tên job và `executionId`. Lần chạy theo lịch sau là một slot mới. Hệ thống **không tự chạy lại** một slot hỏng, vì các job import về sau có thể không idempotent.

### 5.3 Chạy theo từng tenant

- Partitioner `TenantPartitioner` tạo một partition cho mỗi tenant `active` (đọc `tenancy.tenants`, mà cả ba role đều đọc được).
- Có cơ chế để **mọi transaction chunk** của một partition chạy với `app.tenant_id` của tenant đó, đặt bằng `set_config(..., true)` như `TenantScope`, và MDC `tenantId` cũng được đặt theo.
- Cách cài cơ chế này vào Spring Batch 6 do người làm chọn. Có test chứng minh: trong một chunk, RLS chỉ thấy dữ liệu của đúng tenant đó.
- Đây là nền cho các job về sau như index lại ES theo tenant hay import user.

### 5.4 Job đầu tiên: bảo trì partition

- V13 tạo hai hàm `SECURITY DEFINER`:
  - `audit.maintain_partitions()` gọi `util.ensure_monthly_partitions('audit.events', 3)`;
  - `authz.maintain_partitions()` gọi `util.ensure_monthly_partitions('authz.relationship_changes', 3)`.
  Mỗi hàm chỉ cấp EXECUTE cho đúng role của service đó. Cố ý không cấp thẳng `util.ensure_monthly_partitions`, vì hàm đó partition được bất kỳ bảng nào của `rbac_owner`. *(quyết định của tôi)*
- `audit-service` và `authz-service` mỗi bên có job `partition-maintenance` gồm một tasklet gọi hàm của mình. Lịch mặc định là hằng ngày, 02:00 UTC. Hàm idempotent, nên chạy lại nhiều lần không sao.

## 6. Cấu trúc module sau khi làm

```
libs/lib-core     + package event: EventEnvelope, EventHandler, EventHandlerRegistry, UnhandledEventPolicy
libs/lib-db       (giữ nguyên)
libs/lib-outbox   mới: OutboxWriter, relay, OutboxPublisher (log, in-process), RetryPolicy, OutboxInterceptor
libs/lib-batch    mới: batch-defaults.yaml, ScheduledJobRunner, JobLaunchService, TenantPartitioner
db-migrations     + V12__outbox.sql, V13__batch_repository.sql
services/identity-service  dùng lib-outbox (schema identity)
services/authz-service     dùng lib-outbox (authz) và lib-batch (job partition-maintenance)
services/audit-service     dùng lib-batch (job partition-maintenance)
```

Thứ tự trong pom cha: `lib-core`, `lib-db`, `lib-outbox`, `lib-batch`, `db-migrations`… Theo spec trước, các lib dùng `test-support` (scope test).

## 7. Kiểm thử phải chứng minh

**DB (db-migrations, ServiceRoles-style)**

- Role service chỉ INSERT được vào outbox của mình, và chỉ trong tenant hiện tại. Không đọc, sửa hay xóa thẳng được.
- Role của service khác không chạm được outbox, các hàm outbox, bảng batch hay hàm bảo trì partition không thuộc về nó.
- `outbox_claim` thấy sự kiện của mọi tenant. Hai transaction cùng claim không bao giờ lấy trùng một dòng.
- Bảng tạo sau đó bằng `util.create_outbox` hoặc `util.create_batch_repository` có đúng quyền như trên.
- Ma trận ACL chính xác giống `ServiceRolesTest`, có cập nhật cho đối tượng mới.

**lib-outbox**

- CRUD rollback thì không có sự kiện. CRUD commit thì có đúng sự kiện, kèm `requestId`.
- Gọi `append` ngoài transaction thì báo lỗi.
- Gửi thành công thì dòng bị xóa. Lỗi thì `next_attempt_at` lùi theo backoff. Quá `max-attempts` hoặc gặp `NonRetryableEventException` thì chuyển sang `dead`.
- Service chết sau khi lấy việc thì sự kiện được lấy lại khi hết lease, và `attempts` vẫn tăng mỗi lần lấy.
- `outbox_reschedule_many` hoặc `outbox_complete` với token cũ (đã hết lease, việc đã bị instance khác lấy lại) không làm thay đổi dòng nào.
- Handler gọi được `TenantScope.call` và ghi được dữ liệu tenant.
- `rbac_admin` xem và đưa lại được sự kiện `dead` qua hai hàm vận hành. Role service thì không.
- Commit xong thì relay chạy ngay, không chờ hết `poll-interval`.
- Đổi `publisher` thì đổi cách gửi mà không đổi code. `in-process` gọi đủ mọi handler của loại đó, kể cả handler `*`.
- Kiểm tra cả hai chế độ `ignore` và `fail` của `UnhandledEventPolicy`.
- Tắt service thì lô đang dở được làm xong.

**lib-batch**

- Bảng batch nằm trong đúng schema của service.
- Job đã cấu hình thì được lập lịch, job tắt thì không. Cấu hình trỏ tới job không tồn tại thì service không khởi động.
- Hai instance cùng `scheduledAt` thì chỉ một lần thực sự chạy. Một lần FAILED không bị instance khác chạy lại. `restart` và `recover` hoạt động đúng.
- `TenantPartitioner` cho mỗi tenant đúng dữ liệu của nó.

**Service**

- Job `partition-maintenance` của audit tạo partition cho các tháng tới. Role audit không bảo trì được partition của authz.

## 8. Phần để người làm tự quyết

- Tên class bên trong, cách chia package nội bộ của từng lib.
- Cách cài cơ chế tenant cho chunk trong Spring Batch 6 (listener, `TransactionTemplate` riêng hoặc step scope), miễn đạt yêu cầu ở mục 5.3.
- Cách viết tay hay sinh tự động DDL của Spring Batch bên trong `util.create_batch_repository`, miễn đúng với file 6.0.6.
- Thư viện cron (Spring `CronTrigger` là đủ).
- Chuyển các file bằng `git mv` nếu có di chuyển.
