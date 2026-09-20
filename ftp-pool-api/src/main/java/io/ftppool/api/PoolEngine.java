package io.ftppool.api;

import java.time.Duration;

/**
 * Plug-in pool engine SPI (section 13 of the spec).
 *
 * <p>Engine implementations (Fast / Commons) only know a generic resource T —
 * they never see FTP specifics. FtpPool collates engines with the FTP resource
 * factory and lifecycle concern.</p>
 *
 * @param <T> pooled resource type (FtpPoolEntry)
 */
public interface PoolEngine<T> {

    T borrow(Duration timeout) throws Exception;

    void release(T resource);

    /** Drop a broken/non-reusable resource immediately. */
    void invalidate(T resource);

    int size();

    int active();

    int idle();

    void close();

    /**
     * Stop accepting new borrows and halt background maintenance (housekeeper /
     * evictor), <em>without</em> destroying borrowed resources. Used to drain
     * in-flight operations on graceful shutdown; {@link #close()} then tears
     * everything down.
     */
    default void beginShutdown() {
    }

    /**
     * Destroy every idle (non-borrowed) resource. Used by JMX {@code clearIdle()}
     * and administrative tooling; borrowed resources are left untouched.
     */
    default void clearIdle() {
    }

    /**
     * Validate all currently idle resources, destroying any that fail. Used by
     * JMX {@code validateAll()}. Implementations must not disturb borrowed
     * resources.
     */
    default void validateAll() {
    }
}