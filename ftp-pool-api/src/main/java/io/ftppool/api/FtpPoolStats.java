package io.ftppool.api;

/**
 * Pool statistics snapshot (section 42 of the spec).
 */
public interface FtpPoolStats {

    int total();

    int active();

    int idle();

    /** Borrowers currently waiting for a connection. */
    int pending();

    long created();

    long destroyed();

    long borrowed();

    long returned();

    long borrowTimeouts();

    long validationFailures();
}