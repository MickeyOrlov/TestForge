package io.testforge.api.explorer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    private final Set<String> methods;
    private final boolean allowUnsafeMethods;
    private final List<String> includePaths;
    private final List<String> excludePaths;

    /**
     * Takes the four values rather than a properties record so more than one
     * module can enforce the same rule from its own configuration. The gate is
     * a property of exploring a live API, not of any single module's YAML.
     */
    public SafetyPolicy(Set<String> methods, boolean allowUnsafeMethods,
                        List<String> includePaths, List<String> excludePaths) {
        this.methods = Set.copyOf(methods);
        this.allowUnsafeMethods = allowUnsafeMethods;
        this.includePaths = List.copyOf(includePaths);
        this.excludePaths = List.copyOf(excludePaths);
    }

    public static SafetyPolicy from(ApiExplorerProperties properties) {
        return new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                properties.includePaths(), properties.excludePaths());
    }

    /** Empty when the operation may be called. */
    public Optional<SkipReason> refuse(ExplorableOperation operation) {
        if (!methods.contains(operation.method())) {
            return Optional.of(SkipReason.METHOD_NOT_ENABLED);
        }
        if (!ApiExplorerProperties.SAFE_METHODS.contains(operation.method()) && !allowUnsafeMethods) {
            return Optional.of(SkipReason.UNSAFE_METHOD_NOT_ALLOWED);
        }
        if (matchesAny(excludePaths, operation.pathTemplate())) {
            return Optional.of(SkipReason.PATH_EXCLUDED);
        }
        if (!matchesAny(includePaths, operation.pathTemplate())) {
            return Optional.of(SkipReason.PATH_NOT_INCLUDED);
        }
        return Optional.empty();
    }

    private boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
