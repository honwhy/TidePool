package io.ftppool.api;

/**
 * FTP protocol variant.
 */
public enum FtpProtocol {

    /** Plain FTP (port 21). */
    FTP,

    /** Explicit FTPS — AUTH TLS on the regular control port. */
    FTPS_EXPLICIT,

    /** Implicit FTPS — TLS from the very first connection. */
    FTPS_IMPLICIT
}