package io.ftppool.engine.fast;

import io.ftppool.core.LifecycleType;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FastPoolEngineTest {

    private CountingFactory factory;
    private PoolStatsRecorder stats;

    @BeforeEach
    void setUp() {
        factory = new CountingFactory();
        stats = new RecordingStats();
    }

    @Test
    void borrowCreatesWhenEmptyAndReusesOnReturn() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .validationInterval(Duration.ZERO)
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        engine.release(first);
        String second = engine.borrow(Duration.ofMillis(500));

        assertThat(second).isSameAs(first);
        assertThat(factory.created.get()).isEqualTo(1);
        engine.close();
    }

    @Test
    void invalidateDestroysAndNextBorrowCreatesFresh() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .validationInterval(Duration.ZERO)
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        engine.invalidate(first);
        String second = engine.borrow(Duration.ofMillis(500));

        assertThat(second).isNotSameAs(first);
        assertThat(factory.destroyed.get()).isEqualTo(1);
        assertThat(factory.created.get()).isEqualTo(2);
        engine.close();
    }

    @Test
    void borrowTimesOutWhenPoolExhausted() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .maxSize(2)
                .connectionTimeout(Duration.ofMillis(150))
                .validationInterval(Duration.ZERO)
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String a = engine.borrow(Duration.ofMillis(500));
        String b = engine.borrow(Duration.ofMillis(500));

        assertThatThrownBy(() -> engine.borrow(Duration.ofMillis(150)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(a).isNotEqualTo(b);
        engine.close();
    }

    @Test
    void staleEntryIsValidatedBeforeReborrow() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .validationInterval(Duration.ofSeconds(1))
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String first = engine.borrow(Duration.ofMillis(500));
        engine.release(first);
        // sleep so the idle entry crosses the validation interval, then borrow
        // from another thread so the shared idle queue (not the fast path) is used
        Thread.sleep(1050);
        Future<String> future = executor.submit(() -> engine.borrow(Duration.ofMillis(500)));
        String second = future.get(5, TimeUnit.SECONDS);

        assertThat(factory.validations.get()).isGreaterThanOrEqualTo(1);
        assertThat(second).isSameAs(first);
        executor.shutdownNow();
        engine.close();
    }

    @Test
    void simpleLifecycleSkipsPeriodicValidation() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .lifecycle(LifecycleType.SIMPLE)
                .minIdle(0)
                .validationInterval(Duration.ofSeconds(1))
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String first = engine.borrow(Duration.ofMillis(500));
        engine.release(first);
        Thread.sleep(1050);
        String second = executor.submit(() -> engine.borrow(Duration.ofMillis(500))).get(5, TimeUnit.SECONDS);

        assertThat(second).isSameAs(first);
        assertThat(factory.validations.get()).isZero();
        executor.shutdownNow();
        engine.close();
    }

    @Test
    void simpleLifecycleDoesNotEvictIdle() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .lifecycle(LifecycleType.SIMPLE)
                .minIdle(0)
                .maxSize(4)
                .idleTimeout(Duration.ofMillis(100))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        engine.release(first);
        Thread.sleep(1500);

        assertThat(engine.idle()).isEqualTo(1);
        assertThat(factory.destroyed.get()).isZero();
        engine.close();
    }

    @Test
    void housekeeperFillsMinIdle() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(2)
                .maxSize(4)
                .idleTimeout(Duration.ofMinutes(10))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        await(() -> engine.idle() >= 2, 5000);
        assertThat(engine.size()).isBetween(2, 4);
        assertThat(factory.created.get()).isGreaterThanOrEqualTo(2);
        engine.close();
    }

    @Test
    void housekeeperEvictsIdleExpiredEntries() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .maxSize(4)
                .idleTimeout(Duration.ofMillis(250))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        engine.release(first);

        // entry is idle; give it time to exceed the idle timeout between housekeeper runs
        await(() -> engine.idle() == 0, 5000);
        assertThat(factory.destroyed.get()).isGreaterThanOrEqualTo(1);
        engine.close();
    }

    @Test
    void closeDestroyesAllEntries() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .maxSize(4)
                .validationInterval(Duration.ZERO)
                .build();
        FastPoolEngine<String> engine = new FastPoolEngine<>(factory, config, stats);

        String a = engine.borrow(Duration.ofMillis(500));
        String b = engine.borrow(Duration.ofMillis(500));
        engine.release(a);
        engine.release(b);
        engine.close();

        assertThat(factory.destroyed.get()).isGreaterThanOrEqualTo(2);
        assertThat(engine.size()).isEqualTo(0);
    }

    private static void await(ThrowingCondition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + " ms");
    }

    private interface ThrowingCondition {
        boolean test() throws Exception;
    }

    /** Fake resources: each created resource is only ever handed to one borrower. */
    private static final class CountingFactory implements ResourceFactory<String> {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger validations = new AtomicInteger();

        @Override
        public String create() {
            created.incrementAndGet();
            return "resource-" + created.get();
        }

        @Override
        public boolean validate(String resource) {
            validations.incrementAndGet();
            return true;
        }

        @Override
        public boolean reset(String resource) {
            return true;
        }

        @Override
        public void destroy(String resource) {
            destroyed.incrementAndGet();
        }
    }

    private static final class RecordingStats implements PoolStatsRecorder {

        @Override
        public void recordCreate() {
        }

        @Override
        public void recordDestroy() {
        }

        @Override
        public void recordBorrow() {
        }

        @Override
        public void recordReturn() {
        }

        @Override
        public void recordBorrowTimeout() {
        }

        @Override
        public void recordValidation() {
        }

        @Override
        public void recordValidationFailure() {
        }

        @Override
        public void recordWaitStart() {
        }

        @Override
        public void recordWaitEnd() {
        }
    }
}