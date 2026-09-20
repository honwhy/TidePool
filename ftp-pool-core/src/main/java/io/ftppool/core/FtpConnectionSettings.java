package io.ftppool.core;

import io.ftppool.api.FtpProtocol;

import java.util.Objects;

/**
 * Server + credentials for creating physical connections.
 *
 * <p>{@code toString} masks the password — never log, metric or JMX a settings
 * object (spec section 63).</p>
 */
public record FtpConnectionSettings(
        String host,
        int port,
        String username,
        String password,
        FtpProtocol protocol,
        String encoding,
        FtpSslConfig ssl) {

    public static final int DEFAULT_FTP_PORT = 21;
    public static final int DEFAULT_IMPLICIT_FTPS_PORT = 990;
    public static final String DEFAULT_ENCODING = "UTF-8";

    /** Backwards-compatible convenience constructor without TLS config. */
    public FtpConnectionSettings(String host, int port, String username, String password,
                                 FtpProtocol protocol, String encoding) {
        this(host, port, username, password, protocol, encoding, FtpSslConfig.secureDefaults());
    }

    public FtpConnectionSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(protocol, "protocol");
        if (port <= 0) {
            port = defaultPort(protocol);
        }
        username = username == null ? "anonymous" : username;
        password = password == null ? "" : password;
        encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding;
        ssl = ssl == null ? FtpSslConfig.secureDefaults() : ssl;
    }

    public static int defaultPort(FtpProtocol protocol) {
        return protocol == FtpProtocol.FTPS_IMPLICIT ? DEFAULT_IMPLICIT_FTPS_PORT : DEFAULT_FTP_PORT;
    }

    @Override
    public String toString() {
        return "FtpConnectionSettings["
                + "host=" + host
                + ", port=" + port
                + ", username=" + username
                + ", password=******"
                + ", protocol=" + protocol
                + ", encoding=" + encoding
                + ", ssl=" + ssl
                + ']';
    }
}