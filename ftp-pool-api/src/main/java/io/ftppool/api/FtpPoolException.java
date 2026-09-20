package io.ftppool.api;

/**
 * Pool-level failure (borrow timeout, closed pool).
 */
public class FtpPoolException extends FtpException {

    public FtpPoolException(FtpExceptionType type, String message) {
        super(type, message);
    }

    public FtpPoolException(FtpExceptionType type, String message, Throwable cause) {
        super(type, message, cause);
    }

    /** Borrow exceeded {@code connection-timeout}. */
    public static FtpPoolException borrowTimeout(String poolName, long timeoutMillis) {
        return new FtpPoolException(
                FtpExceptionType.POOL_TIMEOUT,
                "Timeout while borrowing from pool '" + poolName + "' after " + timeoutMillis + " ms");
    }

    /** Operation on a closed pool. */
    public static FtpPoolException poolClosed(String poolName) {
        return new FtpPoolException(
                FtpExceptionType.POOL_CLOSED,
                "Pool '" + poolName + "' is closed");
    }
}