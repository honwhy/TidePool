package io.ftppool.observability;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Flags operations slower than a configured threshold (spec section 37).
 *
 * <p>Warns with low-cardinality context only: pool, connectionId, operation,
 * duration — never file paths or credentials. Exposes a slow-operation counter
 * for downstream metrics/JMX.</p>
 */
@Slf4j
public final class SlowOperationFilter implements FtpFilter {

    private final Duration threshold;
    private final AtomicLong slowOperationCount = new AtomicLong();

    public SlowOperationFilter(Duration threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
    }

    @Override
    public void afterBorrow(FtpContext context, FtpConnection connection) {
        check(context);
    }

    @Override
    public void afterExecute(FtpContext context) {
        check(context);
    }

    @Override
    public void afterReturn(FtpContext context) {
        check(context);
    }

    @Override
    public void afterOperation(FtpContext context, FtpOperation operation) {
        check(context);
    }

    public long slowOperationCount() {
        return slowOperationCount.get();
    }

    private void check(FtpContext context) {
        Duration duration = context.getDuration();
        if (duration == null) {
            duration = Duration.between(context.getStartTime(), Instant.now());
        }
        if (duration.compareTo(threshold) > 0) {
            slowOperationCount.incrementAndGet();
            log.warn("Slow operation: pool={}, connectionId={}, operation={}, duration={}ms",
                    context.getPool(), context.getConnectionId(),
                    context.getOperation(), duration.toMillis());
        }
    }
}