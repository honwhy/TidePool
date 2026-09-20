package io.ftppool.api;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;

/**
 * Context passed through the {@link FtpFilter} chain (section 38 of the spec).
 *
 * <p>Carries the logging context fields mandated by the spec: pool,
 * connectionId, operation, duration, result, exceptionType.</p>
 */
@Getter
@Setter
public final class FtpContext {

    private final String pool;
    private final FtpConnectionId connectionId;
    private final FtpOperation operation;
    private final Instant startTime;

    private boolean success;
    private Duration duration;
    private FtpExceptionType exceptionType;
    private Throwable error;
    /** Transfer size for operation-level events (0 for non-transfer operations). */
    private long bytes;
    /** Opaque slot for filters (e.g. a tracing span) to carry state across callbacks. */
    private Object attachment;

    public FtpContext(String pool, FtpConnectionId connectionId, FtpOperation operation) {
        this.pool = pool;
        this.connectionId = connectionId;
        this.operation = operation;
        this.startTime = Instant.now();
    }

    public FtpContext completed(boolean success, Duration duration) {
        this.success = success;
        this.duration = duration;
        return this;
    }

    public FtpContext failed(Throwable error, FtpExceptionType exceptionType) {
        this.success = false;
        this.error = error;
        this.exceptionType = exceptionType;
        this.duration = Duration.between(startTime, Instant.now());
        return this;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", "FtpContext[", "]")
                .add("pool=" + pool)
                .add("connectionId=" + connectionId)
                .add("operation=" + operation)
                .add("duration=" + duration)
                .add("result=" + (success ? "SUCCESS" : "FAILURE"))
                .add("exceptionType=" + exceptionType)
                .toString();
    }
}