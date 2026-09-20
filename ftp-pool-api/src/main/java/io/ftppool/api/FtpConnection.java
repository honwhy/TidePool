package io.ftppool.api;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Logical FTP connection handed to business code.
 *
 * <p>Wraps the physical FTP client (Apache Commons Net FTPClient underneath the
 * adapter). Users never see FTPClient — api stays dependency-free. A connection
 * may be used by exactly one thread at a time.</p>
 */
public interface FtpConnection {

    /** Pool entry identity, stable for the life of the connection. */
    FtpConnectionId id();

    void changeDirectory(String path) throws FtpException;

    String currentDirectory() throws FtpException;

    /** Opens a data stream for RETR. Must be followed by {@link #completePendingCommand()}. */
    InputStream retrieveFileStream(String path) throws FtpException;

    /** Opens a data stream for STOR. Must be followed by {@link #completePendingCommand()}. */
    OutputStream storeFileStream(String path) throws FtpException;

    boolean upload(String path, InputStream input) throws FtpException;

    boolean download(String path, OutputStream output) throws FtpException;

    boolean delete(String path) throws FtpException;

    /** Rename/move a file or directory. Backends without RNFR/RNTO may not support this. */
    default boolean rename(String from, String to) throws FtpException {
        throw new UnsupportedOperationException("rename is not supported by this connection");
    }

    /** Create a directory. Backends without MKD may not support this. */
    default boolean makeDirectory(String path) throws FtpException {
        throw new UnsupportedOperationException("makeDirectory is not supported by this connection");
    }

    FtpFile[] listFiles(String path) throws FtpException;

    /** Completes a pending transfer (data connection finish). */
    void completePendingCommand() throws FtpException;

    /** Lightweight liveness check (e.g. NOOP). */
    boolean isValid();

    /** Mark the physical connection as broken — it will be destroyed on release. */
    void markBroken();

    boolean isBroken();
}