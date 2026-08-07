package io.testforge.api.discovery;

import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the resolver chain over an endpoint's parameters.
 *
 * <p>Path parameters and required query parameters must resolve or the endpoint
 * is not probed. Optional query parameters are simply left out — sending a
 * guessed filter would change what the response contains, and the whole point
 * is to record what the endpoint returns by default.
 */
public class ParameterResolution {

    private final List<PathParameterResolver> resolvers;

    public ParameterResolution(List<PathParameterResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public ResolvedParameters resolve(EndpointDescriptor endpoint) {
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (ParameterDescriptor parameter : endpoint.parameters()) {
            boolean needed = "path".equals(parameter.in())
                    || ("query".equals(parameter.in()) && parameter.required());
            if (!needed) {
                continue;
            }

            Optional<Resolved> resolved = first(endpoint, parameter);
            if (resolved.isEmpty()) {
                missing.add("%s (%s)".formatted(parameter.name(), parameter.in()));
                continue;
            }

            if ("path".equals(parameter.in())) {
                path.put(parameter.name(), resolved.get().value());
            } else {
                query.put(parameter.name(), resolved.get().value());
            }
            sources.put(parameter.in() + ":" + parameter.name(), resolved.get().source());
        }

        return new ResolvedParameters(path, query, sources, missing);
    }

    private Optional<Resolved> first(EndpointDescriptor endpoint, ParameterDescriptor parameter) {
        for (PathParameterResolver resolver : resolvers) {
            Optional<String> value = resolver.resolve(endpoint, parameter);
            if (value.isPresent()) {
                return Optional.of(new Resolved(value.get(), resolver.sourceName()));
            }
        }
        return Optional.empty();
    }

    private record Resolved(String value, String source) {
    }
}
