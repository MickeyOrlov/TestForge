package io.testforge.db.state;

/** An action under test that may fail — an API call, a service method. */
@FunctionalInterface
public interface DbAction {

    void run() throws Throwable;
}
