package io.testforge.web.prewarm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Warms with plain GET requests, following redirects: server-side rendering,
 * CDN and application caches, connection pools — no browser to download. It
 * does not run the page's scripts, so lazily compiled client bundles stay
 * cold; use the browser mode where those are what makes the first test slow.
 */
public final class HttpPrewarmEngine implements PrewarmEngine {

    static final String USER_AGENT = "TestForge-prewarm";

    @Override
    public Session open(PrewarmProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.navigationTimeout())
                .build();
        return new Session() {
            @Override
            public PrewarmVisit visit(String url) {
                long started = System.currentTimeMillis();
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(properties.navigationTimeout())
                            .header("Accept", "text/html,*/*")
                            .header("User-Agent", USER_AGENT)
                            .GET()
                            .build();
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    return new PrewarmVisit(UrlRedactor.redact(url), response.statusCode(),
                            UrlRedactor.redact(response.uri().toString()), System.currentTimeMillis() - started, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failed(url, started, "interrupted");
                } catch (Exception e) {
                    return failed(url, started, e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + BrowserPrewarmEngine.firstLine(e.getMessage())));
                }
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    private static PrewarmVisit failed(String url, long started, String error) {
        return new PrewarmVisit(UrlRedactor.redact(url), null, null, System.currentTimeMillis() - started, error);
    }
}
