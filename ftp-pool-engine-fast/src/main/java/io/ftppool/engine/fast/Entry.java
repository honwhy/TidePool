package io.ftppool.engine.fast;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slot metadata wrapped around a pooled resource.
 *
 * <p>State machine (Hikari-inspired CAS):
 * <ul>
 *   <li>NOT_IN_USE (-1) — idle, borrowable</li>
 *   <li>IN_USE (0) — handed out to a borrower</li>
 *   <li>REMOVED (-2) — destroyed, unreachable</li>
 * </ul></p>
 */
final class Entry<T> {

    static final int STATE_NOT_IN_USE = -1;
    static final int STATE_IN_USE = 0;
    static final int STATE_REMOVED = -2;

    private final T resource;
    private final AtomicInteger state = new AtomicInteger(STATE_NOT_IN_USE);
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final long created;
    private volatile long lastAccessed;
    private volatile boolean validationChecked;

    Entry(T resource) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.created = System.currentTimeMillis();
        this.lastAccessed = created;
    }

    T resource() {
        return resource;
    }

    long created() {
        return created;
    }

    long lastAccessed() {
        return lastAccessed;
    }

    void touch() {
        this.lastAccessed = System.currentTimeMillis();
        this.validationChecked = false;
    }

    boolean validationChecked() {
        return validationChecked;
    }

    void markValidationChecked() {
        this.validationChecked = true;
    }

    int state() {
        return state.get();
    }

    boolean compareAndSetState(int expected, int update) {
        return state.compareAndSet(expected, update);
    }

    /** Claims exclusive destruction rights; only the winning caller destroys. */
    boolean tryDestroy() {
        return destroyed.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "Entry{state=" + state.get() + "}";
    }
}