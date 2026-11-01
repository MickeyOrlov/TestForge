package io.testforge.db.query;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands every statement a proxied datasource executes to the capture windows
 * open at that moment. A window sees statements from every thread — an
 * application under test serving requests on its own threads included — or
 * only its opener's, when it asks.
 */
public final class QueryRecorder {

    private final List<CapturedQueries.Window> open = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<CapturedQueries.Statement>> listeners = new CopyOnWriteArrayList<>();

    void record(CapturedQueries.Statement statement) {
        for (CapturedQueries.Window window : open) {
            window.offer(statement);
        }
        for (var listener : listeners) {
            listener.accept(statement);
        }
    }

    /** Every statement, for the whole run — {@link DbQuerySummary} aggregates without keeping them. */
    void listen(java.util.function.Consumer<CapturedQueries.Statement> listener) {
        listeners.add(listener);
    }

    CapturedQueries.Window open(boolean thisThreadOnly) {
        CapturedQueries.Window window = new CapturedQueries.Window(thisThreadOnly ? Thread.currentThread().threadId() : -1);
        open.add(window);
        return window;
    }

    void close(CapturedQueries.Window window) {
        open.remove(window);
    }
}
