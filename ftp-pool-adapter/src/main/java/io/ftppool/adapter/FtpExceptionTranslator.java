package io.ftppool.adapter;

import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import org.apache.commons.net.ftp.FTPConnectionClosedException;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Maps Apache Commons Net failures onto {@link FtpException} so the pool can
 * decide destroy-vs-return (spec section 33).
 *
 * <p>Physically broken links are always classified CONNECTION (the entry must be
 * destroyed); server reply rejections are surfaced as BUSINESS (reusable
 * connection). Messages never contain credentials.</p>
 */
public final class FtpExceptionTranslator {

    private FtpExceptionTranslator() {
    }

    /** A physical link failure — the connection must be destroyed, never returned. */
    public static FtpException connection(String message, IOException cause) {
        return new FtpException(classify(cause), message, cause);
    }

    /** Server rejected an operation but the control connection is still usable. */
    public static FtpException business(String message) {
        return new FtpException(FtpExceptionType.BUSINESS, message);
    }

    static FtpExceptionType classify(IOException cause) {
        if (cause instanceof FTPConnectionClosedException) {
            return FtpExceptionType.CONNECTION;
        }
        if (cause instanceof SocketTimeoutException) {
            return FtpExceptionType.TIMEOUT;
        }
        return FtpExceptionType.CONNECTION;
    }
}