package io.testforge.api.explorer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Checks every {@code ApiClient} response of a normal functional test against
 * the OpenAPI document, so contract drift is caught by the tests a project
 * already runs — not only by a separate exploration job.
 *
 * <pre>
 * forge:
 *   api-discovery:
 *     specs:
 *       orders:
 *         location: classpath:/openapi/orders.yaml
 *   contract-guard:
 *     enabled: true
 *     mode: record          # record (default): collect and report; fail: fail the test
 *     specs: [orders]       # optional: default every registered spec
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.contract-guard")
public record ContractGuardProperties(boolean enabled, Mode mode, List<String> specs) {

    public enum Mode {
        /** Collect mismatches, log each new one once, publish them to the run index. */
        RECORD,
        /** Throw an {@link AssertionError} from the call that returned a mismatching response. */
        FAIL;
    }

    public ContractGuardProperties {
        if (mode == null) {
            mode = Mode.RECORD;
        }
        specs = List.copyOf(specs == null ? List.of() : specs);
    }
}
