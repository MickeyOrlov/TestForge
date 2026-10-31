package io.testforge.auth;

/**
 * Where a role's credential goes on a request.
 *
 * @param kind   header, query parameter or cookie
 * @param name   header or query parameter name; for a cookie the name travels
 *               in the credential itself ({@code name=value})
 * @param prefix put before the value in a header, e.g. {@code "Bearer "}
 */
public record CredentialPlacement(Kind kind, String name, String prefix) {

    public enum Kind { HEADER, QUERY, COOKIE }

    public CredentialPlacement {
        prefix = prefix == null ? "" : prefix;
    }

    public static CredentialPlacement header(String name, String scheme) {
        return new CredentialPlacement(Kind.HEADER, name, scheme == null || scheme.isBlank() ? "" : scheme + " ");
    }

    public static CredentialPlacement query(String name) {
        return new CredentialPlacement(Kind.QUERY, name, "");
    }

    public static CredentialPlacement cookie() {
        return new CredentialPlacement(Kind.COOKIE, null, "");
    }

    /** {@code header Authorization}, {@code query api_key}, {@code cookie} — for diagnostics; never the value. */
    public String describe() {
        return kind == Kind.COOKIE ? "cookie" : kind.name().toLowerCase(java.util.Locale.ROOT) + " " + name;
    }
}
