package io.ftppool.api;

/**
 * Receives a borrowed {@link FtpConnection} and runs user business code.
 *
 * <p>The {@link FtpPool} guarantees the connection is reset and returned (or
 * destroyed) after this callback completes, so callers never release manually.</p>
 */
@FunctionalInterface
public interface FtpCallback<T> {

    /**
     * Execute business logic against the borrowed connection.
     *
     * @throws FtpException business or connection failure
     * @return arbitrary result
     */
    T execute(FtpConnection connection) throws FtpException;
}