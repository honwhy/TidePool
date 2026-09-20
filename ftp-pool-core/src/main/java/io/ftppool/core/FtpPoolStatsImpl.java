package io.ftppool.core;

import io.ftppool.api.FtpPool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Live {@link FtpPoolStats} implementation backed by atomics.
 *
 * <p>total/active/idle/pending come from the owning engine (size/active/idle)
 * plus waiter accounting; counters are incremented through the shared
 * {@link PoolStatsRecorder} contract.</p>
 */
public final class FtpPoolStatsImpl implements io.ftppool.api.FtpPoolStats, PoolStatsRecorder {

    private final AtomicLong created = new AtomicLong();
    private final AtomicLong destroyed = new AtomicLong();
    private final AtomicLong borrowed = new AtomicLong();
    private final AtomicLong returned = new AtomicLong();
    private final AtomicLong borrowTimeouts = new AtomicLong();
    private final AtomicLong validations = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();

    private volatile ActiveStats activeStats;

    /** Provides live total/active/idle from a pool engine. */
    public interface ActiveStats {
        int total();

        int active();
    }

    public FtpPoolStatsImpl(ActiveStats activeStats) {
        this.activeStats = activeStats;
    }

    public FtpPoolStatsImpl(IntSupplier total, IntSupplier active) {
        bind(total, active);
    }

    /** Bind lazily when the engine outlives stats construction (see {@link FtpPoolBuilder}). */
    public FtpPoolStatsImpl() {
    }

    public void bind(ActiveStats activeStats) {
        this.activeStats = activeStats;
    }

    public void bind(IntSupplier total, IntSupplier active) {
        this.activeStats = new ActiveStats() {
            @Override
            public int total() {
                return total.getAsInt();
            }

            @Override
            public int active() {
                return active.getAsInt();
            }
        };
    }

    @Override
    public int total() {
        ActiveStats stats = activeStats;
        return stats == null ? 0 : stats.total();
    }

    @Override
    public int active() {
        ActiveStats stats = activeStats;
        return stats == null ? 0 : stats.active();
    }

    @Override
    public int idle() {
        // Clamp: total/active/pending are read non-atomically from different
        // atomics, so a transient interleaving could otherwise surface a
        // negative idle count to callers (metrics, JMX, health).
        return Math.max(0, total() - active() - pending());
    }

    @Override
    public int pending() {
        return pending.get();
    }

    @Override
    public long created() {
        return created.get();
    }

    @Override
    public long destroyed() {
        return destroyed.get();
    }

    @Override
    public long borrowed() {
        return borrowed.get();
    }

    @Override
    public long returned() {
        return returned.get();
    }

    @Override
    public long borrowTimeouts() {
        return borrowTimeouts.get();
    }

    @Override
    public long validationFailures() {
        return validationFailures.get();
    }

    public long validations() {
        return validations.get();
    }

    @Override
    public void recordCreate() {
        created.incrementAndGet();
    }

    @Override
    public void recordDestroy() {
        destroyed.incrementAndGet();
    }

    @Override
    public void recordBorrow() {
        borrowed.incrementAndGet();
    }

    @Override
    public void recordReturn() {
        returned.incrementAndGet();
    }

    @Override
    public void recordBorrowTimeout() {
        borrowTimeouts.incrementAndGet();
    }

    @Override
    public void recordValidation() {
        validations.incrementAndGet();
    }

    @Override
    public void recordValidationFailure() {
        validationFailures.incrementAndGet();
    }

    @Override
    public void recordWaitStart() {
        pending.incrementAndGet();
    }

    @Override
    public void recordWaitEnd() {
        pending.decrementAndGet();
    }
}