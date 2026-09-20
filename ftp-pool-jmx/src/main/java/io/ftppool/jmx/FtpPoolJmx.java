package io.ftppool.jmx;

import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolStats;
import io.ftppool.api.PoolEngine;
import io.ftppool.core.FtpPoolImpl;
import lombok.extern.slf4j.Slf4j;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Objects;

/**
 * {@link FtpPoolMXBean} implementation bound to a live pool (spec section 43).
 *
 * <p>Register on the platform MBean server with:</p>
 *
 * <pre>{@code
 * FtpPoolJmx.register(pool, pool.stats(), engine);
 * }</pre>
 *
 * <p>Read attributes come straight from {@link FtpPoolStats}; operations delegate
 * to the {@link PoolEngine} (clearIdle/validateAll) and {@link FtpPool} (shutdown).
 * Exposes no credentials and no high-cardinality data.</p>
 */
@Slf4j
public final class FtpPoolJmx implements FtpPoolMXBean {

    public static final String JMX_DOMAIN = "com.ftppool";
    public static final String JMX_TYPE = "FtpPool";

    private final String poolName;
    private final FtpPoolStats stats;
    private final PoolEngine<?> engine;
    private final FtpPool pool;

    public FtpPoolJmx(String poolName, FtpPoolStats stats, PoolEngine<?> engine, FtpPool pool) {
        this.poolName = Objects.requireNonNull(poolName, "poolName");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    // ------------------------- read attributes -------------------------

    @Override
    public String getPoolName() {
        return poolName;
    }

    @Override
    public int getTotal() {
        return stats.total();
    }

    @Override
    public int getActive() {
        return stats.active();
    }

    @Override
    public int getIdle() {
        return stats.idle();
    }

    @Override
    public int getPending() {
        return stats.pending();
    }

    @Override
    public long getBorrowCount() {
        return stats.borrowed();
    }

    @Override
    public long getBorrowTimeoutCount() {
        return stats.borrowTimeouts();
    }

    @Override
    public long getCreateCount() {
        return stats.created();
    }

    @Override
    public long getDestroyCount() {
        return stats.destroyed();
    }

    @Override
    public long getReturnCount() {
        return stats.returned();
    }

    @Override
    public long getValidationFailureCount() {
        return stats.validationFailures();
    }

    // ------------------------- operations -------------------------

    @Override
    public void clearIdle() {
        engine.clearIdle();
        log.info("JMX clearIdle() on pool '{}'", poolName);
    }

    @Override
    public void validateAll() {
        engine.validateAll();
        log.info("JMX validateAll() on pool '{}'", poolName);
    }

    @Override
    public void shutdown() {
        log.info("JMX shutdown() on pool '{}'", poolName);
        pool.close();
    }

    // ------------------------- registration -------------------------

    /** Registers on the platform MBean server. */
    public static ObjectName register(FtpPoolStats stats, PoolEngine<?> engine, FtpPool pool) {
        return register(poolNameOf(pool), stats, engine, pool);
    }

    /** Registers on the platform MBean server under an explicit pool name. */
    public static ObjectName register(String poolName, FtpPoolStats stats,
                                      PoolEngine<?> engine, FtpPool pool) {
        return register(poolName, stats, engine, pool, ManagementFactory.getPlatformMBeanServer());
    }

    /** Registers on an explicit {@link MBeanServer}. Returns the ObjectName used. */
    public static ObjectName register(String poolName, FtpPoolStats stats,
                                      PoolEngine<?> engine, FtpPool pool, MBeanServer server) {
        Objects.requireNonNull(server, "server");
        try {
            ObjectName name = objectName(poolName);
            if (server.isRegistered(name)) {
                log.warn("JMX bean already registered for pool '{}', replacing", poolName);
                server.unregisterMBean(name);
            }
            server.registerMBean(new FtpPoolJmx(poolName, stats, engine, pool), name);
            log.info("Registered JMX {} for pool '{}'", name, poolName);
            return name;
        } catch (JMException e) {
            throw new IllegalStateException("Unable to register JMX bean for pool '" + poolName + "'", e);
        }
    }

    /** Unregisters from the platform MBean server (no-op if not registered). */
    public static void unregister(ObjectName name) {
        unregister(name, ManagementFactory.getPlatformMBeanServer());
    }

    /** Unregisters from an explicit {@link MBeanServer} (no-op if not registered). */
    public static void unregister(ObjectName name, MBeanServer server) {
        try {
            if (name != null && server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (JMException e) {
            log.warn("Unable to unregister JMX bean {}", name, e);
        }
    }

    public static ObjectName objectName(String poolName) {
        try {
            return new ObjectName(
                    JMX_DOMAIN + ":type=" + JMX_TYPE + ",name=" + ObjectName.quote(poolName));
        } catch (JMException e) {
            throw new IllegalArgumentException("Invalid pool name for JMX: '" + poolName + "'", e);
        }
    }

    private static String poolNameOf(FtpPool pool) {
        if (pool instanceof FtpPoolImpl impl) {
            return impl.poolName();
        }
        return "default";
    }
}