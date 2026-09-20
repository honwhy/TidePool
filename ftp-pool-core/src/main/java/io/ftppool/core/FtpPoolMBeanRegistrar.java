package io.ftppool.core;

/**
 * Optional SPI implemented by management backends (e.g. {@code ftp-pool-jmx})
 * so a plain-Java {@link FtpPoolBuilder#build()} can expose the pool without
 * core taking a compile dependency on the JMX module.
 *
 * <p>Discovered through {@code META-INF/services}. The returned handle is closed
 * when the pool shuts down, so the MBean is unregistered automatically.</p>
 */
public interface FtpPoolMBeanRegistrar {

    /** Register management beans for {@code pool}; return a close handle (may be {@code null}). */
    AutoCloseable register(FtpPoolImpl pool);
}
