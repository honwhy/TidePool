package io.ftppool.core;

import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolStats;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * Health snapshot of an FTP pool (spec section 68).
 *
 * <p>Three states:
 * <ul>
 *   <li>{@link Status#DOWN} — the pool is closed and can no longer serve.</li>
 *   <li>{@link Status#DEGRADED} — real, <em>current</em> contention: borrowers
 *       are waiting for a connection right now.</li>
 *   <li>{@link Status#UP} — usable (a lazily-empty pool is healthy: it creates
 *       on demand; momentary full utilization without waiters is healthy).</li>
 * </ul>
 *
 * <p>Health reflects the <em>live</em> state, never historical counters. A pool
 * that once hit a borrow timeout must not stay DEGRADED forever — cumulative
 * counters belong in metrics, not in health. The snapshot is cheap and
 * mutation-free; actuator/micrometer backends read it through
 * {@link #check(FtpPool)} without touching the engine.</p>
 */
@Getter
@ToString
public final class FtpPoolHealth {

    /** Health states of a pool (spec section 68). */
    public enum Status {
        UP,
        DEGRADED,
        DOWN
    }

    private final Status status;
    private final int total;
    private final int active;
    private final int idle;
    private final int pending;

    private FtpPoolHealth(Status status, int total, int active, int idle, int pending) {
        this.status = Objects.requireNonNull(status, "status");
        this.total = total;
        this.active = active;
        this.idle = idle;
        this.pending = pending;
    }

    /**
     * Health of a live pool snapshot. {@code closed} ⇒ DOWN; current waiters ⇒
     * DEGRADED; otherwise UP. Historical counters are deliberately ignored.
     */
    public static FtpPoolHealth check(FtpPoolStats stats, boolean closed) {
        Objects.requireNonNull(stats, "stats");
        int total = stats.total();
        int active = stats.active();
        int idle = stats.idle();
        int pending = stats.pending();

        Status status;
        if (closed) {
            status = Status.DOWN;
        } else if (pending > 0) {
            status = Status.DEGRADED;
        } else {
            status = Status.UP;
        }
        return new FtpPoolHealth(status, total, active, idle, pending);
    }

    /** Health of a live pool, deriving the closed flag from {@link FtpPool#isClosed()}. */
    public static FtpPoolHealth check(FtpPool pool) {
        Objects.requireNonNull(pool, "pool");
        return check(pool.stats(), pool.isClosed());
    }
}