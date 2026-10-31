package io.testforge.api.fuzz;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * A target URL fit for an artifact: user info dropped and every query value
 * masked. A base URL's query string is where an {@code api_key} or a signed
 * token ends up when a gateway wants one, and this module cannot know which
 * parameter names are secret in someone else's API — so none are kept.
 */
public final class UrlRedactor {

    static final String MASK = "***";

    private UrlRedactor() {
    }

    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() == null && uri.getRawQuery() == null) {
                return url;
            }
            String query = uri.getRawQuery() == null ? null : maskQuery(uri.getRawQuery());
            URI base = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
            return base + (query == null ? "" : "?" + query)
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (URISyntaxException e) {
            return "<redacted-malformed-url>";
        }
    }

    private static String maskQuery(String rawQuery) {
        List<String> parts = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            parts.add(equals < 0 ? part : part.substring(0, equals + 1) + MASK);
        }
        return String.join("&", parts);
    }
}
