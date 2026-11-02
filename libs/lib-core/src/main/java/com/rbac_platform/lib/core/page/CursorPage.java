package com.rbac_platform.lib.core.page;

import java.util.List;
import java.util.function.Function;

/** One page of results plus the opaque cursor of the next one ({@code null} on the last page). */
public record CursorPage<T>(List<T> items, String nextCursor) {

    /** @param fetched up to {@code page.fetchSize()} rows; the extra row only signals that more exist */
    public static <T> CursorPage<T> of(List<T> fetched, PageRequest page, Function<T, Cursor> keyOf) {
        if (fetched.size() > page.limit()) {
            List<T> items = List.copyOf(fetched.subList(0, page.limit()));
            return new CursorPage<>(items, keyOf.apply(items.get(items.size() - 1)).encode());
        }
        return new CursorPage<>(List.copyOf(fetched), null);
    }
}
