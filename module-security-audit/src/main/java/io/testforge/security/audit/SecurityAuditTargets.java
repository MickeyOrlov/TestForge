package io.testforge.security.audit;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** Lexical checks only: no DNS, filesystem inspection or remote calls. */
final class SecurityAuditTargets {
    static void location(SecurityAuditProfile.TargetKind kind, String location) {
        if (location == null
                || location.length() > 2048
                || location.chars().anyMatch(Character::isISOControl))
            throw SecurityAuditProfile.invalid("audit target is missing or exceeds bounds");
        switch (kind) {
            case URL -> url(location);
            case HOST -> host(location);
            case REPOSITORY -> localPath(location);
            case IMAGE -> {
                if (!location.matches(
                        "[a-zA-Z0-9.-]+(:[0-9]{1,5})?/[a-z0-9._/-]+@sha256:[a-f0-9]{64}"))
                    throw SecurityAuditProfile.invalid(
                            "audit image needs explicit registry and immutable digest");
                String registry = location.substring(0, location.indexOf('/'));
                if (!(registry.contains(".")
                                || registry.contains(":")
                                || registry.equals("localhost"))
                        || location.contains("//")
                        || location.contains("/../")
                        || location.contains("/./"))
                    throw SecurityAuditProfile.invalid(
                            "audit image registry must be unambiguous and path normalized");
                url("https://" + registry);
            }
        }
    }

    static URI url(String value) {
        try {
            URI uri = URI.create(value);
            if (!SetHolder.SCHEMES.contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || value.contains("%")
                    || value.contains("\\")
                    || uri.getPath().contains("//")
                    || !uri.normalize().equals(uri)) throw new IllegalArgumentException();
            host(uri.getHost());
            return uri;
        } catch (RuntimeException e) {
            throw SecurityAuditProfile.invalid(
                    "audit URL must be an explicit HTTP endpoint without credentials or query"
                            + " data");
        }
    }

    static String host(String value) {
        try {
            if (value == null
                    || value.length() > 253
                    || value.isBlank()
                    || value.endsWith(".")
                    || !value.equals(value.trim())) throw new IllegalArgumentException();
            String unwrapped =
                    value.startsWith("[") && value.endsWith("]")
                            ? value.substring(1, value.length() - 1)
                            : value;
            if (unwrapped.contains(":")) {
                URI uri = URI.create("http://[" + unwrapped + "]/");
                if (uri.getHost() == null || unwrapped.contains("%"))
                    throw new IllegalArgumentException();
            } else {
                if (!unwrapped.matches("[a-zA-Z0-9.-]+")) throw new IllegalArgumentException();
                for (String label : unwrapped.split("\\.", -1))
                    if (!label.matches("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"))
                        throw new IllegalArgumentException();
                if (unwrapped.matches("[0-9.]+")) {
                    var parts = unwrapped.split("\\.", -1);
                    if (parts.length != 4) throw new IllegalArgumentException();
                    for (String p : parts)
                        if (Integer.parseInt(p) > 255 || p.length() > 1 && p.startsWith("0"))
                            throw new IllegalArgumentException();
                }
            }
            return unwrapped.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            throw SecurityAuditProfile.invalid(
                    "audit host must be a single explicit hostname or IP address");
        }
    }

    static void localPath(String value) {
        try {
            if (value == null
                    || value.length() > 2048
                    || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException();
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.normalize().equals(path))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw SecurityAuditProfile.invalid(
                    "audit local input must be an absolute normalized path");
        }
    }

    private static final class SetHolder {
        static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    }

    private SecurityAuditTargets() {}
}
