package io.testforge.architecture;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module map the architecture rules are measured against.
 *
 * <p>Adding a module or an inter-module dependency is a deliberate act: it has to
 * be recorded here, in the same change that adds it to the build.
 */
final class Modules {

    /** Gradle module name to its root Java package. Nested roots are resolved longest-first. */
    static final Map<String, String> ROOT_PACKAGES = Map.ofEntries(
            Map.entry("core", "io.testforge.core"),
            Map.entry("module-api-codegen", "io.testforge.api.codegen"),
            Map.entry("module-security", "io.testforge.security"),
            Map.entry("module-auth", "io.testforge.auth"),
            Map.entry("module-api-discovery", "io.testforge.api.discovery"),
            Map.entry("module-api-explorer", "io.testforge.api.explorer"),
            Map.entry("module-api-fuzz", "io.testforge.api.fuzz"),
            Map.entry("module-contract", "io.testforge.contract"),
            Map.entry("module-contract-monitor", "io.testforge.contract.monitor"),
            Map.entry("module-data", "io.testforge.data"),
            Map.entry("module-db", "io.testforge.db"),
            Map.entry("module-db-contract", "io.testforge.db.contract"),
            Map.entry("module-flow", "io.testforge.flow"),
            Map.entry("module-http", "io.testforge.http"),
            Map.entry("module-kafka", "io.testforge.kafka"),
            Map.entry("module-rabbitmq", "io.testforge.rabbitmq"),
            Map.entry("module-gherkin", "io.testforge.gherkin"),
            Map.entry("module-quality", "io.testforge.quality"),
            Map.entry("module-mobile-appium", "io.testforge.mobile.appium"),
            Map.entry("module-mock", "io.testforge.mock"),
            Map.entry("module-reporting", "io.testforge.reporting"),
            Map.entry("module-state", "io.testforge.state"),
            Map.entry("module-web", "io.testforge.web"),
            Map.entry("module-web-playwright", "io.testforge.web.playwright"));

    /**
     * Packages that live in the {@code core} jar but outside {@code io.testforge.core}.
     */
    static final List<String> CORE_EXTRA_PACKAGES = List.of("io.testforge.artifact");

    /** The only module-to-module edges allowed. Everything may depend on {@code core}. */
    static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.ofEntries(
            Map.entry("module-security", Set.of("module-http", "module-auth", "module-api-explorer")),
            Map.entry("module-api-codegen", Set.of("module-api-discovery", "module-http")),
            Map.entry("module-api-explorer", Set.of("module-api-discovery", "module-http", "module-auth")),
            Map.entry("module-api-fuzz", Set.of("module-api-discovery")),
            Map.entry("module-contract-monitor", Set.of("module-kafka", "module-contract")),
            Map.entry("module-db", Set.of("module-data")),
            Map.entry("module-db-contract", Set.of("module-db")),
            // step glue per adapter, each module compileOnly
            Map.entry("module-gherkin", Set.of("module-http", "module-kafka", "module-rabbitmq")),
            Map.entry("module-state", Set.of("module-flow", "module-data")));

    private Modules() {
    }

    static String moduleOf(String className) {
        String best = null;
        for (var entry : ROOT_PACKAGES.entrySet()) {
            String root = entry.getValue();
            if ((className.equals(root) || className.startsWith(root + "."))
                    && (best == null || root.length() > ROOT_PACKAGES.get(best).length())) {
                best = entry.getKey();
            }
        }
        if (best == null) {
            for (String extra : CORE_EXTRA_PACKAGES) {
                if (className.startsWith(extra + ".")) {
                    return "core";
                }
            }
        }
        return best;
    }

    static Set<String> allowedFor(String module) {
        return ALLOWED_DEPENDENCIES.getOrDefault(module, Set.of());
    }
}
