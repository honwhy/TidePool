package io.ftppool.observability;

import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpOperation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend-agnostic sink for pool and FTP-operation observability (spec sections
 * 35 / 36). Implementations (default atomics, Micrometer, JMX…) consume the same
 * {@code MetricsFilter} events.
 *
 * <p>High-cardinality data (file paths, usernames) must never reach metrics tags
 * — only operation kinds and exception classes are carried.</p>
 */
public interface FtpMetrics {

    void recordBorrow(long latencyMicros);

    void recordBorrowFailure(FtpExceptionType type);

    void recordExecute(long latencyMicros, boolean success);

    void recordOperation(FtpOperation operation, long latencyMicros, boolean success);

    /**
     * Operation event carrying the transferred size (0 for non-transfer ops).
     * Defaults to the size-less overload so existing sinks keep working.
     */
    default void recordOperation(FtpOperation operation, long latencyMicros, boolean success, long bytes) {
        recordOperation(operation, latencyMicros, success);
    }

    void recordSlowOperation(FtpOperation operation, long latencyMillis);

    /** In-memory default implementation backed by atomics. */
    static FtpMetrics noop() {
        return new FtpMetrics() {
            @Override
            public void recordBorrow(long latencyMicros) {
            }

            @Override
            public void recordBorrowFailure(FtpExceptionType type) {
            }

            @Override
            public void recordExecute(long latencyMicros, boolean success) {
            }

            @Override
            public void recordOperation(FtpOperation operation, long latencyMicros, boolean success) {
            }

            @Override
            public void recordSlowOperation(FtpOperation operation, long latencyMillis) {
            }
        };
    }

    /** Convenience handler exposing all usable counters. */
    final class Default implements FtpMetrics {

        private final AtomicLong borrowCount = new AtomicLong();
        private final AtomicLong borrowFailureCount = new AtomicLong();
        private final AtomicLong executeCount = new AtomicLong();
        private final AtomicLong executeFailureCount = new AtomicLong();
        private final AtomicLong slowOperationCount = new AtomicLong();
        private final ConcurrentMap<FtpOperation, OperationCounters> operations = new ConcurrentHashMap<>();
        private final ConcurrentMap<FtpOperation, AtomicLong> bytes = new ConcurrentHashMap<>();

        @Override
        public void recordBorrow(long latencyMicros) {
            borrowCount.incrementAndGet();
            operation(FtpOperation.BORROW).record(latencyMicros, true);
        }

        @Override
        public void recordBorrowFailure(FtpExceptionType type) {
            borrowFailureCount.incrementAndGet();
            operation(FtpOperation.BORROW).record(0, false);
        }

        @Override
        public void recordExecute(long latencyMicros, boolean success) {
            executeCount.incrementAndGet();
            if (!success) {
                executeFailureCount.incrementAndGet();
            }
            operation(FtpOperation.EXECUTE).record(latencyMicros, success);
        }

        @Override
        public void recordOperation(FtpOperation op, long latencyMicros, boolean success) {
            if (op != null) {
                operation(op).record(latencyMicros, success);
            }
        }

        @Override
        public void recordOperation(FtpOperation op, long latencyMicros, boolean success, long transferredBytes) {
            recordOperation(op, latencyMicros, success);
            if (op != null && transferredBytes > 0) {
                bytes.computeIfAbsent(op, k -> new AtomicLong()).addAndGet(transferredBytes);
            }
        }

        @Override
        public void recordSlowOperation(FtpOperation operation, long latencyMillis) {
            slowOperationCount.incrementAndGet();
        }

        public long borrowCount() {
            return borrowCount.get();
        }

        public long borrowFailureCount() {
            return borrowFailureCount.get();
        }

        public long executeCount() {
            return executeCount.get();
        }

        public long executeFailureCount() {
            return executeFailureCount.get();
        }

        public long slowOperationCount() {
            return slowOperationCount.get();
        }

        public long operationCount(FtpOperation op) {
            return operation(op).count.get();
        }

        public long operationFailureCount(FtpOperation op) {
            return operation(op).failures.get();
        }

        /** Total bytes transferred for an operation (upload/download); 0 if none. */
        public long operationBytes(FtpOperation op) {
            AtomicLong counter = bytes.get(op);
            return counter == null ? 0L : counter.get();
        }

        private OperationCounters operation(FtpOperation op) {
            return operations.computeIfAbsent(op, k -> new OperationCounters());
        }

        private static final class OperationCounters {

            final AtomicLong count = new AtomicLong();
            final AtomicLong failures = new AtomicLong();

            void record(long latencyMicros, boolean success) {
                count.incrementAndGet();
                if (!success) {
                    failures.incrementAndGet();
                }
            }
        }
    }
}