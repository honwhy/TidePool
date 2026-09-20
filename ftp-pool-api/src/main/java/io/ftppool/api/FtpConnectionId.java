package io.ftppool.api;

/**
 * Unique identity of a pooled connection entry.
 *
 * <p>Used for metrics, debug, JMX, leak detection and logging
 * ({@code connectionId=ftp-000012}).</p>
 */
public record FtpConnectionId(long id) {

    @Override
    public String toString() {
        return "ftp-%06d".formatted(id);
    }
}