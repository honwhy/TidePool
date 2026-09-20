package io.ftppool.jmx;

/**
 * Management view of one FTP pool (spec section 43).
 *
 * <p>Object name: {@code com.ftppool:type=FtpPool,name=<poolName>}. Read
 * attributes mirror {@link io.ftppool.api.FtpPoolStats}; the three operations
 * drive administrative maintenance. All read attributes must be cheap and
 * mutation-free.</p>
 */
public interface FtpPoolMXBean {

    String getPoolName();

    int getTotal();

    int getActive();

    int getIdle();

    int getPending();

    long getBorrowCount();

    long getBorrowTimeoutCount();

    long getCreateCount();

    long getDestroyCount();

    long getReturnCount();

    long getValidationFailureCount();

    /** Destroy all idle connections (see {@code PoolEngine#clearIdle()}). */
    void clearIdle();

    /** Validate all idle connections, destroying invalid ones. */
    void validateAll();

    /** Close the whole pool — no new borrows, active connections destroyed. */
    void shutdown();
}