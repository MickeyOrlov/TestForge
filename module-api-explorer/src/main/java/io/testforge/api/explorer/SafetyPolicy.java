package io.testforge.api.explorer;

import java.util.Optional;
import org.springframework.util.AntPathMatcher;

/**
 * Decides which operations may be sent at all.
 *
 * <p>The default set is {@code GET}, {@code HEAD} and {@code OPTIONS} — the
 * methods HTTP itself defines as safe. Everything else needs two deliberate
 * configuration changes, not one, because an exploration run pointed at a
 * shared environment with {@code DELETE} enabled is not a test, it is an
 * incident.
 *
 * <p>Path patterns are a convenience for narrowing a large document, not a
 * safety mechanism. No glob distinguishes {@code POST /orders} from
 * {@code POST /orders/{id}/cancel}; the method gate is what protects the
 * environment.
 */
public class SafetyPolicy {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ApiExplorerProperties properties;

    public SafetyPolicy(ApiExplorerProperties properties) {
        this.properties = properties;
    }

    /** Empty when the operation may be explored. */
    public Optional<SkipReason> refuse(ExplorableOperation operation) {
        if (!properties.methods().contains(operation.method())) {
            return Optional.of(SkipReason.METHOD_NOT_ENABLED);
        }
        if (!ApiExplorerProperties.SAFE_METHODS.contains(operation.method()) && !properties.allowUnsafeMethods()) {
            return Optional.of(SkipReason.UNSAFE_METHOD_NOT_ALLOWED);
        }
        if (matchesAny(properties.excludePaths(), operation.pathTemplate())) {
            return Optional.of(SkipReason.PATH_EXCLUDED);
        }
        if (!matchesAny(properties.includePaths(), operation.pathTemplate())) {
            return Optional.of(SkipReason.PATH_NOT_INCLUDED);
        }
        return Optional.empty();
    }

    private boolean matchesAny(java.util.List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
