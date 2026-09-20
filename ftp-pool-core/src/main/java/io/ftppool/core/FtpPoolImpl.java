package io.ftppool.core;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.api.FtpPoolStats;
import io.ftppool.api.PoolEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link FtpPool} implementation.
 *
 * <p>Collates the three axes mandated by the spec: a pluggable
 * {@link PoolEngine} (Fast/Commons), the FTP {@link FtpConnectionFactory}
 * (lifecycle), and an optional {@link FtpFilter} chain (observability). The
 * pool engine only ever manages {@link FtpPoolEntry} — FTPClient never leaks
 * through this facade.</p>
 */
@Slf4j
public final class FtpPoolImpl implements FtpPool {

    private final String poolName;
    private final PoolConfiguration config;
    private final PoolEngine<FtpPoolEntry> engine;
    private final FtpConnectionFactory connectionFactory;
    private final Map<FtpConnectionId, FtpPoolEntry> entries = new ConcurrentHashMap<>();
    private final List<FtpFilter> filters;
    private final FtpPoolStatsImpl stats;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService leakDetector;
    /** Handles from optional MBean registrars, closed on shutdown. */
    private final List<AutoCloseable> mbeanHandles = new java.util.concurrent.CopyOnWriteArrayList<>();

    public FtpPoolImpl(String poolName,
                       PoolConfiguration config,
                       PoolEngine<FtpPoolEntry> engine,
                       FtpConnectionFactory connectionFactory,
                       List<FtpFilter> filters,
                       FtpPoolStatsImpl stats) {
        this.poolName = Objects.requireNonNull(poolName, "poolName");
        this.config = Objects.requireNonNull(config, "config");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.filters = List.copyOf(filters == null ? List.of() : filters);
        this.stats = stats;
        long thresholdMillis = config.getLeakDetectionThreshold().toMillis();
        if (thresholdMillis > 0) {
            this.leakDetector = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "ftp-pool-leak-detector-" + poolName);
                t.setDaemon(true);
                return t;
            });
            this.leakDetector.scheduleWithFixedDelay(
                    this::checkForLeaks, thresholdMillis, thresholdMillis, TimeUnit.MILLISECONDS);
        } else {
            this.leakDetector = null;
        }
    }

    @Override
    public FtpConnection borrow() throws FtpPoolException {
        return borrow(null);
    }

    @Override
    public FtpConnection borrow(Duration timeout) throws FtpPoolException {
        if (closed.get()) {
            throw FtpPoolException.poolClosed(poolName);
        }
        FtpContext context = new FtpContext(poolName, null, FtpOperation.BORROW);
        for (FtpFilter filter : filters) {
            filter.beforeBorrow(context);
        }
        Duration effective = timeout == null ? config.getConnectionTimeout() : timeout;
        try {
            FtpPoolEntry entry = engine.borrow(effective);
            entry.beginBorrow();
            entries.put(entry.id(), entry);
            FtpConnection connection = instrument(entry.connection());
            context.completed(true, Duration.between(context.getStartTime(), Instant.now()));
            for (FtpFilter filter : filters) {
                filter.afterBorrow(context, connection);
            }
            return connection;
        } catch (FtpPoolException e) {
            stats.recordBorrowTimeout();
            context.failed(e, e.getType());
            for (FtpFilter filter : filters) {
                filter.onError(context, e);
            }
            throw e;
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.TimeoutException) {
                stats.recordBorrowTimeout();
            }
            context.failed(e, FtpExceptionType.POOL_TIMEOUT);
            for (FtpFilter filter : filters) {
                filter.onError(context, e);
            }
            throw new FtpPoolException(
                    FtpExceptionType.POOL_TIMEOUT,
                    "Borrow failed from pool '" + poolName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void release(FtpConnection connection) {
        if (connection == null) {
            return;
        }
        FtpConnectionId id = connection.id();
        FtpPoolEntry entry = entries.remove(id);
        if (entry != null) {
            entry.endBorrow();
        }
        FtpContext context = new FtpContext(poolName, id, FtpOperation.RETURN);
        for (FtpFilter filter : filters) {
            filter.beforeReturn(context);
        }
        if (entry == null) {
            log.warn("Release of unknown/foreign connection {} on pool '{}', destroying", id, poolName);
            context.failed(new FtpException(FtpExceptionType.CONNECTION, "Foreign connection released"),
                    FtpExceptionType.CONNECTION);
            connection.markBroken();
            connectionWasReturned(context);
            return;
        }
        if (closed.get() || connection.isBroken()) {
            log.debug("Destroying {} on pool '{}' (closed={}, broken={})",
                    id, poolName, closed.get(), connection.isBroken());
            engine.invalidate(entry);
        } else {
            engine.release(entry);
        }
        context.completed(true, Duration.between(context.getStartTime(), Instant.now()));
        connectionWasReturned(context);
    }

    private void connectionWasReturned(FtpContext context) {
        for (FtpFilter filter : filters) {
            filter.afterReturn(context);
        }
    }

    /**
     * Wrap the physical connection so per-operation events reach the filter
     * chain (spec sections 35/36). Skipped entirely when no filters are
     * installed, keeping the fast path allocation-free.
     */
    private FtpConnection instrument(FtpConnection connection) {
        if (filters.isEmpty()) {
            return connection;
        }
        return new InstrumentedFtpConnection(poolName, connection, filters);
    }

    @Override
    public <T> T execute(FtpCallback<T> callback) throws FtpException {
        Objects.requireNonNull(callback, "callback");
        FtpConnection connection = borrow();
        FtpContext context = new FtpContext(poolName, connection.id(), FtpOperation.EXECUTE);
        try {
            for (FtpFilter filter : filters) {
                filter.beforeExecute(context);
            }
            T result = callback.execute(connection);
            context.completed(true, Duration.between(context.getStartTime(), Instant.now()));
            for (FtpFilter filter : filters) {
                filter.afterExecute(context);
            }
            return result;
        } catch (RuntimeException | Error e) {
            context.failed(e, classify(e));
            for (FtpFilter filter : filters) {
                filter.onError(context, e);
            }
            if (isConnectionFailure(e)) {
                connection.markBroken();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    @Override
    public FtpPoolStats stats() {
        return stats;
    }

    public String poolName() {
        return poolName;
    }

    public PoolConfiguration configuration() {
        return config;
    }

    public FtpConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    public PoolEngine<FtpPoolEntry> engine() {
        return engine;
    }

    /** Registers a management handle to be closed on {@link #close()} (JMX, etc.). */
    public void addMBeanHandle(AutoCloseable handle) {
        if (handle != null) {
            mbeanHandles.add(handle);
        }
    }

    /**
     * Leak detector (spec section 40): warn once per connection still borrowed
     * past {@code leak-detection-threshold}. Logs low-cardinality breadcrumbs
     * only — borrow thread, borrow age, connection id, captured stack. Never
     * force-destroys.
     */
    private void checkForLeaks() {
        long thresholdMillis = config.getLeakDetectionThreshold().toMillis();
        long now = System.currentTimeMillis();
        for (FtpPoolEntry entry : entries.values()) {
            long borrowedFor = now - entry.borrowStartedAtMillis();
            if (entry.borrowStartedAtMillis() > 0 && borrowedFor >= thresholdMillis && !entry.leakReported()) {
                entry.markLeakReported();
                log.warn("Possible connection leak: pool={}, connectionId={}, borrowThread={}, "
                                + "borrowedFor={}ms, threshold={}ms, trace={}",
                        poolName, entry.id(), entry.borrowThread(), borrowedFor, thresholdMillis,
                        entry.borrowStackTrace());
            }
        }
    }

    /**
     * Graceful shutdown (spec section 62): stop accepting borrows and halt
     * background maintenance, wait up to {@code shutdown-timeout} for in-flight
     * operations to return their connections, then destroy everything.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing pool '{}' (draining up to {}ms)", poolName,
                config.getShutdownTimeout().toMillis());
        if (leakDetector != null) {
            leakDetector.shutdownNow();
        }
        engine.beginShutdown();
        awaitDrain();
        engine.close();
        for (FtpPoolEntry entry : entries.values()) {
            engine.invalidate(entry);
        }
        entries.clear();
        for (AutoCloseable handle : mbeanHandles) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("Failed to unregister management handle for pool '{}'", poolName, e);
            }
        }
        mbeanHandles.clear();
    }

    /** Poll until no connection is borrowed, bounded by {@code shutdown-timeout}. */
    private void awaitDrain() {
        long timeoutMillis = config.getShutdownTimeout().toMillis();
        if (timeoutMillis <= 0) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (engine.active() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        int stillActive = engine.active();
        if (stillActive > 0) {
            log.warn("Pool '{}' closing with {} connection(s) still borrowed after {}ms drain timeout",
                    poolName, stillActive, timeoutMillis);
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /** CONNECTION-class exceptions destroy the connection; BUSINESS/TIMEOUT keep it. */
    private static boolean isConnectionFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof FtpException ftpException) {
                FtpExceptionType type = ftpException.getType();
                if (type == FtpExceptionType.CONNECTION
                        || type == FtpExceptionType.AUTHENTICATION
                        || type == FtpExceptionType.VALIDATION) {
                    return true;
                }
                return false;
            }
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static FtpExceptionType classify(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof FtpException ftpException) {
                return ftpException.getType();
            }
            if (cause instanceof IOException) {
                return FtpExceptionType.CONNECTION;
            }
            cause = cause.getCause();
        }
        return FtpExceptionType.BUSINESS;
    }
}