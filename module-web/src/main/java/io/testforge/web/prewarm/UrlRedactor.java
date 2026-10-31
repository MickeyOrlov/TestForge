package io.testforge.web.prewarm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * A staging URL fit for a log or a report: user info dropped, every query value
 * masked. Basic-auth credentials in a prewarm URL are a common shortcut past a
 * staging gate, and a signed link carries its token in the query.
 */
final class UrlRedactor {

    private UrlRedactor() {
    }

    /**
     * An error message with every spelling of {@code url} that carries a secret
     * replaced by its redacted form. Playwright quotes the URL it was navigating
     * to — credentials included — in its errors.
     */
    static String scrub(String message, String url) {
        if (message == null || url == null) {
            return message;
        }
        String redacted = redact(url);
        String scrubbed = message.replace(url, redacted);
        try {
            String userInfo = new URI(url).getRawUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                scrubbed = scrubbed.replace(userInfo + "@", "");
            }
        } catch (URISyntaxException ignored) {
            // nothing more to find
        }
        return scrubbed;
    }

    static String redact(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() == null && uri.getRawQuery() == null) {
                return url;
            }
            List<String> query = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    int equals = part.indexOf('=');
                    query.add(equals < 0 ? part : part.substring(0, equals + 1) + "***");
                }
            }
            String base = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                    .toString();
            return base + (query.isEmpty() ? "" : "?" + String.join("&", query))
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (URISyntaxException e) {
            return "<unparseable url>";
        }
    }
}
