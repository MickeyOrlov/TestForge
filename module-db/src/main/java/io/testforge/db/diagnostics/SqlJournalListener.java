package io.testforge.db.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * Feeds every statement a proxied datasource executes into the
 * {@link SqlJournal}. The journal is looked up per call: the datasource is
 * wrapped long before the journal bean exists.
 */
public class SqlJournalListener implements QueryExecutionListener {

    static final int MAX_VALUE_CHARS = 200;

    private final Supplier<SqlJournal> journal;

    public SqlJournalListener(Supplier<SqlJournal> journal) {
        this.journal = journal;
    }

    @Override
    public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    }

    @Override
    public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        SqlJournal target = journal.get();
        if (target == null) {
            return;
        }
        String error = execution.isSuccess() || execution.getThrowable() == null
                ? null : execution.getThrowable().toString();
        for (QueryInfo query : queries) {
            List<List<String>> parameters = new ArrayList<>();
            for (Map<String, Object> arguments : query.getQueryArgsList()) {
                parameters.add(render(arguments));
            }
            target.record(new SqlJournal.Statement(execution.getDataSourceName(), query.getQuery(), parameters,
                    execution.getElapsedTime(), error));
        }
    }

    /** Positional arguments arrive keyed "1", "2", …; ordered numerically, shortened. */
    private static List<String> render(Map<String, Object> arguments) {
        Map<Integer, String> ordered = new TreeMap<>();
        arguments.forEach((index, value) -> {
            String text = String.valueOf(value);
            if (text.length() > MAX_VALUE_CHARS) {
                text = text.substring(0, MAX_VALUE_CHARS) + "…";
            }
            try {
                ordered.put(Integer.parseInt(index), text);
            } catch (NumberFormatException e) {
                ordered.put(ordered.size() + 10_000, index + "=" + text);
            }
        });
        return List.copyOf(ordered.values());
    }
}
