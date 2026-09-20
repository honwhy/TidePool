package io.ftppool.api;

import lombok.Getter;

/**
 * Base checked-independent exception for all FtpPool failures.
 *
 * <p>Carries an {@link FtpExceptionType} so callers and the pool itself can
 * classify the failure. Passwords must never appear in the message.</p>
 */
@Getter
public class FtpException extends RuntimeException {

    private final FtpExceptionType type;

    public FtpException(FtpExceptionType type, String message) {
        super(message);
        this.type = type;
    }

    public FtpException(FtpExceptionType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public FtpException(FtpExceptionType type, Throwable cause) {
        super(cause);
        this.type = type;
    }
}