package io.ftppool.observability;

import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;

import java.time.Duration;
import java.time.Instant;

/**
 * Records borrow/execute/operation events into a {@link FtpMetrics} sink
 * (spec section 35). Derives latencies from the context timestamps.
 */
public final class MetricsFilter implements FtpFilter {

    private final FtpMetrics metrics;

    public MetricsFilter(FtpMetrics metrics) {
        this.metrics = metrics == null ? FtpMetrics.noop() : metrics;
    }

    @Override
    public void afterBorrow(FtpContext context, io.ftppool.api.FtpConnection connection) {
        long micros = elapsedMicros(context);
        metrics.recordBorrow(micros);
        metrics.recordOperation(FtpOperation.BORROW, micros, true);
    }

    @Override
    public void afterExecute(FtpContext context) {
        long micros = elapsedMicros(context);
        metrics.recordExecute(micros, true);
    }

    @Override
    public void afterOperation(FtpContext context, FtpOperation operation) {
        metrics.recordOperation(operation, elapsedMicros(context), true, context.getBytes());
    }

    @Override
    public void onOperationError(FtpContext context, FtpOperation operation, Throwable error) {
        metrics.recordOperation(operation, elapsedMicros(context), false, 0L);
    }

    @Override
    public void onError(FtpContext context, Throwable error) {
        long micros = elapsedMicros(context);
        if (context.getOperation() == FtpOperation.BORROW || context.getOperation() == FtpOperation.RETURN) {
            metrics.recordBorrowFailure(context.getExceptionType());
        } else {
            metrics.recordExecute(micros, false);
        }
        metrics.recordOperation(operationOf(context), micros, false);
    }

    private static long elapsedMicros(FtpContext context) {
        Duration d = context.getDuration();
        if (d != null) {
            return d.toNanos() / 1_000L;
        }
        return Duration.between(context.getStartTime(), Instant.now()).toNanos() / 1_000L;
    }

    private static FtpOperation operationOf(FtpContext context) {
        FtpOperation operation = context.getOperation();
        return operation == null ? FtpOperation.EXECUTE : operation;
    }
}