package io.testforge.data;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Test data helper settings.
 *
 * <pre>
 * forge:
 *   data:
 *     max-template-depth: 10
 *     pool:
 *       preload:
 *         - type: com.example.DemoAgreement
 *           tags: [active]
 *           count: 5
 *           refill-below: 2      # optional: top up to count in the background
 *     isolation:
 *       prefix: tf               # marks test data of every run
 *       run-tag: ${CI_RUN_ID:}   # blank: generated once per JVM
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.data")
public record DataProperties(int maxTemplateDepth, PoolProperties pool, IsolationProperties isolation) {

    public DataProperties {
        if (maxTemplateDepth <= 0) {
            maxTemplateDepth = 10;
        }
        if (pool == null) {
            pool = new PoolProperties(null);
        }
        if (isolation == null) {
            isolation = new IsolationProperties(null, null);
        }
    }

    /**
     * @param prefix marks test data of every run; letters and digits, default {@code tf}
     * @param runTag this run's id; letters and digits, blank generates one per JVM
     */
    public record IsolationProperties(String prefix, String runTag) {
    }

    /** Variants stocked when the context starts. */
    public record PoolProperties(List<PreloadEntry> preload) {

        public PoolProperties {
            preload = List.copyOf(preload == null ? List.of() : preload);
        }

        public boolean refillConfigured() {
            return preload.stream().anyMatch(entry -> entry.refillBelow() > 0);
        }
    }

    /**
     * @param type        fully qualified class name a {@code PreparedDataProvider} serves
     * @param tags        variant tags
     * @param count       objects to stock at startup and the refill target
     * @param refillBelow refill in the background when stock drops below this; 0 disables
     */
    public record PreloadEntry(String type, List<String> tags, int count, int refillBelow) {

        public PreloadEntry {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("forge.data.pool.preload entries need a 'type'");
            }
            tags = List.copyOf(tags == null ? List.of() : tags);
            if (count < 0 || refillBelow < 0) {
                throw new IllegalArgumentException("forge.data.pool.preload counts must not be negative: " + type);
            }
        }
    }
}
