package io.ftppool.jmx;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpFile;
import io.ftppool.api.PoolEngine;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.core.FtpPoolEntry;
import io.ftppool.core.FtpPoolImpl;
import io.ftppool.core.FtpPoolStatsImpl;
import io.ftppool.core.PoolConfiguration;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link FtpPoolJmxRegistrar} makes plain-Java pools appear on the platform
 * MBean server via the {@code FtpPoolMBeanRegistrar} SPI, and unregister on close.
 */
class FtpPoolJmxRegistrarTest {

    @Test
    void registerAndCloseUnregistersOnPlatformServer() {
        FtpPoolImpl pool = newPool("registrar-auto");
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = FtpPoolJmx.objectName("registrar-auto");

        AutoCloseable handle = new FtpPoolJmxRegistrar().register(pool);
        try {
            assertThat(server.isRegistered(name)).isTrue();
        } finally {
            closeQuietly(handle);
        }

        assertThat(server.isRegistered(name)).isFalse();
    }

    @Test
    void registrarIsDiscoveredThroughServiceLoader() {
        assertThat(java.util.ServiceLoader.load(io.ftppool.core.FtpPoolMBeanRegistrar.class))
                .anyMatch(r -> r instanceof FtpPoolJmxRegistrar);
    }

    private static void closeQuietly(AutoCloseable handle) {
        try {
            handle.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FtpPoolImpl newPool(String name) {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 1, () -> 0);
        return new FtpPoolImpl(name, PoolConfiguration.createDefault(), new NoopEngine(), new NoopFactory(),
                null, stats);
    }

    private static final class NoopEngine implements PoolEngine<FtpPoolEntry> {
        @Override
        public FtpPoolEntry borrow(Duration timeout) {
            return null;
        }

        @Override
        public void release(FtpPoolEntry resource) {
        }

        @Override
        public void invalidate(FtpPoolEntry resource) {
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public int active() {
            return 0;
        }

        @Override
        public int idle() {
            return 1;
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopFactory implements FtpConnectionFactory {
        @Override
        public FtpConnection create() {
            return new NoopConnection();
        }

        @Override
        public boolean validate(FtpConnection connection) {
            return true;
        }

        @Override
        public boolean reset(FtpConnection connection) {
            return true;
        }

        @Override
        public void destroy(FtpConnection connection) {
        }
    }

    private static final class NoopConnection implements FtpConnection {
        @Override
        public FtpConnectionId id() {
            return new FtpConnectionId(1);
        }

        @Override
        public void changeDirectory(String path) throws FtpException {
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
}
