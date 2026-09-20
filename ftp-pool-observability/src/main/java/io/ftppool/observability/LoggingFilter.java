package io.ftppool.observability;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;
import lombok.extern.slf4j.Slf4j;

/**
 * Audit-style filter emitting one log line per lifecycle transition with the
 * spec-mandated context fields: pool, connectionId, operation, duration,
 * result, exceptionType (spec sections 39 / 66).
 *
 * <p>FtpContext#toString already renders all fields with the password nowhere
 * in sight — credentials never flow through the context.</p>
 */
@Slf4j
public final class LoggingFilter implements FtpFilter {

    /** Debug level tracks every transition; change via {@link #LoggingFilter} ctor. */
    private final boolean verbose;

    public LoggingFilter() {
        this(false);
    }

    public LoggingFilter(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void beforeBorrow(FtpContext context) {
        if (verbose) {
            log.debug("[borrow:start] {}", context);
        }
    }

    @Override
    public void afterBorrow(FtpContext context, FtpConnection connection) {
        log.debug("[borrow:done] {}", context);
    }

    @Override
    public void beforeExecute(FtpContext context) {
        if (verbose) {
            log.debug("[execute:start] {}", context);
        }
    }

    @Override
    public void afterExecute(FtpContext context) {
        log.info("[execute:done] {}", context);
    }

    @Override
    public void onError(FtpContext context, Throwable error) {
        log.warn("[error] {} — {}", context, error.toString());
    }

    @Override
    public void beforeReturn(FtpContext context) {
        if (verbose) {
            log.debug("[return:start] {}", context);
        }
    }

    @Override
    public void afterReturn(FtpContext context) {
        log.debug("[return:done] {}", context);
    }

    @Override
    public void afterOperation(FtpContext context, FtpOperation operation) {
        if (verbose) {
            log.debug("[op:done] operation={}, bytes={}, duration={}",
                    operation, context.getBytes(), context.getDuration());
        }
    }

    @Override
    public void onOperationError(FtpContext context, FtpOperation operation, Throwable error) {
        log.warn("[op:error] operation={}, exceptionType={}, error={}",
                operation, context.getExceptionType(), error.toString());
    }
}