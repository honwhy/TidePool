package io.ftppool.engine.commons;

import io.ftppool.api.PoolEngine;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Commons-Pool-based engine: object factory lifecycle, eviction, idle/min/max
 * management (spec section 15 / 73).
 *
 * <p>Behaves explicitly and predictably: {@code testOnBorrow} stays off (FTP
 * validation is network RTT heavy, spec 11.1) — liveness is checked by
 * {@code testWhileIdle} eviction runs scheduled at {@code validation-interval}.
 * State reset runs on return: a failed reset makes Commons destroy the object
 * instead of re-issuing it (failed reset ⇒ destroy, never idle). Max-lifetime
 * retirement is applied at borrow time.</p>
 */
@Slf4j
public final class CommonsPoolEngine<T> implements PoolEngine<T> {

    private static final int MAX_LIFETIME_REBORROW_TRIES = 3;

    private final GenericObjectPool<T> pool;
    private final Duration maxLifetime;
    private final PoolStatsRecorder stats;
    private final ResourceFactory<T> factory;
    private final Map<T, Instant> created = new ConcurrentHashMap<>();

    public CommonsPoolEngine(ResourceFactory<T> factory,
                             PoolConfiguration config,
                             PoolStatsRecorder stats) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.maxLifetime = config.getMaxLifetime();
        this.stats = stats;
        this.pool = new GenericObjectPool<>(new Factory<>(factory, created, stats), poolConfig(config));
    }

    private static <T> GenericObjectPoolConfig<T> poolConfig(PoolConfiguration config) {
        GenericObjectPoolConfig<T> pc = new GenericObjectPoolConfig<>();
        pc.setMaxTotal(config.getMaxSize());
        pc.setMaxIdle(config.getMaxSize());
        pc.setMinIdle(config.getMinIdle());
        pc.setMaxWait(Duration.ofMillis(config.getConnectionTimeout().toMillis()));
        pc.setBlockWhenExhausted(true);

        // Validation cadence: no per-borrow NOOP; idle liveness via eviction runs.
        pc.setTestOnBorrow(false);
        pc.setTestOnReturn(false);
        pc.setTestWhileIdle(true);
        long runEvery = Math.max(1, config.getValidationInterval().toSeconds());
        pc.setTimeBetweenEvictionRuns(Duration.ofSeconds(runEvery));

        pc.setMinEvictableIdleTime(config.getIdleTimeout());
        pc.setSoftMinEvictableIdleTime(config.getIdleTimeout());
        return pc;
    }

    @Override
    public T borrow(Duration timeout) throws Exception {
        Duration wait = timeout == null || timeout.isZero()
                ? Duration.ofMillis(pool.getMaxWaitDuration().toMillis())
                : timeout;
        stats.recordWaitStart();
        try {
            for (int attempt = 0; ; attempt++) {
                T resource;
                try {
                    resource = pool.borrowObject(wait);
                } catch (NoSuchElementException e) {
                    stats.recordBorrowTimeout();
                    throw new TimeoutException(
                            "Timed out borrowing from pool after " + wait.toMillis() + " ms: " + e.getMessage());
                }
                if (!expired(resource)) {
                    stats.recordBorrow();
                    return resource;
                }
                // Max-lifetime reached (spec section 26): retire, never hand out.
                pool.invalidateObject(resource);
                if (attempt >= MAX_LIFETIME_REBORROW_TRIES) {
                    throw new Exception("Max lifetime retries exhausted while borrowing from pool");
                }
            }
        } finally {
            stats.recordWaitEnd();
        }
    }

    @Override
    public void release(T resource) {
        try {
            pool.returnObject(resource);
            stats.recordReturn();
        } catch (Exception e) {
            log.warn("Return failed for resource on pool, destroying", e);
            invalidate(resource);
        }
    }

    @Override
    public void invalidate(T resource) {
        try {
            pool.invalidateObject(resource);
        } catch (Exception e) {
            log.debug("Invalidate failed for resource on pool", e);
        }
        created.remove(resource);
    }

    @Override
    public int size() {
        return pool.getNumIdle() + pool.getNumActive();
    }

    @Override
    public int active() {
        return pool.getNumActive();
    }

    @Override
    public int idle() {
        return pool.getNumIdle();
    }

    @Override
    public void close() {
        pool.close();
    }

    @Override
    public void clearIdle() {
        pool.clear();
    }

    @Override
    public void validateAll() {
        // Cycle the currently-idle objects through an explicit validation;
        // failures are retired until the re-issued connection is valid. Bounded
        // by the idle count observed up front: a returned valid object stays
        // idle, so an unbounded while(idle>0) would loop forever. The borrow
        // uses a 1ms wait — Commons treats 0ms as "wait forever".
        int target = pool.getNumIdle();
        for (int i = 0; i < target && pool.getNumIdle() > 0; i++) {
            T resource;
            try {
                resource = pool.borrowObject(Duration.ofMillis(1));
            } catch (Exception e) {
                return; // race: nothing left idle
            }
            stats.recordValidation();
            boolean valid;
            try {
                valid = factory.validate(resource);
            } catch (Exception e) {
                valid = false;
            }
            if (valid) {
                try {
                    pool.returnObject(resource);
                } catch (Exception e) {
                    retire(resource);
                }
            } else {
                stats.recordValidationFailure();
                retire(resource);
            }
        }
    }

    private void retire(T resource) {
        try {
            pool.invalidateObject(resource);
        } catch (Exception e) {
            log.debug("Retire failed for resource on pool", e);
        }
    }

    private boolean expired(T resource) {
        Instant createdInstant = created.get(resource);
        if (createdInstant == null || maxLifetime == null || maxLifetime.isZero() || maxLifetime.isNegative()) {
            return false;
        }
        return Instant.now().isAfter(createdInstant.plus(maxLifetime));
    }

    private static final class Factory<T> extends BasePooledObjectFactory<T> {

        private final ResourceFactory<T> delegate;
        private final Map<T, Instant> created;
        private final PoolStatsRecorder stats;

        Factory(ResourceFactory<T> delegate, Map<T, Instant> created, PoolStatsRecorder stats) {
            this.delegate = delegate;
            this.created = created;
            this.stats = stats;
        }

        @Override
        public T create() throws Exception {
            T resource = delegate.create();
            created.put(resource, Instant.now());
            return resource;
        }

        @Override
        public PooledObject<T> wrap(T resource) {
            return new DefaultPooledObject<>(resource);
        }

        @Override
        public boolean validateObject(PooledObject<T> p) {
            stats.recordValidation();
            boolean valid = delegate.validate(p.getObject());
            if (!valid) {
                stats.recordValidationFailure();
            }
            return valid;
        }

        @Override
        public void passivateObject(PooledObject<T> p) throws Exception {
            if (!delegate.reset(p.getObject())) {
                // Failed reset must destroy, never idle.
                throw new IllegalStateException("Connection state reset failed");
            }
        }

        @Override
        public void destroyObject(PooledObject<T> p) throws Exception {
            try {
                delegate.destroy(p.getObject());
            } finally {
                created.remove(p.getObject());
                stats.recordDestroy();
            }
        }
    }
}