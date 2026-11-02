package com.rbac_platform.lib.core.page;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.error.FieldViolation;
import java.util.List;

/** Validated page parameters of a list call. Repositories fetch {@link #fetchSize()} rows to detect a next page. */
public record PageRequest(int limit, Cursor after) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static PageRequest of(Integer limit, String cursor) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1 || effective > MAX_LIMIT) {
            throw ApiException.validation(List.of(new FieldViolation("limit", "out_of_range")));
        }
        Cursor after = cursor == null || cursor.isEmpty() ? null : Cursor.decode(cursor);
        return new PageRequest(effective, after);
    }

    public int fetchSize() {
        return limit + 1;
    }
}
