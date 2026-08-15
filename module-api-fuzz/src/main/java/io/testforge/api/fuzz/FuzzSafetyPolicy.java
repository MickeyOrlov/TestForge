package io.testforge.api.fuzz;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates and enforces safety policies for Schemathesis API fuzzing.
 *
 * <p><strong>The Two-Key Rule:</strong>
 * TestForge guarantees that mutating HTTP methods (such as {@code POST}, {@code PUT},
 * {@code DELETE}, or {@code PATCH}) will never be sent against a target service unless two
 * distinct configuration keys are present:
 * <ol>
 *   <li>The method is explicitly listed in {@code forge.api-fuzz.methods}.</li>
 *   <li>{@code forge.api-fuzz.allow-unsafe-methods} is explicitly set to {@code true}.</li>
 * </ol>
 * Safe methods ({@code GET}, {@code HEAD}, {@code OPTIONS}) are permitted by default when listed.
 *
 * <p><strong>The TRACE finding and unexpected-method suppression:</strong>
 * Passing {@code --include-method} to Schemathesis selects which OpenAPI operations are targeted,
 * but Schemathesis's coverage phase (in {@code negative} or {@code all} generation mode) emits
 * "unspecified HTTP method" test cases whose verb is overridden after operation selection.
 * Empirical verification against Schemathesis 4.24.3 showed that a {@code GET}-only configuration
 * ({@code --include-method GET}) still sent {@code TRACE} requests to the target server because
 * Schemathesis evaluates {@code DEFAULT_UNEXPECTED_METHODS - path.methods}.
 *
 * <p>Therefore, enforcing safety requires TWO mechanisms:
 * <ol>
 *   <li>Pass {@code --include-method <M>} for each permitted method.</li>
 *   <li>Emit {@code unexpected-methods = []} under {@code [phases.coverage]} in a generated
 *       {@code schemathesis.toml} config file passed via {@code --config-file} whenever
 *       {@code allowUnsafeMethods} is {@code false}.</li>
 * </ol>
 * When {@code allowUnsafeMethods} is {@code true}, Schemathesis's default unexpected-methods behavior
 * is left in place.
 */
public class FuzzSafetyPolicy {

    private final Set<String> permittedMethods;
    private final boolean suppressesUnexpectedMethods;

    /**
     * Constructs a safety policy based on the given properties.
     *
     * @param properties the API fuzz configuration properties
     * @throws ApiFuzzException if no HTTP methods are permitted under the policy
     */
    public FuzzSafetyPolicy(ApiFuzzProperties properties) {
        Objects.requireNonNull(properties, "properties cannot be null");

        Set<String> rawMethods = properties.methods();
        Set<String> filtered = (rawMethods == null ? Set.<String>of() : rawMethods).stream()
                .filter(properties::permits)
                .map(m -> m.toUpperCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (filtered.isEmpty()) {
            throw new ApiFuzzException(
                    "No HTTP methods are permitted under the current safety policy. "
                            + "Configured methods: " + rawMethods + ", allowUnsafeMethods: " + properties.allowUnsafeMethods()
            );
        }

        this.permittedMethods = Collections.unmodifiableSet(filtered);
        this.suppressesUnexpectedMethods = !Boolean.TRUE.equals(properties.allowUnsafeMethods());
    }

    /**
     * Creates a safety policy from the given properties.
     *
     * @param properties the API fuzz configuration properties
     * @return a new {@code FuzzSafetyPolicy}
     */
    public static FuzzSafetyPolicy from(ApiFuzzProperties properties) {
        return new FuzzSafetyPolicy(properties);
    }

    /**
     * Returns the set of HTTP methods permitted to be sent by Schemathesis in a stable iteration order.
     *
     * @return unmodifiable set of permitted uppercase method names
     */
    public Set<String> permittedMethods() {
        return permittedMethods;
    }

    /**
     * Returns whether Schemathesis's unexpected-methods generation should be suppressed.
     *
     * <p>Returns {@code true} when {@code allowUnsafeMethods} is {@code false}, indicating that
     * {@code unexpected-methods = []} must be set in {@code [phases.coverage]} in {@code schemathesis.toml}.
     * Returns {@code false} when {@code allowUnsafeMethods} is {@code true}.
     *
     * @return {@code true} if unexpected methods must be suppressed; {@code false} otherwise
     */
    public boolean suppressesUnexpectedMethods() {
        return suppressesUnexpectedMethods;
    }
}
