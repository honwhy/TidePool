package io.ftppool.core;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.api.FtpPoolStats;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Thin, dependency-light facade over an {@link FtpPool} (spec section 47).
 *
 * <p>Wraps a pool so application code (and the Spring Boot starter) can inject a
 * single typed collaborator and call {@code execute} plus convenient
 * one-liners for the common operations. Never exposes a physical FTP client or
 * the underlying engine.</p>
 *
 * <pre>{@code
 * FtpClientTemplate ftp = new FtpClientTemplate(pool);
 * ftp.execute(c -> c.upload("/data/test.txt", input));
 * }</pre>
 */
public final class FtpClientTemplate {

    private final FtpPool pool;

    public FtpClientTemplate(FtpPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /** The wrapped pool. */
    public FtpPool pool() {
        return pool;
    }

    /** Borrow - execute - validate - release in one call. */
    public <T> T execute(FtpCallback<T> callback) throws FtpException {
        return pool.execute(callback);
    }

    /** Borrow a connection with the configured timeout. */
    public FtpConnection borrow() throws FtpPoolException {
        return pool.borrow();
    }

    /** Borrow a connection with an explicit timeout. */
    public FtpConnection borrow(Duration timeout) throws FtpPoolException {
        return pool.borrow(timeout);
    }

    /** Return a connection obtained from {@link #borrow()}. */
    public void release(FtpConnection connection) {
        pool.release(connection);
    }

    /** Live pool statistics snapshot. */
    public FtpPoolStats stats() {
        return pool.stats();
    }

    /** Health of the wrapped pool (spec section 68). */
    public FtpPoolHealth health() {
        return FtpPoolHealth.check(pool);
    }

    /** Convenience: upload in one call, returning the transfer result. */
    public boolean upload(String path, InputStream input) throws FtpException {
        return execute(connection -> connection.upload(path, input));
    }

    /** Convenience: download in one call, returning the transfer result. */
    public boolean download(String path, OutputStream output) throws FtpException {
        return execute(connection -> connection.download(path, output));
    }

    /** Convenience: delete in one call, returning the transfer result. */
    public boolean delete(String path) throws FtpException {
        return execute(connection -> connection.delete(path));
    }
}