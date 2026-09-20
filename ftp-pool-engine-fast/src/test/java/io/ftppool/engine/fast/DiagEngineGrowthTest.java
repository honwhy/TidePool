package io.ftppool.engine.fast;

import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporary diagnostic (DELETE AFTER USE): can the FastPoolEngine grow a pool
 * toward maxSize under demand with max-create-concurrency=2 when creation is fast?
 */
class DiagEngineGrowthTest {

    private static final AtomicInteger idSeq = new AtomicInteger();

    static final class FakeResource implements AutoCloseable {
        final int id = idSeq.incrementAndGet();
        @Override public void close() { }
    }

    private static final class CountingStats implements PoolStatsRecorder {
        final AtomicLong created = new AtomicLong();
        final AtomicLong destroyed = new AtomicLong();
        final AtomicLong borrowed = new AtomicLong();
        final AtomicLong returned = new AtomicLong();
        public void recordCreate() { created.incrementAndGet(); }
        public void recordDestroy() { destroyed.incrementAndGet(); }
        public void recordBorrow() { borrowed.incrementAndGet(); }
        public void recordReturn() { returned.incrementAndGet(); }
        public void recordBorrowTimeout() { }
        public void recordValidation() { }
        public void recordValidationFailure() { }
        public void recordWaitStart() { }
        public void recordWaitEnd() { }
    }

    @Test
    void poolGrowsUnderDemandWithFastFactory() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .poolName("growth")
                .minIdle(0)
                .maxSize(24)
                .connectionTimeout(Duration.ofSeconds(15))
                .maxCreateConcurrency(2)
                .build();
        CountingStats stats = new CountingStats();
        ResourceFactory<FakeResource> factory = new ResourceFactory<>() {
            public FakeResource create() { return new FakeResource(); }
            public boolean validate(FakeResource r) { return true; }
            public boolean reset(FakeResource r) { return true; }
            public void destroy(FakeResource r) { r.close(); }
        };
        FastPoolEngine<FakeResource> engine = new FastPoolEngine<>(factory, config, stats);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    FakeResource r = engine.borrow(null);
                    Thread.sleep(2);
                    engine.release(r);
                    ok.incrementAndGet();
                } catch (Throwable t) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline && done.getCount() > 0) {
            Thread.sleep(500);
            System.out.println("[DiagEngineGrowth] t=" + (System.currentTimeMillis() - tStart)
                    + "ms created=" + stats.created.get() + " total=" + engine.total()
                    + " active=" + engine.active() + " done=" + (threadCount - done.getCount()));
        }
        done.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        System.out.println("[DiagEngineGrowth] created=" + stats.created.get() + " borrowed=" + stats.borrowed.get()
                + " destroyed=" + stats.destroyed.get() + " ok=" + ok.get() + " fail=" + fail.get()
                + " maxTotal=" + engine.total());
        engine.close();
        assertThat(fail.get()).isZero();
    }

    static long tStart;
    static {
        tStart = 0;
    }
}