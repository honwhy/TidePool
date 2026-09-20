package io.ftppool.core;

import io.ftppool.api.FtpProtocol;

/**
 * Service-provider interface for {@link FtpConnectionFactory} implementations,
 * discovered via {@code META-INF/services}. Registered by the adapter module.
 */
public interface FtpConnectionFactoryProvider {

    /** SPI name, e.g. "commons-net". */
    String name();

    /** Whether this provider can serve the given protocol variant. */
    boolean supports(FtpProtocol protocol);

    /**
     * Build a factory for the given server settings and pool tuning.
     *
     * @throws IllegalArgumentException when this provider cannot serve the protocol
     */
    FtpConnectionFactory create(FtpConnectionSettings settings, PoolConfiguration configuration);
}