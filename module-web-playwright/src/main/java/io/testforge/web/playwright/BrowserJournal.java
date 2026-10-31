package io.testforge.web.playwright;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What the browser said during one test that a screenshot cannot show: console
 * errors, uncaught page errors, requests that failed or came back 4xx/5xx.
 * Written as {@code browser.md} next to the screenshot when the test fails —
 * the text to read before opening the trace.
 *
 * <p>Bounded (the last {@value #LIMIT} of each kind, lines cut at
 * {@value #MAX_LINE} characters) and safe for a CI upload: URLs are redacted,
 * request and response headers and bodies are never recorded.
 */
final class BrowserJournal {

    static final int LIMIT = 50;
    static final int MAX_LINE = 300;

    private final Deque<String> console = new ArrayDeque<>();
    private final Deque<String> pageErrors = new ArrayDeque<>();
    private final Deque<String> requests = new ArrayDeque<>();
    private int dropped;

    synchronized void console(String type, String text) {
        if ("error".equals(type) || "warning".equals(type)) {
            add(console, type + ": " + line(text));
        }
    }

    synchronized void pageError(String message) {
        add(pageErrors, line(message));
    }

    synchronized void response(String method, String url, int status) {
        if (status >= 400) {
            add(requests, method + " " + UrlRedactor.redact(url) + " -> " + status);
        }
    }

    synchronized void requestFailed(String method, String url, String reason) {
        add(requests, method + " " + UrlRedactor.redact(url) + " -> failed: " + line(reason));
    }

    synchronized boolean isEmpty() {
        return console.isEmpty() && pageErrors.isEmpty() && requests.isEmpty();
    }

    synchronized String markdown(String testName, String pageUrl, String title) {
        StringBuilder out = new StringBuilder("# Browser — ").append(testName).append("\n\n")
                .append("- page: ").append(pageUrl == null ? "—" : UrlRedactor.redact(pageUrl)).append('\n')
                .append("- title: ").append(title == null || title.isBlank() ? "—" : line(title)).append('\n');
        if (dropped > 0) {
            out.append("- older entries dropped: ").append(dropped).append('\n');
        }
        section(out, "Failed requests", requests);
        section(out, "Page errors", pageErrors);
        section(out, "Console", console);
        return out.toString();
    }

    private static void section(StringBuilder out, String title, Deque<String> lines) {
        out.append("\n## ").append(title).append("\n\n");
        if (lines.isEmpty()) {
            out.append("None.\n");
        }
        lines.forEach(line -> out.append("- ").append(line).append('\n'));
    }

    private void add(Deque<String> lines, String line) {
        if (lines.size() == LIMIT) {
            lines.removeFirst();
            dropped++;
        }
        lines.addLast(line);
    }

    private static String line(String text) {
        if (text == null) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.length() > MAX_LINE ? first.substring(0, MAX_LINE) + "..." : first;
    }

    List<String> requests() {
        return List.copyOf(requests);
    }
}
