# Lát 0 (core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dựng phần nền dùng chung cho mọi API (lỗi, phân trang, gọi service, mã hóa, xác thực JWT, bảo vệ `/internal`, kiểm quyền qua authz, publisher HTTP của outbox, môi trường dev chạy cả ba service trên một DB) mà chưa có endpoint nghiệp vụ nào.

**Architecture:** Thêm vào `lib-core` (lỗi, phân trang, HTTP client), hai module mới `lib-crypto` và `lib-security`, mở rộng `lib-outbox` (publisher `http`) và `test-support` (`TestIssuer` giả lập identity, `DevDatabase`). Ba service bỏ `SecurityConfig` riêng và dùng chuỗi bảo mật chung của `lib-security`. Mỗi phần được chứng minh bằng controller hoặc server giả trong test.

**Tech Stack:** Java 21, Spring Boot 4.1.2 (Spring Security 7.1, Jackson 3 `tools.jackson.*`), Nimbus JOSE 10.9.1 (qua `spring-security-oauth2-jose`), BouncyCastle `bcprov-jdk18on` 1.78.1, zonky embedded Postgres 17, JDK `com.sun.net.httpserver.HttpServer` cho mọi server giả.

**Spec:** `docs/superpowers/specs/2026-11-02-backend-api-design.md` (§5, §6.4, §6.5, §8, §10, §11.1)

## Global Constraints

- Package: `com.rbac_platform.lib.<tên>` cho lib, `com.rbac_platform.testsupport` cho test-support. Auto-configuration đăng ký qua `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; mọi bean của lib có `@ConditionalOnMissingBean` để service thay được.
- Lib không phụ thuộc service; lib không chứa logic nghiệp vụ RBAC.
- Truy cập DB bằng `JdbcClient` + SQL viết tay, không ORM.
- JSON là Jackson 3 (`tools.jackson.databind.json.JsonMapper`), field `camelCase`, thời gian ISO-8601 UTC.
- Lỗi luôn là `application/problem+json` có `code` và `requestId`; `code` lấy **nguyên văn** từ danh sách spec §10: `validation_failed`, `unauthenticated`, `token_expired`, `forbidden`, `mfa_step_up_required`, `not_found`, `tenant_not_found`, `invalid_credentials`, `invalid_challenge`, `invalid_mfa_code`, `password_policy_violation`, `invitation_invalid`, `refresh_token_invalid`, `slug_taken`, `email_taken`, `unknown_resource_type`, `unknown_relation`, `model_revision_conflict`, `range_too_large`, `authz_unavailable`, `internal_error`. Thêm `method_not_allowed` cho 405 (spec không liệt kê; quyết định của plan).
- Header: `X-Request-Id` (đã có, `RequestLoggingFilter.HEADER`), `X-Internal-Token`.
- Timeout gọi service: kết nối 1 giây, đọc 2 giây.
- Access token: `aud = rbac-api`, header `typ = at+jwt`, issuer = `{issuer-prefix}{slug}` với `issuer-prefix` kết thúc bằng `/t/`, slug khớp `[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?`, claim `tid` và `sub` là UUID.
- Không thêm dependency test bên ngoài (không MockWebServer, WireMock): server giả dùng `com.sun.net.httpserver.HttpServer`.
- Làm trên nhánh `feat/core-slice`. Commit bằng `git -c user.name=vuttgl123 -c user.email=phamtuanvu1401@gmail.com commit`, message kết thúc bằng `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Sau khi sửa một lib, chạy `./mvnw -q install -DskipTests` trước khi test module phụ thuộc nó.

## Review Focus

1. Token có `iss` đúng tiền tố nhưng slug không tồn tại hoặc identity không trả lời → phải là `401` trong vài giây, không phải `500` hay treo request (test ở Task 5: `unknownSlugIs401`, `hangingDiscoveryIs401Quickly`).
2. Cursor bị sửa tay (base64 hợp lệ nhưng nội dung rác, UUID hỏng, thời gian hỏng) → `400 validation_failed` với `field = cursor`, không phải `500` (Task 2: `tamperedCursorIsValidationError`).
3. Lỗi phát sinh trong filter bảo mật (trước DispatcherServlet) vẫn phải có `requestId` và `Content-Type: application/problem+json` (Task 5: `missingTokenIsProblemJson`).
4. Tenant bị `suspended` sau khi token đã phát → bị chặn chậm nhất sau `tenant-cache-ttl` (Task 5: `suspendedTenantIsRejectedAfterCacheTtl`).
5. Audit trả `200` nhưng `accepted` không có id của sự kiện → phải coi là thất bại để relay thử lại, không xóa dòng outbox (Task 8: `okWithoutEventIdIsRetryable`).

---

### Task 1: Mô hình lỗi `problem+json` (lib-core)

**Files:**
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/ErrorCodes.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/FieldViolation.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/ApiException.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/ProblemWriter.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/error/ApiExceptionHandler.java`
- Modify: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/CoreAutoConfiguration.java` (thêm bean `ProblemWriter`, `ApiExceptionHandler`)
- Modify: `libs/lib-core/pom.xml` (thêm `spring-boot-starter-validation`)
- Test: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/error/ProblemFixtureController.java`, `.../error/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `final class ErrorCodes` với hằng `String` cho từng mã ở Global Constraints (`VALIDATION_FAILED = "validation_failed"`, …, `METHOD_NOT_ALLOWED = "method_not_allowed"`).
  - `record FieldViolation(String field, String code)`.
  - `class ApiException extends RuntimeException`: `ApiException(HttpStatus status, String code, String detail)`, `ApiException(HttpStatus status, String code, String detail, List<FieldViolation> errors)`; getter `status()`, `code()`, `errors()`; factory tĩnh `validation(List<FieldViolation>)`, `unauthenticated(String detail)`, `forbidden(String detail)`, `notFound(String code, String detail)`, `conflict(String code, String detail)`, `unavailable(String code, String detail)`.
  - `class ProblemWriter`: `ProblemWriter(JsonMapper json)`; `ProblemDetail problem(HttpServletRequest request, HttpStatus status, String code, String detail, List<FieldViolation> errors)`; `void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)`. `requestId` lấy từ attribute `RequestLoggingFilter.REQUEST_ATTRIBUTE`; `title` = reason phrase của status; `type` = `about:blank`; `errors` chỉ có mặt khi khác rỗng.

- [ ] **Step 1: Write the failing test**

`ProblemFixtureController` (test, `@RestController`): `GET /test/conflict` ném `ApiException.conflict("slug_taken", "Slug acme is taken")`; `POST /test/validate` nhận `@Valid @RequestBody Form(@NotBlank String name, @Email String email)`; `GET /test/param?limit=` nhận `@RequestParam int limit`.

`ApiExceptionHandlerTest` (`@SpringBootTest(classes = CoreTestApplication.class, webEnvironment = RANDOM_PORT)`, gọi bằng `java.net.http.HttpClient` như `RequestIdErrorAttributesTest`):

```java
@Test void apiExceptionKeepsStatusAndCode()      // GET /test/conflict → 409, Content-Type application/problem+json,
                                                 // body.code == "slug_taken", body.detail == "Slug acme is taken",
                                                 // body.requestId == header X-Request-Id, body.title == "Conflict"
@Test void beanValidationListsFields()           // POST /test/validate {"name":"","email":"x"} → 400, code "validation_failed",
                                                 // errors chứa {field:"name",code:"not_blank"} và {field:"email",code:"email"}
@Test void malformedJsonIsValidationError()      // POST /test/validate body "{" → 400, code "validation_failed", không có "Exception" trong body
@Test void typeMismatchNamesTheParameter()       // GET /test/param?limit=abc → 400, errors [{field:"limit",code:"type_mismatch"}]
@Test void unknownRouteIsNotFound()              // GET /no/such/path → 404, code "not_found"
@Test void wrongMethodIsMethodNotAllowed()       // DELETE /test/conflict → 405, code "method_not_allowed"
@Test void unexpectedErrorHidesInternals()       // GET /test/boom → 500, code "internal_error", body không chứa "boom" hay "IllegalStateException"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl libs/lib-core test -Dtest=ApiExceptionHandlerTest`
Expected: FAIL (compile error: `ApiException` không tồn tại).

- [ ] **Step 3: Implement**

`ApiExceptionHandler` là `@RestControllerAdvice` trả `ResponseEntity<ProblemDetail>` dựng bằng `ProblemWriter.problem`. Ánh xạ: `ApiException` → status/code của nó; `MethodArgumentNotValidException` và `HandlerMethodValidationException` → 400 `validation_failed`, mỗi lỗi field thành `FieldViolation(field, snake_case(tên annotation constraint))`; `HttpMessageNotReadableException`, `HttpMediaTypeNotSupportedException`, `MissingServletRequestParameterException` → 400 `validation_failed`; `MethodArgumentTypeMismatchException` → 400 với `FieldViolation(tên tham số, "type_mismatch")`; `NoResourceFoundException` → 404 `not_found`; `HttpRequestMethodNotSupportedException` → 405 `method_not_allowed`; `Exception` → 500 `internal_error`, detail `"Unexpected error"`, log ERROR kèm stack. Giữ `RequestIdErrorAttributes` cho lỗi ngoài MVC.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q -pl libs/lib-core test`
Expected: PASS, gồm cả `RequestIdErrorAttributesTest` cũ (body 500 vẫn có `requestId`).

- [ ] **Step 5: Commit**

```bash
git add libs/lib-core
git commit -m "feat(core): problem+json error model with stable codes"
```

---

### Task 2: Phân trang cursor (lib-core)

**Files:**
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/page/Cursor.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/page/PageRequest.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/page/CursorPage.java`
- Test: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/page/CursorPageTest.java`

**Interfaces:**
- Consumes: `ApiException.validation`, `FieldViolation` (Task 1).
- Produces:
  - `record Cursor(Instant at, UUID id)`: `String encode()` (base64url không padding của `at.toString() + "|" + id`), `static Cursor decode(String value)`; chuỗi hỏng → `ApiException.validation(List.of(new FieldViolation("cursor", "invalid_cursor")))`.
  - `record PageRequest(int limit, Cursor after)`: `static PageRequest of(Integer limit, String cursor)`; `limit` null → 50; ngoài 1–200 → `FieldViolation("limit", "out_of_range")`; `cursor` null hoặc rỗng → `after = null`. `int fetchSize()` trả `limit + 1`.
  - `record CursorPage<T>(List<T> items, String nextCursor)`: `static <T> CursorPage<T> of(List<T> fetched, PageRequest page, Function<T, Cursor> keyOf)`; nếu `fetched.size() > page.limit()` thì cắt còn `limit` phần tử và `nextCursor = keyOf(phần tử cuối được giữ).encode()`, ngược lại `nextCursor = null`.

- [ ] **Step 1: Write the failing test**

```java
@Test void cursorRoundTrips()                    // decode(new Cursor(Instant.parse("2026-11-02T09:00:00.123456Z"), id).encode()) bằng nguyên bản
@Test void tamperedCursorIsValidationError()     // decode cho "!!!", base64url("garbage"), base64url("2026-11-02T09:00:00Z|not-a-uuid"),
                                                 // base64url("not-a-time|" + UUID) → đều ApiException status 400, errors == [("cursor","invalid_cursor")]
@Test void limitDefaultsTo50()                   // PageRequest.of(null, null).limit() == 50, after() == null
@Test void limitOutsideRangeIsRejected()         // of(0, null) và of(201, null) → errors == [("limit","out_of_range")]; of(200,null) hợp lệ
@Test void pageTrimsTheExtraRowAndPointsAtLastKept() // fetched 3 phần tử, limit 2 → items 2 phần tử đầu, nextCursor == key(phần tử thứ 2).encode()
@Test void lastPageHasNoNextCursor()             // fetched 2, limit 2 → nextCursor null
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl libs/lib-core test -Dtest=CursorPageTest`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement the three records** theo Interfaces.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl libs/lib-core test -Dtest=CursorPageTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/lib-core
git commit -m "feat(core): opaque cursor pagination"
```

---

### Task 3: HTTP client giữa các service (lib-core)

**Files:**
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/http/HttpClientProperties.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/http/ServiceRestClients.java`
- Create: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/http/RequestIdForwardingInterceptor.java`
- Modify: `libs/lib-core/src/main/java/com/rbac_platform/lib/core/CoreAutoConfiguration.java` (bean `ServiceRestClients`, bật `HttpClientProperties`)
- Test: `libs/lib-core/src/test/java/com/rbac_platform/lib/core/http/ServiceRestClientsTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("rbac.http") record HttpClientProperties(@DefaultValue("1s") Duration connectTimeout, @DefaultValue("2s") Duration readTimeout)`.
  - `class ServiceRestClients`: `ServiceRestClients(HttpClientProperties properties)`; `RestClient create(String baseUrl)`. Dùng `JdkClientHttpRequestFactory` với hai timeout trên, gắn `RequestIdForwardingInterceptor`.
  - `RequestIdForwardingInterceptor implements ClientHttpRequestInterceptor`: nếu MDC có `RequestLoggingFilter.MDC_KEY` thì đặt header `X-Request-Id`; log INFO một dòng `"{method} {uri} -> {status} in {ms} ms"` (không log query string).

- [ ] **Step 1: Write the failing test** (unit test, không cần Spring context; server giả là `HttpServer` trên cổng 0)

```java
@Test void forwardsRequestIdFromMdc()     // MDC.put("requestId","req-7"); GET /ping → server nhận header X-Request-Id: req-7
@Test void noMdcMeansNoHeader()           // không có MDC → server không nhận X-Request-Id
@Test void slowServerTimesOut()           // readTimeout 200ms, server ngủ 1s → ResourceAccessException, thời gian < 900ms
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl libs/lib-core test -Dtest=ServiceRestClientsTest`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement** theo Interfaces.

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -pl libs/lib-core test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/lib-core
git commit -m "feat(core): service RestClient with timeouts and request id forwarding"
```

---

### Task 4: Module `lib-crypto`

**Files:**
- Create: `libs/lib-crypto/pom.xml` (deps: `spring-boot-autoconfigure`, `spring-security-crypto`, `org.bouncycastle:bcprov-jdk18on`; test: `spring-boot-starter-test`)
- Modify: `pom.xml` (module `libs/lib-crypto`; `dependencyManagement`: `lib-crypto` và `org.bouncycastle:bcprov-jdk18on:1.78.1`)
- Create trong `libs/lib-crypto/src/main/java/com/rbac_platform/lib/crypto/`: `Kek.java`, `EnvelopeCipher.java`, `Sealed.java`, `CryptoException.java`, `SecureTokens.java`, `PasswordHasher.java`, `CryptoProperties.java`, `CryptoAutoConfiguration.java`
- Create: `libs/lib-crypto/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `libs/lib-crypto/src/test/java/com/rbac_platform/lib/crypto/EnvelopeCipherTest.java`, `SecureTokensTest.java`, `PasswordHasherTest.java`, `CryptoAutoConfigurationTest.java`

**Interfaces:**
- Produces:
  - `record Kek(String id, SecretKey key)`, `static Kek fromBase64(String id, String base64)`: giải mã phải đúng 32 byte, nếu không thì `IllegalArgumentException("rbac.crypto.kek must decode to 32 bytes but was " + n)`.
  - `record Sealed(String keyId, byte[] ciphertext)`; `ciphertext` = nonce 12 byte ‖ đầu ra AES-256-GCM (tag 128 bit).
  - `class EnvelopeCipher`: `EnvelopeCipher(Kek kek)`; `Sealed seal(byte[] plaintext, byte[] aad)`; `byte[] open(Sealed sealed, byte[] aad)`. `keyId` khác KEK hiện tại hoặc xác thực GCM thất bại thì ném `CryptoException` (unchecked). AAD dùng để gắn bản mã với dòng chứa nó (lát 1 sẽ truyền `tenantId|bảng|id`).
  - `final class SecureTokens`: `static String randomToken(int bytes)` (base64url không padding, `bytes < 32` thì `IllegalArgumentException`); `static String sha256Hex(String value)` (hex chữ thường, 64 ký tự; các cột `token_hash`/`key_hash`/`code_hash` là `text`).
  - `class PasswordHasher`: `String hash(CharSequence raw)`; `boolean verify(CharSequence raw, String phc)`; `void burnVerify(CharSequence raw)` (verify với một hash giả cố định để cân thời gian khi user không tồn tại). Tham số Argon2id: salt 16, hash 32, parallelism 1, memory 19456 KiB, iterations 2.
  - `@ConfigurationProperties("rbac.crypto") record CryptoProperties(String kek, @DefaultValue("env-1") String kekId)`.
  - `CryptoAutoConfiguration`: bean `PasswordHasher` luôn có; bean `EnvelopeCipher` khi `rbac.crypto.kek` được đặt.

- [ ] **Step 1: Write the failing tests**

```java
// EnvelopeCipherTest
@Test void sealThenOpenReturnsPlaintext()
@Test void sameInputTwiceGivesDifferentCiphertext()      // nonce ngẫu nhiên
@Test void wrongAadFails()                               // open với aad khác → CryptoException
@Test void flippedByteFails()                            // sửa 1 byte ciphertext → CryptoException
@Test void foreignKeyIdFails()                           // Sealed("env-2", …) → CryptoException
@Test void kekMustBe32Bytes()                            // fromBase64 của 16 byte → IllegalArgumentException, message chứa "32 bytes"
// SecureTokensTest
@Test void tokenHas256BitsAndIsUrlSafe()                 // randomToken(32).length() == 43, khớp [A-Za-z0-9_-]+
@Test void shortTokensAreRefused()                       // randomToken(16) → IllegalArgumentException
@Test void sha256HexKnownVector()                        // sha256Hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
// PasswordHasherTest
@Test void hashIsArgon2idWithOwaspParameters()           // hash("pw") bắt đầu bằng "$argon2id$v=19$m=19456,t=2,p=1$"
@Test void verifyAcceptsRightAndRejectsWrong()
@Test void burnVerifyDoesNotThrow()
// CryptoAutoConfigurationTest (ApplicationContextRunner)
@Test void cipherOnlyWhenKekConfigured()                 // không có rbac.crypto.kek → không có EnvelopeCipher, có PasswordHasher
@Test void badKekFailsStartup()                          // rbac.crypto.kek=base64 của 10 byte → context fail, message chứa "32 bytes"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q -pl libs/lib-crypto test`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement** theo Interfaces (`Cipher.getInstance("AES/GCM/NoPadding")`, `SecureRandom` dùng chung; `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`).

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -pl libs/lib-crypto test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml libs/lib-crypto
git commit -m "feat(crypto): KEK cipher, secure tokens and Argon2id password hashing"
```

---

### Task 5: Module `lib-security`: resource server JWT nhiều issuer, `Caller`, `TestIssuer`

**Files:**
- Create: `libs/lib-security/pom.xml` (deps: `lib-core`, `lib-db`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`; test: `spring-boot-starter-test`, `test-support`)
- Modify: `pom.xml` (module `libs/lib-security`, `dependencyManagement` cho `lib-security`)
- Modify: `test-support/pom.xml` (thêm `spring-security-oauth2-jose` để có Nimbus)
- Create: `test-support/src/main/java/com/rbac_platform/testsupport/TestIssuer.java`
- Create trong `libs/lib-security/src/main/java/com/rbac_platform/lib/security/`: `SecurityProperties.java`, `TenantJwtDecoders.java`, `TokenValidators.java`, `TenantStatusCache.java`, `Caller.java`, `CallerArgumentResolver.java`, `ProblemAuthenticationEntryPoint.java`, `ProblemAccessDeniedHandler.java`, `RbacSecurityAutoConfiguration.java`
- Create: `libs/lib-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `libs/lib-security/src/test/java/com/rbac_platform/lib/security/SecurityTestApplication.java`, `FixtureController.java`, `ResourceServerTest.java`

**Interfaces:**
- Consumes: `ProblemWriter`, `ApiException`, `ErrorCodes` (Task 1); `ServiceRestClients`/`HttpClientProperties` timeouts (Task 3); `AppDatabase`, `MigratedDatabase` (test-support).
- Produces:
  - `@ConfigurationProperties("rbac.security") record SecurityProperties(String issuerPrefix, @DefaultValue("rbac-api") String audience, @DefaultValue List<String> publicPaths, @DefaultValue("30s") Duration tenantCacheTtl, String internalToken, String authzUrl)`. `issuerPrefix` bắt buộc và phải kết thúc bằng `/t/`, nếu không thì `IllegalArgumentException` nêu tên `rbac.security.issuer-prefix`. (`internalToken`, `authzUrl` dùng ở Task 7.)
  - `class TenantJwtDecoders`: `JwtDecoder decoderFor(String issuer)`; trả `null` nếu `issuer` không khớp đúng `issuerPrefix + slug` (slug theo Global Constraints, không có `/` thừa). Decoder lấy JWKS qua discovery của issuer, mọi HTTP tới identity dùng timeout 1s/2s, cache tối đa 1000 decoder (vượt thì xóa sạch).
  - `final class TokenValidators`: `static OAuth2TokenValidator<Jwt> accessToken(String issuer, String audience, TenantStatusCache tenants)` gộp: issuer khớp; `exp` có lệch tối đa 30 giây, hết hạn → `OAuth2Error("token_expired")`; `aud` chứa audience; header `typ == "at+jwt"`; `tid` và `sub` parse được UUID; tenant `active`.
  - `class TenantStatusCache`: `TenantStatusCache(JdbcClient jdbc, Duration ttl)`; `boolean isActive(UUID tenantId)` chạy `select status from tenancy.tenants where id = ?` (mọi role service đọc được mọi dòng của `tenancy.tenants`), cache theo `ttl`.
  - `record Caller(UUID tenantId, String subjectType, UUID subjectId, UUID sessionId, List<String> amr, String issuer, String token)`: `static Caller from(Jwt jwt)` (`subjectType = "user"`), `boolean hasAmr(String method)`.
  - `CallerArgumentResolver`: controller khai báo tham số `Caller caller`; không có JWT hợp lệ → `ApiException.unauthenticated("Authentication required")`.
  - `ProblemAuthenticationEntryPoint`: 401, `code = token_expired` khi `OAuth2Error` gốc có mã `token_expired`, ngược lại `unauthenticated`; header `WWW-Authenticate: Bearer`. `ProblemAccessDeniedHandler`: 403 `forbidden`.
  - `RbacSecurityAutoConfiguration`: bean `SecurityFilterChain` (khi chưa có): permit `GET /actuator/health`, `/error`, `/internal/**` (Task 7 bảo vệ), và từng mẫu trong `publicPaths`; còn lại `authenticated()`; `oauth2ResourceServer` với `JwtIssuerAuthenticationManagerResolver` dựng từ `TenantJwtDecoders`; CSRF tắt, `STATELESS`, không `httpBasic`, không `formLogin`. Resolver được khai báo thành bean để Boot không sinh user mặc định.
  - `class TestIssuer implements AutoCloseable` (test-support): `static TestIssuer start()`; `String baseUrl()`; `String issuer(String slug)`; `void refuse(String slug)` (discovery của slug đó trả 404); `void hang(String slug)` (discovery của slug đó không trả lời trong 10s); `int discoveryRequests(String slug)`; `TokenBuilder token(String slug)`. `TokenBuilder` có `tenant(UUID)`, `subject(UUID)`, `session(UUID)`, `amr(String...)`, `audience(String)`, `type(String)`, `expiresAt(Instant)`, `claim(String, Object)`, `without(String claim)`, `signedByForeignKey()`, `String sign()`. Mặc định: `aud = rbac-api`, `typ = at+jwt`, `exp = now + 10 phút`, `sub`/`sid` ngẫu nhiên, `amr = ["pwd"]`, ES256 với khóa EC P-256 tạo lúc `start()`. Discovery trả `{"issuer": issuer(slug), "jwks_uri": baseUrl + "/t/" + slug + "/.well-known/jwks.json"}`.

- [ ] **Step 1: Write the failing test**

`SecurityTestApplication` (`@SpringBootApplication`), `FixtureController`: `GET /test/whoami(Caller caller)` trả `{tenantId, subjectId, sessionId, amr}`; `GET /test/public/ping` trả `"pong"`; `GET /test/public/caller(Caller caller)` trả `"unreachable"`.

`ResourceServerTest`: `@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = RANDOM_PORT)`; `@DynamicPropertySource` gọi `AppDatabase.register(registry, "identity_app")`, đặt `rbac.security.issuer-prefix = issuer.baseUrl() + "/t/"`, `rbac.security.public-paths[0] = /test/public/**`, `rbac.security.tenant-cache-ttl = 200ms`. Tenant active tạo bằng `MigratedDatabase.get().insertTenant()`.

```java
@Test void validTokenReachesControllerAsCaller()     // 200; tenantId/subjectId/sessionId đúng claim; amr ["pwd"]
@Test void missingTokenIsProblemJson()               // không header → 401, Content-Type application/problem+json, code "unauthenticated",
                                                     // requestId == header X-Request-Id, WWW-Authenticate không chứa "Basic"
@Test void expiredTokenSaysTokenExpired()            // expiresAt(now - 5 phút) → 401 code "token_expired"
@Test void wrongAudienceIsRejected()                 // audience("other") → 401 "unauthenticated"
@Test void challengeTokenIsRejected()                // type("challenge+jwt") → 401
@Test void foreignSignatureIsRejected()              // signedByForeignKey() → 401
@Test void issuerOutsidePrefixIsRejected()           // claim("iss","http://evil.example/t/acme") → 401
@Test void nestedPathIssuerIsRejected()              // claim("iss", issuer.baseUrl()+"/t/acme/extra") → 401
@Test void missingTenantClaimIsRejected()            // without("tid") → 401
@Test void nonUuidSubjectIsRejected()                // claim("sub","alice") → 401
@Test void unknownSlugIs401()                        // issuer.refuse("ghost"), token của "ghost" → 401 (không phải 500)
@Test void hangingDiscoveryIs401Quickly()            // issuer.hang("slow") → 401 trong < 5 giây
@Test void suspendedTenantIsRejectedAfterCacheTtl()  // token hợp lệ → 200; superuser update status='suspended'; đợi 300ms → 401
@Test void discoveryIsFetchedOncePerIssuer()         // hai request cùng slug → discoveryRequests(slug) == 1
@Test void publicPathNeedsNoToken()                  // GET /test/public/ping → 200 "pong"
@Test void callerOnPublicPathWithoutTokenIs401()     // GET /test/public/caller → 401 code "unauthenticated"
@Test void noGeneratedPassword(CapturedOutput out)   // log không chứa "generated security password"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q install -DskipTests -pl test-support,libs/lib-core -am && ./mvnw -q -pl libs/lib-security test`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `TestIssuer`**, rồi `./mvnw -q -pl test-support install -DskipTests`.

- [ ] **Step 4: Implement các lớp của lib-security** theo Interfaces.

- [ ] **Step 5: Run tests**

Run: `./mvnw -q -pl libs/lib-security test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add pom.xml test-support libs/lib-security
git commit -m "feat(security): multi-issuer JWT resource server, Caller, TestIssuer"
```

---

### Task 6: Chuyển ba service sang `lib-security`

**Files:**
- Delete: `services/{identity,authz,audit}-service/src/main/java/com/rbac_platform/{identity,authz,audit}/SecurityConfig.java`
- Modify: `services/*/pom.xml` (thêm `lib-security`)
- Modify: `services/*/src/main/resources/application.yaml`: thêm `rbac.security.issuer-prefix: ${IDENTITY_PUBLIC_URL:http://localhost:8081}/t/`; riêng identity thêm `rbac.security.public-paths: ["/t/*/auth/**", "/t/*/.well-known/**"]`
- Modify: `services/*/src/test/java/.../<Svc>ServiceApplicationTest.java`

**Interfaces:**
- Consumes: `RbacSecurityAutoConfiguration`, `TestIssuer` (Task 5).

- [ ] **Step 1: Sửa test của từng service**

Trong mỗi `<Svc>ServiceApplicationTest`: thêm `static TestIssuer issuer = TestIssuer.start()` và đặt `rbac.security.issuer-prefix` trong `@DynamicPropertySource`. Đổi tên `noGeneratedLoginExistsAndAnyBasicCredentialIsRejected` thành `basicCredentialsAreRejectedWithoutBasicChallenge`: vẫn 401, log không có "generated security password", và header `WWW-Authenticate` không chứa `Basic`. Thêm:

```java
@Test void validTokenIsAuthenticated()     // token của một tenant active, GET /anything → 404 code "not_found" (đã qua xác thực, không có route)
@Test void unauthenticatedIsProblemJson()  // GET /anything → 401, Content-Type application/problem+json, code "unauthenticated"
```

Riêng identity: `@Test void authPathsArePublic()`: `GET /t/acme/auth/anything` và `GET /t/acme/.well-known/jwks.json` không có token → 404 `not_found` (không phải 401). Dùng GET vì một POST không khớp route sẽ rơi vào resource handler và ra 405.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q install -DskipTests && ./mvnw -q -pl services/identity-service,services/authz-service,services/audit-service test`
Expected: FAIL (`validTokenIsAuthenticated` nhận 401 vì service vẫn dùng `SecurityConfig` cũ).

- [ ] **Step 3: Xóa `SecurityConfig`, thêm dependency và cấu hình** như phần Files.

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -pl services/identity-service,services/authz-service,services/audit-service test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add services
git commit -m "refactor(services): use the shared lib-security filter chain"
```

---

### Task 7: Bảo vệ `/internal/**` và client kiểm tra quyền (lib-security)

**Files:**
- Create: `libs/lib-security/src/main/java/com/rbac_platform/lib/security/InternalTokenFilter.java`
- Create: `libs/lib-security/src/main/java/com/rbac_platform/lib/security/access/ObjectRef.java`, `AccessChecker.java`, `HttpAccessChecker.java`
- Modify: `RbacSecurityAutoConfiguration.java` (gắn filter trước `BearerTokenAuthenticationFilter`; bean `HttpAccessChecker`), `SecurityProperties.java` (validate `internalToken`)
- Modify: `services/*/src/main/resources/application.yaml` (`rbac.security.internal-token: ${RBAC_INTERNAL_TOKEN}`; identity và audit thêm `rbac.security.authz-url: ${AUTHZ_URL:http://localhost:8082}`)
- Modify: `services/*/src/test/java/.../Test<Svc>ServiceApplication.java` (nếu không có `RBAC_INTERNAL_TOKEN` thì đặt system property `rbac.security.internal-token` bằng chuỗi dev cố định 64 ký tự hex), `<Svc>ServiceApplicationTest` (đặt property trong `@DynamicPropertySource`)
- Test: `libs/lib-security/src/test/java/com/rbac_platform/lib/security/InternalTokenFilterTest.java`, `.../access/HttpAccessCheckerTest.java`; thêm `GET /internal/test/ping` vào `FixtureController`

**Interfaces:**
- Consumes: `Caller` (Task 5), `ServiceRestClients` (Task 3), `ApiException`, `ErrorCodes` (Task 1).
- Produces:
  - `SecurityProperties.internalToken` bắt buộc, dài ít nhất 32 ký tự, nếu không thì startup fail với message chứa `rbac.security.internal-token`.
  - `InternalTokenFilter extends OncePerRequestFilter`: chỉ áp dụng cho `/internal/**`; so sánh `X-Internal-Token` bằng `MessageDigest.isEqual`; sai hoặc thiếu → `ProblemWriter.write(…, 401, "unauthenticated", "Internal token required")`. Bearer token không thay được internal token.
  - `record ObjectRef(String type, String id)`, `static ObjectRef tenant(UUID tenantId)` → `("tenant", tenantId.toString())`.
  - `interface AccessChecker { void require(Caller caller, String relation, ObjectRef object); }`.
  - `class HttpAccessChecker implements AccessChecker`: `HttpAccessChecker(RestClient authz)`. Gửi `POST /v1/check` với body `{"permission": relation, "object": {"type": …, "id": …}}` (không có `subject`), header `Authorization: Bearer <caller.token()>`. `result == "allowed"` → trả về; `"denied"` hoặc `"conditional"` → `ApiException.forbidden("Missing " + relation + " on " + type)`; status khác 200, lỗi mạng, timeout, JSON hỏng → `ApiException.unavailable("authz_unavailable", "Authorization service unavailable")`. Bean chỉ tạo khi `rbac.security.authz-url` có giá trị và chưa có bean `AccessChecker` (authz-service sẽ tự cài bản trong process ở lát 1).

- [ ] **Step 1: Write the failing tests**

```java
// InternalTokenFilterTest (SpringBootTest như ResourceServerTest, rbac.security.internal-token = "x".repeat(40))
@Test void correctTokenPasses()               // GET /internal/test/ping + X-Internal-Token đúng → 200
@Test void wrongTokenIs401()                  // → 401 problem code "unauthenticated"
@Test void missingTokenIs401()
@Test void bearerTokenDoesNotOpenInternal()   // chỉ có JWT hợp lệ của TestIssuer → 401
@Test void shortTokenFailsStartup()           // ApplicationContextRunner/SpringApplicationBuilder với token 10 ký tự → fail, message chứa "rbac.security.internal-token"
// HttpAccessCheckerTest (unit, HttpServer giả làm authz)
@Test void allowedReturnsAndSendsCallerToken()  // server trả {"result":"allowed"}; nhận body {"permission":"admin","object":{"type":"tenant","id":"<uuid>"}},
                                                // Authorization "Bearer <token>", X-Request-Id từ MDC
@Test void deniedIsForbidden()                   // {"result":"denied"} → ApiException 403 code "forbidden"
@Test void conditionalIsForbidden()              // {"result":"conditional"} → 403
@Test void serverErrorIsUnavailable()            // 500 → 503 code "authz_unavailable"
@Test void timeoutIsUnavailable()                // readTimeout 200ms, server ngủ 1s → 503
@Test void garbageIsUnavailable()                // body "<html>" → 503
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q -pl libs/lib-security test`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement** theo Interfaces; cập nhật yaml và test của ba service như phần Files.

- [ ] **Step 4: Run tests**

Run: `./mvnw -q install -DskipTests && ./mvnw -q -pl libs/lib-security,services/identity-service,services/authz-service,services/audit-service test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/lib-security services
git commit -m "feat(security): internal token guard and authz-backed AccessChecker"
```

---

### Task 8: Publisher `http` cho outbox (lib-outbox)

**Files:**
- Create: `libs/lib-outbox/src/main/java/com/rbac_platform/lib/outbox/publish/HttpOutboxPublisher.java`
- Modify: `libs/lib-outbox/src/main/java/com/rbac_platform/lib/outbox/OutboxProperties.java` (thêm `@DefaultValue Http http`)
- Modify: `libs/lib-outbox/src/main/java/com/rbac_platform/lib/outbox/OutboxAutoConfiguration.java` (bean khi `rbac.outbox.publisher=http`)
- Modify: các test đang gọi constructor `OutboxProperties` (thêm tham số `http`)
- Test: `libs/lib-outbox/src/test/java/com/rbac_platform/lib/outbox/HttpOutboxPublisherTest.java`; thêm case vào `OutboxAutoConfigurationTest`

**Interfaces:**
- Consumes: `ServiceRestClients` (Task 3), `EventEnvelope`, `NonRetryableEventException` (lib-core, đã có).
- Produces:
  - `record OutboxProperties.Http(String url, String internalToken)`.
  - `class HttpOutboxPublisher implements OutboxPublisher`: `HttpOutboxPublisher(RestClient client, String internalToken)`. Mỗi lần `publish(event)` gửi `POST` tới URL gốc của client (là URL đầy đủ, ví dụ `{AUDIT_URL}/internal/v1/events`), body `{"events":[envelope]}`, header `X-Internal-Token`. Envelope có dạng `{"id","tenantId","aggregateType","aggregateId","eventType","payload","headers","occurredAt"}`, `occurredAt` là chuỗi ISO-8601. Kết quả: 2xx và `accepted` chứa `event.id()` → trả về; 2xx mà thiếu id → `IllegalStateException` (thử lại); 408, 429, 5xx, lỗi mạng → ném lại exception (thử lại); 4xx khác → `NonRetryableEventException("Event rejected with status " + status)`.
  - Auto-config: publisher `http` mà `url` trống hoặc `internalToken` ngắn hơn 32 ký tự → startup fail với message chứa `rbac.outbox.http.url` hoặc `rbac.outbox.http.internal-token`.
- Chưa bật trong service nào: lát 1 mới có endpoint nhận ở audit. Mỗi request gửi một sự kiện vì `OutboxPublisher` nhận từng sự kiện một; endpoint nhận vẫn hỗ trợ danh sách.

- [ ] **Step 1: Write the failing tests**

```java
// HttpOutboxPublisherTest (unit, HttpServer giả)
@Test void deliversEnvelopeWithInternalToken()  // server nhận X-Internal-Token đúng; body.events[0].id == event.id(),
                                                // body.events[0].occurredAt == "2026-11-02T09:00:00Z"; trả {"accepted":[id]} → publish không ném
@Test void okWithoutEventIdIsRetryable()        // {"accepted":[]} → ném exception không phải NonRetryableEventException
@Test void serverErrorIsRetryable()             // 503 → ném exception không phải NonRetryableEventException
@Test void tooManyRequestsIsRetryable()         // 429 → như trên
@Test void badRequestIsNotRetryable()           // 400 → NonRetryableEventException
@Test void connectionRefusedIsRetryable()       // URL tới cổng không có server → ném exception không phải NonRetryableEventException
// OutboxAutoConfigurationTest
@Test void httpPublisherNeedsUrl()              // publisher=http, không url → context fail, message chứa "rbac.outbox.http.url"
@Test void httpPublisherIsSelectedByName()      // publisher=http, url + token hợp lệ → bean OutboxPublisher là HttpOutboxPublisher
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q -pl libs/lib-outbox test -Dtest='HttpOutboxPublisherTest,OutboxAutoConfigurationTest'`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement** theo Interfaces.

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -pl libs/lib-outbox test`
Expected: PASS (gồm các test outbox cũ).

- [ ] **Step 5: Commit**

```bash
git add libs/lib-outbox
git commit -m "feat(outbox): http publisher with retry classification"
```

---

### Task 9: DB dev dùng chung, `scripts/dev.sh`, Vite proxy, README

**Files:**
- Modify: `db-migrations/src/test/java/com/rbac_platform/testsupport/MigratedDatabase.java`: tách `start()` thành `private static MigratedDatabase start(int port)` (0 = cổng ngẫu nhiên, dùng `EmbeddedPostgres.builder().setPort(port)`), thêm `public static MigratedDatabase startStandalone(int port)` (không đụng singleton)
- Modify: `test-support/src/main/java/com/rbac_platform/testsupport/AppDatabase.java`: thêm `public static void enableLogin(MigratedDatabase db, String role)`; bản cũ gọi bản mới với `MigratedDatabase.get()`
- Create: `test-support/src/main/java/com/rbac_platform/testsupport/DevDatabase.java`
- Modify: `test-support/pom.xml` (`exec-maven-plugin` 3.5.0, `mainClass` = `com.rbac_platform.testsupport.DevDatabase`)
- Create: `scripts/dev.sh` (thực thi được)
- Modify: `frontend/vite.config.ts` (proxy)
- Modify: `README.md` (mục "Chạy toàn bộ (dev)", bảng biến môi trường), `.env.example` (`IDENTITY_PUBLIC_URL`, `AUTHZ_URL`, `RBAC_INTERNAL_TOKEN`)

**Interfaces:**
- Consumes: mọi cấu hình service từ Task 6–7.
- Produces:
  - `DevDatabase.main(String[] args)`: cổng lấy từ `args[0]`, rồi `DEV_DB_PORT`, mặc định `54329`; `startStandalone(port)`; `enableLogin` cho `identity_app`, `authz_app`, `audit_app`, `rbac_admin`; in đúng một dòng `DEV DB READY jdbc:postgresql://localhost:<port>/postgres`; sau đó chặn tới khi bị tắt (shutdown hook đóng Postgres).
  - `scripts/dev.sh [--no-install] [--no-fe]`:
    1. Trừ khi `--no-install`: `./mvnw -q install -DskipTests`.
    2. Export `DB_URL=jdbc:postgresql://localhost:${DEV_DB_PORT:-54329}/postgres`; `IDENTITY_DB_USER=identity_app`, `AUTHZ_DB_USER=authz_app`, `AUDIT_DB_USER=audit_app`, ba mật khẩu `unused` (Postgres nhúng tin cậy kết nối local); `IDENTITY_PUBLIC_URL=http://localhost:8081`; `AUTHZ_URL=http://localhost:8082`; `RBAC_INTERNAL_TOKEN` (giữ nếu đã có, nếu không thì `openssl rand -hex 32`).
    3. Mỗi tiến trình chạy bằng `setsid` trong nền, output đi qua `sed -u "s/^/[tên] /"`: `[db]` = `./mvnw -q -pl test-support exec:java`; đợi dòng `DEV DB READY` tối đa 120 giây; rồi `[identity]`, `[authz]`, `[audit]` = `./mvnw -q -pl services/<svc> spring-boot:run`; đợi `curl -sf localhost:808x/actuator/health` của cả ba (tối đa 180 giây); rồi `[fe]` = `npm --prefix frontend run dev` (trừ khi `--no-fe`).
    4. In bảng URL. `trap` cho `EXIT INT TERM` gửi `kill -TERM -- -<pgid>` tới từng nhóm tiến trình, để Ctrl+C không bỏ lại java hay postgres mồ côi.
  - Vite proxy: `/api/identity` → `http://localhost:8081`, `/api/authz` → `:8082`, `/api/audit` → `:8083`, bỏ tiền tố bằng `rewrite`.

- [ ] **Step 1: Implement `MigratedDatabase.startStandalone`, `AppDatabase.enableLogin(db, role)`, `DevDatabase`**

- [ ] **Step 2: Verify the dev database alone**

Run: `./mvnw -q install -DskipTests && timeout 120 ./mvnw -q -pl test-support exec:java | head -n 20`
Expected: có dòng `DEV DB READY jdbc:postgresql://localhost:54329/postgres`.

- [ ] **Step 3: Write `scripts/dev.sh`, Vite proxy, README, `.env.example`**

- [ ] **Step 4: Verify the whole stack**

Run: `scripts/dev.sh --no-fe`, rồi ở terminal khác:
```bash
for p in 8081 8082 8083; do curl -s localhost:$p/actuator/health; echo; done
curl -si localhost:8082/anything | sed -n '1p;/Content-Type/p'
```
Expected: ba dòng `{"status":"UP"}`; `HTTP/1.1 401` và `Content-Type: application/problem+json`. Log của cả ba service in cùng một JDBC URL `localhost:54329`. Ctrl+C ở `dev.sh` rồi `pgrep -f 'spring-boot:run|DevDatabase|postgres -D'` không còn tiến trình nào.

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add db-migrations test-support scripts/dev.sh frontend/vite.config.ts README.md .env.example
git commit -m "feat(dev): shared dev database and one-command dev stack"
```

---

## Ngoài phạm vi của plan này (để lát 1)

- Bật publisher `http` ở identity/authz và endpoint `/internal/v1/events` ở audit (cùng lúc, để không có sự kiện bị chôn vì chưa có nơi nhận).
- Đưa `lib-crypto` vào identity, cùng các biến `RBAC_KEK`, `OPERATOR_DB_USER`/`OPERATOR_DB_PASSWORD`, `FRONTEND_URL`: chỉ thêm khi có code dùng, để service không đòi biến môi trường cho tính năng chưa có.
- Cấp `SELECT (status, deleted_at)` cho `authz_app` (spec §7.4).
