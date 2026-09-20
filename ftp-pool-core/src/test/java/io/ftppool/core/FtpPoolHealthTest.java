package io.ftppool.core;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FtpPoolHealthTest {

    @Test
    void healthyPoolIsUp() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 3);

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.UP);
        assertThat(health.getTotal()).isEqualTo(8);
        assertThat(health.getActive()).isEqualTo(3);
        assertThat(health.getIdle()).isEqualTo(5);
        assertThat(health.getPending()).isZero();
    }

    @Test
    void closedPoolIsDown() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 0);

        FtpPoolHealth health = FtpPoolHealth.check(stats, true);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.DOWN);
    }

    @Test
    void lazilyEmptyPoolIsUp() {
        // total == 0 is the normal lazy state of a pool that creates on demand;
        // it must not be reported as DOWN.
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 0, () -> 0);

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.UP);
    }

    @Test
    void fullyBusyPoolWithoutWaitersIsUp() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 4, () -> 4);

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.UP);
        assertThat(health.getIdle()).isZero();
    }

    @Test
    void pendingBorrowersDegradePool() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 8);
        stats.recordWaitStart();

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.DEGRADED);
        assertThat(health.getPending()).isEqualTo(1);
    }

    @Test
    void historicalBorrowTimeoutsDoNotDegradePool() {
        // Cumulative counters are metrics, not health: a past timeout must not
        // pin the pool to DEGRADED forever.
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 3);
        stats.recordBorrowTimeout();

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.UP);
    }

    @Test
    void historicalValidationFailuresDoNotDegradePool() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 3);
        stats.recordValidationFailure();

        FtpPoolHealth health = FtpPoolHealth.check(stats, false);

        assertThat(health.getStatus()).isEqualTo(FtpPoolHealth.Status.UP);
    }

    @Test
    void checkFromPoolReadsStatsAndClosedFlag() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 2);
        StubPool pool = new StubPool(stats, false);

        assertThat(FtpPoolHealth.check(pool).getStatus()).isEqualTo(FtpPoolHealth.Status.UP);

        pool.closed = true;
        assertThat(FtpPoolHealth.check(pool).getStatus()).isEqualTo(FtpPoolHealth.Status.DOWN);
    }

    private static final class StubPool implements FtpPool {

        private final FtpPoolStats stats;
        private boolean closed;

        StubPool(FtpPoolStats stats, boolean closed) {
            this.stats = stats;
            this.closed = closed;
        }

        @Override
        public FtpConnection borrow() {
            return null;
        }

        @Override
        public FtpConnection borrow(Duration timeout) {
            return null;
        }

        @Override
        public void release(FtpConnection connection) {
        }

        @Override
        public <T> T execute(FtpCallback<T> callback) throws FtpException {
            return null;
        }

        @Override
        public FtpPoolStats stats() {
            return stats;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}