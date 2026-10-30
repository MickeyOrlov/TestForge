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

    @Test
    void tagOrderAndDuplicatesDoNotSplitAVariant() {
        pool.preload(Account.class, List.of("vip", "eu"), 1);

        assertThat(pool.stocked(Account.class, List.of("eu", "vip", "eu"))).isEqualTo(1);
        Account account = pool.acquire(Account.class, List.of("eu", "vip"));
        assertThat(account.tags()).as("the provider still gets the caller's order").containsExactly("vip", "eu");
        assertThat(listener.events).contains("acquired:[eu, vip]:pool");
    }

    @Test
    void exhaustionIsReportedOncePerDrainAndReArmedByPreload() {
        pool.preload(Account.class, List.of("hot"), 1);
        pool.acquire(Account.class, List.of("hot"));
        pool.acquire(Account.class, List.of("hot"));
        pool.acquire(Account.class, List.of("hot"));

        assertThat(listener.events).filteredOn(event -> event.startsWith("exhausted")).hasSize(1);

        pool.preload(Account.class, List.of("hot"), 1);
        pool.acquire(Account.class, List.of("hot"));
        pool.acquire(Account.class, List.of("hot"));
        assertThat(listener.events).filteredOn(event -> event.startsWith("exhausted")).hasSize(2);
    }

    @Test
    void statsCountHitsColdMissesAndExhaustion() {
        pool.preload(Account.class, List.of("b", "a"), 2);
        pool.acquire(Account.class, List.of("a", "b"));
        pool.acquire(Account.class, List.of("a", "b"));
        pool.acquire(Account.class, List.of("a", "b"));
        pool.acquire(Account.class, List.of("other"));

        assertThat(pool.stats()).extracting(PreparedDataPool.VariantStats::tags)
                .containsExactly(List.of("a", "b"), List.of("other"));
        PreparedDataPool.VariantStats ab = pool.stats().getFirst();
        assertThat(ab.type()).isEqualTo(Account.class.getName());
        assertThat(ab.hits()).isEqualTo(2);
        assertThat(ab.coldMisses()).isEqualTo(1);
        assertThat(ab.prepared()).isEqualTo(3);
        assertThat(ab.exhausted()).isEqualTo(1);
        assertThat(ab.stocked()).isZero();
        assertThat(pool.stats().get(1).exhausted()).as("never stocked, never exhausted").isZero();
    }

    @Test
    void refillTopsAVariantBackUpToItsTarget() {
        pool.refillWhenBelow(Account.class, List.of("hot"), 2, 3);
        pool.enableRefill(Runnable::run);
        pool.preload(Account.class, List.of("hot"), 3);

        pool.acquire(Account.class, List.of("hot"));
        assertThat(pool.stocked(Account.class, List.of("hot"))).as("2 is not below 2").isEqualTo(2);

        pool.acquire(Account.class, List.of("hot"));
        assertThat(pool.stocked(Account.class, List.of("hot"))).isEqualTo(3);
        assertThat(pool.stats().getFirst().exhausted()).isZero();
    }

    @Test
    void refillAlsoKicksInAfterAColdMiss() {
        pool.refillWhenBelow(Account.class, List.of("hot"), 1, 2);
        pool.enableRefill(Runnable::run);

        pool.acquire(Account.class, List.of("hot"));

        assertThat(pool.stocked(Account.class, List.of("hot"))).isEqualTo(2);
    }

    @Test
    void aFailingRefillIsCountedAndNeverReachesTheTest() {
        AtomicInteger calls = new AtomicInteger();
        PreparedDataPool flaky = new PreparedDataPool(List.of(new PreparedDataProvider<Account>() {
            @Override
            public Class<Account> type() {
                return Account.class;
            }

            @Override
            public Account prepare(List<String> tags) {
                if (calls.incrementAndGet() > 1) {
                    throw new IllegalStateException("product API down");
                }
                return new Account(calls.get(), tags);
            }
        }), List.of());
        flaky.refillWhenBelow(Account.class, List.of("hot"), 1, 1);
        flaky.enableRefill(Runnable::run);

        assertThat(flaky.acquire(Account.class, List.of("hot")).serial()).isEqualTo(1);
        assertThat(flaky.stats().getFirst().refillFailures()).isEqualTo(1);
    }

    @Test
    void onlyOneRefillRunsPerVariantAtATime() {
        List<Runnable> queued = new ArrayList<>();
        pool.refillWhenBelow(Account.class, List.of("hot"), 5, 5);
        pool.enableRefill(queued::add);

        pool.acquire(Account.class, List.of("hot"));
        pool.acquire(Account.class, List.of("hot"));
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        assertThat(pool.stocked(Account.class, List.of("hot"))).isEqualTo(5);
        pool.acquire(Account.class, List.of("hot"));
        assertThat(queued).as("a finished refill re-arms").hasSize(2);
    }

    @Test
    void aThresholdAboveTheTargetIsRejected() {
        assertThatThrownBy(() -> pool.refillWhenBelow(Account.class, List.of("hot"), 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above the target");
    }
}
