package io.ftppool.micrometer;

import io.ftppool.api.FtpOperation;
import io.ftppool.api.FtpPoolStats;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.core.FtpPoolImpl;
import io.ftppool.core.FtpPoolStatsImpl;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.observability.FtpMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FtpMicrometerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void bindPoolExposesGaugesAndLifecycleCounters() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 8, () -> 3);
        stats.recordBorrow();
        stats.recordCreate();
        FtpPoolImpl pool = new FtpPoolImpl("p1", PoolConfiguration.createDefault(),
                new NoopEngine(), new NoopFactory(), null, stats);

        FtpMicrometer.bindPool(registry, pool);

        assertThat(registry.find("ftp.pool.size").gauge().value()).isEqualTo(8.0);
        assertThat(registry.find("ftp.pool.active").gauge().value()).isEqualTo(3.0);
        assertThat(registry.find("ftp.pool.idle").gauge().value()).isEqualTo(5.0);
        assertThat(registry.find("ftp.pool.pending").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("ftp.pool.borrow").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("ftp.pool.create").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("ftp.pool.borrow.timeout").functionCounter()).isNotNull();
        assertThat(registry.find("ftp.pool.destroy").functionCounter()).isNotNull();
        assertThat(registry.find("ftp.pool.validation.failure").functionCounter()).isNotNull();
        assertThat(registry.find("ftp.pool.size").gauge().getId().getTag("pool")).isEqualTo("p1");
    }

    @Test
    void bindPoolIsIdempotentPerRegistry() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 2, () -> 1);
        FtpPoolImpl pool = new FtpPoolImpl("p2", PoolConfiguration.createDefault(),
                new NoopEngine(), new NoopFactory(), null, stats);

        FtpMicrometer.bindPool(registry, pool);
        FtpMicrometer.bindPool(registry, pool);

        assertThat(registry.find("ftp.pool.size").gauges()).hasSize(1);
    }

    @Test
    void metricsSinkRecordsOperationCountersAndLatency() {
        FtpMetrics sink = FtpMicrometer.metricsSink(registry);

        sink.recordOperation(FtpOperation.UPLOAD, 5_000L, true);
        sink.recordOperation(FtpOperation.UPLOAD, 9_000L, false);
        sink.recordBorrow(1_000L);

        assertThat(registry.find("ftp.operation.upload").tag("result", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ftp.operation.upload").tag("result", "failure").counter().count()).isEqualTo(1.0);

        Timer timer = registry.find("ftp.operation.latency").tag("operation", "upload").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(14.0);

        assertThat(registry.find("ftp.operation.latency").tag("operation", "borrow").timer().count()).isEqualTo(1);
    }

    @Test
    void metricsSinkRecordsTransferredBytes() {
        FtpMetrics sink = FtpMicrometer.metricsSink(registry);

        sink.recordOperation(FtpOperation.UPLOAD, 1_000L, true, 2_048L);
        sink.recordOperation(FtpOperation.DOWNLOAD, 1_000L, true, 512L);

        assertThat(registry.find("ftp.operation.bytes").tag("operation", "upload").counter().count())
                .isEqualTo(2_048.0);
        assertThat(registry.find("ftp.operation.bytes").tag("operation", "download").counter().count())
                .isEqualTo(512.0);
    }

    @Test
    void borrowTimeoutMapsToPoolCounter() {
        FtpMetrics sink = FtpMicrometer.metricsSink(registry);

        sink.recordBorrowFailure(io.ftppool.api.FtpExceptionType.POOL_TIMEOUT);

        assertThat(registry.find("ftp.pool.borrow.timeout").counter()).isNotNull();
        assertThat(registry.find("ftp.pool.borrow.timeout").counter().count()).isEqualTo(1.0);
    }

    @Test
    void slowOperationCounterAccumulates() {
        FtpMetrics sink = FtpMicrometer.metricsSink(registry);

        sink.recordSlowOperation(FtpOperation.DOWNLOAD, 4_200L);

        assertThat(registry.find("ftp.operation.slow").counter().count()).isEqualTo(1.0);
    }

    @Test
    void metricTagsStayLowCardinality() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 4, () -> 0);
        FtpPoolImpl pool = new FtpPoolImpl("lowcard", PoolConfiguration.createDefault(),
                new NoopEngine(), new NoopFactory(), null, stats);

        FtpMicrometer.bindPool(registry, pool);

        String tags = registry.find("ftp.pool.size").gauge().getId().toString();

        assertThat(tags).contains("pool=").doesNotContain("username").doesNotContain("filePath");
    }

    private static final class NoopFactory implements FtpConnectionFactory {

        @Override
        public io.ftppool.api.FtpConnection create() {
            return new NoopConnection();
        }

        @Override
        public boolean validate(io.ftppool.api.FtpConnection connection) {
            return !connection.isBroken();
        }

        @Override
        public boolean reset(io.ftppool.api.FtpConnection connection) {
            return !connection.isBroken();
        }

        @Override
        public void destroy(io.ftppool.api.FtpConnection connection) {
        }
    }

    private static final class NoopEngine implements io.ftppool.api.PoolEngine<io.ftppool.core.FtpPoolEntry> {

        private final AtomicInteger active = new AtomicInteger();

        @Override
        public io.ftppool.core.FtpPoolEntry borrow(java.time.Duration timeout) {
            return null;
        }

        @Override
        public void release(io.ftppool.core.FtpPoolEntry resource) {
        }

        @Override
        public void invalidate(io.ftppool.core.FtpPoolEntry resource) {
            active.decrementAndGet();
        }

        @Override
        public int size() {
            return 8;
        }

        @Override
        public int active() {
            return active.get();
        }

        @Override
        public int idle() {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopConnection implements io.ftppool.api.FtpConnection {

        @Override
        public io.ftppool.api.FtpConnectionId id() {
            return new io.ftppool.api.FtpConnectionId(1);
        }

        @Override
        public void changeDirectory(String path) {
        }

        @Override
        public String currentDirectory() {
            return "/";
        }

        @Override
        public java.io.InputStream retrieveFileStream(String path) {
            return null;
        }

        @Override
        public java.io.OutputStream storeFileStream(String path) {
            return null;
        }

        @Override
        public boolean upload(String path, java.io.InputStream input) {
            return true;
        }

        @Override
        public boolean download(String path, java.io.OutputStream output) {
            return true;
        }

        @Override
        public boolean delete(String path) {
            return true;
        }

        @Override
        public io.ftppool.api.FtpFile[] listFiles(String path) {
            return new io.ftppool.api.FtpFile[0];
        }

        @Override
        public void completePendingCommand() {
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void markBroken() {
        }

        @Override
        public boolean isBroken() {
            return false;
        }
    }
}