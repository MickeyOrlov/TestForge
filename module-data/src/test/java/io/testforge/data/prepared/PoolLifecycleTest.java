package io.testforge.data.prepared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.data.DataProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PoolLifecycleTest {

    public record Ticket(int serial) {
    }

    private final AtomicInteger prepared = new AtomicInteger();
    private final PreparedDataProvider<Ticket> provider = new PreparedDataProvider<>() {
        @Override
        public Class<Ticket> type() {
            return Ticket.class;
        }

        @Override
        public Ticket prepare(List<String> tags) {
            return new Ticket(prepared.incrementAndGet());
        }
    };
    private final List<String> written = new ArrayList<>();
    private final ArtifactSink sink = new ArtifactSink() {
        @Override
        public Path directoryFor(String source) {
            return Path.of("unused");
        }

        @Override
        public void register(TestArtifact artifact) {
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            written.add(source + "/" + category + "/" + name + "\n" + content);
            return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
        }
    };

    private static DataProperties.PoolProperties preload(DataProperties.PreloadEntry... entries) {
        return new DataProperties.PoolProperties(List.of(entries));
    }

    @Test
    void stocksConfiguredVariantsAtStart() {
        PreparedDataPool pool = new PreparedDataPool(List.of(provider), List.of());
        PoolLifecycle lifecycle = new PoolLifecycle(pool,
                preload(new DataProperties.PreloadEntry(Ticket.class.getName(), List.of("open"), 3, 0)), sink);

        lifecycle.start();

        assertThat(pool.stocked(Ticket.class, List.of("open"))).isEqualTo(3);
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();
    }

    @Test
    void anUnknownTypeFailsStartupWithItsName() {
        PoolLifecycle lifecycle = new PoolLifecycle(new PreparedDataPool(List.of(provider), List.of()),
                preload(new DataProperties.PreloadEntry("com.example.Missing", List.of(), 1, 0)), sink);

        assertThatThrownBy(lifecycle::start).hasMessageContaining("com.example.Missing");
    }

    @Test
    void aFailingPreloadFailsStartupInsteadOfRunningEveryTestColdly() {
        PoolLifecycle lifecycle = new PoolLifecycle(new PreparedDataPool(List.of(), List.of()),
                preload(new DataProperties.PreloadEntry(Ticket.class.getName(), List.of("open"), 2, 0)), sink);

        assertThatThrownBy(lifecycle::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Preloading 2 x Ticket [open]")
                .hasMessageContaining("No PreparedDataProvider");
    }

    @Test
    void backgroundRefillKeepsAHotVariantStocked() {
        PreparedDataPool pool = new PreparedDataPool(List.of(provider), List.of());
        PoolLifecycle lifecycle = new PoolLifecycle(pool,
                preload(new DataProperties.PreloadEntry(Ticket.class.getName(), List.of("open"), 2, 1)), sink);
        lifecycle.start();
        try {
            pool.acquire(Ticket.class, List.of("open"));
            pool.acquire(Ticket.class, List.of("open"));

            new Waiter(new WaitProperties(Duration.ofSeconds(5), Duration.ofMillis(10)))
                    .awaitTrue("refill back to 2", () -> pool.stocked(Ticket.class, List.of("open")) == 2);
            assertThat(prepared.get()).isEqualTo(4);
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void stopPublishesStatisticsToTheRunIndex() throws Exception {
        PreparedDataPool pool = new PreparedDataPool(List.of(provider), List.of());
        PoolLifecycle lifecycle = new PoolLifecycle(pool,
                preload(new DataProperties.PreloadEntry(Ticket.class.getName(), List.of("open"), 1, 0)), sink);
        lifecycle.start();
        pool.acquire(Ticket.class, List.of("open"));
        pool.acquire(Ticket.class, List.of("open"));

        lifecycle.stop();

        assertThat(written).singleElement().satisfies(entry -> {
            assertThat(entry).startsWith("module-data/pool-stats/pool-stats.json\n");
            JsonNode variant = new ObjectMapper().readTree(entry.substring(entry.indexOf('\n') + 1))
                    .get("variants").get(0);
            assertThat(variant.get("hits").asLong()).isEqualTo(1);
            assertThat(variant.get("coldMisses").asLong()).isEqualTo(1);
            assertThat(variant.get("exhausted").asLong()).isEqualTo(1);
        });
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void anUnusedPoolPublishesNothing() {
        PoolLifecycle lifecycle = new PoolLifecycle(new PreparedDataPool(List.of(), List.of()), preload(), sink);
        lifecycle.start();
        lifecycle.stop();

        assertThat(written).isEmpty();
    }

    @Test
    void preloadEntriesAreValidated() {
        assertThatThrownBy(() -> new DataProperties.PreloadEntry(" ", null, 1, 0)).hasMessageContaining("'type'");
        assertThatThrownBy(() -> new DataProperties.PreloadEntry("x", null, -1, 0)).hasMessageContaining("negative");
        assertThat(new DataProperties(0, null).pool().preload()).isEmpty();
    }
}
