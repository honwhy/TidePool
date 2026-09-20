package io.ftppool.engine.fast;

import io.ftppool.api.PoolEngine;
import io.ftppool.core.LifecycleType;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hikari-inspired fast pool engine: CAS fast-path, thread-local candidates,
 * shared idle queue, throttled creation, housekeeper eviction.
 *
 * <p>Manages generic {@code T} resources; FTP specifics stay in
 * {@link ResourceFactory}. One engine instance serves one pool.</p>
 */
@Slf4j
public final class FastPoolEngine<T> implements PoolEngine<T>, io.ftppool.core.FtpPoolStatsImpl.ActiveStats {

    private final ResourceFactory<T> factory;
    private final PoolConfiguration config;
    private final PoolStatsRecorder stats;

    /** All live (undestroyed) entries, whether idle or borrowed. */
    private final CopyOnWriteArrayList<Entry<T>> entries = new CopyOnWriteArrayList<>();
    /** Idle, borrowable entries. */
    private final ConcurrentLinkedQueue<Entry<T>> idleQueue = new ConcurrentLinkedQueue<>();

    private final ThreadLocal<List<Entry<T>>> threadLocal = ThreadLocal.withInitial(List::of);

    /** Bounded at max-create-concurrency to protect the FTP server. */
    private final Semaphore createPermit;

    private final ScheduledExecutorService housekeeper;
    /** COMMONS lifecycle enables periodic maintenance; SIMPLE only pre-fills min-idle once. */
    private final boolean fullLifecycle;
    private final AtomicInteger activeCount = new AtomicInteger();

    private volatile boolean closed;
    /** Graceful-shutdown phase: no new borrows, no maintenance, but drain first. */
    private volatile boolean shuttingDown;

    public FastPoolEngine(ResourceFactory<T> factory, PoolConfiguration config, PoolStatsRecorder stats) {
        this.factory = factory;
        this.config = config;
        this.stats = stats;
        this.createPermit = new Semaphore(config.getMaxCreateConcurrency());

        this.fullLifecycle = config.getLifecycle() == LifecycleType.COMMONS;
        long period = Math.max(1, config.getValidationInterval().toSeconds());
        this.housekeeper = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ftp-pool-housekeeper-" + config.getPoolName());
            t.setDaemon(true);
            return t;
        });
        if (fullLifecycle) {
            housekeeper.scheduleWithFixedDelay(this::housekeep, period, period, TimeUnit.SECONDS);
        } else {
            // SIMPLE lifecycle: honor min-idle once, then no background maintenance.
            housekeeper.execute(this::prefillMinIdle);
        }
    }

    @Override
    public T borrow(Duration timeout) throws Exception {
        if (closed || shuttingDown) {
            throw new IllegalStateException("Pool engine is closed");
        }
        long timeoutNanos = (timeout == null || timeout.isZero())
                ? config.getConnectionTimeout().toNanos()
                : timeout.toNanos();
        long deadline = System.nanoTime() + timeoutNanos;

        while (true) {
            // 1. fast path: thread-local candidates (CAS only, no locks)
            Entry<T> candidate = fastPath();
            if (candidate != null) {
                activeCount.incrementAndGet();
                stats.recordBorrow();
                return candidate.resource();
            }

            // 2. shared idle queue
            Entry<T> entry = pollIdleEntry();
            if (entry != null) {
                if (needsValidation(entry)) {
                    boolean valid = validate(entry);
                    if (!valid) {
                        continue; // destroyed inside validate, retry loop
                    }
                }
                entry.touch();
                addThreadLocalCandidate(entry);
                activeCount.incrementAndGet();
                stats.recordBorrow();
                return entry.resource();
            }

            if (shuttingDown) {
                throw new java.util.concurrent.TimeoutException(
                        "Pool '" + config.getPoolName() + "' is shutting down");
            }

            // 3. create new below max-size (throttled)
            if (entries.size() < config.getMaxSize() && createPermit.tryAcquire()) {
                try {
                    Entry<T> created = createEntry();
                    if (created != null) {
                        created.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_IN_USE);
                        addThreadLocalCandidate(created);
                        activeCount.incrementAndGet();
                        stats.recordBorrow();
                        return created.resource();
                    }
                } finally {
                    createPermit.release();
                }
            }

            // 4. wait for a return, or give up on timeout
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new java.util.concurrent.TimeoutException(
                        "Timed out borrowing from '" + config.getPoolName() + "' after "
                                + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + " ms");
            }
            waitForReturn(deadline);
        }
    }

    @Override
    public void release(T resource) {
        Entry<T> entry = findEntry(resource);
        if (entry == null) {
            log.warn("Release of unknown resource on '{}'", config.getPoolName());
            return;
        }
        if (closed || shuttingDown
                || entry.created() + config.getMaxLifetime().toMillis() <= System.currentTimeMillis()) {
            // During shutdown a returned connection is destroyed rather than
            // re-idled, so the drain count converges and nothing is re-issued.
            invalidate(resource);
            return;
        }
        if (!entry.compareAndSetState(Entry.STATE_IN_USE, Entry.STATE_NOT_IN_USE)) {
            log.warn("Release of non-in-use resource on '{}'", config.getPoolName());
            return;
        }
        activeCount.decrementAndGet();
        // State reset on return (spec sections 9/10): FTPClient is highly stateful,
        // so a returned connection must be returned to a clean, predictable state.
        // A failed reset must destroy, never re-issue.
        if (!reset(entry)) {
            entry.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_REMOVED);
            idleQueue.remove(entry);
            entries.remove(entry);
            destroy(entry);
            return;
        }
        stats.recordReturn();
        idleQueue.offer(entry);
        wakeWaiters();
    }

    @Override
    public void invalidate(T resource) {
        removeAndDestroy(resource);
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public int active() {
        return activeCount.get();
    }

    @Override
    public int idle() {
        return Math.max(0, entries.size() - activeCount.get());
    }

    @Override
    public int total() {
        return entries.size();
    }

    @Override
    public void clearIdle() {
        Entry<T> entry;
        while ((entry = idleQueue.poll()) != null) {
            if (entry.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_REMOVED)) {
                entries.remove(entry);
                destroy(entry);
                log.debug("Cleared idle {} on '{}'", entry, config.getPoolName());
            } else {
                idleQueue.offer(entry); // lost a race to a borrower; put back
            }
        }
    }

    @Override
    public void validateAll() {
        for (Entry<T> entry : entries) {
            if (!entry.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_IN_USE)) {
                continue; // borrowed or already being validated
            }
            stats.recordValidation();
            boolean valid;
            try {
                valid = factory.validate(entry.resource());
            } catch (Exception ex) {
                valid = false;
            }
            if (valid) {
                entry.touch();
                entry.compareAndSetState(Entry.STATE_IN_USE, Entry.STATE_NOT_IN_USE);
            } else {
                stats.recordValidationFailure();
                idleQueue.remove(entry);
                entries.remove(entry);
                entry.compareAndSetState(Entry.STATE_IN_USE, Entry.STATE_REMOVED);
                destroy(entry);
                log.debug("validateAll destroyed {} on '{}'", entry, config.getPoolName());
            }
        }
    }

    @Override
    public void beginShutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        housekeeper.shutdownNow();
        wakeWaiters();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        shuttingDown = true;
        housekeeper.shutdownNow();
        for (Entry<T> entry : entries) {
            removeAndDestroy(entry.resource());
        }
        wakeWaiters();
    }

    // ---------------------------- internals ----------------------------

    private Entry<T> fastPath() {
        List<Entry<T>> list = threadLocal.get();
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry<T> e = list.get(i);
            if (e.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_IN_USE)) {
                e.touch();
                return e;
            }
        }
        return null;
    }

    private Entry<T> pollIdleEntry() {
        while (true) {
            Entry<T> e = idleQueue.poll();
            if (e == null) {
                return null;
            }
            if (e.compareAndSetState(Entry.STATE_NOT_IN_USE, Entry.STATE_IN_USE)) {
                return e;
            }
            // lost race; skip to next
        }
    }

    private void addThreadLocalCandidate(Entry<T> e) {
        List<Entry<T>> list = threadLocal.get();
        if (list.size() >= config.getMaxSize()) {
            for (Entry<T> evicted : list) {
                idleQueue.offer(evicted);
            }
            threadLocal.set(List.of());
        }
        List<Entry<T>> updated = new java.util.ArrayList<>(list.size() + 1);
        updated.addAll(list);
        updated.add(e);
        threadLocal.set(updated);
    }

    private boolean needsValidation(Entry<T> e) {
        if (!fullLifecycle) {
            return false;
        }
        long interval = config.getValidationInterval().toMillis();
        if (interval <= 0) {
            return false;
        }
        return System.currentTimeMillis() - e.lastAccessed() >= interval;
    }

    /** One-shot min-idle warm-up for the SIMPLE lifecycle (no periodic refill). */
    private void prefillMinIdle() {
        try {
            refillMinIdle();
        } catch (Exception ex) {
            log.debug("Min-idle prefill failed on '{}'", config.getPoolName(), ex);
        }
    }

    private void refillMinIdle() {
        while (entries.size() < config.getMaxSize() && idle() < config.getMinIdle() && createPermit.tryAcquire()) {
            try {
                Entry<T> created = createEntry();
                if (created != null) {
                    idleQueue.offer(created);
                } else {
                    break;
                }
            } finally {
                createPermit.release();
            }
        }
    }

    private boolean validate(Entry<T> e) {
        stats.recordValidation();
        try {
            boolean valid = factory.validate(e.resource());
            if (valid) {
                return true;
            }
            stats.recordValidationFailure();
        } catch (Exception ex) {
            stats.recordValidationFailure();
            log.debug("Validation threw for {} on '{}'", e, config.getPoolName(), ex);
        }
        removeAndDestroy(e.resource());
        return false;
    }

    private boolean reset(Entry<T> entry) {
        try {
            return factory.reset(entry.resource());
        } catch (Exception ex) {
            log.debug("State reset threw for {} on '{}'", entry, config.getPoolName(), ex);
            return false;
        }
    }

    private Entry<T> createEntry() {
        try {
            T resource = factory.create();
            Entry<T> entry = new Entry<>(resource);
            entries.add(entry);
            stats.recordCreate();
            log.debug("Created {}, total={} on '{}'", entry, entries.size(), config.getPoolName());
            return entry;
        } catch (Exception ex) {
            log.warn("Failed to create connection on '{}'", config.getPoolName(), ex);
            return null;
        }
    }

    private Entry<T> findEntry(T resource) {
        for (Entry<T> e : entries) {
            if (e.resource() == resource) {
                return e;
            }
        }
        return null;
    }

    private void removeAndDestroy(T resource) {
        Entry<T> entry = findEntry(resource);
        if (entry == null) {
            return;
        }
        idleQueue.remove(entry);
        entries.remove(entry);
        // An in-use entry still contributes to activeCount; an idle or already
        // returned one does not (release() already decremented).
        if (entry.compareAndSetState(Entry.STATE_IN_USE, Entry.STATE_REMOVED)) {
            activeCount.decrementAndGet();
        }
        destroy(entry);
    }

    private void destroy(Entry<T> entry) {
        if (!entry.tryDestroy()) {
            return;
        }
        try {
            factory.destroy(entry.resource());
            stats.recordDestroy();
            log.debug("Destroyed {} on '{}'", entry, config.getPoolName());
        } catch (Exception ex) {
            log.debug("Destroy failed for {} on '{}'", entry, config.getPoolName(), ex);
        }
    }

    private void waitForReturn(long deadline) {
        stats.recordWaitStart();
        try {
            synchronized (idleQueue) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0 && !closed && !shuttingDown) {
                    // Single-shot wait: any notify (a returned connection) must
                    // wake the borrower so the borrow loop can re-poll the idle
                    // queue. Re-entering the wait here was a bug: it held the
                    // borrower until timeout, ignoring released resources.
                    idleQueue.wait(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stats.recordWaitEnd();
        }
    }

    private void wakeWaiters() {
        synchronized (idleQueue) {
            idleQueue.notifyAll();
        }
    }

    /**
     * Housekeeper: idle eviction, max-lifetime retirement, min-idle refill.
     */
    private void housekeep() {
        if (closed || shuttingDown) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleTimeoutMillis = config.getIdleTimeout().toMillis();
        long maxLifetimeMillis = config.getMaxLifetime().toMillis();

        // rotate idle queue, destroy expired entries
        int idle = idle();
        int visited = 0;
        while (visited < Math.max(idle, 8)) {
            Entry<T> e = idleQueue.poll();
            if (e == null) {
                break;
            }
            visited++;
            boolean expiredIdle = idleTimeoutMillis > 0 && now - e.lastAccessed() > idleTimeoutMillis;
            boolean expiredLifetime = now - e.created() > maxLifetimeMillis;
            if (expiredIdle || expiredLifetime) {
                idleQueue.remove(e);
                entries.remove(e);
                destroy(e);
                log.debug("Evicted {} on '{}' (idleExpired={}, lifetimeExpired={})",
                        e, config.getPoolName(), expiredIdle, expiredLifetime);
            } else {
                idleQueue.offer(e);
            }
        }

        // min-idle refill
        refillMinIdle();
        wakeWaiters();
    }
}