package io.ftppool.micrometer;

import io.ftppool.api.FtpOperation;
import io.ftppool.observability.FtpMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;

/**
 * Micrometer binding for a pool and its filter events (spec section 44).
 *
 * <p>Ground rules enforced here:
 * <ul>
 *   <li>Pool gauges/counters come from live {@link io.ftppool.api.FtpPoolStats}.</li>
 *   <li>Operation events go through {@link FtpMetrics} into per-operation
 *       counters ({@code ftp.operation.<op>}) and one shared latency timer.</li>
 *   <li>No high-cardinality tags by default — username, file paths and full
 *       FTP commands never become tags (spec section 65).</li>
 * </ul>
 */
public final class FtpMicrometer {

    private FtpMicrometer() {
    }

    /**
     * Battery of pool gauges and lifecycle counters (spec section 44):
     * {@code ftp.pool.size/active/idle/pending} and
     * {@code ftp.pool.borrow/.borrow.timeout/.create/.destroy/.validation.failure}.
     */
    public static void bindPool(MeterRegistry registry, io.ftppool.api.FtpPool pool, Tag... tags) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(pool, "pool");
        io.ftppool.api.FtpPoolStats stats = pool.stats();
        Tags poolTags = Tags.of(poolTag(pool)).and(tags);

        registerGauge(registry, "ftp.pool.size", stats, io.ftppool.api.FtpPoolStats::total, poolTags);
        registerGauge(registry, "ftp.pool.active", stats, io.ftppool.api.FtpPoolStats::active, poolTags);
        registerGauge(registry, "ftp.pool.idle", stats, io.ftppool.api.FtpPoolStats::idle, poolTags);
        registerGauge(registry, "ftp.pool.pending", stats, io.ftppool.api.FtpPoolStats::pending, poolTags);

        registerCounter(registry, "ftp.pool.borrow", stats, io.ftppool.api.FtpPoolStats::borrowed, poolTags);
        registerCounter(registry, "ftp.pool.borrow.timeout", stats, io.ftppool.api.FtpPoolStats::borrowTimeouts, poolTags);
        registerCounter(registry, "ftp.pool.create", stats, io.ftppool.api.FtpPoolStats::created, poolTags);
        registerCounter(registry, "ftp.pool.destroy", stats, io.ftppool.api.FtpPoolStats::destroyed, poolTags);
        registerCounter(registry, "ftp.pool.validation.failure", stats,
                io.ftppool.api.FtpPoolStats::validationFailures, poolTags);
    }

    /**
     * {@link FtpMetrics} sink that records operation events into meters:
     * counters {@code ftp.operation.<op>} (tag {@code result=success|failure}),
     * the {@code ftp.operation.latency} timer (tag {@code operation}) and the
     * {@code ftp.operation.slow} counter.
     */
    public static FtpMetrics metricsSink(MeterRegistry registry, Tag... tags) {
        return new MicrometerFtpMetrics(registry, Tags.of(tags));
    }

    private static void registerGauge(MeterRegistry registry, String name, io.ftppool.api.FtpPoolStats source,
                                  ToDoubleFunction<io.ftppool.api.FtpPoolStats> fn, Tags tags) {
        if (registry.find(name).gauge() == null) {
            Gauge.builder(name, source, fn).tags(tags).register(registry);
        }
    }

    private static void registerCounter(MeterRegistry registry, String name, io.ftppool.api.FtpPoolStats source,
                                        ToDoubleFunction<io.ftppool.api.FtpPoolStats> fn, Tags tags) {
        if (registry.find(name).functionCounter() == null) {
            FunctionCounter.builder(name, source, fn).tags(tags).register(registry);
        }
    }

    private static Tag poolTag(io.ftppool.api.FtpPool pool) {
        String name = "default";
        if (pool instanceof io.ftppool.core.FtpPoolImpl impl) {
            name = impl.poolName();
        }
        return Tag.of("pool", name);
    }

    static Counter operationCounter(MeterRegistry registry, FtpOperation op, Tags tags, boolean success) {
        String result = success ? "success" : "failure";
        return registry.counter("ftp.operation." + op.name().toLowerCase(Locale.ROOT),
                tags.and(Tag.of("result", result)));
    }

    static Timer operationTimer(MeterRegistry registry, FtpOperation op, Tags tags) {
        return registry.timer("ftp.operation.latency", tags.and(Tag.of("operation", op.name().toLowerCase(Locale.ROOT))));
    }

    private static final class MicrometerFtpMetrics implements FtpMetrics {

        private final MeterRegistry registry;
        private final Tags tags;
        private final ConcurrentMap<FtpOperation, Timer> timers = new ConcurrentHashMap<>();

        MicrometerFtpMetrics(MeterRegistry registry, Tags tags) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.tags = tags;
        }

        @Override
        public void recordBorrow(long latencyMicros) {
            timer(FtpOperation.BORROW).record(Duration.ofNanos(latencyMicros * 1_000L));
        }

        @Override
        public void recordBorrowFailure(io.ftppool.api.FtpExceptionType type) {
            if (type == io.ftppool.api.FtpExceptionType.POOL_TIMEOUT) {
                registry.counter("ftp.pool.borrow.timeout", tags).increment();
            }
        }

        @Override
        public void recordExecute(long latencyMicros, boolean success) {
            timer(FtpOperation.EXECUTE).record(Duration.ofNanos(latencyMicros * 1_000L));
        }

        @Override
        public void recordOperation(FtpOperation operation, long latencyMicros, boolean success) {
            if (operation != null) {
                FtpMicrometer.operationCounter(registry, operation, tags, success).increment();
                timer(operation).record(Duration.ofNanos(latencyMicros * 1_000L));
            }
        }

        @Override
        public void recordOperation(FtpOperation operation, long latencyMicros, boolean success, long bytes) {
            recordOperation(operation, latencyMicros, success);
            if (operation != null && bytes > 0) {
                registry.counter("ftp.operation.bytes",
                                tags.and(Tag.of("operation", operation.name().toLowerCase(Locale.ROOT))))
                        .increment(bytes);
            }
        }

        @Override
        public void recordSlowOperation(FtpOperation operation, long latencyMillis) {
            registry.counter("ftp.operation.slow", tags).increment();
        }

        private Timer timer(FtpOperation operation) {
            return timers.computeIfAbsent(operation, op -> FtpMicrometer.operationTimer(registry, op, tags));
        }
    }
}