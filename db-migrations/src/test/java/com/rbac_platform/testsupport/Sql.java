package com.rbac_platform.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Small JDBC helpers for the database tests. */
public final class Sql {

    private Sql() {}

    /** Runs a {@code select count(*)}-style query and returns the first column as long. */
    public static long count(Connection c, String sql, Object... args) {
        Long n = one(c, Long.class, sql, args);
        if (n == null) {
            throw new IllegalStateException("count query returned NULL: " + sql);
        }
        return n;
    }

    /** Executes a statement and returns the update count (0 when it returns rows instead). */
    public static int exec(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            // execute() also accepts statements that return rows, e.g. "select util.enable_tenant_rls(...)"
            return ps.execute() ? 0 : ps.getUpdateCount();
        } catch (SQLException e) {
            throw sneaky(e);
        }
    }

    /** Returns the first column of the first row, or null when there is no row or the value is NULL. */
    public static <T> T one(Connection c, Class<T> type, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, type) : null;
            }
        } catch (SQLException e) {
            throw sneaky(e);
        }
    }

    /** Runs the action, expects a SQLException and returns its SQLState. Fails when nothing is thrown. */
    public static String sqlState(ThrowingRunnable r) {
        return expectSqlException(r).getSQLState();
    }

    /** Runs the action, expects a SQLException and returns its message. Fails when nothing is thrown. */
    public static String errorMessage(ThrowingRunnable r) {
        return expectSqlException(r).getMessage();
    }

    private static SQLException expectSqlException(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof SQLException e) {
                    return e;
                }
            }
            throw new AssertionError("expected a SQLException but got " + t, t);
        }
        throw new AssertionError("expected a SQLException but nothing was thrown");
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    /** Rethrows a checked SQLException without wrapping so tests can see the original SQLState. */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
