package io.testforge.mobile.appium;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Capabilities and hub URLs fit for a failure report. Remote device clouds take
 * their credentials as capabilities ({@code provider:options} with an
 * {@code accessKey}) or in the hub URL's user info, and failure reports are
 * uploaded from CI.
 */
final class CapabilityRedactor {

    static final String MASK = "***";

    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(key|token|secret|password|passwd|credential|auth).*");

    private CapabilityRedactor() {
    }

    /** Sorted by key, nested maps redacted too; a value under a secret-looking key is masked. */
    static Map<String, Object> redact(Map<String, Object> capabilities) {
        Map<String, Object> redacted = new TreeMap<>();
        capabilities.forEach((key, value) -> redacted.put(key, redactValue(key, value)));
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(String key, Object value) {
        if (SECRET.matcher(key).matches()) {
            return MASK;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) nested).forEach((k, v) -> copy.put(String.valueOf(k), v));
            return redact(copy);
        }
        if (value instanceof String text && text.contains("://")) {
            return hub(text);
        }
        return value;
    }

    /** {@code https://user:key@hub.example/wd/hub} → {@code https://hub.example/wd/hub}. */
    static String hub(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getUserInfo() == null) {
                return url;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "<unparseable url>";
        }
    }
}
