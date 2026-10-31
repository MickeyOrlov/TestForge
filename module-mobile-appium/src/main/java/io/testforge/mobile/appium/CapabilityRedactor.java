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

    /**
     * An invalid hub URL, described without echoing it: the parser's own
     * message quotes the whole input, user info and key included.
     */
    static IllegalArgumentException invalidHub(IllegalArgumentException e) {
        String reason = e.getCause() instanceof java.net.URISyntaxException syntax
                ? syntax.getReason() + " at index " + syntax.getIndex()
                : "not a URL";
        return new IllegalArgumentException("Invalid Appium hub URL (" + reason + "); the value is not repeated "
                + "here because it may carry credentials");
    }

    /** Every string a secret-looking key holds, at any depth, plus the hub URL's user info. */
    static java.util.Set<String> secrets(Map<String, Object> capabilities, String hubUrl) {
        java.util.Set<String> secrets = new java.util.HashSet<>();
        collect(capabilities, secrets);
        try {
            String userInfo = URI.create(hubUrl).getRawUserInfo();
            if (userInfo != null) {
                secrets.add(userInfo);
                for (String part : userInfo.split(":")) {
                    if (part.length() >= 4) {
                        secrets.add(part);
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // an unparseable hub never reaches a driver
        }
        secrets.removeIf(value -> value.isBlank());
        return secrets;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> capabilities, java.util.Set<String> secrets) {
        capabilities.forEach((key, value) -> {
            if (SECRET.matcher(key).matches() && value != null && !(value instanceof Map)) {
                secrets.add(String.valueOf(value));
            } else if (value instanceof Map<?, ?> nested) {
                Map<String, Object> copy = new LinkedHashMap<>();
                ((Map<String, Object>) nested).forEach((k, v) -> copy.put(String.valueOf(k), v));
                collect(copy, secrets);
            }
        });
    }

    /** {@code message} with every secret replaced by the mask. */
    static String scrub(String message, java.util.Set<String> secrets) {
        if (message == null) {
            return null;
        }
        String scrubbed = message;
        for (String secret : secrets) {
            scrubbed = scrubbed.replace(secret, MASK);
        }
        return scrubbed;
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
