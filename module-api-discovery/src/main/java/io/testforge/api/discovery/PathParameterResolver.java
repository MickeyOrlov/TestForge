package io.testforge.api.discovery;

import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.Optional;

/**
 * Supplies a value for one path or query parameter, so an endpoint like
 * {@code GET /orders/{id}} can be probed at all.
 *
 * <p>Resolvers run in Spring's {@code @Order} sequence and the first non-empty
 * answer wins. An unresolved parameter is never guessed: the endpoint is
 * reported as skipped, with a ready-to-paste configuration block in the report.
 *
 * <p>{@link #sourceName()} is a safety input, not a label. A value that did not
 * come from {@code CONFIG} — that is, from a human editing YAML — can never
 * enable a request with an unsafe method. This is the extension point where a
 * project could later harvest identifiers from a list endpoint; that harvesting
 * still would not unlock {@code DELETE}.
 */
public interface PathParameterResolver {

    /** Source label recorded in the catalog; {@code CONFIG} has privileged meaning. */
    String sourceName();

    Optional<String> resolve(EndpointDescriptor endpoint, ParameterDescriptor parameter);
}
