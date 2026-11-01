package io.testforge.db.state;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Table and column names a test gave, quoted for the database they run on, so
 * a table called {@code order} or a column called {@code user} — reserved
 * words — still reads.
 *
 * <p>A plain identifier is folded the way the database folds an unquoted one
 * (PostgreSQL lower, H2 and Oracle upper) before it is quoted, so quoting does
 * not make it case-sensitive: {@code orders} names the same table it named
 * unquoted. SQL is built with {@link #name} markers, before a connection is at
 * hand, and {@link #resolve}d on the connection that runs it.
 */
final class SqlNames {

    private static final char OPEN = '\u0001';
    private static final char CLOSE = '\u0002';

    private SqlNames() {
    }

    /** A marker for a validated identifier ({@link TableQuery#identifier}), {@code schema.table} allowed. */
    static String name(String identifier) {
        return OPEN + identifier + CLOSE;
    }

    /** Every marker replaced by its quoted, case-folded name. */
    static String resolve(String sql, DatabaseMetaData meta) throws SQLException {
        if (sql.indexOf(OPEN) < 0) {
            return sql;
        }
        String quote = meta.getIdentifierQuoteString();
        quote = quote == null ? "" : quote.strip();
        boolean upper = meta.storesUpperCaseIdentifiers();
        boolean lower = !upper && meta.storesLowerCaseIdentifiers();
        StringBuilder out = new StringBuilder(sql.length());
        int from = 0;
        for (int open = sql.indexOf(OPEN); open >= 0; open = sql.indexOf(OPEN, from)) {
            int close = sql.indexOf(CLOSE, open);
            out.append(sql, from, open);
            String[] parts = sql.substring(open + 1, close).split("\\.");
            for (int i = 0; i < parts.length; i++) {
                String part = upper ? parts[i].toUpperCase(Locale.ROOT)
                        : lower ? parts[i].toLowerCase(Locale.ROOT) : parts[i];
                out.append(i == 0 ? "" : ".").append(quote).append(part).append(quote);
            }
            from = close + 1;
        }
        return out.append(sql, from, sql.length()).toString();
    }
}
