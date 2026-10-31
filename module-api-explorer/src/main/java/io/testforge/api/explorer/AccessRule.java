package io.testforge.api.explorer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What one role is expected to get from one operation, as written in
 * {@code forge.access-matrix}.
 *
 * <ul>
 *   <li>{@code allow} — authorization let the call through: any status below
 *       500 except 401 and 403. A 404 for an id that does not exist, or a 400
 *       for a missing field, still means the caller was let in;</li>
 *   <li>{@code deny} — 401 or 403;</li>
 *   <li>explicit statuses and classes, comma separated: {@code 404},
 *       {@code 401,403,404}, {@code 2xx}.</li>
 * </ul>
 */
public record AccessRule(String text, boolean allow, boolean deny, Set<Integer> statuses, Set<Integer> classes) {

    public AccessRule {
        statuses = Set.copyOf(statuses);
        classes = Set.copyOf(classes);
    }

    public static AccessRule parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an access rule must not be blank");
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("allow")) {
            return new AccessRule(normalized, true, false, Set.of(), Set.of());
        }
        if (normalized.equals("deny")) {
            return new AccessRule(normalized, false, true, Set.of(), Set.of());
        }
        Set<Integer> statuses = new LinkedHashSet<>();
        Set<Integer> classes = new LinkedHashSet<>();
        for (String part : normalized.split(",")) {
            String token = part.trim();
            if (token.matches("[1-5]xx")) {
                classes.add(token.charAt(0) - '0');
            } else if (token.matches("[1-5][0-9]{2}")) {
                statuses.add(Integer.parseInt(token));
            } else {
                throw new IllegalArgumentException("'" + text + "' is not an access rule: use allow, deny, "
                        + "a status such as 403, a class such as 2xx, or a comma-separated list of them");
            }
        }
        return new AccessRule(normalized, false, false, statuses, classes);
    }

    public boolean matches(int status) {
        if (allow) {
            return status >= 100 && status < 500 && status != 401 && status != 403;
        }
        if (deny) {
            return status == 401 || status == 403;
        }
        return statuses.contains(status) || classes.contains(status / 100);
    }

    /** {@code allow} or {@code deny}: what the observed status says about the caller. */
    static String describe(int status) {
        return status == 401 || status == 403 ? "deny" : "allow";
    }

    @Override
    public String toString() {
        return text;
    }
}
