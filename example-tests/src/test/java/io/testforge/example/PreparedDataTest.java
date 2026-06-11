package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.data.prepared.PoolEventListener;
import io.testforge.data.prepared.Prepared;
import io.testforge.data.prepared.PreparedDataPool;
import io.testforge.data.prepared.PreparedDataProvider;
import io.testforge.data.prepared.PreparedParameterResolver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Demonstrates module-data's prepared-object pool: a test declares what it
 * needs in its signature, the pool hands out a stocked object or builds one
 * via the registered provider. In a real adaptation the provider drives the
 * product API (often a module-flow run) instead of constructing a record.
 */
@SpringBootTest
@ExtendWith(PreparedParameterResolver.class)
class PreparedDataTest {

    record DemoClient(String id, String state) {
    }

    @TestConfiguration
    static class PoolSetup {

        static final AtomicInteger PREPARATIONS = new AtomicInteger();
        static final List<String> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        PreparedDataProvider<DemoClient> demoClientProvider() {
            return new PreparedDataProvider<>() {
                @Override
                public Class<DemoClient> type() {
                    return DemoClient.class;
                }

                @Override
                public DemoClient prepare(List<String> tags) {
                    return new DemoClient(
                            "client-" + PREPARATIONS.incrementAndGet(),
                            tags.isEmpty() ? "new" : tags.get(0));
                }
            };
        }

        @Bean
        PoolEventListener recordingListener() {
            return new PoolEventListener() {
                @Override
                public void onAcquired(Class<?> type, List<String> tags, boolean fromPool) {
                    EVENTS.add("acquired:" + (fromPool ? "pool" : "cold"));
                }

                @Override
                public void onPrepared(Class<?> type, List<String> tags) {
                    EVENTS.add("prepared");
                }
            };
        }
    }

    @Autowired
    PreparedDataPool pool;

    @Test
    void injectsPreparedObjectIntoTestSignature(@Prepared(tags = "active") DemoClient client) {
        assertThat(client.state()).isEqualTo("active");
        assertThat(client.id()).startsWith("client-");
    }

    @Test
    void preloadedObjectsSkipPreparationOnAcquire() {
        pool.preload(DemoClient.class, List.of("vip"), 1);
        PoolSetup.EVENTS.clear();

        DemoClient stocked = pool.acquire(DemoClient.class, List.of("vip"));
        DemoClient coldMiss = pool.acquire(DemoClient.class, List.of("vip"));

        assertThat(stocked.state()).isEqualTo("vip");
        assertThat(coldMiss.id()).isNotEqualTo(stocked.id());
        assertThat(PoolSetup.EVENTS).containsExactly(
                "acquired:pool",          // stocked object handed out, no preparation
                "prepared", "acquired:cold"); // pool dry -> provider builds on the spot
    }
}
