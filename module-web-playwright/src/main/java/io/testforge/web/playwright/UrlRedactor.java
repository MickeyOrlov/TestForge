package io.testforge.web.playwright;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * A URL fit for a report: user info dropped, every query value masked. Pages
 * pass tokens in query strings (OAuth callbacks, signed links), and a failure
 * report is uploaded from CI.
 */
final class UrlRedactor {

    private UrlRedactor() {
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
