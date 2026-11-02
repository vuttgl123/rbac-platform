package com.rbac_platform.lib.core.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CursorPageTest {

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable call, String field, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.errors()).containsExactly(new FieldViolation(field, code));
        });
    }

    @Test
    void cursorRoundTrips() {
        Cursor c = new Cursor(Instant.parse("2026-11-02T09:00:00.123456Z"), UUID.randomUUID());
        assertThat(Cursor.decode(c.encode())).isEqualTo(c);
    }

    @Test
    void tamperedCursorIsValidationError() {
        UUID id = UUID.randomUUID();
        for (String bad : List.of("!!!", b64("garbage"), b64("2026-11-02T09:00:00Z|not-a-uuid"),
                b64("not-a-time|" + id), b64("2026-11-02T09:00:00Z|" + id + "|x"), "")) {
            assertInvalid(() -> Cursor.decode(bad), "cursor", "invalid_cursor");
        }
    }

    @Test
    void limitDefaultsTo50() {
        PageRequest p = PageRequest.of(null, null);
        assertThat(p.limit()).isEqualTo(50);
        assertThat(p.after()).isNull();
        assertThat(p.fetchSize()).isEqualTo(51);
    }

    @Test
    void limitOutsideRangeIsRejected() {
        assertInvalid(() -> PageRequest.of(0, null), "limit", "out_of_range");
        assertInvalid(() -> PageRequest.of(201, null), "limit", "out_of_range");
        assertThat(PageRequest.of(200, null).limit()).isEqualTo(200);
        assertThat(PageRequest.of(5, "").after()).isNull();
    }

    private record Row(Instant at, UUID id) {
        Cursor key() {
            return new Cursor(at, id);
        }
    }

    private static Row row(int n) {
        return new Row(Instant.parse("2026-11-02T09:00:00Z").plusSeconds(n), UUID.randomUUID());
    }

    @Test
    void pageTrimsTheExtraRowAndPointsAtLastKept() {
        List<Row> fetched = List.of(row(1), row(2), row(3));
        CursorPage<Row> page = CursorPage.of(fetched, PageRequest.of(2, null), Row::key);
        assertThat(page.items()).containsExactly(fetched.get(0), fetched.get(1));
        assertThat(page.nextCursor()).isEqualTo(fetched.get(1).key().encode());
    }

    @Test
    void lastPageHasNoNextCursor() {
        CursorPage<Row> page = CursorPage.of(List.of(row(1), row(2)), PageRequest.of(2, null), Row::key);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }
}
