package io.testforge.db.query;

import java.util.List;
import java.util.function.Supplier;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * Feeds executed statements to the {@link QueryRecorder}. The recorder is
 * looked up per call: the datasource is wrapped long before the bean exists.
 * Only SQL text, timing and outcome are kept — never parameter values.
 */
public final class QueryRecorderListener implements QueryExecutionListener {

    private final Supplier<QueryRecorder> recorder;

    public QueryRecorderListener(Supplier<QueryRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    }

    @Override
    public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        QueryRecorder target = recorder.get();
        if (target == null) {
            return;
        }
        String error = execution.isSuccess() || execution.getThrowable() == null
                ? null : execution.getThrowable().getClass().getSimpleName();
        String thread = Thread.currentThread().getName();
        for (QueryInfo query : queries) {
            int batch = Math.max(1, query.getQueryArgsList().size());
            target.record(new CapturedQueries.Statement(execution.getDataSourceName(), query.getQuery(), batch,
                    execution.getElapsedTime(), error, thread, Thread.currentThread()));
        }
    }
}
