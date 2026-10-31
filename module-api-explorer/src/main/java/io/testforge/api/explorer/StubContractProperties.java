package io.testforge.api.explorer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Holds WireMock stubs to the OpenAPI document of the service they stand in
 * for, so a mock cannot answer what the real service never would.
 *
 * <pre>
 * forge:
 *   api-discovery:
 *     specs:
 *       payments:                     # the downstream's own document
 *         location: classpath:/openapi/payments.yaml
 *   stub-contract:
 *     enabled: true
 *     specs: [payments]               # optional: default every registered spec
 *     strip-prefix: /payments-service # optional: the stub URL prefix the document does not know
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.stub-contract")
public record StubContractProperties(boolean enabled, List<String> specs, String stripPrefix) {

    public StubContractProperties {
        specs = List.copyOf(specs == null ? List.of() : specs);
        if (stripPrefix != null && (stripPrefix.isBlank() || stripPrefix.equals("/"))) {
            stripPrefix = null;
        }
    }
}
