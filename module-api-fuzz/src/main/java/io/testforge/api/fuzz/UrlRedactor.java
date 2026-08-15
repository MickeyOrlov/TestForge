package io.testforge.api.fuzz;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlRedactor {
    private UrlRedactor() {
    }

    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null) {
                return new URI(
                        uri.getScheme(),
                        null,
                        uri.getHost(),
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        uri.getFragment()
                ).toString();
            }
            return url;
        } catch (URISyntaxException e) {
            return "<redacted-malformed-url>";
        }
    }
}
