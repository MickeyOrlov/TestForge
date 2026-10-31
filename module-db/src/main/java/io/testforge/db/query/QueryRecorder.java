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

    void record(CapturedQueries.Statement statement) {
        for (CapturedQueries.Window window : open) {
            window.offer(statement);
        }
    }

    CapturedQueries.Window open(boolean thisThreadOnly) {
        CapturedQueries.Window window = new CapturedQueries.Window(thisThreadOnly ? Thread.currentThread() : null);
        open.add(window);
        return window;
    }

    void close(CapturedQueries.Window window) {
        open.remove(window);
    }
}
