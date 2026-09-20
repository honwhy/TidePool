package io.ftppool.observability;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpOperation;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.api.PoolEngine;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.core.FtpConnectionFactoryProvider;
import io.ftppool.core.FtpConnectionSettings;
import io.ftppool.core.FtpPoolProfiles;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolEngineFactory;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code hybrid} profile must resolve the {@code "full"} filter provider via
 * {@code META-INF/services} so FtpPoolBuilder wires observability without core
 * depending on this module.
 */
class ObservabilityWiringTest {

    @BeforeEach
    void clearRegistry() {
        ObservabilityFilterProvider.reset();
    }

    @Test
    void hybridProfileWiresObservabilityFilters() {
        FtpPool pool = FtpPoolProfiles.hybrid()
                .poolName("obs-test")
                .slowOperationThreshold(Duration.ZERO)
                .host("ftp.example.com")
                .username("user")
                .password("pw")
                .minIdle(0)
                .maxSize(4)
                .engineFactory(new StubEngineFactory())
                .connectionFactoryProvider(new StubProvider())
                .build();

        primitive(pool, connection -> connection.upload("/data/x.bin", InputStream.nullInputStream()));
        pool.close();

        FtpFilters.ObservabilityStack stack = ObservabilityFilterProvider.stackFor("obs-test");
        assertThat(stack).isNotNull();
        assertThat(stack.filters()).hasSize(3);
        assertThat(stack.metrics().borrowCount()).isGreaterThanOrEqualTo(1);
        assertThat(stack.metrics().operationCount(FtpOperation.EXECUTE)).isGreaterThanOrEqualTo(1);
        assertThat(stack.metrics().executeCount()).isGreaterThanOrEqualTo(1);
    }

    private static <T> void primitive(FtpPool pool, FtpCallback<T> callback) {
        pool.execute(callback);
    }

    private static final class StubProvider implements FtpConnectionFactoryProvider {

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean supports(FtpProtocol protocol) {
            return true;
        }

        @Override
        public FtpConnectionFactory create(FtpConnectionSettings settings, PoolConfiguration configuration) {
            return new FtpConnectionFactory() {

                @Override
                public FtpConnection create() {
                    return new FakeConnection(new FtpConnectionId(1));
                }

                @Override
                public boolean validate(FtpConnection connection) {
                    return !connection.isBroken();
                }

                @Override
                public boolean reset(FtpConnection connection) {
                    return !connection.isBroken();
                }

                @Override
                public void destroy(FtpConnection connection) {
                }
            };
        }
    }

    private static final class StubEngineFactory implements PoolEngineFactory {

        @Override
        public String name() {
            return "fast";
        }

        @Override
        public <T> PoolEngine<T> create(PoolConfiguration config, ResourceFactory<T> rf, PoolStatsRecorder stats) {
            return new OneShotEngine<>(rf, stats);
        }
    }

    private static final class OneShotEngine<T> implements PoolEngine<T> {

        private final ResourceFactory<T> factory;
        private final PoolStatsRecorder recorder;
        private T single;
        private final AtomicBoolean closed = new AtomicBoolean();

        OneShotEngine(ResourceFactory<T> factory, PoolStatsRecorder recorder) {
            this.factory = factory;
            this.recorder = recorder;
        }

        @Override
        public T borrow(Duration timeout) throws Exception {
            if (single == null) {
                single = factory.create();
                recorder.recordCreate();
            }
            recorder.recordBorrow();
            return single;
        }

        @Override
        public void release(T resource) {
            recorder.recordReturn();
        }

        @Override
        public void invalidate(T resource) {
            if (single != null && single == resource) {
                factory.destroy(resource);
                single = null;
            }
        }

        @Override
        public int size() {
            return single == null ? 0 : 1;
        }

        @Override
        public int active() {
            return single == null ? 0 : 1;
        }

        @Override
        public int idle() {
            return 0;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                invalidate(single);
            }
        }
    }

    private static final class FakeConnection implements FtpConnection {

        private final FtpConnectionId id;
        private boolean broken;

        FakeConnection(FtpConnectionId id) {
            this.id = id;
        }

        @Override
        public FtpConnectionId id() {
            return id;
        }

        @Override
        public void changeDirectory(String path) throws FtpException {
        }

        @Override
        public String currentDirectory() throws FtpException {
            return "/";
        }

        @Override
        public InputStream retrieveFileStream(String path) throws FtpException {
            return null;
        }

        @Override
        public OutputStream storeFileStream(String path) throws FtpException {
            return null;
        }

        @Override
        public boolean upload(String path, InputStream input) throws FtpException {
            return true;
        }

        @Override
        public boolean download(String path, OutputStream output) throws FtpException {
            return true;
        }

        @Override
        public boolean delete(String path) throws FtpException {
            return true;
        }

        @Override
        public FtpFile[] listFiles(String path) throws FtpException {
            return new FtpFile[0];
        }

        @Override
        public void completePendingCommand() throws FtpException {
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void markBroken() {
            broken = true;
        }

        @Override
        public boolean isBroken() {
            return broken;
        }
    }
}