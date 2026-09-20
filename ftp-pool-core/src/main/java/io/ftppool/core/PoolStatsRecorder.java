package io.ftppool.core;

/**
 * Sink for pool-level events. Engine implementations report into this so
 * {@link FtpPoolStatsImpl} can aggregate.
 */
public interface PoolStatsRecorder {

    void recordCreate();

    void recordDestroy();

    void recordBorrow();

    void recordReturn();

    void recordBorrowTimeout();

    void recordValidation();

    void recordValidationFailure();

    void recordWaitStart();

    void recordWaitEnd();
}