package io.testforge.http;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The last few HTTP exchanges of the current scenario, already redacted, kept
 * so a failed test can show what it sent and what came back. One journal per
 * thread, like {@code ScenarioContext}: parallel tests never see each other's
 * traffic. Bounded, so a long scenario costs a constant amount of memory.
 */
public class HttpExchangeJournal {

    /**
     * One redacted exchange.
     *
     * @param status {@code null} when the call failed before a response arrived
     * @param error  the transport failure, or {@code null}
     */
    public record Exchange(String method, String uri, Integer status, long millis,
                           Map<String, String> requestHeaders, String requestBody, String responseBody,
                           String error) {
    }

    private final int capacity;
    private final ThreadLocal<Deque<Exchange>> exchanges = ThreadLocal.withInitial(ArrayDeque::new);

    public HttpExchangeJournal(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    void record(Exchange exchange) {
        Deque<Exchange> journal = exchanges.get();
        journal.addLast(exchange);
        while (journal.size() > capacity) {
            journal.removeFirst();
        }
    }

    /** Oldest first. */
    public List<Exchange> recent() {
        return List.copyOf(exchanges.get());
    }

    public void clear() {
        exchanges.remove();
    }
}
