package io.ftppool.tests;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Embedded Apache MINA FTPS Server (explicit or implicit) backed by a
 * self-signed test keystore, for the FTPS verification suite (spec section 64).
 *
 * <p>The keystore is a test resource ({@code ftps-test-keystore.p12}, password
 * {@code changeit}, CN=localhost) and is only ever used with the client's
 * explicit {@code insecureTrustAll()} opt-in.</p>
 */
final class EmbeddedFtpsServer implements AutoCloseable {

    static final String USERNAME = "tidepool";
    static final String PASSWORD = "tidepool-secret";
    static final String KEYSTORE_PASSWORD = "changeit";

    private final FtpServer server;
    private final int port;
    private final Path homeDir;

    private EmbeddedFtpsServer(FtpServer server, int port, Path homeDir) {
        this.server = server;
        this.port = port;
        this.homeDir = homeDir;
    }

    /** Starts an FTPS server. {@code implicit=true} selects implicit FTPS. */
    static EmbeddedFtpsServer start(boolean implicit) throws Exception {
        Path homeDir = Files.createTempDirectory("ftp-pool-ftps-home-");
        FtpServerFactory serverFactory = new FtpServerFactory();

        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(EmbeddedFtpServer.freePort());
        listenerFactory.setImplicitSsl(implicit);
        listenerFactory.setSslConfiguration(sslConfiguration());
        Listener listener = listenerFactory.createListener();
        serverFactory.addListener("default", listener);

        PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
        Path userFile = homeDir.resolve("users.properties");
        Files.createFile(userFile);
        userManagerFactory.setFile(userFile.toFile());
        userManagerFactory.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        BaseUser user = new BaseUser();
        user.setName(USERNAME);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(homeDir.toString());
        user.setEnabled(true);
        user.setAuthorities(List.of(new WritePermission()));
        userManagerFactory.createUserManager().save(user);
        serverFactory.setUserManager(userManagerFactory.createUserManager());
        serverFactory.setFileSystem(SandboxedFileSystemView::new);

        FtpServer server = serverFactory.createServer();
        server.start();
        return new EmbeddedFtpsServer(server, listener.getPort(), homeDir);
    }

    private static org.apache.ftpserver.ssl.SslConfiguration sslConfiguration() throws Exception {
        SslConfigurationFactory ssl = new SslConfigurationFactory();
        ssl.setKeystoreFile(keystoreFile());
        ssl.setKeystoreType("PKCS12");
        ssl.setKeystorePassword(KEYSTORE_PASSWORD);
        ssl.setKeyPassword(KEYSTORE_PASSWORD);
        ssl.setKeyAlias("ftptest");
        return ssl.createSslConfiguration();
    }

    private static File keystoreFile() throws Exception {
        URL resource = EmbeddedFtpsServer.class.getClassLoader().getResource("ftps-test-keystore.p12");
        if (resource == null) {
            throw new IllegalStateException("Test keystore ftps-test-keystore.p12 not found on classpath");
        }
        return new File(resource.toURI());
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
