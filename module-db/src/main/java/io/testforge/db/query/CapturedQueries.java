package io.testforge.db.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The statements one window saw, oldest first, with expectations over them. */
public final class CapturedQueries {

    /** What a statement does, from its first keyword. */
    public enum Kind { SELECT, INSERT, UPDATE, DELETE, MERGE, OTHER }

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bIN\\s*\\(\\s*\\?(\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern BOUNDED_READ = Pattern.compile("(?i)\\b(WHERE|LIMIT|FETCH\\s+(FIRST|NEXT)|TOP)\\b");
    private static final Pattern TABLES = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([\\w.\"`\\[\\]]+)");

    /**
     * One executed statement.
     *
     * @param batch how many parameter sets it ran with (1 unless batched)
     * @param error the failure's type, or {@code null}
     */
    public record Statement(String dataSource, String sql, int batch, long millis, String error, String thread,
                            Thread owner) {

        /** {@code SELECT * FROM items WHERE order_id = ?}: literals and IN-lists folded, so repeats group. */
        public String shape() {
            String folded = STRING_LITERAL.matcher(sql).replaceAll("?");
            folded = NUMBER_LITERAL.matcher(folded).replaceAll("?");
            folded = IN_LIST.matcher(folded).replaceAll("IN (?)");
            return SPACE.matcher(folded.strip()).replaceAll(" ");
        }

        /** The SQL with string literals blanked, so a {@code 'where'} inside one is not read as a clause. */
        String withoutLiterals() {
            return STRING_LITERAL.matcher(sql).replaceAll("''");
        }

        /** Tables named after {@code FROM} and {@code JOIN}, unquoted and lower-cased. */
        public List<String> tables() {
            List<String> tables = new ArrayList<>();
            java.util.regex.Matcher matcher = TABLES.matcher(withoutLiterals());
            while (matcher.find()) {
                String name = matcher.group(1).replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && !name.startsWith("(") && !tables.contains(name)) {
                    tables.add(name);
                }
            }
            return tables;
        }

        /** An {@code UPDATE} or {@code DELETE} with no {@code WHERE}, or a {@code TRUNCATE}: every row of a table. */
        public boolean unboundedWrite() {
            String text = withoutLiterals();
            Kind kind = kind();
            if (kind == Kind.UPDATE || kind == Kind.DELETE) {
                return !WHERE.matcher(text).find();
            }
            return kind == Kind.OTHER && text.strip().toUpperCase(Locale.ROOT).startsWith("TRUNCATE");
        }

        /** A {@code SELECT} from a table with no {@code WHERE}, {@code LIMIT}, {@code FETCH} or {@code TOP}: the whole table. */
        public boolean unfilteredRead() {
            return kind() == Kind.SELECT && !tables().isEmpty() && !BOUNDED_READ.matcher(withoutLiterals()).find();
        }

        public Kind kind() {
            String first = sql.strip().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            return switch (first) {
                case "SELECT", "WITH" -> Kind.SELECT;
                case "INSERT" -> Kind.INSERT;
                case "UPDATE" -> Kind.UPDATE;
                case "DELETE" -> Kind.DELETE;
                case "MERGE", "UPSERT" -> Kind.MERGE;
                default -> Kind.OTHER;
            };
        }
    }

    /** An open capture; collects until closed. */
    static final class Window {

        private final Thread only;
        private final List<Statement> statements = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;

        Window(Thread only) {
            this.only = only;
        }

        void offer(Statement statement) {
            if (!closed && (only == null || only == statement.owner())) {
                statements.add(statement);
            }
        }

        CapturedQueries close() {
            closed = true;
            synchronized (statements) {
                return new CapturedQueries(List.copyOf(statements));
            }
        }
    }

    private final List<Statement> statements;

    CapturedQueries(List<Statement> statements) {
        this.statements = statements;
    }

    public List<Statement> statements() {
        return statements;
    }

    /** Statements counted as executions: a batch of 50 inserts is 50. */
    public int count() {
        return statements.stream().mapToInt(Statement::batch).sum();
    }

    public int count(Kind kind) {
        return statements.stream().filter(s -> s.kind() == kind).mapToInt(Statement::batch).sum();
    }

    /** How often each statement shape ran, most frequent first. */
    public Map<String, Integer> shapes() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        statements.forEach(s -> counts.merge(s.shape(), s.batch(), Integer::sum));
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> ordered = new LinkedHashMap<>();
        sorted.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    public Expectation expect() {
        return new Expectation(this);
    }

    /** {@code public.orders} → {@code orders}, for matching an allow-list written without the schema. */
    static String bareName(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    /** {@code 12 statements (SELECT 11, UPDATE 1)} and the shapes, most frequent first. */
    @Override
    public String toString() {
        Map<Kind, Integer> byKind = new EnumMap<>(Kind.class);
        statements.forEach(s -> byKind.merge(s.kind(), s.batch(), Integer::sum));
        StringBuilder out = new StringBuilder().append(count()).append(count() == 1 ? " statement" : " statements");
        if (!byKind.isEmpty()) {
            out.append(" (");
            byKind.forEach((kind, n) -> out.append(out.charAt(out.length() - 1) == '(' ? "" : ", ")
                    .append(kind).append(' ').append(n));
            out.append(')');
        }
        shapes().forEach((shape, n) -> out.append("\n  ").append(n).append(" × ").append(shape));
        return out.toString();
    }

    /** Checks run as called; the first that fails throws with every statement shape in the message. */
    public static final class Expectation {

        private final CapturedQueries queries;

        private Expectation(CapturedQueries queries) {
            this.queries = queries;
        }

        /** No more than {@code statements} executions in all. */
        public Expectation atMost(int statements) {
            return check(queries.count() <= statements, "expected at most " + statements + " statements, ran "
                    + queries.count());
        }

        /** No more than {@code statements} executions of this kind. */
        public Expectation atMost(int statements, Kind kind) {
            return check(queries.count(kind) <= statements, "expected at most " + statements + " " + kind
                    + " statements, ran " + queries.count(kind));
        }

        /** None of this kind — no DELETE from a read, no INSERT from a GET. */
        public Expectation none(Kind kind) {
            return check(queries.count(kind) == 0, "expected no " + kind + " statements, ran "
                    + queries.count(kind));
        }

        /**
         * No statement shape ran more than {@code times} — the N+1 check: the
         * same {@code SELECT … WHERE order_id = ?} once per order is one shape
         * run fifty times.
         */
        public Expectation noRepeated(int times) {
            List<String> repeated = new ArrayList<>();
            queries.shapes().forEach((shape, n) -> {
                if (n > times) {
                    repeated.add(n + " × " + shape);
                }
            });
            return check(repeated.isEmpty(), "statements repeated more than " + times + " times (N+1?): "
                    + repeated);
        }

        /**
         * No {@code UPDATE} or {@code DELETE} without a {@code WHERE}, and no
         * {@code TRUNCATE}: a write that reaches every row of a table is the
         * missing tenant filter, the mass update a test then reads as "my row
         * changed".
         */
        public Expectation noUnboundedWrites() {
            return offending(Statement::unboundedWrite, "writes without a WHERE (every row of the table): ");
        }

        /**
         * No {@code SELECT} reads a whole table — no {@code WHERE}, {@code LIMIT},
         * {@code FETCH} or {@code TOP} — except from {@code allowedTables}
         * (reference data: countries, currencies). Fine on test data, a full
         * scan on production volumes.
         */
        public Expectation noUnfilteredReads(String... allowedTables) {
            java.util.Set<String> allowed = new java.util.HashSet<>();
            for (String table : allowedTables) {
                allowed.add(table.toLowerCase(Locale.ROOT));
            }
            return offending(statement -> statement.unfilteredRead()
                            && !allowed.containsAll(statement.tables().stream().map(CapturedQueries::bareName).toList()),
                    "reads of a whole table (no WHERE or LIMIT): ");
        }

        private Expectation offending(java.util.function.Predicate<Statement> test, String message) {
            Map<String, Integer> found = new LinkedHashMap<>();
            queries.statements.stream().filter(test).forEach(s -> found.merge(s.shape(), s.batch(), Integer::sum));
            List<String> lines = new ArrayList<>();
            found.forEach((shape, n) -> lines.add(n + " × " + shape));
            return check(found.isEmpty(), message + lines);
        }

        private Expectation check(boolean ok, String message) {
            if (!ok) {
                throw new AssertionError(message + "\n" + queries);
            }
            return this;
        }
    }
}
