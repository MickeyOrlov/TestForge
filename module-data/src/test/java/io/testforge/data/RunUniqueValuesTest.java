package io.testforge.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RunUniqueValuesTest {

    private final RunUniqueValues values = new RunUniqueValues();

    @Test
    void aValueRegistersOncePerType() {
        assertThat(values.tryRegister("phone", "100")).isTrue();
        assertThat(values.tryRegister("phone", "100")).isFalse();
        assertThat(values.tryRegister("email", "100")).as("types are independent").isTrue();
    }

    @Test
    void generateSkipsCollisionsUntilAFreshValue() {
        values.tryRegister("code", "a");
        Iterator<String> sequence = List.of("a", "a", "b").iterator();

        assertThat(values.generate("code", sequence::next, 3)).isEqualTo("b");
    }

    @Test
    void generateGivesUpWithTheTypeAndAttemptsInTheMessage() {
        values.tryRegister("code", "same");
        Supplier<String> constant = () -> "same";

        assertThatThrownBy(() -> values.generate("code", constant, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'code'")
                .hasMessageContaining("5 attempts");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> values.tryRegister(" ", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.tryRegister("t", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.generate("t", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.generate("t", () -> "v", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearForgetsOneTypeOrAll() {
        values.tryRegister("a", "1");
        values.tryRegister("b", "1");

        values.clear("a");
        assertThat(values.tryRegister("a", "1")).isTrue();
        assertThat(values.tryRegister("b", "1")).isFalse();

        values.clear();
        assertThat(values.snapshot()).isEmpty();
    }

    @Test
    void snapshotIsADetachedCopy() {
        values.tryRegister("a", "1");
        var snapshot = values.snapshot();
        values.tryRegister("a", "2");

        assertThat(snapshot.get("a")).containsExactly("1");
        assertThatThrownBy(() -> snapshot.get("a").add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parallelScenariosNeverReceiveTheSameValue() throws Exception {
        int threads = 8;
        int perThread = 250;
        Set<String> handedOut = ConcurrentHashMap.newKeySet();
        // a narrow space forces collisions, which is the case worth proving
        Supplier<String> narrow = Generators.numeric(4);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String value = values.generate("narrow", narrow, 10_000);
                        assertThat(handedOut.add(value)).as("duplicate %s", value).isTrue();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(handedOut).hasSize(threads * perThread);
    }
}
