package io.testforge.db.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Rules the data must keep, whatever the schema enforces: no order item
 * without its order, no business key twice, no status outside the known set,
 * no NULL where the logic needs a value. A backend can write the row a test
 * expected and still break one of these — a retry that inserted a duplicate, a
 * delete that left orphans — and nothing else notices.
 *
 * <pre>{@code
 * db.invariants()
 *         .within("orders", "customer_id = ?", customer)
 *         .noOrphans("order_items.order_id", "orders.id")
 *         .unique("orders", "external_id")
 *         .allowedValues("orders", "status", "NEW", "PAID", "CANCELLED")
 *         .notNull("orders", "customer_id", "created_at")
 *         .holds("orders", "total >= 0")
 *         .assertHolds();
 * }</pre>
 *
 * <p>Every rule runs, then {@link #assertHolds()} lists every one that was
 * broken, with how many rows broke it and up to {@value #SAMPLE} of them.
 * Table and column names are checked to be plain identifiers; conditions are
 * the test's own SQL.
 */
public final class DbInvariants {

    static final int SAMPLE = 5;

    private final DataSource dataSource;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Scope> scopes = new LinkedHashMap<>();

    DbInvariants(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Only these rows of {@code table} are checked — the test's own data on a
     * shared database. Applies to every rule reading the table, including an
     * orphan check whose child it is.
     */
    public DbInvariants within(String table, String condition, Object... parameters) {
        scopes.put(key(table), new Scope(condition, parameters == null ? new Object[0] : parameters.clone()));
        return this;
    }

    /** Every non-NULL {@code child.column} has a {@code parent.column} — {@code noOrphans("items.order_id", "orders.id")}. */
    public DbInvariants noOrphans(String childColumn, String parentColumn) {
        String[] child = qualified(childColumn);
        String[] parent = qualified(parentColumn);
        rules.add(new Rule("no orphans: every " + childColumn + " exists as " + parentColumn, child[0],
                "SELECT c.* FROM " + child[0] + " c WHERE c." + child[1] + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "
                        + parent[0] + " p WHERE p." + parent[1] + " = c." + child[1] + ")", new Object[0], "c"));
        return this;
    }

    /** No two rows share these columns (rows with a NULL among them are not compared, as in SQL). */
    public DbInvariants unique(String table, String... columns) {
        requireColumns(columns);
        String list = String.join(", ", columns);
        String notNull = String.join(" AND ", Arrays.stream(columns).map(column -> column + " IS NOT NULL").toList());
        rules.add(new Rule("unique: " + table + "(" + list + ")", table,
                "SELECT " + list + ", COUNT(*) AS duplicates FROM " + table + " WHERE " + notNull + " %s GROUP BY "
                        + list + " HAVING COUNT(*) > 1", new Object[0], null).asGrouped());
        return this;
    }

    /** {@code column} holds only these values (NULL aside — see {@link #notNull}). */
    public DbInvariants allowedValues(String table, String column, Object... allowed) {
        requireColumns(column);
        if (allowed == null || allowed.length == 0) {
            throw new IllegalArgumentException("allowedValues(" + table + ", " + column + ") needs at least one value");
        }
        String marks = String.join(", ", java.util.Collections.nCopies(allowed.length, "?"));
        rules.add(new Rule("allowed values: " + table + "." + column + " in " + Arrays.toString(allowed), table,
                "SELECT " + column + ", COUNT(*) AS rows_with_it FROM " + table + " WHERE " + column + " IS NOT NULL AND "
                        + column + " NOT IN (" + marks + ") %s GROUP BY " + column, values(allowed), null).asGrouped());
        return this;
    }

    /** No row has NULL in any of these columns. */
    public DbInvariants notNull(String table, String... columns) {
        requireColumns(columns);
        for (String column : columns) {
            rules.add(new Rule("not null: " + table + "." + column, table,
                    "SELECT * FROM " + table + " WHERE " + column + " IS NULL", new Object[0], null));
        }
        return this;
    }

    /**
     * Every non-NULL {@code column} has a matching {@code matchingColumn} —
     * {@code everyRowHas("orders.id", "outbox.aggregate_id")}: each new order
     * has its outbox event. The same check as {@link #noOrphans}, read the
     * other way round.
     */
    public DbInvariants everyRowHas(String column, String matchingColumn) {
        String[] from = qualified(column);
        String[] to = qualified(matchingColumn);
        rules.add(new Rule("every " + column + " has a " + matchingColumn, from[0],
                "SELECT c.* FROM " + from[0] + " c WHERE c." + from[1] + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "
                        + to[0] + " p WHERE p." + to[1] + " = c." + from[1] + ")", new Object[0], "c"));
        return this;
    }

    /**
     * A stored total equals the sum of its parts —
     * {@code totalMatches("orders.total", "orders.id", "order_items.amount", "order_items.order_id")}.
     * A parent without parts must total zero.
     */
    public DbInvariants totalMatches(String total, String key, String part, String partKey) {
        return aggregate("total matches: " + total + " = sum of " + part, total, key,
                "COALESCE(SUM(i." + qualified(part)[1] + "), 0)", partKey, "parts_sum");
    }

    /**
     * A stored count equals the number of child rows —
     * {@code countMatches("orders.item_count", "orders.id", "order_items.order_id")}.
     */
    public DbInvariants countMatches(String count, String key, String childKey) {
        return aggregate("count matches: " + count + " = rows of " + qualified(childKey)[0], count, key,
                "COUNT(*)", childKey, "child_rows");
    }

    private DbInvariants aggregate(String description, String stored, String key, String expression, String childKey,
                                   String label) {
        String[] parent = qualified(stored);
        String[] parentKey = qualified(key);
        String[] child = qualified(childKey);
        if (!parent[0].equalsIgnoreCase(parentKey[0])) {
            throw new IllegalArgumentException(stored + " and " + key + " must be columns of one table");
        }
        String computed = "(SELECT " + expression + " FROM " + child[0] + " i WHERE i." + child[1] + " = c."
                + parentKey[1] + ")";
        rules.add(new Rule(description, parent[0],
                "SELECT c." + parentKey[1] + ", c." + parent[1] + ", " + computed + " AS " + label + " FROM " + parent[0]
                        + " c WHERE COALESCE(c." + parent[1] + ", 0) <> " + computed, new Object[0], "c"));
        return this;
    }

    /** {@code condition} is true for every row — {@code holds("orders", "total >= 0")}. */
    public DbInvariants holds(String table, String condition, Object... parameters) {
        rules.add(new Rule("holds: " + table + " where " + condition, table,
                "SELECT * FROM " + table + " WHERE NOT (" + condition + ")",
                parameters == null ? new Object[0] : parameters.clone(), null));
        return this;
    }

    /** Runs every rule; never throws on a broken rule. */
    public Report verify() {
        if (rules.isEmpty()) {
            throw new IllegalStateException("No invariant to verify: add noOrphans, unique, allowedValues, notNull or holds");
        }
        List<Violation> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (Rule rule : rules) {
                Violation violation = run(connection, rule);
                if (violation != null) {
                    violations.add(violation);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not verify the invariants: " + e.getMessage(), e);
        }
        return new Report(rules.size(), violations);
    }

    /**
     * Runs the rules until they all hold or {@code timeout} passes — for data a
     * system completes asynchronously: the outbox relay, the projection, the
     * recalculated total. Fails with the last report, so the rules still
     * broken at the deadline are the ones listed.
     */
    public Report assertHoldsWithin(java.time.Duration timeout) {
        java.time.Duration interval = timeout.dividedBy(10).compareTo(java.time.Duration.ofMillis(200)) > 0
                ? java.time.Duration.ofMillis(200) : timeout.dividedBy(10);
        java.util.concurrent.atomic.AtomicReference<Report> last = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> broken = new java.util.concurrent.atomic.AtomicReference<>();
        Report failedToRun = new Report(0, List.of(new Violation("could not run", 0, false, List.of())));
        try {
            return new io.testforge.core.wait.Waiter(new io.testforge.core.wait.WaitProperties(timeout,
                    interval.isZero() ? java.time.Duration.ofMillis(1) : interval))
                    .await("invariants hold", () -> {
                        try {
                            Report report = verify();
                            last.set(report);
                            return report;
                        } catch (IllegalStateException e) {
                            // a rule that cannot run (a typo, a missing column) will not start working by waiting
                            broken.set(e);
                            return failedToRun;
                        }
                    }, Report::holds, report -> report == failedToRun);
        } catch (io.testforge.core.wait.WaitAbortedException e) {
            throw broken.get();
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            Report report = last.get();
            throw new AssertionError("still broken after " + timeout + ": "
                    + (report == null ? e.getMessage() : report.toString()));
        }
    }

    /** Runs every rule and fails with all that were broken. */
    public Report assertHolds() {
        Report report = verify();
        if (!report.holds()) {
            throw new AssertionError(report.toString());
        }
        return report;
    }

    private Violation run(Connection connection, Rule rule) throws SQLException {
        Scope scope = scopes.get(key(rule.table()));
        String scoped = rule.sql();
        List<Object> parameters = new ArrayList<>(Arrays.asList(rule.parameters()));
        // the scope names the table's own columns; in an orphan check they resolve to the child row
        String filter = scope == null ? "" : " AND (" + scope.condition() + ")";
        if (rule.grouped()) {
            scoped = String.format(scoped, filter);
        } else {
            scoped = scoped + filter;
        }
        if (scope != null) {
            parameters.addAll(Arrays.asList(scope.parameters()));
        }
        int count = 0;
        List<String> samples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(scoped)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    count++;
                    if (samples.size() < SAMPLE) {
                        samples.add(row(result, meta));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check " + rule.description() + ": " + e.getMessage(), e);
        }
        return count == 0 ? null : new Violation(rule.description(), count, rule.grouped(), samples);
    }

    private static String row(ResultSet result, ResultSetMetaData meta) throws SQLException {
        StringBuilder out = new StringBuilder("{");
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            out.append(column == 1 ? "" : ", ").append(meta.getColumnLabel(column).toLowerCase(Locale.ROOT))
                    .append('=').append(result.getObject(column));
        }
        return out.append('}').toString();
    }

    private static Object[] values(Object[] allowed) {
        Object[] values = new Object[allowed.length];
        for (int i = 0; i < allowed.length; i++) {
            values[i] = allowed[i] instanceof Enum<?> e ? e.name() : allowed[i];
        }
        return values;
    }

    private static String[] qualified(String column) {
        int dot = column == null ? -1 : column.lastIndexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("'" + column + "' must be table.column");
        }
        return new String[] {
                TableQuery.identifier(column.substring(0, dot), "table"),
                TableQuery.identifier(column.substring(dot + 1), "column")};
    }

    private static void requireColumns(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("at least one column is needed");
        }
        for (String column : columns) {
            TableQuery.identifier(column, "column");
        }
    }

    private static String key(String table) {
        return TableQuery.identifier(table, "table").toLowerCase(Locale.ROOT);
    }

    private record Scope(String condition, Object[] parameters) {
    }

    private record Rule(String description, String table, String sql, Object[] parameters, String alias,
                        boolean grouped) {

        Rule(String description, String table, String sql, Object[] parameters, String alias) {
            this(description, TableQuery.identifier(table, "table"), sql, parameters, alias, false);
        }

        Rule asGrouped() {
            return new Rule(description, table, sql, parameters, alias, true);
        }
    }

    /**
     * One broken rule.
     *
     * @param rows    rows breaking it; for {@code unique} and {@code allowedValues},
     *                distinct offending values
     * @param samples up to five of them, as {@code {column=value, ...}}
     */
    public record Violation(String rule, int rows, boolean grouped, List<String> samples) {

        public Violation {
            samples = List.copyOf(samples);
        }
    }

    /** What the rules found: how many ran, and every one that was broken. */
    public record Report(int rules, List<Violation> violations) {

        public Report {
            violations = List.copyOf(violations);
        }

        public boolean holds() {
            return violations.isEmpty();
        }

        @Override
        public String toString() {
            if (holds()) {
                return "all " + rules + " invariant(s) hold";
            }
            StringBuilder out = new StringBuilder().append(violations.size()).append(" of ").append(rules)
                    .append(" invariant(s) broken:");
            for (Violation violation : violations) {
                out.append("\n  - ").append(violation.rule()).append(": ").append(violation.rows())
                        .append(violation.grouped() ? (violation.rows() == 1 ? " value" : " values")
                                : (violation.rows() == 1 ? " row" : " rows"));
                violation.samples().forEach(sample -> out.append("\n      ").append(sample));
                if (violation.rows() > violation.samples().size()) {
                    out.append("\n      ... and ").append(violation.rows() - violation.samples().size()).append(" more");
                }
            }
            return out.toString();
        }
    }
}
