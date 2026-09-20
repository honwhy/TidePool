package io.ftppool.engine.commons;

import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonsPoolEngineTest {

    private FlakyFactory factory;
    private RecordingStats stats;

    @BeforeEach
    void setUp() {
        factory = new FlakyFactory();
        stats = new RecordingStats();
    }

    @Test
    void borrowCreatesWhenEmptyAndReusesOnReturn() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

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
                .minIdle(0)
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

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
                .minIdle(0)
                .maxSize(2)
                .connectionTimeout(Duration.ofMillis(200))
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        String a = engine.borrow(Duration.ofMillis(500));
        String b = engine.borrow(Duration.ofMillis(500));

        assertThatThrownBy(() -> engine.borrow(Duration.ofMillis(200)))
                .isInstanceOf(TimeoutException.class);
        assertThat(a).isNotEqualTo(b);
        engine.close();
    }

    @Test
    void failedResetDestroysInsteadOfReissuing() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        factory.failReset = true;
        engine.release(first);
        factory.failReset = false;
        String second = engine.borrow(Duration.ofMillis(500));

        // The dirty connection was destroyed on return, never re-issued.
        assertThat(second).isNotSameAs(first);
        assertThat(factory.destroyed.get()).isGreaterThanOrEqualTo(1);
        engine.close();
    }

    @Test
    void maxLifetimeRetiresExpiredConnectionOnBorrow() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .maxLifetime(Duration.ofMillis(250))
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        String first = engine.borrow(Duration.ofMillis(500));
        Thread.sleep(350);
        engine.release(first);
        String second = engine.borrow(Duration.ofMillis(500));

        assertThat(second).isNotSameAs(first);
        assertThat(factory.destroyed.get()).isGreaterThanOrEqualTo(1);
        assertThat(factory.created.get()).isGreaterThanOrEqualTo(2);
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
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        await(() -> engine.idle() >= 2, 5000);
        assertThat(factory.created.get()).isGreaterThanOrEqualTo(2);
        assertThat(engine.size()).isBetween(2, 4);
        engine.close();
    }

    @Test
    void validateAllKeepsValidIdleAndDestroysInvalid() throws Exception {
        // minIdle stays 0 so the Commons evictor cannot refill the pool while
        // validateAll() cycles the idle list (which would never drain).
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .maxSize(4)
                .idleTimeout(Duration.ofMinutes(10))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        String a = engine.borrow(Duration.ofMillis(500));
        String b = engine.borrow(Duration.ofMillis(500));
        engine.release(a);
        engine.release(b);
        assertThat(engine.idle()).isEqualTo(2);

        engine.validateAll();
        assertThat(engine.idle()).isEqualTo(2);
        assertThat(factory.destroyed.get()).isZero();

        long failuresBefore = failures();
        factory.failValidation = true;
        engine.validateAll();

        assertThat(failures()).isGreaterThan(failuresBefore);
        assertThat(factory.destroyed.get()).isGreaterThanOrEqualTo(2);
        assertThat(engine.idle()).isZero();
        factory.failValidation = false;
        engine.close();
    }

    @Test
    void closeDestroysAllIdleEntries() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .minIdle(0)
                .maxSize(4)
                .validationInterval(Duration.ZERO)
                .build();
        CommonsPoolEngine<String> engine = new CommonsPoolEngine<>(factory, config, stats);

        String a = engine.borrow(Duration.ofMillis(500));
        String b = engine.borrow(Duration.ofMillis(500));
        assertThat(a).isNotEqualTo(b);
        engine.release(a);
        engine.release(b);

        engine.close();
        assertThat(factory.destroyed.get()).isEqualTo(2);
        assertThat(engine.size()).isZero();
    }

    private long failures() {
        return stats.validationFailures.get();
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

    /** String resources whose validation/reset can be turned off mid-test. */
    private static final class FlakyFactory implements ResourceFactory<String> {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        volatile boolean failReset;
        volatile boolean failValidation;

        @Override
        public String create() {
            created.incrementAndGet();
            return "resource-" + created.get();
        }

        @Override
        public boolean validate(String resource) {
            return !failValidation;
        }

        @Override
        public boolean reset(String resource) {
            return !failReset;
        }

        @Override
        public void destroy(String resource) {
            destroyed.incrementAndGet();
        }
    }

    private static final class RecordingStats implements PoolStatsRecorder {

        final AtomicInteger validationFailures = new AtomicInteger();

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
            validationFailures.incrementAndGet();
        }

        @Override
        public void recordWaitStart() {
        }

        @Override
        public void recordWaitEnd() {
        }
    }
}