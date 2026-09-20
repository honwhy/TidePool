package io.ftppool.api;

/**
 * Druid-inspired filter chain SPI (section 38 of the spec).
 *
 * <p>Implementations observe borrow/execute/return/error lifecycle. Useful for
 * MetricsFilter, LoggingFilter, SlowOperationFilter, AuditFilter, TracingFilter.</p>
 */
public interface FtpFilter {

    default void beforeBorrow(FtpContext context) {
    }

    default void afterBorrow(FtpContext context, FtpConnection connection) {
    }

    default void beforeExecute(FtpContext context) {
    }

    default void afterExecute(FtpContext context) {
    }

    default void onError(FtpContext context, Throwable error) {
    }

    default void beforeReturn(FtpContext context) {
    }

    default void afterReturn(FtpContext context) {
    }

    /**
     * Invoked before a single FTP operation (upload/download/delete/list/…)
     * on a borrowed connection. The {@code context} carries the specific
     * {@link FtpOperation}.
     */
    default void beforeOperation(FtpContext context, FtpOperation operation) {
    }

    /**
     * Invoked after an FTP operation succeeds. {@link FtpContext#getBytes()}
     * carries the transferred size for upload/download (0 otherwise).
     */
    default void afterOperation(FtpContext context, FtpOperation operation) {
    }

    /** Invoked when an FTP operation fails (connection or business error). */
    default void onOperationError(FtpContext context, FtpOperation operation, Throwable error) {
    }
}