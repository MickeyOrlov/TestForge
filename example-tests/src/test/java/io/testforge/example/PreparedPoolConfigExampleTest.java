package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.data.prepared.Prepared;
import io.testforge.data.prepared.PreparedDataPool;
import io.testforge.data.prepared.PreparedDataProvider;
import io.testforge.data.prepared.PreparedParameterResolver;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Expensive variants stocked from configuration instead of a suite hook:
 * {@code forge.data.pool.preload} prepares them when the context starts, so
 * the first test that asks already finds one. Tag order does not matter —
 * {@code {"eu", "vip"}} is the same variant as {@code {"vip", "eu"}}.
 */
@SpringBootTest(properties = {
        "forge.data.pool.preload[0].type=io.testforge.example.PreparedPoolConfigExampleTest$Agreement",
        "forge.data.pool.preload[0].tags=vip,eu",
        "forge.data.pool.preload[0].count=2"
})
@ExtendWith(PreparedParameterResolver.class)
class PreparedPoolConfigExampleTest {

    public record Agreement(int serial, List<String> tags) {
    }

    @TestConfiguration
    static class Providers {

        static final AtomicInteger PREPARED = new AtomicInteger();

        @Bean
        PreparedDataProvider<Agreement> agreementProvider() {
            return new PreparedDataProvider<>() {
                @Override
                public Class<Agreement> type() {
                    return Agreement.class;
                }

                @Override
                public Agreement prepare(List<String> tags) {
                    return new Agreement(Providers.PREPARED.incrementAndGet(), tags);
                }
            };
        }
    }

    @Autowired
    PreparedDataPool pool;

    @Test
    void theConfiguredVariantIsStockedBeforeTheFirstTest(@Prepared(tags = {"eu", "vip"}) Agreement agreement) {
        assertThat(agreement.tags()).containsExactlyInAnyOrder("vip", "eu");
        assertThat(pool.stats())
                .filteredOn(stats -> stats.type().equals(Agreement.class.getName()))
                .singleElement()
                .satisfies(stats -> {
                    assertThat(stats.hits()).isGreaterThanOrEqualTo(1);
                    assertThat(stats.coldMisses()).as("served from stock, not prepared on the spot").isZero();
                });
    }
}
