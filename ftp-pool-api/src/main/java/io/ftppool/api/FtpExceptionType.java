package io.ftppool.api;

/**
 * Classifies FTP failures so the pool can decide destroy-vs-return.
 *
 * <p>CONNECTION-class failures must destroy the physical connection;
 * BUSINESS-class failures return the connection to the pool.</p>
 */
public enum FtpExceptionType {

    /** Physical control/data link broken (socket reset, EOF, disconnect). */
    CONNECTION,

    /** Login / credentials failure. */
    AUTHENTICATION,

    /** Connect / socket / data timeouts. */
    TIMEOUT,

    /** Protocol-level errors (unexpected reply, illegal sequence). */
    PROTOCOL,

    /** Data transfer failures (RETR/STOR). */
    TRANSFER,

    /** Validation failures (NOOP failed). */
    VALIDATION,

    /** Borrow exceeded connection timeout. */
    POOL_TIMEOUT,

    /** Operation on a closed pool. */
    POOL_CLOSED,

    /** Business-level FTP reply failures — connection is still usable. */
    BUSINESS
}