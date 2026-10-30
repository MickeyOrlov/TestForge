package io.testforge.data.prepared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreparedDataPoolTest {

    record Account(int serial, List<String> tags) {
    }

    static final class AccountProvider implements PreparedDataProvider<Account> {
        final AtomicInteger prepared = new AtomicInteger();

        @Override
        public Class<Account> type() {
            return Account.class;
        }

        @Override
        public Account prepare(List<String> tags) {
            return new Account(prepared.incrementAndGet(), tags);
        }
    }

    static final class RecordingListener implements PoolEventListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onAcquired(Class<?> type, List<String> tags, boolean fromPool) {
            events.add("acquired:" + tags + ":" + (fromPool ? "pool" : "cold"));
        }

        @Override
        public void onPrepared(Class<?> type, List<String> tags) {
            events.add("prepared:" + tags);
        }

        @Override
        public void onExhausted(Class<?> type, List<String> tags) {
            events.add("exhausted:" + tags);
        }
    }

    private final AccountProvider provider = new AccountProvider();
    private final RecordingListener listener = new RecordingListener();
    private final PreparedDataPool pool = new PreparedDataPool(List.of(provider), List.of(listener));

    @Test
    void coldMissPreparesOnTheSpot() {
        Account account = pool.acquire(Account.class, List.of("approved"));

        assertThat(account.tags()).containsExactly("approved");
        assertThat(listener.events).containsExactly("prepared:[approved]", "acquired:[approved]:cold");
    }

    @Test
    void preloadedObjectsAreServedFirstInOrderAndNeverTwice() {
        pool.preload(Account.class, List.of("approved"), 2);
        listener.events.clear();

        Account first = pool.acquire(Account.class, List.of("approved"));
        Account second = pool.acquire(Account.class, List.of("approved"));

        assertThat(first.serial()).isEqualTo(1);
        assertThat(second.serial()).isEqualTo(2);
        assertThat(pool.stocked(Account.class, List.of("approved"))).isZero();
        assertThat(listener.events).containsExactly("acquired:[approved]:pool", "acquired:[approved]:pool");
    }

    @Test
    void aDrainedVariantSignalsExhaustionThenFallsBackToPreparing() {
        pool.preload(Account.class, List.of("approved"), 1);
        pool.acquire(Account.class, List.of("approved"));
        listener.events.clear();

        Account fallback = pool.acquire(Account.class, List.of("approved"));

        assertThat(fallback.serial()).isEqualTo(2);
        assertThat(listener.events)
                .containsExactly("exhausted:[approved]", "prepared:[approved]", "acquired:[approved]:cold");
    }

    @Test
    void neverStockedVariantIsAColdMissNotAnExhaustion() {
        pool.acquire(Account.class, List.of("new"));

        assertThat(listener.events).noneMatch(event -> event.startsWith("exhausted"));
    }

    @Test
    void variantsAreKeptApartByTags() {
        pool.preload(Account.class, List.of("approved"), 1);

        assertThat(pool.stocked(Account.class, List.of("blocked"))).isZero();
        assertThat(pool.acquire(Account.class, List.of("blocked")).tags()).containsExactly("blocked");
        assertThat(pool.stocked(Account.class, List.of("approved"))).isEqualTo(1);
    }

    @Test
    void unknownTypeExplainsWhatToRegister() {
        assertThatThrownBy(() -> pool.acquire(String.class, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PreparedDataProvider<String>");
    }

    @Test
    void twoProvidersForOneTypeAreRejected() {
        assertThatThrownBy(() -> new PreparedDataPool(List.of(new AccountProvider(), new AccountProvider()), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Account.class.getName());
    }

    @Test
    void concurrentAcquiresHandEachPreloadedObjectToExactlyOneTest() throws Exception {
        int stock = 200;
        int threads = 8;
        pool.preload(Account.class, List.of("hot"), stock);
        Set<Integer> served = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < stock / threads; i++) {
                        int serial = pool.acquire(Account.class, List.of("hot")).serial();
                        assertThat(served.add(serial)).as("serial %s served twice", serial).isTrue();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(served).hasSize(stock);
        assertThat(provider.prepared.get()).as("no cold misses while stock lasted").isEqualTo(stock);
    }
}
