package io.ftppool.benchmark;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.core.FtpConnectionFactoryProvider;
import io.ftppool.core.FtpConnectionSettings;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.ObservabilityType;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.engine.fast.FastPoolEngineFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import io.ftppool.adapter.CommonsNetFtpConnectionFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared fixtures for the benchmark suite (spec §56).
 *
 * <ul>
 *   <li>{@link #inMemoryPool(int, int, boolean)} — a real {@link FtpPool}
 *       (Fast engine) over an in-memory connection factory: measures pool
 *       mechanics only, no sockets.</li>
 *   <li>{@link #realServer()} — an embedded MINA FTP server on localhost for
 *       connection creation / validation / transfer benchmarks.</li>
 * </ul>
 */
final class BenchSupport {

    static final String HOST = "127.0.0.1";
    static final String USERNAME = "bench";
    static final String PASSWORD = "benchpass";

    /** Running embedded FTP server plus the loopback port it bound to. */
    record BenchServer(FtpServer server, int port) {
    }

    private BenchSupport() {
    }

    /** Real pool over a fake, socket-free connection factory. */
    static FtpPool inMemoryPool(int minIdle, int maxSize, boolean brokenOnCreate) {
        return inMemoryPool(new FastPoolEngineFactory(), minIdle, maxSize, brokenOnCreate);
    }

    /** Real pool over a fake factory, with an explicit engine (Fast / Commons). */
    static FtpPool inMemoryPool(io.ftppool.core.PoolEngineFactory engineFactory,
                                int minIdle, int maxSize, boolean brokenOnCreate) {
        return FtpPoolBuilder.builder()
                .host("fake")
                .username(USERNAME)
                .password(PASSWORD)
                .minIdle(minIdle)
                .maxSize(maxSize)
                .observability(ObservabilityType.NONE)
                .connectionFactoryProvider(new FakeProvider(brokenOnCreate))
                .engineFactory(engineFactory)
                .build();
    }

    /** Raw socket-free factory for the No-Pool and Apache-Commons-Pool baselines. */
    static FtpConnectionFactory inMemoryConnectionFactory() {
        return new FakeFactory(false);
    }

    /** Pool over the embedded MINA FTP server (loopback). */
    static FtpPool serverPool(BenchServer bind, int minIdle, int maxSize) {
        int port = bind.port();
        return FtpPoolBuilder.builder()
                .host(HOST)
                .port(port)
                .username(USERNAME)
                .password(PASSWORD)
                .protocol(FtpProtocol.FTP)
                .minIdle(minIdle)
                .maxSize(maxSize)
                .observability(ObservabilityType.NONE)
                .engineFactory(new FastPoolEngineFactory())
                .build();
    }

    /** Direct Commons Net factory standing on the embedded server. */
    static FtpConnectionFactory commonsFactory(BenchServer bind) {
        int port = bind.port();
        FtpConnectionSettings settings = new FtpConnectionSettings(
                HOST, port, USERNAME, PASSWORD, FtpProtocol.FTP, "UTF-8");
        PoolConfiguration config = PoolConfiguration.createDefault();
        return new CommonsNetFtpConnectionFactory(settings, config);
    }

    /** Embedded MINA FTP server on an ephemeral loopback port. */
    static BenchServer startServer() {
        return startServer(freePort());
    }

    static BenchServer startServer(int port) {
        try {
            Path home = Files.createTempDirectory("ftp-pool-bench-");
            FtpServerFactory factory = new FtpServerFactory();
            ListenerFactory listener = new ListenerFactory();
            listener.setPort(port);
            factory.addListener("default", listener.createListener());

            PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
            userManagerFactory.setAdminName(USERNAME);
            UserManager userManager = userManagerFactory.createUserManager();
            BaseUser user = new BaseUser();
            user.setName(USERNAME);
            user.setPassword(PASSWORD);
            user.setHomeDirectory(home.toString());
            user.setAuthorities(List.of(new WritePermission()));
            userManager.save(user);
            factory.setUserManager(userManager);

            FtpServer server = factory.createServer();
            server.start();
            return new BenchServer(server, port);
        } catch (org.apache.ftpserver.ftplet.FtpException | IOException e) {
            throw new IllegalStateException("Unable to start embedded FTP server", e);
        }
    }

    static void stopServer(BenchServer bind) {
        stopServer(bind.server());
    }

    static void stopServer(FtpServer server) {
        if (server != null) {
            server.stop();
            try {
                Files.walk(Paths.get(System.getProperty("java.io.tmpdir")))
                        .filter(path -> path.toString().startsWith("ftp-pool-bench-"))
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // best effort
                            }
                        });
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to probe a free port", e);
        }
    }

    // ------------------------- fake in-memory connection -------------------------

    static final class FakeProvider implements FtpConnectionFactoryProvider {

        private final boolean brokenOnCreate;

        FakeProvider(boolean brokenOnCreate) {
            this.brokenOnCreate = brokenOnCreate;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean supports(FtpProtocol protocol) {
            return true;
        }

        @Override
        public FtpConnectionFactory create(FtpConnectionSettings settings, PoolConfiguration configuration) {
            return new FakeFactory(brokenOnCreate);
        }
    }

    static final class FakeFactory implements FtpConnectionFactory {

        private static final AtomicLong IDS = new AtomicLong();
        private final boolean broken;
        private final FtpConnectionId id = new FtpConnectionId(IDS.incrementAndGet());

        FakeFactory(boolean broken) {
            this.broken = broken;
        }

        @Override
        public FtpConnection create() {
            return new FakeConnection(id, broken);
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

    private static final class FakeConnection implements FtpConnection {

        private final FtpConnectionId id;
        private final boolean broken;

        FakeConnection(FtpConnectionId id, boolean broken) {
            this.id = id;
            this.broken = broken;
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
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream storeFileStream(String path) {
            return OutputStream.nullOutputStream();
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
            return broken;
        }
    }
}