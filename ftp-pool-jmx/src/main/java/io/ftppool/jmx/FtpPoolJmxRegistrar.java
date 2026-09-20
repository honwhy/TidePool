package io.ftppool.jmx;

import io.ftppool.core.FtpPoolImpl;
import io.ftppool.core.FtpPoolMBeanRegistrar;

import javax.management.ObjectName;

/**
 * Registers a plain-Java {@link FtpPoolImpl} on the platform MBean server when
 * {@code ftp-pool-jmx} is on the classpath (spec section 43), discovered via
 * {@code META-INF/services}. The returned handle unregisters the MBean when the
 * pool closes.
 */
public final class FtpPoolJmxRegistrar implements FtpPoolMBeanRegistrar {

    @Override
    public AutoCloseable register(FtpPoolImpl pool) {
        ObjectName name = FtpPoolJmx.register(pool.poolName(), pool.stats(), pool.engine(), pool);
        return () -> FtpPoolJmx.unregister(name);
    }
}
