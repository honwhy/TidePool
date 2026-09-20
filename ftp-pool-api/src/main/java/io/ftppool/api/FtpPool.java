package io.ftppool.api;

import java.time.Duration;

/**
 * High-Performance FTP connection pool facade.
 *
 * <p>Users face this type + {@link FtpConnection}; the underlying pool engine
 * (Fast/Commons) and the physical FTP client are never exposed.</p>
 *
 * <pre>{@code
 * pool.execute(ftp -> {
 *     return ftp.download("/data/test.txt", out);
 * });
 * }</pre>
 */
public interface FtpPool extends AutoCloseable {

    /** Borrow with the configured connection-timeout. */
    FtpConnection borrow() throws FtpPoolException;

    FtpConnection borrow(Duration timeout) throws FtpPoolException;

    /** Return a connection obtained from {@link #borrow()}. */
    void release(FtpConnection connection);

    /**
     * Borrow - execute - validate - release in one call.
     * Connection-class failures mark the connection broken (destroy); business
     * failures return it to the pool.
     */
    <T> T execute(FtpCallback<T> callback) throws FtpException;

    /** Live pool statistics snapshot. */
    FtpPoolStats stats();

    /** Shut the pool down and release all resources. Idempotent. */
    void close();

    boolean isClosed();
}