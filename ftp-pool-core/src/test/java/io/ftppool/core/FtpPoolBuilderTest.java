package io.ftppool.core;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.api.PoolEngine;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FtpPoolBuilderTest {

    @Test
    void buildsWithInjectedServiceHooks() throws Exception {
        PoolEngineFactory engineFactory = new PoolEngineFactory() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public <T> PoolEngine<T> create(PoolConfiguration config, ResourceFactory<T> rf, PoolStatsRecorder stats) {
                return new ImmediatelyReturnEngine(rf);
            }
        };
        FtpConnectionFactoryProvider provider = new StubProvider();

        FtpPool pool = FtpPoolBuilder.builder()
                .host("ftp.example.com")
                .username("user")
                .password("pw")
                .minIdle(0)
                .maxSize(4)
                .connectionFactoryProvider(provider)
                .engineFactory(engineFactory)
                .build();

        assertThat(pool.stats().total()).isZero();
        pool.close();
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void sslConfigReachesConnectionFactory() throws Exception {
        StubProvider provider = new StubProvider();
        FtpSslConfig ssl = FtpSslConfig.insecureTrustAll();

        FtpPool pool = FtpPoolBuilder.builder()
                .host("ftp.example.com")
                .username("user")
                .password("pw")
                .minIdle(0)
                .maxSize(4)
                .sslConnection(ssl)
                .connectionFactoryProvider(provider)
                .engineFactory(engineFactoryStub())
                .build();
        pool.close();

        assertThat(provider.lastSettings.ssl()).isEqualTo(ssl);
    }

    @Test
    void missingHostIsRejected() {
        Throwable thrown = catchThrowable(() -> FtpPoolBuilder.builder().build());

        assertThat(thrown)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("host");
    }

    @Test
    void profileDefaultsToHybrid() {
        FtpPoolBuilder fast = FtpPoolProfiles.fast();
        FtpPoolBuilder hybrid = FtpPoolProfiles.hybrid();
        FtpPoolBuilder commons = FtpPoolProfiles.commons();
        FtpPoolBuilder monitor = FtpPoolProfiles.monitor();

        assertThat(fast).isNotNull();
        assertThat(hybrid).isNotNull();
        assertThat(commons).isNotNull();
        assertThat(monitor).isNotNull();
    }

    private static final class StubProvider implements FtpConnectionFactoryProvider {

        private FtpConnectionSettings lastSettings;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean supports(FtpProtocol protocol) {
            return protocol == FtpProtocol.FTP;
        }

        @Override
        public FtpConnectionFactory create(FtpConnectionSettings settings, PoolConfiguration configuration) {
            this.lastSettings = settings;
            return new StubFactory();
        }
    }

    private static PoolEngineFactory engineFactoryStub() {
        return new PoolEngineFactory() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public <T> PoolEngine<T> create(PoolConfiguration config, ResourceFactory<T> rf, PoolStatsRecorder stats) {
                return new ImmediatelyReturnEngine(rf);
            }
        };
    }

    private static final class StubFactory implements FtpConnectionFactory {

        @Override
        public FtpConnection create() {
            return new StubConnection(new FtpConnectionId(1));
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
    }

    private static final class StubConnection implements FtpConnection {

        private final FtpConnectionId id;

        StubConnection(FtpConnectionId id) {
            this.id = id;
        }

        @Override
        public FtpConnectionId id() {
            return id;
        }

        @Override
        public void changeDirectory(String path) {
        }

        @Override
        public String currentDirectory() {
            return "/";
        }

        @Override
        public InputStream retrieveFileStream(String path) {
            return null;
        }

        @Override
        public OutputStream storeFileStream(String path) {
            return null;
        }

        @Override
        public boolean upload(String path, InputStream input) {
            return true;
        }

        @Override
        public boolean download(String path, OutputStream output) {
            return true;
        }

        @Override
        public boolean delete(String path) {
            return true;
        }

        @Override
        public FtpFile[] listFiles(String path) {
            return new FtpFile[0];
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

    private static final class ImmediatelyReturnEngine<T> implements PoolEngine<T> {

        private final ResourceFactory<T> factory;
        private T single;

        ImmediatelyReturnEngine(ResourceFactory<T> factory) {
            this.factory = factory;
        }

        @Override
        public T borrow(Duration timeout) throws Exception {
            if (single == null) {
                single = factory.create();
            }
            return single;
        }

        @Override
        public void release(T resource) {
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
            invalidate(single);
        }
    }
}