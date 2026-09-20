package io.ftppool.tests;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Embedded Apache MINA FTP Server for integration tests (spec section 59).
 *
 * <p>Each instance binds to an ephemeral free port and provisions one user
 * ({@link #USERNAME}/{@link #PASSWORD}) whose home directory is the temp dir.
 * {@code stop()} is safe to call from a test to simulate a server crash.</p>
 */
final class EmbeddedFtpServer implements AutoCloseable {

    static final String USERNAME = "tidepool";
    static final String PASSWORD = "tidepool-secret";

    private final FtpServer server;
    private final int port;
    private final Path homeDir;

    private EmbeddedFtpServer(FtpServer server, int port, Path homeDir) {
        this.server = server;
        this.port = port;
        this.homeDir = homeDir;
    }

    static EmbeddedFtpServer start() throws Exception {
        Path homeDir = Files.createTempDirectory("ftp-pool-home-");
        FtpServerFactory serverFactory = new FtpServerFactory();
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(freePort());
        // Concurrency suites borrow 100/1000 threads at a time: give the NIO
        // acceptor a roomy backlog and the FTP engine generous connection
        // limits so the tests exercise the pool, not the embedded server.
        Listener listener = listenerFactory.createListener();
        serverFactory.addListener("default", listener);

        ConnectionConfigFactory connectionConfigFactory = new ConnectionConfigFactory();
        connectionConfigFactory.setMaxLogins(500);
        connectionConfigFactory.setMaxThreads(300);
        serverFactory.setConnectionConfig(connectionConfigFactory.createConnectionConfig());

        PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
        Path userFile = homeDir.resolve("users.properties");
        // MINA's PropertiesUserManager.init() fails fast if the file is missing,
        // so it must exist *before* the first createUserManager() call.
        Files.createFile(userFile);
        userManagerFactory.setFile(userFile.toFile());
        userManagerFactory.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        BaseUser user = new BaseUser();
        user.setName(USERNAME);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(homeDir.toString());
        user.setEnabled(true);
        // MINA's BaseUser.authorize() returns null without any authorities,
        // which makes every write check (isWritable) fail with 550. Grant write.
        user.setAuthorities(List.of(new WritePermission()));
        userManagerFactory.createUserManager().save(user);
        serverFactory.setUserManager(userManagerFactory.createUserManager());
        // Root the whole virtual tree at the temp home dir (see SandboxedFileSystemView):
        // MINA's default view maps absolute paths onto the host FS root and 550s.
        serverFactory.setFileSystem(SandboxedFileSystemView::new);

        FtpServer server = serverFactory.createServer();
        server.start();
        return new EmbeddedFtpServer(server, listener.getPort(), homeDir);
    }

    /** Ephemeral TCP port reservation: requested before the server binds so the actual port is known. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    int port() {
        return port;
    }

    Path homeDir() {
        return homeDir;
    }

    void stop() {
        server.stop();
    }

    @Override
    public void close() {
        stop();
    }
}