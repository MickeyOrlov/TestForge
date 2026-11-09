package io.testforge.mail;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mailpit API only: this module never sends SMTP, releases mail or starts a server. */
@ConfigurationProperties("forge.mail")
public record MailProperties(boolean enabled, String apiUrl, String username, String password,
        Duration requestTimeout, Duration awaitTimeout, Duration pollInterval, int maxMessages,
        int maxResponseBytes) {

    public MailProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        awaitTimeout = awaitTimeout == null ? Duration.ofSeconds(10) : awaitTimeout;
        pollInterval = pollInterval == null ? Duration.ofMillis(100) : pollInterval;
        for (Duration value : new Duration[] {requestTimeout, awaitTimeout, pollInterval}) {
            if (value.isNegative() || value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("Mail timeouts must be 1ms..5m");
            }
        }
        maxMessages = maxMessages == 0 ? 100 : maxMessages;
        maxResponseBytes = maxResponseBytes == 0 ? 5 * 1024 * 1024 : maxResponseBytes;
        if (maxMessages < 1 || maxMessages > 1000 || maxResponseBytes < 1024
                || maxResponseBytes > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid mail response/message limit");
        }
        if ((username == null) != (password == null) || username != null && username.contains(":")) {
            throw new IllegalArgumentException("Mail API basic authentication needs username and password");
        }
        if (apiUrl != null) {
            URI uri = validateApiUrl(apiUrl);
            if (username != null && "http".equals(uri.getScheme()) && !loopback(uri.getHost())) {
                throw new IllegalArgumentException(
                        "Mail API basic authentication off loopback needs an https api-url");
            }
        }
    }

    /**
     * An explicit HTTP(S) Mailpit URL, optionally with the server's {@code --webroot} path; never
     * credentials, a query or a fragment.
     */
    static URI validateApiUrl(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || !path.isEmpty() && !path.matches("(/[A-Za-z0-9_~-][A-Za-z0-9_.~-]*)*/?")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Select a Mailpit HTTP(S) API URL (optional webroot path) without credentials/query/fragment");
        }
    }

    /** The base every API path resolves against: the webroot with exactly one trailing slash. */
    URI apiBase() {
        return URI.create(apiUrl.endsWith("/") ? apiUrl : apiUrl + "/");
    }

    private static boolean loopback(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        return name.equals("localhost") || name.startsWith("127.") || name.equals("[::1]");
    }

    void validateRun() {
        if (!enabled) {
            throw new IllegalStateException("Set forge.mail.enabled=true");
        }
        if (apiUrl == null) {
            throw new IllegalStateException("Select forge.mail.api-url explicitly");
        }
    }

    @Override
    public String toString() {
        return "MailProperties[enabled=" + enabled + ", credentials=<redacted>]";
    }
}
