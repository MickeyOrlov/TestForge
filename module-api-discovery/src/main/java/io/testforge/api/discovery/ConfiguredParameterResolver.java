package io.testforge.api.discovery;

import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.Optional;

/**
 * Reads parameter values a human put in the configuration — operation-specific
 * first, then the shared defaults.
 *
 * <p>This is the only resolver whose values may be used with an unsafe method,
 * because it is the only one where a person decided what the value is.
 */
public class ConfiguredParameterResolver implements PathParameterResolver {

    public static final String SOURCE = "CONFIG";

    private final ApiDiscoveryProperties.ParameterProperties properties;

    public ConfiguredParameterResolver(ApiDiscoveryProperties.ParameterProperties properties) {
        this.properties = properties;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public Optional<String> resolve(EndpointDescriptor endpoint, ParameterDescriptor parameter) {
        String value = properties.find(endpoint.operationId(), parameter.name());
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
