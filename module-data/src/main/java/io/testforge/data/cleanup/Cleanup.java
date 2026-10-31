package io.testforge.data.cleanup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Undo what a test created on a shared environment — registered the moment the
 * object exists, run after the test even when it failed halfway:
 *
 * <pre>{@code
 * String id = api.request().body(order).post("/orders").path("id");
 * Cleanup.register("order " + id, () -> api.request().delete("/orders/{id}", id));
 * }</pre>
 *
 * Actions run in reverse order (children before the parents they belong to),
 * each one even if an earlier one failed. Registrations are per thread, so
 * parallel tests never clean up each other's data. Run by {@link CleanupExtension}.
 */
public final class Cleanup {

    /** Something to undo, and what to call it in a failure message. */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    record Entry(String description, Action action) {
    }

    /** One problem while cleaning up. */
    public record Failure(String description, Throwable cause) {
    }

    // a stack of scopes: class-level (@BeforeAll) below, the current test on top
    private static final ThreadLocal<Deque<List<Entry>>> SCOPES = ThreadLocal.withInitial(() -> {
        Deque<List<Entry>> scopes = new ArrayDeque<>();
        scopes.push(new ArrayList<>());
        return scopes;
    });

    private Cleanup() {
    }

    public static void register(String description, Action action) {
        Objects.requireNonNull(action, "action");
        String name = description == null || description.isBlank() ? "cleanup action" : description;
        SCOPES.get().peek().add(new Entry(name, action));
    }

    /** Registrations waiting in the current scope, newest first: what would run now. */
    public static List<String> pending() {
        List<String> descriptions = new ArrayList<>();
        List<Entry> current = SCOPES.get().peek();
        for (int i = current.size() - 1; i >= 0; i--) {
            descriptions.add(current.get(i).description());
        }
        return descriptions;
    }

    static void openScope() {
        SCOPES.get().push(new ArrayList<>());
    }

    /** Runs and closes the innermost scope, newest first; returns what failed. */
    static List<Failure> closeScope() {
        Deque<List<Entry>> scopes = SCOPES.get();
        List<Entry> entries = scopes.size() > 1 ? scopes.pop() : scopes.peek();
        List<Failure> failures = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            try {
                entry.action().run();
            } catch (Exception | AssertionError e) {
                failures.add(new Failure(entry.description(), e));
            }
        }
        entries.clear();
        if (scopes.size() == 1 && scopes.peek().isEmpty()) {
            SCOPES.remove();
        }
        return failures;
    }

    static int depth() {
        return SCOPES.get().size();
    }
}
