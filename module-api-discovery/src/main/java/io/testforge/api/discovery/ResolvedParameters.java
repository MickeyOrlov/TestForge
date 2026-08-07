package io.testforge.api.discovery;

import java.util.List;
import java.util.Map;

/**
 * Parameter values for one endpoint, plus where each came from.
 *
 * <p>{@code sources} is keyed by {@code <in>:<name>} and is what
 * {@link ProbePolicy} consults before letting an unsafe method through: a value
 * that did not come from {@code CONFIG} may fill a URL, but it may not fill the
 * URL of a {@code DELETE}.
 *
 * <p>The values themselves never reach an artifact. Only the parameter name,
 * whether it resolved, and the source are recorded.
 */
public record ResolvedParameters(
        Map<String, String> path,
        Map<String, String> query,
        Map<String, String> sources,
        List<String> missing) {

    public ResolvedParameters {
        path = Map.copyOf(path == null ? Map.of() : path);
        query = Map.copyOf(query == null ? Map.of() : query);
        sources = Map.copyOf(sources == null ? Map.of() : sources);
        missing = List.copyOf(missing == null ? List.of() : missing);
    }

    public boolean complete() {
        return missing.isEmpty();
    }

    /** Source labels of every value that was actually resolved. */
    public List<String> usedSources() {
        return sources.values().stream().distinct().sorted().toList();
    }
}
